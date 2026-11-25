<template>
  <section v-loading="loading" class="complaint-report">
    <nav class="subcategory-nav surface" aria-label="投诉工单处理流程">
      <button v-for="(label, key) in flows" :key="key" type="button"
              :aria-pressed="flow === key" @click="selectFlow(key)">
        <span class="data-subcategory-label"><i aria-hidden="true" />{{ label }}</span>
      </button>
    </nav>
    <div class="metric-panel surface">
      <div class="metric-panel-header">
        <span>指标类型</span>
        <div>
          <el-button link size="small" @click="selectAllMetrics">全选</el-button>
          <el-button link size="small" @click="invertMetrics">反选</el-button>
        </div>
      </div>
      <div v-if="groupMetricRows.length" class="metric-options">
        <el-checkbox-group v-model="selectedMetricCodes" aria-label="指标类型" @change="page = 1">
          <el-checkbox v-for="metric in groupMetricRows" :key="metric.metricCode"
                       :value="metric.metricCode" size="small">{{ metric.metricName }}</el-checkbox>
        </el-checkbox-group>
      </div>
    </div>
    <div class="content-area">

      <div class="data-area">
        <div class="filter-row" v-balanced-query><WorkspaceSectionHeading :title="'满意度及投诉服务体验'" icon="DataAnalysis" />
          <el-date-picker v-model="filterDate" type="date" size="small" value-format="YYYY-MM-DD" placeholder="数据日期" clearable @change="selectDate" />
          <el-select v-if="readOnly && flow === 'raw'" v-model="source" size="small" class="scope-select" placeholder="全部投诉类型" @change="page = 1" clearable>
            <el-option v-for="type in scopeOptions" :key="type" :value="type" :label="type" />
          </el-select>
          <el-input v-model="search" size="small" class="keyword-input" placeholder="指标名称" clearable @keyup.enter="query" @clear="query" />
          <div class="query-actions"><el-button type="primary" size="small" :icon="Search" @click="query">查询</el-button><el-button size="small" @click="resetQuery">重置</el-button></div>
          <div class="toolbar-spacer" />
          <el-popover placement="bottom-end" trigger="click" :width="180">
            <template #reference><el-button size="small" :icon="Setting">列设置</el-button></template>
            <el-checkbox-group v-model="columns" class="column-options"><el-checkbox v-for="column in columnOptions" :key="column.value" :value="column.value" :label="column.value">{{ column.label }}</el-checkbox></el-checkbox-group>
          </el-popover>
        </div>
        <div class="data-meta"><span>{{ currentRows.length }} 项指标 · {{ populated }} 项有值</span><span v-if="flow === 'triangle'">仅汇总三个倒三角类型</span><el-button link size="small" @click="showNotes = true">口径说明</el-button></div>
        <el-alert v-for="error in result.errors" :key="error" type="error" :closable="false" :title="error" />
        <div class="desktop-table">
          <el-table v-sticky-header v-mirror-scroll :data="visibleRows" border stripe size="small" empty-text="该统计日暂无数据或没有匹配的指标">
            <el-table-column v-if="columns.includes('importedAt')" label="入库时间" width="170"><template #default>{{ selectedBatch?.importedAt?.replace('T', ' ') || '—' }}</template></el-table-column>
            <el-table-column v-if="!readOnly && columns.includes('source')" prop="sourceType" label="指标类型" min-width="200" />
            <el-table-column v-if="columns.includes('metricName')" prop="metricName" label="指标名称" min-width="170" />
            <el-table-column v-if="columns.includes('metricValue')" label="指标值" width="110" align="right"><template #default="{ row }"><span class="workspace-metric-value" :class="{ missing: row.metricValue == null }">{{ formatValue(row.metricValue) }}</span></template></el-table-column>
            <el-table-column v-if="columns.includes('unit')" prop="unit" label="单位" width="70" />
            <el-table-column v-if="readOnly && columns.includes('source')" label="投诉类型" min-width="210"><template #default="{ row }">{{ row.sourceScope || row.sourceType }}</template></el-table-column>
            <el-table-column v-if="columns.includes('status')" label="数据状态" min-width="120"><template #default="{ row }"><span :class="{ missing: row.metricValue == null }">{{ row.metricValue == null ? '无数据' : row.valueStatus === 'CALCULATED' ? '汇总计算' : '报表原值' }}</span></template></el-table-column>
            <el-table-column label="统计日期" width="110"><template #default>{{ selectedBatch?.businessDate || '—' }}</template></el-table-column>
            <el-table-column v-if="columns.includes('detail')" label="操作" width="70" align="center"><template #default="{ row }"><el-button link type="primary" size="small" @click="detail = row">明细</el-button></template></el-table-column>
          </el-table>
        </div>
        <div class="mobile-cards">
          <div v-for="row in visibleRows" :key="`${row.metricCode || row.sourceType}-${row.metricName}`" class="metric-card">
            <div class="metric-card-header"><strong>{{ row.metricName }}</strong><el-button v-if="readOnly" link type="primary" size="small" @click="detail = row">明细</el-button></div>
            <div class="metric-card-source">统计日期：{{ selectedBatch?.businessDate || '—' }}</div>
            <div v-if="!readOnly" class="metric-card-source">入库时间：{{ selectedBatch?.importedAt?.replace('T', ' ') || '—' }}</div>
            <div class="metric-card-value" :class="{ missing: row.metricValue == null }">{{ formatValue(row.metricValue) }} <small>{{ row.unit }}</small></div>
            <span class="metric-card-source">{{ !readOnly ? `指标类型：${row.sourceType}` : `${row.sourceScope || row.sourceType} · ${row.metricValue == null ? '无数据' : row.valueStatus === 'CALCULATED' ? '汇总计算' : '报表原值'}` }}</span>
          </div>
        </div>
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :total="filteredRows.length" :layout="paginationLayout" small class="pagination" @size-change="page = 1" />
      </div>
    </div>

    <el-dialog class="workspace-theme" v-model="showNotes" title="满意度指标口径" width="min(600px, 94vw)"><p>左侧采用最终清单指标：客服6项、倒三角6项、综调5项，可勾选、全选、反选。</p><p>表格展示对应指标的采集或计算结果与实际入库时间，空值以“—”展示。</p><p>客服取在线客服；综调取综调；倒三角仍汇总服务咨询、绿通（不含个人未完工）、个人未完工三个类型，指标类型标为“倒三角”。缺少分母按无数据处理。</p><p>对端原始字段仍保留在数据库。升级判责、挂起流程及原报表合计不纳入最终17项。</p></el-dialog>
    <el-drawer class="workspace-theme" :model-value="!!detail" title="指标明细" size="min(520px, 94vw)" @close="detail = null">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="指标">{{ detail.metricName }}</el-descriptions-item><el-descriptions-item label="指标值">{{ formatValue(detail.metricValue) }} {{ detail.unit }}</el-descriptions-item>
        <el-descriptions-item label="投诉类型">{{ detail.sourceScope || detail.sourceType }}</el-descriptions-item><el-descriptions-item label="原文件">{{ selectedBatch?.sourceFile }}</el-descriptions-item>
        <el-descriptions-item label="统计日 / 采集时间">{{ selectedBatch?.businessDate }} / {{ selectedBatch?.importedAt }}</el-descriptions-item><el-descriptions-item label="源位置">{{ detail.sourceCell || `第 ${detail.sourceRow} 行` }}</el-descriptions-item>
        <el-descriptions-item label="报表口径 / 计算依据">{{ detail.calculationNote || detail.sourceRule }}</el-descriptions-item><el-descriptions-item v-if="detail.definition" label="清单定义">{{ detail.definition }}</el-descriptions-item><el-descriptions-item v-if="detail.statisticalRule" label="清单口径">{{ detail.statisticalRule }}</el-descriptions-item><el-descriptions-item v-if="detail.thresholdNote" label="清单备注">{{ detail.thresholdNote }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </section>
</template>

<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Search, Setting } from '@element-plus/icons-vue'
import request from '../../api'
import { formatSatisfactionValue as formatValue, formatSatisfactionScope } from '../../utils/satisfactionReport'

