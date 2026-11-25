<template>
  <section ref="mindmapPanel" class="mindmap-panel" :class="{ 'is-fullscreen': isFullscreen }">
    <header class="mindmap-head">
      <div class="mindmap-title-block">
        <div class="mindmap-title-line">
          <h3>指标体系层级全景思维导图（全量{{ stats.metrics }}项监控指标全景树）</h3>
          <button type="button" class="board-toggle" @click="boardCollapsed = !boardCollapsed">
            <i :class="boardCollapsed ? 'collapsed' : 'expanded'"></i>
            {{ boardCollapsed ? '全景树已折叠' : '全量拓扑已展开' }} · {{ stats.metrics }}项 {{ boardCollapsed ? '展开' : '收起' }}
          </button>
        </div>
        <small>系统 → 大类 → 小类 → 指标；点击分支展开/收起，点击指标查看详情</small>
      </div>
      <div class="mindmap-actions">
        <el-input v-model="keyword" clearable placeholder="搜索指标、编码或定义" class="mindmap-search" />
        <div class="zoom-controls" aria-label="画布缩放">
          <el-button size="small" title="缩小" @click="zoomOut">－</el-button>
          <span class="zoom-value">{{ zoomPercent }}%</span>
          <el-button size="small" title="放大" @click="zoomIn">＋</el-button>
          <el-button size="small" @click="fitToWidth">适应</el-button>
          <el-button size="small" @click="resetView">重置</el-button>
        </div>
        <el-button size="small" @click="expandToSubcategory">展开到小类</el-button>
        <el-button size="small" @click="expandAll">展开全部</el-button>
        <el-button size="small" @click="collapseAll">收起全部</el-button>
      </div>
    </header>

    <div class="mindmap-legend">
      <div class="legend-health">
        <span><i class="dot normal"></i>正常 {{ healthStats.normal }}</span>
        <span><i class="dot warning"></i>关注 {{ healthStats.warning }}</span>
        <span><i class="dot danger"></i>异常 {{ healthStats.danger }}</span>
        <span><i class="dot unknown"></i>待计算 {{ healthStats.unknown }}</span>
      </div>
      <span class="mindmap-path">当前授权范围 · 系统 {{ stats.systems }} → 大类 {{ stats.categories }} → 小类 {{ stats.subcategories }} → 指标 {{ stats.metrics }}</span>
    </div>

    <div
      v-if="!boardCollapsed && visibleTree.length"
      ref="viewport"
      class="mindmap-viewport"
      :class="{ dragging }"
      @pointerdown="startDrag"
      @pointermove="moveDrag"
      @pointerup="endDrag"
      @pointerleave="endDrag"
      @pointercancel="endDrag"
      @wheel="onWheel"
    >
      <button type="button" class="mindmap-fullscreen" title="全屏查看思维导图" @pointerdown.stop @click.stop="toggleFullscreen">
        <span class="fullscreen-icon">⛶</span>{{ isFullscreen ? '退出全屏' : '全屏' }}
      </button>
      <div ref="canvas" class="mindmap-canvas" :style="canvasStyle">
        <div v-for="root in visibleTree" :key="root.key" class="mind-root">
          <MindMapNode
            :node="root"
            :expanded-keys="expandedKeys"
            :keyword="keyword"
            @toggle="toggle"
            @metric-detail="metric => emit('metric-detail', metric)"
          />
        </div>
      </div>
      <span class="viewport-hint">拖拽空白处移动画布 · 使用上方缩放</span>
    </div>
    <el-empty v-else-if="!boardCollapsed" :description="keyword ? '没有匹配的指标或分支' : '当前授权范围内没有指标'" :image-size="80" />

    <div v-if="rows.length" class="catalog-table-block">
      <div class="catalog-table-head">
        <div><b>指标台账</b><small>已启用 {{ enabledCount }} 项 / 共 {{ rows.length }} 项指标</small></div>
        <div class="catalog-table-tools">
          <el-input v-model="tableKeyword" clearable placeholder="按名称、编码或定义搜索" />
          <el-button size="small" @click="tableKeyword = ''">重置</el-button>
        </div>
      </div>
      <el-table v-loading="healthLoading" :data="pagedTableRows" stripe border size="small" max-height="240" class="catalog-table" empty-text="当前筛选条件下没有指标">
        <el-table-column prop="systemName" label="系统" width="105" />
        <el-table-column prop="categoryName" label="指标大类" width="112" />
        <el-table-column prop="subCategoryName" label="指标小类" width="112" />
        <el-table-column prop="metricName" label="指标名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="metricCode" label="指标编码" width="145" show-overflow-tooltip />
        <el-table-column prop="metricType" label="类型" width="95"><template #default="{ row }"><MetricTypeTag :type="row.metricType" /></template></el-table-column>
        <el-table-column prop="metricDefinition" label="指标定义 / 业务含义" min-width="230" show-overflow-tooltip />
        <el-table-column label="采集频率" width="92"><template #default="{ row }">{{ row.collectFrequency || '-' }}</template></el-table-column>
        <el-table-column label="阈值" width="100"><template #default="{ row }">{{ thresholdText(row) }}</template></el-table-column>
        <el-table-column label="健康状态" width="100"><template #default="{ row }"><MetricHealthStatus :score="healthById.get(Number(row.id))?.healthScore" show-score /></template></el-table-column>
        <el-table-column label="启用" width="70"><template #default="{ row }"><el-tag size="small" :type="Number(row.status) === 1 ? 'success' : 'info'">{{ Number(row.status) === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
      </el-table>
      <div class="catalog-pagination">
        <span>筛选 {{ filteredTableRows.length }} 项 · 已启用 {{ enabledCount }} 项</span>
        <el-pagination
          v-model:current-page="tablePage"
          v-model:page-size="tablePageSize"
          :page-sizes="[10, 20, 50]"
          :total="filteredTableRows.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>
    </div>
  </section>
