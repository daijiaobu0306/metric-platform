<template>
  <el-dialog class="workspace-theme" v-model="visible" append-to-body :title="`${task?.name || ''} · 采集记录`" width="min(1180px, 96vw)" :close-on-click-modal="false">
    <div class="summary"><span>任务：{{ task?.code }}</span><span>已入库 {{ task?.batchCount ?? 0 }} 个批次</span><span>待归档 {{ task?.pendingArchives ?? 0 }} 个</span><el-button size="small" :loading="loading" @click="load">刷新记录</el-button></div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" class="notice" />
    <el-tabs v-model="tab" @tab-change="resetHistory"><el-tab-pane label="入库批次" name="batches"><template #label><CollectionFieldLabel label="入库批次" help="待归档表示数据已提交、文件归档状态尚待恢复，请勿手动删除源文件。" /></template></el-tab-pane><el-tab-pane label="文件处理记录" name="attempts"><template #label><CollectionFieldLabel label="文件处理记录" help="失败文件保留等待重试；相同内容与统计日期重复投递不会再次入库。" /></template></el-tab-pane><el-tab-pane v-if="isWorker(task)" label="执行过程" name="runs"><template #label><CollectionFieldLabel label="执行过程" help="扫描完成可能没有新文件，实际结果以入库批次为准；执行中断不等同于执行器离线。" /></template></el-tab-pane></el-tabs>
    <div class="filters"><el-input v-model="keyword" clearable :placeholder="tab === 'runs' ? '搜索执行器名称' : '搜索源文件名'" maxlength="200" @keyup.enter="search" @clear="search" /><el-select v-model="status" clearable placeholder="全部状态" @change="search"><el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-button @click="search">查询</el-button></div>
    <el-table v-loading="loading" :data="records" border :empty-text="error ? '加载失败，请重试' : '暂无符合条件的记录'">
      <template v-if="tab === 'batches'">
        <el-table-column prop="sourceFile" label="源文件" min-width="210" show-overflow-tooltip /><el-table-column prop="businessDate" label="数据日期" width="115" /><el-table-column prop="rowCount" label="结果条数" width="90" /><el-table-column label="入库时间" width="175"><template #default="{ row }">{{ formatTime(row.collectedAt) }}</template></el-table-column><el-table-column label="归档状态" width="110"><template #default="{ row }"><el-tag :type="row.archived ? 'success' : 'warning'">{{ row.archived ? '已归档' : '待恢复' }}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="task?.config.parser !== 'satisfaction-report'" link type="primary" @click="openRows(row)">查看数据</el-button><span v-else class="hint">满意度页查看</span></template></el-table-column>
      </template>
      <template v-else-if="tab === 'attempts'">
        <el-table-column prop="sourceFile" label="源文件" min-width="200" show-overflow-tooltip /><el-table-column label="处理结果" width="110"><template #default="{ row }"><el-tag :type="row.status === 'FAILED' ? 'danger' : row.status === 'IMPORTED' ? 'success' : 'info'">{{ attemptLabels[row.status] || row.status }}</el-tag></template></el-table-column><el-table-column label="原因" min-width="240"><template #default="{ row }"><el-button v-if="row.message" link type="primary" class="error-link" @click="showMessage(row.message)">{{ row.message }}</el-button><span v-else>—</span></template></el-table-column><el-table-column label="处理时间" width="175"><template #default="{ row }">{{ formatTime(row.attemptedAt) }}</template></el-table-column>
      </template>
      <template v-else>
        <el-table-column prop="workerId" label="执行器" min-width="140" /><el-table-column label="运行状态" width="110"><template #default="{ row }"><el-tag :type="['FAILED', 'EXPIRED'].includes(row.state) ? 'danger' : row.state === 'CLAIMED' ? 'primary' : 'success'">{{ runLabels[row.state] || row.state }}</el-tag></template></el-table-column><el-table-column prop="scriptVersion" label="插件版本" min-width="125" /><el-table-column label="开始时间" width="175"><template #default="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column><el-table-column label="结束时间" width="175"><template #default="{ row }">{{ formatTime(row.finishedAt) }}</template></el-table-column><el-table-column label="失败原因" min-width="150"><template #default="{ row }"><el-button v-if="row.lastError" link type="primary" @click="showMessage(row.lastError)">查看详情</el-button><span v-else>—</span></template></el-table-column>
      </template>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[10, 20, 50, 100]" :layout="paginationLayout" class="pagination" @current-change="load" @size-change="search" />
    <el-dialog class="workspace-theme" v-model="showError" append-to-body title="完整失败原因" width="min(800px, 94vw)"><pre class="detail">{{ fullMessage }}</pre></el-dialog>
    <el-dialog class="workspace-theme" v-model="showData" append-to-body :title="`${selectedBatch?.sourceFile || ''} · 原始数据`" width="min(1180px, 96vw)">
      <el-descriptions :column="2" border><el-descriptions-item label="批次">{{ selectedBatch?.batchId }}</el-descriptions-item><el-descriptions-item label="数据日期">{{ selectedBatch?.businessDate }}</el-descriptions-item></el-descriptions>
      <el-alert v-if="dataError" type="error" :title="dataError" :closable="false" class="notice" />
      <el-table v-loading="dataLoading" :data="rawRows" border empty-text="暂无通用原始行；专用报表请到对应业务页查看">
        <el-table-column type="expand"><template #default="{ row }"><pre class="detail">{{ JSON.stringify({ source: row.data._source, collection: row.data._collection }, null, 2) }}</pre></template></el-table-column><el-table-column prop="rowNumber" label="记录序号" width="120"><template #header><CollectionFieldLabel label="记录序号" help="零值显示为0，缺失显示为缺失；展开记录可查看来源位置与平台追溯信息。" /></template></el-table-column><el-table-column v-for="key in rawColumns" :key="key" :label="fieldLabels[key] || key" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ displayValue(row.data[key]) }}</template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="dataPage" v-model:page-size="dataSize" :total="dataTotal" :page-sizes="[10, 20, 50, 100]" :layout="paginationLayout" class="pagination" @current-change="loadRows" @size-change="dataPage = 1; loadRows()" />
    </el-dialog>
  </el-dialog>
