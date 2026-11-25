<template>
  <div class="weekly-workspace">
    <div class="workspace-controls" v-balanced-query="{ fill:mode === 'actual' }">
      <el-select v-model="mode" class="report-mode" aria-label="周报内容来源">
        <el-option value="actual" label="本期周报" />
        <el-option value="reference" label="历史文档参考" />
      </el-select>
      <template v-if="mode === 'actual'">
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至" :clearable="false"
          :disabled="loading" :disabled-date="disableFuture" aria-label="报告周期" />
        <el-select v-model="systemId" placeholder="全部系统" clearable :disabled="loading" class="system-filter" aria-label="业务系统">
          <el-option v-for="system in systems" :key="system.id" :label="system.systemName" :value="system.id" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="loadPreview">更新周报</el-button>
      </template>
    </div>

    <template v-if="mode === 'actual'">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert v-if="filtersChanged && report" title="筛选已变化，请点击“更新周报”；下方仍为上次生成的报告。" type="info" :closable="false" />
      <div v-loading="loading" class="preview-content">
        <details v-if="report?.mappingIssues?.total" class="mapping-issues">
          <summary>{{ report.mappingIssues.total }}条来源记录存在编码或数据时间问题，未纳入有效指标统计（展开查看，最多列出50条）</summary>
          <ul><li v-for="(issue, index) in report.mappingIssues.rows" :key="index">{{ issue.sourceFile }} · 第{{ issue.sourceRow }}行 · {{ issue.metricCode || '无编码' }}：{{ issue.reason }}</li></ul>
        </details>
        <WeeklyReportReader v-if="report" :key="reportKey" :report="report" :report-sections="reportSections" @view-observations="openObservations" />
        <el-empty v-else-if="!loading && !error" description="选择报告周期，生成实际数据周报草稿" />
        <div v-else-if="loading" class="loading-placeholder">正在读取本期数据…</div>
      </div>
    </template>
    <template v-else>
      <p class="preview-hint">以下为提供的10月16日—23日 Word 原文参考，与实际数据草稿分开展示。</p>
      <WeeklyReportReader />
    </template>

    <el-dialog v-model="detailsVisible" title="本期指标来源明细" width="min(1200px, 94vw)" append-to-body @closed="closeDetails">
      <template v-if="selectedMetric">
        <div class="detail-heading"><strong>{{ selectedMetric.metricName }}</strong><span>{{ selectedMetric.metricCode }} · {{ selectedMetric.systemName }} · {{ selectedMetric.categoryName }}</span></div>
        <el-descriptions :column="2" border size="small" class="metric-description">
          <el-descriptions-item label="指标编码">{{ selectedMetric.metricCode || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="数据时间">{{ formatTime(selectedMetric.latestDataTime) }}</el-descriptions-item>
          <el-descriptions-item label="告警规则">{{ ruleLabel(selectedMetric) }}</el-descriptions-item>
          <el-descriptions-item label="规则状态">{{ selectedMetric.ruleStatus }}</el-descriptions-item>
          <el-descriptions-item label="指标定义" :span="2">{{ selectedMetric.definition || '未配置' }}</el-descriptions-item>
        </el-descriptions>
        <p class="detail-note">{{ selectedMetric.sourceNote }}</p>
        <p class="detail-note">查询周期：{{ report?.startDate }} 至 {{ report?.endDate }}（北京时间，包含起止日）</p>
        <details v-if="selectedMetric.ruleExtra" class="rule-details"><summary>查看当前指标补充规则（供核对）</summary><pre>{{ formatRaw(selectedMetric.ruleExtra) }}</pre></details>
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <el-table :data="detailRows" border v-loading="detailLoading" max-height="450" empty-text="本期没有可关联的留存记录">
          <el-table-column label="数据时间" min-width="170"><template #default="{ row }">{{ formatTime(row.dataTime) }}</template></el-table-column>
          <el-table-column label="原始指标值" min-width="115"><template #default="{ row }">{{ row.metricValue ?? '缺失' }}</template></el-table-column>
          <el-table-column label="记录状态" min-width="125"><template #default="{ row }">{{ row.conflict ? '名称、单位或分类冲突' : row.metricValue == null ? '值缺失' : row.validValue ? '有效记录' : '数值格式无效' }}</template></el-table-column>
          <el-table-column label="来源文件 / 数据源" min-width="210"><template #default="{ row }">{{ row.sourceFile || row.sourceCode }}<small class="detail-subtext">{{ row.batchId ? `批次：${row.batchId}` : '当前留存快照，非完整历史' }}</small><small v-if="row.sourceRow != null" class="detail-subtext">来源行：{{ row.sourceRow }}</small></template></el-table-column>
          <el-table-column label="导入时间" min-width="170"><template #default="{ row }">{{ formatTime(row.importedAt) }}</template></el-table-column>
          <el-table-column label="来源内容" width="100"><template #default="{ row }"><el-button link type="primary" @click="rawRecord = formatRaw(row.rawRecord)">查看原始记录</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="detailPage" :page-size="20" :total="detailTotal" layout="total, prev, pager, next" class="detail-pagination" @current-change="loadDetails" />
        <div v-if="rawRecord" class="raw-record"><div><strong>原始记录</strong><el-button link @click="rawRecord = ''">收起</el-button></div><pre>{{ rawRecord }}</pre></div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { systemApi } from '@/api'
import { weeklyReportApi } from '@/api/weeklyReport'
import WeeklyReportReader from './WeeklyReportReader.vue'

const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
const lastSevenStart = new Date(Date.parse(`${today}T00:00:00Z`) - 6 * 86400000).toISOString().slice(0, 10)
const mode = ref('actual')
const dateRange = ref([lastSevenStart, today])
const systemId = ref(null)
const systems = ref([])
const loading = ref(false)
const error = ref('')
const report = ref(null)
const reportKey = ref(0)
const generatedFilter = ref('')
const filterKey = computed(() => JSON.stringify([dateRange.value, systemId.value || null]))
const filtersChanged = computed(() => generatedFilter.value !== filterKey.value)
let generation = 0
let detailGeneration = 0
const detailsVisible = ref(false)
const selectedMetric = ref(null)
const detailRows = ref([])
const detailTotal = ref(0)
const detailPage = ref(1)
const detailLoading = ref(false)
const detailError = ref('')
const rawRecord = ref('')

const disableFuture = (date) => {
  const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  return key > today
}
const formatTime = (value) => value ? String(value).replace('T', ' ') : '—'
const ruleLabel = (metric) => metric.alarmThreshold == null ? '未配置'
  : `${metric.alarmWindowMinutes == null ? '窗口未配置' : `${metric.alarmWindowMinutes}分钟窗口`} / ${metric.alarmCondition || '条件未配置'} ${metric.alarmThreshold} ${metric.unit || ''}`
// Keep raw numeric tokens unchanged, including values beyond JavaScript's safe precision.
const formatRaw = (value) => typeof value === 'string' ? value : value == null ? '无原始内容' : JSON.stringify(value)
const reportSections = computed(() => {
  if (!report.value) return []
  const { summary, metrics } = report.value
  const result = [{ id: 'overview', title: '系统运营指标情况', blocks: [
    { type: 'paragraph', text: `本期纳入${summary.systemCount}个系统、${summary.metricCount}项指标，其中${summary.coveredMetricCount}项有有效数据，${summary.withoutValidDataCount}项暂无有效数据。${summary.conflictMetricCount ? `${summary.conflictMetricCount}项存在来源或编码问题，待核对。` : ''}` },
    { type: 'paragraph', text: '以下展示期内最近一次数据，不代表周汇总值。红色标记符合已配置告警条件的指标；暂无阈值的指标不标记。点击指标名称可查看明细。' }
  ] }]
  const categories = new Map()
  for (const metric of metrics) {
    const key = metric.categoryId
    if (!categories.has(key)) categories.set(key, { id: `category-${key}`, title: metric.categoryName, systemName: metric.systemName, metrics: [] })
    categories.get(key).metrics.push(metric)
  }
  for (const category of categories.values()) {
    const blocks = [{ type: 'paragraph', text: `${category.systemName}本期纳入${category.metrics.length}项指标，具体如下：` }]
    const groups = new Map()
    for (const metric of category.metrics) {
      const name = metric.subCategoryName || ''
      if (!groups.has(name)) groups.set(name, [])
      groups.get(name).push(metric)
    }
    for (const [name, group] of groups) {
      if (name) blocks.push({ type: 'subheading', text: name })
      blocks.push({ type: 'metrics', metrics: group })
    }
    result.push({ id: category.id, title: category.title, blocks })
  }
  return result
})

const loadPreview = async () => {
  if (!dateRange.value || dateRange.value.length !== 2) { error.value = '请选择报告起止日期'; return }
  const days = (Date.parse(dateRange.value[1]) - Date.parse(dateRange.value[0])) / 86400000 + 1
  if (days < 1 || days > 31) { error.value = '请选择1至31天的报告周期'; return }
  const requestNumber = ++generation
  const requestedFilter = filterKey.value
  loading.value = true
  error.value = ''
  detailsVisible.value = false
  selectedMetric.value = null
  detailGeneration++
  report.value = null
  try {
    const response = await weeklyReportApi.preview({ startDate: dateRange.value[0], endDate: dateRange.value[1], systemId: systemId.value || undefined })
    if (requestNumber !== generation) return
    report.value = response.data
    generatedFilter.value = requestedFilter
    reportKey.value++
  } catch (err) {
    if (requestNumber === generation) error.value = err.message || '生成失败，请重试'
  } finally {
    if (requestNumber === generation) loading.value = false
  }
}
const openObservations = (metric) => {
  selectedMetric.value = metric
  detailPage.value = 1
  detailsVisible.value = true
  loadDetails()
}
const loadDetails = async () => {
  const requestNumber = ++detailGeneration
  detailLoading.value = true
  detailError.value = ''
  rawRecord.value = ''
  detailRows.value = []
  detailTotal.value = 0
  try {
    const response = await weeklyReportApi.observations({ metricId: selectedMetric.value.metricId, startDate: report.value.startDate,
      endDate: report.value.endDate, page: detailPage.value - 1, size: 20 })
    if (requestNumber !== detailGeneration) return
    detailRows.value = response.data.rows
    detailTotal.value = response.data.total
  } catch (err) {
    if (requestNumber === detailGeneration) detailError.value = err.message || '明细读取失败'
  } finally {
    if (requestNumber === detailGeneration) detailLoading.value = false
  }
}
const closeDetails = () => { detailGeneration++; rawRecord.value = ''; selectedMetric.value = null }
watch(mode, () => { detailsVisible.value = false })
onMounted(async () => {
  loadPreview()
  try { const response = await systemApi.listAll(); systems.value = response.data || [] } catch { systems.value = [] }
})
onBeforeUnmount(() => { generation++; detailGeneration++ })
</script>

<style scoped>
.workspace-controls { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; padding: 16px 0 8px; }
.workspace-controls :deep(.el-date-editor) { width: 290px; flex-grow: 0; }
.system-filter { width: 170px; }
.report-mode { width: 150px; }
.metric-description { margin-top: 16px; }
.preview-hint { color: #7c8797; font-size: 12px; line-height: 1.9; margin: 6px 0 12px; }
.preview-content { min-height: 200px; }
.mapping-issues { background: #fff8ee; color: #96682a; padding: 12px 16px; font-size: 12px; line-height: 1.9; border-radius: 5px; }
.mapping-issues summary { cursor: pointer; }
.mapping-issues li { overflow-wrap: anywhere; }
.loading-placeholder { padding: 70px 0; text-align: center; color: #929baa; }
.detail-heading { display: flex; flex-wrap: wrap; gap: 12px; align-items: baseline; }
.detail-heading span, .detail-note { color: #7b8797; font-size: 12px; line-height: 1.9; }
.detail-subtext { display: block; font-size: 11px; color: #8a94a2; overflow-wrap: anywhere; }
.detail-pagination { margin-top: 16px; justify-content: flex-end; }
.raw-record { background: #f5f7fa; border-radius: 6px; padding: 16px; margin-top: 16px; }
.raw-record > div { display: flex; justify-content: space-between; }
.raw-record pre { max-height: 260px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.8; }
.rule-details { color: #7b8797; font-size: 12px; margin: 12px 0; }
.rule-details pre { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
@media (max-width: 768px) {
  .workspace-controls :deep(.el-date-editor) { width: 100%; }
}
</style>
