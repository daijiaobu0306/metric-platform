<template>
  <div style="position: relative;">
    <el-dialog class="workspace-theme gongdan-detail-dialog"
      v-model="visible"
      title="工单详情"
      :width="dialogWidth"
      :close-on-click-modal="false"
      @closed="handleClosed"
    >
      <template #header><div class="detail-dialog-heading"><span class="heading-dot"></span>工单详情</div></template>
      <div class="detail-container" v-loading="loading">
        <template v-if="detailData">
          <section class="detail-summary-card" aria-label="工单基础信息">
            <div class="summary-heading">
              <div class="summary-heading-main">
                <div class="summary-eyebrow"><el-icon><Document /></el-icon>工单基础信息<span class="created-time">创建时间 {{ formatDateTime(detailData.createTime) || '-' }}</span></div>
                <h2>{{ detailData.gongdanTitle || '-' }}</h2>
              </div>
              <div class="summary-badges">
                <WorkspaceStatusBadge v-if="detailData.synced === 1" tone="success">已推送</WorkspaceStatusBadge>
                <WorkspaceStatusBadge v-else-if="detailData.externalSyncMsg" tone="danger">推送失败</WorkspaceStatusBadge>
                <WorkspaceStatusBadge v-else tone="info">未推送</WorkspaceStatusBadge>
                <WorkspaceStatusBadge :tone="detailData.closedStatus === 0 ? 'warning' : 'success'">{{ detailData.closedStatus === 0 ? '未关单' : '已关单' }}</WorkspaceStatusBadge>
                <WorkspaceStatusBadge v-if="trace?.current?.nodeName" :pulse="detailData.closedStatus === 0" :title="'BOMC 当前节点：' + trace.current.nodeName">{{ trace.current.nodeName }}</WorkspaceStatusBadge>
              </div>
            </div>
            <dl class="detail-info-grid">
              <div class="info-field field-mono"><dt>工单编号</dt><dd>{{ detailData.gongdanId || '-' }}</dd></div>
              <div class="info-field field-mono field-accent"><dt>BOMC工单号</dt><dd>{{ detailData.externalId || '未推送' }}</dd></div>
              <div class="info-field"><dt>工单类型</dt><dd>{{ detailData.gongdanType || '-' }}</dd></div>
              <div class="info-field field-mono"><dt>工单时间</dt><dd>{{ formatDateTime(detailData.gongdanTime) || '-' }}</dd></div>
              <div class="info-field"><dt>关单状态</dt><dd><el-tag :type="detailData.closedStatus === 0 ? 'warning' : 'success'" size="small">
                {{ detailData.closedStatus === 0 ? '未关单' : '已关单' }}
              </el-tag></dd></div>
              <div class="info-field"><dt>BOMC同步状态</dt><dd><el-tag v-if="detailData.synced === 1" type="success" size="small">已推送</el-tag>
              <el-tag v-else-if="detailData.externalSyncMsg" type="danger" size="small">推送失败</el-tag>
              <el-tag v-else type="info" size="small">未推送</el-tag></dd></div>
              <div class="info-field field-wide"><dt>告警对象</dt><dd>{{ detailData.alarmObject || '-' }}</dd></div>
              <div class="info-field"><dt>告警级别</dt><dd><el-tag :type="alarmLevelType(detailData.alarmLevel)" size="small">{{ alarmLevelText(detailData.alarmLevel) }}</el-tag></dd></div>
              <div class="info-field"><dt>优先级</dt><dd>{{ detailData.alarmPriority || '按级别自动推导' }}</dd></div>
              <div class="info-field"><dt>影响度</dt><dd>{{ detailData.alarmImpact || '按级别自动推导' }}</dd></div>
              <div class="info-field"><dt>告警次数</dt><dd>{{ detailData.alarmCount != null ? detailData.alarmCount : '-' }}</dd></div>
              <div class="info-field field-mono"><dt>首次发生时间</dt><dd>{{ formatDateTime(detailData.alarmFirstTime) || '-' }}</dd></div>
              <div class="info-field field-mono"><dt>末次发生时间</dt><dd>{{ formatDateTime(detailData.alarmLastTime) || '-' }}</dd></div>
              <div class="info-field"><dt>主要责任人</dt><dd>{{ detailData.responsiblePerson || '平台默认' }}</dd></div>
              <div class="info-field"><dt>次要责任人</dt><dd>{{ detailData.secondResponsible || '平台默认' }}</dd></div>
              <div class="info-field"><dt>BOMC处理人</dt><dd>{{ detailData.executeUser || '平台默认' }}</dd></div>
              <div class="info-field field-emphasis"><dt>当前处理人</dt><dd>{{ detailData.currentHandler || '-' }}</dd></div>
              <div class="info-field field-mono"><dt>创建时间</dt><dd>{{ formatDateTime(detailData.createTime) || '-' }}</dd></div>
              <div class="info-field field-mono"><dt>更新时间</dt><dd>{{ formatDateTime(detailData.updateTime) || '-' }}</dd></div>
            </dl>
            <div v-if="detailData.externalSyncMsg" class="sync-feedback" :class="{ 'is-success': detailData.synced === 1 }">
              <div class="sync-feedback-label"><el-icon><InfoFilled /></el-icon>BOMC 返回信息</div>
              <div class="sync-feedback-message">{{ detailData.externalSyncMsg }}</div>
            </div>
          </section>

          <section class="detail-section-card" aria-label="事件描述与告警内容">
          <div class="section-title"><el-icon class="content-icon"><WarningFilled /></el-icon>事件描述 / 告警内容</div>
          <div class="content-text">{{ formatContent(detailData.gongdanContent) }}</div>

          </section>
          <section class="detail-section-card" aria-label="BOMC 处理轨迹">
          <div class="section-title">
            <el-icon class="trace-icon"><Connection /></el-icon>BOMC 处理轨迹
            <el-button v-if="!isArchived" type="primary" link size="small" :loading="traceLoading" @click="loadTrace">刷新</el-button>
          </div>

          <el-alert
            v-if="!detailData.externalId"
            type="info"
            :closable="false"
            show-icon
            title="该工单尚未推送至 BOMC，暂无处理轨迹"
          />

          <!-- 已归档：不实时查询 BOMC。归档后 BOMC 侧已无该单处理信息，查询只会返回失败提示；
               节点也不会再变化，详情直接用本地库数据即可。 -->
          <el-alert
            v-else-if="isArchived"
            type="info"
            :closable="false"
            show-icon
            title="工单已归档，不再实时查询 BOMC 处理轨迹"
            description="归档后 BOMC 侧已无该工单的处理信息，当前节点不会再变化；此处展示本地库数据。"
          />

          <template v-else>
            <el-alert
              v-if="traceError"
              type="error"
              :closable="false"
              show-icon
              :title="traceError"
            />
            <template v-else-if="trace">
              <!-- 当前节点 -->
              <div v-if="trace.current" class="current-node">
                <div class="current-node-label">当前节点</div>
                <div class="current-node-body">
                  <span class="node-name">{{ trace.current.nodeName || '-' }}</span>
                  <span class="node-meta">处理人：{{ trace.current.userName || '-' }}</span>
                  <span class="node-meta">角色：{{ trace.current.roleName || '-' }}</span>
                  <span v-if="trace.current.dataOverTime" class="node-meta">超时：{{ trace.current.dataOverTime }}</span>
                </div>
              </div>

              <!-- 历史轨迹 -->
              <el-timeline v-if="sortedHistory.length" class="trace-timeline">
                <el-timeline-item
                  v-for="(item, index) in sortedHistory"
                  :key="index"
                  :timestamp="item.lastUpdate || '处理中'"
                  :type="index === sortedHistory.length - 1 ? 'primary' : ''"
                  placement="top"
                >
                  <div class="trace-item-title">
                    {{ item.nodeName || '-' }}
                    <el-tag v-if="item.actionName" size="small" type="info" effect="plain">{{ item.actionName }}</el-tag>
                  </div>
                  <div class="trace-item-meta">
                    处理人：{{ item.dealedBy || '-' }}
                    <span v-if="item.mobile">（{{ item.mobile }}）</span>
                  </div>
                  <div v-if="item.taskDealedIdea" class="trace-item-idea">{{ item.taskDealedIdea }}</div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-else description="BOMC 暂无历史节点" :image-size="60" />
            </template>
          </template>
          </section>
        </template>
        <el-empty v-else-if="!loading && !detailData" description="暂无数据" />
      </div>
      <template #footer>
        <div class="detail-footer">
        <el-button v-if="detailData && detailData.synced !== 1" plain :loading="pushing" @click="handlePush">
          推送到 BOMC
        </el-button>
        <el-button v-else-if="detailData" :loading="pushing" @click="handlePush(true)">重新推送 BOMC</el-button>
        <el-button type="primary" class="detail-close-button" @click="visible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustDialogWidth" :visible="visible" />
  </div>
