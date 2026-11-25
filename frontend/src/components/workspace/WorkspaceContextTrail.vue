<template>
  <nav class="workspace-context-trail" :aria-label="label"><span class="trail-label">{{ label }}</span><template v-for="(item, index) in items.filter(Boolean)" :key="index"><el-icon v-if="index" class="trail-separator" aria-hidden="true"><ArrowRight /></el-icon><strong>{{ item }}</strong></template><slot /></nav>
</template>
<script setup>
defineProps({ label: { type:String, default:'当前指标' }, items: { type:Array, default:()=>[] } })
</script>
<style scoped>
.workspace-context-trail { display:flex; align-items:center; flex-wrap:wrap; gap:9px; padding:12px 16px; border-bottom:1px solid var(--ui-border-soft); color:var(--ui-text); background:var(--ui-surface); font-size:12px; }
.trail-label { padding-right:10px; border-right:1px solid var(--ui-border); color:var(--ui-subtle); }strong { font-weight:500; }.trail-separator { font-size:10px; color:var(--ui-subtle); }
</style>
