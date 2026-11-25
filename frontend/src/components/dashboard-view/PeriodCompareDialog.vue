<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="min(1180px, 96vw)"
    top="5vh"
    class="workspace-theme dashboard-workspace period-compare-dialog"
    @update:model-value="value => emit('update:modelValue', value)"
  >
    <div class="compare-intro">
      <div>
        <div class="compare-title-line"><h3>{{ target?.name || '指标' }}</h3><el-tag size="small" effect="plain">{{ target?.type === 'metric' ? '指标环比' : '健康度环比' }}</el-tag></div>
        <p>对比当前周期与上一等长周期的指标值、健康度变化；周期按等长位移自动对齐。</p>
      </div>
      <span class="compare-note">数据按实际记录时间对齐，不做补零</span>
    </div>

    <div class="preset-bar">
      <span class="preset-label">快速对比预设</span>
      <button v-for="item in presets" :key="item.key" type="button" class="preset-btn" :class="{ active: preset === item.key }" :disabled="item.disabled" @click="selectPreset(item.key)">{{ item.label }}</button>
      <span class="preset-hint">{{ presetHint }}</span>
    </div>

    <div class="period-grid">
      <div class="period-card current">
        <div class="period-head"><span>基准分析周期</span><small>共 {{ currentRange.days }} 天</small></div>
        <div class="period-range"><b>{{ formatRange(currentRange.start) }}</b><i>至</i><b>{{ formatRange(currentRange.end) }}</b></div>
        <small class="period-points">{{ currentPoints.length }} 个点位</small>
      </div>
      <div class="period-card previous">
        <div class="period-head"><span>对比周期</span><small>自动位移 · 等时长</small></div>
        <div class="period-range"><b>{{ formatRange(previousRange.start) }}</b><i>至</i><b>{{ formatRange(previousRange.end) }}</b></div>
        <small class="period-points">{{ previousPoints.length }} 个点位</small>
      </div>
    </div>

    <div v-loading="loading" class="compare-body">
      <div class="stat-grid">
        <article v-for="card in statCards" :key="card.key" class="compare-stat" :class="card.status">
          <div class="stat-label">{{ card.label }}<span>{{ card.hint }}</span></div>
          <div class="stat-value-line"><b>{{ display(card.current, card.unit) }}</b><small>vs {{ display(card.previous, card.unit) }}</small></div>
          <div class="stat-change" :class="card.status">{{ changeText(card) }}</div>
          <small class="stat-note">{{ card.note }}</small>
        </article>
      </div>

      <div class="compare-charts" :class="{ single: !isMetric }">
        <section v-if="isMetric" class="chart-card">
          <header><div><b>指标值环比对照</b><small>{{ target?.unit || '指标值' }}</small></div><span class="chart-current">本期</span></header>
          <PeriodCompareChart :current="valuePoints.current" :previous="valuePoints.previous" :unit="target?.unit || ''" :threshold="target?.threshold" :height="220" />
          <div class="chart-summary">{{ valueSummary }}</div>
        </section>
        <section class="chart-card">
          <header><div><b>健康度环比对照</b><small>90 / 70 为关注 / 异常分界</small></div><span class="chart-current">本期</span></header>
          <PeriodCompareChart :current="healthPoints.current" :previous="healthPoints.previous" health :height="220" />
          <div class="chart-summary">{{ healthSummary }}</div>
        </section>
      </div>
    </div>

    <template #footer>
      <span class="compare-footer-note">当前数据窗口 30 天 · 对比周期为当前周期等长前移</span>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { screenApi } from './api'
import PeriodCompareChart from './PeriodCompareChart.vue'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  target: { type: Object, default: () => ({}) },
  viewCode: { type: String, default: '' },
  nodes: { type: Array, default: () => [] },
  healthCards: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])
