<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" title="数据范围按专屏分别授权；用户同时命中多条授权时，可见范围会合并。菜单权限仍需允许进入专屏视图。" />
    <el-alert v-if="!menuAllowed" type="warning" :closable="false" style="margin-top: 8px" title="当前对象没有专屏视图菜单权限；请先通过角色开放菜单，再新增专屏授权。" />
    <el-table :data="views" style="margin-top: 12px">
      <el-table-column prop="viewName" label="专屏" />
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 1 ? '已发布' : '草稿' }}</template></el-table-column>
      <el-table-column label="授权" width="180"><template #default="{ row }">{{ grantsOf(row).length ? `直接 ${grantsOf(row).length} 条` : '无直接授权' }}{{ inheritedOf(row).length ? `；角色继承 ${inheritedOf(row).length} 条` : '' }}</template></el-table-column>
      <el-table-column label="授权来源与范围" min-width="280"><template #default="{ row }"><span class="summary">{{ summary(row) }}</span></template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">配置</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="`配置 ${editing?.viewName || ''} 的授权`" width="min(950px, 96vw)" append-to-body :close-on-click-modal="false">
      <el-alert v-if="policy?.template === 'manager'" type="info" :closable="false" title="科室经理的数据范围从关联主管的业务主管专屏继承；这里配置的是进入本专屏的权限。" />
      <div v-for="(grant, index) in grants" :key="index" class="grant-card">
        <div class="grant-title"><strong>授权 {{ index + 1 }}</strong><el-button link type="danger" @click="grants.splice(index, 1)">删除</el-button></div>
        <template v-if="policy?.template !== 'manager'">
          <el-tree-select v-model="grant.scope.include" :data="treeData" node-key="key" :props="{ label: 'label', value: 'key', children: 'children' }" multiple show-checkbox check-strictly filterable style="width: 100%; margin: 10px 0" placeholder="选择明确授权的数据范围" />
          <el-tree-select v-model="grant.scope.exclude" :data="treeData" node-key="key" :props="{ label: 'label', value: 'key', children: 'children' }" multiple show-checkbox check-strictly filterable style="width: 100%" placeholder="选择本条授权排除的范围（可选）" />
          <p class="hint">父节点包含其后代；只选择指标不会获得上级汇总。排除仅作用于本条授权。</p>
        </template>
      </div>
      <el-button @click="addGrant">添加授权</el-button>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { screenApi, message } from './dashboard-view/api'

const props = defineProps({ subjectType: { type: String, required: true }, subjectId: { type: Number, required: true }, roleIds: { type: Array, default: () => [] }, menuAllowed: { type: Boolean, default: true } })
const loading = ref(false), saving = ref(false), visible = ref(false)
const views = ref([]), nodes = ref([]), roles = ref([]), editing = ref(null), policy = ref(null), grants = ref([])
const parse = row => { try { return JSON.parse(row.layoutConfig) } catch { return null } }
const grantsOf = row => (parse(row)?.grants || []).filter(g => g.subjectType === props.subjectType && g.subjectId === props.subjectId)
const inheritedOf = row => props.subjectType === 'user' ? (parse(row)?.grants || []).filter(g => g.subjectType === 'role' && props.roleIds.includes(g.subjectId)) : []
function summary(row) {
  const p = parse(row)
  const all = grantsOf(row).map(g => ({ grant: g, source: '直接授权' }))
    .concat(inheritedOf(row).map(g => ({ grant: g, source: `角色 ${roles.value.find(r => r.id === g.subjectId)?.roleName || g.subjectId}` })))
  if (!all.length) return '无授权'
  const names = new Map(nodes.value.map(n => [n.key, n.label]))
  return all.map(({ grant, source }) => `${source}：${p?.template === 'manager' ? '继承关联主管范围' : grant.scope.include.map(k => names.get(k) || k).join('、') || '空范围'}${grant.scope.exclude.length ? `（排除 ${grant.scope.exclude.map(k => names.get(k) || k).join('、')}）` : ''}`).join('；')
}
const treeData = computed(() => {
  const map = new Map(nodes.value.map(n => [n.key, { ...n, children: [] }]))
  const roots = []
  for (const node of map.values()) { if (map.has(node.parent)) map.get(node.parent).children.push(node); else roots.push(node) }
  return roots
})
async function load() {
  loading.value = true
  try { const data = await screenApi.configuration(); views.value = data.views; nodes.value = data.nodes; roles.value = data.roles }
  catch (e) { ElMessage.error(message(e)) }
  finally { loading.value = false }
}
function edit(row) {
  editing.value = row
  policy.value = parse(row)
  if (!policy.value || policy.value.version !== 1) { ElMessage.warning('旧版专屏请先在专屏视图页面升级配置'); return }
  grants.value = structuredClone(grantsOf(row))
  visible.value = true
}
function addGrant() { grants.value.push({ subjectType: props.subjectType, subjectId: props.subjectId, scope: { include: [], exclude: [] } }) }
async function save() {
  if (grants.value.length && !props.menuAllowed) { ElMessage.warning('请先配置专屏视图菜单权限'); return }
  if (policy.value.template !== 'manager' && grants.value.some(g => !g.scope.include.length)) { ElMessage.warning('每条授权都需要明确选择范围'); return }
  saving.value = true
  try {
    // 避免覆盖其他管理员刚修改的授权或展示模块。
    const latest = await screenApi.configuration()
    const row = latest.views.find(v => v.id === editing.value.id)
    if (!row || row.layoutConfig !== editing.value.layoutConfig) { ElMessage.warning('此专屏配置已变更，请重新打开后再保存'); await load(); return }
    const next = structuredClone(policy.value)
    next.grants = next.grants.filter(g => !(g.subjectType === props.subjectType && g.subjectId === props.subjectId)).concat(grants.value)
    if (!next.grants.length) { ElMessage.warning('专屏至少需要一条授权；请先在专屏视图页面调整或删除视图'); return }
    await screenApi.save({ ...row, layoutConfig: JSON.stringify(next) })
    visible.value = false
    ElMessage.success('专屏授权已保存')
    await load()
  } catch (e) { ElMessage.error(message(e)) }
  finally { saving.value = false }
}
watch(() => props.subjectId, load)
onMounted(load)
</script>

<style scoped>
.grant-card { border: 1px solid #e4e7ed; border-radius: 8px; padding: 14px; margin: 12px 0 }
.grant-title { display: flex; justify-content: space-between; align-items: center }
.hint { color: #737d8c; font-size: 13px }
.summary { overflow-wrap: anywhere }
</style>
