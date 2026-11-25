<template>
  <div class="metric-value-chart" :style="{ minHeight: `${height}px` }">
    <el-empty v-if="!sorted.length" description="暂无指标值 / 健康度历史" :image-size="54" />
    <div v-else ref="chartRef" class="chart-canvas" :style="{ height: `${height}px` }" />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  values: { type: Array, default: () => [] },
  unit: { type: String, default: '' },
  height: { type: Number, default: 180 }
})
const chartRef = ref()
let chart = null
// 合并后的单数组：同一分钟里的指标值/健康度各占一个字段；
// valueMissing 表示该分钟采集缺数（保留蓝线断点），只有健康度没有指标值的分钟不算缺数。
const sorted = computed(() => (props.values || [])
  .map(item => ({
    time: String(item.recordTime || item.time || item.dataTime || '').replace('T', ' ').slice(0, 19),
    value: item.value ?? item.metricValue ?? item.currentValue ?? null,
    healthScore: item.healthScore == null ? null : Number(item.healthScore),
    valueMissing: Boolean(item.valueMissing)
  }))
  .filter(item => item.time)
  .sort((a, b) => a.time.localeCompare(b.time)))

const timestamp = value => {
  const ts = Date.parse(String(value).replace(' ', 'T'))
  return Number.isFinite(ts) ? ts : null
}
function axisTimeLabel (value) {
  const date = new Date(Number(value))
  if (!Number.isFinite(date.getTime())) return ''
  const pad = number => String(number).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function render () {
  if (!sorted.value.length) { chart?.dispose(); chart = null; return }
  if (!chartRef.value) return
  // 空态/有数据来回切换时 canvas 会被重建；旧实例绑在已卸载的 DOM 上会导致新图空白，需要重init。
  if (chart && chart.getDom() !== chartRef.value) { chart.dispose(); chart = null }
  if (!chart) chart = echarts.init(chartRef.value)
  const points = sorted.value
  // 时间轴按真实时间落点：指标值（分钟级）与健康度（小时级）采样频率不同，
  // 用分类轴会互相插入空值把两条线都打断，所以这里用 time 轴各自定位。
  const valuePoints = points
    .filter(item => item.value != null || item.valueMissing)
    .map(item => [timestamp(item.time), item.value == null ? null : item.value])
    .filter(point => point[0] != null)
  const healthPoints = points
    .filter(item => item.healthScore != null)
    .map(item => [timestamp(item.time), item.healthScore])
    .filter(point => point[0] != null)
  chart.setOption({
    animation: false,
    color: ['#0ea5e9', '#10b981'],
    grid: { left: 48, right: 48, top: 34, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      valueFormatter: value => value == null ? '--' : value
    },
    legend: { top: 0, left: 0, itemWidth: 10, itemHeight: 6, textStyle: { fontSize: 10, color: '#64748b' } },
    xAxis: {
      type: 'time',
      axisLabel: { fontSize: 9, color: '#94a3b8', hideOverlap: true, formatter: axisTimeLabel },
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisTick: { show: false }
    },
    yAxis: [
      { type: 'value', name: props.unit || '', nameTextStyle: { fontSize: 9, color: '#94a3b8' }, axisLabel: { fontSize: 9, color: '#94a3b8' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
      { type: 'value', name: '健康度', nameTextStyle: { fontSize: 9, color: '#94a3b8' }, min: 0, max: 100, axisLabel: { fontSize: 9, color: '#94a3b8' }, splitLine: { show: false } }
    ],
    series: [
      {
        name: '指标值',
        type: 'line',
        yAxisIndex: 0,
        data: valuePoints,
        smooth: false,
        connectNulls: false,
        symbol: 'none',
        lineStyle: { width: 2 },
        areaStyle: { opacity: .12 }
      },
      {
        name: '健康度',
        type: 'line',
        yAxisIndex: 1,
        data: healthPoints,
        smooth: false,
        // 健康度采样频率通常低于指标值，允许跨空值连成趋势线（不会与指标值断点互相影响）。
        connectNulls: true,
        symbol: 'none',
        lineStyle: { width: 2 }
      }
    ]
  }, true)
}
function resize () { chart?.resize() }
onMounted(async () => { await nextTick(); render(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
watch(() => props.values, async () => { await nextTick(); render() }, { deep: true })
</script>

<style scoped>
.metric-value-chart { min-height: 180px; }
.chart-canvas { width: 100%; height: 180px; }
</style>