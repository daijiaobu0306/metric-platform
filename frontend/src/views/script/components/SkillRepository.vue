<template>
  <div class="skill-repository">
    <!-- 概览：三个数字既是统计也是筛选入口 -->
    <section class="repo-overview" aria-label="技能仓库概览">
      <button
        v-for="item in overview"
        :key="item.key"
        type="button"
        class="repo-stat"
        :class="[`is-${item.tone}`, { 'is-active': filter === item.key }]"
        @click="filter = item.key"
      >
        <span class="repo-stat-label">{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <span class="repo-stat-unit">{{ item.unit }}</span>
      </button>
      <p class="repo-hint">
        <el-icon><InfoFilled /></el-icon>
        技能包由内网智能体（如 staragent）导入后自行运行；平台负责统一分发，保证每个人拿到的是同一份。
      </p>
    </section>

    <!-- 工具栏 -->
    <div class="repo-toolbar">
      <el-input v-model="keyword" placeholder="搜索技能名称或简介" clearable class="repo-search">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <div class="repo-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        <el-button type="primary" @click="openUpload"><el-icon><Upload /></el-icon>上传技能</el-button>
      </div>
    </div>

    <!-- 技能卡片 -->
    <div v-loading="loading" class="skill-grid-wrap">
      <div v-if="filteredSkills.length" class="skill-grid">
        <article v-for="skill in filteredSkills" :key="skill.id" class="skill-card">
          <header class="skill-card-head">
            <span class="skill-icon" :class="skill.source === 'BUILTIN' ? 'is-builtin' : 'is-uploaded'">
              <el-icon><component :is="skill.source === 'BUILTIN' ? 'Cpu' : 'FolderOpened'" /></el-icon>
            </span>
            <div class="skill-title">
              <h3 :title="skill.name">{{ skill.name }}</h3>
              <div class="skill-badges">
                <span class="skill-badge" :class="skill.source === 'BUILTIN' ? 'is-builtin' : 'is-uploaded'">
                  {{ skill.source === 'BUILTIN' ? '平台内置' : '团队上传' }}
                </span>
                <span class="skill-badge is-muted">v{{ skill.version }}</span>
              </div>
            </div>
          </header>

          <p class="skill-desc" :title="skill.description">{{ skill.description }}</p>

          <div class="skill-meta">
            <span><el-icon><Document /></el-icon>{{ skill.fileCount }} 个文件</span>
            <span><el-icon><Coin /></el-icon>{{ formatSize(skill.size) }}</span>
            <span><el-icon><User /></el-icon>{{ skill.uploader }}</span>
            <span v-if="skill.uploadedAt"><el-icon><Clock /></el-icon>{{ skill.uploadedAt }}</span>
          </div>

          <footer class="skill-card-foot">
            <el-button
              type="primary"
              plain
              :loading="downloadingId === skill.id"
              @click="download(skill)"
            >
              <el-icon><Download /></el-icon>下载技能包
            </el-button>
            <el-button v-if="skill.deletable" link type="danger" @click="remove(skill)">删除</el-button>
          </footer>
        </article>
      </div>
      <WorkspaceEmptyState
        v-else
        :title="skills.length ? '没有匹配的技能' : '技能仓库还是空的'"
        :description="skills.length ? '换个关键词试试，或清空搜索条件。' : '上传一个包含 SKILL.md 的技能包，团队里所有人都能下载使用。'"
      />
    </div>

    <!-- 上传技能 -->
    <el-dialog
      v-model="uploadVisible"
      title="上传技能"
      width="560px"
      class="workspace-theme skill-dialog"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-upload
        ref="uploadRef"
        class="skill-upload"
        drag
        :auto-upload="false"
        :limit="1"
        v-model:file-list="fileList"
        accept=".zip"
        :on-change="handleFileChange"
        :on-exceed="handleExceed"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">把技能包（.zip）拖到这里，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">
            技能包需包含 <code>SKILL.md</code>，并在开头写明 <code>name</code> 与 <code>description</code>；
            可带 references 目录。单个包不超过 20MB。
          </div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { skillRepositoryApi } from '@/api'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'

const skills = ref([])
const loading = ref(false)
const keyword = ref('')
const filter = ref('all')

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadRef = ref()
const fileList = ref([])
const downloadingId = ref('')

const overview = computed(() => [
  { key: 'all', label: '全部技能', value: skills.value.length, unit: '个', tone: 'primary' },
  { key: 'BUILTIN', label: '平台内置', value: skills.value.filter(item => item.source === 'BUILTIN').length, unit: '个', tone: 'success' },
  { key: 'UPLOADED', label: '团队上传', value: skills.value.filter(item => item.source === 'UPLOADED').length, unit: '个', tone: 'violet' }
])

const filteredSkills = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return skills.value.filter(skill => {
    const matchFilter = filter.value === 'all' || skill.source === filter.value
    const matchText = !text
      || (skill.name || '').toLowerCase().includes(text)
      || (skill.description || '').toLowerCase().includes(text)
    return matchFilter && matchText
  })
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await skillRepositoryApi.list()
    skills.value = res.data || []
  } catch (e) {
    // 拦截器已提示，这里不重复弹错误
  } finally {
    loading.value = false
  }
}

function formatSize(size) {
  if (!size) return '-'
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  return (size / 1024 / 1024).toFixed(1) + ' MB'
}