const preset = ref('7d')
const loading = ref(false)
const series = ref([])
const presets = [
  { key: '1d', label: '近24小时 vs 前24小时' },
  { key: '7d', label: '近7天 vs 上7天' },
  { key: '30d', label: '近30天 vs 前30天', disabled: true }
]
const presetHint = computed(() => preset.value === '1d' ? '按小时对比，适合观察短时波动' : '按天对比，适合观察趋势变化')
const isMetric = computed(() => props.target?.type === 'metric')
const dialogTitle = computed(() => isMetric.value ? `指标环比分析 - ${props.target?.name || ''}` : `${props.target?.name || '系统综合健康度'}环比分析`)
const periodMs = computed(() => preset.value === '1d' ? 24 * 60 * 60 * 1000 : 7 * 24 * 60 * 60 * 1000)
const currentRange = computed(() => {
  const end = Date.now()
  return { start: end - periodMs.value, end, days: preset.value === '1d' ? 1 : 7 }
})
const previousRange = computed(() => ({ start: currentRange.value.start - periodMs.value, end: currentRange.value.start, days: currentRange.value.days }))
const currentPoints = computed(() => inRange(series.value, currentRange.value))
const previousPoints = computed(() => inRange(series.value, previousRange.value))
const valuePoints = computed(() => ({
  current: currentPoints.value.map(item => ({ time: item.time, value: item.value })),
  previous: previousPoints.value.map(item => ({ time: item.time, value: item.value }))
}))
const healthPoints = computed(() => ({
  current: currentPoints.value.map(item => ({ time: item.time, value: item.healthScore })),
  previous: previousPoints.value.map(item => ({ time: item.time, value: item.healthScore }))
}))
const statCards = computed(() => {
  if (isMetric.value) {
    return [
      metricCard('value', '指标均值', average(currentPoints.value.map(item => item.value)), average(previousPoints.value.map(item => item.value)), props.target?.unit || '', 'neutral', '当前周期与对比周期均值'),
      metricCard('health', '健康度均值', average(currentPoints.value.map(item => item.healthScore)), average(previousPoints.value.map(item => item.healthScore)), '分', 'higher', '健康度越高越好'),
      metricCard('peak', '周期最高值', max(currentPoints.value.map(item => item.value)), max(previousPoints.value.map(item => item.value)), props.target?.unit || '', 'neutral', '峰值用于观察极端波动'),
      metricCard('alarm', '告警次数', currentPoints.value.filter(item => item.status === 'alarm').length, previousPoints.value.filter(item => item.status === 'alarm').length, '次', 'lower', '触发告警的采集点数量')
    ]
  }
  const currentHealth = currentPoints.value.map(item => item.healthScore)
  const previousHealth = previousPoints.value.map(item => item.healthScore)
  return [
    metricCard('health', '健康度均值', average(currentHealth), average(previousHealth), '分', 'higher', '健康度越高越好'),
    metricCard('max', '最高健康度', max(currentHealth), max(previousHealth), '分', 'higher', '周期内最好状态'),
    metricCard('min', '最低健康度', min(currentHealth), min(previousHealth), '分', 'higher', '周期内最差状态'),
    metricCard('range', '波动幅度', range(currentHealth), range(previousHealth), '分', 'lower', '最高分与最低分之差')
  ]
})
const valueSummary = computed(() => summaryText(valuePoints.value.current, valuePoints.value.previous, props.target?.unit || ''))
const healthSummary = computed(() => summaryText(healthPoints.value.current, healthPoints.value.previous, '分'))

