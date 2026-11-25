<template>
  <div class="role-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>角色管理</span>
          <el-button type="primary" @click="openDialog()">
            <el-icon><Plus /></el-icon>新增角色
          </el-button>
        </div>
      </template>

      <el-table :data="tableData" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="roleName" label="角色名称" width="150" />
        <el-table-column prop="description" label="描述" min-width="200" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="菜单权限" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="menu in row._menus" :key="menu.id" size="small" class="menu-tag">
              {{ menu.menuName }}
            </el-tag>
            <span v-if="!row._menus || row._menus.length === 0" class="no-menu">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="openMenuDialog(row)">配置权限与成员</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)" :disabled="row.roleName === '普通用户'">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑角色弹窗 -->
    <el-dialog class="workspace-theme" v-model="dialogVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="450px">
      <el-form :model="form" :rules="formRules" ref="formRef" label-width="80px">
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色权限与成员配置弹窗 -->
    <el-dialog class="workspace-theme" v-model="menuDialogVisible" title="配置角色" width="min(950px, 96vw)">
      <el-tabs>
        <el-tab-pane label="菜单查看">
          <el-alert
            v-if="isBasicRole"
            class="basic-role-alert"
            type="warning"
            :closable="false"
            show-icon
            title="「普通用户」是系统自动分配给新用户与统一认证（SSO）登录账号的兜底角色，菜单固定为「运营观测」，不能在此修改。"
          >
            <p class="alert-line">要让这类账号看到更多页面：新建一个角色 → 在它的「菜单查看」勾选页面并保存 → 到「关联用户」把账号加进来。</p>
            <p class="alert-line">同一账号可拥有多个角色，权限按并集计算，「运营观测」不会因此丢失。</p>
            <el-button size="small" type="primary" class="alert-action" @click="startCreateRoleFromMenuDialog">
              新建角色并配置菜单
            </el-button>
          </el-alert>
          <div class="menu-checkbox-group" :class="{ 'is-locked': isBasicRole }">
            <el-checkbox
              v-for="menu in allMenus"
              :key="menu.id"
              :label="menu.id"
              v-model="menu.checked"
              :disabled="isBasicRole"
            >
              {{ menu.menuName }}
            </el-checkbox>
          </div>
          <div class="select-all-bar">
            <el-button size="small" :disabled="isBasicRole" @click="selectAllMenus">全选</el-button>
            <el-button size="small" :disabled="isBasicRole" @click="deselectAllMenus">全不选</el-button>
            <el-button v-if="!isBasicRole" type="primary" @click="handleAssignMenus" :loading="saving">保存</el-button>
            <span v-else class="locked-hint">基础角色菜单固定，无法保存</span>
          </div>
        </el-tab-pane>
        <el-tab-pane label="关联用户">
          <el-select v-model="selectedUserIds" multiple filterable placeholder="选择用户" style="width: 100%">
            <el-option v-for="user in allUsers" :key="user.id" :value="user.id" :label="`${user.displayName || user.username} (${user.username})`" />
          </el-select>
          <p class="hint">这里与用户管理页的“分配角色”使用同一关系；不会清除用户的其他角色。</p>
          <el-button type="primary" :loading="saving" @click="handleAssignUsers">保存成员</el-button>
        </el-tab-pane>
        <el-tab-pane label="专屏查看与数据范围">
          <SubjectScreenPermissions v-if="menuDialogVisible && currentRoleId" subject-type="role" :subject-id="currentRoleId" :menu-allowed="allMenus.some(m => m.routePath === '/dashboard-view' && m.checked)" />
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { roleApi, menuApi, userApi } from '../../api'
import SubjectScreenPermissions from '../../components/SubjectScreenPermissions.vue'

const loading = ref(false)
const saving = ref(false)
const tableData = ref([])
const allMenus = ref([])
const allUsers = ref([])
const selectedUserIds = ref([])
const originalUserIds = ref([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, roleName: '', description: '', status: 1 })
const formRules = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}

const menuDialogVisible = ref(false)
const currentRoleId = ref(null)
const currentRoleName = ref('')
// 「普通用户」由后端固定为运营观测菜单（RoleController#assignMenus 直接拒绝），
// 前端不再展示可点击的保存按钮，改为说明成因并给出「新建角色」的出路。
const isBasicRole = computed(() => currentRoleName.value === '普通用户')
// 从权限弹窗跳去新建角色时置为 true，创建成功后自动接着打开新角色的权限配置
const pendingConfigAfterCreate = ref(false)

