<template>
  <section v-loading="loading" class="scenarios">
    <header class="scenario-header">
      <div>
        <h2>业务场景权重配置</h2>
        <p>按北京时间生效。同一指标取优先级最高的规则，同级取较早创建的一条。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="open()">新增场景</el-button>
    </header>
    <el-alert v-if="error" class="scenario-alert" :title="error" type="error" show-icon :closable="false" />
    <el-table :data="scopedRows" row-key="id" empty-text="暂无业务场景">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="scenario-scope-detail">
            <h3>影响指标 · {{ row.settings.metricIds.length }} 项</h3>
            <ScenarioMetricScope v-if="catalogReady" :groups="row.scope" />
            <p v-else class="secondary">{{ loadingMetrics ? '正在读取指标目录' : '指标目录暂不可用' }}</p>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="场景 / 周期" min-width="200">
        <template #default="{ row }">
          <strong>{{ row.settings.name }}</strong>
          <div class="secondary">{{ schedule(row.settings) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="影响范围" min-width="260">
        <template #default="{ row }">
          <div class="scope-summary">
            <div v-if="catalogReady" class="scope-paths">
              <div v-for="category in row.scope" :key="category.id" class="scope-path">
                <strong>{{ category.name }}</strong>
                <span v-for="group in category.groups" :key="group.name">{{ group.name }}</span>
              </div>
            </div>
            <div class="scope-count">{{ row.settings.metricIds.length }} 项 · ×{{ row.settings.multiplier }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="80">
        <template #default="{ row }">{{ row.settings.priority }}</template>
      </el-table-column>
      <el-table-column label="当前状态" width="120">
        <template #default="{ row }">
          <WorkspaceStatusBadge :tone="row.active ? 'success' : row.settings.enabled ? 'primary' : 'muted'">
            {{ !row.settings.enabled ? '已停用' : row.active ? '时段命中' : '等待生效' }}
          </WorkspaceStatusBadge>
        </template>
      </el-table-column>
      <el-table-column prop="nextStart" label="下次时段开始" min-width="135">
        <template #default="{ row }">{{ row.nextStart || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="154" align="center">
        <template #default="{ row }">
          <div class="scenario-actions">
            <el-button size="small" type="primary" plain @click="open(row)">编辑</el-button>
            <el-button size="small" :type="row.settings.enabled ? 'danger' : 'success'" plain @click="toggle(row)">
              {{ row.settings.enabled ? '停用' : '启用' }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="id == null ? '新增场景' : '编辑场景'" width="min(940px, 96vw)">
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" />
      <el-form label-position="top" class="scenario-form">
        <div class="scenario-basics">
          <el-form-item label="场景名称">
            <el-input v-model="form.name" maxlength="100" placeholder="例如 月初月末忙时" />
          </el-form-item>
          <el-form-item label="周期">
            <el-select v-model="form.scheduleType" aria-label="周期">
              <el-option v-for="(label, key) in schedules" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
        </div>
        <div class="scenario-columns">
          <el-form-item v-if="isMonthly" label="月初天数">
            <el-input-number v-model="form.firstDays" :min="0" :max="28" :precision="0" aria-label="月初天数" />
          </el-form-item>
          <el-form-item v-if="isMonthly" label="月末天数">
            <el-input-number v-model="form.lastDays" :min="0" :max="28" :precision="0" aria-label="月末天数" />
          </el-form-item>
          <el-form-item v-if="form.scheduleType === 'date_range'" label="开始日期（含）">
            <el-date-picker v-model="form.startsOn" type="date" value-format="YYYY-MM-DD" aria-label="开始日期" />
          </el-form-item>
          <el-form-item v-if="form.scheduleType === 'date_range'" label="结束日期（含）">
            <el-date-picker v-model="form.endsOn" type="date" value-format="YYYY-MM-DD" aria-label="结束日期" />
          </el-form-item>
          <el-form-item v-if="form.scheduleType === 'annual_range'" label="每年开始（月-日）">
            <el-input v-model="annualStart" placeholder="例如 10-01" maxlength="5" />
          </el-form-item>
          <el-form-item v-if="form.scheduleType === 'annual_range'" label="每年结束（月-日）">
            <el-input v-model="annualEnd" placeholder="例如 10-07" maxlength="5" />
          </el-form-item>
        </div>
        <section class="scenario-picker" v-loading="loadingMetrics" aria-label="影响指标">
          <header class="picker-header">
            <div><h3>影响指标</h3><p>先选大类，再选小类，最后勾选具体指标。可跨小类添加。</p></div>
            <span class="selected-count">已选 {{ form.metricIds.length }} 项</span>
          </header>
          <div v-if="catalogError" class="picker-error">
            <el-alert :title="catalogError" type="error" :closable="false" />
            <el-button size="small" @click="loadCatalog">重试</el-button>
          </div>
          <div class="scope-picker-controls">
            <el-form-item label="1. 指标大类">
              <el-select v-model="activeCategoryId" placeholder="选择指标大类" filterable
                aria-label="指标大类" @change="chooseCategory">
                <el-option v-for="category in availableScope" :key="category.id" :label="category.name" :value="category.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="2. 指标小类">
              <el-select v-model="activeSubcategory" :disabled="!activeCategory" placeholder="选择指标小类" filterable
                aria-label="指标小类" @change="search = ''">
                <el-option v-for="group in activeCategory?.groups || []" :key="group.name" :label="group.name" :value="group.name" />
              </el-select>
            </el-form-item>
            <el-form-item label="3. 具体指标">
              <el-input v-model="search" :disabled="!activeGroup" clearable placeholder="在本小类搜索名称或编码" aria-label="搜索小类内指标" />
            </el-form-item>
          </div>
          <div class="picker-options">
            <p v-if="!activeGroup" class="secondary">请选择大类和小类，再勾选具体指标。</p>
            <p v-else-if="!visibleMetrics.length" class="secondary">这个小类没有匹配的指标。</p>
            <el-checkbox-group v-else v-model="form.metricIds" class="metric-checkboxes" aria-label="具体指标">
              <el-checkbox v-for="metric in visibleMetrics" :key="metric.id" :value="metric.id">
                <span class="metric-checkbox-label">{{ metric.metricName }} <small>{{ metric.metricCode }}</small></span>
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <div v-if="catalogReady && selectedScope.length" class="picker-selected">
            <h4>已选指标</h4>
            <ScenarioMetricScope :groups="selectedScope" removable @remove="removeMetric" />
          </div>
        </section>
        <div class="scenario-columns">
          <el-form-item label="权重乘数">
            <el-input-number v-model="form.multiplier" :min="0.01" :max="10" :precision="2" :step="0.1" aria-label="权重乘数" />
          </el-form-item>
          <el-form-item label="优先级（越大越优先）">
            <el-input-number v-model="form.priority" :min="0" :max="9999" :precision="0" aria-label="优先级" />
          </el-form-item>
        </div>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" aria-label="启用场景" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="loading" :disabled="!catalogReady || loadingMetrics" @click="save">保存场景</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import ScenarioMetricScope from './ScenarioMetricScope.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import request, { metricApi } from '@/api'

const path = '/weight/scenarios'
const options = { silentError: true }
const schedules = {
  month_edges: '每月月初与月末', month_middle: '每月去除月初与月末',
  date_range: '指定日期范围', annual_range: '每年固定日期'
}
const newSettings = () => ({
  name: '', enabled: false, priority: 0, scheduleType: 'month_edges', startsOn: null, endsOn: null,
  firstDays: 5, lastDays: 5, metricIds: [], multiplier: 1.2
})
const rows = ref([])
const loading = ref(false)
const error = ref('')
const formError = ref('')
const loadingMetrics = ref(false)
const metrics = ref([])
const categories = ref([])
const catalogError = ref('')
const catalogReady = ref(false)
const activeCategoryId = ref(null)
const activeSubcategory = ref(null)
const search = ref('')
const visible = ref(false)
const id = ref(null)
const revision = ref(null)
const form = ref(newSettings())
const annualStart = ref('')
const annualEnd = ref('')
const isMonthly = computed(() => form.value.scheduleType.startsWith('month_'))
const metricById = computed(() => new Map(metrics.value.map(metric => [metric.id, metric])))
const categoryById = computed(() => new Map(categories.value.map(category => [category.id, category.categoryName])))
const availableScope = computed(() => groupScope(metrics.value.filter(metric => metric.status === 1)))
const selectedScope = computed(() => groupScope(resolveMetrics(form.value.metricIds)))
const scopedRows = computed(() => rows.value.map(row => ({ ...row, scope: groupScope(resolveMetrics(row.settings.metricIds)) })))
const activeCategory = computed(() => availableScope.value.find(category => category.id === activeCategoryId.value))
const activeGroup = computed(() => activeCategory.value?.groups.find(group => group.name === activeSubcategory.value))
const visibleMetrics = computed(() => {
  const term = search.value.trim().toLowerCase()
  return (activeGroup.value?.metrics || []).filter(metric =>
    !term || [metric.metricName, metric.metricCode].some(value => value?.toLowerCase().includes(term)))
})

function resolveMetrics(ids) {
  return ids.map(id => metricById.value.get(id) || { id, metricName: '#' + id, missing: true })
}

function groupScope(items) {
  const groups = new Map()
  for (const metric of items) {
    const id = metric.categoryId ?? 'missing'
    if (!groups.has(id)) {
      const name = categoryById.value.get(id) || (id === 'missing' ? '目录中不可用' : '大类 #' + id)
      groups.set(id, { id, name, groups: new Map() })
    }
    const subgroups = groups.get(id).groups
    const name = metric.missing ? '已删除指标' : metric.subCategoryName || '未分小类'
    if (!subgroups.has(name)) subgroups.set(name, { name, metrics: [] })
    subgroups.get(name).metrics.push(metric)
  }
  return [...groups.values()].map(category => ({ ...category, groups: [...category.groups.values()] }))
}

function chooseCategory() {
  activeSubcategory.value = null
  search.value = ''
}

function showFirstSelected() {
  if (!visible.value || activeCategoryId.value !== null) return
  const first = resolveMetrics(form.value.metricIds).find(metric => metric.status === 1)
  if (!first) return
  activeCategoryId.value = first.categoryId
  activeSubcategory.value = first.subCategoryName || '未分小类'
}

function removeMetric(id) {
  form.value.metricIds = form.value.metricIds.filter(metricId => metricId !== id)
}

async function loadCatalog() {
  if (catalogReady.value || loadingMetrics.value) return
  loadingMetrics.value = true
  catalogError.value = ''
  try {
    const [items, categoryList] = await Promise.all([metricApi.itemListAll(), metricApi.categoryListAll()])
    metrics.value = items.data || []
    categories.value = categoryList.data || []
    catalogReady.value = true
    showFirstSelected()
  } catch (err) {
    catalogError.value = message(err)
  } finally {
    loadingMetrics.value = false
  }
}

function schedule(settings) {
  const label = schedules[settings.scheduleType]
  if (settings.scheduleType.startsWith('month_')) return label + ' · 各 ' + settings.firstDays + ' / ' + settings.lastDays + ' 天'
  const from = settings.scheduleType === 'annual_range' ? 5 : 0
  return label + ' · ' + settings.startsOn.slice(from) + ' 至 ' + settings.endsOn.slice(from)
}

const message = err => err.response?.data?.message || err.message
async function load() {
  loading.value = true
  error.value = ''
  try { rows.value = (await request.get(path, options)).data }
  catch (err) { error.value = message(err) }
  finally { loading.value = false }
}
onMounted(() => {
  load()
  loadCatalog()
})

async function open(row) {
  id.value = row?.id ?? null
  revision.value = row?.revision ?? null
  form.value = row ? { ...row.settings, metricIds: [...row.settings.metricIds] } : newSettings()
  form.value.firstDays ??= 5
  form.value.lastDays ??= 5
  annualStart.value = form.value.scheduleType === 'annual_range' ? form.value.startsOn?.slice(5) || '' : ''
  annualEnd.value = form.value.scheduleType === 'annual_range' ? form.value.endsOn?.slice(5) || '' : ''
  if (form.value.scheduleType === 'annual_range') {
    form.value.startsOn = null
    form.value.endsOn = null
  }
  visible.value = true
  formError.value = ''
  activeCategoryId.value = null
  chooseCategory()
  if (catalogReady.value) showFirstSelected()
  else await loadCatalog()
}

async function persist(target, version, settings) {
  if (target == null) return request.post(path, { settings }, options)
  return request.put(path + '/' + target, { revision: version, settings }, options)
}

async function save() {
  const settings = { ...form.value }
  if (isMonthly.value) {
    settings.startsOn = null
    settings.endsOn = null
  } else {
    settings.firstDays = null
    settings.lastDays = null
  }
  if (settings.scheduleType === 'annual_range') {
    if (![annualStart.value, annualEnd.value].every(value => /^\d{2}-\d{2}$/.test(value))) {
      formError.value = '每年固定日期请按 MM-DD 输入'
      return
    }
    settings.startsOn = '2000-' + annualStart.value
    settings.endsOn = '2000-' + annualEnd.value
  }
  loading.value = true
  formError.value = ''
  try {
    await persist(id.value, revision.value, settings)
    visible.value = false
    ElMessage.success('场景已保存')
    await load()
  } catch (err) {
    formError.value = message(err)
  } finally {
    loading.value = false
  }
}

async function toggle(row) {
  const enabled = !row.settings.enabled
  try {
    await ElMessageBox.confirm((enabled ? '启用' : '停用') + '「' + row.settings.name + '」？变更将用于后续健康度计算。', '调整场景状态')
  } catch { return }
  loading.value = true
  error.value = ''
  try {
    await persist(row.id, row.revision, { ...row.settings, enabled })
    await load()
  } catch (err) {
    error.value = message(err)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.scenario-header, .picker-header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.scenario-header {
  padding: 14px 16px; margin-bottom: 12px; border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color); border-radius: 10px;
}
.scenario-header h2, .picker-header h3, .scenario-scope-detail h3, .picker-selected h4 {
  font-size: 15px; margin: 0;
}
.scenario-header p, .picker-header p, .secondary { color: var(--el-text-color-secondary); margin: 4px 0 0; }
.scenario-alert { margin-bottom: 10px; }
.scenario-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; }
.scenario-actions .el-button { margin-left: 0; }
.scope-summary { display: grid; gap: 5px; padding: 3px 0; }
.scope-paths, .scope-path { display: flex; flex-wrap: wrap; gap: 4px 6px; }
.scope-paths { flex-direction: column; }
.scope-path { align-items: center; }
.scope-path strong { font-size: 12px; }
.scope-path span, .selected-count {
  padding: 0 6px; border-radius: 5px; background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary); font-size: 11px;
}
.scope-count { font-variant-numeric: tabular-nums; color: var(--el-text-color-secondary); }
.scenario-scope-detail { padding: 12px 24px; background: var(--el-fill-color-lighter); }
.scenario-scope-detail h3 { margin-bottom: 9px; }
.scenario-form { margin-top: 12px; }
.scenario-form .el-select { width: 100%; }
.scenario-basics, .scope-picker-controls { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.scenario-columns { display: flex; flex-wrap: wrap; gap: 12px; }
.scenario-picker {
  margin: 2px 0 16px; padding: 12px; border-radius: 10px;
  border: 1px solid var(--el-border-color-light); background: var(--el-fill-color-lighter);
}
.picker-header { margin-bottom: 12px; }
.selected-count { white-space: nowrap; padding: 3px 8px; }
.scope-picker-controls { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.scope-picker-controls .el-form-item { margin-bottom: 10px; }
.picker-error { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.picker-options {
  padding: 8px 10px; max-height: 180px; overflow: auto; border-radius: 7px;
  border: 1px solid var(--el-border-color-lighter); background: var(--el-bg-color);
}
.picker-options p { margin: 0; }
.metric-checkboxes { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 235px), 1fr)); gap: 3px 10px; }
.metric-checkboxes .el-checkbox { height: auto; min-height: 27px; margin: 0; white-space: normal; }
.metric-checkbox-label { display: inline-grid; gap: 2px; }
.metric-checkbox-label small { color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
.picker-selected { margin-top: 12px; }
.picker-selected h4 { margin-bottom: 8px; }
@media (max-width: 680px) {
  .scenario-basics, .scope-picker-controls { grid-template-columns: minmax(0, 1fr); gap: 0; }
}
</style>
