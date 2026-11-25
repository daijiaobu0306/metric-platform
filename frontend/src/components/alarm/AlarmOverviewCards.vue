<template><section class="alarm-overview" aria-label="告警统计与快捷筛选">
<WorkspaceStatCard label="总触发次数" badge="历史累计" :value="stats.totalCount" icon="Bell" unit="次" hint="查看全部告警" @click="emit('time-range','all')" />
<WorkspaceStatCard label="今日触发" badge="当日" :value="stats.todayCount" icon="Calendar" unit="次" tone="warning" hint="查看今日告警" @click="emit('time-range','today')" />
<WorkspaceStatCard label="本周触发" badge="本周" :value="stats.weekCount" icon="TrendCharts" unit="次" hint="查看本周告警" @click="emit('time-range','week')" />
<WorkspaceStatCard label="未处理 / 已处理" :badge="stats.unprocessedCount > 0 ? `${stats.unprocessedCount} 待处理` : '无待处理'" :badge-tone="stats.unprocessedCount > 0 ? 'danger' : 'success'" :badge-pulse="stats.unprocessedCount > 0" :interactive="false" icon="CircleCheck" role="button" tabindex="0" @click="emit('process-status',-1)" @keydown.enter.self="emit('process-status',-1)" @keydown.space.self.prevent="emit('process-status',-1)">
<template #value><button type="button" class="status-value pending" @click.stop="emit('process-status',0)">{{ stats.unprocessedCount }}</button><span class="divider">/</span><button type="button" class="status-value" @click.stop="emit('process-status',1)">{{ stats.processedCount }}</button></template><template #footer><WorkspaceClosureSummary :pending="stats.unprocessedCount" :completed="stats.processedCount" label="处理率" /></template>
</WorkspaceStatCard></section></template>
<script setup>
import WorkspaceClosureSummary from '@/components/workspace/WorkspaceClosureSummary.vue'
import WorkspaceStatCard from '@/components/workspace/WorkspaceStatCard.vue'
defineProps({stats:{type:Object,required:true}})
const emit=defineEmits(['time-range','process-status'])
</script>
<style scoped>.alarm-overview { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; } .status-value { padding:0; border:0; background:none; font:inherit; color:var(--ui-success); cursor:pointer; } .status-value.pending { color:var(--ui-danger); } .divider { color:var(--ui-subtle); font-size:20px; } @media(max-width:1100px) { .alarm-overview { grid-template-columns:repeat(2,minmax(0,1fr)); } } @media(max-width:768px) { .alarm-overview { gap:10px; } }</style>
