<template><span class="metric-type-tag" :class="tone">{{ type || '未配置' }}</span></template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ type: String })
const tone = computed(() => ({ count:'count', rate:'rate', ratio:'rate', value:'value', 导入:'import', import:'import' })[props.type] || 'other')
</script>
<style scoped>
.metric-type-tag { display:inline-flex; padding:2px 9px; border:1px solid var(--ui-border); border-radius:5px; color:var(--ui-muted); background:var(--ui-page); font-size:11px; line-height:1.6; white-space:nowrap; }
.count,.value { color:var(--ui-primary); border-color:var(--ui-primary-border); background:var(--ui-primary-soft); }
.rate { color:var(--ui-violet); border-color:color-mix(in srgb,var(--ui-violet) 20%,var(--ui-surface)); background:var(--ui-violet-soft); }
</style>
