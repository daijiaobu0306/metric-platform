<template>
  <section class="event-panel">
    <header>
      <div><h3>{{ label }}明细</h3><small>按当前时间范围与授权范围展示；关键词和状态作用于当前页</small></div>
      <el-button link @click="$emit('close')">收起</el-button>
    </header>
    <div class="event-filters" v-balanced-query>
      <el-input v-model="keyword" clearable placeholder="按内容或责任人搜索" />
      <el-select v-model="state" clearable placeholder="处理状态">
        <el-option label="待处理" value="pending" />
        <el-option label="已完成" value="done" />
      </el-select>
      <el-button @click="load">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="filteredRows" stripe size="small" max-height="360" empty-text="当前条件下暂无明细">
      <el-table-column prop="title" label="内容" min-width="260" show-overflow-tooltip />
      <el-table-column prop="owner" label="责任人" width="120" />
      <el-table-column label="发生时间" width="170"><template #default="{ row }">{{ time(row.time) }}</template></el-table-column>
      <el-table-column v-if="kind === 'issue'" label="预计解决" width="170"><template #default="{ row }">{{ time(row.dueTime) }}</template></el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"><el-tag :type="row.done ? 'success' : 'warning'">{{ row.done ? '已完成' : '待处理' }}</el-tag></template>
      </el-table-column>
    </el-table>
    <div class="event-pager">
      <span>共 {{ total }} 条</span>
      <el-pagination
        :current-page="page"
        :page-size="20"
        :total="total"
        layout="prev, pager, next"
        small
        @current-change="changePage"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { screenApi, message } from './api'

const props = defineProps({
  kind: { type: String, required: true },
  code: { type: String, required: true },
  days: { type: Number, default: 7 },
  supervisorId: { type: [Number, String], default: null }
})
defineEmits(['close'])

const loading = ref(false), rows = ref([]), total = ref(0), page = ref(1)
const keyword = ref(''), state = ref('')
const label = computed(() => ({ alarm: '告警', issue: '问题', gongdan: '工单' }[props.kind] || '事项'))
const filteredRows = computed(() => rows.value.filter(row => {
  const text = `${row.title || ''}${row.owner || ''}`
  return (!keyword.value || text.includes(keyword.value)) && (!state.value || (state.value === 'done') === !!row.done)
}))
const time = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'

async function load () {
  loading.value = true
  try {
    const data = await screenApi.events(props.code, {
      kind: props.kind,
      days: props.days,
      page: page.value - 1,
      supervisorId: props.supervisorId || undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (error) { ElMessage.error(message(error)) } finally { loading.value = false }
}
function changePage (next) { page.value = next; load() }
watch(() => [props.kind, props.days, props.supervisorId], () => { page.value = 1; load() })
onMounted(load)
</script>

<style scoped>
.event-panel { margin-top: 12px; padding: 14px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.event-panel header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.event-panel h3 { margin: 0; color: var(--metric-text); font-size: 14px; }
.event-panel header small { display: block; margin-top: 4px; color: var(--metric-text-muted); font-size: 10px; }
.event-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.event-filters .el-input { width: 260px; }
.event-filters .el-select { width: 130px; }
.event-pager { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: var(--metric-text-muted); font-size: 11px; }
@media (max-width: 680px) { .event-filters .el-input, .event-filters .el-select { width: 100%; } }
</style>
