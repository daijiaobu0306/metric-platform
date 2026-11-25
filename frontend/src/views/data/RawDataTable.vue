<template>
  <section class="collected-workspace" v-loading="loading">
    <el-alert v-if="preview" title="本地样例数据" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <nav v-if="groups.length && !flatDataQuality" class="subcategory-nav surface" aria-label="指标小类">
      <button v-for="name in groups" :key="name" type="button"
              :aria-pressed="subcategory === name" @click="selectSubcategory(name)">
        <span class="data-subcategory-label"><i aria-hidden="true" />{{ isResource ? formatResourceSubcategory(name) : name }}</span>
      </button>
    </nav>
    <div v-if="metrics.length" class="metric-panel surface">
      <div class="panel-label">指标类型</div>
      <div class="metric-options">
        <div class="metric-selection" role="group" aria-label="指标类型">
          <el-checkbox v-for="metric in metrics" :key="metric.id" size="small" :model-value="selectedIds.includes(metric.id)" @change="checked => selectMetric(metric.id, checked)">
            {{ metric.name }}
          </el-checkbox>
        </div>
      </div>
    </div>
    <div v-if="metrics.length" class="filter-row">
      <div class="filters">
        <el-date-picker v-if="isResource" size="small" v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" @change="query" />
        <CollectTimeFilter
          v-else
          v-model:date="date"
          v-model:data-time="dataTime"
          :times="availableTimes"
          :loading="timesLoading"
          :hint="timeHint"
          @change="query"
        />
        <el-input size="small" v-model="keyword" clearable :placeholder="searchPlaceholder" @keyup.enter="query" @clear="query" />
        <el-button size="small" type="primary" @click="query">查询</el-button><el-button size="small" @click="reset">重置</el-button>
      </div>
    </div>
    <div v-if="!selectedIds.length && metrics.length" class="surface metric-panel">请选择指标</div>
    <div v-if="flatDataQuality && selectedIds.length" class="surface metric-value-table">
      <el-table :data="dataQualityRows" row-key="id" border stripe size="small" empty-text="当前条件下无匹配指标">
        <el-table-column prop="name" label="指标名称" min-width="180" />
        <el-table-column prop="subcategory" label="指标小类" min-width="170" />
        <el-table-column prop="value" label="指标值" min-width="100" />
        <el-table-column prop="unit" label="单位" min-width="80" />
        <el-table-column prop="dataTime" label="数据时间" min-width="160" />
      </el-table>
    </div>
    <section v-for="entry in detailResults" :key="entry.id" class="metric-result">
      <h2 class="metric-result-title">{{ entry.name }}</h2>
      <el-alert v-if="entry.error" :title="entry.error" type="error" :closable="false" />
      <el-alert v-if="!isResource && entry.data.note && entry.data.dataTime" :title="entry.data.note" type="info" :closable="false" class="note" />
      <div class="desktop-table surface">
        <el-table :data="entry.data.rows || []" border stripe size="small" :empty-text="entry.error ? '数据暂不可用' : '当前条件下无匹配记录'" max-height="600">
          <el-table-column v-for="column in columnsFor(entry.data)" :key="column" :label="column" :min-width="column === '入库时间' ? 160 : /量|次数|时长|耗时/.test(column) ? 110 : 160" show-overflow-tooltip>
            <template #default="{ row }">{{ cellValue(row, column, entry.data) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="mobile-cards">
        <el-card v-for="(row,index) in entry.data.rows || []" :key="index" shadow="never"><div v-for="column in columnsFor(entry.data)" :key="column"><strong>{{ column }}</strong>：{{ cellValue(row, column, entry.data) }}</div></el-card>
        <el-empty v-if="!entry.data.rows?.length" description="当前条件下无匹配记录" />
      </div>
      <el-pagination v-if="entry.data.total > 0" :current-page="entry.page" :page-size="size" :total="entry.data.total" layout="total, prev, pager, next" @current-change="value => changePage(entry.id, value)" />
    </section>
  </section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import CollectTimeFilter from '@/components/workspace/CollectTimeFilter.vue'
import request from '../../api'
import { resourceInputDates, formatResourceSubcategory } from '../../utils/resourceInputDates'
const props = defineProps({ categoryName: { type: String, required: true } })
const isResource = computed(() => props.categoryName === '资源投入')
const dateRange = ref(resourceInputDates())
const preview = import.meta.env.DEV && ['对内部人员', '运行效率'].includes(props.categoryName)
  && new URLSearchParams(window.location.search).get('sample') === '1'