</template>

<script setup>
import { scoreLevel } from '@/utils/healthThresholds'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MindMapNode from './MindMapNode.vue'
import MetricTypeTag from './MetricTypeTag.vue'
import MetricHealthStatus from './MetricHealthStatus.vue'
import StatusTag from './ui/StatusTag.vue'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  healthCards: { type: Array, default: () => [] },
  healthLoading: Boolean
})
const emit = defineEmits(['metric-detail'])
const keyword = ref('')
const boardCollapsed = ref(false)
const tableKeyword = ref(''), tablePage = ref(1), tablePageSize = ref(10)
const expandedKeys = ref(new Set())
const viewport = ref(), canvas = ref(), mindmapPanel = ref(), isFullscreen = ref(false)
const zoom = ref(1), pan = ref({ x: 0, y: 0 }), dragging = ref(false), dragStart = ref({ x: 0, y: 0 })
async function toggleFullscreen () {
  if (document.fullscreenElement) {
    await document.exitFullscreen().catch(() => {})
    return
  }
  if (mindmapPanel.value?.requestFullscreen) {
    try {
      await mindmapPanel.value.requestFullscreen()
      isFullscreen.value = true
      await nextTick()
      fitToWidth()
      return
    } catch {}
  }
  isFullscreen.value = !isFullscreen.value
  await nextTick()
  if (isFullscreen.value) fitToWidth()
}
function syncFullscreen () {
  isFullscreen.value = document.fullscreenElement === mindmapPanel.value
  if (isFullscreen.value) nextTick(() => fitToWidth())
}
onMounted(() => document.addEventListener('fullscreenchange', syncFullscreen))
onBeforeUnmount(() => document.removeEventListener('fullscreenchange', syncFullscreen))

