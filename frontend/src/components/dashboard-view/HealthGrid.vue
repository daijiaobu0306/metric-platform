<template>
  <div v-loading="loading" class="health-grid-wrap" :class="{ compact }">
    <div class="health-summary">
      <span>已匹配 <b>{{ rows.length }}</b> 个指标</span>
      <span class="ok">正常 <b>{{ stats.normal }}</b></span>
      <span class="warn">关注 <b>{{ stats.warning }}</b></span>
      <span class="bad">异常 <b>{{ stats.danger }}</b></span>
      <span class="muted">待计算 <b>{{ stats.unknown }}</b></span>
      <span class="summary-actions">
        <el-button link @click="expandAll">全部展开</el-button>
        <el-button link @click="collapseAll">全部收起</el-button>
      </span>
    </div>

    <div v-if="groups.length" class="health-groups">
      <section v-for="group in groups" :key="group.name" class="health-group">
        <div v-for="sub in group.subgroups" :key="sub.name" class="health-subgroup">
          <div class="subgroup-head">
            <div class="subgroup-title">
              <i></i>
              <b>{{ group.name }} - {{ sub.name || '未分小类' }}</b>
              <span class="subgroup-count">{{ sub.items.length }} 项指标</span>
              <button type="button" class="collapse-btn" :aria-expanded="!isCollapsed(group.name, sub.name)" @click="toggleGroup(group.name, sub.name)">
                {{ isCollapsed(group.name, sub.name) ? '展开' : '收起' }}<span class="chevron">{{ isCollapsed(group.name, sub.name) ? '▾' : '▴' }}</span>
              </button>
            </div>
            <div class="subgroup-stats">
              <span class="bad">异常 {{ subStats(sub).danger }}</span>
              <span class="warn">关注 {{ subStats(sub).warning }}</span>
              <span class="ok">正常 {{ subStats(sub).normal }}</span>
              <span class="muted">待计算 {{ subStats(sub).unknown }}</span>
            </div>
          </div>
          <div v-show="!isCollapsed(group.name, sub.name)" class="health-card-grid">
            <article
              v-for="item in sub.items"
              :key="item.metricId"
              class="health-card"
              :class="level(item)"
              tabindex="0"
              @click="$emit('select', item)"
              @keyup.enter="$emit('select', item)"
            >
              <div class="health-card-top">
                <span :title="`${item.isStale ? '最近值在所选范围之外。' : ''}${item.sourceNotice || ''} 数据时间：${item.dataTime || '缺失'}`">{{ item.metricName }}</span>
                <StatusTag :status="level(item)" :label="levelLabel(item)" />
              </div>
              <div class="health-values">
                <div><small>健康度</small><b class="health-score-link" @click.stop="$emit('health-detail', item)">{{ item.healthScore ?? '--' }}</b></div>
                <div><small>最新值</small><strong>{{ item.latestValue ?? '--' }} {{ item.latestValue != null ? item.unit : '' }}</strong></div>
              </div>
              <SvgLineChart v-if="!compact" :points="item.trend || []" />
              <div class="health-card-foot"><small>{{ trendNote(item.trend) }}</small><span>查看详情</span></div>
            </article>
          </div>
        </div>
      </section>
    </div>
    <el-empty v-else description="当前范围内没有指标健康数据" :image-size="72" />
    <el-button v-if="limit > 0 && rows.length > limit" class="show-all" @click="showAll = !showAll">{{ showAll ? '收起指标' : `展开全部指标（${rows.length}）` }}</el-button>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import SvgLineChart from './ui/SvgLineChart.vue'
import StatusTag from './ui/StatusTag.vue'
import { scoreLevel } from '@/utils/healthThresholds'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  limit: { type: Number, default: 30 },
  compact: { type: Boolean, default: false }
})
defineEmits(['select', 'health-detail'])
const showAll = ref(false)
const collapsedGroups = ref(new Set())
const visibleRows = computed(() => props.limit > 0 && !showAll.value ? props.rows.slice(0, props.limit) : props.rows)
const stats = computed(() => {
  const out = { normal: 0, warning: 0, danger: 0, unknown: 0 }
  props.rows.forEach(item => { out[level(item)]++ })
  return out
})
const groups = computed(() => {
  const out = {}
  visibleRows.value.forEach(item => {
    const category = item.categoryName || '未分类'
    const sub = item.subCategoryName || ''
    const group = out[category] ?? (out[category] = { name: category, subgroups: {} })
    const bucket = group.subgroups[sub] ?? (group.subgroups[sub] = { name: sub, items: [] })
    bucket.items.push(item)
  })
  return Object.values(out).map(group => ({
    ...group,
    subgroups: Object.values(group.subgroups).map(sub => ({ ...sub, items: [...sub.items].sort((a, b) => scoreRank(a) - scoreRank(b)) }))
  }))
})
function scoreRank (item) {
  if (item?.healthScore == null) return 1000
  return Number(item.healthScore)
}
function subStats (sub) {
  const out = { normal: 0, warning: 0, danger: 0, unknown: 0 }
  ;(sub?.items || []).forEach(item => { out[level(item)]++ })
  return out
}
function groupKey (category, sub) { return `${category}||${sub}` }
function isCollapsed (category, sub) { return collapsedGroups.value.has(groupKey(category, sub)) }
function toggleGroup (category, sub) {
  const next = new Set(collapsedGroups.value)
  const key = groupKey(category, sub)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedGroups.value = next
}
function expandAll () { collapsedGroups.value = new Set() }
function collapseAll () { collapsedGroups.value = new Set(groups.value.flatMap(group => group.subgroups.map(sub => groupKey(group.name, sub.name)))) }
function level (item) {
  // 分档阈值统一来自 system_config（60/80），不再写死 70/90
  return scoreLevel(item?.healthScore)
}
function levelLabel (item) {
  const value = level(item)
  return value === 'danger' ? '异常' : value === 'warning' ? '关注' : value === 'unknown' ? '待计算' : '正常'
}
function trendNote (trend) {
  const values = (trend || []).map(x => Number(x.healthScore)).filter(Number.isFinite)
  if (!values.length) return '暂无趋势数据'
  if (values.length === 1) return '首次计算'
  if (values.every(x => x === 0)) return '持续异常'
  const delta = values.at(-1) - values[0]
  return Math.abs(delta) < 3 ? '趋势平稳' : delta > 0 ? '健康度回升' : '健康度下降'
}
</script>

