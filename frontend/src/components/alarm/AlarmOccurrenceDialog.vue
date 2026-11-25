<template>
  <el-dialog v-model="visible" title="告警发生明细" width="1120px" :style="{ maxWidth: 'calc(100vw - 32px)' }"
             append-to-body destroy-on-close class="workspace-theme occurrence-dialog">
    <template v-if="group">
      <section class="occurrence-identity">
        <div>
          <div class="identity-kicker">{{ group.alarmType }} <span>当前筛选范围内的历史记录</span></div>
          <h3>{{ group.targetName || group.metricName || group.categoryName || group.systemName || '告警对象' }}</h3>
          <div class="identity-path">{{ [group.systemName, group.categoryName, group.subCategoryName].filter(Boolean).join(' / ') || '历史对象信息未完整记录' }}</div>
        </div>
        <el-tag effect="plain" type="info">逐次记录保留，策略修改不会重写历史</el-tag>
      </section>
      <section class="occurrence-stats">
        <div class="count-stat"><span>触发次数</span><strong>{{ total }}</strong><small>当前筛选范围</small></div>
        <div><span>处理情况</span><strong class="status-count"><b>{{ summary.unprocessedCount || 0 }}</b> 次未处理</strong><small>{{ summary.processedCount || 0 }} 次已处理 · {{ summary.ticketCount || 0 }} 次工单记录</small></div>
        <div><span>首次发生</span><strong class="date-stat">{{ formatTime(summary.firstAlarmTime) }}</strong><small>本组最早的一次记录</small></div>
        <div><span>最近发生</span><strong class="date-stat">{{ formatTime(summary.lastAlarmTime) }}</strong><small>记录按发生时间倒序排列</small></div>
      </section>
      <div class="history-toolbar"><strong>发生记录</strong><span>点击展开查看完整告警内容和处理信息</span><el-button link type="primary" :loading="loading" @click="load">刷新明细</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="history-error" />
      <el-table :data="events" v-loading="loading" row-key="id" border max-height="430" class="history-table">
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <div class="event-expanded">
              <section><h4>这次发生了什么</h4><p class="event-content">{{ row.alarmContent || '未记录告警内容' }}</p></section>
              <div class="event-metadata">
                <span>记录 ID<strong>#{{ row.id }}</strong></span><span>关联策略<strong>{{ row.strategyId ? `#${row.strategyId}` : '未关联' }}</strong></span>
                <span>处理人<strong>{{ row.processUser || '未记录' }}</strong></span><span>处理时间<strong>{{ formatTime(row.processTime) }}</strong></span>
                <span>屏蔽到期<strong>{{ row.shieldStatus === 1 ? formatTime(row.shieldEndTime, '未设置结束时间') : '未屏蔽' }}</strong></span>
                <span v-if="row.sourceTable">原始来源<strong>{{ row.sourceTable }} / {{ row.sourceId ?? '未记录ID' }}</strong></span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="发生时间" prop="alarmTime" width="172"><template #default="{ row }">{{ formatTime(row.alarmTime) }}</template></el-table-column>
        <el-table-column label="级别" width="82"><template #default="{ row }"><el-tag size="small" :type="level(row.alarmLevel).type">{{ level(row.alarmLevel).label }}</el-tag></template></el-table-column>
        <el-table-column label="发生时数值" min-width="120"><template #default="{ row }">{{ valueText(row) }}</template></el-table-column>
        <el-table-column label="发生时阈值" min-width="120"><template #default="{ row }">{{ numberText(row.thresholdValue, unitOf(row)) }}</template></el-table-column>
        <el-table-column label="处理状态" width="92"><template #default="{ row }"><el-tag :type="row.processStatus === 0 ? 'danger' : 'info'" size="small">{{ statusText(row.processStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="屏蔽" width="82"><template #default="{ row }">{{ row.shieldStatus === 1 ? '已屏蔽' : '未屏蔽' }}</template></el-table-column>
        <el-table-column label="本次操作" width="224" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.processStatus === 0 || row.processStatus === 1" link type="primary" size="small" @click="$emit('process', row)">{{ row.processStatus === 0 ? '处理' : '取消处理' }}</el-button>
            <el-button link type="warning" size="small" @click="$emit('shield', row)">{{ row.shieldStatus === 1 ? '解除屏蔽' : '屏蔽' }}</el-button>
            <el-button link type="primary" size="small" @click="$emit('issue', row)">{{ row.tagStatus === 1 ? '查看问题' : '转问题' }}</el-button>
            <el-button v-if="row.processStatus !== 3 || row.relatedGongdanId" link type="primary" size="small" @click="$emit('gongdan', row)">{{ row.relatedGongdanId ? '查看工单' : '转工单' }}</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当前筛选范围内已无记录，可关闭窗口后调整筛选条件" :image-size="70" /></template>
      </el-table>
      <div class="history-footer"><span>此处操作仅作用于所选的这一次记录。</span><el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" @current-change="load" /></div>
    </template>
    <template #footer>
      <div class="occurrence-dialog-footer">
        <span class="grouping-note">汇总规则：同一对象、同一类型，距上次告警超过30分钟，后续告警将另起一组统计。</span>
        <el-button @click="visible = false">关闭</el-button>
      </div>
    </template>
  </el-dialog>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { alarmApi } from '../../api'
