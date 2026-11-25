<template>
  <span class="workspace-tab-label"><el-icon v-if="icon"><component :is="icon" /></el-icon><slot /><small v-if="count != null">{{ count }}</small></span>
</template>
<script setup>
defineProps({ icon: [String, Object, Function], count: [Number, String] })
</script>
<style scoped>
.workspace-tab-label { display:inline-flex; align-items:center; gap:8px; white-space:nowrap; }
.el-icon { font-size:14px; }
small { padding:1px 7px; border-radius:12px; background:var(--ui-border-soft); color:var(--ui-muted); font-size:10px; line-height:1.6; font-variant-numeric:tabular-nums; }
:global(.el-tabs__item.is-active) .workspace-tab-label small { color:var(--ui-primary-strong); background:var(--ui-primary-soft); }
</style>
