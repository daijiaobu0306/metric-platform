<template>
  <el-drawer :model-value="modelValue" title="AI 权重建议审核" size="min(1500px, 100%)"
    class="workspace-theme" @update:model-value="$emit('update:modelValue', $event)">
    <div class="review-workspace">
      <header class="review-toolbar">
        <div class="review-filters">
          <el-select v-model="filters.type" placeholder="全部来源" aria-label="建议来源" clearable @change="reload">
            <el-option v-for="(label, key) in types" :key="key" :label="label" :value="key" />
          </el-select>
          <el-select v-model="filters.category" placeholder="全部指标大类" aria-label="指标大类" filterable clearable @change="reload">
            <el-option v-for="category in categories" :key="category.id" :label="category.categoryName" :value="category.id" />
          </el-select>
          <el-select v-model="filters.state" placeholder="全部状态" aria-label="审核状态" clearable @change="reload">
            <el-option v-for="(label, key) in states" :key="key" :label="label" :value="key" />
          </el-select>
        </div>
        <div class="review-toolbar-actions">
          <el-button :icon="Download" :loading="downloadingSkills" @click="downloadSkills">下载 Skills</el-button>
          <el-button type="primary" :icon="Refresh" :loading="loadingRuns" @click="reload">检查新建议</el-button>
        </div>
      </header>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="review-alert" />

      <div class="review-layout">
        <aside class="review-inbox" v-loading="loadingRuns" aria-label="建议批次">
          <header class="inbox-header">
            <h2>建议批次</h2>
            <span>本页 {{ runs.length }} 批</span>
          </header>
          <WorkspaceEmptyState v-if="!runs.length" title="暂无匹配的建议" />
          <div v-else class="inbox-batches">
            <button v-for="row in runs" :key="row.id" type="button" class="batch-link"
              :class="{ 'is-selected': row.id === batch?.id }"
              :aria-current="row.id === batch?.id ? 'true' : undefined"
              @click="openBatch(row.id)">
              <span class="batch-path">
                <span>{{ categoryName(row) }}</span>
                <el-icon aria-hidden="true"><ArrowRight /></el-icon>
                <strong>{{ subcategoryName(row) }}</strong>
              </span>
              <span class="batch-link-heading">
                <span>{{ types[row.recommendation_type] }} · #{{ row.id }}</span>
                <span v-if="isAcceptance(row)" class="batch-acceptance">开发验收</span>
              </span>
              <span class="batch-link-meta">
                <time>{{ time(row.generated_at).slice(0, 16) }}</time>
                <WorkspaceStatusBadge :tone="batchState(row).tone">{{ batchState(row).label }}</WorkspaceStatusBadge>
              </span>
            </button>
          </div>
          <footer v-if="cursors.length || runs.length === 25" class="inbox-pagination">
            <el-button size="small" :disabled="!cursors.length || loadingRuns" @click="previousPage">上一页</el-button>
            <el-button size="small" :disabled="runs.length < 25 || loadingRuns" @click="nextPage">下一页</el-button>
          </footer>
        </aside>

        <article v-loading="loading" class="review-detail" aria-label="具体指标与权重变化">
          <WorkspaceEmptyState v-if="!batch" title="选择一个批次查看指标变化" />
          <template v-else>
            <header class="batch-header">
              <div class="batch-context">
                <div><small>指标大类</small><strong>{{ categoryName(batch) }}</strong></div>
                <el-icon aria-hidden="true"><ArrowRight /></el-icon>
                <div><small>指标小类</small><strong>{{ subcategoryName(batch) }}</strong></div>
              </div>
              <div class="batch-badges">
                <WorkspaceStatusBadge v-if="isAcceptance(batch)" tone="warning">开发验收</WorkspaceStatusBadge>
                <WorkspaceStatusBadge :tone="batchState(batch).tone">{{ batchState(batch).label }}</WorkspaceStatusBadge>
              </div>
            </header>

            <section class="analysis-card" aria-label="批次分析">
              <div class="analysis-top">
                <div>
                  <span class="analysis-kicker">批次 #{{ batch.id }} · {{ types[batch.recommendation_type] }}</span>
                  <h3>批次摘要</h3>
                </div>
                <el-button v-if="batch.raw_analysis" size="small" :icon="analysisOpen ? ArrowUp : ArrowDown"
                  :aria-expanded="analysisOpen" @click="analysisOpen = !analysisOpen">
                  {{ analysisOpen ? '收起完整分析' : '查看完整分析' }}
                </el-button>
              </div>
              <p v-if="batch.summary" class="analysis-summary">{{ batch.summary }}</p>
              <dl class="review-meta">
                <div><dt>生成时间（北京时间）</dt><dd>{{ time(batch.generated_at) }}</dd></div>
                <div><dt>有效期至</dt><dd>{{ time(batch.expires_at) }}</dd></div>
                <div><dt>Skill</dt><dd>{{ batch.skill_name }} · {{ batch.skill_version }}</dd></div>
                <div v-if="batch.agent_model"><dt>模型</dt><dd>{{ batch.agent_model }}</dd></div>
                <div v-if="batch.period_start"><dt>统计周期</dt><dd>{{ time(batch.period_start) }} 至 {{ time(batch.period_end) }}</dd></div>
              </dl>
              <div v-if="analysisOpen && batch.raw_analysis" class="analysis-body">
                <strong>完整分析</strong>
                <p class="review-text">{{ batch.raw_analysis }}</p>
              </div>
            </section>

            <el-alert v-if="batch.expired || batch.error_message" class="review-alert" type="warning" :closable="false"
              :title="batch.error_message || '本批次已过期。批准需要重新生成建议。'" />
            <div class="metric-section-title">
              <h3>具体指标</h3>
              <span>共 {{ batch.items.length }} 项</span>
            </div>
            <el-table :key="batch.id + '-' + batchRevision" :data="batch.items" row-key="id" empty-text="本批次暂无指标建议"
              @selection-change="onSelection">
              <el-table-column type="selection" width="46" :selectable="row => row.review_status === 'pending'" />
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="review-evidence">
                    <strong>建议理由</strong>
                    <p class="review-text">{{ row.reason }}</p>
                    <strong>证据{{ row.quality ? ' · ' + row.quality : '' }}</strong>
                    <dl class="evidence-fields">
                      <div v-for="[label, value] in evidenceFields(row.evidence)" :key="label">
                        <dt>{{ label }}</dt>
                        <dd v-if="value == null || typeof value !== 'object'">{{ value ?? '—' }}</dd>
                        <dd v-else><pre>{{ JSON.stringify(value, null, 2) }}</pre></dd>
                      </div>
                    </dl>
                    <p v-if="row.reviewer_name" class="review-text">
                      {{ row.reviewer_name }}于 {{ time(row.reviewed_at) }}审核
                      {{ row.weight_version_id ? ' · 权重版本 #' + row.weight_version_id : '' }}
                      {{ row.review_note ? ' · ' + row.review_note : '' }}
                    </p>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="指标 / 理由" min-width="215">
                <template #default="{ row }">
                  <strong>{{ row.metric_name }}</strong>
                  <div class="review-muted reason" :title="row.reason">{{ row.reason }}</div>
                </template>
              </el-table-column>
              <el-table-column label="生成时占比" align="right" width="100">
                <template #default="{ row }"><span class="weight-flow">{{ percent(row.current_share) }}</span></template>
              </el-table-column>
              <el-table-column label="AI 建议" align="right" width="86">
                <template #default="{ row }"><b class="weight-flow">{{ percent(row.suggested_share) }}</b></template>
              </el-table-column>
              <el-table-column label="变化" width="122">
                <template #default="{ row }"><span class="weight-delta" :class="direction(delta(row))">{{ deltaLabel(delta(row)) }}</span></template>
              </el-table-column>
              <el-table-column label="批准占比（%）" width="142">
                <template #default="{ row }">
                  <el-input-number v-if="row.review_status === 'pending' && row.assessment === 'business_importance' && !batch.expired"
                    v-model="edited[row.id]" :min="0" :max="100" :precision="0" :step="1" size="small"
                    :aria-label="row.metric_name + '的审核占比'" @change="invalidatePreview" />
                  <span v-else-if="row.approved_share != null">{{ percent(row.approved_share) }}</span>
                  <span v-else class="review-muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="判断 / 状态" min-width="125">
                <template #default="{ row }">
                  <div>{{ row.assessment === 'current_risk' ? '当前风险 · 仅参考' : '业务重要性' }}</div>
                  <small class="review-muted">{{ status(row) }}</small>
                </template>
              </el-table-column>
            </el-table>
            <div class="review-actions">
              <div class="review-action-context">
                <strong>审核操作</strong>
                <span class="review-muted">{{ reviewHint }}</span>
              </div>
              <div class="review-action-buttons">
                <el-button :disabled="!selection.length || loading" @click="reject">驳回所选</el-button>
                <el-button type="primary" :loading="loading" :disabled="!canPreview" @click="loadPreview">审核所选</el-button>
              </div>
            </div>

            <section v-if="preview" ref="previewPanel" class="weight-preview" aria-label="审核确认">
              <header>
                <div>
                  <strong>核对整组变化</strong>
                  <p>本组试算合计 100%，未选指标按比例分配剩余占比。</p>
                </div>
                <el-button type="primary" :loading="loading" @click="approve">确认批准并生效</el-button>
              </header>
              <div v-if="preview.impact" class="impact">
                <strong>小类健康度试算</strong>
                <span v-if="preview.impact.beforeScore != null && preview.impact.afterScore != null" class="weight-flow">
                  {{ preview.impact.beforeScore }} 分 → <b>{{ preview.impact.afterScore }} 分</b>
                </span>
                <span v-else>暂无可比结果</span>
                <small class="review-muted">同一批最近观测 · {{ preview.impact.samples }}/{{ preview.impact.totalMetrics }} 项有分数 · 仅计算基础权重</small>
              </div>
              <el-table :data="preview.changes" size="small">
                <el-table-column label="指标" min-width="180">
                  <template #default="{ row }">{{ row.metricName }} <small v-if="!row.selected" class="review-muted">联动</small></template>
                </el-table-column>
                <el-table-column label="当前 → 批准后" min-width="160">
                  <template #default="{ row }">
                    <span class="weight-flow">{{ row.beforePercent }}% → <b>{{ row.afterPercent }}%</b></span>
                    <div class="weight-track"><i :style="{ width: row.afterPercent + '%' }" /></div>
                  </template>
                </el-table-column>
                <el-table-column label="变化" min-width="110">
                  <template #default="{ row }"><span :class="direction(row.afterPercent - row.beforePercent)">
                    {{ deltaLabel(row.afterPercent - row.beforePercent) }}
                  </span></template>
                </el-table-column>
              </el-table>
            </section>
          </template>
        </article>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowRight, ArrowUp, Download, Refresh } from '@element-plus/icons-vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import request from '@/api'

