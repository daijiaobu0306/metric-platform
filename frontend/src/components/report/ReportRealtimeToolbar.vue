<template>
              <div class="report-realtime-toolbar" v-balanced-query>
                <div class="query-field"><span class="filter-label">状态：</span>
                <el-select v-model="filterStatus" placeholder="全部" clearable style="width: 100px;" size="small">
                  <el-option label="全部" value="" />
                  <el-option v-if="activeCategory !== 'businessValue'" label="正常" value="正常" />
                  <el-option v-if="activeCategory !== 'businessValue'" label="异常" value="__abnormal__" />
                  <el-option v-if="['satisfactionMetrics', 'resourceInput', 'businessValue'].includes(activeCategory)" label="待配置" value="待配置" /><el-option v-if="activeCategory === 'satisfactionMetrics'" label="待日历" value="待日历" /><el-option v-if="['satisfactionMetrics', 'resourceInput'].includes(activeCategory)" label="告警停用" value="告警停用" />
                  <el-option v-if="['satisfactionMetrics', 'businessValue'].includes(activeCategory)" label="无数据" value="无数据" />
                  <el-option v-if="activeCategory === 'businessValue'" label="待判定" value="待判定" />
                </el-select></div>
                <div v-if="activeCategory === 'compliance'" class="query-field"><span class="filter-label">时间：</span>
                <el-select v-model="filterTimeRange" placeholder="全部" style="width: 120px;" size="small">
                  <el-option label="全部" value="" />
                  <el-option label="最近1小时" value="1h" />
                  <el-option label="最近6小时" value="6h" />
                  <el-option label="今天" value="today" />
                  <el-option label="最近24小时" value="24h" />
                </el-select></div>
                <div class="query-field"><span class="filter-label">指标名称：</span>
                <el-input v-model="filterMetricName" placeholder="搜索指标名称" style="width: 140px;" size="small" clearable /></div>
                <div class="query-actions"><el-button type="primary" size="small" @click="emit('search')">
                  <el-icon><Search /></el-icon>查询
                </el-button>
                <el-button size="small" @click="emit('reset')">重置</el-button></div>
                <div class="toolbar-spacer"></div>
                <!-- 统计卡片 -->
                <div v-if="activeCategory !== 'businessValue'" class="stat-cards">
                  <div class="stat-card stat-normal clickable" role="button" tabindex="0" @keydown.enter="emit('normal')" @keydown.space.self.prevent="emit('normal')" @click="emit('normal')">
                    <span class="stat-value">{{ normalCount }}</span>
                    <span class="stat-label">正常</span>
                  </div>
                  <div class="stat-card stat-warning clickable" role="button" tabindex="0" @keydown.enter="emit('abnormal')" @keydown.space.self.prevent="emit('abnormal')" @click="emit('abnormal')">
                    <span class="stat-value">{{ warningCount }}</span>
                    <span class="stat-label">异常</span>
                  </div>
                </div>
                <el-popover popper-class="workspace-theme workspace-popover" placement="bottom" :width="280" trigger="click">
                  <template #reference>
                    <el-button size="small">
                      <el-icon><Setting /></el-icon>列设置 ({{ visibleRealtimeColumns.length }}/{{ realtimeColumnOptions.length }})
                    </el-button>
                  </template>
                  <div style="margin-bottom: 8px; color: #909399; font-size: 12px;">已选 {{ visibleRealtimeColumns.length }} / {{ realtimeColumnOptions.length }} 列</div>
                  <el-checkbox-group v-model="visibleRealtimeColumns" class="column-toggle-group" @change="emit('columns-change', $event)">
                    <el-checkbox v-for="col in realtimeColumnOptions" :key="col.prop" :value="col.prop" class="column-toggle-item">
                      {{ col.label }}
                    </el-checkbox>
                  </el-checkbox-group>
                </el-popover>
              </div>
</template>
<script setup>
const filterStatus=defineModel('status',{type:String,default:''})
const filterTimeRange=defineModel('time',{type:String,default:''})
const dateRange=defineModel('dateRange',{type:Array,default:()=>[]})
const filterMetricName=defineModel('name',{type:String,default:''})
const visibleRealtimeColumns=defineModel('columns',{type:Array,required:true})
defineProps({activeCategory:String,normalCount:Number,warningCount:Number,realtimeColumnOptions:Array})
const emit=defineEmits(['search','reset','columns-change','normal','abnormal'])
</script>
<style scoped>
.report-realtime-toolbar { display:flex; align-items:center; flex-wrap:wrap; gap:8px; padding:16px 20px; border-bottom:1px solid var(--ui-border-soft); background:var(--ui-surface); } .filter-label { color:var(--ui-muted); font-size:11px; } .toolbar-spacer { flex:1; } .stat-cards { display:flex; gap:8px; } .stat-card { display:flex; align-items:center; gap:5px; padding:6px 10px; border:1px solid var(--ui-success-border); border-radius:9px; background:var(--ui-success-soft); color:var(--ui-success); font-size:11px; cursor:pointer; }.stat-card::before { content:''; width:6px; height:6px; border-radius:50%; background:currentColor; }.stat-warning { border-color:var(--ui-danger-border); background:var(--ui-danger-soft); color:var(--ui-danger); }.stat-value { font-weight:600; font-variant-numeric:tabular-nums; }.stat-card:focus-visible { outline:2px solid var(--ui-primary); outline-offset:2px; }.column-toggle-group { display:flex; flex-direction:column; gap:3px; }.column-toggle-item { margin:0; }
</style>
