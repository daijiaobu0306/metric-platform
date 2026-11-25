<template>
  <div class="compare-bar-chart" :style="{ height: `${height}px` }">
    <el-empty v-if="!rows.length" description="暂无可对比数据" :image-size="54" />
    <div v-else ref="chartRef" class="chart-canvas" />
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  health: { type: Boolean, default: false },
  period1Label: { type: String, default: '上周期' },
  period2Label: { type: String, default: '本周期' },
  height: { type: Number, default: 240 }
})
const chartRef = ref()
let chart = null

// 本周期柱按语义上色：改善=绿、劣化=红、同档微调/持平/方向不明=浅灰。
// 不用「涨红跌绿」——对超时数、失败率这类越低越好的指标，上涨是坏消息。
// same_level 是健康度专有状态（分数变了但没跨档），必须显式给中性色，
// 否则会回落到 series 默认的主题蓝，看起来像第三种语义。
const IMPROVEMENT_COLOR = {
  improved: '#2f9e6e',
  deteriorated: '#d64545',
  same_level: '#cbd5e1',
  flat: '#cbd5e1',
  neutral: '#cbd5e1'
}

function period2Data () {
  return props.rows.map(r => {
    const color = IMPROVEMENT_COLOR[r.improvement]
    return color ? { value: r.period2Value, itemStyle: { color } } : r.period2Value
  })
}

function dispose () {
  chart?.dispose()
  chart = null
}

function render () {
  // rows 从有到空时模板切到 el-empty，chartRef 变 null，但 ECharts 实例仍挂在已脱离文档的
  // canvas 上。这里必须主动 dispose，否则下次再有数据时 if (!chart) 为假，会继续往这个
  // 死掉的 canvas 上 setOption，用户看到的就是一片空白（先查有数据 → 查空 → 再查回有数据）。
  if (!props.rows.length || !chartRef.value) { dispose(); return }
  if (!chart) chart = echarts.init(chartRef.value)
  const names = props.rows.map(r => r.name || '')
  chart.setOption({
    animation: false,
    color: ['#94a3b8', '#0ea5e9'],
    grid: { left: 48, right: 24, top: 34, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, itemWidth: 10, itemHeight: 6, textStyle: { fontSize: 10, color: '#64748b' } },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: { fontSize: 10, color: '#64748b', interval: 0, rotate: names.length > 6 ? 28 : 0 },
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      min: props.health ? 0 : undefined,
      max: props.health ? 100 : undefined,
      axisLabel: { fontSize: 9, color: '#94a3b8' },
      splitLine: { lineStyle: { color: '#f1f5f9' } }
    },
    series: [
      { name: props.period1Label, type: 'bar', barMaxWidth: 26, data: props.rows.map(r => r.period1Value) },
      { name: props.period2Label, type: 'bar', barMaxWidth: 26, data: period2Data() }
    ]
  }, true)
}
function resize () { chart?.resize() }
onMounted(async () => { await nextTick(); render(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); dispose() })
watch(() => props.rows, async () => { await nextTick(); render() }, { deep: true })
</script>

<style scoped>
.compare-bar-chart { width: 100%; }
.chart-canvas { width: 100%; height: 100%; min-height: 220px; }
</style>