defineProps({ modelValue: Boolean, categories: { type: Array, default: () => [] } })
defineEmits(['update:modelValue'])

const path = '/weight/recommendations'
const types = { expert_base: '专家基础权重', monthly_dynamic: '月度分析' }
const states = { pending: '待审核', applied: '已应用', rejected: '已驳回', expired: '已过期', failed: '生成失败' }
const filters = reactive({ type: '', category: null, state: '' })
const runs = ref([])
const cursor = ref(null)
const cursors = ref([])
const loadingRuns = ref(false)
const batch = ref(null)
const batchRevision = ref(0)
const analysisOpen = ref(false)
const selection = ref([])
const edited = ref({})
const preview = ref(null)
const previewPanel = ref(null)
const loading = ref(false)
const error = ref('')
const downloadingSkills = ref(false)
let operation = 0
let listRequest = 0

const canPreview = computed(() => selection.value.length && !batch.value?.expired &&
  selection.value.every(row => row.assessment === 'business_importance'))

const isAcceptance = row => row?.run_key?.startsWith('acceptance:')
const categoryName = row => row.category_name || ('大类 #' + row.category_id)
const subcategoryName = row => row.sub_category_name || '未分小类'
const reviewHint = computed(() => {
  if (batch.value?.expired) return '本批次已过期，仍可查看或驳回待审建议。'
  if (!selection.value.length) return '勾选待审核指标，可先编辑拟批准占比。'
  if (!canPreview.value) return '所选包含仅供参考的风险建议，取消勾选后可继续。'
  return '已选 ' + selection.value.length + ' 项 · 核对整组变化后确认生效'
})

