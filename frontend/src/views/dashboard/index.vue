<template>
  <div class="dashboard">
    <!-- 数据流示意图 -->
    <div class="dataflow-section">
      <div class="section-title">平台数据流总览</div>

      <!-- 流程容器 - 4列等高布局 -->
      <div class="flow-container">
        <!-- 第1列：数据源 -->
        <div class="flow-col flow-col-1">
          <div class="step-label">数据源</div>
          <div class="flow-panel source-panel">
            <div class="flow-card source-card" v-for="source in dataSources" :key="source.name" @click="handleSourceClick(source.name)">
              <el-icon :size="18" class="source-icon">
                <component :is="source.icon" />
              </el-icon>
              <span class="card-name">{{ source.name }}</span>
            </div>
          </div>
        </div>

        <!-- 箭头1 -->
        <div class="flow-arrow-cell">
          <div class="flow-arrow-pulse">
            <div class="arrow-head"></div>
          </div>
        </div>

        <!-- 第2列：数据入库 -->
        <div class="flow-col flow-col-2">
          <div class="step-label">数据入库</div>
          <div class="flow-panel import-panel">
            <div class="import-stat-card" @click="$router.push('/data')">
              <div class="import-stat-icon">
                <el-icon :size="20"><DataBoard /></el-icon>
              </div>
              <div class="import-stat-info">
                <div class="import-stat-label">今日数据量</div>
                <div class="import-stat-row">
                  <span class="import-stat-value">{{ importCount > 0 ? importCount.toLocaleString() : '--' }}</span>
                  <span class="import-stat-unit">条</span>
                </div>
              </div>
            </div>
            <div class="import-stat-card" @click="$router.push('/data')">
              <div class="import-stat-icon">
                <el-icon :size="20"><Document /></el-icon>
              </div>
              <div class="import-stat-info">
                <div class="import-stat-label">采集文件</div>
                <div class="import-stat-row">
                  <span class="import-stat-value">{{ statistics.fileCount ?? '--' }}</span>
                  <span class="import-stat-unit">个</span>
                </div>
              </div>
            </div>
            <div class="import-stat-card" @click="$router.push('/data')">
              <div class="import-stat-icon">
                <el-icon :size="20"><Grid /></el-icon>
              </div>
              <div class="import-stat-info">
                <div class="import-stat-label">数据表</div>
                <div class="import-stat-row">
                  <span class="import-stat-value">{{ statistics.tableCount ?? '--' }}</span>
                  <span class="import-stat-unit">张</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 箭头2 -->
        <div class="flow-arrow-cell">
          <div class="flow-arrow-pulse">
            <div class="arrow-head"></div>
          </div>
        </div>

        <!-- 第3列：指标计算 + 健康度分析 -->
        <div class="flow-col flow-col-3">
          <div class="step-label">指标计算</div>
          <div class="flow-panel process-panel">
            <div class="flow-card process-card" @click="$router.push('/report')">
              <el-icon :size="18"><DataAnalysis /></el-icon>
              <span class="card-name">指标计算</span>
              <span class="card-stat">{{ metricCount }} 个</span>
            </div>
            <div class="flow-connector-v">
              <div class="flow-arrow-pulse-v">
                <div class="arrow-head-v"></div>
              </div>
            </div>
            <div class="flow-card process-card health-card" @click="$router.push('/observation')">
              <el-icon :size="18"><DataLine /></el-icon>
              <span class="card-name">健康度分析</span>
              <span class="card-stat health-score">{{ healthChartCount }} 个</span>
            </div>
          </div>
        </div>

        <!-- 箭头3 -->
        <div class="flow-arrow-cell">
          <div class="flow-arrow-pulse">
            <div class="arrow-head"></div>
          </div>
        </div>

        <!-- 第4列：处理结果 -->
        <div class="flow-col flow-col-4">
          <div class="step-label">处理结果</div>
          <div class="flow-panel result-panel">
            <!-- 告警统计 -->
            <div class="result-stat-card alarm-stat-card" @click="$router.push({ path: '/alarm', query: { processStatus: 0 } })">
              <div class="result-stat-left">
                <div class="result-stat-header">
                  <el-icon :size="16"><Bell /></el-icon>
                  <span class="result-stat-title">告警</span>
                </div>
                <div class="result-stat-body">
                  <div class="result-stat-sub">本月生成</div>
                  <div class="result-stat-main danger">{{ statistics.totalAlarmCount || 0 }}</div>
                </div>
              </div>
              <div class="result-stat-right">
                <div class="result-footer-item">
                  <span class="footer-label">已处理</span>
                  <span class="footer-value">{{ alarmProcessedCount }}</span>
                </div>
                <div class="result-footer-item">
                  <span class="footer-label">处理率</span>
                  <span class="footer-value" :class="getRateClass(alarmProcessRate)">{{ alarmProcessRate }}%</span>
                </div>
              </div>
            </div>
            <!-- 问题统计 -->
            <div class="result-stat-card issue-stat-card" @click="$router.push({ path: '/issue', query: { resolveStatus: '未解决' } })">
              <div class="result-stat-left">
                <div class="result-stat-header">
                  <el-icon :size="16"><Warning /></el-icon>
                  <span class="result-stat-title">问题</span>
                </div>
                <div class="result-stat-body">
                  <div class="result-stat-sub">本月登记</div>
                  <div class="result-stat-main warning">{{ statistics.issueCount || 0 }}</div>
                </div>
              </div>
              <div class="result-stat-right">
                <div class="result-footer-item">
                  <span class="footer-label">已解决</span>
                  <span class="footer-value">{{ issueResolvedCount }}</span>
                </div>
                <div class="result-footer-item">
                  <span class="footer-label">解决率</span>
                  <span class="footer-value" :class="getRateClass(issueResolveRate)">{{ issueResolveRate }}%</span>
                </div>
              </div>
            </div>
            <!-- 工单统计 -->
            <div class="result-stat-card gongdan-stat-card" @click="$router.push({ path: '/gongdan', query: { closedStatus: 0 } })">
              <div class="result-stat-left">
                <div class="result-stat-header">
                  <el-icon :size="16"><Document /></el-icon>
                  <span class="result-stat-title">工单</span>
                </div>
                <div class="result-stat-body">
                  <div class="result-stat-sub">本月创建</div>
                  <div class="result-stat-main primary">{{ statistics.gongdanCount || 0 }}</div>
                </div>
              </div>
              <div class="result-stat-right">
                <div class="result-footer-item">
                  <span class="footer-label">已完成</span>
                  <span class="footer-value">{{ gongdanCompletedCount }}</span>
                </div>
                <div class="result-footer-item">
                  <span class="footer-label">完成率</span>
                  <span class="footer-value" :class="getRateClass(gongdanCompleteRate)">{{ gongdanCompleteRate }}%</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>关注问题</span>
              <el-button type="primary" link @click="$router.push('/issue')">查看更多</el-button>
            </div>
          </template>
          <el-table v-sticky-header v-mirror-scroll :data="followedIssues" style="width: 100%">
            <el-table-column prop="registerTime" label="登记时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.registerTime) }}</template>
            </el-table-column>
            <el-table-column label="已登记时长" width="120">
              <template #default="{ row }">{{ calcElapsedTime(row.registerTime) }}</template>
            </el-table-column>
            <el-table-column prop="issueDescription" label="问题描述" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ensureHealthThresholds, scoreTone } from '@/utils/healthThresholds'
