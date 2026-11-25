<template><span class="workspace-status" :class="[`tone-${tone}`, { 'is-pulsing': pulse }]"><span class="status-dot" aria-hidden="true"></span><slot /></span></template>
<script setup>
defineProps({tone:{type:String,default:'primary'},pulse:Boolean})
</script>
<style scoped>
.workspace-status { display:inline-flex; align-items:center; gap:6px; padding:3px 9px; border:1px solid var(--ui-primary-border); border-radius:20px; background:var(--ui-primary-soft); color:var(--ui-primary-strong); font-size:11px; line-height:1.5; white-space:nowrap; }
.status-dot { width:6px; height:6px; flex-shrink:0; border-radius:50%; background:currentColor; }
.tone-success { background:var(--ui-success-soft); border-color:var(--ui-success-border); color:var(--ui-success); }
.tone-warning { background:var(--ui-warning-soft); border-color:color-mix(in srgb,var(--ui-warning) 30%,var(--ui-surface)); color:var(--ui-warning); }
.tone-info, .tone-muted { background:var(--ui-page); border-color:var(--ui-border); color:var(--ui-muted); }
.tone-danger { background:var(--ui-danger-soft); border-color:var(--ui-danger-border); color:var(--ui-danger); }
.is-pulsing .status-dot { animation:workspace-status-pulse 2s ease-in-out infinite; }
@keyframes workspace-status-pulse { 50% { opacity:.45; box-shadow:0 0 0 3px color-mix(in srgb,currentColor 10%,transparent); } }
@media(prefers-reduced-motion:reduce) { .is-pulsing .status-dot { animation:none; } }
</style>
