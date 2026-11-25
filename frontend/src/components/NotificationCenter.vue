<template>
  <div class="notif-wrap">
    <!-- 铃铛 -->
    <button
      ref="bellRef"
      class="notif-bell"
      :class="{ 'is-open': notifyState.panelOpen, 'is-muted': muted }"
      :title="bellTitle"
      aria-label="通知"
      :aria-expanded="notifyState.panelOpen"
      @click="togglePanel"
    >
      <el-icon :size="18">
        <MuteNotification v-if="muted" />
        <Bell v-else />
      </el-icon>
      <span v-if="notifyState.unread > 0" class="notif-badge">
        {{ notifyState.unread > 99 ? '99+' : notifyState.unread }}
      </span>
    </button>

    <!-- 通知面板：teleport 到 body，按铃铛位置向右上方展开，避免被侧栏 overflow/transform 裁切 -->
    <Teleport to="body">
      <transition name="np-fade">
        <div v-if="notifyState.panelOpen" class="notif-panel" :style="panelStyle" @click.stop>
          <div class="np-head">
            <span class="np-title">通知</span>
            <span v-if="muted" class="np-mute-tip">已静音 {{ muteRemain }} 分钟</span>
            <el-icon class="np-gear" :class="{ 'is-active': showSettings }" @click="showSettings = !showSettings">
              <Setting />
            </el-icon>
          </div>

          <!-- 设置 -->
          <div v-if="showSettings" class="np-settings">
            <div class="np-row">
              <div>
                <div class="np-row-label">弹出通知</div>
                <div class="np-row-desc">关闭后仍会记录到列表，只是不再右上角弹出</div>
              </div>
              <el-switch
                :model-value="notifyState.settings.enabled"
                size="small"
                @change="val => updateSettings({ enabled: val })"
              />
            </div>

            <div class="np-block">
              <div class="np-row-label">免打扰</div>
              <div class="np-chips">
                <button
                  v-for="opt in MUTE_OPTIONS"
                  :key="opt.minutes"
                  class="np-chip"
                  :class="{ 'is-active': activeMute === opt.minutes }"
                  @click="muteFor(opt.minutes)"
                >{{ opt.label }}</button>
              </div>
              <div v-if="muted" class="np-row-desc">
                静音期间不再弹出，通知仍会记入列表；剩余 {{ muteRemain }} 分钟。
              </div>
            </div>

            <div class="np-block">
              <div class="np-row-label">弹出时长</div>
              <div class="np-chips">
                <button
                  v-for="sec in DURATION_OPTIONS"
                  :key="sec"
                  class="np-chip"
                  :class="{ 'is-active': Number(notifyState.settings.duration) === sec }"
                  @click="updateSettings({ duration: sec })"
                >{{ sec }} 秒</button>
              </div>
            </div>

            <div class="np-block">
              <div class="np-row-label">通知类型</div>
              <div class="np-types">
                <label v-for="t in NOTIFY_TYPES" :key="t.key" class="np-type">
                  <el-checkbox
                    :model-value="notifyState.settings.types[t.key] !== false"
                    @change="val => setTypeEnabled(t.key, val)"
                  />
                  <span class="np-type-dot" :style="{ background: t.color }"></span>
                  <span class="np-type-label">{{ t.label }}</span>
                </label>
              </div>
            </div>
          </div>

          <!-- 列表 -->
          <div v-else class="np-list">
            <div v-if="notifyState.events.length === 0" class="np-empty">暂无通知</div>
            <div
              v-for="ev in notifyState.events"
              :key="ev.id"
              class="np-item"
              @click="openEvent(ev)"
            >
              <span class="np-item-dot" :style="{ background: typeMeta(ev.eventType).color }"></span>
              <div class="np-item-main">
                <div class="np-item-title">{{ ev.title }}</div>
                <div v-if="ev.content" class="np-item-content">{{ ev.content }}</div>
              </div>
              <span class="np-item-time">{{ formatRelative(ev.createdAt) }}</span>
            </div>
          </div>

          <div class="np-foot">
            <span class="np-link" @click="markAllRead">全部已读</span>
            <span class="np-link np-link-history" @click="openHistory">查看全部历史</span>
            <span class="np-link" @click="clearHistory">清空本机列表</span>
          </div>
        </div>
      </transition>
    </Teleport>

    <!-- 右上角浮层：自己实现，样式完全可控；同样 teleport，避免受侧栏定位影响 -->
    <Teleport to="body">
      <transition-group name="toast" tag="div" class="toast-stack">
        <div
          v-for="t in notifyState.toasts"
          :key="t.uid"
          class="toast-card"
          :style="{ '--tone': t.color, '--tone-bg': t.bg }"
          @mouseenter="pauseToast(t.uid)"
          @mouseleave="resumeToast(t.uid)"
          @click="openToast(t)"
        >
          <span class="toast-bar"></span>
          <div class="toast-body">
            <div class="toast-head">
              <span class="toast-tag">{{ t.label }}</span>
              <span class="toast-time">{{ formatClock(t.time) }}</span>
              <el-icon class="toast-close" @click.stop="dismissToast(t.uid)"><Close /></el-icon>
            </div>
            <div class="toast-title">{{ t.title }}</div>
            <div v-if="t.content" class="toast-content">{{ t.content }}</div>
          </div>
        </div>
      </transition-group>
    </Teleport>

    <!-- 历史通知管理（服务端全量，可单条/批量删除、按条件清理） -->
    <NotificationHistoryDrawer v-model="historyVisible" @changed="handleHistoryChanged" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, MuteNotification, Setting, Close } from '@element-plus/icons-vue'