const healthById = computed(() => {
  const map = new Map()
  props.healthCards.forEach(item => map.set(Number(item.metricId), item))
  return map
})
const tree = computed(() => {
  const systems = new Map()
  for (const row of props.rows || []) {
    const systemName = row.systemName || '未归属系统'
    let system = systems.get(systemName)
    if (!system) {
      system = { key: `system:${systemName}`, type: 'system', label: systemName, children: [] }
      systems.set(systemName, system)
    }
    const categoryName = row.categoryName || '未分类'
    let category = system.children.find(node => node.label === categoryName)
    if (!category) {
      category = { key: `${system.key}/category:${categoryName}`, type: 'category', label: categoryName, children: [] }
      system.children.push(category)
    }
    const subCategoryName = row.subCategoryName || '未分小类'
    let subCategory = category.children.find(node => node.label === subCategoryName)
    if (!subCategory) {
      subCategory = { key: `${category.key}/subcategory:${subCategoryName}`, type: 'subcategory', label: subCategoryName, children: [] }
      category.children.push(subCategory)
    }
    subCategory.children.push({
      key: `metric:${row.id}`,
      type: 'metric',
      label: row.metricName,
      metric: row,
      health: healthById.value.get(Number(row.id)),
      children: []
    })
  }
  const roots = [...systems.values()]
  const finalize = node => {
    node.children.sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
    node.children.forEach(finalize)
    node.childCount = node.children.length
    node.metricCount = node.type === 'metric' ? 1 : node.children.reduce((total, child) => total + child.metricCount, 0)
    return node
  }
  roots.forEach(finalize)
  roots.sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
  return roots
})
const stats = computed(() => {
  const out = { systems: tree.value.length, categories: 0, subcategories: 0, metrics: 0 }
  const walk = node => {
    if (node.type === 'category') out.categories++
    if (node.type === 'subcategory') out.subcategories++
    if (node.type === 'metric') out.metrics++
    node.children.forEach(walk)
  }
  tree.value.forEach(walk)
  return out
})
const healthStats = computed(() => {
  const out = { normal: 0, warning: 0, danger: 0, unknown: 0 }
  props.healthCards.forEach(item => {
    if (item.healthScore == null) out.unknown++
    else if (scoreLevel(item.healthScore) === 'danger') out.danger++
    else if (scoreLevel(item.healthScore) === 'warning') out.warning++
    else out.normal++
  })
  return out
})
const enabledCount = computed(() => (props.rows || []).filter(row => Number(row.status) === 1).length)
const filteredTableRows = computed(() => {
  const query = tableKeyword.value.trim().toLowerCase()
  if (!query) return props.rows || []
  return (props.rows || []).filter(row => [row.systemName, row.categoryName, row.subCategoryName, row.metricName, row.metricCode, row.metricDefinition].some(value => String(value || '').toLowerCase().includes(query)))
})
const pagedTableRows = computed(() => {
  const start = (tablePage.value - 1) * tablePageSize.value
  return filteredTableRows.value.slice(start, start + tablePageSize.value)
})
const visibleTree = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return tree.value
  const filter = node => {
    const selfMatch = [node.label, node.metric?.metricCode, node.metric?.metricDefinition].some(value => String(value || '').toLowerCase().includes(query))
    const children = (node.children || []).map(filter).filter(Boolean)
    if (!selfMatch && !children.length) return null
    return { ...node, children: selfMatch && !children.length ? node.children : children, forceOpen: true }
  }
  return tree.value.map(filter).filter(Boolean)
})
const allBranchKeys = computed(() => {
  const keys = []
  const walk = node => {
    if (node.children.length) keys.push(node.key)
    node.children.forEach(walk)
  }
  tree.value.forEach(walk)
  return keys
})
const subcategoryKeys = computed(() => {
  const keys = []
  tree.value.forEach(system => {
    keys.push(system.key)
    system.children.forEach(category => keys.push(category.key))
  })
  return keys
})
const signature = computed(() => (props.rows || []).map(row => row.id).join('|'))
const zoomPercent = computed(() => Math.round(zoom.value * 100))
const canvasStyle = computed(() => ({ transform: `translate(${pan.value.x}px, ${pan.value.y}px) scale(${zoom.value})`, transformOrigin: 'top left' }))

watch(signature, async () => {
  expandedKeys.value = new Set(allBranchKeys.value)
  tablePage.value = 1
  await nextTick()
  fitToWidth()
}, { immediate: true })
watch([tableKeyword, tablePageSize], () => { tablePage.value = 1 })

function thresholdText (row) {
  return row?.alarmThreshold == null ? '未配置' : `${row.alarmThreshold}${row.unit ? ` ${row.unit}` : ''}`
}
function healthStatus (row) {
  const item = healthById.value.get(Number(row.id))
  return item?.healthScore == null ? 'unknown' : scoreLevel(item.healthScore) === 'danger' ? 'danger' : scoreLevel(item.healthScore) === 'warning' ? 'warning' : 'normal'
}
function healthLabel (row) {
  const status = healthStatus(row)
  return status === 'danger' ? '异常' : status === 'warning' ? '关注' : status === 'unknown' ? '待计算' : '正常'
}