function metricCard (key, label, current, previous, unit, better, note) {
  const currentValue = current == null ? null : Number(current)
  const previousValue = previous == null ? null : Number(previous)
  const diff = currentValue != null && previousValue != null ? currentValue - previousValue : null
  let status = 'flat'
  if (diff != null) {
    if (better === 'higher') status = diff > 0 ? 'up' : diff < 0 ? 'down' : 'flat'
    else if (better === 'lower') status = diff < 0 ? 'up' : diff > 0 ? 'down' : 'flat'
  }
  return { key, label, current: currentValue, previous: previousValue, unit, better, note, status, hint: better === 'higher' ? '越高越好' : better === 'lower' ? '越低越好' : '数值对比' }
}
function average (values) {
  const nums = values.map(Number).filter(Number.isFinite)
  return nums.length ? nums.reduce((sum, value) => sum + value, 0) / nums.length : null
}
function max (values) {
  const nums = values.map(Number).filter(Number.isFinite)
  return nums.length ? Math.max(...nums) : null
}
function min (values) {
  const nums = values.map(Number).filter(Number.isFinite)
  return nums.length ? Math.min(...nums) : null
}
function range (values) {
  const nums = values.map(Number).filter(Number.isFinite)
  return nums.length ? Math.max(...nums) - Math.min(...nums) : null
}
function display (value, unit) {
  if (value == null || !Number.isFinite(Number(value))) return '--'
  const rounded = Math.abs(Number(value)) >= 100 ? Math.round(Number(value)) : Math.round(Number(value) * 10) / 10
  return `${rounded}${unit || ''}`
}
function changeText (card) {
  if (card.current == null || card.previous == null) return '暂无对比数据'
  const diff = card.current - card.previous
  const sign = diff > 0 ? '+' : ''
  const value = `${sign}${Math.round(diff * 10) / 10}${card.unit || ''}`
  if (card.better === 'higher') return `${value} ${diff > 0 ? '改善' : diff < 0 ? '劣化' : '持平'}`
  if (card.better === 'lower') return `${value} ${diff < 0 ? '改善' : diff > 0 ? '劣化' : '持平'}`
  return `${value} ${diff === 0 ? '持平' : '变化'}`
}
function summaryText (current, previous, unit) {
  if (!current.length && !previous.length) return '暂无可对比数据'
  if (!current.length) return '本期暂无数据，无法评估变化'
  if (!previous.length) return '对比周期暂无数据，无法评估变化'
  const currentAvg = average(current.map(item => item.value))
  const previousAvg = average(previous.map(item => item.value))
  if (currentAvg == null || previousAvg == null) return '有效数值不足，无法计算均值'
  const diff = currentAvg - previousAvg
  return `本期均值 ${display(currentAvg, unit)}，对比期均值 ${display(previousAvg, unit)}，${diff > 0 ? '上升' : diff < 0 ? '下降' : '持平'} ${display(Math.abs(diff), unit)}。`
}
function inRange (items, range) {
  return (items || []).filter(item => {
    const time = new Date(String(item.time).replace(' ', 'T')).getTime()
    return Number.isFinite(time) && time >= range.start && time <= range.end
  })
}
function formatRange (value) {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:00`
}
function selectPreset (key) { if (!presets.find(item => item.key === key)?.disabled) preset.value = key }
function aggregateCards (cards) {
  const map = new Map()
  cards.forEach(card => (card.trend || []).forEach(point => {
    if (point.healthScore == null) return
    const time = String(point.recordTime || point.time || '')
    if (!time) return
    const bucket = map.get(time) || { time, values: [] }
    bucket.values.push(Number(point.healthScore))
    map.set(time, bucket)
  }))
  return [...map.values()].sort((a, b) => a.time.localeCompare(b.time)).map(bucket => ({ time: bucket.time, healthScore: Math.round(average(bucket.values)) }))
}
async function load () {
  if (!props.modelValue || !props.target) return
  loading.value = true
  series.value = []
  const target = props.target
  try {
    if (target.type === 'metric') {
      const node = (props.nodes || []).find(item => item.type === 'metric' && Number(item.id) === Number(target.metricId))
      if (node && props.viewCode) {
        const result = await screenApi.health(props.viewCode, { node: node.key, days: 30 })
        series.value = (result?.values || []).map(item => ({ time: item.recordTime || item.time, value: item.value, healthScore: item.healthScore, status: item.status })).sort((a, b) => String(a.time).localeCompare(String(b.time)))
      }
      if (!series.value.length) {
        const card = (props.healthCards || []).find(item => Number(item.metricId) === Number(target.metricId))
        series.value = aggregateCards(card ? [card] : [])
      }
    } else {
      const nodes = (props.nodes || []).filter(item => item.type === (target.type === 'system' ? 'category' : target.type))
      const next = []
      for (const node of nodes) {
        if (target.type !== 'system' && node.label !== target.name) continue
        try {
          const result = await screenApi.health(props.viewCode, { node: node.key, days: 30 })
          next.push(...(result?.data || []).map(item => ({ time: item.recordTime || item.time, healthScore: item.healthScore })))
        } catch {}
      }
      if (next.length) {
        const map = new Map()
        next.forEach(item => {
          if (item.healthScore == null) return
          const bucket = map.get(item.time) || { time: item.time, values: [] }
          bucket.values.push(Number(item.healthScore))
          map.set(item.time, bucket)
        })
        series.value = [...map.values()].sort((a, b) => a.time.localeCompare(b.time)).map(bucket => ({ time: bucket.time, healthScore: Math.round(average(bucket.values)) }))
      } else {
        const cards = target.type === 'system'
          ? props.healthCards || []
          : (props.healthCards || []).filter(card => target.type === 'category' ? card.categoryName === target.name : card.subCategoryName === target.name && (!target.categoryName || card.categoryName === target.categoryName))
        series.value = aggregateCards(cards)
      }
    }
  } finally { loading.value = false }
}
watch(() => props.modelValue, open => { if (open) { preset.value = '7d'; load() } }, { immediate: true })
</script>

<style scoped>
.compare-intro { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.compare-title-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.compare-title-line h3 { margin: 0; color: var(--metric-text); font-size: 17px; }
.compare-intro p { margin: 6px 0 0; color: var(--metric-text-secondary); font-size: 12px; }
.compare-note { color: var(--metric-text-muted); font-size: 10px; white-space: nowrap; }
.preset-bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 8px 10px; margin-bottom: 10px; border-radius: var(--metric-radius-sm); background: var(--metric-surface-soft); }
.preset-label { color: var(--metric-text-muted); font-size: 10px; }
.preset-btn { padding: 4px 9px; border: 1px solid var(--metric-border); border-radius: 7px; background: var(--ui-surface); color: var(--metric-text-secondary); font-size: 10px; cursor: pointer; }
.preset-btn.active { border-color: var(--metric-primary); background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-weight: 650; }
.preset-btn:disabled { opacity: .45; cursor: not-allowed; }
.preset-hint { margin-left: auto; color: var(--metric-text-muted); font-size: 9px; }
.period-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; margin-bottom: 10px; }
.period-card { padding: 9px 11px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.period-card.current { border-color: var(--metric-primary-border); background: var(--metric-primary-soft); }
.period-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--metric-text); font-size: 11px; }
.period-head small { color: var(--metric-text-muted); font-size: 9px; }
.period-range { display: flex; align-items: center; gap: 6px; margin-top: 5px; color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 10px; }
.period-range i { color: var(--metric-text-muted); font-style: normal; font-family: var(--metric-font-sans); }
.period-points { display: block; margin-top: 2px; color: var(--metric-text-muted); font-size: 9px; }
.compare-body { min-height: 260px; }
.stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 10px; }
.compare-stat { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.compare-stat .stat-label { display: flex; align-items: center; justify-content: space-between; gap: 6px; color: var(--metric-text-secondary); font-size: 10px; }
.compare-stat .stat-label span { color: var(--metric-text-muted); font-size: 8px; }
.stat-value-line { display: flex; align-items: baseline; gap: 6px; }
.stat-value-line b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 21px; line-height: 1; }
.stat-value-line small { color: var(--metric-text-muted); font-size: 9px; }
.stat-change { font-size: 10px; font-weight: 600; }
.stat-change.up { color: var(--metric-success); }
.stat-change.down { color: var(--metric-danger); }
.stat-change.flat { color: var(--metric-text-muted); }
.stat-note { color: var(--metric-text-muted); font-size: 9px; }
.compare-charts { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.compare-charts.single { grid-template-columns: 1fr; }
.chart-card { min-width: 0; padding: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.chart-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.chart-card header b { color: var(--metric-text); font-size: 11px; }
.chart-card header small { display: block; margin-top: 2px; color: var(--metric-text-muted); font-size: 9px; }
.chart-current { color: var(--metric-text-muted); font-size: 9px; }
.chart-summary { padding-top: 5px; border-top: 1px solid var(--metric-border); color: var(--metric-text-muted); font-size: 9px; line-height: 1.5; }
.compare-footer-note { margin-right: auto; color: var(--metric-text-muted); font-size: 9px; }
@media (max-width: 900px) { .stat-grid, .compare-charts, .period-grid { grid-template-columns: 1fr; } .compare-intro { flex-direction: column; } .preset-hint { margin-left: 0; } }
</style>