const formatTime = (t) => {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await roleApi.getList()
    const roles = res.data || []
    // 加载每个角色的菜单权限
    const menuList = allMenus.value.length > 0 ? allMenus.value : (await menuApi.getList()).data || []
    if (allMenus.value.length === 0) allMenus.value = menuList
    for (const role of roles) {
      try {
        const detail = await roleApi.getById(role.id)
        const menuIds = detail.data?.menuIds || []
        role._menus = menuList.filter(m => menuIds.includes(m.id))
      } catch (e) {
        role._menus = []
      }
    }
    tableData.value = roles
  } catch (e) {
    ElMessage.error('加载角色列表失败')
  } finally {
    loading.value = false
  }
}

const loadMenus = async () => {
  try {
    const res = await menuApi.getList()
    allMenus.value = (res.data || []).map(m => ({ ...m, checked: false }))
  } catch (e) { /* 静默 */ }
}

const openDialog = (row, opts = {}) => {
  isEdit.value = !!row
  // 只有「从权限弹窗新建角色」这条路才接续配置，普通新增不接续
  pendingConfigAfterCreate.value = !row && opts.chainConfig === true
  if (row) {
    form.id = row.id
    form.roleName = row.roleName
    form.description = row.description
    form.status = row.status ?? 1
  } else {
    form.id = null
    form.roleName = ''
    form.description = ''
    form.status = 1
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (isEdit.value) {
      await roleApi.update(form.id, {
        roleName: form.roleName,
        description: form.description,
        status: form.status
      })
      ElMessage.success('更新成功')
      dialogVisible.value = false
      loadData()
    } else {
      const created = await roleApi.create({
        roleName: form.roleName,
        description: form.description
      })
      const chainConfig = pendingConfigAfterCreate.value
      pendingConfigAfterCreate.value = false
      ElMessage.success('创建成功')
      dialogVisible.value = false
      await loadData()
      const newRoleId = created?.data?.id
      if (chainConfig && newRoleId) {
        const row = tableData.value.find(r => r.id === newRoleId)
        if (row) openMenuDialog(row)
      }
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  } finally {
    saving.value = false
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定删除角色 "${row.roleName}" 吗？`, '提示', { type: 'warning' })
    .then(async () => {
      await roleApi.delete(row.id)
      ElMessage.success('删除成功')
      loadData()
    })
    .catch(() => {})
}

const openMenuDialog = async (row) => {
  currentRoleId.value = row.id
  currentRoleName.value = row.roleName
  try {
    const res = await roleApi.getById(row.id)
    const users = []
    let page = 0, totalPages = 1
    while (page < totalPages) {
      const result = await userApi.getList({ page, size: 100 })
      users.push(...(result.data?.content || []))
      totalPages = result.data?.totalPages || 0
      page++
    }
    const menuIds = res.data?.menuIds || []
    selectedUserIds.value = res.data?.userIds || []
    originalUserIds.value = [...selectedUserIds.value]
    allUsers.value = users
    allMenus.value.forEach(m => {
      m.checked = menuIds.includes(m.id)
    })
  } catch (e) {
    ElMessage.error('加载角色配置失败')
    return
  }
  menuDialogVisible.value = true
}

const startCreateRoleFromMenuDialog = () => {
  menuDialogVisible.value = false
  openDialog(null, { chainConfig: true })
}

const handleAssignUsers = async () => {
  saving.value = true
  try {
    const latest = await roleApi.getById(currentRoleId.value)
    if (JSON.stringify([...(latest.data?.userIds || [])].sort()) !== JSON.stringify([...originalUserIds.value].sort())) {
      ElMessage.warning('成员配置已被其他管理员修改，请重新打开后保存')
      return
    }
    await roleApi.assignUsers(currentRoleId.value, selectedUserIds.value)
    originalUserIds.value = [...selectedUserIds.value]
    ElMessage.success('成员已更新')
  } catch (e) { ElMessage.error(e.response?.data?.message || '成员更新失败') }
  finally { saving.value = false }
}

const selectAllMenus = () => {
  allMenus.value.forEach(m => { m.checked = true })
}

const deselectAllMenus = () => {
  allMenus.value.forEach(m => { m.checked = false })
}

const handleAssignMenus = async () => {
  saving.value = true
  try {
    const menuIds = allMenus.value.filter(m => m.checked).map(m => m.id)
    await roleApi.assignMenus(currentRoleId.value, menuIds)
    ElMessage.success('权限配置成功')
    menuDialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error('配置失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadMenus().then(() => loadData())
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.menu-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.no-menu {
  color: var(--ui-muted);
}
.menu-checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 15px;
}
.select-all-bar {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}
.basic-role-alert {
  margin-bottom: 14px;
}
.basic-role-alert .alert-line {
  margin: 0 0 6px;
  line-height: 1.6;
}
.basic-role-alert .alert-action {
  margin-top: 4px;
}
.menu-checkbox-group.is-locked {
  opacity: 0.65;
}
.locked-hint {
  align-self: center;
  font-size: 12px;
  color: var(--ui-muted);
}
</style>
