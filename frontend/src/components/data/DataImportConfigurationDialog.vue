<template>
        <el-dialog class="workspace-theme" v-model="visible" title="导入配置" :width="width">
        <div class="import-config-list">
          <el-alert title="未接入真实解析的旧通道已停止采集，不会移动文件或写入指标。新数据源请在“采集任务”中配置独立目录。" type="info" :closable="false" />
          <div class="config-basepath-hint" v-if="basePath">
            <el-icon style="color: #409eff; margin-right: 4px;"><InfoFilled /></el-icon>
            文件根目录：<code>{{ basePath }}</code>（相对路径将拼接此前缀，绝对路径直接使用）
          </div>
          <div class="config-toolbar">
            <el-button type="primary" size="small" @click="emit('add')">
              <el-icon><Plus /></el-icon>新增配置
            </el-button>
            <el-button type="primary" size="small" :disabled="selectedImportConfigs.length === 0" @click="emit('edit')">编辑</el-button>
            <el-button type="danger" size="small" :disabled="selectedImportConfigs.length === 0" @click="emit('delete')">删除</el-button>
            <el-button type="warning" size="small" :disabled="selectedImportConfigs.length === 0 || selectedImportConfigs.some(row => !row.collectorAvailable || row.enabled !== 1)" @click="emit('scan')">扫描</el-button>
            <div class="config-scan-check">
              <span v-if="scanHealth" class="config-scan-result" :class="scanHealth.healthy ? 'is-ok' : 'is-bad'">
                <i aria-hidden="true"></i>{{ scanHealth.healthy ? `目录检查正常（${scanHealth.totalChecked} 个数据源）` : `${scanHealth.issueCount} 个数据源目录异常` }}
              </span>
              <el-button size="small" :loading="scanHealthLoading" @click="emit('recheck')">
                <el-icon v-if="!scanHealthLoading"><Refresh /></el-icon>重新检查
              </el-button>
            </div>
          </div>
          <el-table v-sticky-header v-mirror-scroll :data="importConfigList" border stripe size="small" @selection-change="emit('selection-change', $event)">
            <el-table-column type="selection" width="50" fixed="left"/>
            <el-table-column prop="subCategoryCode" label="小类编码" width="120" />
            <el-table-column prop="importPath" label="监控目录" min-width="150" class-name="wrap-cell" />
            <el-table-column prop="archivePath" label="归档目录" min-width="150" class-name="wrap-cell" />
            <el-table-column label="扫描间隔" width="100"><template #default="{ row }">{{ row.collectorAvailable ? `${row.scanIntervalMinutes} 分钟` : '—' }}</template></el-table-column>
            <el-table-column prop="collectionStatus" label="采集状态" width="165">
              <template #default="{ row }">
                <el-tag :type="!row.collectorAvailable ? 'warning' : row.enabled === 1 ? 'success' : 'info'" size="small">
                  {{ row.collectionStatus }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-dialog>
</template>
<script setup>
const visible=defineModel({type:Boolean,default:false})
defineProps({width:[Number,String],basePath:String,scanHealth:{type:Object,default:null},scanHealthLoading:Boolean,selectedImportConfigs:{type:Array,default:()=>[]},importConfigList:{type:Array,default:()=>[]}})
const emit=defineEmits(['add','edit','delete','scan','recheck','selection-change'])
</script>
<style scoped>.config-toolbar { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin:14px 0; }.config-basepath-hint { margin-top:12px; padding:10px 12px; border:1px solid var(--ui-border); border-radius:8px; background:var(--ui-page); font-size:12px; color:var(--ui-muted); }.config-basepath-hint code { color:var(--ui-primary-strong); overflow-wrap:anywhere; }.config-scan-check { display:flex; align-items:center; gap:8px; margin-left:auto; }.config-scan-result { display:inline-flex; align-items:center; gap:6px; font-size:12px; color:var(--ui-muted); white-space:nowrap; }.config-scan-result i { width:6px; height:6px; border-radius:50%; background:currentColor; flex:none; }.config-scan-result.is-ok { color:var(--ui-success); }.config-scan-result.is-bad { color:var(--ui-danger); }</style>
