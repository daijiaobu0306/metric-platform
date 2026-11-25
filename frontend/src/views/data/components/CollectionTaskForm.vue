<template>
  <el-dialog class="workspace-theme" v-model="visible" append-to-body :title="editing ? '配置采集任务' : '新增采集任务'" width="min(980px, 96vw)" :close-on-click-modal="false" @closed="reset">
    <el-alert v-if="errors.length" type="error" :closable="false" :title="errors[0]" class="notice" />
    <el-steps :active="step" finish-status="success" simple class="steps"><el-step title="选择指标大类" /><el-step title="文件与解析" /><el-step title="调度与确认" /></el-steps>
    <el-form label-position="top" class="task-form" @submit.prevent>
      <div v-show="step === 0">
        <el-form-item><template #label><CollectionFieldLabel label="指标大类 · 必填" help="选择正式指标大类后，选择兼容的已发布脚本版本。已有任务保持当前绑定，切换须确认。" /></template><div class="inline"><el-select v-model="form.worker.categoryCode" :disabled="editing && !!props.task?.config?.worker?.categoryCode" filterable class="full" placeholder="选择要采集的指标大类" :loading="optionsLoading" @change="selectCategory"><el-option v-for="category in categories" :key="category.id" :label="category.categoryName" :value="category.categoryCode" /></el-select><el-button :loading="optionsLoading" @click="loadOptions">刷新</el-button></div></el-form-item>
        <el-form-item v-if="!editing && form.worker.categoryCode === 'metric_g1'" label="合规数据集">
          <el-select :model-value="form.protocol" class="full" @change="selectComplianceDataset">
            <el-option label="月数据清单" value="metric-values" :disabled="existingComplianceTasks.includes('compliance')" />
            <el-option label="工单明细" value="raw-rows" :disabled="existingComplianceTasks.includes('compliance_detail')" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.worker.categoryCode" label="采集脚本版本">
          <div v-if="editing" class="script-status">当前绑定：{{ props.task?.config?.worker?.version || '未绑定' }}</div>
          <el-select v-model="form.worker.version" class="full" filterable placeholder="选择已发布的兼容脚本版本" :loading="optionsLoading">
            <el-option v-for="version in categoryVersions" :key="version.version" :label="`${version.displayName} · ${version.version}`" :value="version.version" />
          </el-select>
          <el-alert v-if="!optionsLoading && !scriptAvailable" title="当前选择的版本不可用或不兼容；请检查发布目录并刷新后选择兼容版本。原绑定不会自动更改。" type="warning" :closable="false" />
          <div v-if="invalidVersions.length" class="script-status">目录中有 {{ invalidVersions.length }} 个无效版本：{{ invalidVersions.map(v => `${v.version}（${v.error}）`).join('、') }}</div>
        </el-form-item>
      </div>
      <div v-show="step === 1">
        <div class="form-grid">
          <el-form-item><template #label><CollectionFieldLabel label="投递目录 · 必填" help="相对于平台文件根目录，不能越界或与其他采集目录重叠；将完整文件放入该目录。" /></template><el-input v-model="form.importPath"  placeholder="raw_data/import/business_value" /></el-form-item>
          <el-form-item><template #label><CollectionFieldLabel label="归档目录 · 必填" help="相对于平台文件根目录；采集入库成功后将文件归档。已有采集批次时不能修改。" /></template><el-input v-model="form.archivePath" :disabled="hasHistory" placeholder="raw_data/archive/business_value" /></el-form-item>
        </div>
        <div class="form-grid section-gap">
          <el-form-item><template #label><CollectionFieldLabel label="文件格式" help="支持CSV、TXT及XLSX。CSV/TXT无需第三方依赖；XLSX需执行器预装可选依赖，缺少依赖时保留文件并记录失败原因。" /></template><el-select v-model="form.format"  class="full"><el-option label="CSV" value="csv" /><el-option label="TXT" value="txt" /><el-option label="Excel（.xlsx）" value="xlsx" /></el-select></el-form-item>
          <el-form-item><template #label><CollectionFieldLabel label="文件匹配 · 必填" help="使用文件名通配规则，例如 *.csv 或 *_daily.txt；不包含目录。" /></template><el-input v-model="form.filePattern" placeholder="*.csv" /></el-form-item>
        </div>
        <el-form-item v-if="form.format === 'xlsx'"><template #label><CollectionFieldLabel label="工作表" help="填写Excel工作表名称；留空读取第一张工作表。" /></template><el-input v-model="form.sheet" placeholder="留空读取第一张表" /></el-form-item>
        <div v-else class="form-grid"><el-form-item><template #label><CollectionFieldLabel label="文件编码" help="须与对端导出文件一致，支持UTF-8和GB18030；编码不匹配会导致解析失败。" /></template><el-select v-model="form.encoding" class="full"><el-option label="UTF-8" value="UTF-8" /><el-option label="GB18030" value="GB18030" /></el-select></el-form-item><el-form-item><template #label><CollectionFieldLabel label="分隔符" help="按对端实际分隔符填写，支持1～16个字符，例如逗号、@@、€€，按完整文本匹配。不能包含换行、双引号或空字符；字段内包含分隔符时用双引号包裹，内部双引号写成两个双引号。仅对CSV/TXT生效。" /></template><el-input v-model="form.delimiter" maxlength="16" /></el-form-item></div>
        <el-form-item><template #label><CollectionFieldLabel label="数据日期来源" help="按任务已有配置使用脚本输出的数据时间或平台入库时间。" /></template><el-input :model-value="form.dateMode === 'ingestion_time' ? '平台入库时间' : '脚本输出的实际数据时间'" disabled /></el-form-item>
        <template v-if="mapped">
          <div class="mapping-header"><CollectionFieldLabel label="字段映射" help="默认第一行为表头。指标值使用小数类型，空值不会补零；版本内的配置可覆盖任务映射，需与脚本配置保持一致。" /><el-button size="small" @click="addBaseline">补齐标准字段</el-button></div>
          <el-table :data="form.fields" size="small" border max-height="360">
            <el-table-column label="源文件表头" min-width="150"><template #header><CollectionFieldLabel label="源文件表头" help="填写对端文件实际表头，名称须准确一致。" /></template><template #default="{ row }"><el-input v-model="row.source" placeholder="例如 指标编码" /></template></el-table-column>
            <el-table-column label="标准字段" min-width="170"><template #header><CollectionFieldLabel label="标准字段" help="将对端列映射到平台统一字段；需要指标编码、指标值和数据时间。" /></template><template #default="{ row }"><el-select v-model="row.target" filterable allow-create default-first-option><el-option v-for="field in standardFields" :key="field.target" :label="field.label" :value="field.target" /></el-select></template></el-table-column>
            <el-table-column label="类型" min-width="120"><template #header><CollectionFieldLabel label="类型" help="按来源字段含义选择文本、小数、整数、日期或日期时间。" /></template><template #default="{ row }"><el-select v-model="row.type"><el-option v-for="type in types" :key="type.value" :label="type.label" :value="type.value" /></el-select></template></el-table-column>
            <el-table-column label="必填" width="85"><template #header><CollectionFieldLabel label="必填" help="开启后，缺少该列或该列为空时明确报错；指标值允许为空时保持关闭。" /></template><template #default="{ row }"><el-switch v-model="row.required" /></template></el-table-column>
            <el-table-column width="60"><template #default="{ $index }"><el-button link type="danger" @click="form.fields.splice($index, 1)">移除</el-button></template></el-table-column>
          </el-table>
          <el-button size="small" class="section-gap" @click="form.fields.push({ source: '', target: '', type: 'string', required: false })">添加映射</el-button>
        </template>
        <template v-else>
          <el-alert title="该任务按明细行协议采集：脚本直接产出工单明细并回写明细表，不产生指标值，因此无需配置字段映射；调度与启停照常配置。" type="info" :closable="false" class="notice" />
        </template>
      </div>
      <div v-show="step === 2">
        <div class="form-grid">
          <el-form-item label="调度方式"><el-select v-model="form.schedule.type" class="full"><el-option label="按间隔执行" value="interval" /><el-option label="每日定时" value="daily" /></el-select></el-form-item>
          <el-form-item><template #label><CollectionFieldLabel label="保存后启用" help="开启后按配置的执行计划采集；暂停不会删除历史数据。" /></template><el-switch v-model="form.enabled" /></el-form-item>
          <el-form-item v-if="form.schedule.type === 'daily'"><template #label><CollectionFieldLabel label="每日执行时间 · 必填" help="按北京时间执行，保存或启用后从下一个设定时刻开始。例如对端08:00送达前一天数据，可设08:05；实际开始时间受执行器空闲情况影响。" /></template><el-time-picker v-model="form.schedule.time" format="HH:mm" value-format="HH:mm" placeholder="例如 08:05" class="full" /></el-form-item>
          <el-form-item v-else><template #label><CollectionFieldLabel label="扫描间隔" help="按周期扫描，范围10秒至7天，不代表固定时刻执行。" /></template><div class="inline"><el-input-number v-model="intervalValue" :min="1" :max="604800" /><el-select v-model="intervalUnit" style="width:110px"><el-option label="秒" :value="1" /><el-option label="分钟" :value="60" /><el-option label="小时" :value="3600" /><el-option label="天" :value="86400" /></el-select></div></el-form-item>
        </div>
        <el-alert v-if="form.schedule.type === 'daily'" title="T-1数据按文件内原始日期入库。未到文件或执行失败时可用“立即采集”重试；下一次自动执行仍按每日设定时间。" type="info" :closable="false" class="notice" />
        <el-collapse><el-collapse-item title="高级执行设置" name="advanced"><div class="form-grid"><el-form-item><template #label><CollectionFieldLabel label="文件稳定等待（秒）" help="文件最后修改后等待5～3600秒再读取；上游可先写.part，传输完成后改为正式后缀。" /></template><el-input-number v-model="form.settleSeconds" :min="5" :max="3600" /></el-form-item><el-form-item v-if="form.parser === 'python-worker'"><template #label><CollectionFieldLabel label="插件执行超时（秒）" help="单个文件的脚本执行时限，范围1～300秒；超时后记录失败并保留源文件。" /></template><el-input-number v-model="form.worker.timeoutSeconds" :min="1" :max="300" /></el-form-item></div></el-collapse-item></el-collapse>
        <el-descriptions title="配置确认" :column="1" border class="section-gap"><el-descriptions-item label="任务">{{ form.name || '选择大类后自动生成' }}</el-descriptions-item><el-descriptions-item label="执行方式">{{ parserLabel(form.parser) }} / {{ form.worker.version || '未选择脚本' }} / {{ mapped ? '指标值协议' : '明细行协议' }}</el-descriptions-item><el-descriptions-item label="输入">{{ form.format?.toUpperCase() }} / {{ form.filePattern }} / {{ form.importPath || '未填写目录' }}</el-descriptions-item><el-descriptions-item label="调度">{{ scheduleSummary }}；{{ form.enabled ? '启用自动采集' : '保持暂停' }}</el-descriptions-item></el-descriptions>
      </div>
    </el-form>
    <template #footer><div class="dialog-footer"><el-button @click="visible = false" :disabled="saving">取消</el-button><div><el-button v-if="step > 0" @click="step--" :disabled="saving">上一步</el-button><el-button v-if="step < 2" type="primary" @click="next">下一步</el-button><el-button v-else type="primary" :loading="saving" @click="save">保存任务</el-button></div></div></template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import CollectionFieldLabel from './CollectionFieldLabel.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../../api'
