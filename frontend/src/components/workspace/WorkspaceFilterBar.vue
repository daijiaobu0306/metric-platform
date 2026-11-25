<template><section class="workspace-filter-bar" :class="[{ 'is-compact': compact }, layout ? `filter-layout--${layout}` : '']" aria-label="筛选条件"><div class="workspace-filter-fields" v-balanced-query="layout === 'auto' ? {} : layout === 'audit' ? { fill:false } : false"><slot /></div></section></template>
<script setup>
defineProps({ compact: Boolean, layout: { type:String, default:'' } })
</script>
<style scoped>
.workspace-filter-bar { padding:16px; border:1px solid var(--ui-border); border-radius:var(--ui-radius-lg); background:var(--ui-surface); box-shadow:var(--ui-shadow-sm); }
.workspace-filter-fields { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); align-items:center; gap:12px; }
.workspace-filter-fields > :deep(.el-input), .workspace-filter-fields > :deep(.el-select), .workspace-filter-fields > :deep(.el-date-editor) { width:100% !important; min-width:0; }
.workspace-filter-fields > :deep(.el-date-editor) { grid-column:span 2; }
.workspace-filter-fields :deep(.el-input__wrapper), .workspace-filter-fields :deep(.el-select__wrapper) { min-height:34px; box-sizing:border-box; background:var(--ui-field); box-shadow:0 0 0 1px var(--ui-border) inset; font-size:12px; }
.workspace-filter-fields :deep(.el-button) { height:34px; margin:0; }
@media(min-width:1700px) { .workspace-filter-fields { grid-template-columns:repeat(6,minmax(0,1fr)); } }
@media(max-width:768px) { .workspace-filter-fields { grid-template-columns:repeat(2,minmax(0,1fr)); } }
/* 三个业务列表采用内容宽度排版，避免控件随整列无限拉伸。 */
.is-compact { padding:12px 16px; }
.is-compact .workspace-filter-fields { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
.is-compact .workspace-filter-fields > :deep(.el-select) { flex:0 0 144px; width:144px !important; }
.is-compact .workspace-filter-fields > :deep(.el-input) { flex:0 0 172px; width:172px !important; }
.is-compact .workspace-filter-fields > :deep(.filter-wide) { flex-basis:208px; width:208px !important; }
.is-compact .workspace-filter-fields > :deep(.el-date-editor) { flex:0 0 280px; width:280px !important; height:32px; box-sizing:border-box; }
.is-compact .workspace-filter-fields :deep(.el-input__wrapper), .is-compact .workspace-filter-fields :deep(.el-select__wrapper) { min-height:32px; }
.is-compact .workspace-filter-fields :deep(.filter-actions) { display:flex; align-items:center; gap:8px; flex:none; }
.is-compact .workspace-filter-fields :deep(.filter-actions .el-button) { height:32px; min-width:72px; padding:0 14px; }
@media(max-width:768px) {
  .is-compact { padding:12px; }
  .is-compact .workspace-filter-fields > :deep(.el-input), .is-compact .workspace-filter-fields > :deep(.el-select), .is-compact .workspace-filter-fields > :deep(.filter-wide) { flex:0 0 calc(50% - 5px); width:calc(50% - 5px) !important; }
  .is-compact .workspace-filter-fields > :deep(.el-date-editor) { flex-basis:100%; width:100% !important; }
}
/* 按实际容器宽度整行分配，不依赖浏览器窗口宽度或固定控件宽度换行。 */
.workspace-filter-bar[class*="filter-layout--"] { container-type:inline-size; }
[class*="filter-layout--"] .workspace-filter-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
[class*="filter-layout--"].is-compact .workspace-filter-fields > :deep(*) { width:100% !important; min-width:0; }
[class*="filter-layout--"] .workspace-filter-fields > :deep(.el-date-editor),
[class*="filter-layout--"] .workspace-filter-fields > :deep(.filter-actions) { grid-column:span 2; }
[class*="filter-layout--"] .workspace-filter-fields :deep(.filter-actions) { display:flex; gap:8px; }
[class*="filter-layout--"] .workspace-filter-fields :deep(.filter-actions .el-button) { flex:1; min-width:0; height:34px; padding:0 12px; }
[class*="filter-layout--"] .workspace-filter-fields :deep(.el-input__wrapper),
[class*="filter-layout--"] .workspace-filter-fields :deep(.el-select__wrapper),
[class*="filter-layout--"] .workspace-filter-fields > :deep(.el-date-editor) { min-height:34px; height:34px; }
@container (min-width:760px) {
  .filter-layout--alarm .workspace-filter-fields { grid-template-columns:repeat(16,minmax(0,1fr)); }
  .filter-layout--alarm .workspace-filter-fields > :deep(:nth-child(-n+4)) { grid-column:span 4; }
  .filter-layout--alarm .workspace-filter-fields > :deep(:nth-child(5)) { grid-column:span 3; }
  .filter-layout--alarm .workspace-filter-fields > :deep(:nth-child(6)) { grid-column:span 4; }
  .filter-layout--alarm .workspace-filter-fields > :deep(.el-date-editor) { grid-column:span 6; }
  .filter-layout--alarm .workspace-filter-fields > :deep(.filter-actions) { grid-column:span 3; }
  .filter-layout--strategy .workspace-filter-fields { grid-template-columns:repeat(12,minmax(0,1fr)); }
  .filter-layout--strategy .workspace-filter-fields > :deep(:nth-child(-n+3)) { grid-column:span 4; }
  .filter-layout--strategy .workspace-filter-fields > :deep(:nth-child(n+4)) { grid-column:span 3; }
  .filter-layout--gongdan .workspace-filter-fields { grid-template-columns:repeat(12,minmax(0,1fr)); }
  .filter-layout--gongdan .workspace-filter-fields > :deep(:nth-child(-n+8)) { grid-column:span 2; }
  .filter-layout--gongdan .workspace-filter-fields > :deep(.el-date-editor) { grid-column:span 5; }
  .filter-layout--gongdan .workspace-filter-fields > :deep(.filter-actions) { grid-column:span 3; }
}
@container (min-width:960px) {
  /* 策略筛选：策略ID、策略名称、策略类型、对象、指标大类、指标小类、告警、工单、查询/重置 = 9 列，固定一行 */
  .filter-layout--strategy .workspace-filter-fields { grid-template-columns:minmax(90px,1fr) minmax(120px,1.4fr) repeat(6,minmax(84px,1fr)) 152px; }
  .filter-layout--strategy .workspace-filter-fields > :deep(:nth-child(n)) { grid-column:auto; }
}
@container (min-width:1240px) {
  .filter-layout--alarm .workspace-filter-fields { grid-template-columns:repeat(5,minmax(105px,1fr)) minmax(160px,1.5fr) minmax(240px,2fr) 152px; }
  .filter-layout--alarm .workspace-filter-fields > :deep(:nth-child(n)) { grid-column:auto; }
}
@container (min-width:1650px) {
  .filter-layout--gongdan .workspace-filter-fields { grid-template-columns:repeat(4,minmax(140px,1.25fr)) repeat(4,minmax(105px,1fr)) minmax(240px,2fr) 152px; }
  .filter-layout--gongdan .workspace-filter-fields > :deep(:nth-child(n)) { grid-column:auto; }
}
</style>
