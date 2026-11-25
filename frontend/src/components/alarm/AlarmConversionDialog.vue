<template>
  <el-dialog class="workspace-theme" v-model="visible" :title="`告警转${label}`" width="760px" :style="{ maxWidth: 'calc(100vw - 32px)' }"
             append-to-body destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
             @closed="requestVersion++">
    <div v-loading="loading" class="conversion-body">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="preview">
        <section class="conversion-summary">
          <div class="conversion-kicker">{{ scope?.alarmId ? '单次告警' : '整组告警 · 当前筛选范围' }}</div>
          <h3>{{ preview.objectName || '告警对象' }}</h3>
          <p>共 {{ preview.totalCount }} 次记录，<strong>{{ preview.eligibleCount }} 次</strong>可合并创建一条{{ label }}。其余已转换或工单记录会跳过。</p>
        </section>
        <div v-if="preview.related.length" class="related-records">
          <span>已有关联{{ label }}</span>
          <el-button v-for="item in preview.related" :key="item.id" link type="primary" @click="$emit(kind === 'issue' ? 'open-issue' : 'open-gongdan', item.id)">{{ item.title }}</el-button>
        </div>
        <el-form v-if="preview.eligibleCount" :model="form" label-position="top" class="conversion-form" :disabled="saving">
          <template v-if="kind === 'issue'">
            <div class="form-columns">
              <el-form-item label="问题类型" required><el-select v-model="form.issueType"><el-option v-for="value in ['故障', '缺陷', '变更']" :key="value" :label="value" :value="value" /></el-select></el-form-item>
              <el-form-item label="问题属性" required><el-select v-model="form.issueAttribute"><el-option label="新问题" value="新问题" /><el-option label="老问题" value="老问题" /></el-select></el-form-item>
            </div>
            <el-form-item label="问题描述" required><el-input v-model="form.issueDescription" type="textarea" :rows="5" maxlength="15000" /></el-form-item>
            <p class="conversion-note">发现者：{{ preview.discoverer }}（当前登录用户）</p>
            <el-form-item label="责任人"><el-input v-model="form.responsiblePerson" maxlength="50" placeholder="可稍后在问题管理中补充" /></el-form-item>
            <el-form-item label="核查原因"><el-input v-model="form.checkReason" type="textarea" :rows="2" maxlength="10000" /></el-form-item>
          </template>
          <template v-else>
            <el-form-item label="工单标题" required><el-input v-model="form.gongdanTitle" maxlength="200" show-word-limit /></el-form-item>
            <el-form-item label="事件描述" required><el-input v-model="form.gongdanContent" type="textarea" :rows="5" maxlength="15000" /></el-form-item>
            <div class="form-columns three-columns">
              <el-form-item label="告警级别" required><el-select v-model="form.alarmLevel" placeholder="历史记录未定级，请选择"><el-option v-for="level in ruleLevels" :key="level.value" :label="level.label" :value="level.value" /></el-select></el-form-item>
              <el-form-item label="优先级"><el-select v-model="form.alarmPriority" clearable placeholder="按级别推导"><el-option v-for="value in ['低', '中', '高']" :key="value" :label="value" :value="value" /></el-select></el-form-item>
              <el-form-item label="影响度"><el-select v-model="form.alarmImpact" clearable placeholder="按级别推导"><el-option v-for="value in ['一般', '重大', '严重', '无']" :key="value" :label="value" :value="value" /></el-select></el-form-item>
            </div>
            <el-collapse><el-collapse-item title="责任与处理（选填，留空沿用工单平台配置）" name="responsibility">
              <div class="form-columns">
                <el-form-item label="主要责任人"><el-input v-model="form.responsiblePerson" maxlength="100" /></el-form-item>
                <el-form-item label="次要责任人"><el-input v-model="form.secondResponsible" maxlength="100" /></el-form-item>
              </div>
              <el-form-item label="BOMC处理人"><el-input v-model="form.executeUser" maxlength="50" placeholder="填写 BOMC 系统人员 ID" /></el-form-item>
            </el-collapse-item></el-collapse>
            <p class="conversion-note">次数、首末发生时间和所属对象由来源告警自动带入。保存后按现有流程推送 BOMC，推送失败可在工单管理中查看和重试。</p>
          </template>
        </el-form>
        <el-empty v-else description="本次没有可新建的记录，可查看上方已关联记录" :image-size="70" />
      </template>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">{{ preview?.eligibleCount ? '取消' : '关闭' }}</el-button>
      <el-button v-if="preview?.eligibleCount" type="primary" :loading="saving" :disabled="loading" @click="save">创建{{ label }}并关联 {{ preview.eligibleCount }} 次告警</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { alarmApi } from '../../api'
