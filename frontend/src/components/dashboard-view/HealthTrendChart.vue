<!--
  健康度趋势图（专屏 · 健康度观测）
  ============================================================================
  2026-11-17 由手绘 SVG 改为 ECharts：
  1) 原实现按「数据点序号」等距铺开，一个 5 分钟窗口和一天在图上占一样宽。
     健康度历史里常有「个别指标新写入、分数为 0」的稀疏窗口，会被拉成半屏
     悬崖，看起来像系统崩了，其实是采样点太少。
     现改为 time 轴，按真实时间分布绘制，稀疏窗口只占真实的一小段。
  2) 补全可读性：Y 轴刻度与网格、X 轴时间刻度、悬停读数、90/70 分界线、
     最新值标签、样本数提示。
  ============================================================================
-->
<template>
  <div class="health-trend-chart">
    <el-empty v-if="!seriesData.length" description="暂无健康度趋势" :image-size="64" />
    <template v-else>
      <div ref="chartRef" class="chart-canvas" :style="{ height: `${height}px` }"></div>
      <div class="chart-foot">
        <span><i class="dot-line"></i>健康度（按时间窗内各指标算术平均）</span>
        <span><i class="dot-label"></i>最近有效 <b>{{ latest.value }}</b> 分 · {{ statusText(latest.value) }}<template v-if="latest.time">（{{ formatTime(latest.time) }}）</template></span>
        <span v-if="scoreRange.max != null">区间 <b>{{ scoreRange.min }}</b> ~ <b>{{ scoreRange.max }}</b> 分</span>
        <span v-if="yZoomed">纵轴已放大到 {{ yDomain.min }} ~ {{ yDomain.max }} 分</span>
        <span v-if="hiddenCount" class="foot-hint">已隐藏 {{ hiddenCount }} 个低采样时间窗（当时更新指标不足总体三成）</span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ensureHealthThresholds, healthThresholds } from '@/utils/healthThresholds'

const props = defineProps({
  /**
   * 趋势点：{ time | recordTime | snapshotTime, healthScore | value, count? }
   * count 为该时间窗参与平均的指标数，用于在悬停时提示样本量。
   */
  points: { type: Array, default: () => [] },
  height: { type: Number, default: 240 }
})

const chartRef = ref()
let chart = null
let observer = null

