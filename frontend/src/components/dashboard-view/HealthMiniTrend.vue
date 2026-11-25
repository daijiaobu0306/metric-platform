<!--
  健康度卡片内的迷你趋势图（专屏 · 健康度观测）
  ============================================================================
  原来卡片里用纯 SVG 折线画趋势：没有坐标轴、没有刻度，数据点少时只剩一条
  水平线加末尾一个大圆点，看起来像“小蝌蚪”，也读不出时间和分数。
  现改为 ECharts 迷你图：
  1) 横轴按真实时间布点并显示时:分刻度，纵轴显示分数刻度与虚线网格；
  2) 每个采样点画一个小圆点，悬停显示时间 + 分数（有参与指标数时一并显示）；
  3) 纵轴按数据区间自适应放大（稳定的 90+ 分也能看出波动），跨度大时回到 0~100；
  4) 卡片仍按健康状态着色（取容器 currentColor），不引入新配色。
  ============================================================================
-->
<template>
  <div class="health-mini-trend" :style="{ height: `${height}px` }">
    <div v-if="!series.length" class="mini-empty">当前区间暂无健康度采样</div>
    <div v-else ref="chartRef" class="mini-canvas"></div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  /** 趋势点：{ time | recordTime | snapshotTime, healthScore | value, count?, total? } */
  points: { type: Array, default: () => [] },
  height: { type: Number, default: 104 }
})

const chartRef = ref()
let chart = null
let observer = null

const pad = value => String(value).padStart(2, '0')
const formatTime = ms => {
  const d = new Date(Number(ms))
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
const parseTime = value => {
  const text = String(value || '').replace('T', ' ').trim()
  if (!text) return NaN
  const normalized = text.length === 16 ? `${text}:00` : text.slice(0, 19)
  const ms = new Date(normalized.replace(' ', 'T')).getTime()
  return Number.isFinite(ms) ? ms : NaN
}

/** 同一时间点可能来自多个来源，先合并平均，避免 time 轴上出现重复点。 */
const series = computed(() => {
  const buckets = new Map()
  ;(props.points || []).forEach(point => {
    const time = parseTime(point?.time ?? point?.recordTime ?? point?.snapshotTime)
    const value = Number(point?.healthScore ?? point?.value)
    if (!Number.isFinite(time) || !Number.isFinite(value)) return
    const bucket = buckets.get(time) || { sum: 0, count: 0, samples: 0, total: 0 }
    bucket.sum += value
    bucket.count++
    bucket.samples = Math.max(bucket.samples, Number(point?.count || 0) || 1)
    bucket.total = Math.max(bucket.total, Number(point?.total || 0))
    buckets.set(time, bucket)
  })
  return [...buckets.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([time, bucket]) => ({
      time,
      value: Math.round(bucket.sum / bucket.count),
      samples: bucket.samples,
      total: bucket.total
    }))
})

/**
 * 纵轴范围：数据稳定在 90 分以上时，固定 0~100 会变成一条贴顶直线；
 * 这里在数据上下各留 5 分、取整到 10 分，跨度不足 20 分时撑到 20 分；
 * 数据本身跨度超过 40 分仍用 0~100 全尺，避免放大造成误读。
 */
const yDomain = computed(() => {
  const values = series.value.map(point => point.value)
  if (!values.length) return { min: 0, max: 100 }
  const dataMin = Math.min(...values)
  const dataMax = Math.max(...values)
  if (dataMax - dataMin > 40) return { min: 0, max: 100 }
  let min = Math.floor((dataMin - 5) / 10) * 10
  let max = Math.ceil((dataMax + 5) / 10) * 10
  if (max - min < 20) min = Math.max(0, min - 10)
  return { min: Math.max(0, min), max: Math.min(100, max) }
})

/** 只有一个点时，时间轴需要人为撑开，否则点会落在轴边界上。 */
const xRange = computed(() => {
  const times = series.value.map(point => point.time)
  if (!times.length) return { min: undefined, max: undefined }
  if (times.length === 1) return { min: times[0] - 30 * 60 * 1000, max: times[0] + 30 * 60 * 1000 }
  return { min: undefined, max: undefined }
})

/** X 轴标签：跨度超过 2 天显示月-日，否则显示时:分。 */
function xLabel (value) {
  const points = series.value
  const span = points.length > 1 ? points[points.length - 1].time - points[0].time : 0
  const d = new Date(Number(value))
  return span > 2 * 24 * 3600 * 1000
    ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    : `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 卡片按健康状态着色：读容器 currentColor，跟随 .category-card.warning/.danger 的 CSS。 */
function toneColor () {
  const color = chartRef.value ? getComputedStyle(chartRef.value).color : ''
  return color && color !== 'rgba(0, 0, 0, 0)' ? color : '#0ea5e9'
}

function buildOption () {
  const points = series.value
  const color = toneColor()
  const dense = points.length > 60
  return {
    animation: false,
    grid: { left: 34, right: 14, top: 12, bottom: 20 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: '#94a3b8', type: 'dashed' } },
      backgroundColor: 'rgba(15,23,42,.92)',
      borderWidth: 0,
      padding: [7, 9],
      textStyle: { color: '#f8fafc', fontSize: 11 },
      formatter: params => {
        const point = params?.[0]
        if (!point) return ''
        const [time, value] = point.value
        const samples = point.data?.samples
        const hint = samples ? `<br/><span style="color:#94a3b8">参与指标 ${samples} 项</span>` : ''
        return `${formatTime(time)}<br/>健康度 <b style="font-size:13px">${value}</b> 分${hint}`
      }
    },
    xAxis: {
      type: 'time',
      min: xRange.value.min,
      max: xRange.value.max,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisTick: { show: true, lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: '#94a3b8', fontSize: 9, hideOverlap: true, formatter: xLabel },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      min: yDomain.value.min,
      max: yDomain.value.max,
      splitNumber: 2,
      axisLine: { show: true, lineStyle: { color: '#cbd5e1' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 9, formatter: value => `${Math.round(value)}` },
      splitLine: { lineStyle: { color: '#eef2f7', type: 'dashed' } }
    },
    series: [{
      name: '健康度',
      type: 'line',
      data: points.map(point => ({ value: [point.time, point.value], samples: point.samples })),
      // 每个采样点都画一个小圆点；点特别多时改为只画线，避免糊成一片。
      showSymbol: !dense,
      symbol: 'circle',
      symbolSize: 5,
      smooth: false,
      lineStyle: { width: 2, color },
      itemStyle: { color, borderColor: '#fff', borderWidth: 1 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(148,163,184,.20)' },
          { offset: 1, color: 'rgba(148,163,184,0)' }
        ])
      }
    }]
  }
}

async function render () {
  if (!series.value.length) {
    chart?.dispose()
    chart = null
    return
  }
  await nextTick()
  if (!chartRef.value) return
  if (chart && chart.getDom() !== chartRef.value) { chart.dispose(); chart = null }
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption(buildOption(), true)
  chart.resize()
}
function resize () { chart?.resize() }

onMounted(async () => {
  await render()
  window.addEventListener('resize', resize)
  if (chartRef.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(resize)
    observer.observe(chartRef.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})
watch(() => props.points, render, { deep: true })
</script>

<style scoped>
.health-mini-trend { position: relative; width: 100%; min-width: 0; }
.mini-canvas { width: 100%; height: 100%; }
.mini-empty { display: flex; align-items: center; justify-content: center; height: 100%; font-size: 10px; color: var(--metric-text-muted); }
</style>
