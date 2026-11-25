<template>
  <section class="collection-workspace workspace-theme">
    <div class="workspace-header"><div><h2><CollectionFieldLabel label="采集任务" help="新增任务后配置脚本版本、文件和字段映射，启用并投递完整文件，再查看入库批次。立即采集表示安排Worker领取任务，实际结果以入库批次为准。" /></h2></div><div class="header-actions"><span class="refresh-time">{{ updatedAt ? `更新于 ${formatTime(updatedAt)}` : '' }}</span><el-button :loading="loading" @click="load(true)">刷新</el-button><el-button v-if="isAdmin" type="primary" @click="edit()">新增任务</el-button></div></div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="notice" />
    <div class="overview-cards"><button v-for="card in cards" :key="card.key" :class="['overview-card', { active: stateFilter === card.key }]" @click="stateFilter = stateFilter === card.key ? '' : card.key"><span>{{ card.label }}</span><strong :class="card.tone">{{ card.count }}</strong></button></div>
    <div class="task-panel">
      <div class="filters" v-balanced-query><el-input v-model="keyword" clearable placeholder="搜索任务名称、编码或目录" /><el-select v-model="stateFilter" clearable placeholder="全部任务状态"><el-option v-for="option in stateOptions" :key="option.value" :value="option.value" :label="option.label" /></el-select><el-select v-model="parserFilter" clearable placeholder="全部采集方式"><el-option v-for="option in parserOptions" :key="option.value" :value="option.value" :label="option.label" /></el-select><el-button @click="clearFilters">重置</el-button></div>
      <div v-loading="loading && !tasks.length" class="desktop-table">
        <el-table :data="visibleTasks" row-key="code" :empty-text="loadError ? '加载失败，请刷新' : '暂无匹配的采集任务'">
          <el-table-column label="任务" min-width="205"><template #default="{ row }"><div class="task-title">{{ row.name }}</div><div class="subline">{{ row.code }}</div></template></el-table-column>
          <el-table-column label="执行方式" min-width="165"><template #default="{ row }"><div>{{ parserLabel(row.config.parser) }}</div><div v-if="isWorker(row)" class="subline">版本 {{ row.config.worker?.version || '未配置' }}</div></template></el-table-column>
          <el-table-column label="运行状态" min-width="160"><template #default="{ row }"><el-tooltip :content="state(row).description" placement="top" :show-after="300"><el-tag :type="state(row).type">{{ state(row).label }}</el-tag></el-tooltip><div v-if="row.lastError" class="error-preview" @click="openHistory(row)">查看失败原因</div></template></el-table-column>
          <el-table-column label="最近入库" min-width="185"><template #default="{ row }"><div>{{ formatTime(row.lastCollectedAt) }}</div><div class="subline">累计 {{ row.batchCount }} 个批次<span v-if="Number(row.pendingArchives)"> · {{ row.pendingArchives }} 个待归档</span></div></template></el-table-column>
          <el-table-column label="调度" min-width="190"><template #default="{ row }"><div>{{ scheduleLabel(row.config) }}</div><div class="subline">{{ isEnabled(row) ? `下次：${formatTime(row.nextScanAt)}` : '已暂停自动采集' }}</div></template></el-table-column>
          <el-table-column label="操作" width="240" fixed="right"><template #default="{ row }"><div class="row-actions"><el-button link type="primary" @click="openHistory(row)">记录</el-button><template v-if="isAdmin"><el-button link :disabled="row.running || !!busyCode" @click="edit(row)">配置</el-button><el-button link type="primary" :loading="busyCode === row.code && busyAction === 'scan'" :disabled="row.running || !isEnabled(row) || !!busyCode" @click="scan(row)">立即采集</el-button><el-dropdown trigger="click" :disabled="row.running || !!busyCode" @command="command => more(command, row)"><el-button link :disabled="row.running || !!busyCode">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="toggle">{{ isEnabled(row) ? '暂停任务' : '启用任务' }}</el-dropdown-item><el-dropdown-item command="delete" divided>删除任务</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></div></template></el-table-column>
        </el-table>
      </div>
      <div v-loading="loading && !tasks.length" class="mobile-tasks"><article v-for="task in visibleTasks" :key="task.code" class="mobile-task"><div class="mobile-title"><strong>{{ task.name }}</strong><el-tag :type="state(task).type">{{ state(task).label }}</el-tag></div><div class="subline">{{ task.code }} · {{ parserLabel(task.config.parser) }}</div><div>最近入库：{{ formatTime(task.lastCollectedAt) }}</div><div>执行计划：{{ scheduleLabel(task.config) }}</div><div class="mobile-actions"><el-button size="small" @click="openHistory(task)">查看记录</el-button><template v-if="isAdmin"><el-button size="small" :disabled="task.running || !!busyCode" @click="edit(task)">配置</el-button><el-button size="small" :disabled="task.running || !isEnabled(task) || !!busyCode" @click="scan(task)">立即采集</el-button><el-dropdown :disabled="task.running || !!busyCode" @command="command => more(command, task)"><el-button size="small" :disabled="task.running || !!busyCode">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="toggle">{{ isEnabled(task) ? '暂停' : '启用' }}</el-dropdown-item><el-dropdown-item command="delete" divided>删除</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></div></article><el-empty v-if="!visibleTasks.length" description="暂无匹配的任务" /></div>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="filteredTasks.length" :page-sizes="[10, 20, 50]" :layout="paginationLayout" class="pagination" @size-change="page = 1" />
    </div>
    <CollectionTaskForm v-model="showForm" :task="selected" @saved="load(true)" />
    <CollectionTaskHistory v-model="showHistory" :task="selected" />
  </section>