import NotificationHistoryDrawer from './NotificationHistoryDrawer.vue'
import {
  notifyState,
  NOTIFY_TYPES,
  updateSettings,
  setTypeEnabled,
  muteFor,
  muteRemainMinutes,
  markAllRead,
  clearHistory,
  removeLocalEvents,
  dismissToast,
  pauseToast,
  resumeToast,
  routeForType,
  typeMeta,
  formatRelative,
  formatClock,
} from '../utils/notificationCenter'

const router = useRouter()
const showSettings = ref(false)
const muteRemain = ref(0)
const historyVisible = ref(false)

// ===== 面板定位 =====
// 铃铛从顶部栏移到侧栏底部后，面板改为 teleport 到 body 的固定浮层：
// 底边与铃铛底边齐平后向右展开，右侧/顶部空间不足时自动收进视口。
const bellRef = ref(null)
const panelStyle = ref({})
const PANEL_WIDTH = 348
const PANEL_GAP = 12

const updatePanelPosition = () => {
  const bell = bellRef.value
  if (!bell) return
  // 面板贴侧栏右边缘外展，底边与铃铛底边齐平：既不被侧栏压住，也不会离铃铛太远。
  const sidebar = bell.closest('.el-aside')
  const bellRect = bell.getBoundingClientRect()
  const anchorRight = sidebar ? sidebar.getBoundingClientRect().right : bellRect.right
  const viewportW = window.innerWidth
  const viewportH = window.innerHeight
  const width = Math.min(PANEL_WIDTH, viewportW - 24)
  const left = Math.min(Math.max(anchorRight + PANEL_GAP, 12), viewportW - width - 12)
  const bottom = Math.max(viewportH - bellRect.bottom, 12)
  panelStyle.value = {
    left: `${left}px`,
    bottom: `${bottom}px`,
    width: `${width}px`,
    maxHeight: `${Math.max(240, viewportH - bottom - 16)}px`,
  }
}

watch(() => notifyState.panelOpen, (open) => {
  if (open) nextTick(updatePanelPosition)
})

const MUTE_OPTIONS = [
  { label: '不静音', minutes: 0 },
  { label: '30 分钟', minutes: 30 },
  { label: '1 小时', minutes: 60 },
  { label: '2 小时', minutes: 120 },
]
const DURATION_OPTIONS = [3, 5, 8]

const muted = computed(() => {
  void muteRemain.value
  const until = notifyState.settings.mutedUntil || 0
  return until > Date.now()
})

const activeMute = computed(() => {
  void muteRemain.value
  return muted.value ? (notifyState.settings.mutedMinutes || -1) : 0
})
const bellTitle = computed(() => {
  if (muted.value) return `通知已静音（剩余 ${muteRemain.value} 分钟），点击查看`
  if (notifyState.unread > 0) return `${notifyState.unread} 条未读通知`
  return '通知'
})

const togglePanel = () => {
  notifyState.panelOpen = !notifyState.panelOpen
  if (notifyState.panelOpen) markAllRead()
  else showSettings.value = false
}

const refreshMuteRemain = () => { muteRemain.value = muteRemainMinutes() }

const closePanel = (e) => {
  if (!e.target.closest('.notif-wrap')) {
    notifyState.panelOpen = false
    showSettings.value = false
  }
}

const openEvent = (ev) => {
  const path = routeForType(ev.eventType)
  notifyState.panelOpen = false
  if (path) router.push(path)
}

const openToast = (t) => {
  dismissToast(t.uid)
  const path = routeForType(t.type)
  if (path) router.push(path)
}

/** 打开历史通知管理抽屉 */
const openHistory = () => {
  notifyState.panelOpen = false
  historyVisible.value = true
}

/** 抽屉里删除/清理后，同步本机铃铛列表 */
const handleHistoryChanged = (payload) => {
  removeLocalEvents(payload === 'refresh' ? 'refresh' : payload)
}

let muteWatcher = null
onMounted(() => {
  document.addEventListener('click', closePanel)
  window.addEventListener('resize', updatePanelPosition)
  muteWatcher = setInterval(() => {
    refreshMuteRemain()
    // 静音到期自动解除（settings.mutedUntil 归零由 store 看门狗负责，这里只刷新展示）
    if (notifyState.settings.mutedUntil && notifyState.settings.mutedUntil <= Date.now()) {
      notifyState.settings.mutedUntil = 0
      updateSettings({ mutedUntil: 0 })
    }
  }, 15000)
  refreshMuteRemain()
})