import { ruleLevels } from '../../utils/strategyRules'
const props = defineProps({ modelValue: Boolean, group: Object, scope: Object })
const emit = defineEmits(['update:modelValue', 'process', 'shield', 'issue', 'gongdan'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const loading = ref(false), error = ref(''), events = ref([]), total = ref(0), summary = ref({}), page = ref(1)
const size = 10
let requestVersion = 0
watch(() => [props.modelValue, props.group?.groupKey], ([show]) => {
  requestVersion++
  if (show && props.group) { page.value = 1; events.value = []; total.value = props.group.alarmCount; summary.value = { ...props.group }; load() }
})
async function load () {
  if (!props.group) return
  const version = ++requestVersion
  loading.value = true; error.value = ''
  try {
    const res = await alarmApi.history({ ...props.scope, groupKey: props.group.groupKey, page: page.value - 1, size })
    if (version !== requestVersion) return
    total.value = res.data.totalElements; events.value = res.data.content; summary.value = res.data.summary
    if (!events.value.length && total.value && page.value > 1) { page.value = 1; await load() }
  } catch (exception) { if (version === requestVersion) { events.value = []; error.value = exception.message || '读取明细失败，请重试' } }
  finally { if (version === requestVersion) loading.value = false }
}
const level = value => ruleLevels.find(item => item.value === value) || { label: '未定级', type: 'info' }
const unitOf = row => row.alarmType?.includes('健康度') ? '分' : row.unit || ''
const numberText = (value, unit) => value == null ? '未记录' : `${value}${unit ? ` ${unit}` : ''}`
const valueText = row => numberText(row.alarmType?.includes('健康度') ? row.healthScore : row.metricValue, unitOf(row))
const formatTime = (value, fallback = '未记录') => value ? String(value).replace('T', ' ').slice(0, 19) : fallback
const statusText = value => ({ 0: '未处理', 1: '已处理', 2: '已关闭', 3: '工单记录' }[value] || '其他状态')
defineExpose({ reload: load })
</script>
<style scoped>
.occurrence-identity { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 4px 0 20px; border-bottom: 1px solid var(--ui-border-soft); }
.identity-kicker { color: var(--ui-primary); font-size: 12px; }.identity-kicker span { color: var(--ui-muted); margin-left: 12px; }
h3 { color: var(--ui-heading); margin: 8px 0; font-size: 20px; line-height: 1.4; }.identity-path { color: var(--ui-muted); font-size: 12px; line-height: 1.6; }
.occurrence-stats { display: grid; grid-template-columns: .7fr 1fr 1.2fr 1.2fr; gap: 0; background: #f7f9fc; border: 1px solid var(--ui-border-soft); border-radius: 10px; margin: 18px 0 22px; padding: 18px 0; }
.occurrence-stats > div { display: flex; flex-direction: column; gap: 8px; padding: 0 20px; border-left: 1px solid var(--ui-border); }.occurrence-stats > div:first-child { border-left: 0; }
.occurrence-stats span, .occurrence-stats small { color: var(--ui-muted); font-size: 12px; }.occurrence-stats strong { font-weight: 500; color: var(--ui-heading); }.count-stat strong { font-size: 30px; color: var(--ui-primary); line-height: 1; }.date-stat { font-size: 14px; }.status-count { font-size: 13px; }.status-count b { color: var(--ui-danger); font-size: 22px; font-weight: 500; }
.history-toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }.history-toolbar strong { color: var(--ui-heading); font-size: 14px; }.history-toolbar span { flex: 1; color: var(--ui-muted); font-size: 12px; }
.event-expanded { padding: 18px 24px; background: var(--ui-page); display: grid; grid-template-columns: 1.3fr 1fr; gap: 28px; }.event-expanded h4 { margin: 0 0 8px; color: var(--ui-text); font-size: 12px; font-weight: 500; }.event-content { margin: 0; color: var(--ui-heading); line-height: 1.8; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; }
.event-metadata { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }.event-metadata span { color: var(--ui-muted); font-size: 12px; }.event-metadata strong { display: block; color: var(--ui-text); font-weight: 400; margin-top: 5px; overflow-wrap: anywhere; }
.history-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; }.history-footer > span { color: var(--ui-muted); font-size: 12px; }.history-error { margin-bottom: 12px; }
.occurrence-dialog-footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.grouping-note { color: var(--ui-muted); font-size: 12px; line-height: 1.7; text-align: left; }
@media (max-width: 760px) { .occurrence-stats { grid-template-columns: 1fr 1fr; row-gap: 20px; }.occurrence-identity { flex-wrap: wrap; }.event-expanded { grid-template-columns: 1fr; }.history-footer { flex-wrap: wrap; } }
</style>