ensureHealthThresholds()
import { useRouter } from 'vue-router'
import { alarmApi, gongdanApi, issueApi, dataApi, metricApi, originalDataImportApi } from '../../api'
import { formatDateTime } from '../../utils'
import { UploadFilled, DataAnalysis, DataLine, Warning, Bell, QuestionFilled, Document, User, Box, Monitor, Operation, DataBoard, Grid } from '@element-plus/icons-vue'

const statistics = ref({
  // 防御性初始化：让所有 key 都有默认值 0，避免初次渲染或 API 失败时出现 '--'
  totalAlarmCount: 0,
  unprocessedCount: 0,
  gongdanCount: 0,
  issueCount: 0,
  fileCount: 0,
  tableCount: 0
})
const followedIssues = ref([])
const router = useRouter()

// 数据源配置
const dataSources = [
  { name: 'CRM', icon: 'User' },
  { name: 'BOSS', icon: 'Box' },
  { name: '稽核系统', icon: 'Monitor' },
  { name: '端到端监控', icon: 'DataLine' },
  { name: 'BOMC', icon: 'Operation' }
]

// 数据源 -> Tab 映射
const sourceToTabMapping = {
  'CRM': { categoryName: '对外部客户', subCategoryName: '流量提醒' },
  'BOSS': { categoryName: '对外部客户', subCategoryName: '缴费开机' },
  '稽核系统': { categoryName: '对外部客户', subCategoryName: '系统稽核' },
  '端到端监控': { categoryName: '对内部人员' },
  'BOMC': { categoryName: '计划执行' }
}