</template>

<script setup>
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { gongdanApi, gongdanIntegrationApi } from '@/api'
import { formatDateTime, stripNumberTrailingZeros } from '@/utils'
import { useDialogSize } from '@/utils/useDialogSize'
import DialogSizeControls from '@/components/common/DialogSizeControls.vue'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  gongdanId: {
    type: [Number, String],
    default: null
  }
})

const emit = defineEmits(['update:modelValue', 'refresh'])

const loading = ref(false)
const traceLoading = ref(false)
const pushing = ref(false)
const detailData = ref(null)
const trace = ref(null)
const traceError = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const { width: dialogWidth, adjustWidth: adjustDialogWidth } = useDialogSize('gongdanDetail', 900)

// 监听弹窗打开，加载数据
watch(() => props.modelValue, (newVal) => {
  if (newVal && props.gongdanId) {
    loadDetail()
  }
}, { immediate: false })

// 监听 gongdanId 变化
watch(() => props.gongdanId, (newId) => {
  if (newId && props.modelValue) {
    loadDetail()
  }
})

// 历史轨迹按 sortData 升序展示（BOMC 返回顺序不保证）
const sortedHistory = computed(() => {
  if (!trace.value || !trace.value.history) return []
  return [...trace.value.history].sort((a, b) => {
    const sa = a.sortData != null ? a.sortData : 0
    const sb = b.sortData != null ? b.sortData : 0
    return sa - sb
  })
})