async function download(skill) {
  downloadingId.value = skill.id
  try {
    const response = await skillRepositoryApi.download(skill.id)
    let blob = response.data
    if (!(blob instanceof Blob)) {
      blob = new Blob([blob], { type: 'application/zip' })
    }
    // 后端报错时返回的是 JSON，别把错误信息当压缩包存下来
    if (blob.type && blob.type.includes('json')) {
      const text = await blob.text()
      let message = '下载失败'
      try {
        message = JSON.parse(text).message || message
      } catch (e) {
        // 保持默认提示
      }
      throw new Error(message)
    }
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = skill.fileName || `${skill.name}.zip`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e.message || '下载失败')
  } finally {
    downloadingId.value = ''
  }
}

function openUpload() {
  fileList.value = []
  uploadVisible.value = true
}

function handleFileChange(file, files) {
  const target = file.raw || file
  if (target && target.name && !target.name.toLowerCase().endsWith('.zip')) {
    ElMessage.warning('技能包必须是 .zip 文件')
    uploadRef.value?.clearFiles()
    fileList.value = []
    return
  }
  fileList.value = files.slice(-1)
}

function handleExceed(files) {
  uploadRef.value?.clearFiles()
  const file = files[0]
  uploadRef.value?.handleStart(file)
}

async function submitUpload() {
  if (!fileList.value.length) {
    ElMessage.warning('请选择技能包')
    return
  }
  uploading.value = true
  try {
    const res = await skillRepositoryApi.upload(fileList.value[0].raw)
    ElMessage.success(`已上传「${res.data?.name || '技能'}」`)
    uploadVisible.value = false
    fileList.value = []
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    uploading.value = false
  }
}

async function remove(skill) {
  try {
    await ElMessageBox.confirm(`确定要从技能仓库删除「${skill.name}」吗？已下载的副本不受影响。`, '删除技能', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await skillRepositoryApi.delete(skill.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    // 拦截器已提示
  }
}
</script>

<style scoped>
.skill-repository {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 概览 */
.repo-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 160px)) 1fr;
  align-items: stretch;
  gap: 12px;
}
.repo-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-sm);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s, box-shadow 0.18s, background 0.18s;
}
.repo-stat:hover {
  border-color: var(--ui-primary-border);
  box-shadow: var(--ui-shadow-hover);
}
.repo-stat.is-active {
  border-color: var(--ui-primary);
  background: var(--ui-primary-soft);
}
.repo-stat-label {
  color: var(--ui-muted);
  font-size: 12px;
}
.repo-stat strong {
  margin-left: auto;
  color: var(--ui-heading);
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.repo-stat-unit {
  color: var(--ui-subtle);
  font-size: 11px;
}
.repo-stat.is-success.is-active {
  border-color: var(--ui-success-border);
  background: var(--ui-success-soft);
}
.repo-stat.is-violet.is-active {
  border-color: #ddd6fe;
  background: var(--ui-violet-soft);
}
.repo-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 12px 16px;
  border: 1px dashed var(--ui-border);
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface);
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.7;
}
.repo-hint .el-icon {
  color: var(--ui-primary);
  flex-shrink: 0;
}

/* 工具栏 */
.repo-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.repo-search {
  max-width: 300px;
}
.repo-actions {
  display: flex;
  gap: 8px;
}

/* 技能卡片 */
.skill-grid-wrap {
  min-height: 220px;
}
.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.skill-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-sm);
  transition: border-color 0.18s, box-shadow 0.18s;
}
.skill-card:hover {
  border-color: var(--ui-primary-border);
  box-shadow: var(--ui-shadow-hover);
}
.skill-card-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.skill-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  font-size: 20px;
  flex-shrink: 0;
}
.skill-icon.is-builtin {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
}
.skill-icon.is-uploaded {
  background: var(--ui-violet-soft);
  color: var(--ui-violet);
}
.skill-title {
  min-width: 0;
}
.skill-title h3 {
  margin: 0;
  color: var(--ui-heading);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-badges {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.skill-badge {
  padding: 2px 7px;
  border-radius: 5px;
  font-size: 11px;
  line-height: 1.6;
}
.skill-badge.is-builtin {
  background: var(--ui-success-soft);
  color: var(--ui-success);
}
.skill-badge.is-uploaded {
  background: var(--ui-violet-soft);
  color: var(--ui-violet);
}
.skill-badge.is-muted {
  background: var(--ui-page);
  color: var(--ui-muted);
}
.skill-desc {
  margin: 0;
  min-height: 44px;
  color: var(--ui-text);
  font-size: 12.5px;
  line-height: 1.8;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.skill-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding-top: 12px;
  border-top: 1px solid var(--ui-border-soft);
  color: var(--ui-subtle);
  font-size: 11px;
}
.skill-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.skill-card-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
}

/* 上传 */
.skill-upload :deep(.el-upload-dragger) {
  padding: 26px 16px;
  border-color: var(--ui-border);
  border-radius: 10px;
  background: var(--ui-field);
}
.skill-upload :deep(.el-upload-dragger:hover) {
  border-color: var(--ui-primary);
}
.el-upload__tip {
  color: var(--ui-muted);
  font-size: 11.5px;
  line-height: 1.8;
}
.el-upload__tip code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--ui-page);
  color: var(--ui-primary-strong);
  font-family: Consolas, monospace;
}

@media (max-width: 900px) {
  .repo-overview {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .repo-hint {
    grid-column: 1 / -1;
  }
}
@media (max-width: 640px) {
  .skill-grid {
    grid-template-columns: 1fr;
  }
  .repo-search {
    max-width: none;
    flex: 1 1 100%;
  }
}
</style>