// 数据源卡片点击处理
const handleSourceClick = (sourceName) => {
  const mapping = sourceToTabMapping[sourceName]
  if (mapping) {
    router.push({ path: '/data', query: { source: sourceName } })
  } else {
    router.push('/data')
  }
}

// 流程统计数据
const importCount = ref(0)  // 今日入库条数
const metricCount = ref(0)   // 指标数量
const categoryCount = ref(0)  // 指标大类数量
const subCategoryCount = ref(0)  // 指标小类数量
const issueUnresolvedCount = ref(0)
const gongdanOpenCount = ref(0)
const healthScore = ref(0)

// 健康度分析图表数量 = 指标数量 + 1(CRM系统) + 指标大类数量 + 指标小类数量
const healthChartCount = computed(() => metricCount.value + 1 + categoryCount.value + subCategoryCount.value)

const isMobile = computed(() => window.innerWidth < 768)

// 告警统计
const alarmProcessedCount = computed(() => {
  const total = statistics.value.totalAlarmCount || 0
  const unprocessed = statistics.value.unprocessedCount || 0
  return Math.max(0, total - unprocessed)  // 防止 unprocessed > total 导致负数
})
const alarmProcessRate = computed(() => {
  const total = statistics.value.totalAlarmCount || 0
  if (total === 0) return 0
  // processed 不可能超过 total，rate 也用 max(0, ...) + min(100, ...) 双重保护
  return Math.max(0, Math.min(100, Math.round((alarmProcessedCount.value / total) * 100)))
})

// 问题统计
const issueResolvedCount = computed(() => {
  const total = statistics.value.issueCount || 0
  const unresolved = issueUnresolvedCount.value || 0
  return Math.max(0, total - unresolved)
})
const issueResolveRate = computed(() => {
  const total = statistics.value.issueCount || 0
  if (total === 0) return 0
  return Math.max(0, Math.min(100, Math.round((issueResolvedCount.value / total) * 100)))
})

// 工单统计
const gongdanCompletedCount = computed(() => {
  const total = statistics.value.gongdanCount || 0
  const open = gongdanOpenCount.value || 0
  return Math.max(0, total - open)
})
const gongdanCompleteRate = computed(() => {
  const total = statistics.value.gongdanCount || 0
  if (total === 0) return 0
  return Math.max(0, Math.min(100, Math.round((gongdanCompletedCount.value / total) * 100)))
})

const getRateClass = (rate) => {
  if (rate >= 80) return 'rate-excellent'
  if (rate >= 60) return 'rate-good'
  return 'rate-poor'
}

const getScoreClass = (score) => {
  if (score === null || score === undefined || score === 0) return ''
  // 分档阈值统一来自 system_config（60/80），不再写死 70/90
  return scoreTone(score)
}

const loadStatistics = async () => {
  // 防御性重置：所有 key 默认 0，避免任何 race / 异常时显示 '--'
  Object.assign(statistics.value, {
    totalAlarmCount: 0,
    unprocessedCount: 0,
    gongdanCount: 0,
    issueCount: 0
  })
  issueUnresolvedCount.value = 0
  gongdanOpenCount.value = 0
  healthScore.value = 0

  try {
    const [alarmRes, gongdanRes, issueRes] = await Promise.all([
      alarmApi.statistics(),
      gongdanApi.statistics(),
      issueApi.statistics()
    ])
    // 用 Object.assign 合并而不是整体替换 statistics.value
    // 避免 loadFlowStatistics 先 set 的 fileCount / tableCount 被覆盖丢失
    Object.assign(statistics.value, {
      totalAlarmCount: alarmRes.data.totalAlarmCount ?? 0,
      unprocessedCount: alarmRes.data.unprocessedCount ?? 0,
      gongdanCount: gongdanRes.data.totalGongdanCount ?? 0,
      issueCount: issueRes.data.totalIssueCount ?? 0
    })

    // 未解决问题数
    issueUnresolvedCount.value = issueRes.data.unresolvedIssueCount || issueRes.data.totalIssueCount || 0
    // 未关闭工单数
    gongdanOpenCount.value = gongdanRes.data.openGongdanCount || 0
    // 健康度
    healthScore.value = alarmRes.data.healthScore || 0

    // 加载关注问题（未关闭 + 已关注）
    const issueRes2 = await issueApi.list({ page: 0, size: 10, resolveStatus: '未解决', tagStatus: 1 })
    followedIssues.value = issueRes2.data.content || []
  } catch (error) {
    console.error('加载统计数据失败:', error)
    // catch 里已经防御性 reset 为 0 了
  }
}

