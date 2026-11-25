<template><WorkspaceFilterBar compact layout="auto">
          <el-select v-model="queryParams.systemId" placeholder="系统" clearable @change="emit('system-change', $event)" size="small">
            <el-option v-for="s in systemList" :key="s.id" :label="s.systemName" :value="s.id" />
          </el-select>
          <el-select v-model="queryParams.categoryId" placeholder="指标大类" clearable @change="emit('category-change', $event)" size="small">
            <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
          </el-select>
          <el-select class="filter-wide" v-model="queryParams.metricId" placeholder="指标" clearable @change="emit('filter-change', $event)" :disabled="!queryParams.categoryId" size="small">
            <el-option v-for="m in metricList" :key="m.id" :label="m.metricName" :value="m.id" />
          </el-select>
          <el-select v-model="queryParams.issueType" placeholder="问题类型" clearable @change="emit('filter-change', $event)" size="small">
            <el-option label="故障" value="故障" />
            <el-option label="缺陷" value="缺陷" />
            <el-option label="变更" value="变更" />
          </el-select>
          <el-select v-model="queryParams.issueSource" placeholder="问题来源" clearable @change="emit('filter-change', $event)" size="small">
            <el-option label="告警" value="告警" />
            <el-option label="人工巡检" value="人工巡检" />
          </el-select>
          <el-select v-model="queryParams.resolveStatus" placeholder="解决状态" clearable @change="emit('filter-change', $event)" size="small">
            <el-option label="未解决" value="未解决" />
            <el-option label="已解决" value="已解决" />
          </el-select>
          <el-select v-model="queryParams.issueAttribute" placeholder="问题属性" clearable @change="emit('filter-change', $event)" size="small">
            <el-option label="新问题" value="新问题" />
            <el-option label="老问题" value="老问题" />
          </el-select>

          <el-date-picker
            v-model="queryParams.registerTimeRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            @change="emit('filter-change', $event)"

            size="small"
          />
          <el-select v-model="queryParams.hasAttachment" placeholder="附件" clearable @change="emit('filter-change', $event)" size="small">
            <el-option label="有附件" :value="true" />
            <el-option label="无附件" :value="false" />
          </el-select>
          <el-input v-model="queryParams.discoverer" placeholder="发现者" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" />
          <el-input class="filter-wide" v-model="queryParams.keyword" placeholder="问题描述" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" />
          <div class="filter-actions"><el-button type="primary" size="small" @click="emit('search', $event)">
            <el-icon><Search /></el-icon>查询
          </el-button>
          <el-button size="small" @click="emit('reset', $event)">重置</el-button></div>
        </WorkspaceFilterBar></template>
<script setup>
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
const queryParams=defineModel('filters',{type:Object,required:true})
defineProps({systemList:{type:Array,default:()=>[]},categoryList:{type:Array,default:()=>[]},metricList:{type:Array,default:()=>[]}})
const emit=defineEmits(['system-change', 'category-change', 'filter-change', 'search', 'reset'])
</script>
