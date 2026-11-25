<template><section class="login-audit-overview" aria-label="登录统计"><WorkspaceStatCard :interactive="false" label="登录次数" :value="summary.totalCount" unit="次" icon="User" /><WorkspaceStatCard :interactive="false" label="登录人数" :value="summary.distinctUserCount" unit="人" icon="UserFilled" tone="success" /><WorkspaceStatCard :interactive="false" label="登录总时长" :value="formatDuration(summary.totalDurationMinutes)" icon="Clock" /><WorkspaceStatCard :interactive="false" label="活跃天数" :value="activeDays" unit="天" icon="Calendar" tone="warning" /></section></template>
<script setup>
import WorkspaceStatCard from '@/components/workspace/WorkspaceStatCard.vue'
defineProps({summary:{type:Object,required:true},activeDays:Number,formatDuration:{type:Function,required:true}})
</script>
<style scoped>.login-audit-overview { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; } @media(max-width:1100px) { .login-audit-overview { grid-template-columns:repeat(2,minmax(0,1fr)); } }</style>