function clampZoom (value) { return Math.min(1.6, Math.max(0.5, Math.round(value * 100) / 100)) }
function zoomIn () { zoom.value = clampZoom(zoom.value + 0.1) }
function zoomOut () { zoom.value = clampZoom(zoom.value - 0.1) }
function resetView () { zoom.value = 1; pan.value = { x: 0, y: 0 } }
function fitToWidth () {
  const box = viewport.value, content = canvas.value
  if (!box || !content) return
  const available = Math.max(240, box.clientWidth - 40)
  const width = content.offsetWidth || 0
  if (!width) return
  zoom.value = Math.min(1, Math.max(0.55, Math.round((available / width) * 100) / 100))
  pan.value = { x: 16, y: 12 }
}
function startDrag (event) {
  if (event.button !== 0) return
  const target = event.target
  if (target?.closest?.('button, input, .el-input, .el-button, .metric-chip, .mind-card')) return
  dragging.value = true
  dragStart.value = { x: event.clientX - pan.value.x, y: event.clientY - pan.value.y }
  viewport.value?.setPointerCapture?.(event.pointerId)
}
function moveDrag (event) {
  if (!dragging.value) return
  pan.value = { x: event.clientX - dragStart.value.x, y: event.clientY - dragStart.value.y }
}
function endDrag (event) {
  if (!dragging.value) return
  dragging.value = false
  try { viewport.value?.releasePointerCapture?.(event.pointerId) } catch {}
}
function onWheel (event) {
  if (!event.ctrlKey) return
  event.preventDefault()
  zoom.value = clampZoom(zoom.value + (event.deltaY > 0 ? -0.1 : 0.1))
}