// 加载入库统计和指标统计
const loadFlowStatistics = async () => {
  // 防御性重置：万一 API 失败，至少显示 0 而非 '--'
  statistics.value.fileCount = 0
  statistics.value.tableCount = 0
  importCount.value = 0
  metricCount.value = 0
  categoryCount.value = 0
  subCategoryCount.value = 0

  try {
    // 加载原始数据导入统计
    // 用 totalFileCount（全时段累计，不被 0 点重置），不用 todayFileCount
    const importStatsRes = await originalDataImportApi.getStatistics()
    statistics.value.fileCount = importStatsRes.data?.totalFileCount ?? importStatsRes.data?.todayFileCount ?? 0
    statistics.value.tableCount = importStatsRes.data?.tableCount ?? 0
    importCount.value = importStatsRes.data?.todayRecordCount ?? 0

    // 加载指标统计
    const metricRes = await metricApi.statistics()
    metricCount.value = metricRes.data?.itemCount || 0
    categoryCount.value = metricRes.data?.categoryCount || 0
    subCategoryCount.value = metricRes.data?.subCategoryCount || 0
  } catch (error) {
    console.error('加载流程统计数据失败:', error)
    // catch 里已经防御性 reset 为 0 了
  }
}

// 计算已登记时长（统一按天展示，不换算为月）
const calcElapsedTime = (registerTime) => {
  if (!registerTime) return '-'
  const now = new Date()
  const reg = new Date(registerTime)
  const diffMs = now - reg
  const diffHours = Math.floor(diffMs / 3600000)
  if (diffHours < 1) return Math.floor(diffMs / 60000) + '分钟'
  if (diffHours < 24) return diffHours + '小时'
  return Math.floor(diffHours / 24) + '天'
}

// 并行加载：loadStatistics 和 loadFlowStatistics 不再互相覆盖
// (loadStatistics 用 Object.assign 合并而非整体替换 statistics.value)
onMounted(() => {
  loadStatistics()
  loadFlowStatistics()
})
</script>

<style scoped>
.dashboard {
  padding: 0;
}

/* 数据流示意图 */
.dataflow-section {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
}

.section-title {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
  margin-bottom: 16px;
}

/* 流程容器 - 横向等高布局 */
.flow-container {
  display: flex;
  align-items: stretch;
  justify-content: center;
  padding: 0 20px;
}

.flow-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.flow-col-1 {
  width: 140px;
  align-items: flex-start;
}

.flow-col-2 {
  width: 150px;
}

.flow-col-3 {
  width: 150px;
}

.flow-col-4 {
  width: 280px;
  align-items: stretch;
}

/* 响应式布局 - 小屏幕 */
@media (max-width: 900px) {
  .flow-container {
    flex-wrap: wrap;
    gap: 12px;
    padding: 0 10px;
  }

  .flow-col {
    flex: 1 1 calc(50% - 10px);
    min-width: 120px;
  }

  .flow-col-1,
  .flow-col-2,
  .flow-col-3,
  .flow-col-4 {
    width: auto;
    align-items: stretch;
  }

  .flow-arrow-cell {
    display: none;
  }

  .flow-panel {
    min-height: auto;
  }
}

@media (max-width: 600px) {
  .flow-container {
    flex-direction: column;
    align-items: center;
  }

  .flow-col {
    flex: none;
    width: 100%;
    max-width: 280px;
  }

  .flow-col-4 {
    align-items: flex-start;
  }
}

.step-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  height: 20px;
}

/* 流程面板 - 统一的卡片容器 */
.flow-panel {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  overflow: hidden;
  justify-content: space-around;
}

.source-panel {
  background: #fff;
  gap: 6px;
}

.import-panel {
  background: #f5f9ff;
  border-color: #b3d8fd;
}

/* 入库统计卡片 */
.import-stat-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #b3d8fd;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.import-stat-card:hover {
  background: #e8f4ff;
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(64, 158, 255, 0.15);
}