function batchState(row) {
  if (row.run_status === 'failed') return { label: '生成失败', tone: 'danger' }
  if (row.expired ?? (row.expires_at && Date.parse(row.expires_at + '+08:00') <= Date.now())) {
    return { label: '已过期', tone: 'muted' }
  }
  if (Number(row.pending_count) > 0) return { label: row.pending_count + ' 条待审', tone: 'primary' }
  return { label: '审核结束', tone: 'muted' }
}
const time = value => typeof value === 'string' ? value.replace('T', ' ').slice(0, 19) : '—'
const points = value => Math.round(Number(value) * 100)
const percent = value => points(value) + '%'
const delta = row => points(row.suggested_share) - points(row.current_share)
const deltaLabel = value => value === 0 ? '持平' : (value > 0 ? '+' : '−') + Math.abs(value) + ' 个百分点'
const direction = value => value === 0 ? 'review-muted' : value > 0 ? 'weight-up' : 'weight-down'
const status = row => states[row.review_status === 'pending' && batch.value?.expired ? 'expired' : row.review_status]

function evidenceFields(value) {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? Object.entries(parsed) : [['记录', parsed]]
  } catch {
    return [['记录', value]]
  }
}

async function perform(action) {
  const current = ++operation
  loading.value = true
  error.value = ''
  try { await action(() => current === operation) }
  catch (err) {
    if (current === operation) error.value = err.response?.data?.message || err.message
  } finally {
    if (current === operation) loading.value = false
  }
}

