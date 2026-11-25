<template>
  <div v-loading="loading" class="screen-config">
    <div class="config-toolbar">
      <div>
        <h2>角色专屏配置</h2>
        <p>模板决定页面骨架，页签决定维护人员能看到的业务区块；保存后可在“视图”页直接切换。</p>
      </div>
      <div class="toolbar-actions">
        <el-button @click="fileConfigVisible = true"><el-icon><Document /></el-icon>鉴权文件</el-button>
        <el-button type="primary" @click="create"><el-icon><Plus /></el-icon>新建专屏</el-button>
      </div>
    </div>
    <el-alert
      title="范围按明确授权节点向下展开；只选指标不会提升为上级健康度权限。科室经理自动跟随关联主管的业务范围。"
      type="info"
      :closable="false"
    />

    <TemplateGallery :counts="templateCounts" @use="createWithTemplate" @preview="openPreview" />

    <div class="query-bar" v-balanced-query>
      <el-input v-model="query.keyword" clearable placeholder="搜索视图名称、代码、授权用户/角色" class="keyword-input" />
      <el-select v-model="query.template" clearable placeholder="全部模板" class="query-select">
        <el-option v-for="(label, key) in templates" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="query.status" clearable placeholder="全部状态" class="query-select">
        <el-option label="已发布" :value="1" />
        <el-option label="草稿" :value="0" />
      </el-select>
      <el-date-picker
        v-model="query.timeRange"
        type="datetimerange"
        value-format="YYYY-MM-DD HH:mm:ss"
        start-placeholder="创建开始"
        end-placeholder="创建结束"
        range-separator="至"
        class="time-picker"
      />
      <el-button @click="resetQuery">重置</el-button>
    </div>
    <div class="table-summary">共 {{ filteredViews.length }} 个视图<span v-if="filteredViews.length !== views.length">（已从 {{ views.length }} 个视图中筛选）</span></div>
    <el-table :data="filteredViews" stripe style="margin-top:8px" @sort-change="sortViews">
      <el-table-column prop="viewName" label="视图名称" min-width="170" sortable="custom">
        <template #default="{ row }">
          <span>{{ row.viewName }}</span>
          <el-tag v-if="Number(row.isDefault) === 1" size="small" type="success" effect="plain" class="default-tag">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模板" min-width="110" sortable="custom" :sort-method="sortTemplate">
        <template #default="{ row }">
          <span class="template-label">{{ templates[parse(row)?.template] || '旧版，待配置' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="页签" min-width="100">
        <template #default="{ row }"><span class="tab-count">{{ tabCount(row) }} 个</span></template>
      </el-table-column>
      <el-table-column label="状态" prop="status" width="100" sortable="custom">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '已发布' : '草稿' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="授权用户" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ grantNames(row, 'user') }}</template>
      </el-table-column>
      <el-table-column label="授权角色" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ grantNames(row, 'role') }}</template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" min-width="170" sortable="custom">
        <template #default="{ row }">{{ time(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" prop="updateTime" min-width="170" sortable="custom">
        <template #default="{ row }">{{ time(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">配置</el-button>
          <el-button link type="primary" :disabled="Number(row.isDefault) === 1 || row.status !== 1" @click="setDefault(row)">{{ Number(row.isDefault) === 1 ? '默认' : '设为默认' }}</el-button>
          <el-button link type="primary" :disabled="row.status !== 1" @click="share(row)">分享</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑专屏' : '新建专屏'"
      width="min(1120px, 96vw)"
      top="5vh"
      class="workspace-theme dashboard-workspace screen-dialog"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <div class="form-section">
          <div class="section-heading">
            <div><h3>基础信息</h3><p>设置专屏名称、访问代码、模板和发布状态</p></div>
            <el-tag v-if="form.id" size="small" effect="plain">ID {{ form.id }}</el-tag>
          </div>
          <div class="basic-grid">
            <el-form-item label="视图名称" required><el-input v-model="form.viewName" maxlength="100" /></el-form-item>
            <el-form-item label="视图代码" required><el-input v-model="form.viewCode" :disabled="!!form.id" placeholder="字母、数字、下划线或短横线" /></el-form-item>
            <el-form-item label="发布状态">
              <el-radio-group v-model="form.status">
                <el-radio :value="0">草稿</el-radio>
                <el-radio :value="1">发布</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
          </div>
        </div>

        <div class="form-section">
          <div class="section-heading">
            <div><h3>模板与页面布局</h3><p>模板决定页签结构；维护人员创建前就能看到页面骨架</p></div>
            <el-button link type="primary" @click="previewCurrent">预览当前结构</el-button>
          </div>
          <div class="template-picker">
            <button
              v-for="item in templateList"
              :key="item.key"
              type="button"
              class="template-option"
              :class="{ active: policy.template === item.key, disabled: item.status === 'disabled' }"
              :disabled="item.status === 'disabled'"
              @click="changeTemplate(item.key)"
            >
              <span class="option-icon">{{ item.name.slice(0, 1) }}</span>
              <span class="option-body"><b>{{ item.name }}</b><small>{{ item.audience }}</small></span>
              <span class="option-meta">{{ item.tabs.length }} 页签<em v-if="item.badge">{{ item.badge }}</em></span>
            </button>
          </div>
          <el-form-item label="页面布局" class="layout-field">
            <el-radio-group v-model="policy.layoutMode">
              <el-radio-button value="split">分栏专屏</el-radio-button>
              <el-radio-button value="all">一页全览</el-radio-button>
            </el-radio-group>
            <span class="field-hint">{{ policy.layoutMode === 'all' ? '全部页签按业务顺序展开，适合大屏值守' : '按页签切换，适合日常维护和排查' }}</span>
          </el-form-item>
        </div>

        <div class="form-section">
          <div class="section-heading">
            <div><h3>展示内容</h3><p>{{ policy.template === 'manager' ? '勾选科室经理视图的页签；数据模块会按页签自动推导' : '选择该专屏需要展示的业务模块' }}</p></div>
            <span v-if="policy.template === 'manager'" class="module-count">已选 {{ policy.tabs.length }} / {{ managerTabs.length }} 个页签</span>
          </div>

          <div v-if="policy.template === 'manager'" class="manager-tabs">
            <div
              v-for="tab in managerTabs"
              :key="tab.key"
              class="manager-tab"
              :class="{ checked: policy.tabs.includes(tab.key) }"
              @click="toggleTab(tab.key)"
            >
              <el-checkbox :model-value="policy.tabs.includes(tab.key)" @click.stop @change="checked => toggleTab(tab.key, checked)" />
              <div class="manager-tab-body">
                <b>{{ tab.label }}</b>
                <small>{{ tab.desc }}</small>
              </div>
              <span class="manager-tab-index">{{ String(managerTabs.indexOf(tab) + 1).padStart(2, '0') }}</span>
            </div>
          </div>
          <el-alert
            v-else
            type="info"
            :closable="false"
            title="业务主管/领导模板继续复用现有模块配置；本轮优先完善科室经理模板。"
          />
          <el-form-item v-if="policy.template !== 'manager'" label="显示模块" class="module-field">
            <el-checkbox-group v-model="policy.modules">
              <el-checkbox v-for="key in allowedModules" :key="key" :value="key">{{ moduleNames[key] }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <div v-if="policy.template === 'manager'" class="module-preview">
            <span v-for="module in policy.modules" :key="module" class="module-chip">{{ moduleNames[module] || module }}</span>
            <span v-if="!policy.modules.length" class="module-chip empty">尚未选择页签</span>
          </div>
          <el-alert
            v-if="policy.template === 'operator'"
            type="warning"
            :closable="false"
            title="开放平台运行模块将允许查看全平台用户、导入、脚本和接口汇总；业务指标仍按每条授权范围展示。"
          />
        </div>

        <div class="form-section">
          <div class="section-heading">
            <div><h3>访问授权</h3><p>授权对象决定谁可以进入此专屏</p></div>
            <el-button type="primary" plain @click="addGrant">添加授权</el-button>
          </div>
          <el-form-item v-if="policy.template === 'manager'" label="关联业务主管（仅继承业务主管专屏的授权，动态跟随变更）" required>
            <el-select v-model="policy.supervisorIds" multiple filterable placeholder="选择业务主管账号" style="width:100%">
              <el-option v-for="u in users" :key="u.id" :value="u.id" :label="userName(u)" />
            </el-select>
          </el-form-item>
          <el-alert v-if="!policy.grants.length" title="请添加至少一条授权；空范围不会开放全部数据。" type="warning" :closable="false" />
          <el-tabs v-model="activeGrant" type="card" @tab-change="syncTree">
            <el-tab-pane v-for="(grant, index) in policy.grants" :key="index" :name="String(index)" :label="`授权 ${index + 1}`">
              <div class="grant-header">
                <el-select v-model="grant.subjectType" style="width:120px" @change="grant.subjectId = null">
                  <el-option label="角色" value="role" />
                  <el-option label="用户" value="user" />
                </el-select>
                <el-select v-model="grant.subjectId" filterable placeholder="选择授权对象" style="width:300px">
                  <el-option
                    v-for="subject in grant.subjectType === 'role' ? roles : users"
                    :key="subject.id"
                    :value="subject.id"
                    :label="grant.subjectType === 'role' ? subject.roleName : userName(subject)"
                  />
                </el-select>
                <el-button type="danger" link @click="deleteGrant(index)">移除此授权</el-button>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>

        <div v-if="currentGrant && policy.template !== 'manager'" class="scope-grid">
          <div>
            <el-input v-model="search" placeholder="搜索系统、大类、小类、指标" clearable />
            <el-scrollbar height="330px">
              <el-tree
                ref="tree"
                :data="treeData"
                node-key="key"
                :props="{ label: 'label', children: 'children' }"
                show-checkbox
                check-strictly
                :filter-node-method="filterNode"
                @check="checkScope"
              />
            </el-scrollbar>
          </div>
          <div>
            <strong>明确授权的节点（{{ currentGrant.scope.include.length }}）</strong>
            <p class="hint">选择父节点包含其未来新增后代。逐项选满子节点不会自动勾选父节点。</p>
            <el-scrollbar height="155px">
              <div v-for="key in currentGrant.scope.include" :key="key" class="scope-item">
                <span>{{ paths[key] || `${key}（节点已删除）` }}</span>
                <el-button link @click="removeNode(key)">移除</el-button>
              </div>
            </el-scrollbar>
            <el-form-item label="排除节点（仅作用于本条授权；父级汇总含被排除数据时不展示）">
              <el-tree-select v-model="currentGrant.scope.exclude" :data="treeData" node-key="key" :props="{ label: 'label', value: 'key' }" multiple show-checkbox check-strictly filterable style="width:100%" />
            </el-form-item>
          </div>
        </div>
        <div v-if="policy.template === 'manager'" class="manager-hint">本视图的授权角色/用户决定谁能进入；关联主管决定能看哪些业务。无需另选指标。</div>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button @click="previewCurrent">预览结构</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
      </template>
    </el-dialog>

    <TemplatePreviewDialog
      v-model="previewVisible"
      :template-key="previewKey"
      :tabs="previewTabs"
      :modules="previewModules"
      :layout-mode="previewLayoutMode"
    />
    <ShareLinksDialog v-model="shareVisible" :view="shareView" />
    <ShareFileConfigDialog v-model="fileConfigVisible" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { screenApi, templates, moduleNames, message } from './api'
import { templateList, templateMeta, managerTabs, defaultTabs, modulesForTabs } from './templates'
import TemplateGallery from './TemplateGallery.vue'
import TemplatePreviewDialog from './TemplatePreviewDialog.vue'
import ShareLinksDialog from './ShareLinksDialog.vue'
import ShareFileConfigDialog from './ShareFileConfigDialog.vue'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const emit = defineEmits(['saved'])
const loading = ref(false), saving = ref(false), visible = ref(false)
const views = ref([]), users = ref([]), roles = ref([]), nodes = ref([])
const form = ref({})
const policy = ref({ version: 1, template: 'manager', grants: [], supervisorIds: [], modules: [], layoutMode: 'split', tabs: [] })
const activeGrant = ref('0'), tree = ref(), search = ref('')
const query = ref({ keyword: '', template: '', status: null, timeRange: [] })
const sortState = ref({ prop: '', order: '' })
const previewVisible = ref(false), previewKey = ref('manager'), previewTabs = ref([]), previewModules = ref([]), previewLayoutMode = ref('split')
const shareVisible = ref(false), shareView = ref({})
const fileConfigVisible = ref(false)

const userName = u => `${u.displayName || u.username} (${u.username})`
const parse = row => { try { return JSON.parse(row.layoutConfig) } catch { return null } }
const time = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'
const subjectLabel = (type, id) => {
  const list = type === 'role' ? roles.value : users.value
  const item = list.find(x => x.id === id)
  return item ? (type === 'role' ? item.roleName : userName(item)) : `已删除${type === 'role' ? '角色' : '用户'}(${id})`
}
const grantNames = (row, type) => {
  const names = (parse(row)?.grants || []).filter(g => g.subjectType === type).map(g => subjectLabel(type, g.subjectId))
  return names.length ? names.join('、') : '-'
}
const templateKey = row => parse(row)?.template || ''
const tabCount = row => {
  const p = parse(row)
  if (!p) return 0
  return p.tabs?.length || templateMeta[p.template]?.tabs?.length || 0
}
const templateCounts = computed(() => {
  const out = {}
  views.value.forEach(row => { const key = templateKey(row); if (key) out[key] = (out[key] || 0) + 1 })
  return out
})
const filteredViews = computed(() => {
  const q = query.value
  let result = views.value.filter(row => {
    const p = parse(row) || {}, grants = p.grants || []
    const names = grants.map(g => subjectLabel(g.subjectType, g.subjectId)).join(' ')
    const keyword = `${row.viewName || ''} ${row.viewCode || ''} ${names}`
    const range = q.timeRange || [], created = String(row.createTime || '').replace('T', ' ')
    return (!q.keyword || keyword.toLowerCase().includes(q.keyword.toLowerCase()))
      && (!q.template || templateKey(row) === q.template)
      && (q.status === null || q.status === '' || row.status === q.status)
      && (!range.length || (created >= range[0] && created <= range[1]))
  })
  if (sortState.value.prop) {
    const { prop, order } = sortState.value
    result.sort((a, b) => {
      let av = prop === 'template' ? templates[templateKey(a)] || '旧版' : a[prop] ?? ''
      let bv = prop === 'template' ? templates[templateKey(b)] || '旧版' : b[prop] ?? ''
      if (prop === 'status') return (Number(av) - Number(bv)) * (order === 'ascending' ? 1 : -1)
      return String(av).localeCompare(String(bv), 'zh-CN') * (order === 'ascending' ? 1 : -1)
    })
  }
  return result
})
const currentGrant = computed(() => policy.value.grants[Number(activeGrant.value)])
const allowedModules = computed(() => Object.keys(moduleNames)
  .filter(k => k !== 'operations' || policy.value.template === 'operator')
  .filter(k => k !== 'team' || policy.value.template === 'manager')
  .filter(k => k !== 'report'))
const treeData = computed(() => {
  const map = new Map(nodes.value.map(n => [n.key, { ...n, children: [] }]))
  const roots = []
  for (const n of map.values()) {
    if (map.has(n.parent)) map.get(n.parent).children.push(n)
    else roots.push(n)
  }
  return roots
})
const paths = computed(() => {
  const map = new Map(nodes.value.map(n => [n.key, n]))
  const result = {}
  for (const n of nodes.value) {
    let path = n.label, p = map.get(n.parent)
    while (p) { path = `${p.label} / ${path}`; p = map.get(p.parent) }
    result[n.key] = path
  }
  return result
})
const filterNode = (value, node) => !value || (paths.value[node.key] || node.label).toLowerCase().includes(value.toLowerCase())

function resetQuery () {
  query.value = { keyword: '', template: '', status: null, timeRange: [] }
  sortState.value = { prop: '', order: '' }
}
function sortViews ({ prop, order }) { sortState.value = { prop, order } }
function sortTemplate (a, b) { return String(templates[templateKey(a)] || '旧版').localeCompare(String(templates[templateKey(b)] || '旧版'), 'zh-CN') }
watch(search, v => tree.value?.filter(v))

async function load () {
  loading.value = true
  try {
    const d = await screenApi.configuration()
    views.value = d.views
    users.value = d.users
    roles.value = d.roles
    nodes.value = d.nodes
  } catch (e) { ElMessage.error(message(e)) } finally { loading.value = false }
}

function basePolicy (key) {
  const meta = templateMeta[key] || templateMeta.manager
  return {
    version: 1,
    template: key,
    grants: [],
    supervisorIds: [],
    modules: [...meta.modules],
    layoutMode: 'split',
    tabs: defaultTabs(key)
  }
}
function create () { createWithTemplate('manager') }
function createWithTemplate (key) {
  if (templateMeta[key]?.status === 'disabled') return
  form.value = { viewName: '', viewCode: `screen_${Date.now()}`, status: 0, sortOrder: 0, isDefault: 0 }
  policy.value = basePolicy(key)
  addGrant()
  activeGrant.value = '0'
  visible.value = true
  syncTree()
}
function edit (row) {
  form.value = { ...row }
  const p = parse(row)
  policy.value = p?.version === 1 ? structuredClone(p) : basePolicy('manager')
  if (!policy.value.template) policy.value.template = 'manager'
  if (!policy.value.tabs?.length) policy.value.tabs = defaultTabs(policy.value.template)
  if (policy.value.template === 'manager') policy.value.tabs = policy.value.tabs.filter(key => managerTabs.some(tab => tab.key === key))
  if (!policy.value.layoutMode) policy.value.layoutMode = 'split'
  if (!policy.value.modules?.length) policy.value.modules = [...(templateMeta[policy.value.template]?.modules || [])]
  if (!Array.isArray(policy.value.supervisorIds)) policy.value.supervisorIds = []
  if (!Array.isArray(policy.value.grants)) policy.value.grants = []
  policy.value.grants = policy.value.grants.map(grant => ({ ...grant, scope: { include: [], exclude: [], ...(grant.scope || {}) } }))
  if (!Array.isArray(policy.value.modules)) policy.value.modules = []
  activeGrant.value = policy.value.grants.length ? '0' : ''
  visible.value = true
  syncTree()
}
function changeTemplate (key) {
  if (templateMeta[key]?.status === 'disabled') return
  const previous = policy.value.template
  policy.value.template = key
  policy.value.tabs = defaultTabs(key)
  policy.value.modules = [...(templateMeta[key]?.modules || [])]
  if (key !== 'manager' || previous !== 'manager') policy.value.supervisorIds = []
  if (!policy.value.grants.length) addGrant()
  syncTree()
}
function addGrant () {
  policy.value.grants.push({ subjectType: 'role', subjectId: null, scope: { include: [], exclude: [] } })
  activeGrant.value = String(policy.value.grants.length - 1)
  syncTree()
}
function deleteGrant (index) {
  policy.value.grants.splice(index, 1)
  activeGrant.value = policy.value.grants.length ? '0' : ''
  syncTree()
}
async function syncTree () {
  await nextTick()
  tree.value?.setCheckedKeys(currentGrant.value?.scope?.include || [])
  tree.value?.filter(search.value)
}
function checkScope (_, state) {
  if (!currentGrant.value) return
  currentGrant.value.scope.include = [...state.checkedKeys]
}
function removeNode (key) {
  if (!currentGrant.value) return
  currentGrant.value.scope.include = currentGrant.value.scope.include.filter(k => k !== key)
  syncTree()
}
function toggleTab (key, checked) {
  if (checked === undefined) checked = !policy.value.tabs.includes(key)
  const next = new Set(policy.value.tabs)
  if (checked) next.add(key)
  else next.delete(key)
  policy.value.tabs = managerTabs.map(tab => tab.key).filter(k => next.has(k))
  policy.value.modules = modulesForTabs('manager', policy.value.tabs)
}
function openPreview (key) {
  previewKey.value = key
  previewTabs.value = defaultTabs(key)
  previewModules.value = [...(templateMeta[key]?.modules || [])]
  previewLayoutMode.value = 'split'
  previewVisible.value = true
}
function previewCurrent () {
  previewKey.value = policy.value.template
  previewTabs.value = policy.value.tabs?.length ? policy.value.tabs : defaultTabs(policy.value.template)
  previewModules.value = policy.value.modules?.length ? policy.value.modules : [...(templateMeta[policy.value.template]?.modules || [])]
  previewLayoutMode.value = policy.value.layoutMode || 'split'
  previewVisible.value = true
}

async function save () {
  if (!form.value.viewName?.trim() || !policy.value.grants.length || policy.value.grants.some(g => !g.subjectId)) {
    ElMessage.warning('请填写视图名称及授权对象')
    return
  }
  if (policy.value.template === 'manager' && !policy.value.supervisorIds.length) {
    ElMessage.warning('请选择关联主管')
    return
  }
  if (policy.value.template === 'manager' && !policy.value.tabs.length) {
    ElMessage.warning('请至少选择一个展示页签')
    return
  }
  if (policy.value.template !== 'manager' && policy.value.grants.some(g => !g.scope.include.length)) {
    ElMessage.warning('请为每条授权选择范围')
    return
  }
  if (policy.value.template === 'manager') policy.value.modules = modulesForTabs('manager', policy.value.tabs)
  saving.value = true
  try {
    await screenApi.save({ ...form.value, layoutConfig: JSON.stringify(policy.value), moduleConfig: '[]' })
    visible.value = false
    ElMessage.success('专屏配置已保存')
    await load()
    emit('saved')
  } catch (e) { ElMessage.error(message(e)) } finally { saving.value = false }
}
async function remove (row) {
  try {
    await ElMessageBox.confirm(`删除专屏“${row.viewName}”？`, '删除专屏')
    await screenApi.remove(row.id)
    await load()
    emit('saved')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(message(e)) }
}
async function setDefault (row) {
  if (Number(row.isDefault) === 1) return
  try {
    await screenApi.setDefault(row.id)
    ElMessage.success(`已将“${row.viewName}”设为默认专屏`)
    await load()
    emit('saved')
  } catch (e) { ElMessage.error(message(e)) }
}
function share (row) {
  shareView.value = row
  shareVisible.value = true
}
onMounted(load)
</script>

<style scoped>
.screen-config{padding:0 2px}
.config-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin:16px 0}
.toolbar-actions{display:flex;align-items:center;gap:8px;flex:none}
.config-toolbar h2{margin:0;color:var(--metric-text);font-size:18px}
.config-toolbar p{margin:6px 0 0;color:var(--metric-text-muted);font-size:12px}
.query-bar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:16px}
.keyword-input{width:280px}.query-select{width:140px}.time-picker{width:360px}
.table-summary{color:var(--metric-text-muted);font-size:13px;margin-top:12px}
.table-summary span{margin-left:6px}
.template-label{font-weight:600;color:var(--metric-primary-hover)}.default-tag{margin-left:6px}
.tab-count{color:var(--metric-text-secondary);font-size:12px}
.basic-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}
.form-section{padding:18px 20px;margin:0 0 16px;border:1px solid var(--metric-border);border-radius:var(--metric-radius-lg);background:var(--ui-surface)}
.section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}
.section-heading h3{margin:0;color:var(--metric-text);font-size:16px;font-weight:600}
.section-heading p{margin:5px 0 0;color:var(--metric-text-muted);font-size:12px}
.form-section :deep(.el-form-item){margin-bottom:12px}
.template-picker{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-bottom:14px}
.template-option{display:flex;align-items:center;gap:8px;padding:10px;border:1px solid var(--metric-border);border-radius:10px;background:#fbfdff;text-align:left;cursor:pointer;transition:.18s}
.template-option:hover{border-color:var(--metric-primary-border)}
.template-option.active{border:2px solid var(--metric-primary);background:var(--metric-primary-soft);padding:9px}
.template-option.disabled{opacity:.55;cursor:not-allowed}
.option-icon{display:grid;place-items:center;width:26px;height:26px;flex:none;border-radius:8px;background:var(--ui-surface);color:var(--metric-primary-hover);font-size:12px;font-weight:800;border:1px solid var(--metric-primary-border)}
.option-body{display:grid;gap:2px;min-width:0}
.option-body b{color:var(--metric-text);font-size:12px}
.option-body small{overflow:hidden;color:var(--metric-text-muted);font-size:9px;text-overflow:ellipsis;white-space:nowrap}
.option-meta{display:grid;gap:2px;margin-left:auto;color:var(--metric-text-muted);font-size:9px;text-align:right;white-space:nowrap}
.option-meta em{color:var(--metric-primary-hover);font-style:normal}
.layout-field{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.field-hint{color:var(--metric-text-muted);font-size:11px}
.module-count{padding:3px 8px;border-radius:999px;background:var(--metric-primary-soft);color:var(--metric-primary-hover);font-size:11px}
.manager-tabs{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-bottom:12px}
.manager-tab{display:flex;align-items:flex-start;gap:8px;padding:10px 12px;border:1px solid var(--metric-border);border-radius:10px;background:#fbfdff;cursor:pointer;transition:.18s}
.manager-tab:hover{border-color:var(--metric-primary-border)}
.manager-tab.checked{border-color:var(--metric-primary);background:var(--metric-primary-soft)}
.manager-tab-body{display:grid;gap:2px;min-width:0}
.manager-tab-body b{color:var(--metric-text);font-size:12px}
.manager-tab-body small{color:var(--metric-text-muted);font-size:10px;line-height:1.5}
.manager-tab-index{margin-left:auto;color:var(--metric-text-muted);font-family:var(--metric-font-mono);font-size:10px}
.module-preview{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
.module-chip{padding:2px 8px;border-radius:999px;background:#f1f5f9;color:var(--metric-text-secondary);font-size:10px}
.module-chip.empty{background:var(--metric-warning-soft);color:#b45309}
.module-field{margin-top:8px}
.grant-header{display:flex;align-items:center;gap:10px;justify-content:flex-start;margin:12px 0}
.manager-hint{padding:12px 14px;margin:0 0 16px;color:var(--metric-text-secondary);font-size:13px;background:var(--metric-primary-soft);border-left:3px solid var(--metric-primary);border-radius:4px}
.scope-grid{display:grid;grid-template-columns:1fr 1fr;gap:24px;padding:18px 20px;margin-bottom:16px;border:1px solid var(--metric-border);border-radius:var(--metric-radius-lg);background:#fafbfc}
.scope-item{display:flex;justify-content:space-between;gap:8px;margin:6px 0;font-size:13px}
.hint{color:var(--metric-text-muted);font-size:13px;line-height:1.7}
.screen-dialog :deep(.el-dialog__body){padding:20px 24px 8px;background:var(--metric-surface-soft)}
.screen-dialog :deep(.el-dialog__footer){padding:14px 24px 20px;background:var(--metric-surface-soft)}
.screen-dialog :deep(.el-dialog__header){margin-right:0;padding:20px 24px 16px}
.screen-dialog :deep(.el-input),.screen-dialog :deep(.el-select){width:100%}
@media(max-width:1000px){.template-picker{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:768px){.basic-grid,.scope-grid,.manager-tabs{grid-template-columns:1fr}.keyword-input,.query-select,.time-picker{width:100%}.screen-dialog :deep(.el-dialog__body){padding:14px 12px}.form-section{padding:14px}.section-heading{align-items:flex-start}.section-heading .el-button{flex-shrink:0}}
</style>
