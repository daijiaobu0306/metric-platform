<template>
  <div class="health-trend-chart-wrapper">
    <div ref="chartRef" class="health-trend-chart" :style="{ height: height + 'px' }"></div>
    <div v-if="showDrillHint && canDrillDown" class="drill-hint">
      <el-icon><InfoFilled /></el-icon>
      <span>点击数据点可下钻查看更细粒度</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick, defineProps, defineEmits } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  // ECharts option
  option: {
    type: Object,
    required: true
  },
  // 图表高度
  height: {
    type: Number,
    default: 200
  },
  // 当前时间粒度（用于判断是否可下钻）
  timeRange: {
    type: String,
    default: '30d'
  },
  // 原始数据（用于计算点击点的时间）
  rawData: {
    type: Array,
    default: () => []
  },
  // 是否显示下钻提示
  showDrillHint: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['drilldown'])

const chartRef = ref(null)
let chartInstance = null

// 粒度下钻映射
const drilldownMap = {
  '30d': { target: '7d', label: '7天' },
  '7d': { target: '1d', label: '1天' },
  '1d': { target: '6h', label: '6小时' },
  '6h': { target: '1h', label: '1小时' },
  '1h': { target: '5m', label: '5分钟明细' }
}

// 判断当前粒度是否可下钻
const canDrillDown = computed(() => {
  return !!drilldownMap[props.timeRange]
})

// 初始化图表
const initChart = () => {
  if (!chartRef.value) return

  if (chartInstance) {
    chartInstance.dispose()
  }

  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption(props.option)

  // 绑定点击事件
  chartInstance.on('click', (params) => {
    if (!canDrillDown.value) return

    const dataIndex = params.dataIndex
    if (dataIndex === undefined || !props.rawData[dataIndex]) return

    const rawDataPoint = props.rawData[dataIndex]
    const drillInfo = drilldownMap[props.timeRange]

    if (!drillInfo) return

    // 计算下钻后的时间窗口
    const timeWindow = calculateDrilldownWindow(rawDataPoint.recordTime, props.timeRange)

    emit('drilldown', {
      fromTimeRange: props.timeRange,
      toTimeRange: drillInfo.target,
      pointTime: rawDataPoint.recordTime,
      dataPoint: rawDataPoint,
      ...timeWindow
    })
  })
}

// 计算下钻后的时间窗口
const calculateDrilldownWindow = (pointTime, fromRange) => {
  if (!pointTime) return {}

  const t = String(pointTime)
  // 解析时间
  const dateStr = t.replace('T', ' ').slice(0, 19)
  const date = new Date(dateStr)

  if (isNaN(date.getTime())) return {}

  let startTime, endTime

  switch (fromRange) {
    case '30d':
      // 30d → 7d：点击的是某天，切到该天所在的 7 天
      {
        const dayOfWeek = date.getDay() || 7 // 周日=7
        const weekStart = new Date(date)
        weekStart.setDate(date.getDate() - dayOfWeek + 1) // 周一
        const weekEnd = new Date(weekStart)
        weekEnd.setDate(weekStart.getDate() + 6) // 周日
        startTime = formatDate(weekStart)
        endTime = formatDate(weekEnd)
      }
      break
    case '7d':
      // 7d → 1d：点击的是某天，切到该天
      {
        startTime = formatDate(date)
        const dayEnd = new Date(date)
        dayEnd.setHours(23, 59, 59)
        endTime = formatDateTime(dayEnd)
      }
      break
    case '1d':
      // 1d → 6h：点击的是某天的某个 6h 段
      {
        const hour = date.getHours()
        const sixHourBlock = Math.floor(hour / 6) * 6
        const blockStart = new Date(date)
        blockStart.setHours(sixHourBlock, 0, 0)
        const blockEnd = new Date(blockStart)
        blockEnd.setHours(sixHourBlock + 5, 59, 59)
        startTime = formatDateTime(blockStart)
        endTime = formatDateTime(blockEnd)
      }
      break
    case '6h':
      // 6h → 1h：点击的是某小时
      {
        const hourStart = new Date(date)
        hourStart.setMinutes(0, 0)
        const hourEnd = new Date(hourStart)
        hourEnd.setMinutes(59, 59)
        startTime = formatDateTime(hourStart)
        endTime = formatDateTime(hourEnd)
      }
      break
    case '1h':
      // 1h → 5m：点击的是某个 5 分钟
      {
        const minute = date.getMinutes()
        const fiveMinBlock = Math.floor(minute / 5) * 5
        const blockStart = new Date(date)
        blockStart.setMinutes(fiveMinBlock, 0)
        const blockEnd = new Date(blockStart)
        blockEnd.setMinutes(fiveMinBlock + 4, 59)
        startTime = formatDateTime(blockStart)
        endTime = formatDateTime(blockEnd)
      }
      break
  }

  return { startTime, endTime }
}

const formatDate = (date) => {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const formatDateTime = (date) => {
  const datePart = formatDate(date)
  const h = String(date.getHours()).padStart(2, '0')
  const min = String(date.getMinutes()).padStart(2, '0')
  const s = String(date.getSeconds()).padStart(2, '0')
  return `${datePart} ${h}:${min}:${s}`
}

// 监听 option 变化更新图表
watch(() => props.option, () => {
  nextTick(() => {
    if (chartInstance) {
      chartInstance.setOption(props.option, true)
    } else {
      initChart()
    }
  })
}, { deep: true })

// 监听容器大小变化
const resizeObserver = ref(null)

onMounted(() => {
  nextTick(() => {
    initChart()
  })

  // 监听容器大小变化自动 resize
  if (chartRef.value) {
    resizeObserver.value = new ResizeObserver(() => {
      chartInstance?.resize()
    })
    resizeObserver.value.observe(chartRef.value)
  }
})

onUnmounted(() => {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
  if (resizeObserver.value) {
    resizeObserver.value.disconnect()
  }
})

// 暴露方法供父组件调用
defineExpose({
  getChartInstance: () => chartInstance,
  resize: () => chartInstance?.resize()
})
</script>

<style scoped>
.health-trend-chart-wrapper {
  position: relative;
}

.health-trend-chart {
  width: 100%;
}

.drill-hint {
  position: absolute;
  bottom: 4px;
  right: 8px;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #909399;
  opacity: 0.8;
}

.drill-hint .el-icon {
  font-size: 12px;
}
</style>
