<template>
  <el-dialog class="workspace-theme" v-model="visible" title="Excel 批量设置告警策略" width="1080px" :style="{ maxWidth: 'calc(100vw - 32px)' }"
             append-to-body :close-on-click-modal="false" :close-on-press-escape="!executing && !previewing" :show-close="!executing && !previewing" @closed="reset">
    <div class="batch-guide">
      <div><span class="step">1</span><strong>导出模板</strong><p>所有系统、大类、小类和指标都会列进去，已经配好的阈值会带出来。</p></div>
      <div><span class="step">2</span><strong>在 Excel 里填阈值</strong><p>要改哪一档，就在对应 sheet 填比较条件和阈值。空着不填的保持原样。</p></div>
      <div><span class="step">3</span><strong>校验后保存</strong><p>先点“校验并预览”，确认没问题再保存。只会写入填了阈值的行。</p></div>
    </div>
    <el-alert title="连续次数怎么填：每小时更新的指标填 3，表示连续 3 份新数据都异常才告警；重复扫描同一份数据不累加。" type="info" :closable="false" show-icon style="margin-bottom: 14px;" />
    <div class="batch-tools">
      <el-button type="primary" plain :loading="exporting" :disabled="executing" @click="downloadTemplate">导出全量配置模板</el-button>
      <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx" :on-change="selectFile" :disabled="executing || previewing">
        <el-button :disabled="executing || previewing">选择已填写的 Excel</el-button>
      </el-upload>
      <span class="file-name" :title="file?.name">{{ file?.name || '未选择文件' }}</span>
      <el-button type="primary" :loading="previewing" :disabled="!file || executing" @click="previewFile">校验并预览</el-button>
    </div>
    <el-alert type="info" :closable="false" show-icon>
      <template #title>只改浅黄色列。阈值可以填 0，最多四位小数。</template>
      <div>六个 sheet 分别是告警/工单的“一般、重要、严重”三档；没填阈值的行会跳过，已有配置不会被删除。<br>
        同一策略出现在多个 sheet 时，策略名称和状态要填一致；规则开关管整条策略的这一类规则。<br>
        原始数据策略用“原始数据策略”sheet：序号 0 是默认规则，1～30 是个性化规则。新增一条策略请清空策略ID，并换一个新的配置标识。</div>
    </el-alert>
    <template v-if="preview">
      <div class="batch-stats">
        <span>新增策略 <b>{{ preview.newStrategies }}</b></span><span>修改策略 <b>{{ preview.updatedStrategies }}</b></span>
        <span>有效规则行 <b>{{ preview.configuredRows }}</b></span><span>空阈值跳过 <b>{{ preview.skippedRows }}</b></span>
        <span :class="{ 'has-errors': preview.errorCount }">错误 <b>{{ preview.errorCount }}</b></span>
      </div>
      <el-alert v-if="preview.errorCount" type="error" :closable="false" title="文件存在错误，整批不会写入。请修改后重新选择文件并校验。最多展示前200条错误。" />
      <el-table v-if="preview.errorCount" :data="preview.errors" border size="small" max-height="220" class="preview-table">
        <el-table-column prop="sheet" label="Sheet" width="130" /><el-table-column prop="row" label="Excel行号" width="100" />
        <el-table-column prop="message" label="具体问题" min-width="480" />
      </el-table>
      <el-alert v-else-if="!preview.token" type="success" :closable="false" title="没有实际变更，无需导入。已有配置与文件一致，或文件没有填写阈值。" />
      <el-alert v-else type="warning" :closable="false" title="预览有效期15分钟。执行时若对象或策略已被他人修改，将拒绝导入，请重新校验。" />
      <el-table :data="preview.rows" border size="small" max-height="320" class="preview-table">
        <el-table-column prop="sheet" label="Sheet" width="120" /><el-table-column prop="row" label="行号" width="65" />
        <el-table-column prop="target" label="对象" min-width="140" /><el-table-column prop="strategyName" label="策略名称" min-width="180" />
        <el-table-column prop="action" label="操作" width="100" /><el-table-column prop="before" label="原配置" min-width="220" />
        <el-table-column prop="after" label="导入配置" min-width="220" />
      </el-table>
      <div class="preview-note">仅展示有实际变更的策略对应行（最多1000行）；实际保存包含全部有效变更。相同规则重复导入不会新增重复策略。</div>
    </template>
    <template #footer>
      <el-button :disabled="executing || previewing" @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="executing" :disabled="!preview?.token || previewing || preview?.errorCount > 0" @click="executeImport">
        确认批量保存{{ preview?.token ? `（${preview.newStrategies + preview.updatedStrategies} 条策略）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { configApi } from '../../api'
const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const file = ref(null)
const preview = ref(null)
const exporting = ref(false)
const previewing = ref(false)
const executing = ref(false)
function reset () { file.value = null; preview.value = null }
function selectFile (upload) {
  preview.value = null; file.value = null
  if (!upload.raw?.name.toLowerCase().endsWith('.xlsx')) { ElMessage.warning('请选择xlsx文件'); return }
  if (upload.raw.size > 20 * 1024 * 1024) { ElMessage.warning('文件不能超过20MB'); return }
  file.value = upload.raw; preview.value = null
}
async function downloadTemplate () {
  if (exporting.value) return
  exporting.value = true
  try {
    const response = await configApi.strategyExcelExport()
    if (response.data.type?.includes('json')) throw new Error('导出失败，请稍后重试')
    const url = URL.createObjectURL(response.data)
    const link = document.createElement('a')
    link.href = url; link.download = `告警策略全量模板_${new Date().toLocaleDateString('sv-SE')}.xlsx`
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) { ElMessage.error(error.message || '导出失败') }
  finally { exporting.value = false }
}
async function previewFile () {
  if (!file.value) return
  previewing.value = true; preview.value = null
  try {
    const data = new FormData(); data.append('file', file.value)
    preview.value = (await configApi.strategyExcelPreview(data)).data
  } catch (error) { ElMessage.error(error.message || '校验失败') }
  finally { previewing.value = false }
}
async function executeImport () {
  if (!preview.value?.token || executing.value) return
  executing.value = true
  try {
    const response = await configApi.strategyExcelExecute({ token: preview.value.token })
    ElMessage.success(`批量保存成功：新增 ${response.data.newStrategies} 条，修改 ${response.data.updatedStrategies} 条策略`)
    emit('saved'); visible.value = false
  } catch (error) { preview.value = null; ElMessage.error(error.message || '导入失败，请重新校验') }
  finally { executing.value = false }
}
defineExpose({ downloadTemplate })
</script>

<style scoped>
.batch-guide { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 20px; }
.batch-guide > div { background: var(--ui-page); border: 1px solid var(--ui-border-soft); padding: 14px; border-radius: 8px; }
.step { display: inline-flex; justify-content: center; align-items: center; width: 22px; height: 22px; border-radius: 50%; background: var(--ui-primary-soft); color: var(--ui-primary); margin-right: 8px; }
.batch-guide strong { color: var(--ui-heading); font-size: 14px; }
.batch-guide p { margin: 10px 0 0; color: var(--ui-text); font-size: 12px; line-height: 1.7; }
.batch-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.file-name { flex: 1; min-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--ui-text); }
.batch-stats { display: flex; gap: 20px; flex-wrap: wrap; margin: 18px 0 12px; color: var(--ui-text); font-size: 13px; }
.batch-stats b { margin-left: 6px; color: var(--ui-heading); }
.has-errors, .has-errors b { color: var(--ui-danger); }
.preview-table { margin-top: 12px; }
.preview-note { margin-top: 8px; color: var(--ui-muted); font-size: 12px; }
@media (max-width: 720px) { .batch-guide { grid-template-columns: 1fr; gap: 8px; } }
</style>
