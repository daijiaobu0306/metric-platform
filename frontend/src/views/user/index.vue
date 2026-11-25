<template>
  <div class="user-page">
    <el-tabs v-model="activeTab" class="user-tabs">
      <el-tab-pane label="用户列表" name="list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>用户管理</span>
          <el-button type="primary" @click="openDialog()">
            <el-icon><Plus /></el-icon>新增用户
          </el-button>
        </div>
      </template>

      <!-- 筛选区 -->
      <div class="filter-bar" v-balanced-query>
        <el-input v-model="filters.username" placeholder="用户名" clearable style="width: 160px" @keyup.enter="loadData" />
        <el-input v-model="filters.displayName" placeholder="显示名称" clearable style="width: 160px" @keyup.enter="loadData" />
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 120px">
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <div class="query-actions"><el-button type="primary" @click="loadData">查询</el-button><el-button @click="resetFilters">重置</el-button></div>
      </div>

      <!-- 表格 -->
      <el-table :data="tableData" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="displayName" label="显示名称" width="120" />
        <el-table-column label="管理员" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isAdmin === 1 ? 'danger' : 'info'" size="small">
              {{ row.isAdmin === 1 ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">
            <!-- 管理员开关与角色是两套并行机制：开启后走全量菜单，角色暂时不生效。
                 列表按实际生效的身份显示，避免「管理员=是、角色=普通用户」看起来矛盾。 -->
            <el-tooltip v-if="row.isAdmin === 1" placement="top"
              content="已开启管理员开关，拥有全部菜单权限。原有角色仍然保留，关闭开关后会恢复按角色授权。">
              <el-tag type="danger" size="small" class="role-tag">管理员</el-tag>
            </el-tooltip>
            <template v-else>
              <el-tag v-for="role in row._roles" :key="role.id" size="small" class="role-tag">
                {{ role.roleName }}
              </el-tag>
              <span v-if="!row._roles || row._roles.length === 0" class="no-role">-</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="openRoleDialog(row)">分配角色</el-button>
            <el-button size="small" type="primary" @click="openPermissionDialog(row)">权限详情</el-button>
            <el-button size="small" type="info" @click="openPasswordDialog(row)">改密</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)" :disabled="row.id === 1">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="isCurrentAdmin" label="登录日志" name="loginLog">
        <LoginLogTab />
      </el-tab-pane>
    </el-tabs>

    <!-- 新增/编辑用户弹窗 -->
    <el-dialog class="workspace-theme" v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="500px">
      <el-form :model="form" :rules="formRules" ref="formRef" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="管理员">
          <el-switch v-model="form.isAdmin" :active-value="1" :inactive-value="0" />
          <span class="form-hint">开启后拥有全部菜单权限，与角色无关；关闭后恢复按角色授权。</span>
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

    <el-dialog class="workspace-theme" v-model="permissionDialogVisible" :title="`${permissionUser?.username || ''} 的权限`" width="min(950px, 96vw)">
      <p>菜单查看：{{ effectiveMenus.length ? effectiveMenus.join('、') : '无' }}。菜单权限从启用角色继承；修改请到角色管理。</p>
      <p>专屏查看与数据范围：角色授权与用户直接授权会合并。下方可配置该用户的例外授权。</p>
      <SubjectScreenPermissions v-if="permissionDialogVisible && permissionUser" subject-type="user" :subject-id="permissionUser.id" :role-ids="permissionRoleIds" :menu-allowed="permissionUser.isAdmin === 1 || effectiveMenuPaths.includes('/dashboard-view')" />
    </el-dialog>

    <!-- 分配角色弹窗 -->
    <el-dialog class="workspace-theme" v-model="roleDialogVisible" title="分配角色" width="400px">
      <el-checkbox-group v-model="selectedRoleIds">
        <el-checkbox v-for="role in allRoles" :key="role.id" :label="role.id">
          {{ role.roleName }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAssignRoles" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码弹窗 -->
    <el-dialog class="workspace-theme" v-model="passwordDialogVisible" title="修改密码" width="400px">
      <el-form :model="passwordForm" :rules="passwordRules" ref="passwordFormRef" label-width="80px">
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleChangePassword" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi, roleApi, menuApi } from '../../api'
import SubjectScreenPermissions from '../../components/SubjectScreenPermissions.vue'
import LoginLogTab from './LoginLogTab.vue'

const loading = ref(false)
const saving = ref(false)
const tableData = ref([])
const allRoles = ref([])

// 登录日志 tab（admin-only）。从 localStorage.userInfo.isAdmin 读取。
const activeTab = ref('list')
const isCurrentAdmin = ref(false)
try {
  const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
  isCurrentAdmin.value = info.isAdmin === true
} catch (e) {
  isCurrentAdmin.value = false
}

const filters = reactive({ username: '', displayName: '', status: null })
const pagination = reactive({ page: 1, size: 20, total: 0 })

const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, username: '', password: '', displayName: '', isAdmin: 0, status: 1 })
const formRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }],
}