// 是否已归档：归档成功后后端打 bomc_action=ARCHIVED 并把 external_status 写成「已归档」。
// 已归档工单不再实时查询 BOMC —— 归档后 BOMC 侧已无该单处理信息，
// 实时查询（queryTask）只会返回「未查询到工单处理信息」，让详情页报错打扰用户。
const isArchived = computed(() => {
  const data = detailData.value
  if (!data) return false
  return data.bomcAction === 'ARCHIVED' || (data.externalStatus || '').trim() === '已归档'
})

const alarmLevelText = (level) => {
  const map = { 1: '警告告警', 2: '一般告警', 3: '重要告警', 4: '严重告警' }
  return map[level] || '未分级'
}

const alarmLevelType = (level) => {
  const map = { 1: 'info', 2: 'primary', 3: 'warning', 4: 'danger' }
  return map[level] || 'info'
}

const loadDetail = async () => {
  if (!props.gongdanId) return

  loading.value = true
  detailData.value = null
  trace.value = null
  traceError.value = ''

  try {
    const res = await gongdanApi.get(props.gongdanId)
    if (res.data) {
      detailData.value = res.data
      // 已归档工单不查 BOMC：详情只展示本地库数据，避免弹出「未查询到工单处理信息」
      if (res.data.externalId && !isArchived.value) {
        await loadTrace()
      }
    }
  } catch (error) {
    console.error('加载工单详情失败:', error)
  } finally {
    loading.value = false
  }
}

