<template><section class="issue-overview" aria-label="问题统计与快捷筛选">
<WorkspaceStatCard label="问题总数" badge="全量沉淀" :value="stats.totalIssueCount" icon="Bell" unit="个" hint="查看全部问题" @click="emit('time-range','all')" />
<WorkspaceStatCard label="今日新增" badge="当日" :value="stats.todayCount" icon="Calendar" unit="个" tone="warning" hint="查看今日问题" @click="emit('time-range','today')" />
<WorkspaceStatCard label="本周新增" badge="本周" :value="stats.weekCount" icon="TrendCharts" unit="个" hint="查看本周问题" @click="emit('time-range','week')" />
<WorkspaceStatCard label="未解决 / 已解决" :badge="stats.unresolvedCount > 0 ? `${stats.unresolvedCount} 待解决` : '无待解决'" :badge-tone="stats.unresolvedCount > 0 ? 'danger' : 'success'" :badge-pulse="stats.unresolvedCount > 0" :interactive="false" icon="CircleCheck" role="button" tabindex="0" @click="emit('resolve-status',-1)" @keydown.enter.self="emit('resolve-status',-1)" @keydown.space.self.prevent="emit('resolve-status',-1)">
<template #value><button type="button" class="status-value pending" @click.stop="emit('resolve-status',0)">{{ stats.unresolvedCount }}</button><span class="divider">/</span><button type="button" class="status-value" @click.stop="emit('resolve-status',1)">{{ stats.resolvedCount }}</button></template><template #footer><WorkspaceClosureSummary :pending="stats.unresolvedCount" :completed="stats.resolvedCount" label="解决率" /></template>
</WorkspaceStatCard></section></template>
<script setup>
import WorkspaceClosureSummary from '@/components/workspace/WorkspaceClosureSummary.vue'
import WorkspaceStatCard from '@/components/workspace/WorkspaceStatCard.vue'
defineProps({stats:{type:Object,required:true}})
const emit=defineEmits(['time-range','resolve-status'])
</script>
<style scoped>.issue-overview { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; } .status-value { padding:0; border:0; background:none; font:inherit; color:var(--ui-success); cursor:pointer; } .status-value.pending { color:var(--ui-danger); } .divider { color:var(--ui-subtle); font-size:20px; } @media(max-width:1100px) { .issue-overview { grid-template-columns:repeat(2,minmax(0,1fr)); } } @media(max-width:768px) { .issue-overview { gap:10px; } }</style>
