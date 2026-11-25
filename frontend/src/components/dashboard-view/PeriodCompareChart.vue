<template>
  <div class="compare-chart" :style="{ height: `${height}px` }">
    <el-empty v-if="!current.length && !previous.length" description="暂无可对比数据" :image-size="54" />
    <div v-else ref="chartRef" class="chart-canvas" />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  current: { type: Array, default: () => [] },
  previous: { type: Array, default: () => [] },
  unit: { type: String, default: '' },
  health: { type: Boolean, default: false },
  height: { type: Number, default: 210 },
  threshold: { type: Number, default: null }
})
const chartRef = ref()
let chart = null
const currentPoints = computed(() => props.current || [])
const previousPoints = computed(() => props.previous || [])
const labels = computed(() => currentPoints.value.map((point, index) => timeLabel(point.time, index)))
function timeLabel (value, index) {
  const text = String(value || '').replace('T', ' ')
  if (text.length >= 16) return text.slice(5, 16)
  return `第 ${index + 1} 天`
}
function render () {
  if (!chartRef.value || (!currentPoints.value.length && !previousPoints.value.length)) return
  if (!chart) chart = echarts.init(chartRef.value)
  const markLines = []
  if (props.health) markLines.push({ yAxis: 90, label: { formatter: '关注 90', fontSize: 9, color: '#f43f5e' }, lineStyle: { color: '#f43f5e', type: 'dashed', opacity: .45 } })
  if (props.health) markLines.push({ yAxis: 70, label: { formatter: '异常 70', fontSize: 9, color: '#f43f5e' }, lineStyle: { color: '#f43f5e', type: 'dashed', opacity: .45 } })
  if (!props.health && props.threshold != null) markLines.push({ yAxis: props.threshold, label: { formatter: `阈值 ${props.threshold}`, fontSize: 9 }, lineStyle: { color: '#f43f5e', type: 'dashed' } })
  chart.setOption({
    animation: false,
    color: ['#0ea5e9', '#94a3b8'],
    grid: { left: 40, right: 20, top: 32, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, itemWidth: 10, itemHeight: 6, textStyle: { fontSize: 10, color: '#64748b' } },
    xAxis: { type: 'category', data: labels.value, axisLabel: { fontSize: 9, color: '#94a3b8', hideOverlap: true }, axisLine: { lineStyle: { color: '#e2e8f0' } }, axisTick: { show: false } },
    yAxis: { type: 'value', min: props.health ? 0 : undefined, max: props.health ? 100 : undefined, axisLabel: { fontSize: 9, color: '#94a3b8' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
    series: [
      {
        name: '本期',
        type: 'line',
        data: currentPoints.value.map(point => point.value),
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2.2 },
        areaStyle: { opacity: .12 },
        markLine: markLines.length ? { symbol: 'none', silent: true, data: markLines } : undefined
      },
      {
        name: '上期',
        type: 'line',
        data: previousPoints.value.map(point => point.value),
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1.6, type: 'dashed' }
      }
    ]
  })
}
function resize () { chart?.resize() }
onMounted(async () => { await nextTick(); render(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
watch(() => [props.current, props.previous], async () => { await nextTick(); render() }, { deep: true })
</script>

<style scoped>
.compare-chart { width: 100%; }
.chart-canvas { width: 100%; height: 100%; min-height: 210px; }
</style>
