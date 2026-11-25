<template>
  <el-container class="layout-container">
    <!-- 移动端遮罩层 -->
    <div v-if="isMobile && asideVisible" class="mask" @click="asideVisible = false"></div>

    <el-aside :width="asideWidth" :class="{ 'mobile-aside': isMobile, 'aside-visible': asideVisible, 'is-collapsed': isCollapse }">
      <!-- Logo + 收缩按钮 -->
      <div class="logo">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 28 28" fill="none"><path d="M4 15h4v10H4zM12 10h4v15h-4zM20 5h4v20h-4z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="m4 10 6-5 5 3 7-6m-4 0h4v4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </span>
        <div v-show="!isCollapse" class="brand-copy"><span class="logo-text" :title="platformName">{{ platformName }}</span><span class="brand-subtitle">METRICS MATRIX</span></div>
        <button class="collapse-btn" type="button" :aria-label="isCollapse ? '展开导航菜单' : (isMobile ? '关闭导航菜单' : '收起导航菜单')" @click="toggleAside"><el-icon><Expand v-if="isCollapse" /><Fold v-else /></el-icon></button>
      </div>

      <!-- 导航菜单（根据权限动态渲染） -->
      <el-menu
        :default-active="activeMenu"
        class="el-menu-vertical"
        :collapse="isCollapse"
        router
        @select="handleMenuSelect"
      >
        <template v-for="item in visibleMenus" :key="item.path">
          <!-- 数据汇聚菜单项带警告点 -->
          <el-menu-item v-if="item.path === '/data'" index="/data">
            <el-icon><Grid /></el-icon>
            <template #title>
              <span>数据汇聚</span>
            </template>
            <span v-if="scanIssueCount > 0" class="scan-warning-dot" :title="'有 ' + scanIssueCount + ' 个扫描目录异常'"></span>
          </el-menu-item>
          <!-- 告警管理菜单项带 badge -->
          <el-menu-item v-else-if="item.path === '/alarm'" index="/alarm" class="alarm-menu-item">
            <el-icon><Bell /></el-icon>
            <template #title>
              <span>告警管理</span>
            </template>
            <span v-if="unprocessedCount > 0" class="alarm-badge" @click.stop="goToAlarms" :title="unprocessedCount + ' 条未处理告警'">
              {{ unprocessedCount > 99 ? '99+' : unprocessedCount }}
            </span>
          </el-menu-item>
          <!-- 普通菜单项 -->
          <el-menu-item v-else :index="item.path" :route="item.mode === 'tab' && !item.folder ? $route.fullPath : item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.name }}</template>
          </el-menu-item>
        </template>
      </el-menu>

      <!-- 底部工具栏：全局工具入口统一放这里（当前只有通知铃铛），居中排列 -->
      <div class="sidebar-tools" :class="{ 'is-collapsed': isCollapse }" role="toolbar" aria-label="全局工具">
        <NotificationCenter />
      </div>

      <!-- 用户信息卡片：展开与折叠态共享同一菜单 -->
      <div class="sidebar-user" :class="{ 'is-collapsed': isCollapse }">
        <button class="user-card" type="button" :aria-expanded="showUserDropdown" :title="displayName" @click="toggleUserDropdown">
          <span class="user-avatar">{{ displayName.slice(0, 2).toUpperCase() }}</span>
          <span v-show="!isCollapse" class="user-card-info">
            <span class="user-row"><span class="user-name">{{ displayName }}</span><span class="user-role">{{ isAdmin ? '管理员' : '普通用户' }}</span></span>
            <span class="login-duration"><span class="session-dot"></span>已登录 {{ loginDuration }}</span>
          </span>
          <el-icon v-show="!isCollapse" :size="14" class="dropdown-arrow"><ArrowUp v-if="showUserDropdown" /><ArrowDown v-else /></el-icon>
        </button>
        <div v-if="showUserDropdown" class="user-dropdown" @click.stop>
          <button type="button" class="dropdown-item" @click="openProfile"><el-icon><User /></el-icon><span>个人信息</span></button>
          <button type="button" class="dropdown-item logout-item" @click="logout"><el-icon><SwitchButton /></el-icon><span>退出登录</span></button>
        </div>
      </div>
    </el-aside>

    <!-- direction="vertical" 必须显式声明：el-container 只按子节点类型（ElHeader/ElFooter）
         自动判断纵向，否则会退化成横向布局 -->
    <el-container direction="vertical">
      <el-main>
        <NavigationWorkspace :menus="externalMenus" :builtins="ALL_MENUS" :ready="!externalMenuLoading"><router-view /></NavigationWorkspace>
      </el-main>
    </el-container>

    <!-- 个人信息弹窗 -->
    <el-dialog v-model="profileDialogVisible" title="个人信息" width="450px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户名">{{ profileData.username }}</el-descriptions-item>
        <el-descriptions-item label="显示名称">{{ profileData.displayName }}</el-descriptions-item>
        <el-descriptions-item label="管理员">
          <el-tag :type="profileData.isAdmin ? 'danger' : 'info'" size="small">
            {{ profileData.isAdmin ? '是' : '否' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag v-for="role in profileData.roles" :key="role.id" size="small" style="margin-right: 4px">
            {{ role.roleName }}
          </el-tag>
          <span v-if="!profileData.roles || profileData.roles.length === 0">-</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider />

      <h4 style="margin: 0 0 10px">修改密码</h4>
      <el-form :model="passwordForm" :rules="passwordRules" ref="passwordFormRef" label-width="80px">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="profileDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleSelfPasswordChange" :loading="passwordSaving">修改密码</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import NavigationWorkspace from '../components/config/NavigationWorkspace.vue'
import { externalMenuApi } from '../api'
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { alarmApi, platformConfigApi, errorImportApi, sessionApi, userApi } from '../api'
import { startNotificationPolling, stopNotificationPolling } from '../utils/notificationCenter'
import NotificationCenter from '../components/NotificationCenter.vue'
import { Connection, Tools, Fold, Expand, DataLine, DataBoard, Grid, Bell, Document, Warning, TrendCharts, Monitor, Setting, User, SwitchButton, ArrowUp, ArrowDown, Histogram, Coin, Tickets, CircleCheck, Clock, Cpu, CollectionTag } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const asideVisible = ref(true)
const isMobile = ref(false)
const isCollapse = ref(false)
const unprocessedCount = ref(0)
const scanIssueCount = ref(0)
const platformName = ref('指标体系平台')
const displayName = ref(sessionStorage.getItem('displayName') || '管理员')

// ===== 动态菜单 =====
// 图标按「前端原型参考」侧边栏逐项对齐（Heroicons → Element Plus 的同义映射）：
//   运营观测=柱状图 Histogram / 运营报表=展示板 DataBoard / 数据汇聚=数据库圆柱 Coin
//   告警=铃铛 Bell / 工单=单据 Tickets / 问题=盾形勾 CircleCheck / 指标体系配置=标签集合 CollectionTag
//   变更审计=时钟 Clock / 配置管理=齿轮 Setting / 专屏=显示器 Monitor / 脚本=代码 Cpu
const iconMap = { Histogram, DataBoard, Coin, Bell, Tickets, CircleCheck, CollectionTag, Clock, Setting, Monitor, Cpu, Connection, Tools, User, DataLine, Grid, Document, Warning, TrendCharts }

// 菜单定义（所有可用菜单）
const ALL_MENUS = [
  { path: '/observation', name: '运营观测', icon: 'Histogram', sort: 1 },
  { path: '/report', name: '运营报表', icon: 'DataBoard', sort: 2 },
  { path: '/data', name: '数据汇聚', icon: 'Coin', sort: 3 },
  { path: '/alarm', name: '告警管理', icon: 'Bell', sort: 4 },
  { path: '/gongdan', name: '工单管理', icon: 'Tickets', sort: 5 },
  { path: '/issue', name: '问题管理', icon: 'CircleCheck', sort: 6 },
  { path: '/metric', name: '指标体系配置', icon: 'CollectionTag', sort: 7 },
  { path: '/audit', name: '变更审计', icon: 'Clock', sort: 8 },
  { path: '/config', name: '配置管理', icon: 'Setting', sort: 9 },
  { path: '/dashboard-view', name: '专屏视图', icon: 'Monitor', sort: 10 },
  { path: '/script', name: '技能管理', icon: 'Cpu', sort: 11 },
  // 能力运营菜单已临时隐藏（2026-09-15 暂不需要）。恢复时取消下面这一行的注释 + router/index.js 中对应的路由项。
  // { path: '/ability-operation', name: '能力运营', icon: 'Connection', sort: 12 },
]

const permissions = ref(JSON.parse(sessionStorage.getItem('permissions') || '[]'))
const isAdmin = ref(sessionStorage.getItem('isAdmin') === 'true')

const externalMenus=ref([]),externalMenuLoading=ref(true)
async function loadExternalMenus(){try{externalMenus.value=(await externalMenuApi.list()).data||[]}catch(e){externalMenus.value=[]}finally{externalMenuLoading.value=false}}
const visibleMenus=computed(()=>{
 const base=(isAdmin.value?ALL_MENUS:ALL_MENUS.filter(m=>permissions.value.includes(m.path))).map(m=>({...m}))
 // 一级目录独立显示，不以是否存在子页面作为可见条件。
 const custom=externalMenus.value.filter(m=>!m.parent).map(m=>({...m,path:`/external/${m.id}`}))
 return [...base,...custom].sort((a,b)=>a.sort-b.sort)
})

// ===== 用户下拉菜单 =====
const showUserDropdown = ref(false)

const toggleUserDropdown = () => {
  showUserDropdown.value = !showUserDropdown.value
}

// 点击外部关闭下拉菜单
const closeDropdown = (e) => {
  if (!e.target.closest('.sidebar-user') && !e.target.closest('.sidebar-user-collapsed')) {
    showUserDropdown.value = false
  }
}

// ===== 个人信息弹窗 =====
const profileDialogVisible = ref(false)
const profileData = ref({ username: '', displayName: '', isAdmin: false, roles: [] })
const passwordFormRef = ref(null)
const passwordSaving = ref(false)
const passwordForm = ref({ oldPassword: '', newPassword: '' })
const passwordRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, message: '密码至少6位', trigger: 'blur' }],
}

