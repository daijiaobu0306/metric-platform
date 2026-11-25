<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <img src="/favicon.svg" alt="logo" class="login-logo" />
        <h1 class="login-title">{{ platformName }}</h1>
        <p class="login-subtitle">请登录以继续</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        class="login-form"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            prefix-icon="User"
            autocomplete="username"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>

        <el-form-item>
          <el-button
            size="large"
            class="sso-btn"
            :loading="ssoLoading"
            @click="handleSsoLogin"
          >
            使用统一认证登录
          </el-button>
        </el-form-item>

        <!-- 统一认证可用时会自动跳转，这里给出可中断的本地登录入口 -->
        <el-form-item v-if="ssoJumping" class="sso-switch-item">
          <div class="sso-switch">
            <span class="sso-switch-hint">正在跳转统一认证登录…</span>
            <el-button link type="primary" class="sso-switch-btn" @click="stayLocalLogin">
              使用本地账号登录
            </el-button>
          </div>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import request from '../../api'
import { sessionApi, platformConfigApi } from '../../api'
import { isSafeRedirect } from '../../router'
import { resetNotificationCursor } from '../../utils/notificationCenter'

const router = useRouter()
const route = useRoute()
const formRef = ref(null)
const loading = ref(false)
const ssoLoading = ref(false)
const platformName = ref('指标体系平台')
// 统一认证可用时，页面会自动跳转到 IdP。跳转前留出一段可中断窗口，
// 让用户能主动选择本地账号密码登录（否则直接打开应用地址会被强行带走）。
const ssoJumping = ref(false)
let ssoJumpTimer = null