onUnmounted(() => {
  document.removeEventListener('click', closePanel)
  window.removeEventListener('resize', updatePanelPosition)
  if (muteWatcher) clearInterval(muteWatcher)
})
</script>

<style scoped>
.notif-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

/* ===== 铃铛 ===== */
.notif-bell {
  position: relative;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #606266;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.15s, color 0.15s;
}

.notif-bell:hover {
  background: #f2f3f5;
  color: #303133;
}

.notif-bell.is-open {
  background: #ecf5ff;
  color: #409eff;
}

.notif-bell.is-muted {
  color: #c0c4cc;
}

.notif-badge {
  position: absolute;
  top: -1px;
  right: -2px;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 8px;
  background: #f56c6c;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 15px;
  text-align: center;
  box-shadow: 0 0 0 2px #fff;
}

/* ===== 面板（teleport 到 body 的固定浮层，位置由 panelStyle 计算） ===== */
.notif-panel {
  position: fixed;
  width: 348px;
  max-height: 70vh;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 2200;
}

.np-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #f2f3f5;
}

.np-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.np-mute-tip {
  font-size: 11px;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 4px;
  padding: 1px 6px;
}

.np-gear {
  margin-left: auto;
  cursor: pointer;
  color: #909399;
  transition: color 0.15s, transform 0.3s;
}

.np-gear:hover,
.np-gear.is-active {
  color: #409eff;
  transform: rotate(45deg);
}

.np-list {
  flex: 1;
  overflow-y: auto;
  max-height: 340px;
}

.np-empty {
  padding: 32px 0;
  text-align: center;
  font-size: 12px;
  color: #909399;
}

.np-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid #fafafa;
  transition: background-color 0.15s;
}

.np-item:hover {
  background: #f7f9fc;
}

.np-item-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}

.np-item-main {
  flex: 1;
  min-width: 0;
}

.np-item-title {
  font-size: 13px;
  color: #303133;
  line-height: 1.45;
  word-break: break-all;
}

.np-item-content {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  line-height: 1.45;
  word-break: break-all;
}

.np-item-time {
  flex-shrink: 0;
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 1px;
}

.np-foot {
  display: flex;
  justify-content: space-between;
  padding: 8px 14px;
  border-top: 1px solid #f2f3f5;
  background: #fcfcfd;
}

.np-link {
  font-size: 12px;
  color: #909399;
  cursor: pointer;
}

.np-link:hover {
  color: #409eff;
}

/* 「查看全部历史」做成主入口，与两个清理动作区分开 */
.np-link-history {
  color: #409eff;
  font-weight: 600;
}

/* ===== 设置区 ===== */
.np-settings {
  flex: 1;
  overflow-y: auto;
  max-height: 380px;
  padding: 6px 14px 10px;
}

.np-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid #fafafa;
}

.np-block {
  padding: 10px 0;
  border-bottom: 1px solid #fafafa;
}

.np-block:last-child {
  border-bottom: none;
}

.np-row-label {
  font-size: 13px;
  color: #303133;
}

.np-row-desc {
  margin-top: 4px;
  font-size: 11px;
  color: #a8abb2;
  line-height: 1.4;
}

.np-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.np-chip {
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  font-size: 12px;
  line-height: 1;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.np-chip:hover {
  border-color: #409eff;
  color: #409eff;
}

.np-chip.is-active {
  background: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
  font-weight: 600;
}

.np-types {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 10px;
  margin-top: 6px;
}

.np-type {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.np-type-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.np-type-label {
  font-size: 12px;
  color: #606266;
}

/* ===== 右上角浮层 ===== */
.toast-stack {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 2400;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}

.toast-card {
  pointer-events: auto;
  display: flex;
  width: 330px;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #ebeef5;
  box-shadow: 0 10px 26px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.15s, transform 0.15s;
}

.toast-card:hover {
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.16);
  transform: translateY(-1px);
}

.toast-bar {
  width: 4px;
  background: var(--tone, #909399);
  flex-shrink: 0;
}

.toast-body {
  flex: 1;
  min-width: 0;
  padding: 10px 12px 11px;
}

.toast-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toast-tag {
  font-size: 11px;
  font-weight: 600;
  color: var(--tone, #909399);
  background: var(--tone-bg, #f4f4f5);
  border-radius: 4px;
  padding: 1px 6px;
}

.toast-time {
  font-size: 11px;
  color: #c0c4cc;
}

.toast-close {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
  cursor: pointer;
}

.toast-close:hover {
  color: #909399;
}

.toast-title {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  line-height: 1.45;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.toast-content {
  margin-top: 3px;
  font-size: 12px;
  color: #909399;
  line-height: 1.45;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ===== 动画 ===== */
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

.np-fade-enter-active,
.np-fade-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.np-fade-enter-from,
.np-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media screen and (max-width: 768px) {
  .toast-stack {
    top: 12px;
    right: 10px;
    left: 10px;
  }

  .toast-card {
    width: 100%;
  }
}
</style>
