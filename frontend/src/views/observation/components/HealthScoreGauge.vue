<template>
  <div class="health-gauge" :class="[tone, { 'is-compact': compact, 'is-loading': loading }]" role="img" :aria-label="ariaLabel">
    <div class="gauge-halo" aria-hidden="true"></div>
    <svg viewBox="0 0 200 200" aria-hidden="true"><circle class="gauge-outer" cx="100" cy="100" r="90" /><circle class="gauge-inner" cx="100" cy="100" r="64" /><circle class="gauge-track" cx="100" cy="100" r="76" /><circle v-if="score != null" class="gauge-progress" cx="100" cy="100" r="76" pathLength="100" :stroke-dasharray="`${Math.max(0, Math.min(100, Number(score)))} 100`" transform="rotate(-90 100 100)" /></svg>
    <!-- 分数未就绪时先给“计算中”骨架，不能先显示 0 分再跳成真实分数 -->
    <div v-if="loading && score == null" class="gauge-skeleton">
      <span class="gauge-spinner" aria-hidden="true"></span>
      <span class="gauge-skeleton-text">计算中…</span>
    </div>
    <div v-else class="gauge-content"><div class="gauge-number">{{ score ?? '-' }}<small v-if="score != null">分</small></div><span class="gauge-label">{{ label }}</span><span class="gauge-description"><i></i>{{ description }}</span></div>
    <!-- 已有上次分数时只用右上角转圈提示刷新，数字保留不闪烁 -->
    <span v-if="loading && score != null" class="gauge-refreshing" title="数据刷新中"><span class="gauge-spinner" aria-hidden="true"></span></span>
  </div>
</template>
<script setup>
import { computed } from 'vue'

const props = defineProps({
  compact: Boolean,
  label: { type: String, default: '系统综合健康分' },
  score: [Number, String],
  tone: String,
  description: String,
  // 汇总请求进行中：有旧分数时加遮罩刷新，没有分数时显示骨架
  loading: Boolean
})

const ariaLabel = computed(() => props.loading && props.score == null
  ? `${props.label}：计算中`
  : `${props.label}：${props.score ?? '暂无数据'}，${props.description}`)
</script>
<style scoped>
.health-gauge { --gauge-color:var(--ui-primary); position:relative; width:220px; height:220px; max-width:100%; margin:auto; color:var(--ui-heading); }
.score-unknown { --gauge-color:var(--ui-subtle); } .score-excellent { --gauge-color:var(--ui-success); } .score-good { --gauge-color:var(--ui-warning); } .score-poor { --gauge-color:var(--ui-danger); }
.health-gauge svg { position:relative; width:100%; height:100%; overflow:visible; }
.gauge-halo { position:absolute; inset:8%; border-radius:50%; background:radial-gradient(circle,transparent 53%,color-mix(in srgb,var(--gauge-color) 12%,transparent) 65%,transparent 76%); }
circle { fill:none; }.gauge-outer { stroke:color-mix(in srgb,var(--gauge-color) 25%,transparent); stroke-width:1.5; stroke-dasharray:1 6; }
.gauge-track { stroke:var(--ui-border-soft); stroke-width:12; }.gauge-progress { stroke:var(--gauge-color); stroke-width:12; stroke-linecap:round; filter:drop-shadow(0 2px 4px color-mix(in srgb,var(--gauge-color) 25%,transparent)); }
.gauge-content { position:absolute; inset:0; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:7px; }
.gauge-number { font-size:36px; line-height:1.2; font-weight:750; letter-spacing:-1px; font-variant-numeric:tabular-nums; }.gauge-number small { font-size:12px; font-weight:400; margin-left:4px; }
.gauge-label { font-size:11px; color:var(--ui-subtle); }.gauge-description { display:flex; align-items:center; gap:5px; max-width:80%; font-size:10px; color:var(--gauge-color); background:var(--ui-surface); border-radius:20px; padding:3px 7px; }
.gauge-description i { width:5px; height:5px; border-radius:50%; background:currentColor; }
.gauge-inner { stroke:color-mix(in srgb,var(--ui-primary) 20%,transparent); stroke-width:1; }
.gauge-skeleton { position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:var(--ui-subtle); }
.gauge-skeleton-text { font-size:12px; letter-spacing:1px; }
.gauge-spinner { width:18px; height:18px; border:2px solid var(--ui-border-soft); border-top-color:var(--ui-primary); border-radius:50%; animation:gauge-spin .8s linear infinite; }
.gauge-refreshing { position:absolute; top:12px; right:12px; display:flex; align-items:center; justify-content:center; padding:4px; border-radius:50%; background:var(--ui-surface); box-shadow:var(--ui-shadow-sm); }
.gauge-refreshing .gauge-spinner { width:12px; height:12px; }
@keyframes gauge-spin { to { transform:rotate(360deg); } }
</style>

<style scoped>
.health-gauge.is-compact { width:164px; height:164px; }
.is-compact .gauge-content { gap:4px; }
.is-compact .gauge-number { font-size:28px; }
.is-compact .gauge-label { font-size:9px; }
.is-compact .gauge-description { font-size:9px; padding:2px 5px; }
.is-compact .gauge-skeleton-text { font-size:10px; }
</style>
