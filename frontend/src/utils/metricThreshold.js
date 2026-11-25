// Compare decimal source strings without rounding them through Number.
const decimalParts = (value) => {
  if (value == null) return null
  const match = String(value).trim().match(/^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/)
  if (!match) return null
  const fraction = match[3] ?? match[4] ?? ''
  const exponent = Number(match[5] || 0)
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 1000) return null
  const digits = (match[2] || '0') + fraction
  if (digits.length > 1000) return null
  return { coefficient: BigInt((match[1] === '-' ? '-' : '') + digits), scale: fraction.length - exponent }
}

export const compareMetricValues = (leftValue, rightValue) => {
  const left = decimalParts(leftValue)
  const right = decimalParts(rightValue)
  if (!left || !right) return left ? -1 : right ? 1 : 0
  const scale = Math.max(left.scale, right.scale)
  const a = left.coefficient * 10n ** BigInt(scale - left.scale)
  const b = right.coefficient * 10n ** BigInt(scale - right.scale)
  return a < b ? -1 : a > b ? 1 : 0
}

export const matchesMetricThreshold = (metric) => {
  if (metric.dataStatus?.includes('冲突') || metric.dataStatus?.includes('编码')) return false
  const value = decimalParts(metric.latestValue)
  const threshold = decimalParts(metric.alarmThreshold)
  if (!value || !threshold) return false
  const scale = Math.max(value.scale, threshold.scale)
  const left = value.coefficient * 10n ** BigInt(scale - value.scale)
  const right = threshold.coefficient * 10n ** BigInt(scale - threshold.scale)
  switch (metric.alarmCondition?.trim()) {
    case '>': return left > right
    case '>=': return left >= right
    case '<': return left < right
    case '<=': return left <= right
    case '=':
    case '==': return left === right
    default: return false
  }
}
