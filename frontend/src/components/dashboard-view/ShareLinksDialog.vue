<template>
  <el-dialog
    :model-value="modelValue"
    title="分享链接"
    width="min(880px, 94vw)"
    top="8vh"
    class="workspace-theme dashboard-workspace share-dialog"
    @update:model-value="value => emit('update:modelValue', value)"
  >
    <div class="share-head">
      <div>
        <b>{{ view?.viewName || '专屏' }}</b>
        <small>只读分享 · 访客不需要平台账号</small>
        <small class="share-kinds">移动端 = 精简 H5 页面；PC = 与平台内专屏完全一致的完整页面</small>
      </div>
      <div class="share-create">
        <el-radio-group v-model="shareTarget" size="small" aria-label="链接版本">
          <el-radio-button value="mobile">移动端</el-radio-button>
          <el-radio-button value="pc">PC</el-radio-button>
        </el-radio-group>
        <el-select v-model="expiresDays" aria-label="链接有效期">
          <el-option label="1 天" :value="1" />
          <el-option label="7 天" :value="7" />
          <el-option label="30 天" :value="30" />
          <el-option label="永久有效" value="permanent" />
        </el-select>
        <el-button type="primary" :loading="creating" @click="create">生成{{ shareTarget === 'pc' ? ' PC ' : '移动端' }}链接</el-button>
      </div>
    </div>

    <div class="base-row">
      <span class="base-label">对外访问地址</span>
      <el-input v-model="baseUrl" :disabled="baseFromEnv" :placeholder="origin" size="small" />
      <el-button size="small" :loading="savingBase" :disabled="baseFromEnv" @click="saveBase">保存</el-button>
      <small class="base-hint">{{ baseFromEnv ? '已由环境变量 SCREEN_SHARE_FILE_BASE_URL 覆盖' : '写入鉴权文件的链接用它拼；留空则用当前访问地址' }}</small>
    </div>

    <el-alert
      title="分享页只读，不提供告警处理、工单编辑等写入操作；链接生成后可在下方列表随时再次复制，撤销或过期后立即失效。"
      type="info"
      :closable="false"
    />

    <div v-if="created" class="created-link">
      <div class="created-head"><b>{{ created.target === 'pc' ? '新 PC 链接已生成' : '新移动端链接已生成' }}</b><small>已记入下方列表，之后可随时复制，不用再担心漏复制。</small></div>
      <div class="created-row">
        <el-input :model-value="fullUrl" readonly />
        <el-button @click="copy()">复制链接</el-button>
      </div>
      <small>{{ created.permanent ? '永久有效 · 可手动撤销' : '有效期至 ' + time(created.expiresAt) }}</small>
      <small v-if="created.target === 'pc'" class="pc-hint">PC 链接打开即是完整 PC 专屏（与平台内页面一致）；按要求不写入鉴权文件。</small>
      <el-alert v-if="created.fileWarning" :title="created.fileWarning" type="warning" :closable="false" show-icon class="file-warning" />
    </div>

    <div class="list-head"><b>已生成的链接</b><span>共 {{ links.length }} 条 · 有效链接可点击「复制」取回完整地址</span></div>
    <el-table v-loading="loading" :data="links" border size="small" max-height="340" empty-text="还没有分享链接">
      <el-table-column label="状态" width="72">
        <template #default="{ row }"><el-tag size="small" :type="Number(row.status) === 1 && !expired(row) ? 'success' : 'info'">{{ Number(row.status) === 1 && !expired(row) ? '有效' : '已失效' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="创建时间" width="168">
        <template #default="{ row }">
          <div>{{ time(row.createTime) }}</div>
          <span
            class="link-id"
            :class="{ clickable: row.recoverable }"
            :title="row.recoverable ? '点击查看并复制完整链接' : '该链接无法还原，请重新生成'"
            @click="row.recoverable && openCopy(row.shareUrl)"
          >{{ row.publicId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建人" width="90"><template #default="{ row }">{{ row.createdByName || '-' }}</template></el-table-column>
      <el-table-column label="有效期至" width="150"><template #default="{ row }">{{ row.permanent ? '永久有效' : time(row.expiresAt) }}</template></el-table-column>
      <el-table-column label="访问" width="140">
        <template #default="{ row }">
          <div>{{ row.eventCount || 0 }} 次</div>
          <small class="cell-muted">{{ row.lastUsedAt ? time(row.lastUsedAt) : '尚未访问' }}</small>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="196" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.recoverable" link type="primary" @click="copy(row.shareUrl)">复制移动端</el-button>
          <el-button v-if="row.recoverable && row.pcShareUrl" link type="primary" @click="copy(row.pcShareUrl)">PC</el-button>
          <el-button link type="danger" :disabled="Number(row.status) !== 1" @click="revoke(row)">撤销</el-button>
          <el-tooltip v-if="!row.recoverable" content="该链接生成于本次升级前，只保存了密钥哈希，无法还原；请重新生成一条" placement="top">
            <span class="cell-muted">不可恢复</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="copyDialog"
      title="复制分享链接"
      width="min(560px, 92vw)"
      class="workspace-theme dashboard-workspace"
      append-to-body
      @opened="selectAll"
    >
      <el-input ref="copyInput" v-model="copyText" type="textarea" :rows="3" readonly resize="none" />
      <p class="copy-tip">
        浏览器禁止自动复制时，点「全选」后按 <b>Ctrl + C</b>（macOS 为 ⌘ + C）手动复制。
      </p>
      <template #footer>
        <el-button @click="selectAll">全选</el-button>
        <el-button type="primary" @click="retryCopy">一键复制</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { shareApi, message } from './api'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  view: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue'])
const expiresDays = ref(7), creating = ref(false), loading = ref(false), links = ref([]), created = ref(null)
// 链接版本：mobile=现有 H5 精简页，pc=完整 PC 专屏页（PC 链接不写鉴权文件）
const shareTarget = ref('mobile')
const baseUrl = ref(''), savingBase = ref(false), fileConfig = ref({})
const origin = location.origin
const baseFromEnv = computed(() => fileConfig.value.baseUrlSource === 'env')
const absolute = value => !value ? '' : (/^https?:\/\//i.test(value) ? value : origin + value)
const fullUrl = computed(() => created.value ? absolute(created.value.shareUrl || created.value.url) : '')

async function loadFileConfig () {
  try {
    fileConfig.value = await shareApi.fileConfig() || {}
    baseUrl.value = fileConfig.value.baseUrl || ''
  } catch (error) { /* 未配置属正常情况，生成链接时后端会自动记录当前访问地址 */ }
}
async function saveBase () {
  savingBase.value = true
  try {
    fileConfig.value = await shareApi.saveFileConfig({ baseUrl: baseUrl.value })
    baseUrl.value = fileConfig.value.baseUrl || ''
    ElMessage.success('对外访问地址已保存')
  } catch (error) { ElMessage.error(message(error)) } finally { savingBase.value = false }
}

const time = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'
function expired (row) { return !row.permanent && row.expiresAt && new Date(String(row.expiresAt).replace(' ', 'T')) < new Date() }

async function load () {
  if (!props.view?.id) return
  loading.value = true
  try { links.value = await shareApi.list(props.view.id) || [] } catch (error) { ElMessage.error(message(error)) } finally { loading.value = false }
}
async function create () {
  if (!props.view?.id) return
  creating.value = true
  try {
    const permanent = expiresDays.value === 'permanent'
    const duration = permanent ? { permanent } : { expiresDays: expiresDays.value }
    created.value = await shareApi.create({ viewId: props.view.id, ...duration, baseUrl: location.origin, target: shareTarget.value })
    ElMessage.success(shareTarget.value === 'pc' ? 'PC 分享链接已生成' : '移动端分享链接已生成')
    await load()
  } catch (error) { ElMessage.error(message(error)) } finally { creating.value = false }
}
const copyDialog = ref(false), copyText = ref(''), copyInput = ref(null)

function selectAll () {
  const area = copyInput.value?.textarea
  if (!area) return
  area.focus()
  area.select()
}
/** 显式弹窗：无论浏览器是否支持自动复制，都能看到完整链接并手动选取。 */
function openCopy (value) {
  const text = absolute(value)
  if (!text) return
  copyText.value = text
  copyDialog.value = true
}
/** 老浏览器（内网 IE 内核、非 HTTPS 的精简浏览器）没有 clipboard API，退回 execCommand。 */
function legacyCopy (text) {
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.focus()
    area.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(area)
    return copied
  } catch { return false }
}
async function writeClipboard (text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch { /* 权限被拒或非安全上下文，继续尝试旧接口 */ }
  return legacyCopy(text)
}
async function copy (value) {
  const text = typeof value === 'string' ? absolute(value) : fullUrl.value
  if (!text) return
  if (await writeClipboard(text)) { ElMessage.success('链接已复制'); return }
  openCopy(text)
  ElMessage.warning('当前浏览器不支持自动复制，请在弹窗中点「全选」后手动复制')
}
async function retryCopy () {
  if (await writeClipboard(copyText.value)) {
    ElMessage.success('链接已复制')
    copyDialog.value = false
    return
  }
  selectAll()
  ElMessage.warning('自动复制不可用，链接已全选，请按 Ctrl + C 手动复制')
}
async function revoke (row) {
  try {
    await ElMessageBox.confirm('撤销后该链接立即失效，已发送的链接将无法访问。确认撤销？', '撤销分享链接')
    await shareApi.revoke(row.id)
    ElMessage.success('链接已撤销')
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(message(error)) }
}
watch(() => props.modelValue, open => {
  if (!open) return
  created.value = null
  expiresDays.value = 7
  shareTarget.value = 'mobile'
  load()
  loadFileConfig()
}, { immediate: true })
</script>

<style scoped>
.share-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.share-head b { display: block; color: var(--metric-text); font-size: 14px; }
.share-head small { display: block; margin-top: 4px; color: var(--metric-text-muted); font-size: 11px; }
.share-head .share-kinds { color: var(--metric-text-secondary); }
.pc-hint { display: block; margin-top: 4px; color: var(--metric-primary-hover); font-size: 10px; }
.share-create { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.share-create .el-select { width: 118px; }
.base-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed var(--metric-border); }
.base-label { flex: none; color: var(--metric-text-secondary); font-size: 12px; }
.base-row .el-input { max-width: 300px; }
.base-hint { color: var(--metric-text-muted); font-size: 10px; }
.created-link { margin: 12px 0; padding: 12px; border: 1px solid var(--metric-primary-border); border-radius: var(--metric-radius); background: var(--metric-primary-soft); }
.created-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.created-head b { color: var(--metric-primary-hover); font-size: 12px; }
.created-head small, .created-link > small { color: var(--metric-text-secondary); font-size: 10px; }
.created-row { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.file-warning { margin-top: 8px; }
.list-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin: 16px 0 8px; }
.list-head b { color: var(--metric-text); font-size: 12px; }
.list-head span { color: var(--metric-text-muted); font-size: 10px; }
.cell-muted { color: var(--metric-text-muted); font-size: 10px; }
.link-id { display: inline-block; max-width: 100%; overflow: hidden; color: var(--metric-text-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.link-id.clickable { cursor: pointer; }
.link-id.clickable:hover { color: var(--metric-primary-hover); text-decoration: underline; }
.copy-tip { margin: 10px 0 0; color: var(--metric-text-secondary); font-size: 12px; line-height: 1.6; }
.copy-tip b { color: var(--metric-text); }
@media (max-width: 680px) {
  .share-head { flex-direction: column; }
  .share-create { width: 100%; }
  .share-create .el-select { flex: 1; }
  .created-row { align-items: stretch; flex-direction: column; }
  .base-row { flex-wrap: wrap; }
  .base-row .el-input { max-width: none; flex: 1 1 100%; }
}
</style>
