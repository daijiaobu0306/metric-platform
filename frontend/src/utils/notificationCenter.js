/**
 * 全局通知中心（V47）
 * ------------------------------------------------------------
 * 取代 V46 的 toastNotifier：
 *  - 轮询 /api/notifications/recent，维护「通知列表 + 右上角浮层」
 *  - 支持总开关、免打扰时长、按类型开关、弹出时长
 *  - 历史与设置都落 localStorage，刷新后不丢
 *  - 同一批拉到的同类型事件会合并成一条，避免告警风暴刷屏
 *
 * 后端只负责记录事件，静音/开关属于个人偏好，全部在前端完成。
 */
import { reactive } from 'vue'
import { eventStreamApi } from '../api'

const SETTINGS_KEY = 'notification_settings_v2'
const HISTORY_KEY = 'notification_history_v2'
const CURSOR_KEY = 'notification_cursor_id'

const POLL_MS = 5_000
const HISTORY_MAX = 60
const MAX_TOASTS = 3
const FETCH_LIMIT = 20

/** 四类通知的展示元信息（颜色与页面语义保持一致） */
export const NOTIFY_TYPES = [
  { key: 'DATA_COLLECTION', label: '数据汇聚', color: '#16a34a', bg: '#f0fdf4', icon: 'DataLine' },
  { key: 'ALARM', label: '告警', color: '#e6a23c', bg: '#fdf6ec', icon: 'Bell' },
  { key: 'GONGDAN', label: '工单', color: '#2563eb', bg: '#eff6ff', icon: 'Document' },
  { key: 'ISSUE', label: '问题', color: '#dc2626', bg: '#fef2f2', icon: 'Warning' },
]

const TYPE_MAP = NOTIFY_TYPES.reduce((acc, t) => { acc[t.key] = t; return acc }, {})

/** 点击通知/浮层后跳转的页面 */
const TYPE_ROUTE = {
  DATA_COLLECTION: '/data',
  ALARM: '/alarm',
  GONGDAN: '/gongdan',
  ISSUE: '/issue',
}

const DEFAULT_SETTINGS = {
  enabled: true,      // 是否弹出右上角通知
  mutedUntil: 0,      // 免打扰截止时间（毫秒时间戳），0 表示未静音
  mutedMinutes: 0,    // 当前选中的静音档位（仅用于界面高亮）
  duration: 5,        // 每条通知停留秒数
  types: { DATA_COLLECTION: true, ALARM: true, GONGDAN: true, ISSUE: true },
}

function loadSettings() {
  try {
    const raw = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null')
    if (!raw || typeof raw !== 'object') return { ...DEFAULT_SETTINGS, types: { ...DEFAULT_SETTINGS.types } }
    return {
      ...DEFAULT_SETTINGS,
      ...raw,
      types: { ...DEFAULT_SETTINGS.types, ...(raw.types || {}) },
    }
  } catch (_) {
    return { ...DEFAULT_SETTINGS, types: { ...DEFAULT_SETTINGS.types } }
  }
}

function loadHistory() {
  try {
    const raw = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch (_) {
    return []
  }
}

export const notifyState = reactive({
  events: loadHistory(),                       // 通知列表，最新在前
  unread: loadHistory().filter(e => !e.read).length,
  toasts: [],                                  // 当前右上角浮层
  settings: loadSettings(),
  panelOpen: false,
  polling: false,
})

// 浮层自动收起计时器：uid -> { timer, startedAt, remaining }
const toastTimers = new Map()
let uidSeed = 0
let timer = null

function persistSettings() {
  try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(notifyState.settings)) } catch (_) { /* ignore */ }
}

function persistHistory() {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(notifyState.events.slice(0, HISTORY_MAX))) } catch (_) { /* ignore */ }
}

/* ---------------- 轮询 ---------------- */

/** 启动轮询（幂等）。Layout 挂载时调用。 */
export function startNotificationPolling() {
  if (timer) return
  notifyState.polling = true
  poll()
  timer = setInterval(poll, POLL_MS)
}

/** 停止轮询并清空浮层（空闲超时/卸载时调用）。 */
export function stopNotificationPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  notifyState.polling = false
  clearToasts()
}

