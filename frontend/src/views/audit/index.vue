<template>
  <div class="audit-container workspace-theme workspace-management">
    <WorkspacePageHeader title="变更审计" description="追踪原始数据导入、指标变更与用户登录记录，保留可查阅的操作凭证和变更轨迹。" />
    <div class="audit-shell">
      <!-- 标题统一由 WorkspacePageHeader 展示，页签区不再重复标题 -->
      <el-tabs v-model="activeTab" class="audit-tabs workspace-tabs management-tabs">
        <el-tab-pane label="原始数据变更" name="originalData"><template #label><WorkspaceTabLabel icon="Coin">原始数据变更</WorkspaceTabLabel></template>
          <!-- 筛选条件 -->
          <AuditOriginalDataFilters v-model:filters="originalDataQueryParams" :original-data-selected-rows="originalDataSelectedRows" @search="loadOriginalDataLogs" @reset="resetOriginalDataFilters" @delete="batchDeleteOriginalData" />

          <!-- 桌面端表格 -->
          <div class="desktop-table">
            <el-table v-sticky-header v-mirror-scroll :data="originalDataTableData" border v-loading="originalDataTableLoading" style="width: 100%;" @selection-change="onOriginalDataSelectionChange"><template #empty><WorkspaceEmptyState title="暂无原始数据变更记录" description="采集导入记录将在此展示，可调整数据类别筛选。" /></template>
              <el-table-column type="selection" width="40" fixed="left" />
              <el-table-column prop="subCategoryCode" label="数据类别" width="150">
                <template #default="{ row }">
                  {{ getSubCategoryName(row.subCategoryCode) }}
                </template>
              </el-table-column>
              <el-table-column prop="tableName" label="目标表" width="150" />
              <el-table-column prop="fileName" label="文件名" min-width="200" class-name="wrap-cell" />
              <el-table-column prop="recordCount" label="记录数" width="100" align="right">
                <template #default="{ row }">
                  {{ row.recordCount ? row.recordCount.toLocaleString() : 0 }}
                </template>
              </el-table-column>
              <el-table-column prop="importTime" label="导入时间" class-name="workspace-time-cell" width="160">
                <template #default="{ row }">{{ formatDateTime(row.importTime) }}</template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 移动端卡片 -->
          <div class="mobile-cards">
            <el-card v-for="row in originalDataTableData" :key="row.id" class="audit-card" shadow="hover">
              <div class="card-content">
                <div class="card-row">
                  <span class="label">数据类别：</span>
                  <span class="value">{{ getSubCategoryName(row.subCategoryCode) }}</span>
                </div>
                <div class="card-row">
                  <span class="label">目标表：</span>
                  <span class="value">{{ row.tableName }}</span>
                </div>
                <div class="card-row">
                  <span class="label">文件名：</span>
                  <span class="value">{{ row.fileName }}</span>
                </div>
                <div class="card-row">
                  <span class="label">记录数：</span>
                  <span class="value">{{ row.recordCount ? row.recordCount.toLocaleString() : 0 }}</span>
                </div>
                <div class="card-row">
                  <span class="label">导入时间：</span>
                  <span class="value">{{ formatDateTime(row.importTime) }}</span>
                </div>
              </div>
            </el-card>
            <el-empty v-if="originalDataTableData.length === 0" description="暂无数据" />
          </div>

          <WorkspacePagination v-model:page="originalDataPagination.page" v-model:size="originalDataPagination.size" :total="originalDataPagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadOriginalDataLogs" />
        </el-tab-pane>

        <el-tab-pane label="指标变更" name="metric"><template #label><WorkspaceTabLabel icon="CollectionTag">指标变更</WorkspaceTabLabel></template>
          <!-- 筛选条件 -->
          <AuditMetricFilters v-model:filters="queryParams" :metric-selected-rows="metricSelectedRows" @search="loadData" @reset="resetFilters" @delete="batchDeleteMetric" />

          <!-- 桌面端表格 -->
          <div class="desktop-table">
            <el-table v-sticky-header v-mirror-scroll :data="tableData" border v-loading="tableLoading" style="width: 100%;" @selection-change="onMetricSelectionChange"><template #empty><WorkspaceEmptyState title="暂无匹配的指标变更" description="可调整操作类型或重置筛选条件。" /></template>
              <el-table-column type="selection" width="40" fixed="left" />
              <el-table-column prop="operationType" label="操作类型" width="100">
                <template #default="{ row }">
                  <el-tag :type="getOperationTypeTag(row.operationType)">
                    {{ row.operationType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="entityType" label="实体类型" width="120" />
              <el-table-column prop="changeContent" label="变更内容" min-width="300">
                <template #default="{ row }">{{ formatChangeContent(row) }}</template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="100" />
              <el-table-column prop="createTime" label="操作时间" width="160">
                <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 移动端卡片 -->
          <div class="mobile-cards">
            <el-card v-for="row in tableData" :key="row.id" class="audit-card" shadow="hover">
              <div class="card-content">
                <div class="card-row">
                  <el-tag :type="getOperationTypeTag(row.operationType)" size="small">
                    {{ row.operationType }}
                  </el-tag>
                  <span class="time">{{ formatDateTime(row.createTime) }}</span>
                </div>
                <div class="card-row">
                  <span class="label">实体类型：</span>
                  <span class="value">{{ row.entityType }}</span>
                </div>
                <div class="card-row">
                  <span class="label">变更内容：</span>
                  <span class="value">{{ row.changeContent }}</span>
                </div>
                <div class="card-row">
                  <span class="label">操作人：</span>
                  <span class="value">{{ row.operator }}</span>
                </div>
              </div>
            </el-card>
            <el-empty v-if="tableData.length === 0" description="暂无数据" />
          </div>

          <WorkspacePagination v-model:page="pagination.page" v-model:size="pagination.size" :total="pagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadData" />
        </el-tab-pane>

        <el-tab-pane label="登录日志" name="loginLog"><template #label><WorkspaceTabLabel icon="User">登录日志</WorkspaceTabLabel></template>
          <!-- 统计看板 + 趋势图 -->
          <WorkspaceOverviewLayout>
            <template #stats><LoginAuditOverview :summary="loginLogStatsData.summary" :active-days="loginLogStatsData.daily.length" :format-duration="formatDuration" /></template>
            <template #trend>
<div class="login-log-trend-area trend-area">

                <div class="login-log-trend-header trend-header">
                  <WorkspaceSectionHeading title="每日登录次数趋势" description="按所选周期查看登录次数与访问节奏" icon="TrendCharts" />
                  <el-radio-group v-model="loginLogTrendDimension" size="small" @change="loadLoginLogStats">
                    <el-radio-button value="today">今天</el-radio-button>
                    <el-radio-button value="week">近一周</el-radio-button>
                    <el-radio-button value="month">近一月</el-radio-button>
                    <el-radio-button value="year">近一年</el-radio-button>
                  </el-radio-group>
                </div>
                <div class="trend-chart-wrapper"><div ref="loginLogTrendChartDom" class="login-log-trend-chart trend-chart"></div>
                <div v-if="loginLogStatsData.daily.length === 0" class="login-log-trend-empty">
                  <el-icon size="20" color="#909399"><WarningFilled /></el-icon>
                  <span>当前时间范围内暂无登录数据</span>
                </div>
              </div>
</div>
            </template>
          </WorkspaceOverviewLayout>

          <!-- 筛选条件 -->
          <AuditLoginFilters v-model:filters="loginLogParams" :login-log-selected-rows="loginLogSelectedRows" @search="loadLoginLogs" @reset="resetLoginLogFilters" @delete="batchDeleteLoginLog" />

          <!-- 桌面端表格 -->
          <div class="desktop-table">
            <el-table v-sticky-header v-mirror-scroll :data="loginLogData" border v-loading="loginLogLoading" style="width: 100%;" @selection-change="onLoginLogSelectionChange"><template #empty><WorkspaceEmptyState title="暂无匹配的登录记录" description="可调整用户、设备及时间范围筛选。" /></template>
              <el-table-column type="selection" width="40" fixed="left" />
              <el-table-column prop="username" label="用户名" min-width="90" />
              <el-table-column prop="clientIp" label="客户端IP" width="130" />
              <el-table-column label="浏览器" min-width="140">
                <template #default="{ row }">
                  {{ row.browserName }} {{ row.browserVersion }}
                </template>
              </el-table-column>
              <el-table-column prop="osName" label="操作系统" min-width="100" />
              <el-table-column prop="deviceType" label="设备" min-width="90">
                <template #default="{ row }">
                  {{ { desktop: '桌面', tablet: '平板', mobile: '手机' }[row.deviceType] || row.deviceType }}
                </template>
              </el-table-column>
              <el-table-column label="屏幕" min-width="100" align="center">
                <template #default="{ row }">
                  {{ row.screenWidth && row.screenHeight ? row.screenWidth + '×' + row.screenHeight : '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="loginTime" label="登录时间" class-name="workspace-time-cell" width="160">
                <template #default="{ row }">{{ formatDateTime(row.loginTime) }}</template>
              </el-table-column>
              <el-table-column prop="durationMinutes" label="在线时长" width="90" align="right">
                <template #default="{ row }">
                  {{ formatDuration(row.durationMinutes) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="70" align="center">
                <template #default="{ row }">
                  <WorkspaceStatusBadge :tone="row.isActive === 1 ? 'success' : 'muted'" :pulse="row.isActive === 1">
                    {{ row.isActive === 1 ? '在线' : '离开' }}
                  </WorkspaceStatusBadge>
                </template>
              </el-table-column>
              <el-table-column prop="logoutTime" label="离开时间" width="160">
                <template #default="{ row }">{{ row.logoutTime ? formatDateTime(row.logoutTime) : '-' }}</template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 移动端卡片 -->
          <div class="mobile-cards">
            <el-card v-for="row in loginLogData" :key="row.id" class="audit-card" shadow="hover">
              <div class="card-content">
                <div class="card-row">
                  <span>
                    <WorkspaceStatusBadge :tone="row.isActive === 1 ? 'success' : 'muted'" :pulse="row.isActive === 1">
                      {{ row.isActive === 1 ? '在线' : '离开' }}
                    </WorkspaceStatusBadge>
                    {{ row.username }}
                  </span>
                  <span class="time">{{ formatDateTime(row.loginTime) }}</span>
                </div>
                <div class="card-row">
                  <span class="label">IP：</span>
                  <span class="value">{{ row.clientIp }}</span>
                </div>
                <div class="card-row">
                  <span class="label">浏览器：</span>
                  <span class="value">{{ row.browserName }} {{ row.browserVersion }} / {{ row.osName }}</span>
                </div>
                <div class="card-row">
                  <span class="label">在线时长：</span>
                  <span class="value">{{ formatDuration(row.durationMinutes) }}</span>
                </div>
              </div>
            </el-card>
            <el-empty v-if="loginLogData.length === 0" description="暂无数据" />
          </div>

          <WorkspacePagination v-model:page="loginLogPagination.page" v-model:size="loginLogPagination.size" :total="loginLogPagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadLoginLogs" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import { readWorkspaceChartTheme } from '@/utils/workspaceTheme'
import WorkspaceOverviewLayout from '@/components/workspace/WorkspaceOverviewLayout.vue'
import LoginAuditOverview from '@/components/audit/LoginAuditOverview.vue'
import WorkspacePagination from '@/components/workspace/WorkspacePagination.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import AuditLoginFilters from '@/components/audit/AuditLoginFilters.vue'
import AuditMetricFilters from '@/components/audit/AuditMetricFilters.vue'
import AuditOriginalDataFilters from '@/components/audit/AuditOriginalDataFilters.vue'
import { ref, onMounted, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled, Search } from '@element-plus/icons-vue'
import { metricApi, originalDataImportApi, sessionApi } from '../../api'
import { formatDateTime } from '../../utils'
import * as echarts from 'echarts'

const activeTab = ref('originalData')

// 批量选中行
const originalDataSelectedRows = ref([])
const metricSelectedRows = ref([])
const loginLogSelectedRows = ref([])
const onOriginalDataSelectionChange = (rows) => { originalDataSelectedRows.value = rows }
const onMetricSelectionChange = (rows) => { metricSelectedRows.value = rows }
const onLoginLogSelectionChange = (rows) => { loginLogSelectedRows.value = rows }

// 原始数据变更Tab数据
const originalDataTableData = ref([])
const originalDataTableLoading = ref(false)
const originalDataPagination = ref({ page: 1, size: 20, total: 0 })
const originalDataQueryParams = ref({
  subCategoryCode: ''
})

// 指标变更Tab数据
const tableData = ref([])
const tableLoading = ref(false)
const pagination = ref({ page: 1, size: 10, total: 0 })
const queryParams = ref({
  operationType: ''
})

// 数据类别名称映射
const subCategoryNameMap = {
  'jihe': '系统稽核',
  'jihe_detail': '系统稽核明细',
  'performance': '运行效率',
  'error': '错误',
  'error_frontend': '前端错误',
  'inner': '内部'
}

const getSubCategoryName = (code) => {
  return subCategoryNameMap[code] || code || '-'
}

const getOperationTypeTag = (type) => {
  switch (type) {
    case '新增': return 'success'
    case '修改': return 'warning'
    case '删除': return 'danger'
    default: return 'info'
  }
}

const formatChangeContent = (row) => {
  if (row.operationType === '新增' || row.operationType === '删除') {
    return row.changeContent || '-'
  }
  const parts = []
  if (row.fieldName) parts.push(`字段【${row.fieldName}】`)
  if (row.oldValue) parts.push(`旧值【${row.oldValue}】`)
  if (row.newValue) parts.push(`新值【${row.newValue}】`)
  return parts.length > 0 ? parts.join('，') : (row.changeContent || '-')
}

const loadOriginalDataLogs = async () => {
  originalDataTableLoading.value = true
  try {
    const params = {
      page: originalDataPagination.value.page - 1,
      size: originalDataPagination.value.size,
      subCategoryCode: originalDataQueryParams.value.subCategoryCode || null
    }
    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })
    const res = await originalDataImportApi.getLogs(params)
    originalDataTableData.value = res.data.content || []
    originalDataPagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载失败:', error)
    ElMessage.error('加载失败')
  } finally {
    originalDataTableLoading.value = false
  }
}

const resetOriginalDataFilters = () => {
  originalDataQueryParams.value.subCategoryCode = ''
  originalDataPagination.value.page = 1
  loadOriginalDataLogs()
}

const loadData = async () => {
  tableLoading.value = true
  try {
    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      operationType: queryParams.value.operationType || null
    }
    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })
    const res = await metricApi.getAuditLogs(params)
    tableData.value = res.data.content || []
    pagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载失败:', error)
    ElMessage.error('加载失败')
  } finally {
    tableLoading.value = false
  }
}

const resetFilters = () => {
  queryParams.value.operationType = ''
  pagination.value.page = 1
  loadData()
}

// ===== 登录日志 Tab =====
const loginLogData = ref([])
const loginLogLoading = ref(false)
const loginLogPagination = ref({ page: 1, size: 20, total: 0 })

// 登录日志统计看板（Issue 1B）
const loginLogStatsData = ref({
  summary: { totalCount: 0, totalDurationMinutes: 0, distinctUserCount: 0 },
  daily: []
})
const loginLogTrendDimension = ref('week')
const loginLogTrendChartDom = ref(null)
const loginLogTrendChart = ref(null)
const loginLogParams = ref({
  username: '',
  clientIp: '',
  browserName: '',
  osName: '',
  deviceType: '',
  isActive: '',
  dateRange: null
})

const formatDuration = (minutes) => {
  if (!minutes || minutes <= 0) return '0 分钟'
  if (minutes < 60) return minutes + ' 分钟'
  const hours = Math.floor(minutes / 60)
  const remainMin = minutes % 60
  if (hours < 24) return hours + ' 时' + (remainMin > 0 ? remainMin + ' 分' : '')
  const days = Math.floor(hours / 24)
  return days + ' 天' + (hours % 24 > 0 ? (hours % 24) + ' 时' : '')
}

const loadLoginLogs = async () => {
  loginLogLoading.value = true
  try {
    const params = {
      page: loginLogPagination.value.page - 1,
      size: loginLogPagination.value.size,
    }
    const p = loginLogParams.value
    if (p.username) params.username = p.username
    if (p.clientIp) params.clientIp = p.clientIp
    if (p.browserName) params.browserName = p.browserName
    if (p.osName) params.osName = p.osName
    if (p.deviceType) params.deviceType = p.deviceType
    if (p.isActive !== '' && p.isActive !== null) params.isActive = p.isActive
    if (p.dateRange && p.dateRange[0]) params.loginTimeStart = p.dateRange[0]
    if (p.dateRange && p.dateRange[1]) params.loginTimeEnd = p.dateRange[1]

    const res = await sessionApi.getLogs(params)
    loginLogData.value = res.data.content || []
    loginLogPagination.value.total = res.data.totalElements || 0
    // 列表刷新时同步刷新看板（沿用当前 filter）
    loadLoginLogStats()
  } catch (error) {
    console.error('加载登录日志失败:', error)
    ElMessage.error('加载登录日志失败')
  } finally {
    loginLogLoading.value = false
  }
}

// ============================================================
// 登录日志统计看板（Issue 1B）
//   - 与筛选条件同步：用户名 / 客户端 IP / 浏览器 / OS / 设备 影响列表也影响统计
//   - 看板自身的时间范围（today/week/month/year）独立于列表的日期范围
// ============================================================

const loadLoginLogStats = async () => {
  try {
    const p = loginLogParams.value
    const params = { timeRange: loginLogTrendDimension.value }
    if (p.username) params.username = p.username
    if (p.clientIp) params.clientIp = p.clientIp
    if (p.browserName) params.browserName = p.browserName
    if (p.osName) params.osName = p.osName
    if (p.deviceType) params.deviceType = p.deviceType
    const res = await sessionApi.getStats(params)
    loginLogStatsData.value = {
      summary: (res && res.data && res.data.summary) || { totalCount: 0, totalDurationMinutes: 0, distinctUserCount: 0 },
      daily: (res && res.data && res.data.daily) || []
    }
    await nextTick()
    drawLoginLogTrendChart()
  } catch (error) {
    console.error('加载登录日志统计失败:', error)
  }
}

const drawLoginLogTrendChart = () => {
  if (!loginLogTrendChartDom.value) return
  const daily = loginLogStatsData.value.daily || []
  const xAxisData = daily.map(d => d.date ? d.date.substring(5) : '')  // 只显示 MM-DD
  const seriesData = daily.map(d => d.count)

  if (!loginLogTrendChart.value) {
    loginLogTrendChart.value = echarts.init(loginLogTrendChartDom.value)
    const ro = new ResizeObserver(() => loginLogTrendChart.value && loginLogTrendChart.value.resize())
    ro.observe(loginLogTrendChartDom.value)
    loginLogTrendChartDom.value._ro = ro
  }

  const chartTheme = readWorkspaceChartTheme(loginLogTrendChartDom.value)
  const option = {
    backgroundColor: chartTheme.surface,
    textStyle: { color: chartTheme.text },
    tooltip: {
      trigger: 'axis',
      confine: true,
      formatter: (params) => {
        const p = params[0]
        const row = daily[p.dataIndex] || {}
        return `${row.date || ''}<br/>`
             + `登录次数: <b>${row.count}</b><br/>`
             + `登录人数: ${row.userCount}<br/>`
             + `总时长: ${formatDuration(row.durationMinutes)}`
      }
    },
    grid: { left: '3%', right: '4%', bottom: '8%', top: '20px', containLabel: true },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: { fontSize: 10, interval: xAxisData.length > 12 ? 1 : 0 }
    },
    yAxis: { type: 'value', min: 0, splitNumber: 4 },
    series: [{
      type: 'line',
      data: seriesData,
      smooth: true,
      label: { show: true, position: 'top', fontSize: 10, formatter: '{c}' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: chartTheme.areaStart },
          { offset: 1, color: chartTheme.areaEnd }
        ])
      },
      lineStyle: { color: chartTheme.primary, width: 2 },
      itemStyle: { color: chartTheme.primary }
    }]
  }
  loginLogTrendChart.value.setOption(option, true)
}

