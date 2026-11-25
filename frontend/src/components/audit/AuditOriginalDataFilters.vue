<template><WorkspaceFilterBar compact layout="audit">
            <el-select v-model="originalDataQueryParams.subCategoryCode" placeholder="数据类别" clearable>
              <el-option label="全部" value="" />
              <el-option label="系统稽核(jihe)" value="jihe" />
              <el-option label="系统稽核明细(jihe_detail)" value="jihe_detail" />
              <el-option label="系统运行(performance)" value="performance" />
              <el-option label="错误(error)" value="error" />
              <el-option label="前端错误(error_frontend)" value="error_frontend" />
              <el-option label="内部(inner)" value="inner" />
            </el-select>
            <div class="filter-actions">
            <el-button size="small" type="primary" @click="emit('search')">
              <el-icon><Search /></el-icon>查询
            </el-button>
            <el-button size="small" @click="emit('reset')">重置</el-button>
            <el-button size="small" type="danger" :disabled="originalDataSelectedRows.length === 0" @click="emit('delete')">
              批量删除 ({{ originalDataSelectedRows.length }})
            </el-button>
            </div>
          </WorkspaceFilterBar></template>
<script setup>
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
const originalDataQueryParams=defineModel('filters',{type:Object,required:true})
defineProps({originalDataSelectedRows:{type:Array,default:()=>[]}})
const emit=defineEmits(['search','reset','delete'])
</script>