const openProfile = async () => {
  showUserDropdown.value = false
  try {
    const username = sessionStorage.getItem('username') || 'admin'
    const res = await userApi.getCurrent({ username })
    if (res.code === 200 && res.data) {
      profileData.value = {
        username: res.data.user?.username || '',
        displayName: res.data.user?.displayName || '',
        isAdmin: res.data.user?.isAdmin === 1,
        roles: res.data.roles || []
      }
    }
  } catch (e) { /* 静默 */ }
  passwordForm.value = { oldPassword: '', newPassword: '' }
  profileDialogVisible.value = true
}

const handleSelfPasswordChange = async () => {
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) return

  passwordSaving.value = true
  try {
    const userId = profileData.value.userId || 1 // 简化处理
    await userApi.changePassword(userId, passwordForm.value)
    ElMessage.success('密码修改成功')
    passwordForm.value = { oldPassword: '', newPassword: '' }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '修改失败')
  } finally {
    passwordSaving.value = false
  }
}

const logout = async () => {
  showUserDropdown.value = false
  try {
    await sessionApi.end(sessionKey)
  } catch (e) { /* 静默 */ }
  sessionStorage.clear()
  // 主动退出后禁止静默 SSO，避免统一认证仍在线时立即重新登录。
  sessionStorage.setItem('silentSsoTried', '1')
  router.replace({ path: '/login', query: { nosso: '1' } })
}

