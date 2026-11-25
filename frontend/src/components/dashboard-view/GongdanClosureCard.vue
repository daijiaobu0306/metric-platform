<template>
  <section class="gongdan-closure-card">
    <header><div><h3>工单闭环率</h3><small>工单闭环与期间流转</small></div><WorkspaceStatusBadge tone="muted">{{ closureRate == null ? '暂无期间统计基数' : '期间统计' }}</WorkspaceStatusBadge></header>
    <div class="closure-hero">
      <HalfGauge class="closure-ring" :value="closureRate" label="期间闭环率" ring />
      <div class="closure-copy"><strong>{{ display(closed) }} <i>/</i> {{ display(created) }} 单已闭环</strong><small>问题按期解决率 {{ percentage(issueOnTimeRate) }}</small><div class="closure-progress" aria-hidden="true"><i :style="{ width: `${closureRate ?? 0}%` }"></i></div></div>
    </div>
    <div class="closure-metrics">
      <div title="当前时间范围内新建、目前已闭环的工单"><span>期间已闭环</span><b>{{ display(closed) }}</b></div>
      <div title="当前授权范围内全部未闭环工单，不代表超期数量"><span>当前未闭环</span><b>{{ display(pending) }}</b></div>
      <div title="当前时间范围内新建、目前仍未闭环的工单"><span>期间流转中</span><b>{{ display(inProgress) }}</b></div>
    </div>
    <small class="closure-note">闭环率按期间新建工单统计；当前未闭环含历史积压。无可计算记录时显示“—”。</small>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import HalfGauge from './ui/HalfGauge.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
const props = defineProps({ gongdan: Object, issue: Object })
const numberOrNull = value => value == null || value === '' || !Number.isFinite(Number(value)) ? null : Number(value)
const created = computed(() => numberOrNull(props.gongdan?.closure?.created))
const closed = computed(() => numberOrNull(props.gongdan?.closure?.closed))
const pending = computed(() => numberOrNull(props.gongdan?.pending))
const inProgress = computed(() => created.value == null || closed.value == null ? null : Math.max(0, created.value - closed.value))
const rate = (done, total) => done != null && total != null && total > 0 ? Math.min(100, Math.max(0, Math.round(done * 100 / total))) : null
const closureRate = computed(() => rate(closed.value, created.value))
const issueOnTimeRate = computed(() => rate(numberOrNull(props.issue?.timeliness?.onTime), numberOrNull(props.issue?.timeliness?.due)))
const display = value => value ?? '—'
const percentage = value => value == null ? '—' : `${value}%`
</script>
<style scoped>
.gongdan-closure-card { display:flex; flex-direction:column; gap:12px; min-width:0; padding:18px; border:1px solid var(--ui-border); border-radius:var(--ui-radius-lg); background:var(--ui-surface); }
header { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; flex-wrap:wrap; }
h3 { display:flex; align-items:center; gap:9px; margin:0 0 4px; color:var(--ui-heading); font-size:14px; font-weight:650; }
h3::before { content:''; width:5px; height:15px; border-radius:4px; background:var(--ui-primary); }
header small, .closure-note { color:var(--ui-subtle); font-size:10px; line-height:1.6; }
.closure-hero { display:flex; align-items:center; gap:16px; padding:12px; border:1px solid var(--ui-border); border-radius:12px; background:var(--ui-page); }
.closure-ring { width:126px; flex:none; --gauge-progress:var(--ui-primary); --gauge-track:var(--ui-border-soft); --gauge-text:var(--ui-heading); --gauge-label:var(--ui-subtle); }
.closure-copy { display:grid; flex:1; min-width:0; gap:8px; }
.closure-copy strong { color:var(--ui-heading); font-size:14px; font-variant-numeric:tabular-nums; }
.closure-copy i { color:var(--ui-subtle); font-style:normal; }
.closure-copy small { color:var(--ui-muted); font-size:10px; }
.closure-progress { height:5px; overflow:hidden; border-radius:8px; background:var(--ui-border-soft); }
.closure-progress i { display:block; height:100%; border-radius:inherit; background:var(--ui-primary); }
.closure-metrics { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px; }
.closure-metrics > div { display:grid; gap:4px; min-width:0; padding:9px; border:1px solid var(--ui-border); border-radius:8px; }
.closure-metrics span { color:var(--ui-muted); font-size:10px; }
.closure-metrics b { color:var(--ui-heading); font-size:18px; font-variant-numeric:tabular-nums; }
.closure-note { margin-top:auto; }
@media(max-width:540px) { .gongdan-closure-card { padding:14px; }.closure-hero { gap:10px; padding:10px; }.closure-ring { width:94px; }.closure-metrics > div { padding:7px; } }
</style>
