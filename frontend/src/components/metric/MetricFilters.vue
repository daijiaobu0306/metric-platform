<template>
  <WorkspaceFilterBar compact layout="auto">

          <el-select v-model="queryParams.systemId" placeholder="系统" clearable filterable @change="emit('filter-change', $event)">
            <el-option v-for="sys in systemOptions" :key="sys.id" :label="sys.systemName" :value="sys.id" />
          </el-select>
          <el-select v-model="queryParams.categoryId" placeholder="指标大类" clearable filterable @change="emit('filter-change', $event)">
            <el-option v-for="cat in categoryOptions" :key="cat.id" :label="cat.categoryName" :value="cat.id" />
          </el-select>
          <el-select v-model="queryParams.subCategoryName" placeholder="指标小类" clearable filterable @change="emit('filter-change', $event)">
            <el-option v-for="sub in subCategoryOptions" :key="sub" :label="sub" :value="sub" />
          </el-select>
          <el-select v-model="queryParams.metricType" placeholder="类型" clearable @change="emit('filter-change', $event)">
            <el-option v-for="type in metricTypeOptions" :key="type" :label="type" :value="type" />
          </el-select>
          <el-select v-model="queryParams.status" placeholder="状态" clearable @change="emit('filter-change', $event)">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
          <el-input v-model="queryParams.metricName" placeholder="指标名称" clearable @keyup.enter="emit('filter-change', $event)" @clear="emit('filter-change', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <el-input v-model="queryParams.metricCode" placeholder="指标编码" clearable @keyup.enter="emit('filter-change', $event)" @clear="emit('filter-change', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <div class="filter-actions"><el-button type="primary" size="small" @click="emit('search', $event)">
            <el-icon><Search /></el-icon>查询
          </el-button>
          <el-button size="small" @click="emit('reset', $event)">重置</el-button></div>

  </WorkspaceFilterBar>
</template>
<script setup>
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
const queryParams=defineModel('filters',{type:Object,required:true})
defineProps({systemOptions:{type:Array,default:()=>[]},categoryOptions:{type:Array,default:()=>[]},subCategoryOptions:{type:Array,default:()=>[]},metricTypeOptions:{type:Array,default:()=>[]}})
const emit=defineEmits(["filter-change", "search", "reset"])
</script>
