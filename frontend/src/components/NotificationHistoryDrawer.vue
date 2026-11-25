<template>
  <el-drawer
    v-model="visible"
    title="历史通知"
    size="72%"
    append-to-body
    :close-on-click-modal="false"
    @open="handleOpen"
  >
    <div class="history-body">
      <!-- 顶部统计：点数字即按类型筛选 -->
      <div class="stat-row">
        <button class="stat-chip" :class="{ 'is-active': !filters.eventType }" @click="pickType('')">
          <span class="stat-label">全部</span>
          <b class="stat-num">{{ stats.total }}</b>
        </button>
        <button
          v-for="t in NOTIFY_TYPES"
          :key="t.key"
          class="stat-chip"
          :class="{ 'is-active': filters.eventType === t.key }"
          :style="{ '--chip': t.color }"
          @click="pickType(t.key)"
        >
          <span class="stat-label">{{ t.label }}</span>
          <b class="stat-num">{{ stats.byType[t.key] || 0 }}</b>
        </button>
      </div>

      <!-- 筛选 -->
      <div class="filter-row">
        <el-select v-model="filters.eventType" clearable placeholder="全部类型" style="width: 130px">
          <el-option v-for="t in NOTIFY_TYPES" :key="t.key" :label="t.label" :value="t.key" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="搜索标题或内容"
          style="width: 220px"
          @keyup.enter="search"
        />
        <el-date-picker
          v-model="filters.range"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 340px"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="filter-spacer"></div>
        <el-dropdown trigger="click" @command="handleCleanup">
          <el-button :loading="cleaning">清理 <el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="7">清理 7 天前的通知</el-dropdown-item>
              <el-dropdown-item command="30">清理 30 天前的通知</el-dropdown-item>
              <el-dropdown-item command="type" divided>清空当前类型的全部通知</el-dropdown-item>
              <el-dropdown-item command="all">清空全部通知</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <!-- 操作条 -->
      <div class="action-row">
        <span class="action-tip">
          已选 <b>{{ selected.length }}</b> 条 / 当前筛选共 <b>{{ total }}</b> 条
        </span>
        <el-button
          type="danger"
          plain
          size="small"
          :disabled="selected.length === 0"
          :loading="deleting"
          @click="removeSelected"
        >批量删除</el-button>
        <el-button size="small" @click="clearSelection" :disabled="selected.length === 0">取消选择</el-button>
      </div>

      <!-- 列表 -->
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        :height="tableHeight"
        size="small"
        border
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="44" reserve-selection />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatFull(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :style="tagStyle(row.eventType)" effect="light">
              {{ typeMeta(row.eventType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[20, 50, 100, 200]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="handleSizeChange"
        />
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { eventStreamApi } from '../api'
import { NOTIFY_TYPES, typeMeta, formatClock } from '../utils/notificationCenter'

const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['changed'])

const loading = ref(false)
const deleting = ref(false)
const cleaning = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selected = ref([])
const tableRef = ref(null)

// 表格高度按视口算，避免在抽屉里出现双滚动条
const tableHeight = ref(520)
const syncTableHeight = () => {
  tableHeight.value = Math.max(300, window.innerHeight - 290)
}

const filters = reactive({
  eventType: '',
  keyword: '',
  range: null,
})

const stats = reactive({ total: 0, byType: {} })

const hasFilter = computed(() => !!(filters.eventType || filters.keyword || (filters.range && filters.range.length === 2)))

function tagStyle(type) {
  const meta = typeMeta(type)
  return { color: meta.color, background: meta.bg, borderColor: 'transparent' }
}

/** 完整时间：后端返回 2026-10-28T10:25:03.974 → 10-28 10:25:03 */
function formatFull(iso) {
  if (!iso) return ''
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}:\d{2}:\d{2})/)
  return m ? `${m[2]}-${m[3]} ${m[4]}` : formatClock(iso)
}

function buildParams() {
  const params = { page: page.value, size: size.value }
  if (filters.eventType) params.eventType = filters.eventType
  if (filters.keyword) params.keyword = filters.keyword.trim()
  if (filters.range && filters.range.length === 2) {
    params.startTime = filters.range[0]
    params.endTime = filters.range[1]
  }
  return params
}

