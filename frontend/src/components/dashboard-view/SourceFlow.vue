<template><WorkspaceDataFlow dedicated :data-sources="dataSources" :active-source="activeSource" :metric-count="metricCount" :health-chart-count="healthCount" :pipeline-status="pipelineStatus" :coverage-rate="coverageRate" :import-cards="importCards" :pending-cards="pendingCards" @source-select="emit('select-source', activeSource === $event ? '' : $event)" @navigate="emit('navigate', $event)" /></template>
<script setup>
import { computed } from 'vue'
import WorkspaceDataFlow from '@/components/workspace/WorkspaceDataFlow.vue'
const props = defineProps({
  sources: { type: Array, default: () => [] },
  dataSummary: { type: Object, default: () => ({}) },
  metricCount: { type: Number, default: 0 },
  healthCount: { type: Number, default: 0 },
  alarmPending: { type: Number, default: 0 },
  issuePending: { type: Number, default: 0 },
  gongdanPending: { type: Number, default: 0 },
  activeSource: { type: String, default: '' }
})
const emit = defineEmits(['select-source','navigate'])
const dataSources = computed(() => {
  const map = new Map()
  ;(props.sources || []).forEach(row => {
    const name = row.systemName || '未授权系统'
    const item = map.get(name) || { name, count: 0 }
    item.count++
    map.set(name, item)
  })
  return [...map.values()].sort((a, b) => b.count - a.count).map((item, index) => ({
    ...item,
    icon: item.name.includes('CRM') ? 'User' : item.name.includes('BOSS') ? 'Box' : item.name.includes('稽核') ? 'Monitor' : item.name.includes('端到端') ? 'DataLine' : item.name.includes('BOMC') ? 'Operation' : 'Monitor',
    subtitle: `${item.count} 项授权指标`,
    tone: ['blue', 'cyan', 'purple', 'green', 'orange', 'blue'][index % 6]
  }))
})
const calculatedCount = computed(() => (props.sources || []).filter(row => row.latestCalculationTime).length)
// 2026-10-28：metric_item 的 calc_source_table / calc_field 列已删除，
// 统一由 data_source 承载数据来源（合并 main 时新组件仍读旧字段，此处对齐）
const dataSourceCount = computed(() => new Set((props.sources || []).map(row => row.dataSource).filter(Boolean)).size)
const coverageRate = computed(() => props.metricCount ? Math.min(100, Math.round(props.healthCount * 100 / props.metricCount)) : 0)
const pipelineStatus = computed(() => computedCount.value > 0 ? '部分链路运行' : '等待接入')
const computedCount = computed(() => (props.sources || []).filter(row => row.latestCalculationTime).length)
const importCards = computed(() => [
  {label:'当前范围数据量',value:props.dataSummary?.records,unit:'条',icon:'Coin'},
  {label:'数据来源',value:dataSourceCount.value,unit:'个',icon:'Document'},
  {label:'已产生计算',value:calculatedCount.value,unit:'项',icon:'Grid'}
])
const pendingCards = computed(() => [
  {label:'告警',value:props.alarmPending,status:'待处理',caption:'当前待处理',icon:'Bell',className:'alarm-stat-card',path:'/alarm'},
  {label:'问题',value:props.issuePending,status:'未解决',caption:'当前未解决',icon:'Warning',className:'issue-stat-card',path:'/issue'},
  {label:'工单',value:props.gongdanPending,status:'未闭环',caption:'当前未闭环',icon:'Document',className:'gongdan-stat-card',path:'/gongdan'}
])
</script>
