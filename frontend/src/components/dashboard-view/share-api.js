import axios from 'axios'

// 分享访问不携带平台 X-Session-Key，也不触发全局 401 跳登录逻辑。
const shareRequest = axios.create({ baseURL: '/metric-code/api', timeout: 20000 })
const shareData = promise => promise.then(response => {
  if (response.data?.code !== 200) return Promise.reject(new Error(response.data?.message || '请求失败'))
  return response.data.data
})
/**
 * 自定义时间区间参数：只有开始、结束都传时才附加上去。
 * 老分享链接（只带 days）的请求参数保持原样，后端按 days 走原有逻辑。
 */
const windowOf = range => (range?.startTime && range?.endTime)
  ? { startTime: range.startTime, endTime: range.endTime }
  : {}
export const publicShareApi = {
  open: (publicId, secret) => shareData(shareRequest.post(`/dashboard-share/s/${encodeURIComponent(publicId)}/session`, null, { headers: { 'X-Share-Secret': secret } })),
  panel: (publicId, token, days, panel, range) => shareData(shareRequest.get(`/dashboard-share/s/${encodeURIComponent(publicId)}/panel`, { timeout: ['health', 'data', 'sources', 'operations'].includes(panel) ? 45000 : 20000, params: { days, panel, ...windowOf(range) }, headers: { 'X-Share-Session': token } })),
  metricDetail: (publicId, token, metricId, days, range) => shareData(shareRequest.get(`/dashboard-share/s/${encodeURIComponent(publicId)}/metrics/${encodeURIComponent(metricId)}`, { params: { days, ...windowOf(range) }, headers: { 'X-Share-Session': token } })),
  eventRows: (publicId, token, kind, page, days, range) => shareData(shareRequest.get(`/dashboard-share/s/${encodeURIComponent(publicId)}/event-rows`, { params: { kind, page, days, ...windowOf(range) }, headers: { 'X-Share-Session': token } })),
  event: (publicId, token, payload) => shareData(shareRequest.post(`/dashboard-share/s/${encodeURIComponent(publicId)}/events`, payload, { headers: { 'X-Share-Session': token } }))
}
