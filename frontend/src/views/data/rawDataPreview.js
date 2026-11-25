// Fixtures for opt-in local layout previews. No files are sent to the collection worker.
const examples = {
  对内部人员: [
    ['查询类', '页面加载时长', ['业务名称', '步骤类型', '步骤名称', '访问量', '平均时长(秒)'],
      ['客户资料查询', '页面加载', '进入页面', 0, 1.25]],
    ['查询类', '查询时长', ['业务名称', '步骤名称', '访问量', '平均时长(秒)'],
      ['账户查询', '提交查询', 120, 0.86]],
    ['办理类-单业务', '步骤响应时长', ['业务名称', '步骤名称', '步骤响应耗时(秒)'],
      ['套餐变更', '确认办理', 2.14]],
    ['办理类-融合业务', '办理按钮数', ['业务名称', '办理按钮总数(步骤数)'], ['融合套餐办理', 5]]
  ],
  运行效率: [
    ['系统性能', '接口平均响应时长', ['应用名称', '接口名称', '访问量', '平均响应时长(ms)'],
      ['样例应用', '/example/query', 126, 180]],
    ['系统异常', '接口异常次数', ['应用名称', '异常名称', '发生次数'],
      ['样例应用', '样例超时', 2]]
  ]
}

export function sampleData(endpoint, { category, metricId, date, keyword, page = 1, size = 50 }) {
  const entries = examples[category] || []
  if (endpoint === 'catalog') return entries.map(([subcategory, name], index) => ({
    id: index + 1, name, subcategory
  }))
  const entry = entries[metricId - 1]
  const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
  if (!entry || (date && date !== day)) return { columns: [], rows: [], total: 0 }
  const [, , columns, values] = entry
  const rows = Array.from({ length: 8 }, (_, index) => Object.fromEntries(
    columns.map((column, i) => [column, i === 0 ? values[i] + ' ' + (index + 1) : values[i]])
  )).filter(row => !keyword || Object.values(row).some(value => String(value).toLowerCase().includes(keyword.toLowerCase())))
  return {
    columns, rows: rows.slice((page - 1) * size, page * size), total: rows.length,
    dataTime: day + 'T00:00:00', collectedAt: day + 'T06:00:00'
  }
}
