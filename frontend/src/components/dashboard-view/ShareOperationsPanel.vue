<template>
  <div v-if="operations" class="operations" :class="{ compact }">
    <section class="operation-panel">
      <h3>平台运行</h3>
      <div class="operation-counts">
        <KpiCard label="启用用户" :value="operations.users?.enabled ?? 0" />
        <KpiCard label="启用接口" :value="operations.interfaces?.enabled ?? 0" />
        <KpiCard label="计算指标" :value="operations.calculations?.metrics ?? 0" />
      </div>
    </section>
    <section v-for="group in groups" :key="group.key" class="operation-panel">
      <h3>{{ group.title }}</h3>
      <ShareMobileList :rows="operations[group.key] || []" :fields="group.fields" />
    </section>
    <p v-if="operations.notice" class="note">{{ operations.notice }}</p>
  </div>
</template>

<script setup>
import KpiCard from './ui/KpiCard.vue'
import ShareMobileList from './ShareMobileList.vue'

defineProps({
  operations: Object,
  compact: Boolean
})

const groups = [
  { key: 'usage', title: '用户使用', fields: [
    { label: '日期', value: 'day' }, { label: '活跃用户', value: 'activeUsers' }, { label: '登录次数', value: 'sessions' }
  ] },
  { key: 'imports', title: '数据导入', fields: [
    { label: '来源表', value: 'name' }, { label: '批次', value: 'batches' }, { label: '记录数', value: 'records' }, { label: '最近导入', value: 'latest' }
  ] },
  { key: 'scripts', title: '脚本执行', fields: [
    { label: '状态', value: 'status' }, { label: '执行次数', value: 'count' }, { label: '平均耗时（ms）', value: 'averageMs' }, { label: '最近执行', value: 'latest' }
  ] },
  { key: 'calls', title: '接口调用', fields: [
    { label: '业务类型', value: 'businessType' }, { label: '调用次数', value: 'count' }, { label: '状态', value: 'status' }, { label: '最近调用', value: 'latest' }
  ] },
  { key: 'features', title: '功能使用', fields: [
    { label: '模块', value: 'module' }, { label: '操作次数', value: 'count' }, { label: '操作类型', value: 'operation' }
  ] }
]
</script>

<style scoped>
.operations { display: grid; gap: 8px; }
.operation-panel { min-width: 0; padding: 12px; border: 1px solid var(--metric-border); border-radius: 8px; background: #fff; }
h3 { margin: 0 0 6px; font-size: 14px; }
.operation-counts { display: grid; gap: 4px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.note { margin: 0; color: var(--metric-text-muted); font-size: 12px; }
.compact .operation-panel { padding: 8px 10px; }
.compact .operation-counts :deep(.metric-kpi-card) { gap: 2px; min-height: 0; padding: 6px; box-shadow: none; }
.compact .operation-counts :deep(.metric-bar) { display: none; }
.compact .operation-counts :deep(.value) { font-size: 16px; }
</style>
