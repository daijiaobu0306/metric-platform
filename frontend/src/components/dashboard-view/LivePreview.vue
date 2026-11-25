<template>
  <div class="live-preview">
    <div class="preview-header">
      <span>{{ viewName }}</span>
      <el-tag size="small" type="info">预览</el-tag>
    </div>
    <div class="preview-content">
      <div v-if="modules.length === 0" class="empty-preview">
        <el-empty description="暂无模块配置" :image-size="60" />
      </div>
      <div v-else class="modules-grid">
        <div
          v-for="(module, index) in modules"
          :key="index"
          class="module-card"
          :style="getModuleStyle(module)"
        >
          <div class="module-header">
            <span>{{ module.title || '模块 ' + (index + 1) }}</span>
            <el-tag size="small" type="info">{{ getTypeLabel(module.type) }}</el-tag>
          </div>
          <div class="module-body">
            <!-- 加载状态 -->
            <div v-if="moduleLoading[index]" class="module-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>加载中...</span>
            </div>
            <!-- 错误状态 -->
            <div v-else-if="moduleError[index]" class="module-error">
              <el-icon><WarningFilled /></el-icon>
              <span>{{ moduleError[index] }}</span>
            </div>
            <!-- 指标卡（统计类） -->
            <div v-else-if="module.config?.display?.mode === 'card'" class="metric-card-content">
              <div class="metric-value">
                {{ formatValue(getNestedValue(moduleData[index], module.config.display.valueField)) }}
                <span v-if="module.config.display.suffix" class="metric-suffix">{{ module.config.display.suffix }}</span>
              </div>
              <div class="metric-label">{{ module.title }}</div>
            </div>
            <!-- 表格 -->
            <div v-else-if="module.config?.display?.mode === 'table'" class="table-container">
              <el-table :data="(moduleData[index] || []).slice(0, 10)" size="small" border max-height="250" style="width: 100%;">
                <el-table-column
                  v-for="col in (module.config.display.columns || [])"
                  :key="col.prop"
                  :prop="col.prop"
                  :label="col.label"
                  :width="col.width || 150"
                  class-name="wrap-cell"
                />
              </el-table>
              <div v-if="!moduleData[index]?.length" class="empty-hint">暂无数据</div>
            </div>
            <!-- 图表（折线/柱状/饼图） -->
            <div v-else-if="module.config?.display?.mode === 'line_chart' || module.config?.display?.mode === 'bar_chart' || module.config?.display?.mode === 'pie_chart'"
                 class="chart-container">
              <div v-if="!moduleData[index]?.length" class="chart-placeholder">
                <el-empty description="暂无数据" :image-size="40" />
              </div>
              <div v-else :ref="el => chartRefs[index] = el" class="echarts-container"></div>
            </div>
            <!-- 空数据 -->
            <div v-else class="unknown-module">
              <span>不支持的展示模式: {{ module.config?.display?.mode }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { alarmApi, gongdanApi, issueApi, dataApi } from '../../api'

const props = defineProps({
  viewName: { type: String, default: '' },
  modules: { type: Array, default: () => [] }
})

const chartRefs = ref([])
const moduleData = ref({})
const moduleLoading = ref({})
const moduleError = ref({})
let charts = {}

const typeLabels = {
  alarm_list: '告警列表',
  alarm_statistics: '告警统计',
  alarm_trend: '告警趋势',
  gongdan_list: '工单列表',
  gongdan_statistics: '工单统计',
  issue_list: '问题列表',
  issue_statistics: '问题统计',
  data_aggregation: '数据汇聚',
  realtime_metric: '实时指标',
  health_trend: '健康度趋势',
  metric_trend: '指标值趋势'
}

const getTypeLabel = (type) => typeLabels[type] || type

const getModuleStyle = (module) => {
  const style = {}
  if (module.width) style.width = typeof module.width === 'number' ? module.width + 'px' : module.width
  if (module.height) style.height = typeof module.height === 'number' ? module.height + 'px' : module.height
  return style
}

const getNestedValue = (obj, path) => {
  if (!path || !obj) return '-'
  return path.split('.').reduce((o, k) => (o || {})[k], obj) ?? '-'
}

const formatValue = (val) => {
  if (val === null || val === undefined || val === '-') return '-'
  if (typeof val === 'number') return val.toLocaleString()
  return val
}

// 根据 module type 调用对应 API
const fetchModuleData = async (module) => {
  const { type, config } = module
  const { params } = config.dataSource || {}

  switch (type) {
    case 'alarm_list':
      return alarmApi.list({ ...params, page: 0, size: 20 })
    case 'alarm_statistics':
      return alarmApi.statistics()
    case 'alarm_trend':
      return alarmApi.getTrend(params || {})
    case 'gongdan_list':
      return gongdanApi.list({ ...params, page: 0, size: 20 })
    case 'gongdan_statistics':
      return gongdanApi.statistics()
    case 'issue_list':
      return issueApi.list({ ...params, page: 0, size: 20 })
    case 'issue_statistics':
      return issueApi.statistics()
    case 'data_aggregation':
      return dataApi.getDetail({ ...params, page: 0, size: 20 })
    default:
      return Promise.resolve({ data: [] })
  }
}

const loadModuleData = async (module, index) => {
  moduleLoading.value[index] = true
  moduleError.value[index] = null

  try {
    const res = await fetchModuleData(module)
    // 统一处理返回结构：列表接口返回 content，数据接口直接返回 data
    const data = res.data?.content !== undefined ? res.data.content : (res.data || [])
    moduleData.value[index] = data
  } catch (e) {
    moduleError.value[index] = e.message || '加载失败'
    moduleData.value[index] = []
  } finally {
    moduleLoading.value[index] = false
  }
}

const loadAllModuleData = () => {
  // 先清空旧图表
  Object.values(charts).forEach(c => c.dispose())
  charts = {}
  moduleData.value = {}
  moduleLoading.value = {}
  moduleError.value = {}

  props.modules.forEach((module, index) => {
    loadModuleData(module, index)
  })
}

const initCharts = () => {
  nextTick(() => {
    props.modules.forEach((module, index) => {
      const data = moduleData.value[index]
      if (!data?.length) return

      const display = module.config?.display || {}
      const mode = display.mode

      if (mode === 'line_chart' || mode === 'bar_chart' || mode === 'pie_chart') {
        const el = chartRefs.value[index]
        if (!el) return

        if (charts[index]) {
          charts[index].dispose()
        }
        const chart = echarts.init(el)
        charts[index] = chart

        const xField = display.xField || 'time'
        const yField = display.yField || 'count'

        let option = {}
        if (mode === 'line_chart') {
          option = {
            tooltip: { trigger: 'axis' },
            xAxis: { type: 'category', data: data.map(d => d[xField]) },
            yAxis: { type: 'value' },
            series: [{ data: data.map(d => d[yField]), type: 'line', smooth: true }]
          }
        } else if (mode === 'bar_chart') {
          option = {
            tooltip: { trigger: 'axis' },
            xAxis: { type: 'category', data: data.map(d => d[xField]) },
            yAxis: { type: 'value' },
            series: [{ data: data.map(d => d[yField]), type: 'bar' }]
          }
        } else if (mode === 'pie_chart') {
          option = {
            tooltip: { trigger: 'item' },
            series: [{ type: 'pie', radius: '60%', data: data.map(d => ({ name: d[xField], value: d[yField] })) }]
          }
        }
        chart.setOption(option)
      }
    })
  })
}

watch(() => props.modules, async () => {
  loadAllModuleData()
  // 等待数据加载后初始化图表
  setTimeout(initCharts, 500)
}, { deep: true })

watch(moduleData, () => {
  initCharts()
}, { deep: true })

onMounted(() => {
  loadAllModuleData()
  setTimeout(initCharts, 800)
})

onUnmounted(() => {
  Object.values(charts).forEach(c => c.dispose())
  charts = {}
})
</script>

<style scoped>
.live-preview {
  background: var(--ui-surface);
  border-radius: 8px;
  overflow: hidden;
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
  font-weight: bold;
  color: #303133;
}

.preview-content {
  padding: 16px;
  max-height: 500px;
  overflow: auto;
}

.modules-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.module-card {
  background: #fafafa;
  border-radius: 6px;
  padding: 12px;
  border: 1px solid #ebeef5;
}

.module-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: bold;
  color: #606266;
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid #eee;
  gap: 8px;
}

.module-body {
  min-height: 80px;
}

.metric-card-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 8px;
}

.metric-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

.metric-suffix {
  font-size: 14px;
  color: #909399;
  margin-left: 2px;
}

.metric-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.module-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 20px;
  color: #909399;
  font-size: 12px;
}

.module-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 20px;
  color: #f56c6c;
  font-size: 12px;
}

.chart-container {
  height: 200px;
}

.echarts-container {
  width: 100%;
  height: 100%;
  min-height: 150px;
}

.chart-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.table-container {
  max-height: 250px;
  overflow: auto;
}

.empty-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 12px;
  padding: 20px;
}

.unknown-module {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 12px;
  padding: 20px;
}

.empty-preview {
  padding: 40px 0;
}
</style>
