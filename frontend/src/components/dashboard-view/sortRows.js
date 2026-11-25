/**
 * 专屏各视图「数据来源」表格的展示排序。
 *
 * <p>只调整已经取到前端的那一批行的顺序，不重新请求后端、不改变过滤和统计口径；
 * 默认倒序（最近数据在前），没有时间值的行固定放末尾，避免空值参与比较后顺序来回跳。
 * 时间列在不同来源里字段名不一致，按传入的 candidates 依次探测，全部取不到时退回 id。
 */
export function sortRowsByTime (rows, order = 'desc', candidates = ['latestDataTime', 'latestCalculationTime', 'dataTime', 'recordTime']) {
  const list = Array.isArray(rows) ? rows : []
  if (!list.length) return []
  const sign = order === 'asc' ? 1 : -1
  const timeOf = row => {
    for (const key of candidates) {
      const value = row?.[key]
      if (value != null && value !== '') return String(value).replace('T', ' ')
    }
    return ''
  }
  const idOf = row => Number(row?.metricId ?? row?.id ?? 0)
  const filled = list.filter(row => timeOf(row))
  const empty = list.filter(row => !timeOf(row))
  filled.sort((a, b) => {
    const at = timeOf(a)
    const bt = timeOf(b)
    if (at === bt) return sign * (idOf(a) - idOf(b))
    return sign * at.localeCompare(bt)
  })
  // 没有可用时间列时（很少见），退回按 id 排序，保证按钮仍有反馈
  if (!filled.length) {
    return [...list].sort((a, b) => sign * (idOf(a) - idOf(b)))
  }
  return filled.concat(empty)
}