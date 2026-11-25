<template>
  <span class="workspace-closure-summary">
    <span class="closure-caption"><span>{{ label }}</span><strong>{{ percentage == null ? '—' : `${percentage}%` }}</strong></span>
    <span class="closure-track" aria-hidden="true"><span class="closure-pending" :style="{ flex: pending }"></span><span class="closure-complete" :style="{ flex: completed }"></span></span>
    <span class="sr-only">未完成 {{ pending }}，已完成 {{ completed }}</span>
  </span>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ pending: { type: Number, default: 0 }, completed: { type: Number, default: 0 }, label: { type: String, default: '闭环率' } })
// 分母仅使用同一统计接口给出的两项状态数，不混用列表总量或其他时间范围。
const percentage = computed(() => {
  const total = props.pending + props.completed
  return total > 0 ? Math.round(props.completed / total * 1000) / 10 : null
})
</script>
<style scoped>
.workspace-closure-summary { display:flex; flex-direction:column; gap:9px; width:100%; }
.closure-caption { display:flex; justify-content:space-between; gap:8px; color:var(--ui-subtle); font-size:11px; }
strong { color:var(--ui-success); font-weight:600; font-variant-numeric:tabular-nums; }
.closure-track { display:flex; height:5px; overflow:hidden; border-radius:6px; background:var(--ui-border-soft); }
.closure-pending { background:var(--ui-danger); }.closure-complete { background:var(--ui-success); }
.sr-only { position:absolute; width:1px; height:1px; padding:0; overflow:hidden; clip-path:inset(50%); white-space:nowrap; }
</style>
