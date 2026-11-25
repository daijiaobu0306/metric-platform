import request from '../../api'
import { shareMode } from './share-mode'
const data = promise => promise.then(response => response.data)
/**
 * 分享模式（/screen/pc/s/:publicId）下，专屏页面复用同一套组件，
 * 这里把请求改走匿名只读分享接口；返回结构与平台内接口保持一致，页面代码不需要区分。
 */
const inShare = () => shareMode.active
const shareGet = (path, params) => shareMode.get(path, params)
/**
 * 自定义时间区间参数（startTime/endTime，格式 YYYY-MM-DD HH:mm:ss）。
 * 不传区间时返回空对象，请求参数与老版本完全一致（只带 days）。
 */
const rangeOf = params => (params?.startTime && params?.endTime)
  ? { startTime: params.startTime, endTime: params.endTime }
  : {}
/** 把页面里的 [start,end] 数组（el-date-picker datetimerange 的值）转成请求参数。 */
export const windowParams = range => Array.isArray(range) && range.length === 2
  ? { startTime: range[0], endTime: range[1] }
  : {}
export const screenApi = {
  capabilities: () => inShare() ? Promise.resolve({ canConfigure: false }) : data(request.get('/dashboard-view/workspace/capabilities')),
  configuration: () => data(request.get('/dashboard-view/workspace/configuration')),
  list: () => inShare() ? Promise.resolve([]) : data(request.get('/dashboard-view')),
  save: payload => data(payload.id
    ? request.put(`/dashboard-view/${payload.id}`, payload)
    : request.post('/dashboard-view', payload)),
  remove: id => data(request.delete(`/dashboard-view/${id}`)),
  setDefault: id => data(request.post(`/dashboard-view/${id}/set-default`)),
  overview: (code, params) => inShare()
    ? shareGet('/overview', { days: params?.days || 7, ...rangeOf(params) })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}`, { params })),
  metricPanel: (code, params) => inShare()
    ? shareGet('/panel', { days: params?.days || 7, panel: params?.panel || 'all', ...rangeOf(params) })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/metric-panel`, { params })),
  health: (code, params) => inShare()
    ? shareGet('/health', { node: params?.node, days: params?.days || 7, ...rangeOf(params) })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/health`, { params })),
  healthCards: (code, params) => inShare()
    ? shareGet('/health-cards', { days: params?.days || 7, ...rangeOf(params) })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/health-cards`, { params })),
  dataMeta: (code, params) => inShare()
    ? shareGet('/data-meta', { days: params?.days || 7, ...rangeOf(params) })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/data-meta`, { params })),
  dataRows: (code, params) => inShare()
    ? shareGet('/data-rows', {
        category: params?.category,
        subCategory: params?.subCategory,
        page: params?.page ?? 0,
        size: params?.size ?? 10,
        ...rangeOf(params)
      })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/data-rows`, { params })),
  eventWorkspace: (code, params) => inShare()
    ? shareGet('/event-workspace', {
        kind: params?.kind,
        days: params?.days || 7,
        page: params?.page ?? 0,
        size: params?.size ?? 10,
        systemId: params?.systemId,
        categoryId: params?.categoryId,
        metricId: params?.metricId,
        status: params?.status,
        type: params?.type,
        keyword: params?.keyword,
        ...rangeOf(params)
      })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/event-workspace`, { params })),
  events: (code, params) => inShare()
    ? shareGet('/event-rows', { kind: params?.kind, page: params?.page ?? 0, days: params?.days || 7, ...rangeOf(params) })
    : data(request.get(`/dashboard-view/workspace/${encodeURIComponent(code)}/events`, { params }))
}
export const shareApi = {
  create: payload => data(request.post('/dashboard-share', payload)),
  list: viewId => data(request.get('/dashboard-share', { params: { viewId } })),
  revoke: id => data(request.post(`/dashboard-share/${id}/revoke`)),
  statistics: (start, end) => data(request.get('/dashboard-share/statistics', { params: { start, end } })),
  fileConfig: () => data(request.get('/dashboard-share/file-config')),
  saveFileConfig: payload => data(request.put('/dashboard-share/file-config', payload)),
  fileSync: () => data(request.post('/dashboard-share/file-sync'))
}
export const templates = { supervisor: '业务主管', leader: '领导', operator: '平台运营', manager: '科室经理' }
export const moduleNames = { health: '指标与健康度', data: '数据汇聚情况', events: '告警、问题、工单', report: '范围运营摘要', team: '团队使用情况', operations: '平台运行与使用' }
export const defaultModules = template => template === 'operator' ? ['data', 'events', 'operations'] : template === 'manager' ? ['health', 'data', 'events', 'team'] : ['health', 'data', 'events']
export const message = error => error.response?.data?.message || error.message || '加载失败，请重试'