const props = defineProps({ readOnly: Boolean, initialBatchId: { type: String, default: '' }, initialFlow: { type: String, default: 'customer' } })
const result = ref({ rows: [], raw: [], batches: [], errors: [] })
const batchId = ref(props.initialBatchId), filterDate = ref('')
const flow = ref(props.initialFlow), source = ref(props.initialFlow === 'triangle' ? '倒三角' : ''), search = ref(''), keyword = ref(''), page = ref(1), pageSize = ref(10)
const detail = ref(null), loading = ref(false), showNotes = ref(false)
const selectedMetricCodes = ref([])
let selectionBatch = null
const flows = { customer: '客服投诉工单处理流程', triangle: '倒三角工单处理流程', dispatch: '综调工单处理流程' }
function selectFlow(key) {
  if (flow.value === key) return
  flow.value = key
  resetFilters()
}
const groupMetricRows = computed(() => {
  const group = flows[flow.value]
  return result.value.rows.filter(row => row.groupName === group).map(row => ({ ...row, sourceScope: formatSatisfactionScope(row.sourceScope), sourceType: formatSatisfactionScope(row.sourceScope) }))
})
function selectAllMetrics() { selectedMetricCodes.value = groupMetricRows.value.map(row => row.metricCode); page.value = 1 }
function invertMetrics() { selectedMetricCodes.value = groupMetricRows.value.filter(row => !selectedMetricCodes.value.includes(row.metricCode)).map(row => row.metricCode); page.value = 1 }
const columns = ref(props.readOnly ? ['metricName', 'metricValue', 'unit', 'status', 'detail'] : ['importedAt', 'source', 'metricName', 'metricValue', 'unit'])
const columnOptions = props.readOnly ? [{ value: 'metricName', label: '指标名称' }, { value: 'metricValue', label: '指标值' }, { value: 'unit', label: '单位' }, { value: 'source', label: '投诉类型' }, { value: 'status', label: '数据状态' }, { value: 'detail', label: '操作' }] : [{ value: 'importedAt', label: '入库时间' }, { value: 'source', label: '指标类型' }, { value: 'metricName', label: '指标名称' }, { value: 'metricValue', label: '指标值' }, { value: 'unit', label: '单位' }]
const preferenceSection = props.readOnly ? 'satisfactionReport' : 'satisfactionAggregationV2'
const columnPreferenceKey = 'metric_column_prefs'
function loadColumns() {
  try {
    const saved = JSON.parse(localStorage.getItem(columnPreferenceKey) || '{}')?.[preferenceSection]
    if (Array.isArray(saved)) { const valid = saved.filter(value => columnOptions.some(option => option.value === value)); if (valid.length) columns.value = valid }
  } catch { /* Invalid local preferences fall back to visible default columns. */ }
}
watch(columns, values => {
  try {
    const saved = JSON.parse(localStorage.getItem(columnPreferenceKey) || '{}')
    if (saved && typeof saved === 'object' && !Array.isArray(saved)) localStorage.setItem(columnPreferenceKey, JSON.stringify({ ...saved, [preferenceSection]: values }))
  } catch { /* Unavailable browser storage does not block data display. */ }
}, { deep: true })
const width = ref(window.innerWidth)
const updateWidth = () => { width.value = window.innerWidth }
const paginationLayout = computed(() => width.value < 768 ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper')
const selectedBatch = computed(() => result.value.batches.find(batch => batch.id === batchId.value && (!filterDate.value || batch.businessDate === filterDate.value)))
const flowRows = computed(() => {
  if (!selectedBatch.value) return []
  return groupMetricRows.value.filter(row => selectedMetricCodes.value.includes(row.metricCode))
})
const scopeOptions = computed(() => [...new Set(flowRows.value.map(row => row.sourceScope || row.sourceType))].sort((a, b) => a === '倒三角' ? -1 : b === '倒三角' ? 1 : 0))
const currentRows = computed(() => flowRows.value.filter(row => !props.readOnly || !source.value || (row.sourceScope || row.sourceType) === source.value))
const populated = computed(() => currentRows.value.filter(row => row.metricValue != null).length)
const filteredRows = computed(() => currentRows.value.filter(row => !keyword.value || row.metricName.includes(keyword.value)))
const visibleRows = computed(() => filteredRows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
function resetFilters() { source.value = flow.value === 'triangle' ? '倒三角' : ''; search.value = ''; keyword.value = ''; selectAllMetrics(); page.value = 1 }
async function query() { keyword.value = search.value.trim(); page.value = 1; await load() }
async function selectDate() { batchId.value = filterDate.value ? result.value.batches.find(batch => batch.businessDate === filterDate.value)?.id || '' : ''; page.value = 1; await load() }
async function resetQuery() { filterDate.value = ''; batchId.value = ''; resetFilters(); await load() }
async function load() {
  loading.value = true
  try {
    const response = await request.get('/collection/satisfaction-report', { params: { batchId: batchId.value || undefined } })
    result.value = response.data
    if (selectionBatch !== response.data.batchId) { selectionBatch = response.data.batchId; selectAllMetrics(); page.value = 1 }
    if (!filterDate.value) {
      batchId.value = response.data.batchId || ''
    }
  } finally { loading.value = false }
}
watch(() => props.initialBatchId, async value => { batchId.value = value; filterDate.value = ''; await load() })
watch(() => props.initialFlow, value => { flow.value = value; resetFilters() })
onMounted(() => { window.addEventListener('resize', updateWidth); loadColumns(); load() })
onUnmounted(() => window.removeEventListener('resize', updateWidth))
</script>

<style scoped>
.surface { border: 1px solid var(--ui-border); border-radius: 14px; background: var(--ui-surface); }
.metric-panel { margin-top: 12px; padding: 12px 16px; }
.metric-panel-header { display: flex; align-items: center; justify-content: space-between; font-size: 13px; color: var(--el-text-color-regular); }
.metric-options .el-checkbox-group { display: flex; flex-wrap: wrap; gap: 4px 20px; }
.metric-options .el-checkbox { margin: 0; height: auto; min-height: 24px; max-width: 100%; white-space: normal; }
.content-area { display: flex; gap: 10px; margin-top: 8px; }
.metric-sidebar.source-sidebar { width: 245px; }
.source-groups { max-height: 65vh; overflow-y: auto; }
.source-metrics { display: flex; flex-direction: column; padding-left: 16px; }
.source-groups :deep(.el-collapse-item__header) { height: auto; min-height: 36px; line-height: 1.4; }
.metric-sidebar.source-sidebar :deep(.el-checkbox) { height: auto; min-height: 24px; align-items: flex-start; padding: 3px 0; }
.metric-sidebar.source-sidebar :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; line-height: 1.4; font-size: 12px; }
.source-sidebar :deep(.el-collapse-item__content) { padding-bottom: 6px; }
.metric-sidebar { width: 180px; flex-shrink: 0; background: var(--ui-page); border: 1px solid var(--ui-border-soft); border-radius: 4px; padding: 8px; align-self: flex-start; }
.sidebar-header { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--ui-muted); margin-bottom: 6px; }
.sidebar-header .el-button { margin-left: 4px; }
.metric-sidebar .el-checkbox-group, .column-options { display: flex; flex-direction: column; }
.metric-sidebar :deep(.el-checkbox) { height: 28px; margin-right: 0; }
.metric-sidebar :deep(.el-checkbox__label) { font-size: 13px; }
.kind-count { color: var(--ui-muted); font-size: 12px; margin-left: 6px; }
.sidebar-footer { border-top: 1px solid var(--ui-border-soft); padding-top: 8px; margin-top: 8px; font-size: 12px; line-height: 1.7; color: var(--ui-muted); }
.data-area { flex: 1; min-width: 0; }
.filter-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 6px; }
.filter-row :deep(.el-date-editor) { width: 120px; }
.scope-select { width: 245px; }.keyword-input { width: 140px; }.toolbar-spacer { flex: 1; }
.data-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; color: var(--ui-muted); font-size: 12px; margin: 4px 0 8px; }
.missing { color: var(--ui-muted); }.pagination { justify-content: flex-end; margin-top: 10px; }
.mobile-cards { display: none; }.metric-card { border: 1px solid var(--ui-border-soft); border-radius: 4px; padding: 10px 12px; margin-bottom: 8px; background: var(--ui-surface); }
.metric-card-header { display: flex; align-items: center; justify-content: space-between; font-size: 13px; }.metric-card-value { font-size: 22px; font-weight: 500; margin: 6px 0; color: var(--ui-heading); }.metric-card-value.missing { color: var(--ui-muted); }.metric-card-value small { font-size: 12px; font-weight: normal; color: var(--ui-muted); }.metric-card-source { font-size: 12px; color: var(--ui-muted); }
@media (max-width: 767px) { .metric-sidebar.source-sidebar { width: 100%; } }
@media (max-width: 767px) { .content-area { flex-direction: column; }.metric-sidebar { width: 100%; box-sizing: border-box; }.metric-sidebar .el-checkbox-group { flex-direction: row; flex-wrap: wrap; gap: 6px 18px; }.sidebar-footer { display: none; }.data-area { width: 100%; }.desktop-table { display: none; }.mobile-cards { display: block; }.pagination { justify-content: center; }.data-meta { gap: 2px 12px; }.toolbar-spacer { display: none; } }
</style>