<style scoped>
.health-summary { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 9px 12px; margin-bottom: 11px; border-radius: var(--metric-radius-sm); background: var(--metric-surface-soft); color: var(--metric-text-secondary); font-size: 11px; }
.health-summary b { color: var(--metric-text); }
.health-summary .ok b { color: var(--metric-success); }
.health-summary .warn b { color: var(--metric-warning); }
.health-summary .bad b { color: var(--metric-danger); }
.health-summary .muted b { color: var(--metric-text-muted); }
.summary-actions { display: flex; align-items: center; gap: 4px; margin-left: auto; }
.health-groups { display: grid; gap: 12px; }
.health-group { display: grid; gap: 10px; }
.health-subgroup { padding: 11px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--metric-surface-soft); }
.subgroup-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.subgroup-title { display: flex; align-items: center; gap: 6px; min-width: 0; flex-wrap: wrap; }
.subgroup-title > i { width: 4px; height: 13px; border-radius: 2px; background: var(--metric-primary); }
.subgroup-title b { overflow: hidden; color: var(--metric-text); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.subgroup-count { color: var(--metric-text-muted); font-size: 10px; }
.collapse-btn { display: inline-flex; align-items: center; gap: 2px; padding: 1px 6px; border: 1px solid var(--metric-primary-border); border-radius: 999px; background: var(--ui-surface); color: var(--metric-primary-hover); font-size: 9px; cursor: pointer; }
.collapse-btn:hover { background: var(--metric-primary-soft); }
.chevron { font-size: 9px; }
.subgroup-stats { display: flex; gap: 9px; flex-wrap: wrap; font-size: 9px; white-space: nowrap; }
.subgroup-stats .bad { color: var(--metric-danger); }
.subgroup-stats .warn { color: var(--metric-warning); }
.subgroup-stats .ok { color: var(--metric-success); }
.subgroup-stats .muted { color: var(--metric-text-muted); }
.health-card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(255px, 1fr)); gap: 8px; }
.health-card { display: grid; gap: 6px; padding: 9px 10px; border: 1px solid var(--metric-border); border-left: 4px solid var(--metric-primary); border-radius: 8px; background: var(--ui-surface); cursor: pointer; transition: .18s; }
.health-card:hover, .health-card:focus { outline: none; border-color: currentColor; box-shadow: var(--metric-shadow-md); transform: translateY(-1px); }
.health-card.danger { border-left-color: var(--metric-danger); background: var(--metric-danger-soft); }
.health-card.warning { border-left-color: var(--metric-warning); background: var(--metric-warning-soft); }
.health-card.normal { border-left-color: var(--metric-success); }
.health-card.unknown { border-left-color: var(--metric-text-muted); }
.health-card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.health-card-top > span { color: var(--metric-text); font-size: 11px; font-weight: 600; line-height: 1.35; }
.health-values { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.health-values > div { display: grid; gap: 2px; }
.health-values small { color: var(--metric-text-muted); font-size: 9px; }
.health-values b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 18px; line-height: 1; }
.health-score-link { cursor: pointer; text-decoration: underline dotted; text-underline-offset: 2px; }
.health-score-link:hover { color: var(--metric-primary-hover); }
.health-values strong { color: var(--metric-text-secondary); font-size: 13px; line-height: 1.2; }
.health-card :deep(svg) { height: 28px; color: var(--metric-primary); }
.health-card.danger :deep(svg) { color: var(--metric-danger); }
.health-card.warning :deep(svg) { color: var(--metric-warning); }
.health-card.normal :deep(svg) { color: var(--metric-success); }
.health-card-foot { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding-top: 4px; border-top: 1px solid rgb(226 232 240 / .65); }
.health-card-foot small { color: var(--metric-text-muted); font-size: 9px; }
.health-card-foot span { color: var(--metric-primary-hover); font-size: 9px; }
.show-all { margin-top: 12px; }
@media (max-width: 760px) {
  .subgroup-head { align-items: flex-start; flex-direction: column; }
  .subgroup-stats { white-space: normal; }
}
.compact .health-summary { gap: 5px; font-size: 11px; }
.compact .health-card-grid { grid-template-columns: minmax(0, 1fr); gap: 0; }
.compact .health-card { grid-template-columns: minmax(0, 1fr) minmax(90px, .7fr); gap: 4px; padding: 5px 3px; border: 0; border-top: 1px solid var(--metric-border); border-radius: 0; background: #fff; }
.compact .health-card-top { flex-direction: column; gap: 2px; }
.compact .health-card-top > span { font-size: 12px; }
.compact .health-values { grid-template-columns: minmax(0, .7fr) minmax(0, 1.3fr); }
.compact .health-values b, .compact .health-values strong { font-size: 12px; overflow-wrap: anywhere; }
.compact .health-card-foot { display: none; }
.compact .subgroup-head { gap: 2px; }
</style>