</template>
<script setup>
import CollectionFieldLabel from './CollectionFieldLabel.vue'
import { ref, computed, watch } from 'vue'
import request from '../../../api'
import { isWorker, formatTime, runLabels, attemptLabels, displayValue } from '../../../utils/collectionTasks'
const props = defineProps({ modelValue: Boolean, task: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const tab = ref('batches'), records = ref([]), total = ref(0), page = ref(1), pageSize = ref(20), keyword = ref(''), status = ref(''), loading = ref(false), error = ref('')
const showError = ref(false), fullMessage = ref(''), showData = ref(false), selectedBatch = ref(null), dataPage = ref(1), dataSize = ref(20), dataTotal = ref(0), rawRows = ref([]), dataLoading = ref(false), dataError = ref('')
const paginationLayout = window.innerWidth < 768 ? 'prev, pager, next' : 'total, sizes, prev, pager, next'
const fieldLabels = { metric_code: '指标编码', metric_name: '指标名称', metric_value: '指标值', data_time: '数据时间', unit: '单位', category_name: '指标大类', sub_category_name: '指标小类' }
const rawColumns = computed(() => [...new Set(rawRows.value.flatMap(row => Object.keys(row.data)))].filter(key => !['_source', '_collection'].includes(key)))
const statuses = computed(() => tab.value === 'batches' ? [{ label: '已归档', value: '1' }, { label: '待归档', value: '0' }] : Object.entries(tab.value === 'runs' ? runLabels : attemptLabels).map(([value, label]) => ({ value, label })))
let historyRequest = 0, dataRequest = 0
watch(() => props.modelValue, opened => {
  if (!opened) { historyRequest++; dataRequest++; return }
  tab.value = props.task?.lastError ? 'attempts' : 'batches'; page.value = 1; keyword.value = ''; status.value = ''; records.value = []; total.value = 0; load()
})
function search() { page.value = 1; load() }
function resetHistory() { page.value = 1; status.value = ''; keyword.value = ''; load() }
async function load() {
  if (!props.task) return
  const id = ++historyRequest; loading.value = true; error.value = ''
  try {
    const { data } = await request.get(`/collection/tasks/${encodeURIComponent(props.task.code)}/history`, { params: { type: tab.value, page: page.value, size: pageSize.value, keyword: keyword.value.trim(), status: status.value } })
    if (id === historyRequest) { records.value = data.records; total.value = data.total }
  } catch (failure) { if (id === historyRequest) { error.value = failure.response?.data?.message || failure.message || '记录加载失败'; records.value = []; total.value = 0 } }
  finally { if (id === historyRequest) loading.value = false }
}
function showMessage(message) { fullMessage.value = message; showError.value = true }
function openRows(batch) { selectedBatch.value = batch; dataPage.value = 1; rawRows.value = []; showData.value = true; loadRows() }
async function loadRows() {
  const id = ++dataRequest; dataLoading.value = true; dataError.value = ''
  try {
    const { data } = await request.get(`/collection/tasks/batches/${encodeURIComponent(selectedBatch.value.batchId)}/rows-page`, { params: { page: dataPage.value, size: dataSize.value } })
    if (id !== dataRequest) return
    rawRows.value = data.records.map(row => ({ rowNumber: row.rowNumber, data: typeof row.dataJson === 'string' ? JSON.parse(row.dataJson) : row.dataJson })); dataTotal.value = data.total
  } catch (failure) { if (id === dataRequest) { dataError.value = failure.response?.data?.message || failure.message || '原始数据加载失败'; rawRows.value = []; dataTotal.value = 0 } }
  finally { if (id === dataRequest) dataLoading.value = false }
}
</script>
<style scoped>
.summary{display:flex;gap:20px;align-items:center;flex-wrap:wrap;color:var(--el-text-color-secondary);font-size:13px}.summary>.el-button{margin-left:auto}.filters{display:flex;gap:12px;flex-wrap:wrap}.filters>.el-input{width:280px}.filters>.el-select{width:180px}.hint{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7}.notice{margin:16px 0}.pagination{margin-top:18px;justify-content:flex-end}.error-link{max-width:100%;display:block;text-align:left;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.detail{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.7;max-height:60vh;overflow:auto;padding:0 16px}@media(max-width:768px){.filters>.el-input{width:100%}.summary{gap:8px 16px}.pagination{justify-content:center}}
</style>