const form = reactive({
  // 开发环境自动填 admin/admin123，免输入。prod (vite build) 时为 false，保留空。
  username: import.meta.env.DEV ? 'admin' : '',
  password: import.meta.env.DEV ? 'admin123' : ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/**
 * 从 URL query 中解析 redirect 回跳目标（白名单校验），默认 /。
 */
const resolveRedirectTarget = () => {
  const redirect = router.currentRoute.value.query.redirect
  if (typeof redirect !== 'string') return '/'
  const decoded = decodeURIComponent(redirect)
  return isSafeRedirect(decoded) ? decoded : '/'
}

onMounted(async () => {
  // 加载平台名称
  try {
    const res = await platformConfigApi.getAll()
    if (res.data) {
      const nameCfg = res.data.find(c => c.configKey === 'platform_name')
      if (nameCfg?.configValue) platformName.value = nameCfg.configValue
    }
  } catch (e) { /* 静默失败 */ }

  // 从 URL ?error=... 读取统一认证失败原因
  const errMsg = route.query.error
  if (typeof errMsg === 'string' && errMsg) {
    ElMessage.error(decodeURIComponent(errMsg))
  }

  // Silent SSO 自动尝试（仅当无 sessionKey）：
  //   - 浏览器从 Usermanager「产品服务」点 metric-code 图标 → 落到 /login
  //   - 自动跳到 /api/sso/silent-entry?target=... 后端会带 prompt=none 走 OAuth2 流程
  //   - IdP 已有 session：直接 302 回 /sso/callback，登录成功
  //   - IdP 无 session：跳回 /login?nosso=1；其他认证失败展示错误并停止自动重试
  //   - 跳转前记录本标签页已尝试，避免失败返回后再次自动跳转
  // P2/dev: ?nosso=1 跳过 silent SSO（dev 环境 silent SSO 跳 Usermanager IdP 会卡住）
  const skipSilent = route.query.nosso === '1' || !!errMsg || sessionStorage.getItem('silentSsoTried') === '1'
  if (!skipSilent && !sessionStorage.getItem('sessionKey')) {
    try {
      // 先由本平台探测统一认证服务。不可用时保留本地登录页，避免浏览器卡在 IdP 地址。
      const status = await axios.get('/metric-code/api/sso/availability', { timeout: 2000 })
      if (status.data?.code === 200 && status.data?.data?.available) {
        sessionStorage.setItem('silentSsoTried', '1')
        const target = resolveRedirectTarget()
        const query = target === '/' ? '' : `?target=${encodeURIComponent(target)}`
        // 不立即跳转，先给出 1.2s 可中断窗口：用户点「使用本地账号登录」即取消。
        // 未打断则照旧走静默 SSO，保持从 Usermanager 图标进入时的无感登录体验。
        ssoJumping.value = true
        ssoJumpTimer = setTimeout(() => {
          ssoJumpTimer = null
          window.location.href = `/metric-code/api/sso/silent-entry${query}`
        }, 1200)
      }
    } catch (e) {
      // 本地账号登录保持可用，不因 SSO 探测失败打断登录页。
    }
  }
})

const handleLogin = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await sessionApi.login({
      username: form.username,
      password: form.password
    })

    if (res.code === 200 && res.data) {
      // 存储会话信息
      sessionStorage.setItem('sessionKey', res.data.sessionKey)
      sessionStorage.setItem('displayName', res.data.displayName || form.username)
      sessionStorage.setItem('username', form.username)
      sessionStorage.setItem('idleTimeout', res.data.idleTimeout || 20)
      sessionStorage.setItem('isAdmin', res.data.isAdmin ? 'true' : 'false')
      sessionStorage.setItem('permissions', JSON.stringify(res.data.permissions || []))

      // 关键：清空通知游标，避免上一用户在本浏览器里看过的历史事件再次弹出
      // 配合后端按 loginTime 二次过滤，确保新会话只看上线之后的事件
      resetNotificationCursor()

      ElMessage.success('登录成功')
      // 登录成功：跳转到原本想访问的页面（白名单校验后），否则回首页
      const target = resolveRedirectTarget()
      router.push(target)
    } else {
      ElMessage.error(res.message || '登录失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '用户名或密码错误')
  } finally {
    loading.value = false
  }
}

/**
 * 使用统一认证登录：
 *   1. GET /api/sso/login-url?target=... 把 target 暂存到后端 HttpSession
 *   2. 后端返回 {url: "/oauth2/authorization/sso"}
 *   3. 浏览器 window.location.href → Spring Security OAuth2 流程
 *   4. 统一认证平台登录 → 回调 → Oauth2LoginSuccessHandler → 302 /sso/callback?sessionKey=...
 */
const handleSsoLogin = async () => {
  ssoLoading.value = true
  try {
    const target = resolveRedirectTarget()
    const res = await request.get('/sso/login-url', { params: { target } })
    if (res && res.code === 200 && res.data && res.data.url) {
      window.location.href = res.data.url
    } else {
      ElMessage.error(res?.message || '获取统一认证登录地址失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '统一认证登录入口不可用')
  } finally {
    // window.location.href 会跳转，这里设不设都无所谓
    ssoLoading.value = false
  }
}

const clearSsoJumpTimer = () => {
  if (ssoJumpTimer) {
    clearTimeout(ssoJumpTimer)
    ssoJumpTimer = null
  }
}

/**
 * 中断自动跳转，停留在本地账号密码登录。
 *   - 清掉定时器，避免随后仍被带去 IdP
 *   - silentSsoTried 已在探测成功时写入，刷新后也不会再自动跳转
 *   - 同步把 nosso=1 写进地址栏，语义与「主动退出后禁止静默 SSO」一致
 */
const stayLocalLogin = () => {
  clearSsoJumpTimer()
  ssoJumping.value = false
  sessionStorage.setItem('silentSsoTried', '1')
  if (route.query.nosso !== '1') {
    router.replace({ path: route.path, query: { ...route.query, nosso: '1' } })
  }
  ElMessage.info('已切换为本地账号登录')
}

onUnmounted(clearSsoJumpTimer)
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 400px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-logo {
  width: 64px;
  height: 64px;
  margin-bottom: 12px;
}

.login-title {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 8px;
}

.login-subtitle {
  font-size: 14px;
  color: #909399;
  margin: 0;
}

.login-form {
  margin-top: 20px;
}

.login-btn {
  width: 100%;
  margin-top: 8px;
}

.sso-btn {
  width: 100%;
  margin-top: 4px;
  /* 与主登录按钮做轻量区分 */
  background: linear-gradient(135deg, #4b6cb7 0%, #182848 100%);
  color: #fff;
  border: none;
}
.sso-btn:hover {
  opacity: 0.92;
}

.sso-switch-item {
  margin-bottom: 0;
}

.sso-switch {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  font-size: 13px;
  color: #909399;
}

.sso-switch-hint {
  line-height: 1.4;
}

.sso-switch-btn {
  font-size: 13px;
  padding: 0;
}
</style>
