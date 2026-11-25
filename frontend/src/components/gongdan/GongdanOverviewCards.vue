<template>
<section class="gongdan-overview" aria-label="工单统计与快捷筛选">
  <WorkspaceStatCard label="近 30 天工单总量" badge="近30天" :value="stats.totalGongdanCount" icon="Tickets" unit="件" hint="查看近 30 天工单" @click="emit('time-range','month')" />
  <WorkspaceStatCard label="今日新增" badge="当日" :value="stats.todayCount" icon="CirclePlus" unit="件" tone="success" hint="查看当日新增工单" @click="emit('time-range','today')" />
  <WorkspaceStatCard label="本周新增" badge="本周" :value="stats.weekCount" icon="Calendar" unit="件" hint="查看本周新增工单" @click="emit('time-range','week')" />
  <WorkspaceStatCard label="未关单 / 已关单" :badge="stats.openCount > 0 ? `${stats.openCount} 未关单` : '无未关单'" :badge-tone="stats.openCount > 0 ? 'danger' : 'success'" :badge-pulse="stats.openCount > 0" icon="CircleCheck" :interactive="false" role="button" tabindex="0" @click="emit('closed-status',-1)" @keydown.enter.self="emit('closed-status',-1)" @keydown.space.self.prevent="emit('closed-status',-1)">
    <template #value><button type="button" class="status-value" @click.stop="emit('closed-status',0)">{{ stats.openCount }}</button><span class="divider">/</span><button type="button" class="status-value is-closed" @click.stop="emit('closed-status',1)">{{ stats.closedCount }}</button></template>
    <template #footer><WorkspaceClosureSummary :pending="stats.openCount" :completed="stats.closedCount" label="关单率" /></template>
  </WorkspaceStatCard>
  <WorkspaceStatCard label="未成功推送 BOMC" :badge="stats.pendingSyncCount > 0 ? '待跟进' : '无待推送'" :badge-tone="stats.pendingSyncCount > 0 ? 'danger' : 'success'" :value="stats.pendingSyncCount" icon="Upload" unit="件" :tone="stats.pendingSyncCount > 0 ? 'danger' : 'warning'" hint="查看待推送与推送失败工单" @click="emit('synced',0)" />
</section>
</template>
<script setup>
import WorkspaceClosureSummary from '@/components/workspace/WorkspaceClosureSummary.vue'
import WorkspaceStatCard from '@/components/workspace/WorkspaceStatCard.vue'
defineProps({stats:{type:Object,required:true}})
const emit=defineEmits(['time-range','closed-status','synced'])
</script>
<style scoped>.gongdan-overview { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:16px; } .status-value { padding:0; border:0; background:none; color:var(--ui-primary); font:inherit; cursor:pointer; } .status-value.is-closed { color:var(--ui-success); } .divider { color:var(--ui-subtle); font-size:20px; } @media(max-width:1200px) { .gongdan-overview { grid-template-columns:repeat(3,minmax(0,1fr)); } } @media(max-width:768px) { .gongdan-overview { grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; } }</style>