function toggle (key) {
  const next = new Set(expandedKeys.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedKeys.value = next
}
function expandAll () { expandedKeys.value = new Set(allBranchKeys.value) }
function collapseAll () { expandedKeys.value = new Set() }
function expandToSubcategory () { expandedKeys.value = new Set(subcategoryKeys.value) }
</script>

<style scoped>
.mindmap-panel { min-width: 0; width: 100%; padding: 16px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius-lg); background: var(--ui-surface); box-shadow: var(--metric-shadow-xs); }
.mindmap-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 10px; }
.mindmap-title-block { min-width: 0; }
.mindmap-title-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.mindmap-head h3 { margin: 0; color: var(--metric-text); font-size: 15px; }
.board-toggle { display: inline-flex; align-items: center; gap: 5px; padding: 3px 9px; border: 1px solid #a7f3d0; border-radius: 999px; background: #ecfdf5; color: #047857; font-size: 10px; cursor: pointer; white-space: nowrap; }
.board-toggle:hover { background: #d1fae5; }
.board-toggle i { width: 6px; height: 6px; border-radius: 50%; background: #10b981; box-shadow: 0 0 0 3px #d1fae5; }
.board-toggle i.collapsed { background: var(--metric-text-muted); box-shadow: 0 0 0 3px #f1f5f9; }
.mindmap-head small { display: block; margin-top: 4px; color: var(--metric-text-muted); font-size: 10px; }
.mindmap-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
.mindmap-search { width: 220px; }
.zoom-controls { display: inline-flex; align-items: center; gap: 3px; padding: 1px 4px; border: 1px solid var(--metric-border); border-radius: 7px; background: var(--metric-surface-soft); }
.zoom-value { min-width: 38px; color: var(--metric-text-secondary); font-family: var(--metric-font-mono); font-size: 10px; text-align: center; }
.mindmap-legend { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 8px 10px; margin-bottom: 10px; border-radius: var(--metric-radius-sm); background: var(--metric-surface-soft); color: var(--metric-text-muted); font-size: 10px; }
.legend-health { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.legend-health span { display: inline-flex; align-items: center; gap: 5px; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; }
.dot.normal { background: var(--metric-success); box-shadow: 0 0 0 3px var(--metric-success-soft); }
.dot.warning { background: var(--metric-warning); box-shadow: 0 0 0 3px var(--metric-warning-soft); }
.dot.danger { background: var(--metric-danger); box-shadow: 0 0 0 3px var(--metric-danger-soft); }
.dot.unknown { background: var(--metric-text-muted); box-shadow: 0 0 0 3px #f1f5f9; }
.mindmap-path { color: var(--metric-text-secondary); font-family: var(--metric-font-mono); font-size: 10px; }
.mindmap-viewport {
  position: relative;
  height: clamp(420px, 58vh, 760px);
  overflow: hidden;
  border: 1px solid var(--metric-border);
  border-radius: var(--metric-radius);
  background-color: var(--metric-surface-soft);
  background-image: radial-gradient(circle at 1px 1px, #dbe5f0 1px, transparent 0);
  background-size: 18px 18px;
  cursor: grab;
  touch-action: none;
  user-select: none;
}
.mindmap-fullscreen { position: absolute; top: 10px; right: 10px; z-index: 4; display: inline-flex; align-items: center; gap: 4px; padding: 5px 9px; border: 1px solid var(--metric-primary-border); border-radius: 7px; background: rgb(255 255 255 / .94); color: var(--metric-primary-hover); font-size: 10px; cursor: pointer; box-shadow: var(--metric-shadow-xs); }
.mindmap-fullscreen:hover { background: var(--metric-primary-soft); }
.fullscreen-icon { font-size: 13px; line-height: 1; }
.mindmap-viewport.dragging { cursor: grabbing; }
.mindmap-panel.is-fullscreen { position: fixed; inset: 0; z-index: 2000; display: flex; flex-direction: column; width: 100vw; height: 100vh; padding: 16px; border-radius: 0; background: var(--ui-surface); overflow: hidden; }
.mindmap-panel:fullscreen { display: flex; flex-direction: column; width: 100vw; height: 100vh; padding: 16px; border-radius: 0; background: var(--ui-surface); overflow: hidden; }
.mindmap-panel.is-fullscreen .mindmap-viewport, .mindmap-panel:fullscreen .mindmap-viewport { flex: 1; height: auto; min-height: 0; }
.mindmap-panel.is-fullscreen .catalog-table-block, .mindmap-panel:fullscreen .catalog-table-block { display: none; }
.mindmap-panel.is-fullscreen .mindmap-legend, .mindmap-panel:fullscreen .mindmap-legend { flex: none; }
.mindmap-canvas { display: flex; flex-direction: column; gap: 18px; width: max-content; min-width: 100%; padding: 18px 12px 22px; will-change: transform; }
.mindmap-viewport:not(.dragging) .mindmap-canvas { transition: transform .16s ease; }
.viewport-hint { position: absolute; right: 12px; bottom: 10px; padding: 3px 8px; border-radius: 999px; background: rgb(255 255 255 / .86); border: 1px solid var(--metric-border); color: var(--metric-text-muted); font-size: 9px; pointer-events: none; }
.mind-root { display: flex; align-items: flex-start; }
.catalog-table-block { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--metric-border); }
.catalog-table-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.catalog-table-head b { display: block; color: var(--metric-text); font-size: 13px; }
.catalog-table-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 10px; }
.catalog-table-tools { display: flex; align-items: center; gap: 6px; }
.catalog-table-tools .el-input { width: 240px; }
.catalog-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: var(--metric-text-muted); font-size: 10px; }
@media (max-width: 1500px) {
  .mindmap-head { flex-direction: column; }
  .mindmap-title-block { width: 100%; }
  .mindmap-actions { width: 100%; justify-content: flex-start; }
  .mindmap-search { width: min(100%, 280px); }
  .catalog-table-head { flex-direction: column; }
  .catalog-table-tools { width: 100%; }
  .catalog-table-tools .el-input { width: 100%; }
  .catalog-pagination { align-items: flex-start; flex-direction: column; }
}
@media (max-width: 900px) {
  .mindmap-head { flex-direction: column; }
  .mindmap-actions { width: 100%; justify-content: flex-start; }
  .mindmap-search { width: 100%; }
  .zoom-controls { width: auto; }
  .mindmap-legend { align-items: flex-start; flex-direction: column; }
  .catalog-table-head { flex-direction: column; }
  .catalog-table-tools { width: 100%; }
  .catalog-table-tools .el-input { width: 100%; }
  .catalog-pagination { align-items: flex-start; flex-direction: column; }
}
</style>