import { ruleLevels } from '../../utils/strategyRules'
const emit = defineEmits(['converted', 'open-issue', 'open-gongdan'])
const visible = ref(false), loading = ref(false), saving = ref(false), error = ref(''), kind = ref('issue')
const scope = ref(null), preview = ref(null), form = ref({})
const label = computed(() => kind.value === 'issue' ? '问题' : '工单')
let requestVersion = 0
async function open (selection, type) {
  scope.value = { ...selection }; kind.value = type; preview.value = null; error.value = ''; form.value = {}; visible.value = true
  const version = ++requestVersion
  loading.value = true
  try {
    const res = await alarmApi.conversionPreview({ ...scope.value, kind: type })
    if (version !== requestVersion) return
    preview.value = res.data
    form.value = type === 'issue'
      ? { issueType: '故障', issueAttribute: '新问题', issueDescription: res.data.description, responsiblePerson: '', checkReason: '' }
      : { gongdanTitle: res.data.title, gongdanContent: res.data.description, alarmLevel: res.data.alarmLevel, alarmPriority: '', alarmImpact: '', responsiblePerson: '', secondResponsible: '', executeUser: '' }
  } catch (exception) { if (version === requestVersion) error.value = exception.message || '读取转换信息失败' }
  finally { if (version === requestVersion) loading.value = false }
}
async function save () {
  if (saving.value) return
  if (kind.value === 'issue' && !form.value.issueDescription?.trim()) return ElMessage.warning('请填写问题描述')
  if (kind.value === 'gongdan') {
    if (!form.value.gongdanTitle?.trim() || !form.value.gongdanContent?.trim()) return ElMessage.warning('请填写工单标题和事件描述')
    if (!form.value.alarmLevel) return ElMessage.warning('请选择告警级别')
  }
  saving.value = true; error.value = ''
  try {
    const res = await alarmApi.convert({ ...scope.value, kind: kind.value, [kind.value === 'issue' ? 'issue' : 'gongdan']: { ...form.value } })
    const result = res.data
    visible.value = false
    if (kind.value === 'gongdan' && !result.pushSuccess) ElMessage.warning({ message: '本地工单已创建并关联告警；BOMC 推送未成功，可在工单管理查看和重试。', duration: 7000 })
    else ElMessage.success(`已创建${label.value}，关联 ${result.affectedCount} 次告警${result.skippedCount ? `，跳过 ${result.skippedCount} 次` : ''}`)
    emit('converted', result)
  } catch (exception) { error.value = exception.message || '转换失败，请重试' }
  finally { saving.value = false }
}
defineExpose({ open })
</script>
<style scoped>
.conversion-body { min-height: 100px; }.conversion-summary { background: #f6f9fd; border: 1px solid #e4ecf7; padding: 16px 20px; border-radius: 8px; margin-bottom: 18px; }
.conversion-kicker { color: var(--ui-primary); font-size: 12px; }.conversion-summary h3 { margin: 8px 0; font-size: 16px; color: var(--ui-heading); line-height: 1.6; }.conversion-summary p { margin: 0; color: var(--ui-text); font-size: 13px; line-height: 1.8; }.conversion-summary strong { color: var(--ui-primary); }
.related-records { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 0 0 18px; max-height: 130px; overflow: auto; }.related-records > span { color: var(--ui-muted); font-size: 12px; }
.form-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }.three-columns { grid-template-columns: 1fr 1fr 1fr; }.conversion-form :deep(.el-select) { width: 100%; }.conversion-note { color: var(--ui-muted); font-size: 12px; line-height: 1.8; margin: 14px 0 0; }
@media (max-width: 600px) { .form-columns { grid-template-columns: 1fr; gap: 0; } }
</style>