// 会话跟踪（后端持久化，刷新不重置，关浏览器自动结束）
const sessionKey = sessionStorage.getItem('sessionKey') || ''
const durationMinutes = ref(0)
let heartbeatTimer = null

const loginDuration = computed(() => {
  const m = durationMinutes.value
  if (m < 60) return m + ' 分钟'
  const hours = Math.floor(m / 60)
  const remainMin = m % 60
  if (hours < 24) return hours + ' 小时 ' + remainMin + ' 分钟'
  const days = Math.floor(hours / 24)
  return days + ' 天 ' + (hours % 24) + ' 小时'
})

// ===== 空闲检测 =====
let idleTimer = null
let idleTimeoutMs = 20 * 60 * 1000 // 默认 20 分钟，启动时从后端读取
const IDLE_EVENTS = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'click']

const resetIdleTimer = () => {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(onIdle, idleTimeoutMs)
}

const onIdle = async () => {
  // 暂停心跳
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
  // V47: 暂停全局通知轮询，避免闲置回来弹一堆积压事件
  stopNotificationPolling()
  // 结束会话
  try {
    await sessionApi.end(sessionKey)
  } catch (e) { /* 静默失败 */ }
  // 存储退出时间
  sessionStorage.setItem('logoutTime', new Date().toISOString())
  // 跳转退出页
  router.push('/session-expired')
}

