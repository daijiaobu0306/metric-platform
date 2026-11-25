import axios from 'axios'
import { ElMessage } from 'element-plus'
import { resetNotificationCursor } from '../utils/notificationCenter'

const request = axios.create({
  baseURL: '/metric-code/api',
  timeout: 20000
})

// 请求拦截器 — P2/V42: 注入 X-Session-Key header 给后端 SessionKeyAuthenticationFilter
request.interceptors.request.use(
  config => {
    const sessionKey = sessionStorage.getItem('sessionKey')
    if (sessionKey) {
      config.headers['X-Session-Key'] = sessionKey
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器 — P2/V42: 401 统一清 sessionStorage + 跳 /login
request.interceptors.response.use(
  response => {
    // blob响应直接返回，避免拦截器检查code
    if (response.config.responseType === 'blob') {
      return response
    }
    const res = response.data
    if (res.code !== 200) {
      if (!(response.config.silentNotFound && res.message === 'Config not found')) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  error => {
    if (error.response && error.response.status === 401) {
      // 后端 SessionKeyAuthenticationEntryPoint 返的 JSON 未登录
      // 同步 /sso/callback 等白名单不要触发 401 跳登录（它们没带 X-Session-Key）
      const rawUrl = error.config && error.config.url ? error.config.url : ''
      // config.url 是相对 baseURL 的（/session/init），而白名单写的是完整路径（/api/session/...）。
      // 不补前缀的话任何 401 都会被当成「会话失效」踢回登录页，SSO 回调会被反复打断。
      const url = rawUrl.startsWith('/api/') ? rawUrl : '/api' + rawUrl
      const isPublicPath = url.startsWith('/api/session/login')
        || url.startsWith('/api/session/config')
        || url.startsWith('/api/session/init')
        || url.startsWith('/api/session/heartbeat')
        || url.startsWith('/api/session/end')
        || url.startsWith('/api/sso/login-url')
        || url.startsWith('/api/sso/silent-entry')
        || url.startsWith('/api/sso/availability')
        || url.startsWith('/api/sso/post-login')
        || url.startsWith('/api/sso/me')
        || url.startsWith('/api/sso/front-logout')
        // 免登录专屏由独立分享密钥与短期访客会话鉴权；失效时由分享页显示统一提示，
        // 不能把匿名访问者重定向到统一认证。
        || url.startsWith('/dashboard-share/s/')
        || url.startsWith('/api/dashboard-share/s/')
        || url.startsWith('/api/sso/access-link')
      if (!isPublicPath) {
        // 本平台会话失效时先留在本地登录页，不要再次触发 silent SSO。
        // 用户主动点击“使用统一认证登录”时仍可正常进入 SSO。
        sessionStorage.setItem('silentSsoTried', '1')
        sessionStorage.removeItem('sessionKey')
        sessionStorage.removeItem('displayName')
        sessionStorage.removeItem('username')
        sessionStorage.removeItem('idleTimeout')
        sessionStorage.removeItem('isAdmin')
        sessionStorage.removeItem('permissions')
        // 401 也清通知游标，避免下一用户复用旧游标
        resetNotificationCursor()
        // 避免在 /login / /sso/callback 等页面递归
        const path = window.location.pathname
        if (!path.startsWith('/metric-code/login') && !path.startsWith('/metric-code/sso/')
            && !path.startsWith('/metric-code/session-expired')) {
          const target = path.startsWith('/metric-code/')
            ? path.slice('/metric-code'.length) + window.location.search
            : '/'
          window.location.href = '/metric-code/login?nosso=1&redirect=' + encodeURIComponent(target)
        }
      }
    }
    if (!error.config?.silentError) ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default request

// SSO 管理 API（P4）
export const ssoAdminApi = {
  syncLogs: () => request.get('/admin/sso/sync/logs'),
  triggerSync: () => request.post('/admin/sso/sync')
}

// 系统管理 API
export const systemApi = {
  list: (params) => request.get('/system', { params }),
  listAll: () => request.get('/system/list'),
  get: (id) => request.get(`/system/${id}`),
  create: (data) => request.post('/system', data),
  update: (id, data) => request.put(`/system/${id}`, data),
  delete: (id) => request.delete(`/system/${id}`)
}

// 指标管理 API
export const metricApi = {
  categoryList: (params) => request.get('/metric/category', { params }),
  categoryListAll: () => request.get('/metric/category/list'),
  categoryBySystem: (systemId) => request.get(`/metric/category/system/${systemId}`),
  categoryGet: (id) => request.get(`/metric/category/${id}`),
  categoryCreate: (data) => request.post('/metric/category', data),
  categoryUpdate: (id, data) => request.put(`/metric/category/${id}`, data),
  categoryDelete: (id) => request.delete(`/metric/category/${id}`),

  itemList: (params) => request.get('/metric/item', { params }),
  itemListAll: () => request.get('/metric/item/list'),
  itemByCategory: (categoryId) => request.get(`/metric/item/category/${categoryId}`),
  itemGet: (id) => request.get(`/metric/item/${id}`),
  itemCreate: (data) => request.post('/metric/item', data),
  itemUpdate: (id, data) => request.put(`/metric/item/${id}`, data),
  itemDelete: (id) => request.delete(`/metric/item/${id}`),
  // 批量启用/停用：{ ids: [], status: 0|1 }
  itemBatchStatus: (data) => request.put('/metric/item/batch-status', data),
  // 导入两阶段（2026-10-28）：先预演算差异，确认后再执行
  importPreview: (formData, params) => request.post('/metric/import/preview', formData, { params }),
  importExecute: (data) => request.post('/metric/import/execute', data),

  statistics: () => request.get('/metric/statistics'),
  // 岗位（面向人员）字典：指标配置下拉、运营观测人员视角、专屏岗位过滤共用
  audienceList: () => request.get('/metric/audience/list'),
  // 岗位字典维护（2026-11-18）：全部岗位含停用 + 引用数 + 未登记编码
  audienceManage: () => request.get('/metric/audience/manage'),
  audienceCreate: (data) => request.post('/metric/audience/item', data),
  audienceUpdate: (id, data) => request.put(`/metric/audience/item/${id}`, data),
  audienceDelete: (id) => request.delete(`/metric/audience/item/${id}`),
  syncAuditMetrics: () => request.post('/metric/audit/sync'),
  getAuditLogs: (params) => request.get('/metric/audit/logs', { params }),
  batchDeleteAuditLogs: (ids) => request.post('/metric/audit/batch-delete', ids),
}

// 告警管理 API
export const alarmApi = {
  summary: (params) => request.get('/alarm/summary', { params }),
  observationStatus: (strategyId) => request.get('/alarm/observation-status', { params: { strategyId } }),
  history: (params) => request.get('/alarm/summary/history', { params }),
  groupAction: (data) => request.post('/alarm/summary/action', data, { timeout: 120000 }),
  conversionPreview: (data) => request.post('/alarm/conversion/preview', data),
  convert: (data) => request.post('/alarm/conversion', data, { timeout: 120000 }),
  list: (params) => request.get('/alarm', { params }),
  get: (id) => request.get(`/alarm/${id}`),
  create: (data) => request.post('/alarm', data),
  update: (id, data) => request.put(`/alarm/${id}`, data),
  delete: (id) => request.delete(`/alarm/${id}`),
  process: (id, data) => request.put(`/alarm/${id}/process`, null, { params: data }),
  shield: (id, data) => request.put(`/alarm/${id}/shield`, null, { params: data }),
  updateTagStatus: (id, data) => request.put(`/alarm/${id}/tag`, null, { params: data }),
  statistics: () => request.get('/alarm/statistics'),
  getTrend: (params) => request.get('/alarm/trend', { params }),
  getAuditLogs: (params) => request.get('/alarm-audit/logs', { params }),
  batchDeleteAuditLogs: (ids) => request.post('/alarm-audit/batch-delete', ids),
  getScanErrors: () => request.get('/alarm/scan-errors'),
}

// 屏蔽规则 API
export const shieldRuleApi = {
  list: () => request.get('/shield-rule'),
  get: (id) => request.get(`/shield-rule/${id}`),
  create: (data) => request.post('/shield-rule', data),
  update: (id, data) => request.put(`/shield-rule/${id}`, data),
  delete: (id) => request.delete(`/shield-rule/${id}`)
}

// 工单管理 API
export const gongdanApi = {
  list: (params) => request.get('/gongdan', { params }),
  get: (id) => request.get(`/gongdan/${id}`),
  create: (data) => request.post('/gongdan', data),
  update: (id, data) => request.put(`/gongdan/${id}`, data),
  delete: (id) => request.delete(`/gongdan/${id}`),
  close: (id) => request.put(`/gongdan/${id}/close`),
  statistics: () => request.get('/gongdan/statistics'),
  getTrend: (params) => request.get('/gongdan/trend', { params })
}

// 问题管理 API
export const issueApi = {
  list: (params) => request.get('/issue', { params }),
  get: (id) => request.get(`/issue/${id}`),
  create: (data) => request.post('/issue', data),
  update: (id, data) => request.put(`/issue/${id}`, data),
  delete: (id) => request.delete(`/issue/${id}`),
  resolve: (id, resolveStatus) => request.put(`/issue/${id}/resolve?resolveStatus=${resolveStatus}`),
  statistics: () => request.get('/issue/statistics'),
  getTrend: (params) => request.get('/issue/trend', { params }),
  // 发现者统计看板：按发现者聚合 Top N（问题数/核查原因有/附件有）
  discovererStats: (params) => request.get('/issue/discoverer-stats', { params }),
  generateOverdueAlerts: () => request.post('/issue/generate-overdue-alerts'),
  // 系统名输入建议：基于 issue 表历史 system_name distinct（与指标系统表解耦）
  suggestSystems: (keyword) => request.get('/issue/system-suggestions', { params: { keyword } }),
  // 指标大类输入建议：基于 issue 表历史 category_name distinct（与指标大类表解耦）
  suggestCategories: (keyword) => request.get('/issue/category-suggestions', { params: { keyword } }),
  // 指标小类输入建议：基于 issue 表历史 sub_category_name distinct（与指标小类表解耦）
  suggestSubCategories: (keyword) => request.get('/issue/sub-category-suggestions', { params: { keyword } }),
  uploadAttachment: (id, file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post(`/issue/${id}/attachment`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  deleteAttachment: (id) => request.delete(`/issue/${id}/attachment`),
  // 导出Excel
  exportExcel: (params) => request.get('/issue/export', { params, responseType: 'blob' }),
  // 下载导入模板
  downloadTemplate: () => request.get('/issue/template', { responseType: 'blob' }),
  // 导入Excel
  importExcel: (file, overwrite = true) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('overwrite', overwrite)
    return request.post('/issue/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

// 数据汇聚 API（明细查询涉及原始大表，单独放宽超时）
export const dataApi = {
  getCategories: () => request.get('/data/categories'),
  getSubcategories: (params) => request.get('/data/subcategories', { params }),
  getDetail: (params) => request.get('/data/detail', { params, timeout: 60000 }),
  getAttributes: (subCategoryName, tableName, stepType) => {
    const params = { tableName }
    if (stepType) params.stepType = stepType
    return request.get(`/data/attributes/${encodeURIComponent(subCategoryName)}`, { params, timeout: 60000 })
  },
  drilldown: (params) => request.get('/data/drilldown', { params, timeout: 60000 }),
}

// 实时指标 API（读取采集观测值，数据量大时放宽超时）
export const realtimeApi = {
  // 获取实时指标数据
  getMetrics: (params) => request.get('/realtime/metrics', { params, timeout: 60000 }),
  // 获取指标配置列表
  getMetricConfigs: (params) => request.get('/realtime/metric-configs', { params }),
  // 获取所有监控指标（系统运行用）
  getMonitorMetrics: (params) => request.get('/realtime/monitor/metrics', { params, timeout: 60000 }),
  // 获取业务视角的实时指标（对外部客户/对内部人员/计划执行用）
  getBusinessMetrics: (params) => request.get('/realtime/business/metrics', { params, timeout: 60000 }),
  // 获取单个监控指标
  getMonitorMetric: (metricId) => request.get(`/realtime/monitor/metrics/${metricId}`),
  // 获取监控配置
  getMonitorConfig: (metricId) => request.get(`/realtime/monitor/config/${metricId}`),
  // 更新监控配置
  updateMonitorConfig: (metricId, data) => request.put(`/realtime/monitor/config/${metricId}`, data),
  // 阈值模拟
  simulateThreshold: (params) => request.post('/realtime/monitor/simulate', null, { params }),
  // 手动触发扫描
  triggerScan: () => request.post('/realtime/monitor/scan'),
}

// 指标检索 API（运营报表新增区：按指标+数据时间查采集批次明细）
export const metricSearchApi = {
  catalog: () => request.get('/report/metric-catalog'),
  search: (params) => request.get('/report/metric-search', { params, timeout: 60000 })
}

// 环比分析 API（运营报表：大类健康度/小类健康度/指标值 两时段多选对比）
export const periodComparisonApi = {
  compare: (data) => request.post('/report/period-comparison', data, { timeout: 60000 }),
  // 进页默认预选的指标：最近 N 天有采集记录、按大类封顶
  activeMetrics: (params) => request.get('/report/period-comparison/active-metrics', { params })
}

// 运营观测 API
export const observationApi = {
  // 获取指标大类健康度趋势
  getCategoryHealthTrend: (params) => request.get('/observation/category-health-trend', { params }),
  // 获取指标小类健康度趋势
  getSubcategoryHealthTrend: (params) => request.get('/observation/subcategory-health-trend', { params }),
  // 获取具体指标的指标值趋势
  getMetricValueTrend: (params) => request.get('/observation/metric-value-trend', { params }),
  // 获取具体指标的健康度趋势
  getMetricHealthTrend: (params) => request.get('/observation/metric-health-trend', { params }),
  // 获取指标小类的指标列表
  getSubcategoryMetrics: (params) => request.get('/observation/subcategory-metrics', { params }),
  // 获取系统健康度（按大类权重加权计算）
  getSystemHealthScore: (params) => request.get('/observation/system-health-score', { params }),
  // 获取系统健康度历史趋势
  getSystemHealthTrend: (params) => request.get('/observation/system-health-trend', { params }),
  // 获取指标健康度明细（5分钟粒度下钻）
  getMetricHealthDetail: (params) => request.get('/observation/metric-health-detail', { params }),
  // 系统健康度汇总（公式/大类分数/权重/覆盖率/最差指标，页面悬停依据直接读它）
  getSystemHealthSummary: (params) => request.get('/observation/system-health-summary', { params }),
  // 系统列表（页面系统切换）
  getSystems: () => request.get('/observation/systems'),
  // 指标健康度趋势上的规则断点（规则版本变化位置）
  getMetricRuleBreakpoints: (params) => request.get('/observation/metric-rule-breakpoints', { params }),
}

// 健康度规则配置 API（P1：规则表 + 白名单 + 基线 + 试算 + 审计）
export const healthRuleApi = {
  tree: (systemId) => request.get('/metric/health-rules/tree', { params: systemId ? { systemId } : {} }),
  suggest: (metricId) => request.get('/metric/health-rules/suggest', { params: { metricId } }),
  preview: (data) => request.post('/metric/health-rules/preview', data),
  batch: (data) => request.put('/metric/health-rules/batch', data, { timeout: 120000 }),
  history: (params) => request.get('/metric/health-rules/history', { params }),
  compare: () => request.get('/metric/health-rules/compare'),
  config: () => request.get('/metric/health-rules/config'),
  recalculate: () => request.post('/metric/health-rules/recalculate', null, { timeout: 120000 }),
  metricTrace: (metricId) => request.get('/metric/health-rules/metric-trace', { params: { metricId } }),
}

// 配置管理 API
export const configApi = {
  systemList: () => request.get('/config/system'),
  systemByType: (type) => request.get(`/config/system/type/${type}`),
  systemByKey: (key) => request.get(`/config/system/key/${encodeURIComponent(key)}`, { silentNotFound: true }),
  systemGet: (id) => request.get(`/config/system/${id}`),
  systemCreate: (data) => request.post('/config/system', data),
  systemUpdate: (id, data) => request.put(`/config/system/${id}`, data),
  systemDelete: (id) => request.delete(`/config/system/${id}`),

  getSystemInfo: () => request.get('/config/system-info'),
  saveSystemInfo: (data) => request.post('/config/system-info', data),

  strategyExcelExport: () => request.get('/alarm/strategy-excel/export', { responseType: 'blob', timeout: 120000 }),
  strategyExcelPreview: (data) => request.post('/alarm/strategy-excel/preview', data, { timeout: 120000 }),
  strategyExcelExecute: (data) => request.post('/alarm/strategy-excel/execute', data, { timeout: 120000 }),
  strategyList: () => request.get('/config/strategy'),
  strategyFilter: (params) => request.get('/config/strategy/filter', { params }),
  strategyBatchStatus: (data) => request.post('/config/strategy/batch-status', data),
  strategyByType: (type) => request.get(`/config/strategy/type/${type}`),
  strategyByTarget: (params) => request.get('/config/strategy/target', { params }),
  strategyGet: (id) => request.get(`/config/strategy/${id}`),
  strategyCreate: (data) => request.post('/config/strategy', data),
  strategyUpdate: (id, data) => request.put(`/config/strategy/${id}`, data),
  strategyDelete: (id) => request.delete(`/config/strategy/${id}`),

  // 注：原「策略同步」接口（/strategy-sync/*）已在 2026-10-28 指标与告警解耦时删除，
  // 告警策略是阈值唯一真值，不再需要从指标配置同步。

  // 原始数据告警配置 API
  rawStrategySources: () => request.get('/raw-data-alarm/sources'),
  rawStrategyDefinition: (id) => request.get(`/raw-data-alarm/strategy/${id}`),
  rawStrategySave: (data) => request.post('/raw-data-alarm/strategy', data),
  rawStrategyMatch: (data) => request.post('/raw-data-alarm/match', data),
  rawDataAlarmList: () => request.get('/raw-data-alarm'),
  rawDataAlarmGet: (id) => request.get(`/raw-data-alarm/${id}`),
  rawDataAlarmCreate: (data) => request.post('/raw-data-alarm', data),
  rawDataAlarmUpdate: (id, data) => request.put(`/raw-data-alarm/${id}`, data),
  rawDataAlarmDelete: (id) => request.delete(`/raw-data-alarm/${id}`),
  rawDataAlarmToggle: (id) => request.post(`/raw-data-alarm/${id}/toggle`)
}

// 指标小类配置 API
export const subcategoryApi = {
  list: (categoryId) => request.get('/metric/subcategory/list', { params: { categoryId } }),
  get: (id) => request.get(`/metric/subcategory/${id}`),
  create: (data) => request.post('/metric/subcategory', data),
  update: (id, data) => request.put(`/metric/subcategory/${id}`, data),
  delete: (id) => request.delete(`/metric/subcategory/${id}`),
  getCodes: (categoryId) => request.get('/metric/subcategory/codes', { params: { categoryId } }),
}

// 权重配置 API
export const weightApi = {
  getConfig: (systemId) => request.get(`/weight/config/${systemId}`),
  updateCategoryWeights: (systemId, weights) => request.put('/weight/category', weights, { params: { systemId } }),
  updateSubcategoryWeights: (categoryId, weights) => request.put('/weight/subcategory', weights, { params: { categoryId } }),
  updateMetricWeights: (categoryId, weights) => request.put('/weight/metric', weights, { params: { categoryId } }),
  exportConfig: (systemId) => {
    return request.get(`/weight/export/${systemId}`, { responseType: 'blob' })
  },
}

// 系统异常-关键词配置 API
export const errorKeywordApi = {
  list: (metricId) => request.get('/error/keyword/list', { params: { metricId } }),
  get: (id) => request.get(`/error/keyword/${id}`),
  create: (data) => request.post('/error/keyword', data),
  update: (id, data) => request.put(`/error/keyword/${id}`, data),
  delete: (id) => request.delete(`/error/keyword/${id}`),
  deleteByMetric: (metricId) => request.delete(`/error/keyword/metric/${metricId}`),
}

// 系统异常-导入配置 API
export const errorImportApi = {
  list: () => request.get('/error/import/config/list'),
  get: (id) => request.get(`/error/import/config/${id}`),
  getBySubCategory: (subCategoryCode) => request.get(`/error/import/config/subcategory/${subCategoryCode}`),
  create: (data) => request.post('/error/import/config', data),
  update: (id, data) => request.put(`/error/import/config/${id}`, data),
  delete: (id) => request.delete(`/error/import/config/${id}`),
  triggerScan: (subCategoryCode) => request.post(`/error/import/scan/${subCategoryCode}`),
  scanHealth: () => request.get('/error/scan-health'),
}

// 系统异常-下钻明细 API
export const errorDrilldownApi = {
  getDrilldown: (metricId, threshold) => request.get(`/error/drilldown/${metricId}`, { params: { threshold } }),
}

// 平台配置 API
export const platformConfigApi = {
  getAll: () => request.get('/platform/config'),
  get: (key) => request.get(`/platform/config/${key}`),
  save: (data) => request.post('/platform/config', data),
  uploadLogo: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/platform/logo/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  getLogoHistory: () => request.get('/platform/logo/history'),
  deleteLogo: (logoPath) => request.delete('/platform/logo', { params: { logoPath } }),
}

// 原始数据导入日志 API
export const originalDataImportApi = {
  getLogs: (params) => request.get('/original-data-import/logs', { params }),
  getTodayLogs: (params) => request.get('/original-data-import/today-logs', { params }),
  getStatistics: () => request.get('/original-data-import/statistics'),
  batchDelete: (ids) => request.post('/original-data-import/batch-delete', ids),
}

// 插件配置 API
export const pluginConfigApi = {
  list: (params) => request.get('/plugin-config', { params }),
  get: (id) => request.get(`/plugin-config/${id}`),
  create: (data) => request.post('/plugin-config', data),
  update: (id, data) => request.put(`/plugin-config/${id}`, data),
  delete: (id) => request.delete(`/plugin-config/${id}`),
  toggle: (id) => request.put(`/plugin-config/${id}/toggle`),
  debug: (id, testParams) => request.post(`/plugin-config/${id}/debug`, testParams || {}),
  getLogs: (id, params) => request.get(`/plugin-config/${id}/logs`, { params }),
  downloadTemplate: () => {
    return request.get('/plugin-config/template', { responseType: 'blob' })
  },
  importExcel: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/plugin-config/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
}

// 插件动态认证 API
export const pluginAuthApi = {
  list: () => request.get('/plugin-auth/list'),
  get: (id) => request.get(`/plugin-auth/${id}`),
  create: (data) => request.post('/plugin-auth', data),
  update: (id, data) => request.put(`/plugin-auth/${id}`, data),
  delete: (id) => request.delete(`/plugin-auth/${id}`),
  test: (id) => request.post(`/plugin-auth/${id}/test`),
  status: (id) => request.get(`/plugin-auth/${id}/status`),
  clearCache: (id) => request.post(`/plugin-auth/${id}/clear-cache`)
}

// 工单集成 API
export const gongdanIntegrationApi = {
  getPlugins: () => request.get('/gongdan-integration/plugins'),
  syncToExternal: (gongdanId, pluginId) => request.post('/gongdan-integration/sync/' + gongdanId, { pluginId }),
  closeInExternal: (gongdanId, pluginId) => request.post('/gongdan-integration/close/' + gongdanId, { pluginId }),
  syncPending: () => request.post('/gongdan-integration/sync-pending'),
  getBomcConfig: () => request.get('/gongdan-integration/bomc-config'),
  updateBomcConfig: (data) => request.put('/gongdan-integration/bomc-config', data),
  push: (gongdanId, force = false) => request.post(`/gongdan-integration/push/${gongdanId}`, null, { params: { force } }),
  trace: (gongdanId) => request.get(`/gongdan-integration/trace/${gongdanId}`),
  pushPending: () => request.post('/gongdan-integration/push-pending'),
  // 归档工单（archiveTask，reason → opin，必填）
  archive: (gongdanId, reason) => request.post(`/gongdan-integration/archive/${gongdanId}`, { reason }),
  // 撤销工单（quashTask，reason → opin，可空默认"工单撤销"）
  quash: (gongdanId, reason) => request.post(`/gongdan-integration/quash/${gongdanId}`, { reason }),
  // 重新推送：BOMC 新建工单 + 本地新建一条工单（原工单保留）
  repush: (gongdanId) => request.post(`/gongdan-integration/repush/${gongdanId}`),
  // 批量刷新 BOMC 状态（只传当前页工单 id，后端逐个查轨迹并回填）
  refreshStatus: (ids) => request.post('/gongdan-integration/trace-refresh', { ids }),
}

// 告警通道 API
export const alarmChannelApi = {
  list: () => request.get('/alarm-channel'),
  get: (id) => request.get(`/alarm-channel/${id}`),
  create: (data) => request.post('/alarm-channel', data),
  update: (id, data) => request.put(`/alarm-channel/${id}`, data),
  delete: (id) => request.delete(`/alarm-channel/${id}`),
  toggle: (id, enabled) => request.put(`/alarm-channel/${id}/toggle`, { enabled }),
  statistics: () => request.get('/alarm-channel/statistics'),
}

// 告警/工单开关 API
export const alarmSwitchApi = {
  getStatus: () => request.get('/alarm-switch/status'),
  toggleAlarm: () => request.post('/alarm-switch/toggle/alarm'),
  toggleGongdan: () => request.post('/alarm-switch/toggle/gongdan'),
}

// 告警分析 API
export const alarmAnalysisApi = {
  getReports: (params) => request.get('/alarm-analysis/reports', { params }),
  getReportsByDate: (date) => request.get(`/alarm-analysis/reports/${date}`),
  getConfig: () => request.get('/alarm-analysis/config'),
  updateConfig: (data) => request.put('/alarm-analysis/config', data),
  trigger: (params) => request.post('/alarm-analysis/trigger', null, { params }),
  batchDelete: (ids) => request.delete('/alarm-analysis/reports', { data: ids }),
}

// 专屏视图配置 API
export const dashboardViewApi = {
  list: () => request.get('/dashboard-view'),
  getByCode: (code) => request.get(`/dashboard-view/${code}`),
  getDefault: () => request.get('/dashboard-view/default'),
  getLayout: (code) => request.get(`/dashboard-view/${code}/layout`),
  getModules: (code) => request.get(`/dashboard-view/${code}/modules`),
  create: (data) => request.post('/dashboard-view', data),
  update: (id, data) => request.put(`/dashboard-view/${id}`, data),
  delete: (id) => request.delete(`/dashboard-view/${id}`),
  toggle: (id) => request.post(`/dashboard-view/${id}/toggle`),
  setDefault: (id) => request.post(`/dashboard-view/${id}/set-default`),
  saveModules: (id, modules) => request.put(`/dashboard-view/${id}/modules`, modules),
}

// 技能仓库 API（技能管理页面）
export const skillRepositoryApi = {
  list: () => request.get('/skill-repository'),
  download: (id) => request.get(`/skill-repository/${id}/download`, {
    responseType: 'blob',
    silentError: true
  }),
  upload: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/skill-repository/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
  },
  delete: (id) => request.delete(`/skill-repository/${id}`),
}

// 提示词管理 API
export const promptApi = {
  list: (params) => request.get('/prompt', { params }),
  get: (id) => request.get(`/prompt/${id}`),
  create: (data) => request.post('/prompt', data),
  update: (id, data) => request.put(`/prompt/${id}`, data),
  delete: (id) => request.delete(`/prompt/${id}`),
}

// 实体审计日志 API（工单/问题操作日志）
export const entityAuditApi = {
  list: (params) => request.get('/entity-audit/logs', { params }),
  get: (id) => request.get(`/entity-audit/logs/${id}`),
  batchDelete: (ids) => request.post('/entity-audit/batch-delete', ids),
}

// 能力运营 API
export const abilityMetricApi = {
  list: (params) => request.get('/ability-metric/list', { params }),
  statistics: (params) => request.get('/ability-metric/statistics', { params }),
  getYoYMoM: (params) => request.get('/ability-metric/yoy-mom', { params }),
  export: (params) => request.get('/ability-metric/export', { params }),
  triggerScan: () => request.post('/ability-metric/scan'),
  importFile: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/ability-metric/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  getCapabilities: () => request.get('/ability-metric/capabilities'),
  getUsers: () => request.get('/ability-metric/users'),
}

// 会话跟踪 API
export const sessionApi = {
  login: (data) => request.post('/session/login', data),
  init: (data) => request.post('/session/init', data),
  heartbeat: (sessionKey) => request.post('/session/heartbeat', { sessionKey }),
  end: (sessionKey) => request.post('/session/end', { sessionKey }),
  getConfig: () => request.get('/session/config'),
  activeList: () => request.get('/session/active'),
  getLogs: (params) => request.get('/session/logs', { params }),
  getStats: (params) => request.get('/session/stats', { params }),
  batchDelete: (ids) => request.post('/session/batch-delete', ids),
}

// 用户管理 API
export const userApi = {
  getList: (params) => request.get('/user', { params }),
  getById: (id) => request.get(`/user/${id}`),
  create: (data) => request.post('/user', data),
  update: (id, data) => request.put(`/user/${id}`, data),
  delete: (id) => request.delete(`/user/${id}`),
  changePassword: (id, data) => request.put(`/user/${id}/password`, data),
  assignRoles: (id, roleIds) => request.put(`/user/${id}/roles`, roleIds),
  getCurrent: (params) => request.get('/user/current', { params }),
}

// 角色管理 API
export const roleApi = {
  getList: (params) => request.get('/role', { params }),
  getById: (id) => request.get(`/role/${id}`),
  create: (data) => request.post('/role', data),
  update: (id, data) => request.put(`/role/${id}`, data),
  delete: (id) => request.delete(`/role/${id}`),
  assignMenus: (id, menuIds) => request.put(`/role/${id}/menus`, menuIds),
  assignUsers: (id, userIds) => request.put(`/role/${id}/users`, userIds),
}

// 登录日志 API（2026-07-10）
export const loginLogApi = {
  query: (params) => request.get('/login-log', { params }),
  dashboard: () => request.get('/login-log/dashboard'),
  onlineCount: () => request.get('/login-log/online-count'),
}

// 菜单 API
export const menuApi = {
  getList: () => request.get('/menu'),
}

// 全局通知事件 API（前端轮询消费 Toast 通知 + 历史通知管理）
export const eventStreamApi = {
  fetchRecent: (params) => request.get('/notifications/recent', { params }),
  // 历史通知分页（服务端全量，不受本机 localStorage 限制）
  fetchPage: (params) => request.get('/notifications/page', { params }),
  stats: () => request.get('/notifications/stats'),
  remove: (id) => request.delete(`/notifications/${id}`),
  batchRemove: (ids) => request.post('/notifications/batch-delete', ids),
  cleanup: (payload) => request.post('/notifications/cleanup', payload),
}

export const externalMenuApi = {
  list: () => request.get('/external-menus'),
  admin: () => request.get('/external-menus/admin'),
  save: data => request.put('/external-menus/admin',data),
  launch: id => request.post(`/external-menus/${encodeURIComponent(id)}/launch`)
}

export const zhiyunApi = {
  admin: () => request.get('/zhiyun/admin'),
  save: data => request.put('/zhiyun/admin', data),
  launch: () => request.post('/zhiyun/launch')
}

// ============ MCP 管理（技能管理 → MCP 管理页签） ============
export const mcpApi = {
  servers: () => request.get('/mcp/servers'),
  overview: () => request.get('/mcp/overview'),
  toggle: (serverKey, enabled) => request.put(`/mcp/servers/${serverKey}/enabled`, { enabled }),
  tokens: () => request.get('/mcp/tokens'),
  createToken: (payload) => request.post('/mcp/tokens', payload),
  revokeToken: (id) => request.delete(`/mcp/tokens/${id}`),
  logs: (params) => request.get('/mcp/logs', { params }),
  // 接入包是 zip，拦截器对 blob 直接放行
  downloadPackage: (serverKey, token) => request.post('/mcp/package', { serverKey, token }, {
    responseType: 'blob',
    silentError: true
  })
}
