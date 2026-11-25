<template>
  <div class="sso-callback-container">
    <div class="sso-callback-card">
      <el-icon :size="56" :color="errorMsg ? '#F56C6C' : '#409EFF'" class="spin"><Loading /></el-icon>
      <h1 class="sso-title">{{ errorMsg ? '登录失败' : '正在登录...' }}</h1>
      <p class="sso-desc">{{ errorMsg || statusText }}</p>
      <pre v-if="errorMsg" class="sso-error-detail">{{ errorDetail }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sessionApi } from '../../api'
import { isSafeRedirect } from '../../router'

const router = useRouter()
const statusText = ref('正在准备会话信息')
const errorMsg = ref('')
const errorDetail = ref('')

/** 给任意 Promise 加超时上限 —— 可选步骤绝不能无限等待。 */
const withTimeout = (promise, ms) => Promise.race([
  promise,
  new Promise((_, reject) => setTimeout(() => reject(new Error(`timeout after ${ms}ms`)), ms))
])

/** URL 上的 permissions 是 JSON 数组字符串（vue-router 已解码过一次），解析失败一律给 null。 */
const parsePermissions = (raw) => {
  if (!raw) return null
  const candidates = [raw, decodeURIComponent(raw)]
  for (const text of candidates) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) return parsed
    } catch (e) { /* 试下一种形式 */ }
  }
  return null
}

onMounted(async () => {
  const query = router.currentRoute.value.query
  const sessionKey = query.sessionKey
  const target = isSafeRedirect(query.target) ? query.target : '/'
  const usernameFromUrl = (query.username || '').toString()
  const isAdminFromUrl = query.isAdmin === 'true' || query.isAdmin === '1'
  const permsFromUrl = parsePermissions(query.permissions)

  console.log('[sso-callback] onMounted', { sessionKey, target, isAdmin: isAdminFromUrl, permsFromUrl })

  if (!sessionKey) {
    ElMessage.error('SSO 回调缺少 sessionKey')
    router.replace('/login')
    return
  }

  // 后端成功回调已经把 sessionKey / 用户档案 / permissions 全拼在 URL 上，
  // 这里先无条件落盘：不依赖任何接口也能完成登录。
  sessionStorage.setItem('sessionKey', sessionKey)
  sessionStorage.setItem('username', usernameFromUrl || '管理员')
  sessionStorage.setItem('displayName', (query.displayName || usernameFromUrl || '管理员').toString())
  sessionStorage.setItem('isAdmin', String(isAdminFromUrl))
  sessionStorage.setItem('idleTimeout', String(Number(query.idleTimeout) || 20))
  sessionStorage.setItem('permissions', JSON.stringify(permsFromUrl || []))

  try {
    // 只有「老后端回调 URL 没带 permissions」时才需要 init 补档案。
    // 即使需要，也最多等 5 秒；超时就按 URL 参数继续登录。
    // 之前是无限 await，init 一旦挂住整个登录就死在这里（页面永远停在“正在激活会话”）。
    const needProfile = !isAdminFromUrl && permsFromUrl === null
    if (needProfile) {
      statusText.value = '正在激活会话...'
      try {
        const initRes = await withTimeout(
          sessionApi.init({ sessionKey, username: usernameFromUrl || '管理员' }), 5000)
        const data = (initRes && initRes.data) || {}
        if (Array.isArray(data.permissions)) {
          sessionStorage.setItem('permissions', JSON.stringify(data.permissions))
        }
        if (data.idleTimeout) sessionStorage.setItem('idleTimeout', String(data.idleTimeout))
        if (data.displayName) sessionStorage.setItem('displayName', data.displayName)
        if (data.isAdmin === true || data.isAdmin === 1) sessionStorage.setItem('isAdmin', 'true')
      } catch (e) {
        console.warn('[sso-callback] init 未在 5 秒内返回，按 URL 参数继续登录', e)
      }
    }

    statusText.value = '登录成功，正在跳转...'
    await router.replace(target || '/')
  } catch (e) {
    console.error('[sso-callback] 跳转失败', e)
    // 兜底：路由异常时整页跳首页，避免停在白屏
    window.location.replace('/metric-code/')
  }
})
</script>

<style scoped>
.sso-callback-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.sso-callback-card {
  width: 380px;
  padding: 48px 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  text-align: center;
}

.spin {
  animation: spin 1.2s linear infinite;
  margin-bottom: 20px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.sso-title {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 12px;
}

.sso-desc {
  font-size: 14px;
  color: #909399;
  margin: 0;
}

.sso-error-detail {
  margin-top: 16px;
  padding: 12px;
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  border-radius: 6px;
  font-size: 12px;
  color: #c45656;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 240px;
  overflow: auto;
}
</style>