// 用户操作时重置空闲计时器（绑定在 Layout 挂载时）
const onUserActivity = () => {
  resetIdleTimer()
  // 如果心跳暂停中（空闲后用户操作），恢复心跳
  if (!heartbeatTimer) {
    heartbeatTimer = setInterval(heartbeatSession, 60000)
  }
  // V47: 恢复全局通知轮询
  startNotificationPolling()
}

// 解析 UA 信息
const parseUA = () => {
  const ua = navigator.userAgent
  let browserName = 'Unknown', browserVersion = '', osName = 'Unknown'
  // 浏览器
  if (ua.includes('Firefox/')) {
    browserName = 'Firefox'; browserVersion = ua.split('Firefox/')[1]?.split(' ')[0] || ''
  } else if (ua.includes('Edg/')) {
    browserName = 'Edge'; browserVersion = ua.split('Edg/')[1]?.split(' ')[0] || ''
  } else if (ua.includes('Chrome/') && !ua.includes('Chromium')) {
    browserName = 'Chrome'; browserVersion = ua.split('Chrome/')[1]?.split(' ')[0] || ''
  } else if (ua.includes('Safari/') && ua.includes('Version/')) {
    browserName = 'Safari'; browserVersion = ua.split('Version/')[1]?.split(' ')[0] || ''
  }
  // 操作系统
  if (ua.includes('Windows')) osName = 'Windows'
  else if (ua.includes('Mac OS')) osName = 'macOS'
  else if (ua.includes('Linux')) osName = 'Linux'
  else if (ua.includes('Android')) osName = 'Android'
  else if (ua.includes('iPhone') || ua.includes('iPad')) osName = 'iOS'
  // 设备类型
  let deviceType = 'desktop'
  if (ua.includes('Mobile') || ua.includes('Android')) deviceType = 'mobile'
  else if (ua.includes('Tablet') || ua.includes('iPad')) deviceType = 'tablet'
  return { browserName, browserVersion, osName, deviceType, userAgent: ua }
}

const initSession = async () => {
  if (!sessionKey) return
  try {
    const ua = parseUA()
    const res = await sessionApi.init({
      sessionKey,
      username: sessionStorage.getItem('displayName') || '管理员',
      screenWidth: window.screen.width,
      screenHeight: window.screen.height,
      ...ua
    })
    durationMinutes.value = res.data?.durationMinutes || 0
  } catch (e) {
    // 只有后端明确回答「会话不存在/已失效/用户停用」才踢回登录页；
    // 网络抖动、超时、后端重启这类临时故障不能当成会话失效，
    // 否则一次短暂断连就会把正常会话判死（今天后端频繁重启时尤其明显）。
    const message = String(e?.message || '')
    const sessionInvalid = /会话|登录|停用/.test(message)
    if (sessionInvalid) {
      console.warn('会话已失效，返回登录页:', message)
      sessionStorage.removeItem('sessionKey')
      router.replace('/login')
      return
    }
    console.warn('会话初始化暂时失败，稍后由心跳重试:', message)
  }
}

const heartbeatSession = async () => {
  try {
    const res = await sessionApi.heartbeat(sessionKey)
    if (res.data?.valid === false) {
      sessionStorage.removeItem('sessionKey')
      router.replace('/login')
    } else {
      durationMinutes.value = res.data?.durationMinutes || durationMinutes.value
    }
  } catch (e) {
    // 静默失败
  }
}

const activeMenu = computed(() => {
 const item=externalMenus.value.find(m=>`/external/${m.id}`===route.path)
 if(item?.parent)return item.parent.startsWith('/')?item.parent:`/external/${item.parent}`
 return route.path
})

// 页面标题由各页面自身的页头（工作区 heading / 页面卡片标题）承担，布局层不再重复渲染顶部标题栏。
// 外部菜单的导航名称由 NavigationWorkspace 的 heading 与页签展示。

