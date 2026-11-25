<template>
        <el-dialog class="workspace-theme" v-model="visible" title="CRM前台自定义返回信息关键词配置" :width="width">
        <div class="keyword-config">
          <div class="config-toolbar">
            <el-button type="primary" @click="emit('add')">
              <el-icon><Plus /></el-icon>新增关键词
            </el-button>
          </div>
          <el-table v-sticky-header v-mirror-scroll :data="keywordList" border stripe v-loading="keywordLoading">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="metricId" label="指标ID" width="100" />
            <el-table-column prop="keyword" label="关键词" min-width="200" class-name="wrap-cell" />
            <el-table-column prop="createTime" label="创建时间" width="160">
              <template #default="{ row }">
                {{ row.createTime ? formatDateTime(row.createTime) : '-' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="emit('edit', row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="emit('delete', row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-dialog>
</template>
<script setup>
const visible=defineModel({type:Boolean,default:false})
defineProps({width:[Number,String],keywordList:{type:Array,default:()=>[]},keywordLoading:Boolean,formatDateTime:{type:Function,required:true}})
const emit=defineEmits(['add','edit','delete'])
</script>
<style scoped>.config-toolbar { display:flex; gap:8px; margin-bottom:16px; }</style>
