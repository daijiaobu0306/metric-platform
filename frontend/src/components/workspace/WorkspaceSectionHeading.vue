<template>
  <div class="workspace-section-heading"><span v-if="icon" class="section-icon" :class="`tone-${tone}`"><el-icon><component :is="icon" /></el-icon></span><div><h3>{{ title }}</h3><p v-if="description">{{ description }}</p></div><span v-if="$slots.default" class="section-extra"><slot /></span></div>
</template>
<script setup>
defineProps({ title: { type: String, required: true }, description: String, icon: [String, Object, Function], tone: { type: String, default: 'primary' } })
</script>
<style scoped>
.workspace-section-heading { display:flex; align-items:center; gap:10px; min-width:0; }
.section-icon { display:inline-flex; align-items:center; justify-content:center; width:30px; height:30px; flex-shrink:0; border-radius:9px; color:var(--ui-primary); background:var(--ui-primary-soft); }
.section-icon.tone-success { color:var(--ui-success); background:var(--ui-success-soft); }
h3 { margin:0; color:var(--ui-heading); font-size:14px; font-weight:600; line-height:1.6; }p { margin:3px 0 0; color:var(--ui-subtle); font-size:11px; line-height:1.6; font-weight:400; }
.section-extra { margin-left:auto; color:var(--ui-subtle); font-size:11px; font-weight:400; }
</style>