/** 清掉本地游标 —— 登录 / 会话失效时调用，避免下一个用户看到上一个人的事件。 */
export function resetNotificationCursor() {
  try { localStorage.removeItem(CURSOR_KEY) } catch (_) { /* ignore */ }
}

async function poll() {
  try {
    const cursorRaw = localStorage.getItem(CURSOR_KEY)
    // 首次进入（没有游标）只补齐历史列表，不为陈年事件弹浮层
    const firstLoad = !cursorRaw
    const sinceId = Number(cursorRaw || 0)
    const res = await eventStreamApi.fetchRecent({ sinceId, limit: FETCH_LIMIT })
    if (!res || res.code !== 200 || !Array.isArray(res.data) || res.data.length === 0) return
    const list = res.data
    try { localStorage.setItem(CURSOR_KEY, String(list[list.length - 1].id)) } catch (_) { /* ignore */ }
    ingest(list, { silent: firstLoad })
  } catch (_) {
    // 静默失败：网络抖动/未登录不打扰用户
  }
}

/* ---------------- 事件入库 ---------------- */

function normalize(ev) {
  return {
    id: ev.id,
    eventType: ev.eventType || 'DATA_COLLECTION',
    title: ev.title || '',
    content: ev.content || '',
    count: ev.relatedCount || 1,
    createdAt: ev.createdAt || '',
    read: false,
  }
}

function ingest(list, { silent = false } = {}) {
  const incoming = list.map(normalize)
  const exists = new Set(notifyState.events.map(e => e.id))
  const fresh = incoming.filter(e => !exists.has(e.id))
  if (fresh.length === 0) return

  // 最新在前
  notifyState.events = [...fresh.slice().reverse(), ...notifyState.events].slice(0, HISTORY_MAX)
  // 首次静默补历史 / 面板正开着（用户已经看得到）→ 直接算已读，徽标不涨
  const seen = silent || notifyState.panelOpen
  if (seen) fresh.forEach(e => { e.read = true })
  else notifyState.unread += fresh.length
  persistHistory()

  if (!silent) raiseToasts(fresh)
}

/** 把一批新事件变成浮层：同类型合并，最多 3 条同时展示。 */
function raiseToasts(list) {
  const groups = new Map()
  for (const ev of list) {
    if (!shouldPopup(ev.eventType)) continue
    if (!groups.has(ev.eventType)) groups.set(ev.eventType, [])
    groups.get(ev.eventType).push(ev)
  }
  for (const [type, evs] of groups) {
    const meta = TYPE_MAP[type] || { label: '通知' }
    if (evs.length === 1) {
      pushToast({
        type,
        title: evs[0].title,
        content: evs[0].content,
        time: evs[0].createdAt,
        count: 1,
      })
    } else {
      // 合并：标题给条数，正文列出最近两条
      const brief = evs.slice(-2).map(e => (e.content || e.title)).filter(Boolean).join('；')
      pushToast({
        type,
        title: `${meta.label} ${evs.length} 条新通知`,
        content: brief,
        time: evs[evs.length - 1].createdAt,
        count: evs.length,
      })
    }
  }
}

/** 当前是否应该弹浮层：总开关 + 免打扰 + 分类开关 */
export function shouldPopup(type) {
  const s = notifyState.settings
  if (!s.enabled) return false
  if (s.mutedUntil && s.mutedUntil > Date.now()) return false
  return s.types[type] !== false
}

function pushToast({ type, title, content, time, count }) {
  const meta = TYPE_MAP[type] || {}
  const uid = ++uidSeed
  const remaining = Math.max(2, Number(notifyState.settings.duration) || 5) * 1000
  const toast = {
    uid,
    type,
    label: meta.label || '通知',
    color: meta.color || '#909399',
    bg: meta.bg || '#f4f4f5',
    title,
    content,
    time,
    count,
    remaining,
  }
  notifyState.toasts.unshift(toast)
  while (notifyState.toasts.length > MAX_TOASTS) {
    const dropped = notifyState.toasts.pop()
    clearToastTimer(dropped.uid)
  }
  scheduleToast(toast)
}

function scheduleToast(toast) {
  const timerId = setTimeout(() => dismissToast(toast.uid), toast.remaining)
  toastTimers.set(toast.uid, { timer: timerId, startedAt: Date.now(), remaining: toast.remaining })
}

