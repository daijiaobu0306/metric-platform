<template>
  <div class="prompt-library">
    <section class="prompt-panel">
      <!-- 面板头：标题 + 计数 + 搜索 / 新建 -->
      <header class="prompt-panel-head">
        <div class="panel-heading">
          <h3>提示词库</h3>
          <span class="panel-count">共 {{ pagination.total }} 条</span>
        </div>
        <div class="panel-actions">
          <el-input
            v-model="query.name"
            placeholder="搜索提示词名称"
            clearable
            class="panel-search"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" @click="handleCreate">
            <el-icon><Plus /></el-icon>新建提示词
          </el-button>
        </div>
      </header>

      <!-- 列表 -->
      <div v-loading="loading" class="prompt-list">
        <article v-for="row in tableData" :key="row.id" class="prompt-item">
          <div class="prompt-item-head">
            <span class="prompt-icon"><el-icon><ChatLineSquare /></el-icon></span>
            <div class="prompt-title">
              <h4 :title="row.name" @click="openView(row)">{{ row.name }}</h4>
              <p :title="row.description">{{ row.description || '未填写描述' }}</p>
            </div>
            <div class="prompt-actions">
              <el-button size="small" type="primary" plain @click="copyPrompt(row)">
                <el-icon><CopyDocument /></el-icon>复制
              </el-button>
              <el-button link type="primary" @click="openView(row)">查看</el-button>
              <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </div>
          </div>

          <p v-if="row.content" class="prompt-preview">{{ row.content }}</p>

          <div class="prompt-meta">
            <span><el-icon><Document /></el-icon>{{ (row.content || '').length }} 字</span>
            <span><el-icon><Clock /></el-icon>{{ formatDateTime(row.updatedAt) }}</span>
          </div>
        </article>

        <WorkspaceEmptyState
          v-if="!loading && !tableData.length"
          :title="query.name ? '没有匹配的提示词' : '还没有保存的提示词'"
          :description="query.name ? '换个关键词试试，或清空搜索条件。' : '把常用的提示词存在这里，下次直接复制到智能体里使用，不用每次重写。'"
        />
      </div>

      <footer v-if="pagination.total > pagination.size" class="prompt-panel-foot">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadData"
          @current-change="loadData"
        />
      </footer>
    </section>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑提示词' : '新建提示词'"
      width="720px"
      class="workspace-theme prompt-dialog"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="76px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：月度权重分析口径说明" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明这条提示词用在什么场景" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="14"
            resize="vertical"
            class="prompt-content-input"
            placeholder="粘贴提示词正文。可以直接复制到 staragent 等智能体里使用。"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看 -->
    <el-dialog
      v-model="viewVisible"
      :title="current?.name || '提示词'"
      width="720px"
      class="workspace-theme prompt-dialog"
      append-to-body
    >
      <p v-if="current?.description" class="view-desc">{{ current.description }}</p>
      <pre class="view-content">{{ current?.content }}</pre>
      <template #footer>
        <el-button @click="viewVisible = false">关闭</el-button>
        <el-button type="primary" @click="copyPrompt(current)"><el-icon><CopyDocument /></el-icon>复制提示词</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { promptApi } from '@/api'
import { formatDateTime } from '@/utils'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'

const tableData = ref([])
const loading = ref(false)
const saving = ref(false)
const query = reactive({ name: '' })
const pagination = reactive({ page: 1, size: 10, total: 0 })

const dialogVisible = ref(false)
const viewVisible = ref(false)
const current = ref(null)
const formRef = ref()
const form = reactive({ id: null, name: '', description: '', content: '' })
const rules = {
  name: [{ required: true, message: '请输入提示词名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入提示词内容', trigger: 'blur' }]
}

onMounted(loadData)

