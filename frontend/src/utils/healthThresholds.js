import { reactive } from 'vue'
import { healthRuleApi } from '@/api'

/**
 * 健康度分档阈值（前后端统一口径）
 *
 * 改造前前端 8 个组件各自写死 70/90，与后端判定线（60/80）不一致，
 * 会出现“页面显示正常、后端判异常”的矛盾。现在只从这里取，值来自
 * system_config（metric.health.alarm-threshold / warning-threshold）。
 */
export const healthThresholds = reactive({
  alarm: 60,
  warning: 80,
  loaded: false,
  loading: false
})

/** 拉一次配置；失败时保留默认值，不阻塞页面 */
export async function ensureHealthThresholds () {
  if (healthThresholds.loaded || healthThresholds.loading) return healthThresholds
  // 匿名只读分享页（/screen/s/、/screen/pc/s/）没有平台登录态：
  // 这里直接使用默认 60/80 分档，避免配置接口 401 被全局拦截器当成会话失效、把访客踢去登录页。
  if (!sessionStorage.getItem('sessionKey')) return healthThresholds
  healthThresholds.loading = true
  try {
    const res = await healthRuleApi.config()
    const alarm = Number(res?.data?.alarmThreshold)
    const warning = Number(res?.data?.warningThreshold)
    if (Number.isFinite(alarm) && alarm > 0) healthThresholds.alarm = alarm
    if (Number.isFinite(warning) && warning > 0) healthThresholds.warning = warning
    healthThresholds.loaded = true
  } catch (e) {
    // 配置接口异常时用默认 60/80，避免把健康度展示整体打挂
  } finally {
    healthThresholds.loading = false
  }
  return healthThresholds
}

/** 分数 → 语义等级：unknown/danger/warning/normal */
export function scoreLevel (score) {
  if (score === null || score === undefined || score === '') return 'unknown'
  const value = Number(score)
  if (!Number.isFinite(value)) return 'unknown'
  if (value < healthThresholds.alarm) return 'danger'
  if (value < healthThresholds.warning) return 'warning'
  return 'normal'
}

/** 分数 → 展示样式类（score-excellent / score-good / score-poor / score-unknown） */
export function scoreTone (score) {
  const level = scoreLevel(score)
  return level === 'danger' ? 'score-poor' : level === 'warning' ? 'score-good' : level === 'unknown' ? 'score-unknown' : 'score-excellent'
}

export default healthThresholds