async function downloadSkills() {
  downloadingSkills.value = true
  try {
    const { data } = await request.get(path + '/skills', { responseType: 'blob', silentError: true })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = '指标平台权重技能.zip'
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    ElMessage.error('Skills 下载失败，请刷新后重试')
  } finally {
    downloadingSkills.value = false
  }
}

async function fetchRuns(before, active) {
  const params = { type: filters.type || undefined, category: filters.category, state: filters.state || undefined, before }
  const { data } = await request.get(path, { params, silentError: true })
  if (!active()) return false
  runs.value = data
  cursor.value = before
  if (!data.some(row => row.id === batch.value?.id)) {
    batch.value = null
    if (data.length) await fetchBatch(data[0].id, active)
  }
  return active()
}

function loadRuns(before, history) {
  const requestId = ++listRequest
  loadingRuns.value = true
  return perform(async active => {
    if (await fetchRuns(before, active)) cursors.value = history
  }).finally(() => {
    if (requestId === listRequest) loadingRuns.value = false
  })
}

const reload = () => loadRuns(null, [])
const nextPage = () => loadRuns(runs.value.at(-1).id, [...cursors.value, cursor.value])
const previousPage = () => loadRuns(cursors.value.at(-1), cursors.value.slice(0, -1))
onMounted(reload)