async function readData(endpoint, options) {
  if (isResource.value) return request.get('/collection/resource-input/' + endpoint, options)
  if (!preview) return request.get('/collection/raw-data/' + endpoint, options)
  const { sampleData } = await import('./rawDataPreview.js')
  return { data: sampleData(endpoint, options.params) }
}
const catalog = ref([]), subcategory = ref(''), selectedIds = ref([]), date = ref(''), dataTime = ref(''), keyword = ref('')
const size = ref(50), results = ref({}), loading = ref(false), error = ref('')
const availableTimes = ref([]), timesLoading = ref(false)
let requestVersion = 0
// 只有多批观测时才提示可切换时间点，日粒度大类不需要这行说明。
const timeHint = computed(() => (availableTimes.value.length > 1 ? `当日 ${availableTimes.value.length} 批` : ''))
const groups = computed(() => {
  const names = new Set(catalog.value.map(metric => metric.subcategory || '未配置小类'))
  const priority = props.categoryName === '对内部人员'
    ? ['查询类', '办理类-单业务', '办理类-融合业务']
    : props.categoryName === '运行效率' ? ['系统性能', '系统异常'] : isResource.value ? ['需求开发-boss', '需求开发-bass'] : []
  return [...new Set([...priority, ...names])]
    .filter(name => names.has(name) && !(isResource.value && name === '需求开发'))
})
const flatDataQuality = computed(() => props.categoryName === '数据质量'
  && catalog.value.length > 0 && groups.value.length === catalog.value.length)
const metrics = computed(() => flatDataQuality.value ? catalog.value
  : catalog.value.filter(m => (m.subcategory || '未配置小类') === subcategory.value))
const singleOnly = computed(() => props.categoryName === '运行效率' && ['系统性能', '系统异常'].includes(subcategory.value))
const selectedResults = computed(() => metrics.value.filter(m => selectedIds.value.includes(m.id)).map(m => ({ id: m.id, name: m.name, page: results.value[m.id]?.page || 1, data: results.value[m.id]?.data || {}, error: results.value[m.id]?.error || '' })))
const detailResults = computed(() => flatDataQuality.value ? [] : selectedResults.value)
const dataQualityRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return metrics.value
    .filter(metric => selectedIds.value.includes(metric.id)
      && [metric.name, metric.subcategory].some(value => value?.toLowerCase().includes(search)))
    .map(metric => {
      const result = results.value[metric.id]
      return {
        id: metric.id,
        name: metric.name,
        subcategory: metric.subcategory || '未配置小类',
        value: result?.error ? '读取失败' : result?.data.rows?.[0]?.['指标值'] ?? '—',
        unit: metric.unit || '—',
        dataTime: formatTime(result?.data.dataTime)
      }
    })
})
const searchPlaceholder = computed(() => flatDataQuality.value ? '搜索指标名称、小类'
  : props.categoryName === '对内部人员' ? '业务名称、步骤名称等' : props.categoryName === '运行效率' ? '应用、接口、异常或指标名称' : '搜索当前指标数据')
function columnsFor(data) {
  // 资源投入是「去年到今年」的历史累计数据，数据日期不准确，不展示
  if (isResource.value) return (data.columns || []).filter(column => column !== '数据日期')
  const columns = (data.columns || []).filter(column => column !== '入库时间')
  return data.collectedAt ? ['入库时间', ...columns] : columns
}
function cellValue(row, column, data) {
  if (isResource.value && column === '指标小类') return formatResourceSubcategory(row[column]) || '—'
  const value = column === '入库时间' && !isResource.value ? formatTime(data.collectedAt) : row[column]
  return value == null || value === '' ? '—' : value
}
const formatTime = v => v ? String(v).replace('T',' ').slice(0,19) : '—'
async function load() {
  const version = ++requestVersion
  error.value = ''
  if (!selectedIds.value.length) { results.value = {}; loading.value = false; return }
  loading.value = true
  if (!isResource.value) timesLoading.value = true
  const ids = [...selectedIds.value]
  const readMetric = async id => {
    const currentPage = results.value[id]?.page || 1
    try {
      const { data } = await readData('records', { timeout: 60000, params: { category: props.categoryName, metricId: id, date: date.value || undefined, dataTime: dataTime.value || undefined, startDate: isResource.value ? dateRange.value?.[0] : undefined, endDate: isResource.value ? dateRange.value?.[1] : undefined, keyword: flatDataQuality.value ? undefined : keyword.value.trim() || undefined, page: currentPage, size: flatDataQuality.value ? 1 : size.value } })
      return [id, { page: currentPage, data: data || {}, error: '' }]
    } catch (e) { return [id, { page: currentPage, data: {}, error: e.message || '原始数据加载失败' }] }
  }
  const entries = []
  for (let i = 0; i < ids.length; i += 5) {
    entries.push(...await Promise.all(ids.slice(i, i + 5).map(readMetric)))
    if (version !== requestVersion) return
  }
  if (version === requestVersion) {
    results.value = Object.fromEntries(entries)
    loading.value = false
    syncAvailableTimes(entries)
  }
}

