<template>
  <div class="login-log-tab">
    <!-- ===== KPI 卡 第一行 4 个 ===== -->
    <el-row :gutter="12">
      <el-col :span="6">
        <KpiCard label="今日登录次数" :value="dashboard.todayLoginCount" />
      </el-col>
      <el-col :span="6">
        <KpiCard label="今日登录人数" :value="dashboard.todayLoginUserCount" />
      </el-col>
      <el-col :span="6">
        <KpiCard label="当前在线" :value="dashboard.onlineCount" />
      </el-col>
      <el-col :span="6">
        <KpiCard label="近 24h 活跃用户" :value="dashboard.last24hActiveUserCount" />
      </el-col>
    </el-row>

    <!-- ===== KPI 卡 第二行 3 个 ===== -->
    <el-row :gutter="12">
      <el-col :span="8">
        <KpiCard label="今日总登录时长" :value="formatMinutes(dashboard.todayTotalDurationMinutes)" />
      </el-col>
      <el-col :span="8">
        <KpiCard label="本周" :value="weekLabel" />
      </el-col>
      <el-col :span="8">
        <KpiCard label="本月" :value="monthLabel" />
      </el-col>
    </el-row>

    <!-- ===== 趋势折线图 ===== -->
    <el-card class="chart-card">
      <template #header>
        <div class="chart-header">
          <span>登录趋势</span>
          <el-radio-group v-model="trendMode" size="small" @change="renderTrendChart">
            <el-radio-button value="day">最近 30 天</el-radio-button>
            <el-radio-button value="hour">最近 24 小时</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <div ref="trendChartEl" class="trend-chart"></div>
    </el-card>

    <!-- ===== 多维筛选 ===== -->
    <el-card class="filter-card">
      <el-form :inline="true" :model="filters" class="login-log-filter" v-balanced-query>
        <el-form-item label="用户名">
          <el-input v-model="filters.username" clearable placeholder="模糊搜索" style="width: 140px" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="filters.source" clearable placeholder="全部" style="width: 100px">
            <el-option label="本地" value="LOCAL" />
            <el-option label="SSO" value="SSO" />
            <el-option label="退出" value="LOGOUT" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="filters.result" clearable placeholder="全部" style="width: 100px">
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 110px">
            <el-option label="在线" value="ONLINE" />
            <el-option label="已退出" value="OFFLINE" />
            <el-option label="超时退出" value="TIMEOUT" />
          </el-select>
        </el-form-item>
        <el-form-item label="IP">
          <el-input v-model="filters.clientIp" clearable placeholder="模糊" style="width: 130px" />
        </el-form-item>
        <el-form-item label="浏览器">
          <el-select v-model="filters.browserName" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="b in browserOptions" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item label="系统">
          <el-select v-model="filters.osName" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="o in osOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备">
          <el-select v-model="filters.deviceType" clearable placeholder="全部" style="width: 100px">
            <el-option label="桌面" value="desktop" />
            <el-option label="移动" value="mobile" />
            <el-option label="平板" value="tablet" />
          </el-select>
        </el-form-item>
        <el-form-item label="登录时间">
          <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至"
                          start-placeholder="开始" end-placeholder="结束"
                          value-format="YYYY-MM-DD HH:mm:ss"
                          style="width: 360px" />
        </el-form-item>
        <el-form-item class="query-actions">
          <el-button type="primary" @click="onQuery">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ===== 列表 ===== -->
    <el-table :data="rows" border stripe v-loading="loading" style="margin-top: 12px;">
      <el-table-column prop="loginTime" label="登录时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.loginTime) }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户" width="120" />
      <el-table-column label="来源" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="sourceTagType(row.source)" size="small">{{ sourceLabel(row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="clientIp" label="IP" width="140" />
      <el-table-column label="浏览器" width="130">
        <template #default="{ row }">
          <span>{{ row.browserName || '-' }}</span>
          <span v-if="row.browserVersion" class="version"> {{ row.browserVersion }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="osName" label="系统" width="100" />
      <el-table-column prop="deviceType" label="设备" width="80" />
      <el-table-column label="在线时长" width="100">
        <template #default="{ row }">{{ formatDuration(row.durationMinutes) }}</template>
      </el-table-column>
      <el-table-column label="退出时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.logoutTime) }}</template>
      </el-table-column>
      <el-table-column prop="failReason" label="失败原因" min-width="150" show-overflow-tooltip />
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 15px; justify-content: flex-end;"
      @size-change="loadList"
      @current-change="loadList"
    />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import * as echarts from 'echarts'
import { loginLogApi } from '../../api'
import KpiCard from '../../components/common/KpiCard.vue'

const loading = ref(false)
const rows = ref([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const filters = reactive({
  username: '',
  source: '',
  result: '',
  status: '',
  clientIp: '',
  browserName: '',
  osName: '',
  deviceType: '',
})
const timeRange = ref([])

const dashboard = reactive({
  todayLoginCount: 0,
  todayLoginUserCount: 0,
  onlineCount: 0,
  last24hActiveUserCount: 0,
  todayTotalDurationMinutes: 0,
  weekLoginUserCount: 0,
  weekTotalDurationMinutes: 0,
  monthLoginUserCount: 0,
  monthTotalDurationMinutes: 0,
  trend30d: [],
  trend24h: [],
})

const trendMode = ref('day')
const trendChartEl = ref(null)
let trendChart = null

const browserOptions = ['Chrome', 'Edge', 'Safari', 'Firefox', 'Opera', 'Other']
const osOptions = ['Windows', 'macOS', 'Linux', 'Android', 'iOS', 'Other']

const weekLabel = computed(() =>
  `${dashboard.weekLoginUserCount} 人 / ${formatMinutes(dashboard.weekTotalDurationMinutes)}`)
const monthLabel = computed(() =>
  `${dashboard.monthLoginUserCount} 人 / ${formatMinutes(dashboard.monthTotalDurationMinutes)}`)

// ===== 格式化 =====
const formatMinutes = (m) => {
  if (!m || m <= 0) return '-'
  const h = Math.floor(m / 60)
  const mm = m % 60
  return h > 0 ? `${h}h ${mm}m` : `${mm}m`
}
const formatDuration = (m) => formatMinutes(m)
const formatDateTime = (s) => {
  if (!s) return '-'
  return String(s).replace('T', ' ').substring(0, 19)
}
const sourceLabel = (s) => ({ LOCAL: '本地', SSO: 'SSO', LOGOUT: '退出' }[s] || s)
const sourceTagType = (s) => ({ LOCAL: '', SSO: 'success', LOGOUT: 'info' }[s] || '')
const statusLabel = (s) => ({ ONLINE: '在线', OFFLINE: '已退出', TIMEOUT: '超时退出' }[s] || s)
const statusTagType = (s) => ({ ONLINE: 'success', OFFLINE: 'info', TIMEOUT: 'warning' }[s] || '')

// ===== 列表加载 =====
const loadList = async () => {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: pageSize.value,
      username: filters.username || undefined,
      source: filters.source || undefined,
      result: filters.result || undefined,
      status: filters.status || undefined,
      clientIp: filters.clientIp || undefined,
      browserName: filters.browserName || undefined,
      osName: filters.osName || undefined,
      deviceType: filters.deviceType || undefined,
      loginTimeStart: timeRange.value?.[0] || undefined,
      loginTimeEnd: timeRange.value?.[1] || undefined,
    }
    const res = await loginLogApi.query(params)
    rows.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } catch (e) {
    console.error('加载登录日志失败', e)
  } finally {
    loading.value = false
  }
}

const onQuery = () => {
  page.value = 1
  loadList()
}
const onReset = () => {
  Object.keys(filters).forEach(k => filters[k] = '')
  timeRange.value = []
  page.value = 1
  loadList()
}

// ===== 看板加载 =====
const loadDashboard = async () => {
  try {
    const res = await loginLogApi.dashboard()
    Object.assign(dashboard, res.data)
    await nextTick()
    renderTrendChart()
  } catch (e) {
    console.error('加载登录看板失败', e)
  }
}

// ===== 趋势图 =====
const renderTrendChart = () => {
  if (!trendChartEl.value) return
  if (!trendChart) trendChart = echarts.init(trendChartEl.value)
  const points = trendMode.value === 'day' ? dashboard.trend30d : dashboard.trend24h
  const option = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 40 },
    xAxis: {
      type: 'category',
      data: points.map(p => p.label),
      axisLabel: {
        rotate: trendMode.value === 'day' ? 30 : 0,
        fontSize: 11,
      },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      name: '登录次数',
      type: 'line',
      data: points.map(p => p.count),
      smooth: true,
      areaStyle: { opacity: 0.3 },
      itemStyle: { color: '#409EFF' },
    }],
  }
  trendChart.setOption(option, true)
}

const handleResize = () => trendChart?.resize()
watch(trendMode, renderTrendChart)

onMounted(() => {
  loadDashboard()
  loadList()
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
})
</script>

<style scoped>
.login-log-tab {
  padding: 0;
}
.chart-card,
.filter-card {
  margin-bottom: 12px;
}
.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.trend-chart {
  width: 100%;
  height: 300px;
}
.version {
  color: #909399;
  font-size: 12px;
}
</style>