const resetLoginLogFilters = () => {
  loginLogParams.value = {
    username: '',
    clientIp: '',
    browserName: '',
    osName: '',
    deviceType: '',
    isActive: '',
    dateRange: null
  }
  loginLogPagination.value.page = 1
  loadLoginLogs()
}

watch(activeTab, (newTab) => {
  if (newTab === 'metric') {
    loadData()
  } else if (newTab === 'loginLog') {
    loadLoginLogs()
    loadLoginLogStats()
  }
})

onMounted(() => {
  loadOriginalDataLogs()
})

// ===== 批量删除 =====
const batchDeleteOriginalData = async () => {
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${originalDataSelectedRows.value.length} 条记录？`, '提示', { type: 'warning' })
    const ids = originalDataSelectedRows.value.map(r => r.id)
    await originalDataImportApi.batchDelete(ids)
    ElMessage.success('删除成功')
    loadOriginalDataLogs()
  } catch (e) { if (e !== 'cancel') { console.error(e); ElMessage.error('删除失败') } }
}
const batchDeleteMetric = async () => {
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${metricSelectedRows.value.length} 条记录？`, '提示', { type: 'warning' })
    const ids = metricSelectedRows.value.map(r => r.id)
    await metricApi.batchDeleteAuditLogs(ids)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) { if (e !== 'cancel') { console.error(e); ElMessage.error('删除失败') } }
}
const batchDeleteLoginLog = async () => {
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${loginLogSelectedRows.value.length} 条记录？`, '提示', { type: 'warning' })
    const ids = loginLogSelectedRows.value.map(r => r.id)
    await sessionApi.batchDelete(ids)
    ElMessage.success('删除成功')
    loadLoginLogs()
  } catch (e) { if (e !== 'cancel') { console.error(e); ElMessage.error('删除失败') } }
}
</script>

<style scoped>
/* 登录日志统计看板（Issue 1B） */
.login-log-stat-cards {
  background: var(--ui-page);
  border-radius: 8px;
  padding: 10px;
  height: 100%;
  min-height: 100px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 8px;
}
.login-log-stat-card {
  background: var(--ui-surface);
  border-radius: 6px;
  padding: 6px;
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  border: 1px solid #e8eef5;
}
.login-log-stat-num {
  font-size: 20px;
  font-weight: 600;
  color: var(--ui-heading);
  line-height: 1.4;
  word-break: break-all;
}
.login-log-stat-desc {
  font-size: 12px;
  color: var(--ui-muted);
}
.login-log-trend-area {
  background: var(--ui-page);
  border-radius: 8px;
  padding: 12px;
  height: 100%;
  min-height: 100px;
  position: relative;
}
.login-log-trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.login-log-trend-title {
  font-size: 13px;
  color: var(--ui-text);
  font-weight: 500;
}
.login-log-trend-chart {
  width: 100%;
  height: 130px;
}
.login-log-trend-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--ui-muted);
  font-size: 12px;
  pointer-events: none;
}

.audit-container {
  padding: 20px;
}

.audit-tabs {
  margin-top: 0;
}

.filter-row {
  display: flex;
  gap: 10px;
  margin-bottom: 15px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .desktop-table {
    display: none;
  }
  .mobile-cards {
    display: block;
  }
}

@media (min-width: 769px) {
  .desktop-table {
    display: block;
  }
  .mobile-cards {
    display: none;
  }
}

.audit-card {
  margin-bottom: 10px;
}

.card-content .card-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 14px;
}

.card-content .card-row .label {
  color: var(--ui-muted);
}

.card-content .card-row .time {
  color: var(--ui-muted);
  font-size: 12px;
}
</style>

<style scoped src="@/views/audit/prototype-theme.css"></style>