async function fetchBatch(id, active) {
  const { data } = await request.get(path + '/' + id, { silentError: true })
  if (!active()) return
  batch.value = data
  analysisOpen.value = false
  batchRevision.value++
  edited.value = Object.fromEntries(data.items.map(row => [row.id, points(row.suggested_share)]))
  selection.value = []
  invalidatePreview()
}

function openBatch(id) {
  if (loading.value || id === batch.value?.id) return
  return perform(active => fetchBatch(id, active))
}

function invalidatePreview() {
  preview.value = null
}

function onSelection(rows) {
  selection.value = rows
  invalidatePreview()
}

function reviewBody() {
  return { selections: selection.value.map(row => ({ id: row.id, approvedPercent: edited.value[row.id] })) }
}

function loadPreview() {
  const body = reviewBody()
  return perform(async active => {
    const { data } = await request.post(path + '/' + batch.value.id + '/preview', body, { silentError: true })
    if (!active() || JSON.stringify(reviewBody()) !== JSON.stringify(body)) return
    preview.value = data
    await nextTick()
    if (active()) previewPanel.value?.scrollIntoView({ block: 'start' })
  })
}

async function approve() {
  if (!preview.value || loading.value) return
  const id = batch.value.id
  const body = { ...reviewBody(), previewToken: preview.value.token }
  return perform(async active => {
    const { data } = await request.post(path + '/' + id + '/approve', body, { silentError: true })
    ElMessage.success('已应用 · 权重版本 #' + data)
    await fetchBatch(id, active)
    if (active()) await fetchRuns(cursor.value, active)
  })
}

async function reject() {
  const id = batch.value.id
  const body = reviewBody()
  let note
  try {
    const result = await ElMessageBox.prompt('请输入驳回理由。所选建议将结束审核。', '驳回权重建议', {
      confirmButtonText: '确认驳回', cancelButtonText: '返回', inputType: 'textarea',
      inputValidator: value => value?.trim() && value.trim().length <= 500 ? true : '请填写 1 至 500 字'
    })
    note = result.value.trim()
  } catch { return }
  return perform(async active => {
    await request.post(path + '/' + id + '/reject', { ...body, note }, { silentError: true })
    ElMessage.success('已驳回所选建议')
    await fetchBatch(id, active)
    if (active()) await fetchRuns(cursor.value, active)
  })
}
</script>

