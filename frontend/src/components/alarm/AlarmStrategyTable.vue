<template>
              <el-table v-sticky-header v-mirror-scroll :data="rows" row-key="id" @selection-change="emit('selection-change', $event)">
                <el-table-column type="selection" width="40" fixed="left"/>
                <el-table-column prop="id" label="ID" width="60" />
                <el-table-column prop="strategyName" label="策略名称" width="180" />
                <el-table-column prop="strategyType" label="策略类型" width="100">
                  <template #default="{ row }">
                    <el-tag>{{ row.strategyType === 'raw' ? '原始数据' : row.strategyType === 'metric' ? '指标值' : '健康度' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="targetType" label="对象" width="80">
                  <template #default="{ row }">
                    <el-tag type="info" size="small">{{ row.targetType === 'raw' ? '数据表' : row.targetType === 'system' ? '系统' : row.targetType === 'category' ? '大类' : row.targetType === 'subCategory' ? '小类' : '指标' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="targetName" label="数据来源／对象" min-width="180" />
                <el-table-column label="告警配置" min-width="300">
                  <template #default="{ row }">
                    <div class="config-cell" role="button" tabindex="0" @click="emit('edit', row)" @keydown.enter="emit('edit', row)" @keydown.space.self.prevent="emit('edit', row)">
                      <WorkspaceStatusBadge :tone="row.alarmEnabled ? 'warning' : 'muted'" :pulse="!!row.alarmEnabled">{{ row.alarmEnabled ? '启用' : '禁用' }}</WorkspaceStatusBadge>
                      <span style="font-size: 12px; color: #666;">{{ ruleSummary(row, 'alarm') }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="工单配置" min-width="300">
                  <template #default="{ row }">
                    <div class="config-cell" role="button" tabindex="0" @click="emit('edit', row)" @keydown.enter="emit('edit', row)" @keydown.space.self.prevent="emit('edit', row)">
                      <WorkspaceStatusBadge :tone="row.gongdanEnabled ? 'success' : 'muted'" :pulse="!!row.gongdanEnabled">{{ row.gongdanEnabled ? '启用' : '禁用' }}</WorkspaceStatusBadge>
                      <span style="font-size: 12px; color: #666;">{{ ruleSummary(row, 'gongdan') }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="status" label="状态" width="70">
                  <template #default="{ row }">
                    <el-switch
                      v-model="row.status"
                      :active-value="1"
                      :inactive-value="0"
                      @change="emit('status-change', row)"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="100" fixed="right">
                  <template #default="{ row }">
                    <el-button type="primary" link size="small" @click="emit('edit', row)">编辑</el-button>
                  </template>
                </el-table-column>
              </el-table>
</template>
<script setup>
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { ruleSummary } from '@/utils/strategyRules'
defineProps({rows:{type:Array,default:()=>[]}})
const emit=defineEmits(['selection-change','edit','status-change'])
</script>
<style scoped>
.config-cell { display:flex; align-items:flex-start; gap:8px; cursor:pointer; padding:6px; border-radius:6px; } .config-cell:hover { background:var(--ui-primary-soft); } .config-cell span { color:var(--ui-text) !important; line-height:1.7; }
.config-cell:focus-visible { outline:2px solid var(--ui-primary); outline-offset:2px; }
</style>
