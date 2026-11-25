<template>
  <component :is="interactive ? 'button' : 'article'" :type="interactive ? 'button' : undefined" @click="emit('click', $event)" @keydown="emit('keydown', $event)" class="workspace-stat-card" :class="[`tone-${tone}`, `variant-${variant}`, { 'is-interactive': interactive, 'is-compact': compact }]">
    <span v-if="variant === 'hierarchy'" class="hierarchy-card-top"><el-icon class="stat-symbol"><component :is="icon" /></el-icon><span class="hierarchy-card-entry">{{ hint }} <el-icon><TopRight /></el-icon></span></span>
    <span class="stat-heading"><span>{{ label }}</span><span class="stat-heading-extra"><span v-if="badge" class="stat-badge" :class="[`badge-${badgeTone}`, { 'badge-pulse': badgePulse }]"><i v-if="badgePulse" class="badge-dot" aria-hidden="true"></i>{{ badge }}</span><el-icon v-if="icon && !badge && variant !== 'hierarchy'" class="stat-symbol"><component :is="icon" /></el-icon></span></span>
    <div class="stat-main"><slot name="value">{{ value }}<small v-if="unit">{{ unit }}</small></slot></div>
    <span v-if="variant !== 'hierarchy' && (hint || $slots.footer)" class="stat-footer"><slot name="footer">{{ hint }}<el-icon v-if="interactive"><Right /></el-icon></slot></span>
  </component>
</template>
<script setup>
// 显式转发原生事件，让动态根节点的交互成为稳定的组件契约。
const emit = defineEmits(['click', 'keydown'])
defineProps({ compact: Boolean, badge: String, badgePulse: Boolean, badgeTone: {type:String,default:'primary'}, variant: {type:String,default:'standard'}, label: { type:String, required:true }, value:[Number,String], unit:String, icon:[String,Object,Function], hint:String, tone:{type:String,default:'primary'}, interactive:{type:Boolean,default:true} })
</script>
<style scoped>
.workspace-stat-card { display:flex; flex-direction:column; gap:16px; min-width:0; padding:20px; text-align:left; border:1px solid var(--ui-border); border-radius:var(--ui-radius-lg); background:var(--ui-surface); color:var(--ui-text); box-shadow:var(--ui-shadow-sm); font:inherit; }
.is-interactive { cursor:pointer; transition:border-color .18s,box-shadow .18s; }
.is-interactive:hover { border-color:var(--ui-primary-border); box-shadow:var(--ui-shadow-hover); }
.is-interactive:focus-visible { outline:2px solid var(--ui-primary); outline-offset:3px; }
.stat-heading { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; color:var(--ui-muted); font-size:12px; }
.stat-symbol { width:32px; height:32px; border-radius:9px; background:var(--ui-primary-soft); color:var(--ui-primary); font-size:18px; }
.tone-success .stat-symbol { background:var(--ui-success-soft); color:var(--ui-success); }
.tone-warning .stat-symbol { background:var(--ui-warning-soft); color:var(--ui-warning); }
.tone-danger .stat-symbol { background:var(--ui-danger-soft); color:var(--ui-danger); }
.stat-main { display:flex; align-items:baseline; flex-wrap:wrap; gap:8px; color:var(--ui-heading); font-size:28px; font-weight:700; line-height:1.3; font-variant-numeric:tabular-nums; }
.stat-main small { color:var(--ui-muted); font-size:12px; font-weight:400; }
.stat-footer { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-top:auto; color:var(--ui-subtle); font-size:11px; line-height:1.6; }
@media(max-width:768px) { .workspace-stat-card { padding:14px; gap:12px; } .stat-main { font-size:25px; } }
@media(prefers-reduced-motion:reduce) { .is-interactive { transition:none; } }

.tone-indigo .stat-symbol { color:var(--ui-indigo); background:var(--ui-indigo-soft); }
.tone-violet .stat-symbol { color:var(--ui-violet); background:var(--ui-violet-soft); }
.hierarchy-card-top { display:flex; align-items:flex-start; justify-content:space-between; width:100%; gap:8px; }
.variant-hierarchy .stat-symbol { width:40px; height:40px; border-radius:12px; font-size:20px; }
.variant-hierarchy .stat-heading { margin-top:4px; }
.variant-hierarchy .stat-main { margin-top:-10px; }
.variant-hierarchy.is-compact { display:grid; grid-template-columns:32px minmax(0,1fr) auto; gap:4px 12px; align-items:center; padding:14px 16px; }
.variant-hierarchy.is-compact .hierarchy-card-top { display:contents; }
.variant-hierarchy.is-compact .stat-symbol { grid-column:1; grid-row:1 / 3; width:32px; height:34px; border-radius:9px; font-size:17px; }
.variant-hierarchy.is-compact .hierarchy-card-entry { grid-column:3; grid-row:1 / 3; padding:4px 6px; font-size:10px; white-space:nowrap; }
.variant-hierarchy.is-compact .stat-heading { grid-column:2; grid-row:1; margin:0; font-size:11px; }
.variant-hierarchy.is-compact .stat-main { grid-column:2; grid-row:2; margin:0; gap:5px; font-size:25px; }
.variant-hierarchy.is-compact .stat-main small { font-size:10px; }
@media(max-width:540px) {
  .variant-hierarchy.is-compact { grid-template-columns:28px minmax(0,1fr); gap:4px 8px; padding:12px; }
  .variant-hierarchy.is-compact .stat-symbol { width:28px; height:30px; }
  .variant-hierarchy.is-compact .hierarchy-card-entry { grid-column:1 / -1; grid-row:3; justify-self:start; }
}
.hierarchy-card-entry { display:inline-flex; align-items:center; gap:5px; padding:5px 7px; border-radius:6px; background:var(--ui-primary-soft); color:var(--ui-primary-strong); font-size:11px; }
.is-interactive:hover .stat-footer > .el-icon, .is-interactive:hover .hierarchy-card-entry .el-icon { transform:translateX(2px); }
.stat-footer > .el-icon, .hierarchy-card-entry .el-icon { transition:transform .18s; }
@media(prefers-reduced-motion:reduce) { .stat-footer > .el-icon, .hierarchy-card-entry .el-icon { transition:none; transform:none !important; } }
 .stat-heading-extra { margin-left:auto; flex-shrink:0; display:inline-flex; align-items:center; gap:8px; }
.stat-badge { display:inline-flex; align-items:center; gap:4px; padding:2px 6px; border-radius:5px; color:var(--ui-primary-strong); background:var(--ui-primary-soft); font-size:10px; white-space:nowrap; }
.badge-muted { color:var(--ui-muted); background:var(--ui-page); }.badge-success { color:var(--ui-success); background:var(--ui-success-soft); }.badge-danger { color:var(--ui-danger); background:var(--ui-danger-soft); }
.badge-dot { width:5px; height:5px; border-radius:50%; background:currentColor; animation:stat-badge-pulse 2s ease-in-out infinite; }
@keyframes stat-badge-pulse { 50% { opacity:.45; box-shadow:0 0 0 3px color-mix(in srgb,currentColor 12%,transparent); } }
@media(prefers-reduced-motion:reduce) { .badge-dot { animation:none; } }
</style>
