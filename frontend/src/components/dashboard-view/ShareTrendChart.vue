<template>
  <div ref="wrap" class="share-trend-chart">
    <div class="chart-head">
      <b>{{ title }}</b>
      <span v-if="series.length > 1">{{ rangeText }}</span>
    </div>
    <p v-if="!series.length" class="chart-empty">当前范围暂无可绘制的数据</p>
    <template v-else>
      <svg :viewBox="`0 0 ${box.width} ${box.height}`" class="chart-svg" role="img" :aria-label="`${title}折线图`">
        <defs>
          <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" :stop-color="color" stop-opacity="0.26" />
            <stop offset="100%" :stop-color="color" stop-opacity="0" />
          </linearGradient>
        </defs>
        <line
          v-for="tick in yTicks"
          :key="`grid-${tick.key}`"
          class="grid-line"
          :x1="box.left"
          :x2="box.width - box.right"
          :y1="tick.y"
          :y2="tick.y"
        />
        <text
          v-for="tick in yTicks"
          :key="`label-${tick.key}`"
          class="axis-text"
          :x="box.left - 4"
          :y="tick.y + 3"
          text-anchor="end"
        >{{ tick.label }}</text>
        <polygon v-if="areaPoints" :points="areaPoints" :fill="`url(#${gradientId})`" />
        <polyline :points="linePoints" :stroke="color" stroke-width="2" fill="none" stroke-linejoin="round" stroke-linecap="round" />
        <circle v-for="point in dots" :key="`dot-${point.key}`" :cx="point.x" :cy="point.y" r="2.6" :fill="point.color" stroke="#fff" stroke-width="1.4" />
        <text class="axis-text" :x="box.left" :y="box.height - 3">{{ timeLabel(series[0].time) }}</text>
        <text v-if="series.length > 1" class="axis-text" :x="box.width - box.right" :y="box.height - 3" text-anchor="end">{{ timeLabel(series[series.length - 1].time) }}</text>
      </svg>
      <div class="chart-foot">
        <span>最高 <b>{{ full(maxValue) }}</b></span>
        <span>最低 <b>{{ full(minValue) }}</b></span>
        <span>最新 <b>{{ full(lastValue) }}</b><em v-if="unit">{{ unit }}</em></span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  points: { type: Array, default: () => [] },
  // 空字符串：自动取 value / metricValue（指标值）；传 healthScore 可画健康度
  valueKey: { type: String, default: '' },
  title: { type: String, default: '趋势' },
  unit: { type: String, default: '' },
  height: { type: Number, default: 136 },
  color: { type: String, default: '#2563eb' }
})

const wrap = ref()
const width = ref(320)
let observer

const rawSeries = computed(() => (props.points || [])
  .map((point, index) => {
    const raw = props.valueKey ? point[props.valueKey] : (point.value ?? point.metricValue)
    const value = raw == null || raw === '' ? null : Number(raw)
    return {
      key: index,
      value: Number.isFinite(value) ? value : null,
      time: String(point.time || point.recordTime || point.dataTime || point.snapshotTime || '')
    }
  })
  .filter(point => point.value != null))

// 详情接口最多返回 5000 条，移动端画全量会卡；超过 600 个点等间隔抽样，首尾必留
const series = computed(() => {
  const list = rawSeries.value
  if (list.length <= 600) return list
  const step = Math.ceil(list.length / 600)
  const sampled = []
  for (let index = 0; index < list.length; index += step) sampled.push(list[index])
  if (sampled[sampled.length - 1] !== list[list.length - 1]) sampled.push(list[list.length - 1])
  return sampled
})

const box = computed(() => ({
  width: Math.max(220, Math.round(width.value)),
  height: props.height,
  left: 46,
  right: 10,
  top: 16,
  bottom: 18
}))

const minValue = computed(() => rawSeries.value.reduce((min, point) => Math.min(min, point.value), Number.POSITIVE_INFINITY))
const maxValue = computed(() => rawSeries.value.reduce((max, point) => Math.max(max, point.value), Number.NEGATIVE_INFINITY))
const lastValue = computed(() => rawSeries.value.length ? rawSeries.value[rawSeries.value.length - 1].value : null)

