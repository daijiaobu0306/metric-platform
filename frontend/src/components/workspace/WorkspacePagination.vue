<template><footer class="workspace-pagination-footer"><span class="pagination-summary">共 <strong>{{ total }}</strong> 条记录<span class="pagination-divider">|</span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span><el-pagination v-bind="$attrs" v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="pageSizes" layout="sizes, prev, pager, next, jumper" class="workspace-pagination" @size-change="emit('change')" @current-change="emit('change')" /></footer></template>
<script setup>
defineOptions({ inheritAttrs:false })
const page=defineModel('page',{type:Number,required:true})
const size=defineModel('size',{type:Number,required:true})
defineProps({total:{type:Number,required:true},pageSizes:{type:Array,default:()=>[10,20,50,100]}})
const emit=defineEmits(['change'])
</script>
<style scoped>
.workspace-pagination-footer { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; padding:0 16px; border-top:1px solid var(--ui-border-soft); background:var(--ui-surface); }
.pagination-summary { color:var(--ui-muted); font-size:12px; font-variant-numeric:tabular-nums; }.pagination-summary strong { color:var(--ui-primary); font-weight:500; }.pagination-divider { margin:0 12px; color:var(--ui-border); }
.workspace-pagination :deep(.btn-prev), .workspace-pagination :deep(.btn-next) { border:1px solid var(--ui-border); border-radius:7px; background:var(--ui-surface); color:var(--ui-muted); }
.workspace-pagination :deep(button:disabled) { color:var(--ui-subtle); opacity:.5; }
@media(max-width:768px) { .workspace-pagination-footer { justify-content:center; padding-top:12px; } }

.workspace-pagination { padding:16px; margin:0; justify-content:flex-end; flex-wrap:wrap; gap:8px; }
.workspace-pagination :deep(.el-pager li) { border:1px solid var(--ui-border); border-radius:6px; margin:0 3px; min-width:28px; font-size:12px; font-weight:500; }
.workspace-pagination :deep(.el-pager li.is-active) { background:var(--ui-primary); border-color:var(--ui-primary); color:var(--ui-on-primary); }
@media(max-width:768px) { .workspace-pagination { padding:12px 8px; justify-content:center; } }
</style>
