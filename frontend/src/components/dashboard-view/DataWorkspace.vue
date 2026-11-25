<template>
  <section class="panel data-workspace">
    <div class="workspace-head">
      <div><h3>数据汇聚工作台</h3><small>{{ sourceName ? `当前系统：${sourceName} · ` : '' }}仅展示当前专屏授权范围内的系统、大类、小类和明细</small></div>
      <div class="workspace-head-tags"><el-tag size="small" effect="plain">授权维度 {{ dimensions.length }}</el-tag><el-tag v-if="meta?.metricCount != null" size="small" type="success" effect="plain">授权指标 {{ meta.metricCount }}</el-tag></div>
    </div>

    <el-tabs v-if="dimensions.length" v-model="activeCategory" class="workspace-tabs" @tab-change="handleCategoryChange">
      <el-tab-pane v-for="dim in dimensions" :key="dim.name" :label="dim.name" :name="dim.name" />
    </el-tabs>

    <div v-if="dimensions.length" class="workspace-body">
      <aside class="subcategory-panel">
        <div class="subcategory-head"><span>指标小类</span><small>{{ selectedDimension?.subcategories?.length || 0 }}</small></div>
        <button v-for="sub in selectedDimension?.subcategories || []" :key="sub.name" type="button" class="subcategory-item" :class="{ active: activeSubcategory === sub.name }" @click="selectSubcategory(sub)">
          <span>{{ sub.name }}</span><small>{{ sub.metricCount }} 项</small>
        </button>
      </aside>
      <div class="workspace-detail">
        <div class="detail-head"><div><b>{{ activeCategory }} / {{ activeSubcategory || '未选择小类' }}</b><small>{{ table ? `来源表：${table}` : '当前维度未配置原始明细表' }}</small></div><span v-if="notice" class="detail-notice">{{ notice }}</span></div>
        <div class="detail-toolbar">
          <el-input v-model="keyword" clearable placeholder="搜索当前页数据" />
          <el-button type="primary" @click="applyQuery">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <div class="toolbar-spacer"></div>
          <!-- 明细排序只切换当前页展示顺序，不重新请求后端、不改变分页口径 -->
          <el-radio-group v-model="sortOrder" size="small" class="sort-switch" title="只调整当前页明细的展示顺序，不重新请求，也不做全量排序">
            <el-radio-button value="desc">倒序</el-radio-button>
            <el-radio-button value="asc">正序</el-radio-button>
          </el-radio-group>
          <el-popover v-model:visible="columnPopover" placement="bottom-end" :width="280" trigger="click">
            <template #reference><el-button><el-icon><Setting /></el-icon>列设置 ({{ visibleColumns.length }}/{{ columnOptions.length }})</el-button></template>
            <div class="column-tip">已选 {{ visibleColumns.length }} / {{ columnOptions.length }} 列</div>
            <el-checkbox-group v-model="visibleColumns" class="column-list" @change="handleColumnChange">
              <el-checkbox v-for="col in columnOptions" :key="col.prop" :value="col.prop">{{ col.label }}</el-checkbox>
            </el-checkbox-group>
          </el-popover>
        </div>
        <el-table v-loading="loading" :data="sortedRows" border stripe size="small" max-height="460" class="workspace-table" empty-text="当前维度暂无可展示的数据明细">
          <el-table-column v-for="col in visibleColumnDefs" :key="col.prop" :prop="col.prop" :label="col.label" :min-width="col.width" show-overflow-tooltip>
            <template #default="{ row }">{{ cell(row[col.prop]) }}</template>
          </el-table-column>
        </el-table>
        <div class="workspace-pager"><span>当前页 {{ sortedRows.length }} 条 · 共 {{ total }} 条</span><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[10, 20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next, jumper" background @current-change="loadRows" @size-change="onSizeChange" /></div>
      </div>
    </div>
    <el-empty v-else :description="sourceName ? '当前系统在授权范围内没有可展示的数据维度' : '当前专屏没有授权数据维度'" :image-size="76" />
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { screenApi, windowParams } from './api'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  viewCode: { type: String, default: '' },
  days: { type: Number, default: 7 },
  // 自定义时间区间 [start,end]；为空时按 days 预设。明细行本身不做时间过滤，但目录/权限上下文保持一致。
  range: { type: Array, default: null },
  previewUser: { type: Number, default: null },
  supervisorId: { type: Number, default: null },
  // 岗位（面向人员）过滤：与专屏顶部下拉一致，明细不能越过滤条件
  audience: { type: String, default: '' },
  sourceName: { type: String, default: '' }
})
const meta = ref(null), loading = ref(false), rows = ref([]), total = ref(0), page = ref(1), size = ref(10), keyword = ref(''), notice = ref(''), table = ref('')
const activeCategory = ref(''), activeSubcategory = ref(''), columnPopover = ref(false), visibleColumns = ref([])
// 明细排序方向：默认倒序（后端本就按 id 倒序返回，保持原有观感），点正序再翻转
const sortOrder = ref('desc')
const dimensions = computed(() => {
  const all = meta.value?.dimensions || []
  if (!props.sourceName) return all
  return all.filter(dim => (dim.systems || []).includes(props.sourceName))
})
const selectedDimension = computed(() => dimensions.value.find(dim => dim.name === activeCategory.value))
const columnOptions = computed(() => {
  const first = rows.value[0]
  if (!first) return []
  return Object.keys(first).filter(key => key !== 'id').slice(0, 14).map(key => ({ prop: key, label: label(key), width: width(key) }))
})
const visibleColumnDefs = computed(() => {
  const map = new Map(columnOptions.value.map(col => [col.prop, col]))
  return visibleColumns.value.map(prop => map.get(prop)).filter(Boolean)
})
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(row => Object.values(row).some(value => String(value ?? '').toLowerCase().includes(q)))
})
// 不同明细表字段不统一，运行期探测排序字段：优先能体现先后的时间列，其次自增 id
const TIME_FIELDS = ['data_time', 'create_time', 'insert_time', 'abnormal_date', 'record_time', 'stat_time']
function resolveSortKey (list) {
  // 时间列在部分明细表里可能大半为空，只有覆盖过半才拿它排序，否则退回 id，避免默认倒序的观感被空值打乱
  const threshold = Math.ceil(list.length / 2)
  const timeKey = TIME_FIELDS.find(key => list.filter(row => row?.[key] != null && row?.[key] !== '').length >= threshold)
  if (timeKey) return timeKey
  return list.some(row => row?.id != null) ? 'id' : ''
}
function compareValues (a, b) {
  // 纯数字（如 id、编号）按数值比较，避免 "10" 排在 "9" 前面
  const an = typeof a === 'number' ? a : (/^-?\d+$/.test(String(a).trim()) ? Number(a) : NaN)
  const bn = typeof b === 'number' ? b : (/^-?\d+$/.test(String(b).trim()) ? Number(b) : NaN)
  if (Number.isFinite(an) && Number.isFinite(bn)) return an - bn
  // 时间列（如 2026-11-20T10:00:00 / 2026-11-20 10:00:00）转时间戳比较，避免格式差异导致排序错位
  const at = Date.parse(a), bt = Date.parse(b)
  if (!Number.isNaN(at) && !Number.isNaN(bt)) return at - bt
  return String(a).localeCompare(String(b))
}
// 只排当前页：分页仍由后端按 page/size 返回，这里仅对已拿到的 rows 重排，
// 切换排序不会触发 loadRows（否则等于重新请求，且容易被误解成全量排序）
const sortedRows = computed(() => {
  const list = filteredRows.value
  const key = resolveSortKey(list)
  if (!key) return sortOrder.value === 'asc' ? [...list].reverse() : list
  // 空值单独摘出来固定放末尾，避免 null / 空串参与比较后顺序来回跳
  const filled = list.filter(row => row?.[key] != null && row?.[key] !== '')
  const empty = list.filter(row => row?.[key] == null || row?.[key] === '')
  if (!filled.length) return sortOrder.value === 'asc' ? [...list].reverse() : list
  const ordered = [...filled].sort((a, b) => compareValues(a[key], b[key]))
  return (sortOrder.value === 'asc' ? ordered : ordered.reverse()).concat(empty)
})
const LABELS = {
  insert_time: '入库时间', create_time: '数据生成时间', abnormal_date: '异常数据时间', data_time: '数据时间',
  first_classify: '一级分类', second_classify: '二级分类', check_code: '稽核编码', check_name: '稽核名称', check_rule: '稽核规则', check_freq: '稽核频率', abnormal_count: '异常量', business_owner: '业务负责人',
  business_name: '业务名称', step_type: '步骤类型', step_name: '步骤名称', access_count: '访问量', avg_duration_sec: '平均时长(秒)', avg_response_sec: '响应时长(秒)', interval_sec: '间隔时长(秒)',
  metric_attr: '指标属性', metric_name: '指标名称', object_name: '对象名称', object_url: '对象URL', metric_value: '指标值', unit: '单位', status: '状态', department: '部门', source_file: '来源文件',
  business_module: '业务模块', check_optimize: '优化建议', current_step: '当前环节', check_status: '稽核状态', verify_result: '核查结果', order_2month: '近两月派单量', unclosed_2month: '近两月未关单量', develop_time: '负责部门', first_level_category: '一级分类', second_level_category: '二级分类'
}
function label (key) { return LABELS[key] || String(key).replaceAll('_', ' ') }
function width (key) { return ['check_name', 'business_name', 'metric_name', 'check_rule', 'object_url'].includes(key) ? 210 : 140 }
function cell (value) {
  if (value == null || value === '') return '--'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value).replace('T', ' ').slice(0, 100)
}
async function loadMeta () {
  if (!props.viewCode) return
  const data = await screenApi.dataMeta(props.viewCode, { days: props.days, ...windowParams(props.range), previewUser: props.previewUser, supervisorId: props.supervisorId, audience: props.audience || undefined })
  meta.value = data
  ensureSelection()
}
function ensureSelection () {
  if (!dimensions.value.length) { activeCategory.value = ''; activeSubcategory.value = ''; return }
  if (!dimensions.value.some(dim => dim.name === activeCategory.value)) {
    activeCategory.value = dimensions.value[0].name
    selectFirstSubcategory()
  } else if (!selectedDimension.value?.subcategories?.some(sub => sub.name === activeSubcategory.value)) {
    selectFirstSubcategory()
  } else {
    loadRows()
  }
}
function selectFirstSubcategory () {
  activeSubcategory.value = selectedDimension.value?.subcategories?.[0]?.name || ''
  columnPopover.value = false
  loadRows()
}
function handleCategoryChange () { selectFirstSubcategory() }
function selectSubcategory (sub) { activeSubcategory.value = sub.name; page.value = 1; keyword.value = ''; loadRows() }
async function loadRows () {
  if (!props.viewCode || !activeCategory.value || !activeSubcategory.value) { rows.value = []; total.value = 0; return }
  loading.value = true; notice.value = ''; table.value = ''
  try {
    const data = await screenApi.dataRows(props.viewCode, { category: activeCategory.value, subCategory: activeSubcategory.value, page: page.value - 1, size: size.value, ...windowParams(props.range), previewUser: props.previewUser, supervisorId: props.supervisorId, audience: props.audience || undefined })
    rows.value = data?.rows || []
    total.value = Number(data?.total || 0)
    table.value = data?.table || ''
    notice.value = data?.notice || ''
    const nextColumns = columnOptions.value.map(col => col.prop)
    const kept = visibleColumns.value.filter(prop => nextColumns.includes(prop))
    visibleColumns.value = kept.length ? kept : nextColumns
  } catch (e) {
    rows.value = []; total.value = 0; notice.value = e?.response?.data?.message || '当前分类未获授权或加载失败'
  } finally { loading.value = false }
}
function applyQuery () { page.value = 1; loadRows() }
function resetQuery () { keyword.value = ''; page.value = 1; loadRows() }
function onSizeChange () { page.value = 1; loadRows() }
function handleColumnChange (value) { if (!value.length) visibleColumns.value = [columnOptions.value[0]?.prop].filter(Boolean) }
watch(() => [props.viewCode, props.days, props.range, props.previewUser, props.supervisorId, props.audience], () => { page.value = 1; loadMeta() }, { immediate: true })
watch(() => props.sourceName, () => { keyword.value = ''; page.value = 1; ensureSelection() })
</script>

