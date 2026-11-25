<template>
  <WorkspaceFilterBar compact layout="alarm">

              <el-select v-model="queryParams.systemId" placeholder="系统" clearable @change="emit('system-change', $event)">
                <el-option v-for="s in systemList" :key="s.id" :label="s.systemName" :value="s.id" />
              </el-select>
              <el-select v-model="queryParams.categoryId" placeholder="指标大类" clearable @change="emit('category-change', $event)">
                <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
              </el-select>
              <el-select v-model="queryParams.alarmType" placeholder="告警类型" clearable @change="emit('search', $event)">
                <el-option label="全部" :value="null" />
                <el-option v-for="type in alarmTypeOptions" :key="type" :label="type" :value="type" />
              </el-select>
              <el-select v-model="queryParams.processStatus" placeholder="处理状态" clearable @change="emit('search', $event)">
                <el-option label="全部" :value="null" />
                <el-option label="未处理" :value="0" />
                <el-option label="已处理" :value="1" />
              </el-select>
              <el-select v-model="queryParams.shieldStatus" placeholder="屏蔽状态" clearable @change="emit('search', $event)">
                <el-option label="全部" :value="null" />
                <el-option label="未屏蔽" :value="0" />
                <el-option label="已屏蔽" :value="1" />
              </el-select>
              <el-input class="filter-wide" v-model="queryParams.alarmContent" placeholder="告警内容" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
              <el-date-picker
                v-model="queryParams.alarmTimeRange"
                type="daterange"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                value-format="YYYY-MM-DD"
                @change="emit('search', $event)"

                size="small"
              />
              <div class="filter-actions"><el-button type="primary" size="small" @click="emit('search', $event)">
                <el-icon><Search /></el-icon>查询
              </el-button>
              <el-button size="small" @click="emit('reset', $event)">重置</el-button></div>

  </WorkspaceFilterBar>
</template>
<script setup>
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
const queryParams=defineModel('filters',{type:Object,required:true})
defineProps({systemList:{type:Array,default:()=>[]},categoryList:{type:Array,default:()=>[]},alarmTypeOptions:{type:Array,default:()=>[]}})
const emit=defineEmits(["system-change", "category-change", "search", "reset"])
</script>