async function loadData() {
  loading.value = true
  try {
    const res = await promptApi.list({
      name: query.name || undefined,
      page: pagination.page - 1,
      size: pagination.size
    })
    tableData.value = res.data.content || []
    pagination.total = res.data.totalElements || 0
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.page = 1
  loadData()
}

function resetForm() {
  Object.assign(form, { id: null, name: '', description: '', content: '' })
}

function handleCreate() {
  resetForm()
  dialogVisible.value = true
}

function handleEdit(row) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    description: row.description || '',
    content: row.content || ''
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description.trim(),
      content: form.content
    }
    if (form.id) {
      await promptApi.update(form.id, payload)
      ElMessage.success('已保存')
    } else {
      await promptApi.create(payload)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

function openView(row) {
  current.value = row
  viewVisible.value = true
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除提示词「${row.name}」吗？`, '删除提示词', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await promptApi.delete(row.id)
    ElMessage.success('已删除')
    if (tableData.value.length === 1 && pagination.page > 1) {
      pagination.page -= 1
    }
    loadData()
  } catch (e) {
    // 拦截器已提示
  }
}

async function copyPrompt(row) {
  if (!row?.content) {
    ElMessage.warning('这条提示词还没有内容')
    return
  }
  try {
    await writeClipboard(row.content)
    ElMessage.success('提示词已复制到剪贴板')
  } catch (e) {
    ElMessage.error('复制失败，请手动选中内容复制')
  }
}

/** 内网多为 http 访问，navigator.clipboard 不可用时退回 execCommand */
async function writeClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!ok) throw new Error('copy failed')
}
</script>

<style scoped>
.prompt-library {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 面板 */
.prompt-panel {
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-sm);
  overflow: hidden;
}
.prompt-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--ui-border-soft);
  background: var(--ui-surface);
}
.panel-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}
.panel-heading h3 {
  margin: 0;
  color: var(--ui-heading);
  font-size: 14px;
  font-weight: 600;
}
.panel-count {
  padding: 2px 8px;
  border-radius: 20px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary-strong);
  font-size: 11px;
}
.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.panel-search {
  width: 240px;
}

/* 列表 */
.prompt-list {
  display: flex;
  flex-direction: column;
  min-height: 160px;
}
.prompt-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--ui-border-soft);
  transition: background 0.16s;
}
.prompt-item:last-child {
  border-bottom: none;
}
.prompt-item:hover {
  background: var(--ui-primary-soft);
}
.prompt-item-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.prompt-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 9px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-size: 16px;
}
.prompt-title {
  flex: 1;
  min-width: 0;
}
.prompt-title h4 {
  margin: 0;
  color: var(--ui-heading);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.prompt-title h4:hover {
  color: var(--ui-primary-strong);
}
.prompt-title p {
  margin: 2px 0 0;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.prompt-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding-top: 2px;
}
.prompt-actions :deep(.el-button + .el-button) {
  margin-left: 4px;
}

.prompt-preview {
  margin: 0 0 0 44px;
  padding: 10px 12px;
  border: 1px solid var(--ui-border-soft);
  border-radius: 8px;
  background: var(--ui-field);
  color: var(--ui-text);
  font-size: 12.5px;
  line-height: 1.8;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.prompt-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: 44px;
  color: var(--ui-subtle);
  font-size: 11px;
}
.prompt-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.prompt-panel-foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--ui-border-soft);
  background: var(--ui-surface);
}

/* 弹窗 */
.view-desc {
  margin: 0 0 12px;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.8;
}
.view-content {
  margin: 0;
  padding: 16px;
  max-height: 52vh;
  overflow: auto;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-field);
  color: var(--ui-text);
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 12.5px;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}
.prompt-content-input :deep(.el-textarea__inner) {
  font-family: Consolas, 'PingFang SC', monospace;
  font-size: 12.5px;
  line-height: 1.8;
}

@media (max-width: 900px) {
  .prompt-item-head {
    flex-wrap: wrap;
  }
  .prompt-actions {
    width: 100%;
    padding-left: 44px;
  }
  .prompt-preview,
  .prompt-meta {
    margin-left: 0;
  }
}
@media (max-width: 640px) {
  .panel-search {
    width: 100%;
  }
  .panel-actions {
    width: 100%;
  }
}
</style>