function clearToastTimer(uid) {
  const rec = toastTimers.get(uid)
  if (rec) {
    clearTimeout(rec.timer)
    toastTimers.delete(uid)
  }
}

/** 鼠标悬停：暂停倒计时 */
export function pauseToast(uid) {
  const rec = toastTimers.get(uid)
  if (!rec) return
  clearTimeout(rec.timer)
  rec.remaining = Math.max(800, rec.remaining - (Date.now() - rec.startedAt))
  toastTimers.set(uid, rec)
}

/** 移出鼠标：继续倒计时 */
export function resumeToast(uid) {
  const toast = notifyState.toasts.find(t => t.uid === uid)
  const rec = toastTimers.get(uid)
  if (!toast || !rec) return
  rec.startedAt = Date.now()
  rec.timer = setTimeout(() => dismissToast(uid), rec.remaining)
  toastTimers.set(uid, rec)
}

export function dismissToast(uid) {
  clearToastTimer(uid)
  const i = notifyState.toasts.findIndex(t => t.uid === uid)
  if (i >= 0) notifyState.toasts.splice(i, 1)
}

export function clearToasts() {
  notifyState.toasts.splice(0, notifyState.toasts.length)
  toastTimers.forEach(rec => clearTimeout(rec.timer))
  toastTimers.clear()
}

/* ---------------- 列表操作 ---------------- */

export function markAllRead() {
  if (notifyState.unread === 0) return
  notifyState.events.forEach(e => { e.read = true })
  notifyState.unread = 0
  persistHistory()
}

export function clearHistory() {
  notifyState.events = []
  notifyState.unread = 0
  persistHistory()
}

/**
 * 服务端删除后同步本机缓存（历史通知管理里删掉的条目，不应再出现在铃铛列表里）。
 * @param {number[]|'refresh'} ids 被删掉的 id 列表；传 'refresh' 表示整体重置缓存
 */
export function removeLocalEvents(ids) {
  if (ids === 'refresh') {
    notifyState.events = []
    notifyState.unread = 0
    persistHistory()
    return
  }
  if (!Array.isArray(ids) || ids.length === 0) return
  const set = new Set(ids)
  const before = notifyState.events.length
  notifyState.events = notifyState.events.filter(e => !set.has(e.id))
  if (notifyState.events.length !== before) {
    notifyState.unread = notifyState.events.filter(e => !e.read).length
    persistHistory()
  }
}

export function routeForType(type) {
  return TYPE_ROUTE[type] || null
}

export function typeMeta(type) {
  return TYPE_MAP[type] || { label: '通知', color: '#909399', bg: '#f4f4f5' }
}

/* ---------------- 设置 ---------------- */

export function updateSettings(patch) {
  Object.assign(notifyState.settings, patch)
  persistSettings()
  if (patch.enabled === false) clearToasts()
}

export function setTypeEnabled(type, enabled) {
  notifyState.settings.types[type] = !!enabled
  persistSettings()
}

/** 免打扰 minutes 分钟；传 0 表示取消静音 */
export function muteFor(minutes) {
  const until = minutes > 0 ? Date.now() + minutes * 60 * 1000 : 0
  notifyState.settings.mutedUntil = until
  notifyState.settings.mutedMinutes = minutes > 0 ? minutes : 0
  persistSettings()
  if (until) clearToasts()
}

/** 剩余静音分钟数（向上取整），0 表示未静音 */
export function muteRemainMinutes() {
  const until = notifyState.settings.mutedUntil || 0
  if (!until || until <= Date.now()) return 0
  return Math.ceil((until - Date.now()) / 60000)
}

/* ---------------- 时间格式化 ---------------- */

export function formatClock(iso) {
  if (!iso) return ''
  // 后端 "2026-07-03T11:42:00.213" → "11:42:00"
  const m = String(iso).match(/T(\d{2}:\d{2}:\d{2})/)
  return m ? m[1] : String(iso).slice(11, 19)
}

export function formatRelative(iso) {
  if (!iso) return ''
  const t = new Date(String(iso).replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return formatClock(iso)
  const diff = Date.now() - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + ' 小时前'
  return String(iso).slice(5, 16).replace('T', ' ')
}
