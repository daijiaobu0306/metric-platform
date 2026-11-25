<!--
  岗位字典维护（2026-11-18）

  为什么要有这个抽屉：
  「面向人员」是受控词表 —— 指标只存编码，能选什么由字典决定。
  在此之前字典只有 GET 接口、没有维护入口，加岗位只能连库 INSERT 或写迁移，
  等于把字典写死在 SQL 里。

  规则：
  1) 编码是稳定标识，新增时可填，之后不可改（指标表靠它关联）；
  2) 改名会同步回填指标上的「面向人员」文本快照，避免列表与详情两个名字；
  3) 有指标引用时不允许停用/删除（否则下拉选不到、卡片也聚不出来）；
  4) 指标里出现但字典没有的编码会列在下方，可一键登记，防止历史数据消失。
-->
<template>
  <el-drawer
    :model-value="modelValue"
    class="workspace-theme aud-drawer-wrap"
    size="760px"
    :with-header="false"
    destroy-on-close
    @update:model-value="value => emit('update:modelValue', value)"
  >
    <div class="aud-drawer">
      <header class="aud-head">
        <div class="aud-title">
          <h2>岗位字典</h2>
          <p>指标的「面向人员」只能从这里选；在这里新增岗位后，指标下拉、专屏角色卡片、筛选器会立刻出现它。</p>
        </div>
        <div class="aud-head-actions">
          <el-button type="primary" @click="openCreate()">
            <el-icon><Plus /></el-icon>新增岗位
          </el-button>
          <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        </div>
      </header>

      <el-alert
        v-if="unregistered.length"
        class="aud-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="`发现 ${unregistered.length} 个未登记的岗位编码`"
        description="这些编码在指标里已经在用，但字典里没有，页面上下拉选不到、角色卡片也聚合不出来。点右侧「登记」补进字典即可。"
      />

      <el-table v-loading="loading" :data="list" size="small" border class="aud-table">
        <el-table-column prop="audienceCode" label="编码" min-width="140" show-overflow-tooltip />
        <el-table-column prop="audienceName" label="名称" min-width="110" />
        <el-table-column prop="sortOrder" label="排序" width="66" align="center" />
        <el-table-column label="引用指标" width="86" align="center">
          <template #default="{ row }">
            <span :class="{ 'aud-count-warn': (row.metricCount || 0) > 0 }">{{ row.metricCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="76" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="168" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="aud-empty">字典还是空的，先新增一个岗位。</div>
        </template>
      </el-table>

      <section v-if="unregistered.length" class="aud-unreg">
        <h3>未登记编码</h3>
        <p class="aud-unreg-hint">来源是 `metric_item.audience_code`；登记后这些指标会自动归到该角色下，不需要逐条改指标。</p>
        <el-table :data="unregistered" size="small" border>
          <el-table-column prop="audienceCode" label="编码" min-width="140" show-overflow-tooltip />
          <el-table-column label="涉及指标" width="86" align="center">
            <template #default="{ row }">{{ row.metricCount || 0 }}</template>
          </el-table-column>
          <el-table-column label="建议名称" min-width="110">
            <template #default="{ row }">{{ row.sampleName || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="86" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="register(row)">登记</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog
      v-model="formVisible"
      :title="formMode === 'create' ? '新增岗位' : '编辑岗位'"
      width="440px"
      append-to-body
      class="workspace-theme"
    >
      <el-form label-width="72px" size="default">
        <el-form-item label="编码" required>
          <el-input
            v-model="form.audienceCode"
            :disabled="formMode === 'edit'"
            placeholder="如 maintainer"
            maxlength="50"
          />
          <div class="aud-form-tip">
            {{ formMode === 'edit' ? '编码是指标的关联键，创建后不能修改' : '建议用英文小写下划线；历史编码可能是中文，也允许' }}
          </div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.audienceName" placeholder="如 维护人员" maxlength="100" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :step="10" controls-position="right" />
          <span class="aud-form-tip inline">越小越靠前</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
          <div class="aud-form-tip">停用后「面向人员」下拉和专屏角色卡片不再展示该岗位；已挂指标保留编码，重新启用后恢复</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { metricApi } from '@/api'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const unregistered = ref([])
const nextSortOrder = ref(10)

const formVisible = ref(false)
const formMode = ref('create')
const form = reactive({ id: null, audienceCode: '', audienceName: '', sortOrder: 10, status: 1 })

const enabledCount = computed(() => list.value.filter(row => row.status === 1).length)

const load = async () => {
  loading.value = true
  try {
    const res = await metricApi.audienceManage()
    const payload = res?.data || {}
    list.value = payload.list || []
    unregistered.value = payload.unregistered || []
    nextSortOrder.value = Number(payload.nextSortOrder || 10)
  } catch (error) {
    console.error('加载岗位字典失败:', error)
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, value => {
  if (value) load()
})

const resetForm = (data = {}) => {
  form.id = data.id ?? null
  form.audienceCode = data.audienceCode || ''
  form.audienceName = data.audienceName || ''
  form.sortOrder = Number(data.sortOrder ?? nextSortOrder.value)
  form.status = data.status ?? 1
}

const openCreate = (data = {}) => {
  formMode.value = 'create'
  resetForm(data)
  formVisible.value = true
}

const openEdit = row => {
  formMode.value = 'edit'
  resetForm(row)
  formVisible.value = true
}

/** 从「未登记编码」补录：名称先用指标上的原文，允许用户改。 */
const register = row => {
  openCreate({
    audienceCode: row.audienceCode,
    audienceName: row.sampleName || row.audienceCode,
    status: 1
  })
}

const submit = async () => {
  const code = (form.audienceCode || '').trim()
  const name = (form.audienceName || '').trim()
  if (!code) return ElMessage.warning('请填写岗位编码')
  if (!name) return ElMessage.warning('请填写岗位名称')
  saving.value = true
  try {
    const payload = { audienceCode: code, audienceName: name, sortOrder: form.sortOrder, status: form.status }
    const res = formMode.value === 'create'
      ? await metricApi.audienceCreate(payload)
      : await metricApi.audienceUpdate(form.id, payload)
    const synced = Number(res?.data?.syncedMetrics || 0)
    const retained = Number(res?.data?.retainedMetricRefs || 0)
    if (retained > 0) {
      ElMessage.success(`已停用；${retained} 个指标保留「面向人员」编码，但角色维度不再展示`)
    } else {
      ElMessage.success(synced > 0 ? `已保存，同步更新了 ${synced} 个指标的「面向人员」` : '已保存')
    }
    formVisible.value = false
    await load()
    emit('saved')
  } catch (error) {
    // 拦截器已弹出后端返回的具体原因（重名、删除被引用等）
    console.error('保存岗位失败:', error)
  } finally {
    saving.value = false
  }
}

const toggleStatus = async row => {
  const target = row.status === 1 ? 0 : 1
  // 停用：允许保留指标引用，只把岗位从下拉/专屏角色卡片隐藏；删除才要求先改挂。
  if (target === 0 && (row.metricCount || 0) > 0) {
    try {
      await ElMessageBox.confirm(
        `「${row.audienceName}」下还有 ${row.metricCount} 个指标。停用后该岗位不再出现在「面向人员」下拉和专屏角色卡片，已挂指标保留编码、重新启用后恢复。确定停用？`,
        '停用岗位',
        { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  try {
    await metricApi.audienceUpdate(row.id, {
      audienceCode: row.audienceCode,
      audienceName: row.audienceName,
      sortOrder: row.sortOrder,
      status: target
    })
    ElMessage.success(target === 1 ? '已启用' : '已停用')
    await load()
    emit('saved')
  } catch (error) {
    console.error('切换岗位状态失败:', error)
  }
}

const remove = async row => {
  if ((row.metricCount || 0) > 0) {
    ElMessage.warning(`「${row.audienceName}」下还有 ${row.metricCount} 个指标，不能删除；可以改为停用`)
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除岗位「${row.audienceName}」（${row.audienceCode}）吗？删除后编码不可复用同名记录。`,
      '删除岗位',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await metricApi.audienceDelete(row.id)
    ElMessage.success('已删除')
    await load()
    emit('saved')
  } catch (error) {
    console.error('删除岗位失败:', error)
  }
}
</script>

<style scoped>
.aud-drawer { display: flex; flex-direction: column; gap: 12px; height: 100%; padding: 2px 2px 8px; }
.aud-head { display: flex; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.aud-title h2 { margin: 0; font-size: 18px; color: var(--ui-heading, #1f2d3d); }
.aud-title p { margin: 5px 0 0; font-size: 12px; color: #7a8699; line-height: 1.6; max-width: 520px; }
.aud-head-actions { display: flex; gap: 8px; margin-left: auto; }
.aud-alert { margin: 0; }
.aud-table { flex: none; }
.aud-count-warn { color: #b88230; font-weight: 600; }
.aud-empty { padding: 18px 0; color: #98a2b3; font-size: 13px; }
.aud-unreg { border-top: 1px dashed #e3e8f0; padding-top: 12px; }
.aud-unreg h3 { margin: 0 0 4px; font-size: 14px; color: #43506a; }
.aud-unreg-hint { margin: 0 0 8px; font-size: 11px; color: #8a94a6; }
.aud-form-tip { font-size: 11px; color: #98a2b3; line-height: 1.5; margin-top: 2px; }
.aud-form-tip.inline { margin: 0 0 0 8px; }
.aud-drawer :deep(.el-form-item__content) { flex-wrap: wrap; }
</style>
