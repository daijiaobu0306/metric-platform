<template>
  <div class="audit-log-container">
    <div style="margin-bottom: 10px;">
      <el-button type="danger" size="small" :disabled="selectedRows.length === 0" @click="batchDelete">
        批量删除 <span v-if="selectedRows.length">({{ selectedRows.length }})</span>
      </el-button>
    </div>
    <el-table v-loading="loading" :data="logs" border stripe @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="40" fixed="left" />
      <el-table-column prop="createTime" label="操作时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column prop="operationType" label="操作类型" width="100">
        <template #default="{ row }">
          <el-tag :type="getOperationTypeTagType(row.operationType)" size="small">
            {{ getOperationTypeLabel(row.operationType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="entityId" label="实体ID" width="80" />
      <el-table-column prop="operator" label="操作人" width="100" />
      <el-table-column prop="changeContent" label="变更内容" min-width="200" class-name="wrap-cell" />
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 15px; justify-content: flex-end;"
      @size-change="loadLogs"
      @current-change="loadLogs"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { entityAuditApi } from '../../api'

const props = defineProps({
  entityType: {
    type: String,
    default: 'GONGDAN'
  }
})

const loading = ref(false)
const logs = ref([])
const selectedRows = ref([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const loadLogs = async () => {
  loading.value = true
  try {
    const params = {
      entityType: props.entityType,
      page: page.value - 1,
      size: pageSize.value
    }
    const res = await entityAuditApi.list(params)
    logs.value = res.data.content || []
    total.value = res.data.totalElements || 0
  } catch (error) {
    console.error('加载操作日志失败:', error)
  } finally {
    loading.value = false
  }
}

const formatDateTime = (datetime) => {
  if (!datetime) return ''
  return datetime.replace('T', ' ').substring(0, 19)
}

const getOperationTypeTagType = (type) => {
  const map = {
    'CREATE': 'success',
    'UPDATE': 'warning',
    'DELETE': 'danger',
    'CLOSE': 'info'
  }
  return map[type] || ''
}

const getOperationTypeLabel = (type) => {
  const map = {
    'CREATE': '创建',
    'UPDATE': '更新',
    'DELETE': '删除',
    'CLOSE': '关单'
  }
  return map[type] || type
}

const handleSelectionChange = (selection) => {
  selectedRows.value = selection
}

const batchDelete = async () => {
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${selectedRows.value.length} 条记录吗？`,
      '批量删除',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    const ids = selectedRows.value.map(row => row.id)
    await entityAuditApi.batchDelete(ids)
    ElMessage.success('批量删除成功')
    loadLogs()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('批量删除失败:', error)
      ElMessage.error('批量删除失败')
    }
  }
}

const showDetail = (row) => {
  let content = `操作类型: ${row.operationType}\n`
  content += `操作人: ${row.operator}\n`
  content += `操作时间: ${formatDateTime(row.createTime)}\n`
  content += `变更内容: ${row.changeContent || '无'}\n\n`

  if (row.oldValue) {
    content += `--- 变更前 ---\n${formatJson(row.oldValue)}\n\n`
  }
  if (row.newValue) {
    content += `--- 变更后 ---\n${formatJson(row.newValue)}`
  }

  ElMessageBox.alert(content, '操作详情', {
    confirmButtonText: '关闭',
    customStyle: 'white-space: pre-wrap;'
  })
}

const formatJson = (str) => {
  try {
    return JSON.stringify(JSON.parse(str), null, 2)
  } catch (e) {
    return str
  }
}

onMounted(() => {
  loadLogs()
})

defineExpose({
  refresh: loadLogs
})
</script>