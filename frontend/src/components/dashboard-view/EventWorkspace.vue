<template>
  <section v-loading="loading" class="event-workspace">
    <header class="event-workspace-head">
      <div><h3>{{ title }}</h3><small>{{ subtitle }}</small></div>
      <el-button link @click="$emit('close')">收起</el-button>
    </header>

    <div class="event-overview">
      <aside class="event-stat-panel">
        <div class="event-stat-panel-head"><b>统计概览</b><small>点击数字下钻</small></div>
        <button type="button" class="event-stat" :class="{ active: activeStat === 'all' }" @click="applyStatFilter('all')">
          <span>总计</span><b>{{ stats.total ?? '--' }}</b><small>当前授权范围</small>
        </button>
        <button type="button" class="event-stat warning" :class="{ active: activeStat === 'pending' }" @click="applyStatFilter('pending')">
          <span>待处理</span><b>{{ stats.pending ?? '--' }}</b><small>需要闭环</small>
        </button>
        <button type="button" class="event-stat success" :class="{ active: activeStat === 'done' }" @click="applyStatFilter('done')">
          <span>已完成</span><b>{{ stats.completed ?? '--' }}</b><small>已闭环</small>
        </button>
        <button type="button" class="event-stat" :class="{ active: activeStat === 'average' }" @click="applyStatFilter('average')">
          <span>平均耗时</span><b>{{ avgText }}</b><small>完成记录均值</small>
        </button>
      </aside>
      <section class="event-trend-card">
        <div class="event-trend-head"><div><b>趋势统计</b><small>按发生日统计新增，按完成日统计完成</small></div><span>新增 / 完成</span></div>
        <div ref="chartRef" class="event-trend-chart"></div>
      </section>
    </div>

    <div class="event-filters" v-balanced-query>
      <el-select v-model="filters.systemId" clearable filterable placeholder="全部系统" @change="filters.metricId = null"><el-option v-for="node in nodes('system')" :key="node.id" :label="node.label" :value="Number(node.id)" /></el-select>
      <el-select v-model="filters.categoryId" clearable filterable placeholder="全部大类" @change="filters.metricId = null"><el-option v-for="node in nodes('category')" :key="node.id" :label="node.label" :value="Number(node.id)" /></el-select>
      <el-select v-model="filters.metricId" clearable filterable placeholder="全部指标"><el-option v-for="node in nodes('metric')" :key="node.id" :label="node.label" :value="Number(node.id)" /></el-select>
      <el-select v-model="filters.status" clearable placeholder="处理状态"><el-option label="待处理" value="pending" /><el-option label="已完成" value="done" /></el-select>
      <el-input v-model="filters.type" clearable :placeholder="typePlaceholder" />
      <el-input v-model="filters.keyword" clearable placeholder="内容/责任人" @keyup.enter="query" />
      <div class="query-actions"><el-button type="primary" @click="query">查询</el-button>
      <el-button @click="reset">重置</el-button></div>
    </div>

    <el-table :data="rows" stripe border size="small" max-height="380" empty-text="当前筛选条件下暂无记录">
      <el-table-column prop="title" label="内容" min-width="250" show-overflow-tooltip />
      <el-table-column prop="owner" label="责任人" width="110" show-overflow-tooltip />
      <el-table-column label="发生时间" width="160"><template #default="{ row }">{{ time(row.time) }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="row.done ? 'success' : 'warning'">{{ row.done ? '已完成' : '待处理' }}</el-tag></template></el-table-column>
      <el-table-column label="系统 / 大类 / 指标" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ nodeLabel(row.systemId, 'system') }} / {{ nodeLabel(row.categoryId, 'category') }} / {{ nodeLabel(row.metricId, 'metric') }}</template></el-table-column>
      <el-table-column label="类型" width="125"><template #default="{ row }">{{ row.type || row.level || '-' }}</template></el-table-column>
      <el-table-column label="完成时间" width="160"><template #default="{ row }">{{ time(row.completedTime) }}</template></el-table-column>
    </el-table>
    <div class="event-pager"><span>共 {{ total }} 条</span><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[10, 20, 50]" :total="total" layout="total, sizes, prev, pager, next" background @current-change="load" @size-change="onSizeChange" /></div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { screenApi, windowParams } from './api'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  kind: { type: String, required: true },
  code: { type: String, required: true },
  days: { type: Number, default: 7 },
  // 自定义时间区间 [start,end]；为空时按 days 预设。明细接口与统计都按同一时间窗取数。
  range: { type: Array, default: null },
  supervisorId: { type: [Number, String], default: null },
  // 岗位（面向人员）过滤：与专屏顶部下拉一致
  audience: { type: String, default: '' },
  overview: { type: Object, default: () => ({}) }
})
defineEmits(['close'])
const rows = ref([]), total = ref(0), stats = ref({}), page = ref(1), size = ref(10), loading = ref(false), chartRef = ref(), activeStat = ref('all')
const filters = ref({ systemId: null, categoryId: null, metricId: null, status: '', type: '', keyword: '' })
let chart = null
const isAlarm = computed(() => props.kind === 'alarm')
const isIssue = computed(() => props.kind === 'issue')
const title = computed(() => isAlarm.value ? '告警多维查询' : isIssue.value ? '问题多维查询' : '工单多维查询')
const subtitle = computed(() => {
  const rangeText = Array.isArray(props.range) && props.range.length === 2 ? `${props.range[0]} ~ ${props.range[1]}` : `${props.days}天`
  return `当前专屏授权范围 · 按${rangeText}时间窗口统计 · 数据仅展示有权限的系统/大类/指标`
})
const typePlaceholder = computed(() => isAlarm.value ? '告警类型' : isIssue.value ? '问题类型' : '工单类型')
const avgText = computed(() => stats.value.averageHours == null ? '--' : `${Number(stats.value.averageHours).toFixed(1)}h`)
const kindNodes = computed(() => (props.overview?.nodes || []).filter(node => node.type === 'system' || node.type === 'category' || node.type === 'metric'))
const labelMap = computed(() => new Map(kindNodes.value.map(node => [`${node.type}:${node.id}`, { label: node.label, type: node.type }])))
function nodes (type) { return kindNodes.value.filter(node => node.type === type) }
function nodeLabel (id, type) { return id == null ? '--' : (labelMap.value.get(`${type}:${id}`)?.label || `#${id}`) }
function time (value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '--' }
async function load () {
  if (!props.code || !props.kind) return
  loading.value = true
  try {
    const data = await screenApi.eventWorkspace(props.code, {
      kind: props.kind, days: props.days, ...windowParams(props.range), page: page.value - 1, size: size.value,
      systemId: filters.value.systemId || undefined, categoryId: filters.value.categoryId || undefined,
      metricId: filters.value.metricId || undefined, status: filters.value.status || undefined,
      type: filters.value.type || undefined, keyword: filters.value.keyword || undefined,
      supervisorId: props.supervisorId || undefined, audience: props.audience || undefined
    })
    rows.value = data?.rows || []
    total.value = Number(data?.total || 0)
    stats.value = data?.stats || {}
    await nextTick()
    renderChart()
  } catch { rows.value = []; total.value = 0; stats.value = {} } finally { loading.value = false }
}
function applyStatFilter (key) {
  activeStat.value = key
  filters.value.status = key === 'pending' ? 'pending' : key === 'done' || key === 'average' ? 'done' : ''
  page.value = 1
  load()
}
function query () { page.value = 1; load() }
function reset () { filters.value = { systemId: null, categoryId: null, metricId: null, status: '', type: '', keyword: '' }; activeStat.value = 'all'; page.value = 1; load() }
function onSizeChange () { page.value = 1; load() }
function renderChart () {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  const created = new Map((stats.value.trend || []).map(item => [String(item.day), Number(item.created || 0)]))
  const completed = new Map((stats.value.completionTrend || []).map(item => [String(item.day), Number(item.completed || 0)]))
  const days = [...new Set([...created.keys(), ...completed.keys()])].sort()
  chart.setOption({
    animation: false, grid: { left: 38, right: 18, top: 26, bottom: 26 }, tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, itemWidth: 10, itemHeight: 6, textStyle: { fontSize: 9, color: '#64748b' } },
    xAxis: { type: 'category', data: days, axisLabel: { fontSize: 9, color: '#94a3b8' }, axisLine: { lineStyle: { color: '#e2e8f0' } } },
    yAxis: { type: 'value', axisLabel: { fontSize: 9, color: '#94a3b8' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
    series: [
      { name: '新增', type: 'line', smooth: true, symbol: 'none', areaStyle: { opacity: .14 }, data: days.map(day => created.get(day) || 0), color: '#3b82f6' },
      { name: '完成', type: 'line', smooth: true, symbol: 'none', data: days.map(day => completed.get(day) || 0), color: '#10b981' }
    ]
  })
}
function resize () { chart?.resize() }
onMounted(() => { window.addEventListener('resize', resize); load() })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
watch(() => [props.kind, props.days, props.range, props.supervisorId], () => { page.value = 1; activeStat.value = 'all'; filters.value = { systemId: null, categoryId: null, metricId: null, status: '', type: '', keyword: '' }; load() })
</script>

<style scoped>
.event-workspace { margin-top: 12px; padding: 12px 14px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.event-workspace-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.event-workspace-head h3 { margin: 0; color: var(--metric-text); font-size: 13px; }
.event-workspace-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 9px; }
.event-overview { display: grid; grid-template-columns: 245px minmax(0, 1fr); gap: 9px; margin-bottom: 8px; }
.event-stat-panel { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; align-content: start; padding: 8px; border: 1px solid var(--metric-border); border-radius: 8px; background: var(--metric-surface-soft); }
.event-stat-panel-head { grid-column: 1 / -1; display: flex; align-items: baseline; justify-content: space-between; padding: 1px 2px 3px; }
.event-stat-panel-head b { color: var(--metric-text); font-size: 10px; }
.event-stat-panel-head small { color: var(--metric-text-muted); font-size: 8px; }
.event-stat { display: grid; gap: 1px; min-width: 0; padding: 7px 8px; border: 1px solid var(--metric-border); border-radius: 6px; background: var(--ui-surface); text-align: left; cursor: pointer; transition: .18s; }
.event-stat:hover, .event-stat.active { border-color: var(--metric-primary); background: var(--metric-primary-soft); }
.event-stat span { color: var(--metric-text-muted); font-size: 8px; }
.event-stat b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 18px; line-height: 1.1; }
.event-stat small { overflow: hidden; color: var(--metric-text-muted); font-size: 7px; text-overflow: ellipsis; white-space: nowrap; }
.event-stat.warning b { color: var(--metric-warning); }
.event-stat.success b { color: var(--metric-success); }
.event-trend-card { padding: 8px 10px; margin-bottom: 8px; border: 1px solid var(--metric-border); border-radius: 7px; background: var(--ui-surface); }
.event-trend-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.event-trend-head b { color: var(--metric-text); font-size: 10px; }
.event-trend-head >div { display: flex; align-items: baseline; gap: 7px; }
.event-trend-head >span { color: var(--metric-text-muted); font-size: 8px; }
.event-trend-head small { color: var(--metric-text-muted); font-size: 8px; }
.event-trend-chart { height: 188px; }
.event-filters { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.event-filters .el-select { width: 125px; }
.event-filters .el-input { width: 125px; }
.event-pager { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 7px; color: var(--metric-text-muted); font-size: 9px; }
@media (max-width: 760px) { .event-overview { grid-template-columns: 1fr; } .event-stat-panel { grid-template-columns: repeat(4, minmax(0, 1fr)); } .event-filters .el-select, .event-filters .el-input { width: calc(50% - 3px); } }
</style>
