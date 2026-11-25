/**
 * 专屏「指标值与健康度」趋势序列合并。
 *
 * <p>后端 `healthResult` 的 metric 节点分别返回：
 * <ul>
 *   <li>{@code values}：observationReads.trend() 的采集点（time/value），healthScore 固定为 null；</li>
 *   <li>{@code healthTrend}：metric_health_history 的窗口记录（recordTime/healthScore）；</li>
 *   <li>{@code data}：健康度快照趋势（recordTime/healthScore），按 1d/7d/30d 聚合，作为兜底。</li>
 * </ul>
 * 两条序列的采样时间不一定一致，直接叠加会让绿线断开。这里按「分钟」对齐成
 * MetricValueHealthChart 需要的单数组：同一分钟内的指标值和健康度放进同一个点，
 * 指标值缺失保留 null（不插值），健康度优先用区间历史、快照只在没有区间历史时兜底。
 */

/** 时间统一成分钟键，兼容 "2026-11-23T10:00:00" / "2026-11-23 10:00:00" / 带毫秒等写法。 */
const minuteKey = value => String(value || '').replace('T', ' ').slice(0, 16)

const finiteScore = value => value != null && Number.isFinite(Number(value))

export function mergeMetricTrendSeries (metricValues, healthTrend, fallbackTrend) {
  const map = new Map()
  const ensure = key => {
    if (!map.has(key)) map.set(key, { time: key, value: null, healthScore: null, valueMissing: false })
    return map.get(key)
  }
  const applyHealth = (points) => {
    ;(points || []).forEach(point => {
      const key = minuteKey(point?.recordTime || point?.time || point?.snapshotTime)
      if (!key || !finiteScore(point?.healthScore)) return
      ensure(key).healthScore = Number(point.healthScore)
    })
  }
  // 健康度优先用区间历史；区间历史为空时才退回快照趋势，避免两种粒度混在同一根绿线上。
  const hasWindowHealth = (healthTrend || []).some(point =>
    finiteScore(point?.healthScore) && minuteKey(point?.recordTime || point?.time || point?.snapshotTime))
  applyHealth(hasWindowHealth ? healthTrend : fallbackTrend)
  ;(metricValues || []).forEach(point => {
    const key = minuteKey(point?.time || point?.recordTime || point?.dataTime)
    if (!key) return
    const item = ensure(key)
    const value = point?.value ?? point?.metricValue ?? point?.currentValue
    if (value != null) {
      item.value = value
      item.valueMissing = false
    } else {
      // trend() 对「缺数」也会返回 dataTime，值保持 null；标记出来让图表保留断点，
      // 而不是把只有健康度的采样分钟也当成指标值缺失。
      item.valueMissing = true
    }
    if (finiteScore(point?.healthScore)) item.healthScore = Number(point.healthScore)
  })
  return [...map.values()].sort((a, b) => a.time.localeCompare(b.time))
}