import { parserLabel, scheduleLabel } from '../../../utils/collectionTasks'
const props = defineProps({ modelValue: Boolean, task: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const form = ref({ fields: [], worker: {}, schedule: { type: 'interval', time: null } }), step = ref(0), errors = ref([]), saving = ref(false)
const categoryVersions = computed(() => versions.value.filter(version => version.status === 'available' && version.categoryCode === form.value.worker?.categoryCode && version.protocol === (form.value.protocol === 'raw-rows' ? 'raw-rows' : 'metric-values') && (version.categoryCode !== 'metric_g1' || version.datasetCode === (form.value.protocol === 'raw-rows' ? 'compliance_detail' : 'compliance') || (!version.datasetCode && version.version === props.task?.config?.worker?.version))))
const invalidVersions = computed(() => versions.value.filter(version => version.status === 'invalid'))
const scriptAvailable = computed(() => categoryVersions.value.some(version => version.version === form.value.worker?.version))
const optionsError = ref(false)
const versions = ref([]), categories = ref([]), optionsLoading = ref(false), intervalValue = ref(5), intervalUnit = ref(60)
let optionRequest = 0
const editing = computed(() => !!props.task), hasHistory = computed(() => Number(props.task?.batchCount) > 0)
const existingComplianceTasks = computed(() => (categories.value.find(item => item.categoryCode === 'metric_g1')?.taskCodes || '').split(','))
const mapped = computed(() => form.value.protocol !== 'raw-rows')
const scheduleSummary = computed(() => scheduleLabel({ ...form.value, intervalSeconds: intervalValue.value * intervalUnit.value }))
const standardFields = [
  { label: '指标编码', source: '指标编码', target: 'metric_code', type: 'string', required: true },
  { label: '指标名称', source: '指标名称', target: 'metric_name', type: 'string', required: false },
  { label: '指标值', source: '指标值', target: 'metric_value', type: 'decimal', required: false },
  { label: '单位', source: '单位', target: 'unit', type: 'string', required: false },
  { label: '数据时间', source: '数据时间', target: 'data_time', type: 'datetime', required: true },
  { label: '指标大类', source: '指标大类', target: 'category_name', type: 'string', required: false },
  { label: '指标小类', source: '指标小类', target: 'sub_category_name', type: 'string', required: false }
]
const types = [{ value: 'string', label: '文本' }, { value: 'decimal', label: '小数' }, { value: 'long', label: '整数' }, { value: 'date', label: '日期' }, { value: 'datetime', label: '日期时间' }]
function addBaseline() {
  const targets = new Set(form.value.fields.map(field => field.target))
  form.value.fields.push(...standardFields.filter(field => !targets.has(field.target)).map(({ label, ...field }) => ({ ...field })))
}
function generatedCode(category) {
  const prefix = String(category).replace(/([a-z])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9_]/g, '_').slice(0, 24)
  return `${/^[a-z]/.test(prefix) ? prefix : 'collect'}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`
}
function bindScript() {
  // Bind by the exact declared category code. Pin this version on the task;
  // refreshing the catalog must not silently upgrade an existing binding.
  if (!editing.value && !form.value.worker.version && categoryVersions.value.length === 1) form.value.worker.version = categoryVersions.value[0].version
}
function selectCategory(code) {
  if (editing.value) return
  if (code === 'metric_g1') {
    selectComplianceDataset(existingComplianceTasks.value.includes('compliance') ? 'raw-rows' : 'metric-values')
    return
  }
  const previousCode = form.value.code
  form.value.protocol = 'metric-values'
  const category = categories.value.find(item => item.categoryCode === code)
  form.value.code = generatedCode(code)
  form.value.name = `${category?.categoryName || code}采集任务`.slice(0, 100)
  if (!form.value.importPath || form.value.importPath === `raw_data/import/${previousCode}`) form.value.importPath = `raw_data/import/${form.value.code}`
  if (!form.value.archivePath || form.value.archivePath === `raw_data/archive/${previousCode}`) form.value.archivePath = `raw_data/archive/${form.value.code}`
  form.value.worker.version = ''
  bindScript()
}
function selectComplianceDataset(protocol) {
  const previousCode = form.value.code
  form.value.protocol = protocol
  form.value.code = protocol === 'raw-rows' ? 'compliance_detail' : 'compliance'
  form.value.name = protocol === 'raw-rows' ? '合规工单明细采集' : '合规月数据清单采集'
  if (!form.value.importPath || form.value.importPath === `raw_data/import/${previousCode}`) form.value.importPath = `raw_data/import/${form.value.code}`
  if (!form.value.archivePath || form.value.archivePath === `raw_data/archive/${previousCode}`) form.value.archivePath = `raw_data/archive/${form.value.code}`
  form.value.worker.version = ''
  bindScript()
}
async function loadOptions() {
  const id = ++optionRequest
  optionsLoading.value = true
  optionsError.value = false
  try {
    const results = await Promise.allSettled([request.get('/collection/tasks/script-catalog'), request.get('/collection/tasks/categories')])
    if (id !== optionRequest) return
    versions.value = results[0].status === 'fulfilled' ? results[0].value.data : []
    categories.value = results[1].status === 'fulfilled' ? results[1].value.data : []
    optionsError.value = results.some(result => result.status === 'rejected')
    if (optionsError.value) errors.value = ['部分配置选项未能加载，请刷新或稍后再试；当前配置未被修改。']
    else { bindScript(); errors.value = [] }
  } finally { if (id === optionRequest) optionsLoading.value = false }
}
function reset() { errors.value = []; step.value = 0 }
watch(() => props.modelValue, opened => {
  if (!opened) { optionRequest++; optionsLoading.value = false; return }
  reset()
  form.value = props.task ? JSON.parse(JSON.stringify(props.task.config)) : {
    code: '', name: '', enabled: false, importPath: '', archivePath: '', intervalSeconds: 300, settleSeconds: 30,
    parser: 'python-worker', format: 'csv', filePattern: '*.csv', sheet: '', encoding: 'UTF-8', delimiter: ',',
    dateMode: 'data_time', dateRegex: '^([0-9]{4}-[0-9]{2}-[0-9]{2})[_-].*', dateFormat: 'uuuu-MM-dd', fields: []
  }
  form.value.parser = 'python-worker'
  form.value.dateMode ??= 'data_time'
  form.value.fields ??= []
  form.value.schedule ??= { type: 'interval', time: null }
  form.value.worker ??= { version: '', categoryCode: form.value.code === 'running_efficiency' ? 'system_run' : '', timeoutSeconds: 60 }
  if (!props.task) addBaseline()
  const seconds = form.value.intervalSeconds
  intervalUnit.value = [86400, 3600, 60].find(unit => seconds % unit === 0) || 1
  intervalValue.value = seconds / intervalUnit.value
  loadOptions()
})
watch(() => form.value.format, (value, previous) => {
  if (previous && form.value.filePattern === `*.${previous}`) form.value.filePattern = `*.${value}`
})
function validate(targetStep) {
  const issues = []
  if (targetStep === 0) {
    if (optionsLoading.value || optionsError.value) return ['请等待配置加载完成，加载失败时点击刷新重试']
    if (!form.value.worker.categoryCode) return ['请选择指标大类']
    if (!editing.value) {
      const category = categories.value.find(item => item.categoryCode === form.value.worker.categoryCode)
      const duplicate = form.value.worker.categoryCode === 'metric_g1'
        ? existingComplianceTasks.value.includes(form.value.code) : Number(category?.taskCount) > 0
      if (duplicate) return ['该数据集已有采集任务，请编辑现有任务']
    }
    if (!form.value.name?.trim() || !/^[a-z][a-z0-9_]{0,49}$/.test(form.value.code || '')) issues.push('任务信息未能自动生成，请重新选择指标大类')
    if (!scriptAvailable.value) issues.push('当前大类的采集脚本不可用，请发布对应脚本后刷新')
  }
  if (targetStep === 1) {
    if (!form.value.importPath?.trim() || !form.value.archivePath?.trim()) issues.push('请填写投递和归档目录')
    if (!form.value.filePattern?.trim()) issues.push('请填写文件匹配规则')
    if (form.value.format !== 'xlsx' && (!form.value.delimiter || form.value.delimiter.length > 16 || /[\r\n"\0]/.test(form.value.delimiter))) issues.push('分隔符须为1～16个字符，不能含换行、引号或空字符')
    if (mapped.value) {
      if (!form.value.fields.length) issues.push('请配置字段映射')
      const targets = form.value.fields.map(field => field.target)
      if (targets.length !== new Set(targets).size) issues.push('标准目标字段不能重复')
      if (form.value.fields.some(field => !field.source?.trim() || !/^[a-zA-Z][a-zA-Z0-9_]{0,63}$/.test(field.target || ''))) issues.push('请填写有效的源表头和标准目标字段')
    }
  }
  if (targetStep === 2) {
    const interval = intervalValue.value * intervalUnit.value
    if (form.value.schedule.type === 'daily') {
      if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(form.value.schedule.time || '')) issues.push('请选择每日执行时间（北京时间，精确到分钟）')
    } else if (!Number.isInteger(interval) || interval < 10 || interval > 604800) issues.push('扫描间隔须为10秒至7天的整数秒数')
  }
  return issues
}
function next() { errors.value = validate(step.value); if (!errors.value.length) step.value++ }
async function save() {
  if (saving.value || optionsLoading.value) return
  for (let index = 0; index < 3; index++) {
    errors.value = validate(index)
    if (errors.value.length) { step.value = index; return }
  }
  if (editing.value && props.task?.config?.worker?.version !== form.value.worker.version) {
    try { await ElMessageBox.confirm(`将脚本版本从 ${props.task?.config?.worker?.version || '未绑定'} 切换为 ${form.value.worker.version}。请确认新脚本适配对端文件并保持输出协议一致。`, '确认切换脚本', { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '取消' }) }
    catch { return }
  }
  saving.value = true
  try {
    const daily = form.value.schedule?.type === 'daily'
    const payload = { ...form.value, intervalSeconds: intervalValue.value * intervalUnit.value,
      protocol: mapped.value ? 'metric-values' : 'raw-rows',
      schedule: { type: form.value.schedule?.type || 'interval', time: daily ? form.value.schedule.time : null,
        retryUntil: daily ? (form.value.schedule.retryUntil ?? null) : null,
        retryIntervalMinutes: daily ? (form.value.schedule.retryIntervalMinutes ?? null) : null },
      worker: form.value.worker }
    await request.post('/collection/tasks', payload)
    ElMessage.success('任务已保存')
    visible.value = false
    emit('saved')
  } catch (error) { errors.value = [error.response?.data?.message || error.message || '保存失败，配置已保留，请修正后重试'] }
  finally { saving.value = false }
}
</script>

<style scoped>
.script-status{margin-top:6px}.steps,.notice{margin-bottom:20px}.task-form{padding:4px 8px 4px 2px;min-height:300px;max-height:60vh;overflow-y:auto}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}.full{width:100%}.inline{display:flex;align-items:center;gap:8px;width:100%}.inline>.el-select{min-width:0}.section-gap{margin-top:16px}.mapping-header,.dialog-footer{display:flex;justify-content:space-between;align-items:center}.mapping-header{margin-top:20px}.task-form :deep(.el-form-item__label){font-weight:500}.task-form :deep(.el-form-item__content){display:block}@media(max-width:768px){.form-grid{grid-template-columns:1fr}.steps{font-size:12px}.dialog-footer{gap:12px}}
</style>