const asideWidth = computed(() => {
  if (isMobile.value) {
    return asideVisible.value ? '256px' : '0px'
  }
  return isCollapse.value ? '72px' : '224px'
})

const checkMobile = () => {
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) {
    asideVisible.value = false
    isCollapse.value = false
  } else {
    asideVisible.value = true
  }
}

const toggleAside = () => {
  if (isMobile.value) {
    asideVisible.value = !asideVisible.value
  } else {
    isCollapse.value = !isCollapse.value
  }
}

const handleMenuSelect = key => {
  const selected=externalMenus.value.find(m=>`/external/${m.id}`===key)
  if(selected?.mode==='tab'&&!selected.folder){
    const target=window.open('about:blank','_blank');if(!target){ElMessage.warning('浏览器阻止了新标签页，请允许本站弹窗后重试');return}if(target){target.opener=null;const policy=target.document.createElement('meta');policy.name='referrer';policy.content='no-referrer';target.document.head.appendChild(policy)}
    externalMenuApi.launch(selected.id).then(r=>{if(target)target.location.replace(r.data.url)}).catch(()=>target?.close())
  }
  if (isMobile.value) {
    asideVisible.value = false
  }
}

onMounted(async () => {
  loadExternalMenus()
  window.addEventListener('external-menus-changed',loadExternalMenus)
  checkMobile()
  window.addEventListener('resize', checkMobile)
  loadUnprocessedCount()
  loadPlatformName()
  loadScanHealth()
  initSession()
  // 启动全局通知轮询（V47：侧栏铃铛 + 右上角浮层）
  startNotificationPolling()
  // 点击外部关闭用户下拉菜单
  document.addEventListener('click', closeDropdown)

  // 每分钟心跳上报 + 更新时长
  heartbeatTimer = setInterval(heartbeatSession, 60000)
  // 不在 beforeunload 结束会话：刷新也会触发 beforeunload，导致会话被错误结束
  // 会话由后端定时任务（30分钟无心跳）自动清理

  // 空闲检测：从后端读取超时配置
  try {
    const savedTimeout = sessionStorage.getItem('idleTimeout')
    if (savedTimeout) {
      idleTimeoutMs = parseInt(savedTimeout) * 60 * 1000
    }
    const res = await sessionApi.getConfig()
    if (res.data?.idleTimeout) {
      idleTimeoutMs = res.data.idleTimeout * 60 * 1000
      sessionStorage.setItem('idleTimeout', res.data.idleTimeout)
    }
  } catch (e) { /* 使用默认值 */ }

  // 绑定用户活动事件 → 重置空闲计时器
  IDLE_EVENTS.forEach(event => window.addEventListener(event, onUserActivity))
  resetIdleTimer()

  // 监听路由变化，切换到告警页面时刷新数量
  watch(() => route.path, (newPath) => {
    if (newPath === '/alarm') {
      loadUnprocessedCount()
    }
  })
  // 监听告警数量更新事件
  window.addEventListener('alarm-count-update', loadUnprocessedCount)
  // 监听平台配置更新事件
  window.addEventListener('platform-config-updated', loadPlatformName)
  // 监听扫描健康状态更新事件
  window.addEventListener('scan-health-update', loadScanHealth)
})

const loadUnprocessedCount = async () => {
  try {
    const res = await alarmApi.statistics()
    unprocessedCount.value = res.data.unprocessedCount || 0
  } catch (error) {
    console.error('加载未处理告警数量失败:', error)
  }
}

const loadPlatformName = async () => {
  try {
    const res = await platformConfigApi.getAll()
    const name = res.data?.platform_name
    if (name) {
      platformName.value = name
    }
  } catch (error) {
    console.error('加载平台名称失败:', error)
  }
}

const loadScanHealth = async () => {
  try {
    const res = await errorImportApi.scanHealth()
    scanIssueCount.value = res.data?.issueCount || 0
  } catch (error) {
    // 静默失败，不影响页面
    scanIssueCount.value = 0
  }
}

// 点击告警管理文字 → 正常进入告警页面
const goToAlarmPage = (e) => {
  // #title span 的 click.stop 已阻止菜单默认路由，需手动导航
  router.push('/alarm')
}

