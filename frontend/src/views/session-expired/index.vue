<template>
  <div class="expired-container">
    <div class="expired-card">
      <div class="expired-icon">
        <el-icon :size="64" color="#E6A23C"><WarningFilled /></el-icon>
      </div>

      <h1 class="expired-title">会话已结束</h1>
      <p class="expired-desc">由于长时间无操作，您的会话已自动结束</p>

      <div class="info-section">
        <div class="info-row">
          <span class="info-label">用户名</span>
          <span class="info-value">{{ displayName }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">退出时间</span>
          <span class="info-value">{{ logoutTimeStr }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">已退出时长</span>
          <span class="info-value expired-duration">{{ expiredDuration }}</span>
        </div>
      </div>

      <el-button
        type="primary"
        size="large"
        class="relogin-btn"
        @click="goLogin"
      >
        重新登录
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const displayName = ref(sessionStorage.getItem('displayName') || '管理员')
const logoutTimeStr = ref('')
const expiredDuration = ref('0 秒')
let durationTimer = null
let logoutTime = null

const formatExpiredDuration = (seconds) => {
  if (seconds < 60) return seconds + ' 秒'
  const minutes = Math.floor(seconds / 60)
  const remainSec = seconds % 60
  if (minutes < 60) return minutes + ' 分 ' + remainSec + ' 秒'
  const hours = Math.floor(minutes / 60)
  const remainMin = minutes % 60
  if (hours < 24) return hours + ' 时 ' + remainMin + ' 分'
  const days = Math.floor(hours / 24)
  return days + ' 天 ' + (hours % 24) + ' 时'
}

const updateDuration = () => {
  if (!logoutTime) return
  const now = new Date()
  const seconds = Math.floor((now - logoutTime) / 1000)
  expiredDuration.value = formatExpiredDuration(seconds)
}

onMounted(() => {
  // 解析退出时间
  const stored = sessionStorage.getItem('logoutTime')
  if (stored) {
    logoutTime = new Date(stored)
    logoutTimeStr.value = logoutTime.toLocaleString('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
  } else {
    // 如果没有存储退出时间，使用当前时间
    logoutTime = new Date()
    logoutTimeStr.value = logoutTime.toLocaleString('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
  }

  updateDuration()
  durationTimer = setInterval(updateDuration, 1000)
})

onUnmounted(() => {
  if (durationTimer) clearInterval(durationTimer)
})

const goLogin = () => {
  // 清除会话信息
  sessionStorage.removeItem('sessionKey')
  sessionStorage.removeItem('displayName')
  sessionStorage.removeItem('logoutTime')
  router.push('/login')
}
</script>

<style scoped>
.expired-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.expired-card {
  width: 440px;
  padding: 48px 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  text-align: center;
}

.expired-icon {
  margin-bottom: 20px;
}

.expired-title {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 8px;
}

.expired-desc {
  font-size: 14px;
  color: #909399;
  margin: 0 0 28px;
}

.info-section {
  background: #f5f7fa;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 28px;
  text-align: left;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}

.info-row + .info-row {
  border-top: 1px solid #e4e7ed;
}

.info-label {
  font-size: 14px;
  color: #909399;
}

.info-value {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.expired-duration {
  color: #E6A23C;
  font-weight: 600;
}

.relogin-btn {
  width: 100%;
}
</style>