// 查询 BOMC 处理轨迹（透明化查询 queryTask）
const loadTrace = async () => {
  if (!detailData.value || !detailData.value.externalId) return
  // 已归档工单直接跳过 BOMC 查询（后端同样会拦截，这里提前返回减少无谓请求）
  if (isArchived.value) {
    trace.value = null
    traceError.value = ''
    return
  }
  traceLoading.value = true
  traceError.value = ''
  try {
    const res = await gongdanIntegrationApi.trace(detailData.value.id)
    trace.value = res.data || null
  } catch (error) {
    trace.value = null
    traceError.value = error?.response?.data?.message || error?.message || '查询 BOMC 处理轨迹失败'
  } finally {
    traceLoading.value = false
  }
}

// 推送 / 重新推送 BOMC
const handlePush = async (force = false) => {
  if (force) {
    try {
      await ElMessageBox.confirm(
        `该工单已推送过 BOMC（工单号 ${detailData.value.externalId}），重新推送会在 BOMC 再建一条工单，确定继续吗？`,
        '提示', { type: 'warning' })
    } catch (e) {
      return // 用户取消
    }
  }
  pushing.value = true
  try {
    const res = await gongdanIntegrationApi.push(detailData.value.id, !!force)
    const serial = res.data && res.data.bomcSerialNo
    ElMessage.success(serial ? `推送成功，BOMC 工单号 ${serial}` : '推送成功')
    await loadDetail()
    emit('refresh')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '推送失败')
    await loadDetail()
    emit('refresh')
  } finally {
    pushing.value = false
  }
}

const handleClosed = () => {
  detailData.value = null
  trace.value = null
  traceError.value = ''
}

// 详情中显示的工单内容：去掉数字尾随零（如 1.0000 → 1），同时保留空值/无内容的占位
const formatContent = (text) => {
  if (!text) return '-'
  return stripNumberTrailingZeros(text)
}
</script>