<style scoped>
.review-workspace {
  color: var(--el-text-color-primary);
  font-size: 13px;
}
.review-toolbar, .review-toolbar-actions, .review-filters, .review-actions {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
}
.review-toolbar {
  justify-content: space-between; padding: 12px; margin-bottom: 16px;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 12px;
}
.review-toolbar-actions .el-button, .review-actions .el-button { margin-left: 0; }
.review-filters .el-select { width: min(40vw, 150px); }
.review-layout { display: grid; grid-template-columns: minmax(218px, 250px) minmax(0, 1fr); align-items: start; gap: 16px; }
.review-inbox, .review-detail {
  min-width: 0; border: 1px solid var(--el-border-color-light); background: var(--el-bg-color); border-radius: 12px;
}
.inbox-header, .metric-section-title, .analysis-top, .batch-header, .weight-preview header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.inbox-header { padding: 13px 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.inbox-header h2, .analysis-top h3, .metric-section-title h3 { margin: 0; }
.inbox-header h2, .analysis-top h3, .metric-section-title h3 { font-size: 15px; }
.inbox-header span, .metric-section-title span { color: var(--el-text-color-secondary); }
.inbox-batches { display: grid; gap: 8px; max-height: 60vh; overflow: auto; padding: 10px; }
.batch-link {
  display: grid; gap: 5px; width: 100%; padding: 9px 10px;
  border: 1px solid var(--el-border-color-light); border-radius: 8px; background: none; color: inherit;
  text-align: left; font: inherit; cursor: pointer;
}
.batch-link:hover { background: var(--el-fill-color-light); }
.batch-link:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.batch-link.is-selected {
  border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9);
}
.batch-path { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; font-size: 13px; }
.batch-path .el-icon { flex-shrink: 0; color: var(--el-text-color-secondary); }
.batch-link-heading, .batch-link-meta { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 4px; }
.batch-link-heading, .batch-link-meta time { font-size: 11px; color: var(--el-text-color-secondary); font-variant-numeric: tabular-nums; }
.batch-acceptance { color: var(--el-color-warning-dark-2); }
.inbox-pagination { display: flex; justify-content: space-between; padding: 10px; border-top: 1px solid var(--el-border-color-lighter); }
.review-detail { padding: 16px; }
.batch-header { flex-wrap: wrap; margin-bottom: 14px; }
.batch-context, .batch-badges { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.batch-context div { display: grid; gap: 3px; }
.batch-context small, .analysis-kicker { color: var(--el-text-color-secondary); font-size: 11px; }
.batch-context strong { font-size: 18px; font-weight: 650; line-height: 1.3; }
.batch-context .el-icon { color: var(--el-text-color-secondary); }
.analysis-card {
  margin-bottom: 18px; padding: 14px 16px; border: 1px solid var(--el-border-color-light);
  border-radius: 10px; background: var(--el-fill-color-lighter);
}
.analysis-top h3 { margin-top: 4px; }
.analysis-summary, .review-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.65; }
.analysis-summary { margin: 10px 0; }
.review-meta { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 10px 0 0; }
.review-meta > div { display: grid; gap: 3px; }
.review-meta dt, .evidence-fields dt { color: var(--el-text-color-secondary); font-size: 11px; }
.review-meta dd, .evidence-fields dd { margin: 0; overflow-wrap: anywhere; }
.analysis-body {
  margin-top: 12px; padding: 12px 14px; border-left: 3px solid var(--el-color-primary-light-5);
  background: var(--el-bg-color); border-radius: 5px;
}
.analysis-body p { margin: 5px 0 0; }
.review-alert { margin-bottom: 12px; }
.metric-section-title { margin-bottom: 9px; }
.review-actions {
  justify-content: space-between; margin-top: 14px; padding: 10px 12px;
  border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-lighter);
}
.review-action-context { display: grid; gap: 3px; }
.review-action-buttons { display: flex; flex-wrap: wrap; gap: 8px; }
.review-muted { color: var(--el-text-color-secondary); }
.reason { white-space: nowrap; text-overflow: ellipsis; overflow: hidden; font-size: 12px; }
.weight-flow { font-variant-numeric: tabular-nums; }
.weight-delta { display: inline-block; border-radius: 6px; padding: 1px 6px; font-size: 12px; font-weight: 600; }
.weight-up { color: var(--el-color-success-dark-2); background: var(--el-color-success-light-9); }
.weight-down { color: var(--el-color-danger-dark-2); background: var(--el-color-danger-light-9); }
.weight-preview { margin-top: 16px; border: 1px solid var(--el-border-color); border-radius: 8px; overflow: hidden; }
.weight-preview header, .review-evidence { background: var(--el-fill-color-lighter); padding: 12px 16px; }
.weight-preview header p { margin: 5px 0 0; color: var(--el-text-color-secondary); }
.evidence-fields { display: grid; gap: 8px; margin: 6px 0; }
.evidence-fields > div { display: grid; grid-template-columns: minmax(85px, 140px) minmax(0, 1fr); gap: 8px; }
.evidence-fields pre { max-height: 14rem; margin: 0; overflow: auto; white-space: pre-wrap; }
.impact { display: flex; flex-wrap: wrap; align-items: baseline; gap: 12px; padding: 12px 16px; }
.weight-track { margin-top: 4px; height: 4px; border-radius: 4px; background: var(--el-fill-color-dark); }
.weight-track i { display: block; height: 100%; border-radius: inherit; background: var(--el-color-primary); }
@media (max-width: 1024px) {
  .review-layout { grid-template-columns: minmax(0, 1fr); }
  .inbox-batches { max-height: 220px; }
}
</style>
