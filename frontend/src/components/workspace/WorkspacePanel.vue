<template><section class="workspace-panel"><header v-if="title || $slots.header" class="workspace-panel-heading"><slot name="header"><div><h2><el-icon v-if="icon" class="panel-title-icon"><component :is="icon" /></el-icon>{{ title }}</h2><p v-if="description">{{ description }}</p></div></slot><slot name="actions" /></header><slot /></section></template>
<script setup>
defineProps({ title:String, description:String, icon:[String,Object,Function] })
</script>
<style scoped>
.workspace-panel { min-width:0; padding:20px; border:1px solid var(--ui-border); border-radius:var(--ui-radius-lg); background:var(--ui-surface); box-shadow:var(--ui-shadow-sm); }
.workspace-panel-heading { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:16px; }
h2 { margin:0; color:var(--ui-heading); font-size:14px; font-weight:600; } p { margin:6px 0 0; color:var(--ui-subtle); font-size:11px; }
@media(max-width:768px) { .workspace-panel { padding:16px; } }
h2 { display:flex; align-items:center; gap:9px; }
.panel-title-icon { display:flex; width:28px; height:28px; border-radius:8px; background:var(--ui-primary-soft); color:var(--ui-primary); font-size:16px; }
</style>