<style scoped>
/* 详情在所有入口共用同一视觉与真实数据，推送及轨迹逻辑保持不变。 */
:deep(.gongdan-detail-dialog.el-dialog) {
  --el-color-primary: var(--ui-primary);
  --el-color-primary-light-9: var(--ui-primary-soft);
  --el-border-color: var(--ui-border);
  --el-border-radius-base: 8px;
  display: flex;
  flex-direction: column;
  padding: 0;
  max-width: calc(100vw - 24px);
  max-height: 92vh;
  border-radius: 16px;
  overflow: hidden;
  color: var(--ui-text);
  box-shadow: 0 24px 60px #0f172a26;
}
:deep(.gongdan-detail-dialog.el-dialog .el-dialog__header) { flex-shrink: 0; padding: 20px 24px; margin: 0; background: #fbfcfe; border-bottom: 1px solid var(--ui-border); }
:deep(.gongdan-detail-dialog.el-dialog .el-dialog__headerbtn) { top: 9px; right: 12px; }
:deep(.gongdan-detail-dialog.el-dialog .el-dialog__body) { flex: 1; min-height: 0; overflow: auto; padding: 24px; scrollbar-width: thin; scrollbar-color: #cbd5e1 transparent; }
:deep(.gongdan-detail-dialog.el-dialog .el-dialog__footer) { flex-shrink: 0; padding: 14px 20px; background: var(--ui-page); border-top: 1px solid var(--ui-border); }
.detail-dialog-heading { display: flex; align-items: center; gap: 12px; color: var(--ui-heading); font-size: 17px; font-weight: 700; }
.heading-dot { width: 10px; height: 10px; border-radius: 50%; background: #f43f5e; }
.detail-container { min-height: 200px; }
.detail-summary-card { padding: 22px 24px; background: #f8fbff; border: 1px solid var(--ui-border); border-radius: 14px; }
.summary-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 18px; border-bottom: 1px solid var(--ui-border); }
.summary-heading-main { flex: 1; min-width: 0; }
.summary-eyebrow { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--ui-muted); font-size: 12px; font-weight: 600; }
.summary-eyebrow > .el-icon { color: var(--ui-primary); font-size: 15px; }
.created-time { color: var(--ui-subtle); font-size: 11px; font-weight: 400; }
.summary-heading h2 { margin: 9px 0 0; color: var(--ui-heading); font-size: 16px; font-weight: 700; line-height: 1.65; overflow-wrap: anywhere; }
.summary-badges { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; flex-shrink: 0; padding-top: 2px; }
.summary-badges :deep(.el-tag) { border-radius: 20px; padding: 0 9px; }
.detail-info-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 20px 24px; margin: 20px 0 0; }
.info-field { min-width: 0; }
.info-field dt { margin-bottom: 6px; color: #8a9db9; font-size: 12px; line-height: 1.4; }
.info-field dd { margin: 0; color: var(--ui-text); font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.field-wide { grid-column: span 2; }
.field-mono dd { font-family: Consolas, "SFMono-Regular", monospace; font-size: 12px; font-variant-numeric: tabular-nums; }
.field-accent dd { color: var(--ui-primary); font-weight: 600; }
.field-emphasis dd { color: var(--ui-heading); font-weight: 600; }
.info-field :deep(.el-tag) { font-size: 11px; border-radius: 6px; }
.sync-feedback { margin-top: 20px; padding: 12px 14px; background: var(--ui-danger-soft); border: 1px solid #ffe4e6; border-radius: 8px; }
.sync-feedback-label { display: flex; align-items: center; gap: 6px; color: #be123c; font-size: 11px; font-weight: 600; }
.sync-feedback-message { margin-top: 6px; color: #be123c; font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.sync-feedback.is-success { border-color: #d1fae5; background: var(--ui-success-soft); }
.sync-feedback.is-success .sync-feedback-label, .sync-feedback.is-success .sync-feedback-message { color: var(--ui-success); }
.detail-section-card { padding: 18px 20px; margin-top: 20px; border: 1px solid var(--ui-border); border-radius: 14px; background: var(--ui-surface); }
.section-title { margin: 0 0 14px; display: flex; align-items: center; gap: 8px; color: var(--ui-heading); font-size: 13px; font-weight: 600; }
.section-title > .el-button { margin-left: auto; }
.content-icon { color: #f59e0b; font-size: 16px; }
.trace-icon { color: var(--ui-primary); font-size: 16px; }
.content-text { padding: 16px 18px; border: 1px solid var(--ui-border-soft); border-radius: 9px; background: var(--ui-page); color: var(--ui-text); font-family: Consolas, "Microsoft YaHei", monospace; font-size: 12px; line-height: 2; white-space: pre-wrap; overflow-wrap: anywhere; }
.current-node { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; padding: 14px; border: 1px solid var(--ui-primary-border); border-radius: 9px; background: var(--ui-primary-soft); }
.current-node-label { font-size: 11px; color: var(--ui-primary); flex-shrink: 0; }
.current-node-body { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.node-name { color: var(--ui-heading); font-size: 13px; font-weight: 600; }
.node-meta { color: var(--ui-muted); font-size: 12px; }
.trace-timeline { padding: 0 0 0 4px; margin: 0; }
.trace-item-title { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; color: var(--ui-heading); font-size: 13px; font-weight: 600; }
.trace-item-meta { margin-top: 6px; color: var(--ui-subtle); font-size: 12px; }
.trace-item-idea { margin-top: 8px; padding: 10px 12px; border: 1px solid var(--ui-border-soft); border-radius: 8px; background: var(--ui-page); color: var(--ui-text); font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.detail-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-footer :deep(.el-button) { height: 36px; padding: 0 18px; border-radius: 9px; font-size: 12px; }
.detail-close-button { margin-left: auto !important; min-width: 76px; }
@media (max-width: 900px) {
  .detail-info-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
  .summary-heading { flex-direction: column; gap: 10px; }
  .summary-badges { justify-content: flex-start; }
}
@media (max-width: 540px) {
  :deep(.gongdan-detail-dialog.el-dialog .el-dialog__body) { padding: 12px; }
  .detail-summary-card { padding: 16px; }
  .detail-section-card { padding: 16px; margin-top: 14px; }
  .summary-heading h2 { font-size: 14px; }
  .detail-info-grid { gap: 16px 12px; }
  .info-field dd { font-size: 11px; }
  .current-node { align-items: flex-start; flex-direction: column; }
  .content-text { padding: 12px; }
}
</style>