// 点击数字 badge → 进入未处理告警查询
const goToAlarms = (e) => {
  router.push({ path: '/alarm', query: { processStatus: 0 } })
}

onUnmounted(() => {
  window.removeEventListener('external-menus-changed',loadExternalMenus)
  window.removeEventListener('resize', checkMobile)
  window.removeEventListener('alarm-count-update', loadUnprocessedCount)
  window.removeEventListener('platform-config-updated', loadPlatformName)
  window.removeEventListener('scan-health-update', loadScanHealth)
  document.removeEventListener('click', closeDropdown)
  if (heartbeatTimer) clearInterval(heartbeatTimer)
  // V47: 清理全局通知轮询
  stopNotificationPolling()
  // 清理空闲检测
  if (idleTimer) clearTimeout(idleTimer)
  IDLE_EVENTS.forEach(event => window.removeEventListener(event, onUserActivity))
  // 注意：这里刻意不再调用 /session/end。
  // 组件卸载 ≠ 用户离开：跳登录页、被踢下线、开发热更新都会触发 onUnmounted，
  // 一旦在这里结束会话，任何一次异常跳转都会把服务端会话真正杀掉，用户只能重新登录。
  // 会话结束只由「主动退出」和「后端空闲超时（session_idle_timeout）」两处负责。
})
</script>

<style scoped>
.layout-container {
  height: 100vh;
  position: relative;
}

/* ===== 侧边栏 ===== */
.el-aside {
  background-color: #304156;
  color: #fff;
  transition: width 0.3s;
  overflow: hidden;
  position: relative;
  display: flex;
  flex-direction: column;
}

/* Logo 区域 */
.logo {
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #fff;
  font-size: 14px;
  font-weight: bold;
  border-bottom: 1px solid #404857;
  white-space: nowrap;
  flex-shrink: 0;
  padding: 0 8px;
}

.logo-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
}

.logo-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.collapse-btn {
  cursor: pointer;
  opacity: 0.6;
  transition: opacity 0.2s;
  flex-shrink: 0;
}

.collapse-btn:hover {
  opacity: 1;
}

/* 移动端汉堡菜单 */
.hamburger-mobile {
  display: none;
  font-size: 20px;
  cursor: pointer;
  padding: 4px;
}

@media (max-width: 767px) {
  .hamburger-mobile {
    display: block;
  }
  .collapse-btn {
    display: none;
  }
}

