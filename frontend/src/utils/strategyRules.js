export const ruleLevels = [
  { value: 2, label: '一般', type: 'info' },
  { value: 3, label: '重要', type: 'warning' },
  { value: 4, label: '严重', type: 'danger' }
]

export function rulesOf (strategy, kind) {
  if (typeof strategy?.[`${kind}Rules`] === 'string') {
    try { return JSON.parse(strategy[`${kind}Rules`]) } catch { return [] }
  }
  if (Array.isArray(strategy?.[`${kind}Rules`])) return strategy[`${kind}Rules`].map(rule => ({ ...rule }))
  if (strategy?.[`${kind}Threshold`] == null) return []
  return [{ level: 2, condition: strategy[`${kind}Condition`] ?? null,
    threshold: strategy[`${kind}Threshold`], consecutiveTimes: strategy[`${kind}ConsecutiveTimes`] ?? 1 }]
}

export function ruleSummary (strategy, kind) {
  return rulesOf(strategy, kind).map(rule => `${ruleLevels.find(level => level.value === rule.level)?.label || '未定级'}：${rule.condition || '条件未配置'} ${rule.threshold ?? '未配置'}${strategy.strategyType === 'raw' ? ` ms · 连续 ${rule.consecutiveTimes || 1} 条新记录异常` : ` · 连续 ${rule.consecutiveTimes} 次有效更新异常`}`).join('；') || '未配置'
}
