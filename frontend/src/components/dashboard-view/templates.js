/**
 * 专屏模板元数据：模板画廊、配置面板和视图页共用这一份定义。
 * 新增模板时只改这里 + 对应渲染组件，避免配置项与页面结构各写一套。
 */

export const managerTabs = [
  { key: 'health-observation', label: '健康度观测', short: '健康观测', desc: '系统综合健康度、历史趋势、指标健康总览，以及底部的人员角色健康度（按岗位下钻）', modules: ['health'] },
  { key: 'situation', label: '闭环管理', short: '闭环', desc: '待办闭环、闭环分析与主管闭环对比', modules: ['events'] },
  { key: 'catalog', label: '指标体系', short: '指标体系', desc: '系统 → 大类 → 小类 → 指标全景与指标台账', modules: [] },
  { key: 'data', label: '指标与健康', short: '指标健康', desc: '指标最新值、健康度与数据汇聚记录', modules: ['data', 'health'] },
  { key: 'sources', label: '数据来源', short: '数据来源', desc: '来源表/字段、采集频率、最近数据与最新结果', modules: [] },
  { key: 'strategy', label: '策略配置', short: '策略', desc: '告警与工单触发规则（只读）', modules: [] },
  { key: 'team', label: '团队使用', short: '团队', desc: '关联主管登录与操作情况', modules: ['team'] }
]

export const templateMeta = {
  supervisor: {
    key: 'supervisor',
    name: '业务主管',
    audience: '业务主管 / 指标负责人',
    desc: '按主管自身授权范围展示指标、健康度、数据来源与闭环事项。',
    accent: 'primary',
    status: 'available',
    tabs: [
      { key: 'overview', label: '总览', desc: '指标基线、待办闭环与闭环分析', modules: ['events'] },
      { key: 'catalog', label: '指标体系', desc: '当前授权范围内的指标台账', modules: [] },
      { key: 'data', label: '指标与健康', desc: '指标最新值、健康度与汇聚数据', modules: ['data', 'health'] },
      { key: 'sources', label: '数据来源', desc: '来源配置与最新计算结果', modules: [] },
      { key: 'strategy', label: '策略配置', desc: '告警与工单规则', modules: [] }
    ],
    modules: ['health', 'data', 'events']
  },
  leader: {
    key: 'leader',
    name: '领导',
    audience: '领导 / 管理层',
    desc: '聚焦整体态势、重点风险与闭环成效，减少明细干扰。',
    accent: 'special',
    status: 'available',
    tabs: [
      { key: 'overview', label: '整体态势', desc: '风险占比、健康分布与待决事项', modules: ['health', 'events'] },
      { key: 'risk', label: '重点风险', desc: '按健康度排序的风险指标与趋势', modules: ['health'] },
      { key: 'outcome', label: '闭环成效', desc: '工单闭环率、问题按期解决率与责任分布', modules: ['events'] }
    ],
    modules: ['health', 'events', 'team']
  },
  manager: {
    key: 'manager',
    name: '科室经理',
    audience: '科室经理 / 团队负责人',
    desc: '按关联业务主管动态继承数据范围，覆盖健康观测、指标台账、数据来源、策略、主管闭环与团队使用。',
    accent: 'primary',
    status: 'priority',
    badge: '当前优先完善',
    tabs: managerTabs,
    modules: ['health', 'data', 'events', 'team']
  },
  operator: {
    key: 'operator',
    name: '平台运营',
    audience: '平台运营 / 系统管理员',
    desc: '查看全平台用户、导入、脚本、接口调用与操作日志汇总；业务指标仍按授权范围展示。',
    accent: 'warning',
    status: 'disabled',
    badge: '暂不开放',
    note: '本轮先完善科室经理模板，平台运营模板保留入口但暂不开放配置。',
    tabs: [
      { key: 'overview', label: '总览', desc: '平台运行与使用概览', modules: [] },
      { key: 'data', label: '数据汇聚', desc: '导入批次与数据量', modules: ['data'] },
      { key: 'events', label: '告警问题工单', desc: '闭环事项汇总', modules: ['events'] },
      { key: 'operations', label: '平台运行', desc: '用户、脚本、接口与操作日志', modules: ['operations'] }
    ],
    modules: ['data', 'events', 'operations']
  }
}

export const templateList = Object.values(templateMeta)

export function templateTabs(key) {
  return templateMeta[key]?.tabs || managerTabs
}

// Existing manager policies can contain the legacy supervisor comparison tab.
export function shareTemplateTabs(key) {
  const tabs = templateTabs(key)
  if (key !== 'manager') return tabs
  return [...tabs, { key: 'supervisors', label: '主管与闭环', short: '主管' }]
}

export function defaultTabs(key) {
  return templateTabs(key).map(tab => tab.key)
}

/** 根据页签推导后端需要的模块集合，保证页面不出现“有页签但没取数”。 */
export function modulesForTabs(templateKey, tabKeys) {
  const allowed = templateTabs(templateKey)
  const selected = allowed.filter(tab => tabKeys.includes(tab.key))
  const modules = new Set()
  selected.forEach(tab => (tab.modules || []).forEach(module => modules.add(module)))
  // 管理体系、来源、策略是后端按授权范围直接返回的只读数据，不依赖模块开关。
  return [...modules]
}

export function tabsForModules(templateKey, modules) {
  const allowed = templateTabs(templateKey)
  const selected = allowed.filter(tab => (tab.modules || []).every(module => modules.includes(module)))
  return selected.length ? selected.map(tab => tab.key) : defaultTabs(templateKey)
}