/* 菜单 */
.el-menu-vertical {
  border-right: none;
  background-color: #304156;
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

/* 隐藏滚动条但保留功能 */
.el-menu-vertical::-webkit-scrollbar {
  width: 0;
}

:deep(.el-menu-item) {
  color: #bfcbd9;
  height: 36px;
  line-height: 36px;
}

:deep(.el-menu-item span) {
  font-size: 13px;
}

:deep(.el-menu-item:hover),
:deep(.el-menu-item.is-active) {
  background-color: #263445;
  color: #409eff;
}

:deep(.el-menu--collapse .el-menu-item) {
  padding: 0 !important;
  display: flex;
  justify-content: center;
}

:deep(.el-menu--collapse .el-menu-item .el-tooltip__trigger) {
  padding: 0 !important;
}

/* 子菜单样式 */
:deep(.el-sub-menu__title) {
  color: #bfcbd9;
  height: 36px;
  line-height: 36px;
}

:deep(.el-sub-menu__title:hover) {
  background-color: #263445;
  color: #409eff;
}

:deep(.el-sub-menu .el-menu) {
  background-color: #263445;
}

:deep(.el-sub-menu .el-menu-item) {
  padding-left: 54px !important;
  height: 32px;
  line-height: 32px;
  color: #bfcbd9;
  background-color: #263445;
}

:deep(.el-sub-menu .el-menu-item:hover) {
  background-color: #304156;
  color: #409eff;
}

:deep(.el-sub-menu .el-menu-item.is-active) {
  background-color: #304156;
  color: #409eff;
}

:deep(.el-menu--collapse .el-menu-item) {
  padding: 0 !important;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

:deep(.el-menu--collapse .el-menu-item .el-tooltip__trigger) {
  padding: 0 !important;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 居中图标：收缩时去掉默认的 20px padding */
:deep(.el-menu--collapse .el-menu-item .el-icon) {
  margin: 0;
}

/* ===== 告警数字 badge ===== */
.alarm-badge {
  position: absolute;
  top: 2px;
  right: 8px;
  min-width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  font-size: 10px;
  font-weight: bold;
  color: #fff;
  background-color: #f56c6c;
  border-radius: 10px;
  padding: 0 4px;
  cursor: pointer;
  z-index: 10;
  pointer-events: auto;
}

.alarm-badge:hover {
  background-color: #f78989;
}

/* 告警菜单项需要 relative 定位 */
:deep(.alarm-menu-item) {
  position: relative;
}

/* 收缩状态下 badge 位置微调 */
:deep(.el-menu--collapse .alarm-badge) {
  top: 2px;
  right: 2px;
  min-width: 14px;
  height: 14px;
  line-height: 14px;
  font-size: 9px;
  padding: 0 3px;
}

/* ===== 数据汇聚扫描警告点 ===== */
.scan-warning-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #f56c6c;
  margin-left: 6px;
  animation: scan-pulse 2s infinite;
  flex-shrink: 0;
}

@keyframes scan-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* 收缩状态下警告点绝对定位到右上角 */
:deep(.el-menu--collapse .scan-warning-dot) {
  position: absolute;
  top: 4px;
  right: 4px;
  margin: 0;
  width: 6px;
  height: 6px;
}

/* ===== 用户信息区域 ===== */
.sidebar-user {
  padding: 10px 16px;
  border-top: 1px solid #404857;
  flex-shrink: 0;
  position: relative;
  cursor: pointer;
}

.sidebar-user:hover {
  background-color: #263445;
}

.user-row {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #bfcbd9;
  font-size: 13px;
}

.user-name {
  font-weight: 500;
  flex: 1;
}

.dropdown-arrow {
  transition: transform 0.2s;
}

.login-duration {
  color: #8492a6;
  font-size: 11px;
  margin-top: 2px;
  padding-left: 20px;
}

/* 用户下拉菜单 */
.user-dropdown {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  background: #263445;
  border: 1px solid #404857;
  border-radius: 4px;
  margin-bottom: 4px;
  overflow: hidden;
  z-index: 100;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  color: #bfcbd9;
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.dropdown-item:hover {
  background-color: #304156;
  color: #409eff;
}

.sidebar-user-collapsed {
  padding: 10px 0;
  border-top: 1px solid #404857;
  display: flex;
  justify-content: center;
  color: #bfcbd9;
  flex-shrink: 0;
}

/* ===== 主内容区 ===== */
.el-main {
  background-color: #f0f2f5;
  padding: 15px;
  overflow-x: hidden;
  /* 永远给竖向滚动条留出位置：内容变高变矮（例如切换指标小类后图表数量变化）时，
     滚动条出现/消失不再改变内容宽度，避免「宽度变 17px → 网格列数跳变 → 高度再变」
     这种反复闪烁的布局自激（专屏健康度卡片忽大忽小就是这个原因）。 */
  scrollbar-gutter: stable;
}

/* ===== 移动端 ===== */
.mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  z-index: 999;
}

.mobile-aside {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  z-index: 1000;
}

.aside-visible {
  transform: translateX(0);
}

/* 响应式表格和卡片 */
@media screen and (max-width: 768px) {
  .el-main {
    padding: 10px;
  }

  .el-card {
    margin-bottom: 10px;
  }

  .el-table {
    font-size: 12px;
  }

  :deep(.el-table td.el-table__cell) {
    padding-top: 4px;
    padding-bottom: 4px;
  }

  :deep(.el-table .el-table__body .el-table__row) {
    line-height: 24px;
  }

  :deep(.editable-cell-text) {
    min-height: unset;
    line-height: 20px;
  }

  .el-button {
    padding: 8px 12px;
    font-size: 12px;
  }

  .el-dialog {
    width: 90% !important;
    margin: 10px auto !important;
  }

  .el-pagination {
    flex-wrap: wrap;
    justify-content: center;
  }
}

@media screen and (max-width: 480px) {
  :deep(.el-menu-item) {
    padding: 0 10px !important;
  }

  .el-form-item {
    margin-bottom: 15px;
  }

  .el-input,
  .el-select {
    width: 100% !important;
  }
}
</style>

<style scoped src="@/layouts/sidebar-theme.css"></style>