async function load() {
  loading.value = true
  try {
    const res = await eventStreamApi.fetchPage(buildParams())
    const data = res.data || {}
    rows.value = data.records || []
    total.value = data.total || 0
    // 删除后当前页可能空了，自动回退一页
    if (rows.value.length === 0 && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载历史通知失败')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    const res = await eventStreamApi.stats()
    const data = res.data || {}
    stats.total = data.total || 0
    const map = {}
    for (const item of data.items || []) map[item.eventType] = item.count || 0
    stats.byType = map
  } catch (e) {
    /* 统计失败不阻塞列表 */
  }
}

function handleOpen() {
  page.value = 1
  selected.value = []
  syncTableHeight()
  loadStats()
  load()
}

onMounted(() => {
  syncTableHeight()
  window.addEventListener('resize', syncTableHeight)
})

onUnmounted(() => {
  window.removeEventListener('resize', syncTableHeight)
})

function search() {
  page.value = 1
  load()
}

function reset() {
  filters.eventType = ''
  filters.keyword = ''
  filters.range = null
  page.value = 1
  load()
}

function pickType(type) {
  filters.eventType = type
  page.value = 1
  load()
}

function handleSizeChange() {
  page.value = 1
  load()
}

function onSelectionChange(list) {
  selected.value = list
}

function clearSelection() {
  tableRef.value?.clearSelection()
  selected.value = []
}

async function removeOne(row) {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复，确定删除这条通知吗？\n\n${row.title || ''}`,
      '删除通知',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  deleting.value = true
  try {
    await eventStreamApi.remove(row.id)
    ElMessage.success('已删除')
    clearSelection()
    await Promise.all([load(), loadStats()])
    emit('changed', [row.id])
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  } finally {
    deleting.value = false
  }
}

async function removeSelected() {
  const ids = selected.value.map(r => r.id)
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      `即将删除选中的 ${ids.length} 条通知，删除后不可恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: `删除 ${ids.length} 条`, cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  deleting.value = true
  try {
    const res = await eventStreamApi.batchRemove(ids)
    ElMessage.success(res.message || `已删除 ${ids.length} 条`)
    clearSelection()
    await Promise.all([load(), loadStats()])
    emit('changed', ids)
  } catch (e) {
    ElMessage.error(e?.message || '批量删除失败')
  } finally {
    deleting.value = false
  }
}

async function handleCleanup(command) {
  let payload = null
  let confirmText = ''
  if (command === '7' || command === '30') {
    payload = { beforeDays: Number(command), eventType: filters.eventType || null }
    confirmText = `将清理 ${command} 天前${filters.eventType ? `的「${typeMeta(filters.eventType).label}」` : ''}通知，删除后不可恢复。`
  } else if (command === 'type') {
    if (!filters.eventType) {
      ElMessage.warning('请先在上方筛选出一个类型')
      return
    }
    payload = { eventType: filters.eventType }
    const count = stats.byType[filters.eventType] || 0
    confirmText = `将清空全部「${typeMeta(filters.eventType).label}」通知（约 ${count} 条），删除后不可恢复。`
  } else if (command === 'all') {
    payload = { all: true }
    confirmText = `将清空全部通知（约 ${stats.total} 条），删除后不可恢复。`
  }
  if (!payload) return

  try {
    await ElMessageBox.confirm(confirmText, '清理通知', {
      type: 'warning',
      confirmButtonText: '确认清理',
      cancelButtonText: '取消',
    })
  } catch (_) {
    return
  }

  cleaning.value = true
  try {
    const res = await eventStreamApi.cleanup(payload)
    ElMessage.success(res.message || '清理完成')
    clearSelection()
    page.value = 1
    await Promise.all([load(), loadStats()])
    emit('changed', 'refresh')
  } catch (e) {
    ElMessage.error(e?.message || '清理失败')
  } finally {
    cleaning.value = false
  }
}
</script>

<style scoped>
.history-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 10px;
}

/* ===== 类型统计 ===== */
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.stat-chip {
  --chip: #909399;
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.stat-chip:hover {
  border-color: var(--chip);
}

.stat-chip.is-active {
  border-color: var(--chip);
  background: #f7f9fc;
  box-shadow: inset 0 0 0 1px var(--chip);
}

.stat-label {
  font-size: 12px;
  color: #606266;
}

.stat-num {
  font-size: 15px;
  color: #303133;
}

/* ===== 筛选 & 操作 ===== */
.filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-spacer {
  flex: 1;
}

.action-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.action-tip {
  font-size: 12px;
  color: #909399;
  margin-right: 4px;
}

.action-tip b {
  color: #303133;
}

.history-body :deep(.el-table) {
  min-height: 300px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}
</style>
