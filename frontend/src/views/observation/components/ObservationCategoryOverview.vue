<template><section class="category-summary-grid" aria-label="指标大类健康度"><button v-for="category in categories" :key="category.code" type="button" class="category-summary-card" :class="getScoreClass(category.score)" @click="emit('select', category)"><span class="category-summary-top"><b>{{ category.name }}</b><span class="category-alert" :class="{ 'has-alert': category.alarmCount > 0 }"><i></i>{{ category.alarmCount > 0 ? `${category.alarmCount} 项异常` : '0 项异常' }}</span></span><span class="category-summary-main"><strong :title="category.intervalSamples ? `区间均分 · ${category.intervalSamples} 个采样点` : '区间趋势未加载或无有效采样，暂显示实时最新分'">{{ category.score ?? '-' }}</strong><small>指标 {{ category.metricCount || 0 }} | 异常 {{ category.alarmCount || 0 }}</small></span><span class="category-summary-footer"><span>大类健康度</span><span>点击下钻 <el-icon><ArrowRight /></el-icon></span></span></button></section></template>
<script setup>
defineProps({categories:{type:Array,default:()=>[]},getScoreClass:{type:Function,required:true}})
const emit=defineEmits(['select'])
</script>
<style scoped>
.category-summary-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; margin-bottom:20px; }
.category-summary-card { --category-color:var(--ui-primary); display:flex; flex-direction:column; gap:16px; min-width:0; position:relative; overflow:hidden; padding:18px 16px; border:1px solid var(--ui-border); border-radius:12px; background:var(--ui-page); color:var(--ui-text); text-align:left; font:inherit; cursor:pointer; }
.category-summary-card::before { content:''; position:absolute; left:0; right:0; top:0; height:4px; background:var(--category-color); }
.score-excellent { --category-color:var(--ui-success); }.score-good { --category-color:var(--ui-warning); }.score-poor { --category-color:var(--ui-danger); }
.category-summary-card:hover { border-color:var(--category-color); box-shadow:var(--ui-shadow-hover); }
.category-summary-card:focus-visible { outline:2px solid var(--ui-primary); outline-offset:3px; }
.category-summary-top { display:flex; align-items:center; justify-content:space-between; gap:8px; font-size:12px; }.category-alert { display:inline-flex; gap:4px; align-items:center; border-radius:15px; padding:3px 6px; background:var(--ui-success-soft); color:var(--ui-success); font-size:10px; white-space:nowrap; }.category-alert i { width:5px; height:5px; border-radius:50%; background:currentColor; }.category-alert.has-alert { background:var(--ui-danger-soft); color:var(--ui-danger); }
.category-summary-main { display:flex; align-items:baseline; justify-content:space-between; gap:8px; flex-wrap:wrap; }.category-summary-main strong { color:var(--category-color); font-size:28px; font-variant-numeric:tabular-nums; }.category-summary-main small { color:var(--ui-muted); font-size:11px; }
.category-summary-footer { display:flex; align-items:center; justify-content:space-between; padding-top:12px; border-top:1px solid var(--ui-border); font-size:11px; color:var(--ui-subtle); }.category-summary-footer > span:last-child { display:flex; align-items:center; gap:5px; color:var(--ui-primary); }
@media(max-width:1200px) { .category-summary-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } } @media(max-width:600px) { .category-summary-grid { grid-template-columns:1fr; } }
</style>
