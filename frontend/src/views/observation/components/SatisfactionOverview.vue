<template>
  <el-card shadow="never" class="satisfaction-overview" v-loading="loading">
    <template #header>
      <div class="section-header"><div><span>满意度及投诉服务体验</span><el-tag size="small" effect="plain" type="info">单日指标</el-tag></div><div class="header-actions"><span class="report-date">{{ selectedBatch?.businessDate || '暂无数据' }}</span><el-button link size="small" @click="load">刷新</el-button><el-button type="primary" link size="small" @click="openReport">查看指标</el-button></div></div>
    </template>
    <div class="summary-meta">{{ populated }} / {{ mappedRows.length }} 项清单指标有值<span>健康度阈值待确认，当前不参与系统健康度评分</span></div>
    <div class="desktop-table">
      <el-table :data="flows" border size="small" empty-text="尚未采集满意度报表">
        <el-table-column prop="name" label="指标流程" min-width="180" />
        <el-table-column v-for="name in metricNames" :key="name" :label="name" :min-width="name === '96小时超时量' ? 120 : 95" align="right"><template #default="{ row }">{{ displayMetric(row, name) }}</template></el-table-column>
        <el-table-column label="操作" width="80" align="center"><template #default="{ row }"><el-button link type="primary" size="small" @click="openReport(row.name)">明细</el-button></template></el-table-column>
      </el-table>
    </div>
    <div class="mobile-cards"><div v-for="flow in flows" :key="flow.name" class="flow-card"><div class="flow-heading"><strong>{{ flow.name }}</strong><el-button link type="primary" size="small" @click="openReport(flow.name)">明细</el-button></div><div class="flow-values"><div v-for="metric in flow.values" :key="metric.metricCode"><span>{{ metric.metricName }}</span><strong>{{ displayMetric(flow, metric.metricName) }}</strong></div></div></div></div>
    <div class="source-note">倒三角只汇总三个类型；缺分母及原报表缺数显示“—”。{{ selectedBatch ? `采集时间：${selectedBatch.importedAt.replace('T', ' ')}` : '' }}</div>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api'
import { formatSatisfactionValue as formatValue, satisfactionFlowOverview } from '@/utils/satisfactionReport'

const router = useRouter(), loading = ref(false)
const result = ref({ rows: [], raw: [], batches: [], batchId: null })
const selectedBatch = computed(() => result.value.batches.find(batch => batch.id === result.value.batchId))
const mappedRows = computed(() => result.value.rows.filter(row => row.sourceRow > 0))
const populated = computed(() => mappedRows.value.filter(row => row.metricValue != null).length)
const flows = computed(() => result.value.rows.length ? satisfactionFlowOverview(result.value.rows) : [])
const metricNames = ['工单累计量', '及时率', '满意率', '一次解决率', '重投率', '回退率', '96小时超时量']
function displayMetric(flow, name) {
  const metric = flow.values.find(value => value.metricName === name)
  if (!metric) return '不适用'
  return metric.metricValue == null ? '—' : `${formatValue(metric.metricValue)}${metric.unit === '%' ? '%' : ''}`
}
function openReport(group) {
  const flow = { '客服投诉工单处理流程': 'customer', '倒三角工单处理流程': 'triangle', '综调工单处理流程': 'dispatch' }[group]
  router.push({ path: '/report', query: { tab: 'realtime', category: 'satisfactionMetrics', batchId: result.value.batchId || undefined, flow } })
}
async function load() { loading.value = true; try { const response = await request.get('/collection/satisfaction-report'); result.value = response.data } finally { loading.value = false } }
onMounted(load)
</script>

<style scoped>
.satisfaction-overview { margin: 16px 0; border-radius: 4px; }
.section-header, .section-header > div, .header-actions { display: flex; align-items: center; gap: 10px; font-size: 14px; }.section-header { justify-content: space-between; flex-wrap: wrap; gap: 6px; }
.report-date, .summary-meta, .source-note { font-size: 12px; color: #909399; }.summary-meta { display: flex; gap: 8px 20px; flex-wrap: wrap; margin-bottom: 10px; }.source-note { margin-top: 8px; line-height: 1.7; }.mobile-cards { display: none; }
.flow-card { border: 1px solid #ebeef5; border-radius: 4px; padding: 10px 12px; margin-bottom: 8px; }.flow-heading { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }.flow-values { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 10px; }.flow-values span { display: block; font-size: 12px; color: #909399; }.flow-values strong { display: block; margin-top: 4px; color: #303133; font-size: 18px; font-weight: 500; }
@media (max-width: 767px) { .desktop-table { display: none; }.mobile-cards { display: block; }.section-header > div { flex-wrap: wrap; } }
</style>