/**
 * 时间点候选由后端按指标返回。多指标勾选时合并所有指标的 data_time 并集，
 * 这样切换时间点对每个指标都取同一时刻，符合"按批次看数据"的直觉。
 */
function syncAvailableTimes(entries) {
  const times = new Set()
  for (const [, entry] of entries) {
    for (const time of entry.data?.availableTimes || []) times.add(time)
  }
  // 多指标勾选时取并集：同一时刻对每个指标分别取其当批观测，缺批的指标显示无数据。
  availableTimes.value = [...times].sort().reverse()
  timesLoading.value = false
}
function selectMetric(id, checked) {
  if (singleOnly.value) selectedIds.value = [id]
  else selectedIds.value = checked ? [...selectedIds.value, id] : selectedIds.value.filter(value => value !== id)
  query()
}
function query() {
  results.value = {}
  load()
}
function changePage(id, value) { results.value[id] = { ...results.value[id], page: value }; load() }
function reset() {
  date.value = ''; dataTime.value = ''; availableTimes.value = []
  dateRange.value = resourceInputDates(); keyword.value = ''
  query()
}
function selectSubcategory(name) {
  if (!name || name === subcategory.value) return
  subcategory.value = name
  // 换小类即换指标集，原时间点归属旧指标，清空后按新指标重新取候选。
  dataTime.value = ''; availableTimes.value = []
  selectedIds.value = singleOnly.value ? metrics.value.slice(0, 1).map(m => m.id) : metrics.value.map(m => m.id)
  keyword.value = ''
  query()
}
watch(() => props.categoryName, async category => {
  const version = ++requestVersion
  catalog.value = []; results.value = {}; selectedIds.value = []; error.value = ''; loading.value = true
  try {
    const { data } = await readData('catalog', { params: { category } })
    if(version !== requestVersion) return
    catalog.value = data || []
    subcategory.value = ''; date.value = ''; dataTime.value = ''; availableTimes.value = []
    dateRange.value = resourceInputDates()
    if (groups.value.length) selectSubcategory(groups.value[0])
    else loading.value = false
  } catch(e) { if(version === requestVersion) { error.value = e.message || '指标目录加载失败'; loading.value = false } }
}, { immediate: true })
</script>
<style scoped>
.collected-workspace {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.surface {
  min-width: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 14px;
  background: var(--el-bg-color);
}
.metric-panel { padding: 12px 16px; }
.metric-result { display: grid; gap: 8px; min-width: 0; }
.metric-result + .metric-result { margin-block-start: 8px; }
.metric-result-title {
  margin: 0;
  padding-inline-start: 10px;
  border-inline-start: 3px solid var(--el-color-primary);
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 700;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
.filter-row { margin: 0; }
.panel-label { color: var(--el-text-color-regular); font-size: 13px; }
.metric-panel .metric-selection { display: flex; flex-wrap: wrap; gap: 4px 20px; }
.metric-panel .el-checkbox { margin: 0; max-width: 100%; height: auto; min-height: 24px; white-space: normal; }
.filters { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.filters > .el-input { flex: 1 1 240px; width: auto; }
.filters > .el-date-editor { flex: 0 1 260px; }
.filters > .el-button { margin: 0; }
.meta { display: flex; flex-wrap: wrap; gap: 16px; font-size: 13px; color: var(--el-text-color-secondary); }
.desktop-table, .metric-value-table { overflow: hidden; }
.desktop-table :deep(th.el-table__cell), .metric-value-table :deep(th.el-table__cell) { background: var(--el-fill-color-light); }
.mobile-cards { display: none; }
.el-pagination { justify-content: flex-end; }
.mobile-cards .el-card { margin-bottom: 10px; line-height: 1.8; }
@media (max-width: 767px) {
  .desktop-table { display: none; }
  .mobile-cards { display: block; }
  .filters > .el-input, .filters > .el-date-editor { flex: 1 1 100%; }
  .el-pagination { justify-content: center; }
}
</style>
