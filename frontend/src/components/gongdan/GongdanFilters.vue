<template>
  <WorkspaceFilterBar compact layout="gongdan">

          <el-input v-model="queryParams.gongdanId" placeholder="工单编号" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <el-input v-model="queryParams.externalId" placeholder="BOMC工单号" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <el-input class="filter-wide" v-model="queryParams.gongdanTitle" placeholder="工单标题" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <el-input class="filter-wide" v-model="queryParams.alarmObject" placeholder="告警对象" clearable @keyup.enter="emit('search', $event)" @clear="emit('search', $event)" size="small" ><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <el-select v-model="queryParams.gongdanType" placeholder="类型" clearable @change="emit('search', $event)" size="small">
            <el-option label="全部" :value="null" />
            <el-option label="自动" value="自动" />
            <el-option label="手动" value="手动" />
          </el-select>
          <el-select v-model="queryParams.alarmLevel" placeholder="告警级别" clearable @change="emit('search', $event)" size="small">
            <el-option label="全部" :value="null" />
            <el-option v-for="item in alarmLevelOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="queryParams.synced" placeholder="BOMC同步" clearable @change="emit('search', $event)" size="small">
            <el-option label="全部" :value="null" />
            <el-option label="已推送" :value="1" />
            <el-option label="未推送/失败" :value="0" />
          </el-select>
          <el-select v-model="queryParams.closedStatus" placeholder="关单状态" clearable @change="emit('search', $event)" size="small">
            <el-option label="全部" :value="null" />
            <el-option label="未关单" :value="0" />
            <el-option label="已关单" :value="1" />
          </el-select>
          <el-date-picker
            v-model="queryParams.gongdanTimeRange"
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
defineProps({alarmLevelOptions:{type:Array,default:()=>[]}})
const emit=defineEmits(["search", "reset"])
</script>
