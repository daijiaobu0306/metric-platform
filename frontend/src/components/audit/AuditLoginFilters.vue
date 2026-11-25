<template><WorkspaceFilterBar compact layout="audit">
            <el-input v-model="loginLogParams.username" placeholder="用户名" clearable size="small" />
            <el-input v-model="loginLogParams.clientIp" placeholder="客户端IP" clearable size="small" />
            <el-select v-model="loginLogParams.browserName" placeholder="浏览器" clearable size="small">
              <el-option label="全部" value="" />
              <el-option label="Chrome" value="Chrome" />
              <el-option label="Firefox" value="Firefox" />
              <el-option label="Edge" value="Edge" />
              <el-option label="Safari" value="Safari" />
            </el-select>
            <el-select v-model="loginLogParams.osName" placeholder="操作系统" clearable size="small">
              <el-option label="全部" value="" />
              <el-option label="Windows" value="Windows" />
              <el-option label="macOS" value="macOS" />
              <el-option label="Linux" value="Linux" />
              <el-option label="Android" value="Android" />
              <el-option label="iOS" value="iOS" />
            </el-select>
            <el-select v-model="loginLogParams.deviceType" placeholder="设备类型" clearable size="small">
              <el-option label="全部" value="" />
              <el-option label="桌面" value="desktop" />
              <el-option label="平板" value="tablet" />
              <el-option label="手机" value="mobile" />
            </el-select>
            <el-select v-model="loginLogParams.isActive" placeholder="状态" clearable size="small">
              <el-option label="全部" value="" />
              <el-option label="在线" :value="1" />
              <el-option label="已离开" :value="0" />
            </el-select>
            <el-date-picker v-model="loginLogParams.dateRange" type="daterange" range-separator="-"
              start-placeholder="登录开始" end-placeholder="登录结束" value-format="YYYY-MM-DD"
              size="small" clearable />
            <div class="filter-actions">
            <el-button type="primary" size="small" @click="emit('search')">
              <el-icon><Search /></el-icon>查询
            </el-button>
            <el-button size="small" @click="emit('reset')">重置</el-button>
            <el-button type="danger" size="small" :disabled="loginLogSelectedRows.length === 0" @click="emit('delete')">
              批量删除 ({{ loginLogSelectedRows.length }})
            </el-button>
            </div>
          </WorkspaceFilterBar></template>
<script setup>
import WorkspaceFilterBar from '@/components/workspace/WorkspaceFilterBar.vue'
const loginLogParams=defineModel('filters',{type:Object,required:true})
defineProps({loginLogSelectedRows:{type:Array,default:()=>[]}})
const emit=defineEmits(['search','reset','delete'])
</script>