const statusOf = score => {
  if (score == null) return 'unknown'
  if (Number(score) < healthThresholds.alarm) return 'danger'
  if (Number(score) < healthThresholds.warning) return 'warning'
  return 'normal'
}
const statusText = score => {
  const status = statusOf(score)
  return status === 'danger' ? '异常' : status === 'warning' ? '关注' : status === 'unknown' ? '待计算' : '正常'
}
const pad = value => String(value).padStart(2, '0')
const formatTime = ms => {
  const d = new Date(ms)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
/** 同一时间戳可能来自多个来源，先合并成一个读数，避免 time 轴上出现重复点。 */
function parseTime (value) {
  const text = String(value || '').replace('T', ' ').trim()
  if (!text) return NaN
  const normalized = text.length === 16 ? `${text}:00` : text.slice(0, 19)
  const ms = new Date(normalized.replace(' ', 'T')).getTime()
  return Number.isFinite(ms) ? ms : NaN
}

const rawSeries = computed(() => {
  const buckets = new Map()
  ;(props.points || []).forEach(point => {
    const time = parseTime(point.time ?? point.recordTime ?? point.snapshotTime)
    const value = Number(point.healthScore ?? point.value)
    if (!Number.isFinite(time) || !Number.isFinite(value)) return
    const bucket = buckets.get(time) || { sum: 0, count: 0, samples: 0, total: 0 }
    bucket.sum += value
    bucket.count++
    bucket.samples = Math.max(bucket.samples, Number(point.count || 0) || 1)
    bucket.total = Math.max(bucket.total, Number(point.total || 0))
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
 * 低采样时间窗不参与绘制。
 * 各指标的健康度写入节奏不同，最新一个窗口常常只有少数指标刚更新，
 * 用这些小样本的平均值画「整体趋势」，会出现「78 → 0」的假跳水，
 * 和左侧当前健康度自相矛盾。这里要求单个时间窗至少覆盖三成指标。
 * 若过滤后不足 2 个点则回退全量，避免整张图变空。
 */
const seriesData = computed(() => {
  const list = rawSeries.value
  if (list.length <= 2) return list
  const filtered = list.filter(point => {
    const total = Number(point.total || 0)
    const least = total > 0 ? Math.max(2, Math.ceil(total * 0.3)) : 2
    return point.samples >= least
  })
  return filtered.length >= 2 ? filtered : list
})
const hiddenCount = computed(() => rawSeries.value.length - seriesData.value.length)
const latest = computed(() => seriesData.value[seriesData.value.length - 1] || { value: null, time: null })
const scoreRange = computed(() => {
  const values = seriesData.value.map(point => point.value)
  return values.length ? { min: Math.min(...values), max: Math.max(...values) } : { min: null, max: null }
})
/**
 * 纵轴范围：健康度常年稳定在 80~90 时，固定 0~100 的轴会让曲线贴着顶端、
 * 看起来就是一条直线。这里在数据区间上下各留 5 分、取整到 10 分，
 * 并把跨度不足 20 分的区间撑到 20 分，避免放大后把正常波动画成剧烈抖动。
 * 跨度本身超过 40 分时仍用 0~100 全尺，避免尺度误导。
 */
const yDomain = computed(() => {
  const values = seriesData.value.map(point => point.value)
  if (!values.length) return { min: 0, max: 100 }
  const dataMin = Math.min(...values)
  const dataMax = Math.max(...values)
  if (dataMax - dataMin > 40) return { min: 0, max: 100 }
  let min = Math.floor((dataMin - 5) / 10) * 10
  let max = Math.ceil((dataMax + 5) / 10) * 10
  if (max - min < 20) { min = Math.max(0, min - 10) }
  return { min: Math.max(0, min), max: Math.min(100, max) }
})
const yZoomed = computed(() => yDomain.value.min > 0 || yDomain.value.max < 100)

/** X 轴标签：跨度超过 2 天显示月-日，否则只显示时:分，避免 7 天视图被日期挤满。 */
function xAxisLabel (value) {
  const span = seriesData.value.length
    ? seriesData.value[seriesData.value.length - 1].time - seriesData.value[0].time
    : 0
  const d = new Date(value)
  return span > 2 * 24 * 3600 * 1000
    ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    : `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function buildOption () {
  const points = seriesData.value
  const dense = points.length > 48
  return {
    animation: false,
    grid: { left: 42, right: 26, top: 30, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: '#94a3b8', type: 'dashed' } },
      backgroundColor: 'rgba(15,23,42,.92)',
      borderWidth: 0,
      padding: [8, 10],
      textStyle: { color: '#f8fafc', fontSize: 11 },
      formatter: params => {
        const point = params?.[0]
        if (!point) return ''
        const [time, value] = point.value
        const samples = point.data?.samples
        const hint = samples ? `<br/><span style="color:#94a3b8">参与指标 ${samples} 项</span>` : ''
        return `${formatTime(time)}<br/>健康度 <b style="font-size:14px">${value}</b> 分 · ${statusText(value)}${hint}`
      }
    },
    xAxis: {
      type: 'time',
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisTick: { show: true, lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: '#94a3b8', fontSize: 10, hideOverlap: true, formatter: xAxisLabel },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      min: yDomain.value.min,
      max: yDomain.value.max,
      splitNumber: 5,
      name: '分',
      nameTextStyle: { color: '#94a3b8', fontSize: 10, padding: [0, 0, 0, -18] },
      axisLine: { show: true, lineStyle: { color: '#cbd5e1' } },
      axisTick: { show: true, lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: '#94a3b8', fontSize: 10 },
      splitLine: { lineStyle: { color: '#eef2f7', type: 'dashed' } }
    },
    series: [
      {
        name: '健康度',
        type: 'line',
        data: points.map(point => ({ value: [point.time, point.value], samples: point.samples })),
        showSymbol: !dense,
        symbol: 'circle',
        symbolSize: 5,
        smooth: false,
        lineStyle: { width: 2.4, color: '#0ea5e9' },
        itemStyle: { color: '#0ea5e9', borderColor: '#fff', borderWidth: 1 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(14,165,233,.24)' },
            { offset: 1, color: 'rgba(14,165,233,0)' }
          ])
        },
        // 最新一点直接标在图上，省得再去左边找当前分数
        endLabel: {
          show: !dense,
          formatter: params => `${params.value[1]} 分`,
          color: '#0284c7',
          fontSize: 11,
          fontWeight: 600,
          distance: 6
        },
        markLine: {
          silent: true,
          symbol: 'none',
          label: { position: 'insideEndTop', fontSize: 9, fontWeight: 600 },
          // 分界线取系统配置（system_config 的 metric.health.*），不写死 90/70：
          // 否则图上画 90 分界线、状态标签却按 60/80 判，同一张卡片里两套口径。
          // 纵轴放大后分界线可能落在可视范围外，超出范围的不画，避免画到坐标轴外
          data: [
            { yAxis: healthThresholds.warning, name: `关注线 ${healthThresholds.warning}`, lineStyle: { color: '#f59e0b', type: 'dashed', width: 1 }, label: { formatter: `关注线 ${healthThresholds.warning}`, color: '#b45309' } },
            { yAxis: healthThresholds.alarm, name: `异常线 ${healthThresholds.alarm}`, lineStyle: { color: '#ef4444', type: 'dashed', width: 1 }, label: { formatter: `异常线 ${healthThresholds.alarm}`, color: '#b91c1c' } }
          ].filter(item => item.yAxis >= yDomain.value.min && item.yAxis <= yDomain.value.max)
        }
      }
    ]
  }
}

function render () {
  if (!chartRef.value || !seriesData.value.length) return
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption(buildOption(), true)
}
function resize () { chart?.resize() }

onMounted(async () => {
  await nextTick()
  render()
  // 阈值是异步读配置的：读到后重画一次，保证分界线与状态判定一致
  ensureHealthThresholds()
  window.addEventListener('resize', resize)
  // 侧边栏折叠、分屏拖拽时容器宽度会变，单靠 window resize 捕捉不到
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
watch(() => props.points, async () => { await nextTick(); render() }, { deep: true })
watch(() => props.height, async () => { await nextTick(); resize() })
// 配置里的分档阈值到位（或管理员改了阈值）后重画分界线
watch(() => [healthThresholds.alarm, healthThresholds.warning], async () => { await nextTick(); render() })
</script>

<style scoped>
.health-trend-chart { display: grid; gap: 6px; }
.chart-canvas { width: 100%; min-width: 0; }
.chart-foot { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; color: var(--metric-text-muted); font-size: 10px; }
.chart-foot b { color: var(--metric-text); font-variant-numeric: tabular-nums; }
.dot-line, .dot-label { display: inline-block; width: 14px; height: 2px; margin-right: 4px; border-radius: 2px; background: #0ea5e9; vertical-align: middle; }
.dot-label { width: 6px; height: 6px; border-radius: 50%; }
.foot-hint { color: #b45309; }
</style>