function compact (value) {
  const abs = Math.abs(value)
  if (abs >= 100000000) return `${(value / 100000000).toFixed(1)}亿`
  if (abs >= 10000) return `${(value / 10000).toFixed(1)}万`
  if (abs >= 100) return String(Math.round(value))
  return String(Math.round(value * 10) / 10)
}
function full (value) {
  if (value == null) return '—'
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
function timeLabel (value) {
  const text = String(value || '').replace('T', ' ')
  const matched = text.match(/^(\d{4})-(\d{2})-(\d{2})[ ]?(\d{2}:\d{2})?/)
  return matched ? `${matched[2]}-${matched[3]}${matched[4] ? ` ${matched[4]}` : ''}` : text.slice(0, 16)
}
const rangeText = computed(() => series.value.length > 1
  ? `${timeLabel(series.value[0].time)} ~ ${timeLabel(series.value[series.value.length - 1].time)}`
  : '')

const yTicks = computed(() => {
  if (!series.value.length) return []
  const innerTop = box.value.top
  const innerBottom = box.value.height - box.value.bottom
  if (maxValue.value === minValue.value) {
    return [{ key: 'flat', y: (innerTop + innerBottom) / 2, label: compact(maxValue.value) }]
  }
  const rows = [
    { key: 'max', ratio: 1, label: compact(maxValue.value) },
    { key: 'mid', ratio: 0.5, label: compact((maxValue.value + minValue.value) / 2) },
    { key: 'min', ratio: 0, label: compact(minValue.value) }
  ]
  return rows.map(row => ({
    ...row,
    y: innerTop + (1 - row.ratio) * (innerBottom - innerTop)
  }))
})

function position (point, index) {
  const innerW = box.value.width - box.value.left - box.value.right
  const innerTop = box.value.top
  const innerBottom = box.value.height - box.value.bottom
  const x = series.value.length === 1 ? box.value.left + innerW / 2 : box.value.left + (index * innerW) / (series.value.length - 1)
  const span = maxValue.value - minValue.value
  const ratio = span === 0 ? 0.5 : (point.value - minValue.value) / span
  return { x, y: innerBottom - ratio * (innerBottom - innerTop) }
}

const linePoints = computed(() => series.value
  .map((point, index) => {
    const { x, y } = position(point, index)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  .join(' '))

const areaPoints = computed(() => {
  if (series.value.length < 2) return ''
  const baseline = box.value.height - box.value.bottom
  return `${box.value.left},${baseline} ${linePoints.value} ${box.value.width - box.value.right},${baseline}`
})

// 点太多时只保留首尾和峰值，避免移动端 SVG 节点过多
const dots = computed(() => {
  const total = series.value.length
  if (!total) return []
  const keep = new Set([0, total - 1])
  let maxIndex = 0
  let minIndex = 0
  series.value.forEach((point, index) => {
    if (point.value > series.value[maxIndex].value) maxIndex = index
    if (point.value < series.value[minIndex].value) minIndex = index
  })
  keep.add(maxIndex)
  keep.add(minIndex)
  return [...keep].slice(0, 6).map(index => ({
    key: index,
    ...position(series.value[index], index),
    color: index === maxIndex ? '#dc2626' : index === minIndex ? '#0f766e' : props.color
  }))
})

const gradientId = `share-trend-${Math.random().toString(36).slice(2, 9)}`

// 量的是内容宽度（去掉卡片自身左右内边距），保证 SVG 的 viewBox 与实际像素 1:1，文字不会被横向拉伸
function measureWidth () {
  if (!wrap.value) return 0
  const style = getComputedStyle(wrap.value)
  return wrap.value.clientWidth - parseFloat(style.paddingLeft || 0) - parseFloat(style.paddingRight || 0)
}

onMounted(() => {
  const initial = measureWidth()
  if (initial > 1) width.value = initial
  observer = new ResizeObserver(() => {
    const next = measureWidth()
    if (next > 1 && Math.abs(next - width.value) > 1) width.value = next
  })
  if (wrap.value) observer.observe(wrap.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<style scoped>
.share-trend-chart { min-width: 0; margin-bottom: 12px; padding: 10px 10px 8px; border: 1px solid var(--metric-border, #e2e8f0); border-radius: 8px; background: #fff; }
.chart-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
.chart-head b { color: #17324d; font-size: 12px; }
.chart-head span { color: #6b7f96; font-size: 10px; }
.chart-svg { display: block; width: 100%; height: auto; }
.grid-line { stroke: #e6edf5; stroke-width: 1; stroke-dasharray: 3 3; }
.axis-text { fill: #7c8ea3; font-size: 9px; }
.chart-foot { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding-top: 6px; margin-top: 2px; border-top: 1px dashed #e6edf5; color: #6b7f96; font-size: 10px; }
.chart-foot b { color: #17324d; font-variant-numeric: tabular-nums; }
.chart-foot em { margin-left: 2px; color: #7c8ea3; font-style: normal; }
.chart-empty { margin: 10px 0; color: #7c8ea3; font-size: 11px; text-align: center; }
</style>