.import-stat-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #e8f4ff;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #409eff;
}

.import-stat-info {
  flex: 1;
}

.import-stat-row {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.import-stat-value {
  font-size: 16px;
  font-weight: bold;
  color: #f56c6c;
  line-height: 1.2;
}

.import-stat-unit {
  font-size: 11px;
  color: #f56c6c;
  font-weight: 500;
}

.import-stat-label {
  font-size: 10px;
  color: #909399;
}

.process-panel {
  background: #ecf5ff;
  border-color: #b3d8fd;
}

.result-panel {
  background: #fff;
  gap: 8px;
  flex-direction: column;
  align-items: stretch;
}

/* 箭头单元格 */
.flow-arrow-cell {
  display: flex;
  align-items: center;
  padding: 0 8px;
}

.flow-arrow-h {
  color: #c0c4cc;
  font-size: 20px;
}

/* 数据流动画箭头 */
.flow-arrow-pulse {
  position: relative;
  width: 50px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.flow-arrow-pulse::before {
  content: '';
  position: absolute;
  left: 0;
  width: 100%;
  height: 2px;
  background: #e4e7ed;
  border-radius: 1px;
}

.flow-arrow-pulse::after {
  content: '';
  position: absolute;
  width: 8px;
  height: 8px;
  background: #409eff;
  border-radius: 50%;
  left: 5px;
  box-shadow: 0 0 8px #409eff;
  animation: flowDot 2s ease-in-out infinite;
}

.flow-arrow-pulse .arrow-head {
  position: absolute;
  right: 0;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-left: 10px solid #409eff;
}

@keyframes flowDot {
  0% {
    left: 5px;
    opacity: 0;
    box-shadow: 0 0 4px #409eff;
  }
  20% {
    opacity: 1;
    box-shadow: 0 0 12px #409eff;
  }
  80% {
    opacity: 1;
    box-shadow: 0 0 12px #409eff;
  }
  100% {
    left: calc(100% - 13px);
    opacity: 0;
    box-shadow: 0 0 4px #409eff;
  }
}

/* 箭头脉冲动画 - 交错效果 */
.flow-arrow-cell:nth-child(2) .flow-arrow-pulse::after {
  animation-delay: 0s;
}

.flow-arrow-cell:nth-child(4) .flow-arrow-pulse::after {
  animation-delay: 0.6s;
}

.flow-arrow-cell:nth-child(6) .flow-arrow-pulse::after {
  animation-delay: 1.2s;
}

.source-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.flow-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 10px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid #ebeef5;
  background: #fafafa;
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
}

.flow-card:hover {
  border-color: #409eff;
  background: #ecf5ff;
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.source-card {
  flex-direction: row;
  gap: 8px;
  padding: 8px 12px;
  background: #fff;
  border: 1px solid #e4e7ed;
}

.source-card:hover {
  border-color: #409eff;
  background: #ecf5ff;
}

.source-card .source-icon {
  color: #409eff;
}

.source-card .card-name {
  font-size: 12px;
  color: #606266;
  font-weight: 500;
}

/* 垂直连接线 */
.flow-connector-v {
  display: flex;
  justify-content: center;
  padding: 8px 0;
}

/* 垂直数据流动画箭头 */
.flow-arrow-pulse-v {
  position: relative;
  width: 30px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.flow-arrow-pulse-v::before {
  content: '';
  position: absolute;
  top: 0;
  width: 2px;
  height: 100%;
  background: #e4e7ed;
  border-radius: 1px;
}

.flow-arrow-pulse-v::after {
  content: '';
  position: absolute;
  width: 8px;
  height: 8px;
  background: #409eff;
  border-radius: 50%;
  top: 5px;
  box-shadow: 0 0 8px #409eff;
  animation: flowDotV 2s ease-in-out infinite;
}

.flow-arrow-pulse-v .arrow-head-v {
  position: absolute;
  bottom: 0;
  width: 0;
  height: 0;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 10px solid #409eff;
}

@keyframes flowDotV {
  0% {
    top: 5px;
    opacity: 0;
    box-shadow: 0 0 4px #409eff;
  }
  20% {
    opacity: 1;
    box-shadow: 0 0 12px #409eff;
  }
  80% {
    opacity: 1;
    box-shadow: 0 0 12px #409eff;
  }
  100% {
    top: calc(100% - 13px);
    opacity: 0;
    box-shadow: 0 0 4px #409eff;
  }
}

/* 计算卡片 */
.process-card {
  background: #fff;
  border-color: #b3d8fd;
}

.process-card:hover {
  background: #d9ecff;
  border-color: #409eff;
}

.process-card .card-name {
  font-size: 12px;
  color: #409eff;
  font-weight: 500;
  margin-top: 4px;
}

.process-card .card-stat {
  font-size: 16px;
  color: #303133;
  font-weight: bold;
  margin-top: 2px;
}

/* 处理结果统计卡片 */
.result-stat-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  cursor: pointer;
  transition: all 0.2s;
}

.result-stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.result-stat-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex-shrink: 0;
}

.result-stat-header {
  display: flex;
  align-items: center;
  gap: 4px;
}

.result-stat-title {
  font-size: 11px;
  color: #606266;
  font-weight: 500;
}

.result-stat-body {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.result-stat-main {
  font-size: 20px;
  font-weight: bold;
  line-height: 1.2;
}

.result-stat-main.danger { color: #f56c6c; }
.result-stat-main.warning { color: #e6a23c; }
.result-stat-main.primary { color: #409eff; }

.result-stat-sub {
  font-size: 10px;
  color: #909399;
  margin-right: 8px;
}

.result-stat-right {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: right;
}

.result-footer-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 10px;
  min-width: 90px;
}

.result-footer-item .footer-label {
  color: #909399;
  flex-shrink: 0;
  width: 42px;
}

.result-footer-item .footer-value {
  color: #606266;
  font-weight: 500;
  text-align: center;
  width: 36px;
}

/* 健康度卡片 */
.health-card {
  background: #fff;
  border-color: #a8d86e;
}

.health-card:hover {
  background: #e1f3d8;
  border-color: #67c23a;
}

.health-card .card-name {
  color: #67c23a;
}

/* 健康度分数颜色 */
.card-stat.health-score.score-excellent { color: #67c23a; }
.card-stat.health-score.score-good { color: #e6a23c; }
.card-stat.health-score.score-poor { color: #f56c6c; }

/* 保留原有样式 */
.stat-card {
  cursor: pointer;
  transition: all 0.3s;
  margin-bottom: 10px;
}

.stat-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.stat-icon.alarm {
  background: linear-gradient(135deg, #f56c6c, #e74c3c);
}

.stat-icon.alarm-pending {
  background: linear-gradient(135deg, #e6a23c, #f39c12);
}

.stat-icon.gongdan {
  background: linear-gradient(135deg, #409eff, #3498db);
}

.stat-icon.issue {
  background: linear-gradient(135deg, #909399, #7f8c8d);
}

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 22px;
  font-weight: bold;
  color: #303133;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

/* 本月统计卡片区 */
.monthly-stats-section {
  display: flex;
  gap: 16px;
  margin-top: 16px;
}

.monthly-stat-card {
  flex: 1;
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  border: 1px solid #ebeef5;
  cursor: pointer;
  transition: all 0.2s;
}

.monthly-stat-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.alarm-stat-card {
  border-left: 3px solid #f56c6c;
}

.issue-stat-card {
  border-left: 3px solid #e6a23c;
}

.gongdan-stat-card {
  border-left: 3px solid #409eff;
}

.stat-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.alarm-stat-card .stat-card-header {
  color: #f56c6c;
}

.issue-stat-card .stat-card-header {
  color: #e6a23c;
}

.gongdan-stat-card .stat-card-header {
  color: #409eff;
}

.stat-card-title {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
}

.stat-card-body {
  text-align: center;
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 12px;
}

.stat-main-value {
  font-size: 32px;
  font-weight: bold;
}

.stat-main-value.danger {
  color: #f56c6c;
}

.stat-main-value.warning {
  color: #e6a23c;
}

.stat-main-value.primary {
  color: #409eff;
}

.stat-sub-text {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.stat-card-footer {
  display: flex;
  justify-content: space-around;
}

.stat-footer-item {
  text-align: center;
}

.footer-label {
  display: block;
  font-size: 11px;
  color: #909399;
  margin-bottom: 2px;
}

.footer-value {
  font-size: 16px;
  font-weight: bold;
  color: #303133;
}

.footer-value.rate-excellent {
  color: #67c23a;
}

.footer-value.rate-good {
  color: #e6a23c;
}

.footer-value.rate-poor {
  color: #f56c6c;
}
</style>
