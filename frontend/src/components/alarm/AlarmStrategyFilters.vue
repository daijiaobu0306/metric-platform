<template><WorkspaceFilterBar compact layout="strategy">
                <el-input v-model="strategyFilters.id" placeholder="策略ID" clearable @keyup.enter="emit('search')" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-input v-model="strategyFilters.name" placeholder="策略名称" clearable @keyup.enter="emit('search')" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-select v-model="strategyFilters.type" placeholder="策略类型" clearable @change="emit('search')">
                  <el-option label="指标值" value="metric" />
                  <el-option label="健康度" value="health" />
                  <el-option label="原始数据" value="raw" />
                </el-select>
                <el-select v-model="strategyFilters.targetType" placeholder="对象" clearable @change="emit('search')">
                  <el-option label="系统" value="system" />
                  <el-option label="大类" value="category" />
                  <el-option label="小类" value="subCategory" />
                  <el-option label="指标" value="metric" />
                  <el-option label="数据表" value="raw" />
                </el-select>
                <!-- 指标大类/小类筛选：用于把某个大类下的指标值策略一起找出来批量启停 -->
                <el-select v-model="strategyFilters.categoryId" placeholder="指标大类" clearable filterable @change="onCategoryChange">
                  <el-option v-for="c in categories" :key="c.id" :label="c.categoryName" :value="c.id" />
                </el-select>
                <el-select v-model="strategyFilters.subCategoryId" placeholder="指标小类" clearable filterable
                           :disabled="!strategyFilters.categoryId" @change="emit('search')">
                  <el-option v-for="s in subCategories" :key="s.id" :label="s.subCategoryName" :value="s.id" />
                </el-select>
                <el-select v-model="strategyFilters.alarmEnabled" placeholder="告警" clearable @change="emit('search')">
                  <el-option label="启用" :value="1" />
                  <el-option label="禁用" :value="0" />
                </el-select>
                <el-select v-model="strategyFilters.gongdanEnabled" placeholder="工单" clearable @change="emit('search')">
                  <el-option label="启用" :value="1" />
                  <el-option label="禁用" :value="0" />
                </el-select>
                <div class="filter-actions"><el-button type="primary" size="small" @click="emit('search')">
                  <el-icon><Search /></el-icon>查询
                </el-button>
                <el-button size="small" @click="emit('reset')">重置</el-button></div>
              </WorkspaceFilterBar>
              <section class="strategy-actions" aria-label="策略批量操作">
                <el-button type="danger" size="small" :disabled="selectedStrategyRows.length === 0" @click="emit('delete')">
                  <el-icon><Delete /></el-icon>删除{{ selectedStrategyRows.length > 0 ? ` (${selectedStrategyRows.length})` : '' }}
                </el-button>
                <el-button type="warning" size="small" :disabled="selectedStrategyRows.length === 0" @click="emit('toggle-status')">
                  批量{{ allSelectedEnabled ? '禁用' : '启用' }}{{ selectedStrategyRows.length > 0 ? ` (${selectedStrategyRows.length})` : '' }}
                </el-button>
                <span class="strategy-actions__divider" />
                <el-button size="small" :disabled="!hasScopeFilter || filteredTotal === 0 || filterBatchLoading" :loading="filterBatchLoading" @click="emit('batch-filter-status', 1)">
                  按筛选全部启用{{ filteredTotal ? `（${filteredTotal}）` : '' }}
                </el-button>
                <el-button size="small" :disabled="!hasScopeFilter || filteredTotal === 0 || filterBatchLoading" :loading="filterBatchLoading" @click="emit('batch-filter-status', 0)">
                  按筛选全部停用{{ filteredTotal ? `（${filteredTotal}）` : '' }}
                </el-button>
<span class="strategy-actions__selection">{{ selectedStrategyRows.length ? `已选择 ${selectedStrategyRows.length} 条策略` : (hasScopeFilter ? '按大类/小类批量操作会覆盖当前筛选的全部结果' : '勾选下方策略后可批量操作，或先选指标大类/小类') }}</span></section></template>
<script setup>
import { ref, watch } from 'vue'
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
import { metricApi, subcategoryApi } from '@/api'
const strategyFilters=defineModel('filters',{type:Object,required:true})
defineProps({
  selectedStrategyRows:{type:Array,default:()=>[]},
  allSelectedEnabled:Boolean,
  hasScopeFilter:Boolean,
  filteredTotal:{type:Number,default:0},
  filterBatchLoading:Boolean
})
const emit=defineEmits(['search','reset','delete','toggle-status','batch-filter-status'])
const categories=ref([])
const subCategories=ref([])
async function loadCategories(){
  try{ categories.value=(await metricApi.categoryListAll()).data||[] }catch{ categories.value=[] }
}
async function loadSubCategories(categoryId){
  if(!categoryId){ subCategories.value=[]; return }
  try{ subCategories.value=(await subcategoryApi.list(categoryId)).data||[] }catch{ subCategories.value=[] }
}
function onCategoryChange(value){
  strategyFilters.value.subCategoryId=null
  loadSubCategories(value)
  emit('search')
}
watch(()=>strategyFilters.value.categoryId,(value)=>{ if(value) loadSubCategories(value); else subCategories.value=[] })
loadCategories()
</script>

<style scoped>
.strategy-actions { display:flex; align-items:center; flex-wrap:wrap; gap:10px; padding:14px 16px; margin:14px 0; border:1px solid var(--ui-border); border-radius:var(--ui-radius-lg); background:var(--ui-surface); }
.strategy-actions :deep(.el-button) { margin:0; width:auto; }
.strategy-actions__divider { width:1px; height:20px; background:var(--ui-border); margin:0 2px; }
.strategy-actions__selection { color:var(--ui-muted); font-size:12px; margin-left:auto; }
</style>
