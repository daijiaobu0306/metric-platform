<template>
  <div class="workspace-overview-layout" :class="{ 'has-aside': withAside }">
    <div class="overview-stats"><slot name="stats" /></div>
    <div class="overview-trend"><slot name="trend" /></div>
    <div v-if="withAside" class="overview-aside"><slot name="aside" /></div>
  </div>
</template>
<script setup>
defineProps({ withAside: Boolean })
</script>
<style scoped>
.workspace-overview-layout { display:grid; grid-template-columns:minmax(290px, .9fr) minmax(0, 2.7fr); gap:16px; align-items:stretch; min-width:0; }
.workspace-overview-layout.has-aside { grid-template-columns:minmax(260px, .85fr) minmax(0, 1.35fr) minmax(0, 1.15fr); }
.overview-stats, .overview-trend, .overview-aside { min-width:0; }
.overview-trend, .overview-aside { display:flex; flex-direction:column; }
.overview-trend > :deep(*), .overview-aside > :deep(*) { flex:1; min-width:0; }
.overview-stats { display:flex; padding:10px; border:1px solid var(--ui-border-soft); border-radius:var(--ui-radius-lg); background:var(--ui-page); }
.overview-stats :deep(section) { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; width:100%; }
.overview-stats :deep(.workspace-stat-card) { padding:10px 12px; gap:5px; border-radius:10px; box-shadow:none; }
.overview-stats :deep(.workspace-stat-card:nth-child(5):last-child) { grid-column:1 / -1; }
.overview-stats :deep(.stat-heading) { gap:4px; font-size:11px; }
.overview-stats :deep(.stat-heading-extra) { gap:4px; }
.overview-stats :deep(.stat-badge) { padding:1px 4px; font-size:9px; }
.overview-stats :deep(.stat-main) { gap:5px; font-size:23px; line-height:1.2; }
.overview-stats :deep(.stat-main small), .overview-stats :deep(.stat-footer) { font-size:10px; }
.overview-stats :deep(.workspace-closure-summary) { gap:4px; }
.overview-stats :deep(.closure-caption) { font-size:10px; }
.overview-stats :deep(.closure-track) { height:3px; }
.overview-trend :deep(.workspace-panel), .overview-trend :deep(.trend-area) { height:100%; min-height:0; box-sizing:border-box; padding:14px 16px; }
.overview-trend :deep(.workspace-panel-heading) { margin-bottom:10px; }
.overview-trend :deep(.workspace-panel-heading p), .overview-trend :deep(.insight-title p) { margin-top:3px; }
.overview-trend :deep(.insight-title) { margin-bottom:10px; }
.overview-trend :deep(.trend-header) { display:flex; flex-direction:row; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; margin-bottom:6px; }
.overview-trend :deep(.trend-filters) { display:flex; flex-wrap:wrap; min-width:0; gap:6px; }
.overview-trend :deep(.trend-filters .el-date-editor) { width:220px !important; flex:0 1 220px; min-width:0; max-width:100%; }
.overview-trend :deep(.trend-chart-wrapper) { min-height:170px; }
.overview-trend :deep(.trend-chart) { width:100%; height:170px; }
.overview-trend :deep(.trend-range-hint) { margin:3px 0; font-size:10px; }
.overview-trend :deep(.drill-down-tip) { padding:4px 8px; margin-bottom:4px; font-size:11px; }
.overview-aside :deep(.discoverer-board) { min-height:0; padding:14px; }
.overview-aside :deep(.board-header) { flex-wrap:wrap; gap:6px; margin-bottom:8px; }
.overview-aside :deep(.board-table) { font-size:11px; }
.overview-aside :deep(th), .overview-aside :deep(td) { padding:7px 6px; }
@media(max-width:1199px) {
  .workspace-overview-layout.has-aside { grid-template-columns:minmax(260px, .9fr) minmax(0, 1.8fr); }
  .has-aside .overview-aside { grid-column:1 / -1; }
}
@media(max-width:800px) {
  .workspace-overview-layout, .workspace-overview-layout.has-aside { grid-template-columns:minmax(0,1fr); gap:12px; }
  .overview-stats :deep(section) { grid-template-columns:repeat(2,minmax(0,1fr)); }
  .overview-trend :deep(.trend-chart) { height:180px; }
  .overview-trend :deep(.trend-chart-wrapper) { min-height:180px; }
}
</style>
