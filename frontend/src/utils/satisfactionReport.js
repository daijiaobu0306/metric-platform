export function formatSatisfactionValue(value) {
  return value == null ? '—' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

export function formatSatisfactionScope(value) {
  return value === '倒三角三类汇总' ? '倒三角' : value
}

// Read stored report values; ratios are calculated by the collection service.
export function satisfactionFlowOverview(rows) {
  const flows = [
    { name: '客服投诉工单处理流程', scope: '在线客服' },
    { name: '倒三角工单处理流程', scope: '倒三角' },
    { name: '综调工单处理流程', scope: '综调' }
  ]
  return flows.map(flow => {
    const values = rows.filter(row => row.groupName === flow.name)
    return { ...flow, values,
      populated: values.filter(row => row.metricValue != null).length, total: values.length }
  })
}
