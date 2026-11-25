<template><WorkspaceFilterBar compact layout="audit">
            <el-select v-model="queryParams.operationType" placeholder="操作类型" clearable>
              <el-option label="全部" value="" />
              <el-option label="新增" value="新增" />
              <el-option label="修改" value="修改" />
              <el-option label="删除" value="删除" />
            </el-select>
            <div class="filter-actions">
            <el-button size="small" type="primary" @click="emit('search')">
              <el-icon><Search /></el-icon>查询
            </el-button>
            <el-button size="small" @click="emit('reset')">重置</el-button>
            <el-button size="small" type="danger" :disabled="metricSelectedRows.length === 0" @click="emit('delete')">
              批量删除 ({{ metricSelectedRows.length }})
            </el-button>
            </div>
          </WorkspaceFilterBar></template>
<script setup>
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
const queryParams=defineModel('filters',{type:Object,required:true})
defineProps({metricSelectedRows:{type:Array,default:()=>[]}})
const emit=defineEmits(['search','reset','delete'])
</script>
