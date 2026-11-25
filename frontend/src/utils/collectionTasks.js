export const parserOptions = [{ value: 'python-worker', label: 'Python 脚本 + 配置' }]
export const parserLabel = value => value === 'python-worker' ? 'Python 脚本 + 配置' : '旧采集方式（已下线）'
export const isEnabled = task => task.enabled === true || task.enabled === 1 || task.enabled === '1'
export const isWorker = task => task?.config?.parser === 'python-worker'
export const formatTime = value => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
export function intervalLabel(seconds) {
  if (seconds % 86400 === 0) return `${seconds / 86400} 天`
  if (seconds % 3600 === 0) return `${seconds / 3600} 小时`
  if (seconds % 60 === 0) return `${seconds / 60} 分钟`
  return `${seconds} 秒`
}
export function scheduleLabel(config) {
  return config?.schedule?.type === 'daily'
    ? `每天 ${config.schedule.time || '未配置时间'}（北京时间）`
    : `每 ${intervalLabel(config?.intervalSeconds)}`
}
function timestamp(value) {
  if (!value) return 0
  const text = String(value).replace(' ', 'T')
  return Date.parse(/[zZ]|[+-]\d\d:\d\d$/.test(text) ? text : text + '+08:00')
}
export function taskState(task, serverTime) {
  if (task.running) return { key: 'running', label: '执行中', type: 'primary', description: '本轮结束后可修改配置或启停' }
  if (!isEnabled(task)) return { key: 'paused', label: '已暂停', type: 'info', description: '不再自动执行，历史数据保留' }
  if (task.lastError) return { key: 'error', label: '上次执行失败', type: 'danger', description: task.lastError }
  if (isWorker(task) && Number(task.workerRun?.expired) === 1) return { key: 'error', label: '执行中断', type: 'warning', description: '上次执行租约已过期，等待执行器重新领取任务' }
  if (Number(task.pendingArchives) > 0) return { key: 'archive', label: '有文件待归档', type: 'warning', description: '数据已提交，归档状态仍待恢复；查看批次记录' }
  if (isWorker(task) && timestamp(task.nextScanAt) <= timestamp(serverTime)) return { key: 'waiting', label: '等待执行器', type: 'warning', description: '任务已到期，尚未开始；请确认独立执行器已启动，此状态不等同于离线' }
  if (!task.lastScanAt) return { key: 'new', label: '待首次执行', type: 'info', description: '启用后将按配置的执行计划采集' }
  return { key: 'scheduled', label: '等待下次执行', type: 'success', description: '最近一次扫描已结束；是否采到新数据以入库批次为准' }
}
export const runLabels = { CLAIMED: '执行中', SUCCEEDED: '扫描完成', FAILED: '执行失败', EXPIRED: '执行中断' }
export const attemptLabels = { IMPORTED: '入库成功', DUPLICATE: '重复跳过', FAILED: '处理失败' }
export function displayValue(value) {
  if (value === null || value === undefined) return '缺失'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