const roleDialogVisible = ref(false)
const selectedRoleIds = ref([])
const originalRoleIds = ref([])
const currentUserId = ref(null)
const permissionDialogVisible = ref(false)
const permissionUser = ref(null)
const permissionRoleIds = ref([])
const effectiveMenus = ref([])
const effectiveMenuPaths = ref([])

const passwordDialogVisible = ref(false)
const passwordFormRef = ref(null)
const passwordForm = reactive({ newPassword: '' })
const passwordRules = { newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }] }

const formatTime = (t) => {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const params = {
      page: pagination.page - 1,
      size: pagination.size,
      ...filters
    }
    const res = await userApi.getList(params)
    if (res.code === 200) {
      const users = res.data.content || []
      // 加载每个用户的角色
      for (const user of users) {
        try {
          const detail = await userApi.getById(user.id)
          user._roles = detail.data?.roles || []
        } catch (e) {
          user._roles = []
        }
      }
      tableData.value = users
      pagination.total = res.data.totalElements || 0
    }
  } catch (e) {
    ElMessage.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

const loadRoles = async () => {
  try {
    const res = await roleApi.getList()
    allRoles.value = res.data || []
  } catch (e) { /* 静默 */ }
}

const resetFilters = () => {
  filters.username = ''
  filters.displayName = ''
  filters.status = null
  pagination.page = 1
  loadData()
}

const openDialog = (row) => {
  isEdit.value = !!row
  if (row) {
    form.id = row.id
    form.username = row.username
    form.password = ''
    form.displayName = row.displayName
    form.isAdmin = row.isAdmin || 0
    form.status = row.status ?? 1
  } else {
    form.id = null
    form.username = ''
    form.password = ''
    form.displayName = ''
    form.isAdmin = 0
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
      await userApi.update(form.id, {
        displayName: form.displayName,
        isAdmin: form.isAdmin,
        status: form.status
      })
      ElMessage.success('更新成功')
    } else {
      await userApi.create({
        username: form.username,
        password: form.password,
        displayName: form.displayName,
        isAdmin: form.isAdmin
      })
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  } finally {
    saving.value = false
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定删除用户 "${row.username}" 吗？`, '提示', { type: 'warning' })
    .then(async () => {
      await userApi.delete(row.id)
      ElMessage.success('删除成功')
      loadData()
    })
    .catch(() => {})
}

const openRoleDialog = async (row) => {
  currentUserId.value = row.id
  try {
    const res = await userApi.getById(row.id)
    selectedRoleIds.value = res.data?.roleIds || []
    originalRoleIds.value = [...selectedRoleIds.value]
  } catch (e) {
    selectedRoleIds.value = []
  }
  roleDialogVisible.value = true
}

const openPermissionDialog = async (row) => {
  try {
    const detail = await userApi.getById(row.id)
    permissionUser.value = row
    permissionRoleIds.value = (detail.data?.roles || []).filter(r => r.status === 1).map(r => r.id)
    const [menus, ...roles] = await Promise.all([menuApi.getList(), ...permissionRoleIds.value.map(id => roleApi.getById(id))])
    const ids = new Set(roles.flatMap(r => r.data?.menuIds || []))
    const allowed = row.isAdmin === 1 ? (menus.data || []).filter(m => m.status === 1)
      : (menus.data || []).filter(m => ids.has(m.id) && m.status === 1)
    effectiveMenus.value = allowed.map(m => m.menuName)
    effectiveMenuPaths.value = allowed.map(m => m.routePath)
    permissionDialogVisible.value = true
  } catch (e) { ElMessage.error('加载权限详情失败') }
}

const handleAssignRoles = async () => {
  saving.value = true
  try {
    const latest = await userApi.getById(currentUserId.value)
    if (JSON.stringify([...(latest.data?.roleIds || [])].sort()) !== JSON.stringify([...originalRoleIds.value].sort())) {
      ElMessage.warning('角色配置已被其他管理员修改，请重新打开后保存')
      return
    }
    await userApi.assignRoles(currentUserId.value, selectedRoleIds.value)
    ElMessage.success('角色分配成功')
    roleDialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error('分配失败')
  } finally {
    saving.value = false
  }
}

const openPasswordDialog = (row) => {
  currentUserId.value = row.id
  passwordForm.newPassword = ''
  passwordDialogVisible.value = true
}

const handleChangePassword = async () => {
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await userApi.changePassword(currentUserId.value, { newPassword: passwordForm.newPassword })
    ElMessage.success('密码修改成功')
    passwordDialogVisible.value = false
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '修改失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadData()
  loadRoles()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filter-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 15px;
}
.pagination-bar {
  margin-top: 15px;
  display: flex;
  justify-content: flex-end;
}
.role-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.no-role {
  color: var(--ui-muted);
}
.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--ui-muted);
  line-height: 1.4;
}
</style>
