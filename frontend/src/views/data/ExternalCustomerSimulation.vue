<template>
  <section v-loading="loading">
    <div class="content-area">

      <div class="data-area">
        <div class="filter-row" v-balanced-query><WorkspaceSectionHeading :title="`可选指标列表（${subCategory}）`" icon="DataAnalysis" /><WorkspaceMetricPicker v-model="selected" :items="metrics" @change="page = 1" @select-all="selectAll" @invert="invert" />
          <CollectTimeFilter
            v-model:date="date"
            v-model:data-time="dataTime"
            :times="availableTimes"
            :loading="loading"
            :hint="availableTimes.length > 1 ? `当日 ${availableTimes.length} 批` : ''"
            @change="query"
          />
          <el-input v-model="search" placeholder="指标名称" clearable size="small" style="width: 160px" @keyup.enter="query" />
          <el-button type="primary" size="small" :disabled="busy" @click="query">查询</el-button>
        </div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div class="data-meta">统计日期：{{ date || '全部' }} · {{ filtered.length }} 条记录</div>
        <div class="desktop-table"><el-table v-sticky-header v-mirror-scroll :data="visible" border stripe size="small">
          <el-table-column prop="importedAt" label="入库时间" width="170" class-name="workspace-time-cell" /><el-table-column prop="metricType" label="指标类型" width="110"><template #default="{ row }"><span class="workspace-category-chip">{{ row.metricType || '—' }}</span></template></el-table-column><el-table-column prop="metricName" label="指标名称" min-width="200" />
          <el-table-column label="指标值" width="110" align="right"><template #default="{ row }"><span class="workspace-metric-value">{{ formatValue(row.metricValue) }}</span></template></el-table-column><el-table-column prop="unit" label="单位" width="60" />
          <el-table-column prop="businessDate" label="统计日期" width="120" /><el-table-column v-if="availableTimes.length > 1" prop="dataTime" label="数据时间" width="170" class-name="workspace-time-cell" />
        </el-table></div>
        <div class="mobile-cards"><el-card v-for="row in visible" :key="`${row.businessDate}-${row.metricCode}`" shadow="never"><strong>{{ row.metricName }}</strong><p>{{ formatValue(row.metricValue) }} {{ row.unit }}</p><div>{{ row.metricType }} · {{ row.businessDate }}</div><div>入库时间：{{ row.importedAt }}</div></el-card></div>
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="filtered.length" :page-sizes="[10,20,50,100]" :layout="width < 768 ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper'" class="pagination" @size-change="page = 1" />
      </div>
    </div>
  </section>
</template>
<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import WorkspaceMetricPicker from '@/components/workspace/WorkspaceMetricPicker.vue'
import CollectTimeFilter from '@/components/workspace/CollectTimeFilter.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import request from '../../api'
import { formatSatisfactionValue as formatValue } from '../../utils/satisfactionReport'
const props = defineProps({ subCategory: { type: String, required: true } })
const rows = ref([]), metrics = ref([]), selected = ref([]), date = ref(''), dataTime = ref(''), search = ref(''), keyword = ref(''), page = ref(1), size = ref(10)
const availableTimes = ref([])
const loading = ref(false), busy = ref(false), error = ref(''), width = ref(window.innerWidth)
const dates = computed(() => [...new Set(rows.value.map(row => row.businessDate))].sort().reverse())
const filtered = computed(() => rows.value.filter(row => (!date.value || row.businessDate === date.value) && selected.value.includes(row.metricCode) && (!keyword.value || row.metricName.includes(keyword.value))))
const visible = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
const selectAll = () => { selected.value = metrics.value.map(row => row.metricCode); page.value = 1 }
const invert = () => { selected.value = metrics.value.filter(row => !selected.value.includes(row.metricCode)).map(row => row.metricCode); page.value = 1 }
async function load(initial = false) {
  loading.value = true; error.value = ''
  try {
    const response = await request.get('/data/external-customer', {
      params: { subCategory: props.subCategory, date: date.value || undefined, dataTime: dataTime.value || undefined }
    })
    rows.value = response.data.rows; metrics.value = response.data.metrics
    availableTimes.value = response.data.availableTimes || []
    if (initial) { selectAll(); date.value = dates.value[0] || '' }
  } catch { error.value = '数据加载失败，请稍后重试。'; rows.value = [] }
  finally { loading.value = false }
}
async function query() { keyword.value = search.value.trim(); page.value = 1; await load() }
const resize = () => { width.value = window.innerWidth }
onMounted(() => { window.addEventListener('resize', resize); load(true) })
onUnmounted(() => window.removeEventListener('resize', resize))
</script>
<style scoped>
.content-area { display: flex; gap: 10px; margin-top: 10px; }.metric-sidebar { width: 220px; flex-shrink: 0; align-self: flex-start; padding: 8px; background: var(--ui-page); border: 1px solid var(--ui-border-soft); border-radius: 4px; }
.sidebar-header { display: flex; justify-content: space-between; align-items: center; color: var(--ui-muted); font-size: 12px; margin-bottom: 6px; }.sidebar-header .el-button { margin-left: 4px; }
.metric-sidebar .el-checkbox-group { display: flex; flex-direction: column; }.metric-sidebar :deep(.el-checkbox) { margin-right: 0; height: auto; min-height: 28px; }.metric-sidebar :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; line-height: 1.5; font-size: 12px; }
.data-area { flex: 1; min-width: 0; }.filter-row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }.data-meta { color: var(--ui-muted); font-size: 12px; margin-bottom: 8px; }.pagination { justify-content: flex-end; margin-top: 10px; }.mobile-cards { display: none; }.mobile-cards .el-card { margin-bottom: 8px; font-size: 13px; }
@media(max-width:767px) { .content-area { flex-direction: column; }.metric-sidebar { width: 100%; box-sizing: border-box; }.desktop-table { display: none; }.mobile-cards { display: block; }.pagination { justify-content: center; } }
</style>
