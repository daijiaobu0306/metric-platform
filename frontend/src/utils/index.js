export const formatDateTime = (date) => {
  if (!date) return '-'
  // 处理 MySQL datetime 格式 (如 "2026-04-21 12:00:00") 当作本地时间解析
  let d
  if (typeof date === 'string') {
    // 如果是 ISO 格式 (带 T) 或已有时区信息，正常解析
    if (date.includes('T') || date.endsWith('Z')) {
      d = new Date(date)
    } else if (date.includes('-')) {
      // MySQL datetime 格式，用空格分割
      const parts = date.split(' ')
      if (parts.length >= 2) {
        const [year, month, day] = parts[0].split('-')
        const [hours, minutes, seconds] = parts[1].split(':')
        d = new Date(parseInt(year), parseInt(month) - 1, parseInt(day),
                     parseInt(hours), parseInt(minutes), parseInt(seconds || 0))
      } else {
        d = new Date(date)
      }
    } else {
      d = new Date(date)
    }
  } else {
    d = new Date(date)
  }
  if (isNaN(d.getTime())) return '-'

  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hours = String(d.getHours()).padStart(2, '0')
  const minutes = String(d.getMinutes()).padStart(2, '0')
  const seconds = String(d.getSeconds()).padStart(2, '0')

  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 智能简化时间标签，数据多时去掉公共前缀
// labels 格式可能是: "2026-04-13 04:00" (today), "2026-04-06" (week/month), "2026-03" (year)
export const simplifyTimeLabels = (labels, dimension) => {
  if (labels.length === 0) return labels

  // year 维度不简化，保持 YYYY-MM 格式
  if (dimension === 'year') return labels

  // today 维度不简化，已经是 HH:mm 格式
  if (dimension === 'today') return labels

  // week/month 维度
  const years = new Set(labels.map(l => l.substring(0, 4)))
  const months = new Set(labels.map(l => l.substring(0, 7)))

  if (years.size > 1) return labels  // 跨年，保留完整
  if (months.size > 1) return labels.map(l => l.substring(5))  // 同年跨月，去年份
  return labels.map(l => l.substring(8))  // 同年同月，只留日期
}

/**
 * 去掉文本中数字的尾随零（对齐 BigDecimal.stripTrailingZeros().toPlainString() 行为）
 *   1.0000 → 1
 *   3.0000 → 3
 *   1.5000 → 1.5
 *   1.5500 → 1.55
 *   1.5    → 1.5（无尾随零则保留）
 * 用于工单/告警内容显示：数据库历史数据由旧版本 BigDecimal.toPlainString() 生成，存在 1.0000 这类零后缀
 */
export const stripNumberTrailingZeros = (text) => {
  if (!text) return text
  return String(text).replace(/(\d+)\.(\d+)/g, (match, intPart, decPart) => {
    const trimmed = decPart.replace(/0+$/, '')
    return trimmed ? `${intPart}.${trimmed}` : intPart
  })
}

// 获取时间范围提示文本
export const getTimeRangeHint = (dimension, data) => {
  if (!data || data.length === 0) return ''

  const formatTime = (t, showDate) => {
    if (dimension === 'today') {
      return showDate ? `${t.substring(5, 10)} ${t.substring(11, 16)}` : t.substring(11, 16)
    }
    if (dimension === 'year') return t  // YYYY-MM 完整显示
    // week/month: 跨月显示完整，否则只显示月-日
    const months = new Set(data.map(d => d.time.substring(0, 7)))
    return months.size > 1 ? t : t.substring(5)
  }

  const start = formatTime(data[0].time, dimension === 'today')
  const end = formatTime(data[data.length - 1].time, false)

  return `${start} ~ ${end}`
}