<style scoped>
.data-workspace { padding: 12px 14px; }
.workspace-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.workspace-head h3 { margin: 0; color: var(--metric-text); font-size: 13px; }
.workspace-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 9px; }
.workspace-head-tags { display: flex; gap: 5px; flex-wrap: wrap; }
.workspace-tabs { margin-bottom: 2px; }
.workspace-tabs :deep(.el-tabs__header) { margin-bottom: 6px; }
.workspace-body { display: grid; grid-template-columns: 170px minmax(0, 1fr); gap: 9px; }
.subcategory-panel { min-width: 0; padding: 7px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--metric-surface-soft); }
.subcategory-head { display: flex; align-items: center; justify-content: space-between; padding: 3px 4px 7px; color: var(--metric-text-secondary); font-size: 10px; }
.subcategory-head small { color: var(--metric-text-muted); font-size: 9px; }
.subcategory-item { display: flex; align-items: center; justify-content: space-between; gap: 4px; width: 100%; padding: 6px 7px; margin-bottom: 3px; border: 1px solid transparent; border-radius: 6px; background: transparent; color: var(--metric-text-secondary); text-align: left; cursor: pointer; }
.subcategory-item:hover, .subcategory-item.active { border-color: var(--metric-primary-border); background: var(--ui-surface); color: var(--metric-primary-hover); }
.subcategory-item span { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.subcategory-item small { flex: none; color: var(--metric-text-muted); font-size: 8px; }
.workspace-detail { min-width: 0; }
.detail-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; padding: 2px 2px 6px; }
.detail-head b { color: var(--metric-text); font-size: 11px; }
.detail-head small { margin-left: 5px; color: var(--metric-text-muted); font-size: 9px; }
.detail-notice { color: var(--metric-warning); font-size: 9px; }
.detail-toolbar { display: flex; align-items: center; gap: 6px; margin-bottom: 7px; }
.detail-toolbar .el-input { width: 230px; }
.toolbar-spacer { flex: 1; }
.sort-switch { flex: none; }
.sort-switch :deep(.el-radio-button__inner) { padding: 5px 9px; font-size: 11px; }
.column-tip { margin-bottom: 7px; color: var(--metric-text-muted); font-size: 10px; }
.column-list { display: grid; grid-template-columns: 1fr 1fr; gap: 2px 8px; }
.workspace-table :deep(.el-table__cell) { padding: 5px 0; }
.workspace-pager { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 7px; color: var(--metric-text-muted); font-size: 9px; }
@media (max-width: 900px) { .workspace-body { grid-template-columns: 1fr; } .subcategory-panel { display: flex; flex-wrap: wrap; gap: 3px; align-items: center; } .subcategory-head { width: 100%; } .subcategory-item { width: auto; margin: 0; } }
</style>
