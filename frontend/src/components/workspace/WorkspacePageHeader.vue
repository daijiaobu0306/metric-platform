<template>
  <header class="workspace-page-header">
    <div class="workspace-page-title"><h1>{{ title }}</h1><span v-if="badge" class="workspace-page-badge" :class="`is-${tone}`">{{ badge }}</span><slot name="badge" /></div>
    <p v-if="description">{{ description }}</p><slot />
  </header>
</template>
<script setup>
defineProps({ title: { type: String, required: true }, description: String, badge: String, tone: { type: String, default: 'primary' } })
</script>
<style scoped>
.workspace-page-header { padding: 22px 24px; background: var(--ui-surface); border-bottom: 1px solid var(--ui-border); }
.workspace-page-title { display:flex; align-items:center; flex-wrap:wrap; gap:12px; }
h1 { margin:0; font-size:24px; line-height:1.4; font-weight:700; letter-spacing:-.6px; color:var(--ui-heading); }
p { margin:7px 0 0; color:var(--ui-muted); font-size:12px; line-height:1.8; }
.workspace-page-badge { display:inline-flex; align-items:center; gap:6px; padding:3px 10px; border:1px solid var(--ui-primary-border); border-radius:20px; background:var(--ui-primary-soft); color:var(--ui-primary-strong); font-size:11px; }
.workspace-page-badge.is-success { border-color:var(--ui-success-border); background:var(--ui-success-soft); color:var(--ui-success); }
@media(max-width:768px) { .workspace-page-header { padding:18px 16px; } h1 { font-size:20px; } }
.workspace-page-badge::before { content:""; width:5px; height:5px; border-radius:50%; background:currentColor; }
</style>
