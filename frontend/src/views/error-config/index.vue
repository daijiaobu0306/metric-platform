<template>
  <div class="error-config-page">
    <el-tabs v-model="activeTab" class="config-tabs">
      <!-- 关键词配置 Tab -->
      <el-tab-pane label="关键词配置" name="keywords">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>业务返回异常关键词配置</span>
              <el-button type="primary" @click="handleKeywordAdd">
                <el-icon><Plus /></el-icon>新增关键词
              </el-button>
            </div>
          </template>

          <div class="filter-toolbar">
            <el-select v-model="keywordFilter.metricId" placeholder="选择指标" clearable filterable @change="loadKeywords">
              <el-option label="业务自定义返回异常" :value="7" />
            </el-select>
          </div>

          <el-table v-sticky-header v-mirror-scroll :data="keywordList" border v-loading="keywordLoading" stripe>
            <el-table-column label="操作" width="120" fixed>
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="handleKeywordEdit(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="handleKeywordDelete(row)">删除</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="metricId" label="指标ID" width="100" />
            <el-table-column prop="keyword" label="关键词" min-width="200" class-name="wrap-cell" />
            <el-table-column prop="createTime" label="创建时间" width="180">
              <template #default="{ row }">
                {{ row.createTime ? formatDateTime(row.createTime) : '-' }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 导入配置 Tab -->
      <el-tab-pane label="导入配置" name="import">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>原始数据导入配置</span>
              <el-button type="primary" @click="handleImportConfigAdd">
                <el-icon><Plus /></el-icon>新增配置
              </el-button>
            </div>
          </template>

          <div class="batch-toolbar">
            <el-button type="primary" size="small" :disabled="selectedImportConfigs.length === 0" @click="handleBatchEdit">编辑</el-button>
            <el-button type="danger" size="small" :disabled="selectedImportConfigs.length === 0" @click="handleBatchDelete">删除</el-button>
            <el-button type="warning" size="small" :disabled="selectedImportConfigs.length === 0" @click="handleBatchScan">触发扫描</el-button>
          </div>

          <el-table v-sticky-header v-mirror-scroll :data="importConfigList" border v-loading="importLoading" stripe @selection-change="handleImportSelectionChange">
            <el-table-column type="selection" width="50" fixed="left"/>
            <el-table-column prop="subCategoryCode" label="小类编码" width="130" />
            <el-table-column prop="importPath" label="监控目录" min-width="220" class-name="wrap-cell" />
            <el-table-column prop="archivePath" label="归档目录" min-width="220" class-name="wrap-cell" />
            <el-table-column prop="scanIntervalMinutes" label="扫描间隔(分钟)" width="120" />
            <el-table-column prop="enabled" label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
                  {{ row.enabled === 1 ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 关键词对话框 -->
    <div style="position: relative;">
    <el-dialog v-model="keywordDialogVisible" :title="keywordForm.id ? '编辑关键词' : '新增关键词'" :width="keywordDialogWidth">
      <el-form :model="keywordForm" label-width="100px">
        <el-form-item label="指标ID">
          <el-select v-model="keywordForm.metricId" placeholder="选择指标" filterable>
            <el-option label="业务自定义返回异常" :value="7" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="keywordForm.keyword" placeholder="请输入关键词" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keywordDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveKeyword">确定</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustKeywordDialogWidth" :visible="keywordDialogVisible" />
    </div>

    <!-- 导入配置对话框 -->
    <div style="position: relative;">
    <el-dialog v-model="importConfigDialogVisible" :title="importConfigForm.id ? '编辑配置' : '新增配置'" :width="importConfigDialogWidth">
      <el-form :model="importConfigForm" label-width="120px">
        <el-form-item label="小类编码">
          <el-select v-model="importConfigForm.subCategoryCode" placeholder="选择小类" filterable :disabled="!!importConfigForm.id">
            <el-option label="系统异常 (error)" value="error" />
            <el-option label="系统性能 (performance)" value="performance" />
            <el-option label="系统稽核 (jihe)" value="jihe" />
            <el-option label="前端页面异常 (error_frontend)" value="error_frontend" />
            <el-option label="对内部人员 (inner)" value="inner" />
          </el-select>
        </el-form-item>
        <el-form-item label="监控目录">
          <el-input v-model="importConfigForm.importPath" placeholder="如: files/raw_data/import/" />
        </el-form-item>
        <el-form-item label="归档目录">
          <el-input v-model="importConfigForm.archivePath" placeholder="如: files/raw_data/archive/" />
        </el-form-item>
        <el-form-item label="扫描间隔(分钟)">
          <el-input-number v-model="importConfigForm.scanIntervalMinutes" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="importConfigForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importConfigDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveImportConfig">确定</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustImportConfigDialogWidth" :visible="importConfigDialogVisible" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorKeywordApi, errorImportApi } from '../../api'
import { Plus } from '@element-plus/icons-vue'
import DialogSizeControls from '@/components/common/DialogSizeControls.vue'
import { useDialogSize } from '@/utils/useDialogSize'

const { width: keywordDialogWidth, adjustWidth: adjustKeywordDialogWidth } = useDialogSize('error-keyword-dialog', '450px')
const { width: importConfigDialogWidth, adjustWidth: adjustImportConfigDialogWidth } = useDialogSize('error-import-dialog', '550px')

const activeTab = ref('keywords')
const keywordList = ref([])
const keywordLoading = ref(false)
const keywordDialogVisible = ref(false)
const keywordForm = ref({
  id: null,
  metricId: 7,
  keyword: ''
})
const keywordFilter = ref({
  metricId: null
})

const importConfigList = ref([])
const importLoading = ref(false)
const selectedImportConfigs = ref([])
const importConfigDialogVisible = ref(false)
const importConfigForm = ref({
  id: null,
  subCategoryCode: 'error',
  importPath: 'files/raw_data/import/',
  archivePath: 'files/raw_data/archive/',
  scanIntervalMinutes: 5,
  enabled: 1
})

// 子类型切换时自动同步路径（统一指向 files/raw_data/）
watch(() => importConfigForm.value.subCategoryCode, (newVal) => {
  if (!newVal) return
  if (importConfigForm.value.id) return  // 编辑模式不覆盖
  importConfigForm.value.importPath = 'files/raw_data/import/'
  importConfigForm.value.archivePath = 'files/raw_data/archive/'
})

// 关键词相关方法
const loadKeywords = async () => {
  keywordLoading.value = true
  try {
    const res = await errorKeywordApi.list(keywordFilter.value.metricId)
    keywordList.value = res.data || []
  } catch (error) {
    console.error('加载关键词失败:', error)
  } finally {
    keywordLoading.value = false
  }
}

const handleKeywordAdd = () => {
  keywordForm.value = { id: null, metricId: 7, keyword: '' }
  keywordDialogVisible.value = true
}

const handleKeywordEdit = (row) => {
  keywordForm.value = { ...row }
  keywordDialogVisible.value = true
}

const handleKeywordDelete = (row) => {
  ElMessageBox.confirm('确定删除该关键词吗?', '提示', { type: 'warning' })
    .then(async () => {
      await errorKeywordApi.delete(row.id)
      ElMessage.success('删除成功')
      loadKeywords()
    }).catch(() => {})
}

const saveKeyword = async () => {
  try {
    if (keywordForm.value.id) {
      await errorKeywordApi.update(keywordForm.value.id, keywordForm.value)
    } else {
      await errorKeywordApi.create(keywordForm.value)
    }
    ElMessage.success('保存成功')
    keywordDialogVisible.value = false
    loadKeywords()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

// 导入配置相关方法
const loadImportConfigs = async () => {
  importLoading.value = true
  try {
    const res = await errorImportApi.list()
    importConfigList.value = res.data || []
  } catch (error) {
    console.error('加载导入配置失败:', error)
  } finally {
    importLoading.value = false
  }
}

const handleImportConfigAdd = () => {
  importConfigForm.value = {
    id: null,
    subCategoryCode: 'error',
    importPath: 'files/raw_data/import/',
    archivePath: 'files/raw_data/archive/',
    scanIntervalMinutes: 5,
    enabled: 1
  }
  importConfigDialogVisible.value = true
}

const handleImportConfigEdit = (row) => {
  importConfigForm.value = { ...row }
  importConfigDialogVisible.value = true
}

const handleImportConfigDelete = (row) => {
  ElMessageBox.confirm('确定删除该配置吗?', '提示', { type: 'warning' })
    .then(async () => {
      await errorImportApi.delete(row.id)
      ElMessage.success('删除成功')
      loadImportConfigs()
    }).catch(() => {})
}

const handleImportSelectionChange = (selection) => {
  selectedImportConfigs.value = selection
}

const handleBatchEdit = () => {
  if (selectedImportConfigs.value.length === 1) {
    handleImportConfigEdit(selectedImportConfigs.value[0])
  } else if (selectedImportConfigs.value.length > 1) {
    ElMessage.warning('请选择一条记录进行编辑')
  }
}

const handleBatchDelete = () => {
  if (selectedImportConfigs.value.length === 0) return
  ElMessageBox.confirm('确定删除选中的配置吗?', '提示', { type: 'warning' })
    .then(async () => {
      for (const row of selectedImportConfigs.value) {
        await errorImportApi.delete(row.id)
      }
      ElMessage.success('删除成功')
      selectedImportConfigs.value = []
      loadImportConfigs()
    }).catch(() => {})
}

const handleBatchScan = () => {
  if (selectedImportConfigs.value.length === 0) return
  ElMessageBox.confirm('确定触发选中的配置扫描吗?', '提示', { type: 'warning' })
    .then(async () => {
      for (const row of selectedImportConfigs.value) {
        await errorImportApi.triggerScan(row.subCategoryCode)
      }
      ElMessage.success('扫描任务已触发')
    }).catch(() => {})
}

const saveImportConfig = async () => {
  try {
    if (importConfigForm.value.id) {
      await errorImportApi.update(importConfigForm.value.id, importConfigForm.value)
    } else {
      await errorImportApi.create(importConfigForm.value)
    }
    ElMessage.success('保存成功')
    importConfigDialogVisible.value = false
    loadImportConfigs()
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || String(error)
    console.error('保存失败:', error)
    ElMessage.error('保存失败: ' + msg)
  }
}

const handleTriggerScan = async (row) => {
  try {
    await errorImportApi.triggerScan(row.subCategoryCode)
    ElMessage.success('扫描任务已触发')
  } catch (error) {
    console.error('触发扫描失败:', error)
  }
}

const formatDateTime = (datetime) => {
  if (!datetime) return ''
  const date = new Date(datetime)
  return date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

onMounted(() => {
  loadKeywords()
  loadImportConfigs()
})
</script>

<style scoped>
.error-config-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-toolbar {
  margin-bottom: 15px;
}

.batch-toolbar {
  margin-bottom: 15px;
}

.config-tabs {
  padding: 0;
}

@media screen and (max-width: 768px) {
  .card-header {
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
  }

  .card-header .el-button {
    width: 100%;
  }
}
</style>