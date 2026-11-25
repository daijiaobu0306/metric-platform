<template>
  <el-dialog
    :model-value="modelValue"
    title="分享鉴权文件"
    width="min(820px, 94vw)"
    top="8vh"
    class="workspace-theme dashboard-workspace screen-dialog"
    @update:model-value="value => emit('update:modelValue', value)"
  >
    <el-alert
      title="生成分享链接时写入一行：role 列是角色中文名（仅说明），user 列是真正的鉴权名单（角色成员 + 直接授权用户）。撤销或过期自动删行，成员/角色变动每分钟自动刷新。"
      type="info"
      :closable="false"
    />

    <el-form v-loading="loading" label-width="150px" class="file-form">
      <el-form-item label="写入文件">
        <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        <span class="field-hint">停用后不再写文件，已存在的行保持不变</span>
      </el-form-item>
      <el-form-item label="文件绝对路径">
        <el-input v-model="form.path" :disabled="pathFromEnv" placeholder="/home/appuser/4Alogin/screenUrl.txt" />
        <div class="field-hint">
          <template v-if="pathFromEnv">已被环境变量 <code>SCREEN_SHARE_FILE_PATH</code> 覆盖，页面修改不生效</template>
          <template v-else>Docker 部署填容器内路径（如 <code>/data/screen/screenUrl.txt</code>），启动时用 <code>-v /home/appuser/4Alogin:/data/screen</code> 挂载</template>
        </div>
      </el-form-item>
      <el-form-item label="对外访问地址">
        <el-input v-model="form.baseUrl" :disabled="baseUrlFromEnv" placeholder="https://211.17.117.144:18080" />
        <div class="field-hint">
          <template v-if="baseUrlFromEnv">已被环境变量 <code>SCREEN_SHARE_FILE_BASE_URL</code> 覆盖</template>
          <template v-else>写入文件的链接用此前缀；首次生成链接时会自动取当前访问地址，可在此改正</template>
        </div>
      </el-form-item>
    </el-form>

    <div class="preview-head">
      <b>文件内容预览</b>
      <span>{{ statusText }}</span>
    </div>
    <pre class="preview">{{ content || '（文件不存在或为空）' }}</pre>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button :loading="syncing" :disabled="!form.enabled" @click="sync">立即同步</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { shareApi, message } from './api'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const loading = ref(false), saving = ref(false), syncing = ref(false)
const form = ref({ enabled: false, path: '', baseUrl: '' })
const meta = ref({})
const content = ref('')
const pathFromEnv = computed(() => meta.value.pathSource === 'env')
const baseUrlFromEnv = computed(() => meta.value.baseUrlSource === 'env')
const statusText = computed(() => {
  if (!form.value.path) return '未配置路径'
  return meta.value.exists ? `共 ${meta.value.lineCount ?? 0} 条链接` : '文件尚未创建'
})

function apply (data) {
  meta.value = data || {}
  content.value = data?.content || ''
  form.value = {
    enabled: Boolean(data?.enabled),
    path: data?.path || '',
    baseUrl: data?.baseUrl || ''
  }
}
async function load () {
  loading.value = true
  try { apply(await shareApi.fileConfig()) } catch (error) { ElMessage.error(message(error)) } finally { loading.value = false }
}
async function save () {
  saving.value = true
  try {
    apply(await shareApi.saveFileConfig({ ...form.value }))
    ElMessage.success('鉴权文件配置已保存')
  } catch (error) { ElMessage.error(message(error)) } finally { saving.value = false }
}
async function sync () {
  syncing.value = true
  try {
    apply(await shareApi.fileSync())
    ElMessage.success('已清理失效链接并刷新预览')
  } catch (error) { ElMessage.error(message(error)) } finally { syncing.value = false }
}
watch(() => props.modelValue, open => { if (open) load() }, { immediate: true })
</script>

<style scoped>
.file-form { margin-top: 16px; }
.field-hint { margin-top: 4px; color: var(--metric-text-muted); font-size: 11px; line-height: 1.6; }
.field-hint code { padding: 1px 4px; border-radius: 4px; background: #f1f5f9; font-family: var(--metric-font-mono); }
.preview-head { display: flex; align-items: baseline; justify-content: space-between; margin: 4px 0 8px; }
.preview-head b { color: var(--metric-text); font-size: 12px; }
.preview-head span { color: var(--metric-text-muted); font-size: 11px; }
.preview { max-height: 230px; padding: 12px; margin: 0; overflow: auto; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: #fbfdff; color: var(--metric-text-secondary); font-family: var(--metric-font-mono); font-size: 11px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; }
@media (max-width: 680px) { .el-form-item { margin-bottom: 16px; } }
</style>
