<template><el-tabs v-model="selection" class="workspace-category-navigation" :class="{ 'is-secondary': secondary }" @tab-change="emit('change', $event)"><el-tab-pane v-for="item in items" :key="item.code" :name="item.code"><template #label><span class="category-label"><span v-if="secondary" class="category-dot"></span><el-icon v-if="!secondary"><component :is="getWorkspaceCategoryIcon(item.code)" /></el-icon>{{ item.name }}<small v-if="item.count != null">{{ item.count }}</small></span></template><slot :item="item" /></el-tab-pane><slot name="extra" /></el-tabs></template>
<script setup>
import { getWorkspaceCategoryIcon } from '@/utils/workspaceCategoryIcons'
const selection=defineModel({type:String,default:''})
defineProps({items:{type:Array,default:()=>[]},secondary:Boolean})
const emit=defineEmits(['change'])
</script>
<style scoped>
.workspace-category-navigation { min-width:0; }.workspace-category-navigation :deep(.el-tabs__header) { margin:0; }.workspace-category-navigation :deep(.el-tabs__nav-wrap::after), .workspace-category-navigation :deep(.el-tabs__active-bar) { display:none; }.workspace-category-navigation :deep(.el-tabs__nav) { display:flex; gap:6px; padding:4px 0; }.workspace-category-navigation :deep(.el-tabs__item) { height:36px; padding:0 14px; border:1px solid transparent; border-radius:9px; color:var(--ui-muted); font-size:12px; font-weight:500; }.workspace-category-navigation :deep(.el-tabs__item.is-active) { color:var(--ui-primary-strong); border-color:var(--ui-primary-border); background:var(--ui-primary-soft); font-weight:600; }.category-label { display:inline-flex; align-items:center; gap:7px; }small { padding:1px 6px; border-radius:12px; background:var(--ui-page); color:var(--ui-muted); font-size:10px; }.category-dot { width:6px; height:6px; border-radius:50%; background:var(--ui-subtle); }.is-secondary :deep(.el-tabs__item) { height:30px; border:1px solid var(--ui-border); border-radius:18px; }.is-secondary :deep(.el-tabs__item.is-active) .category-dot { background:var(--ui-primary); }
/* Element Plus 会清零首个/末个 tab 的左右内边距（为让下划线式标签贴齐边缘），
   药丸标签下会变成文字贴边框；这里恢复对称内边距，保证文字在药丸内居中。 */
.workspace-category-navigation :deep(.el-tabs__header .el-tabs__item:nth-child(2)),
.workspace-category-navigation :deep(.el-tabs__header .el-tabs__item:last-child) { padding-left:14px; padding-right:14px; }
</style>
