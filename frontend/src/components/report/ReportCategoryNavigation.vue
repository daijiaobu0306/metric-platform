<template><section class="report-category-panel"><div class="report-category-row"><span class="category-heading">业务域分类</span><WorkspaceCategoryNavigation v-model="category" :items="categories" @change="emit('category-change',$event)" /><el-button link type="primary" @click="emit('refresh')"><el-icon><Refresh /></el-icon>刷新</el-button></div><div v-if="showSubcategories && subCategories.length" class="report-subcategory-row"><span class="subcategory-heading">指标小类</span><WorkspaceCategoryNavigation v-model="subcategory" :items="subCategories" secondary @change="emit('subcategory-change',$event)" /></div></section></template>
<script setup>
import WorkspaceCategoryNavigation from '@/components/workspace/WorkspaceCategoryNavigation.vue'
const category=defineModel('category',{type:String,required:true})
const subcategory=defineModel('subcategory',{type:String,default:''})
defineProps({categories:Array,subCategories:Array,showSubcategories:Boolean})
const emit=defineEmits(['category-change','subcategory-change','refresh'])
</script>
<style scoped>
.report-category-panel { margin-bottom:16px; padding:16px 20px; border:1px solid var(--ui-border); border-radius:14px; background:var(--ui-surface); box-shadow:var(--ui-shadow-sm); }.report-category-row,.report-subcategory-row { display:flex; align-items:center; gap:12px; min-width:0; }.report-category-row > :deep(.workspace-category-navigation), .report-subcategory-row > :deep(.workspace-category-navigation) { flex:1; min-width:0; }.category-heading { display:flex; align-items:center; gap:8px; color:var(--ui-heading); font-size:12px; font-weight:600; white-space:nowrap; }.category-heading::before { content:''; height:16px; width:4px; border-radius:3px; background:var(--ui-primary); }.report-subcategory-row { margin-top:10px; padding-top:10px; border-top:1px solid var(--ui-border-soft); }.subcategory-heading { color:var(--ui-subtle); font-size:11px; white-space:nowrap; }
</style>