</template>
<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api'
import CollectionTaskForm from './components/CollectionTaskForm.vue'
import CollectionFieldLabel from './components/CollectionFieldLabel.vue'
import CollectionTaskHistory from './components/CollectionTaskHistory.vue'
import { parserOptions, parserLabel, isEnabled, isWorker, formatTime, scheduleLabel, taskState } from '../../utils/collectionTasks'
const tasks = ref([]), loading = ref(false), loadError = ref(''), updatedAt = ref(''), serverTime = ref('')
const keyword = ref(''), stateFilter = ref(''), parserFilter = ref(''), page = ref(1), pageSize = ref(10)
const showForm = ref(false), showHistory = ref(false), selected = ref(null), busyCode = ref(''), busyAction = ref('')
const isAdmin = ['true', '1'].includes(sessionStorage.getItem('isAdmin'))
const paginationLayout = window.innerWidth < 768 ? 'prev, pager, next' : 'total, sizes, prev, pager, next'
const stateOptions = [{ value: 'running', label: '执行中' }, { value: 'error', label: '异常待处理' }, { value: 'waiting', label: '等待执行器' }, { value: 'archive', label: '待归档' }, { value: 'paused', label: '已暂停' }, { value: 'new', label: '待首次执行' }, { value: 'scheduled', label: '等待下次执行' }]
const state = task => taskState(task, serverTime.value)
const cards = computed(() => [
  { key: '', label: '全部任务', count: tasks.value.length, note: '按数据来源管理', tone: '' },
  { key: 'running', label: '执行中', count: tasks.value.filter(task => state(task).key === 'running').length, note: '当前正在处理', tone: 'primary' },
  { key: 'error', label: '异常待处理', count: tasks.value.filter(task => state(task).key === 'error').length, note: '查看原因后重试', tone: 'danger' },
  { key: 'paused', label: '已暂停', count: tasks.value.filter(task => !isEnabled(task)).length, note: '历史数据保留', tone: 'muted' }
])
const filteredTasks = computed(() => tasks.value.filter(task => {
  const text = keyword.value.trim().toLowerCase()
  return (!text || [task.name, task.code, task.config.importPath, task.config.archivePath].some(value => String(value || '').toLowerCase().includes(text))) && (!parserFilter.value || task.config.parser === parserFilter.value) && (!stateFilter.value || state(task).key === stateFilter.value)
}))
const visibleTasks = computed(() => filteredTasks.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch([keyword, stateFilter, parserFilter], () => { page.value = 1 })
watch(() => filteredTasks.value.length, total => { page.value = Math.min(page.value, Math.max(1, Math.ceil(total / pageSize.value))) })
let inFlight = false, active = true, timer, requestVersion = 0
async function load(manual = false) {
  if (inFlight && !manual) return
  const version = ++requestVersion
  inFlight = true; loading.value = manual || !tasks.value.length
  try {
    const { data } = await request.get('/collection/tasks/overview')
    if (!active || version !== requestVersion) return
    tasks.value = data.tasks; serverTime.value = data.serverTime; updatedAt.value = data.serverTime; loadError.value = ''
  } catch (error) {
    if (active && version === requestVersion) loadError.value = error.response?.data?.message || error.message || '加载失败，当前显示上次获取的数据'
  } finally {
    if (version === requestVersion) { inFlight = false; loading.value = false }
  }
}
function clearFilters() { keyword.value = ''; parserFilter.value = ''; stateFilter.value = '' }
function edit(task = null) { selected.value = task; showForm.value = true }
function openHistory(task) { selected.value = task; showHistory.value = true }
async function scan(task) {
  if (busyCode.value) return
  busyCode.value = task.code; busyAction.value = 'scan'
  try {
    const { data } = await request.post(`/collection/tasks/${encodeURIComponent(task.code)}/scan`, {}, { timeout: 120000 })
    if (data.queued) ElMessage.success('已安排执行器领取，请在记录中跟踪结果')
    else if (data.busy) ElMessage.info('任务正在执行')
    else if (data.errors?.length) { ElMessage.error('本轮存在处理失败，请查看记录'); openHistory({ ...task, lastError: data.errors.join('\n') }) }
    else ElMessage.success(`扫描完成：入库 ${data.importedFiles} 个，重复跳过 ${data.duplicateFiles} 个，等待文件稳定 ${data.pendingFiles} 个`)
    await load(true)
  } finally { busyCode.value = ''; busyAction.value = '' }
}
async function more(command, task) {
  if (busyCode.value) return
  if (command === 'delete') {
    try { await ElMessageBox.confirm(`删除“${task.name}”后将停止采集，已入库数据、历史记录和目录文件保留。`, '删除采集任务', { type: 'warning', confirmButtonText: '删除任务', cancelButtonText: '取消' }) } catch { return }
  }
  busyCode.value = task.code; busyAction.value = command
  try {
    if (command === 'toggle') {
      await request.put(`/collection/tasks/${encodeURIComponent(task.code)}/enabled`, { enabled: !isEnabled(task) })
      ElMessage.success(isEnabled(task) ? '已暂停任务，历史数据保留' : '已启用任务，将按计划采集')
    } else {
      await request.delete(`/collection/tasks/${encodeURIComponent(task.code)}`)
      ElMessage.success('任务已删除，历史数据保留')
    }
    await load(true)
  } finally { busyCode.value = ''; busyAction.value = '' }
}
onMounted(() => { load(); timer = setInterval(() => { if (!showForm.value && !showHistory.value && !busyCode.value) load() }, 30000) })
onUnmounted(() => { active = false; clearInterval(timer) })
</script>
<style scoped>
.collection-workspace{color:var(--el-text-color-primary)}.workspace-header,.header-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.workspace-header{margin-bottom:24px}.workspace-header h2{font-size:20px;margin:0 0 6px}.workspace-header p{margin:0;color:var(--el-text-color-secondary);font-size:13px}.refresh-time{color:var(--el-text-color-placeholder);font-size:12px}.overview-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:22px}.overview-card{display:flex;flex-direction:column;align-items:flex-start;gap:10px;background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:10px;padding:18px 20px;text-align:left;cursor:pointer;color:inherit;font-family:inherit}.overview-card:hover,.overview-card.active{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.overview-card>span{font-size:13px;color:var(--el-text-color-secondary)}.overview-card strong{font-size:28px;font-weight:600;line-height:1}.overview-card small{font-size:12px;color:var(--el-text-color-placeholder)}.primary{color:var(--el-color-primary)}.danger{color:var(--el-color-danger)}.muted{color:var(--el-text-color-secondary)}.task-panel{border:1px solid var(--el-border-color-lighter);border-radius:10px;background:var(--el-bg-color);padding:20px}.filters{display:flex;gap:12px;margin-bottom:20px;flex-wrap:wrap}.filters>.el-input{width:280px}.filters>.el-select{width:180px}.task-title{font-weight:500}.subline{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7;margin-top:4px}.row-actions{display:flex;align-items:center;gap:10px}.row-actions :deep(.el-button+.el-button){margin-left:0}.error-preview{font-size:12px;color:var(--el-color-danger);margin-top:5px;cursor:pointer}.pagination{justify-content:flex-end;margin-top:20px}.workspace-help{font-size:12px;color:var(--el-text-color-secondary);padding:20px 2px;line-height:1.8}.workspace-help strong{color:var(--el-text-color-primary);margin-right:14px}.workspace-help p{margin:5px 0 0}.notice{margin-bottom:20px}.desktop-table{min-height:160px}.mobile-tasks{display:none}.mobile-task{padding:16px;border:1px solid var(--el-border-color-lighter);border-radius:8px;line-height:1.9;font-size:13px}.mobile-title{display:flex;justify-content:space-between;gap:12px}.mobile-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.mobile-actions :deep(.el-button){margin-left:0}@media(max-width:900px){.refresh-time{display:none}.overview-cards{gap:10px}.overview-card{padding:14px}.task-panel{padding:14px}}@media(max-width:768px){.workspace-header{align-items:flex-start}.header-actions{flex-wrap:wrap;justify-content:flex-end}.overview-cards{grid-template-columns:repeat(2,minmax(0,1fr))}.desktop-table{display:none}.mobile-tasks{display:grid;gap:12px}.filters>.el-input{width:100%}.filters>.el-select{width:calc(50% - 6px)}.pagination{justify-content:center}.workspace-help span{display:block}}
</style>
