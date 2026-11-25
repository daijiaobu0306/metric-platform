<template>
  <el-dialog v-model="visible" width="min(1120px,94vw)" top="5vh" class="workspace-theme dashboard-workspace screen-metric-detail" destroy-on-close @closed="emit('closed')">
    <template #header>
      <div class="detail-heading"><span class="heading-dot" :class="{ danger: metric?.healthScore != null && Number(metric.healthScore) < 70 }"></span><div>
        <div class="detail-title"><h2>{{ metric?.metricName || '指标详情' }}</h2><el-tag effect="plain" size="small">指标详情</el-tag><MetricHealthStatus :score="metric?.healthScore" /></div>
        <p>{{ [metric?.systemName,metric?.categoryName,metric?.subCategoryName].filter(Boolean).join(' / ') || '当前授权指标' }}<span>编码：{{ definition?.code || metric?.metricCode || '—' }}</span></p>
      </div></div>
    </template>
    <div v-loading="loading" class="detail-content">
      <section class="detail-summary">
        <div><span class="field-label">指标编码</span><WorkspaceInlineCode :value="definition?.code || metric?.metricCode" empty-text="—" /></div>
        <div><span class="field-label">指标定义 / 业务含义</span><p>{{ definition?.definition || '—' }}</p></div>
        <div><span class="field-label">单位 / 告警阈值</span><p><b>{{ definition?.unit || '—' }}</b><span class="threshold"> / {{ definition?.threshold == null ? '未配置' : definition.threshold }}</span></p></div>
        <div class="value-field" :class="{ 'is-danger': metric?.healthScore != null && Number(metric.healthScore) < 70 }"><span class="field-label">最新值 / 健康度</span><div class="latest-value">{{ metric?.latestValue ?? '—' }}<MetricHealthStatus :score="metric?.healthScore" show-score /></div></div>
      </section>
      <el-alert v-if="detail?.notice || detail?.valueNotice" :title="detail.notice || detail.valueNotice" type="info" :closable="false" show-icon />
      <section class="detail-panel"><WorkspaceSectionHeading :title="`${metric?.metricName || '指标'}趋势分析`" description="指标值与健康度双线 · 展示当前所选时间范围的实际采样" icon="TrendCharts" /><div class="detail-chart"><MetricValueHealthChart :values="trendSeries" :unit="definition?.unit || ''" :height="280" /></div></section>
      <section class="detail-panel records-panel"><WorkspaceSectionHeading title="近期采集记录" :description="`当前返回 ${records.length} 条记录`" icon="Document" />
        <el-table :data="records" size="small" max-height="320" empty-text="当前时间范围暂无可展示的采集记录">
          <el-table-column label="数据时间" min-width="180" class-name="workspace-time-cell"><template #default="{ row }">{{ formatTime(row.time || row.recordTime || row.dataTime) }}</template></el-table-column>
          <el-table-column label="指标值" min-width="110"><template #default="{ row }">{{ row.value ?? row.metricValue ?? '—' }}</template></el-table-column>
          <el-table-column label="健康度" width="100"><template #default="{ row }">{{ row.healthScore ?? '—' }}</template></el-table-column>
          <el-table-column label="状态" min-width="130"><template #default="{ row }"><WorkspaceStatusBadge :tone="recordTone(row.status)">{{ row.status || '—' }}</WorkspaceStatusBadge></template></el-table-column>
        </el-table>
      </section>
    </div>
    <template #footer><div class="detail-footer"><span><i></i>最近数据时间：{{ formatTime(metric?.dataTime || metric?.latestDataTime) }}</span><el-button @click="visible = false">关闭</el-button></div></template>
  </el-dialog>
</template>
<script setup>
import { computed } from 'vue'
import MetricValueHealthChart from './MetricValueHealthChart.vue'
import MetricHealthStatus from './MetricHealthStatus.vue'
import WorkspaceInlineCode from '@/components/workspace/WorkspaceInlineCode.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import { mergeMetricTrendSeries } from './metricTrendSeries'
const visible = defineModel({ type:Boolean, default:false })
const props = defineProps({ metric:Object, detail:Object, loading:Boolean })
const emit = defineEmits(['closed'])
const definition = computed(() => props.detail?.definition?.[0])
const records = computed(() => props.detail?.values || [])
// 趋势图同时画指标值（蓝）与健康度（绿）：values 只有采集值、healthTrend 是区间健康度历史，
// 按分钟合并后再交给图表；没有区间健康度记录时用快照趋势兜底，至少保留绿线。
const trendSeries = computed(() => mergeMetricTrendSeries(props.detail?.values, props.detail?.healthTrend, props.detail?.data))
const formatTime = value => value ? String(value).replace('T',' ').slice(0,19) : '—'
const recordTone = status => ({ alarm:'danger', 异常:'danger', warning:'warning', 关注:'warning', normal:'success', 正常:'success', 已采集:'success' })[status] || 'muted'
</script>
<style scoped>
.detail-heading { display:flex; align-items:center; gap:12px; padding-right:30px; }.heading-dot { width:12px; height:12px; flex-shrink:0; border-radius:50%; background:var(--ui-primary); box-shadow:0 0 0 5px var(--ui-primary-soft); }.heading-dot.danger { background:var(--ui-danger); box-shadow:0 0 0 5px var(--ui-danger-soft); }
.detail-title { display:flex; align-items:center; flex-wrap:wrap; gap:10px; }h2 { margin:0; color:var(--ui-heading); font-size:20px; line-height:1.5; }.detail-heading p { margin:5px 0 0; color:var(--ui-muted); font-size:12px; }.detail-heading p span { margin-left:12px; color:var(--ui-subtle); }
.detail-content { display:grid; gap:22px; }.detail-summary { display:grid; grid-template-columns:1fr 2.1fr 1fr 1.2fr; gap:14px; padding:18px; border:1px solid var(--ui-border); border-radius:14px; }.detail-summary > div { min-width:0; padding:15px 12px; border:1px solid var(--ui-border-soft); border-radius:9px; background:var(--ui-page); }.field-label { display:block; margin-bottom:12px; color:var(--ui-subtle); font-size:12px; }.detail-summary p { margin:0; color:var(--ui-text); font-size:13px; line-height:1.7; overflow-wrap:anywhere; }.threshold { color:var(--ui-muted); }.latest-value { display:flex; align-items:center; flex-wrap:wrap; gap:9px; color:var(--ui-primary-strong); font-size:24px; font-weight:700; font-variant-numeric:tabular-nums; }.detail-summary .is-danger { border-color:var(--ui-danger-border); background:var(--ui-danger-soft); }.is-danger .latest-value { color:var(--ui-danger); }
.detail-panel { min-width:0; padding:20px; border:1px solid var(--ui-border); border-radius:14px; }.detail-chart { margin-top:16px; padding:12px; border:1px solid var(--ui-border-soft); border-radius:9px; background:var(--ui-page); }.records-panel :deep(.el-table) { margin-top:16px; }.detail-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; }.detail-footer > span { color:var(--ui-subtle); font-size:12px; }.detail-footer i { display:inline-block; width:6px; height:6px; margin-right:8px; border-radius:50%; background:var(--ui-success); }
@media(max-width:768px) { .detail-summary { grid-template-columns:1fr 1fr; padding:12px; gap:10px; }.detail-panel { padding:14px; }.detail-heading p span { display:block; margin:4px 0 0; }h2 { font-size:17px; }.detail-footer { flex-wrap:wrap; } }
</style>
