<template>
  <section class="panel data-convergence-panel">
    <div class="convergence-head">
      <div><h3>数据汇聚 · {{ sourceName || '请选择数据源' }}</h3><small>{{ subtitle }}</small></div>
      <el-tag v-if="mapping.table" size="small" type="success" effect="plain">{{ mapping.table }}</el-tag>
      <el-tag v-else-if="sourceName" size="small" type="info" effect="plain">模拟 / 指标采集</el-tag>
    </div>

    <template v-if="sourceName">
      <div class="convergence-context">
        <span>来源系统 <b>{{ sourceName }}</b></span>
        <span>汇聚分类 <b>{{ mapping.category || '未映射' }} / {{ mapping.sub || '-' }}</b></span>
        <span>原始表 <b>{{ mapping.table || '未配置' }}</b></span>
        <span>数据清单 <b>{{ total.toLocaleString('en-US') }} 条</b></span>
      </div>

      <template v-if="mapping.table">
        <div class="convergence-toolbar">
          <el-input v-model="keyword" clearable placeholder="在当前页数据中搜索" />
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
        <el-table v-loading="loading" :data="displayRows" stripe border size="small" max-height="430" class="convergence-table" empty-text="当前来源暂无可展示的数据记录">
          <el-table-column v-for="column in columns" :key="column" :label="columnLabel(column)" :min-width="columnWidth(column)" show-overflow-tooltip>
            <template #default="{ row }">{{ cell(row[column]) }}</template>
          </el-table-column>
        </el-table>
        <div class="convergence-pager">
          <span>当前页 {{ displayRows.length }} 条 · 共 {{ total }} 条</span>
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50]" :total="total" layout="total, sizes, prev, pager, next" background @current-change="load" @size-change="onSizeChange" />
        </div>
      </template>
      <el-empty v-else description="该来源在数据汇聚页使用模拟采集/指标采集，未配置原始明细表；可在数据汇聚页查看模拟数据。" :image-size="76" />
    </template>
    <el-empty v-else description="点击上方平台数据流中的数据源卡片，查看对应数据汇聚源清单" :image-size="76" />
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { dataApi } from '../../api'

const props = defineProps({
  sourceName: { type: String, default: '' }
})
const SOURCE_MAP = {
  CRM: { category: '对外部客户', sub: '流量提醒', table: '', columns: [] },
  BOSS: { category: '对外部客户', sub: '缴费开机', table: '', columns: [] },
  稽核系统: { category: '合规管理', sub: '月数据清单', table: 'system_jhd', columns: ['check_code', 'check_name', 'business_owner', 'data_time', 'check_freq', 'abnormal_date'] },
  端到端监控: { category: '对内部人员', sub: '查询类', table: 'inner_query', columns: ['insert_time', 'business_name', 'step_type', 'step_name', 'data_time'] },
  BOMC: { category: '计划执行', sub: '工单执行', table: '', columns: [] }
}
const mapping = computed(() => SOURCE_MAP[props.sourceName] || { category: '', sub: '', table: '', columns: [] })
const subtitle = computed(() => props.sourceName
  ? (mapping.value.table ? `数据汇聚源明细 · 对应 ${mapping.value.category} / ${mapping.value.sub}` : `数据汇聚源配置 · 对应 ${mapping.value.category || '-'} / ${mapping.value.sub || '-'}`)
  : '选择数据源后直接查看对应的数据汇聚源清单')
const rows = ref([]), total = ref(0), page = ref(1), pageSize = ref(10), keyword = ref(''), loading = ref(false), error = ref('')
const displayRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(row => Object.values(row).some(value => String(value ?? '').toLowerCase().includes(q)))
})
const columns = computed(() => {
  const preferred = mapping.value.columns || []
  const keys = new Set(rows.value.flatMap(row => Object.keys(row)))
  return preferred.filter(key => keys.has(key)).concat([...keys].filter(key => !preferred.includes(key) && !['id'].includes(key))).slice(0, 9)
})
const LABELS = {
  check_code: '稽核编码', check_name: '稽核名称', business_owner: '业务负责人', data_time: '数据时间', check_freq: '稽核频率', abnormal_date: '异常日期',
  insert_time: '入库时间', business_name: '业务名称', step_type: '步骤类型', step_name: '步骤名称', metric_value: '指标值', value: '值', status: '状态',
  'close order count': '关单量', 'verify result': '核查结果', 'rectif complete count': '整改完成数', 'check rule': '稽核规则'
}
function columnLabel (key) { return LABELS[key] || String(key).replaceAll('_', ' ') }
function columnWidth (key) { return key === 'check_name' || key === 'business_name' ? 210 : 130 }
function cell (value) {
  if (value == null || value === '') return '--'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value).replace('T', ' ').slice(0, 80)
}
async function load () {
  rows.value = []
  total.value = 0
  if (!mapping.value.table) return
  loading.value = true
  error.value = ''
  try {
    const res = await dataApi.getDetail({ page: page.value - 1, size: pageSize.value, tableName: mapping.value.table, subCategoryName: mapping.value.sub })
    const payload = res?.data || res || {}
    rows.value = payload.content || payload.rows || []
    total.value = Number(payload.total || 0)
  } catch (e) {
    error.value = e?.message || '加载数据汇聚源失败'
    rows.value = []
    total.value = 0
  } finally { loading.value = false }
}
function reset () { keyword.value = ''; page.value = 1; load() }
function onSizeChange () { page.value = 1; load() }
watch(() => props.sourceName, () => { page.value = 1; keyword.value = ''; load() }, { immediate: true })
</script>

<style scoped>
.data-convergence-panel { padding: 12px 14px; }
.convergence-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.convergence-head h3 { margin: 0; color: var(--metric-text); font-size: 13px; }
.convergence-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 9px; }
.convergence-context { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 7px 9px; margin-bottom: 8px; border-radius: 6px; background: var(--metric-surface-soft); color: var(--metric-text-muted); font-size: 9px; }
.convergence-context b { margin-left: 3px; color: var(--metric-text); font-weight: 600; }
.convergence-toolbar { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.convergence-toolbar .el-input { width: 240px; }
.convergence-table :deep(.el-table__cell) { padding: 6px 0; }
.convergence-pager { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; color: var(--metric-text-muted); font-size: 9px; }
</style>
