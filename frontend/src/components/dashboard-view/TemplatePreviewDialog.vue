<template>
  <el-dialog
    :model-value="modelValue"
    :title="meta.name + '模板预览'"
    width="min(1280px, 96vw)"
    top="5vh"
    class="workspace-theme dashboard-workspace template-preview-dialog"
    @update:model-value="v => emit('update:modelValue', v)"
  >
    <div class="preview-intro">
      <div>
        <div class="preview-title-line">
          <h3>{{ meta.name }}专屏</h3>
          <span class="preview-badge" :class="`accent-${meta.accent}`">{{ meta.badge || '可用模板' }}</span>
          <span class="preview-badge ghost">所见即所得</span>
        </div>
        <p>{{ meta.desc }}</p>
        <small>适用：{{ meta.audience }} · 当前勾选 {{ activeTabs.length }} 个页签 · {{ meta.modules.length }} 个数据模块</small>
      </div>
      <div class="preview-side">
        <!-- 科室经理模板的布局切换由实际组件 ManagerScreen 自己提供（与运行时位置一致）；
             其他模板是结构示意，所以在预览头里补一个同样的切换。 -->
        <el-radio-group v-if="meta.key !== 'manager'" v-model="previewLayout" size="small" class="preview-layout-switch">
          <el-radio-button value="split">分栏查看</el-radio-button>
          <el-radio-button value="page">一页全览</el-radio-button>
        </el-radio-group>
        <div class="preview-legend">
          <span><i class="dot primary"></i>指标基线</span>
          <span><i class="dot danger"></i>待办闭环</span>
          <span><i class="dot success"></i>闭环成效</span>
        </div>
      </div>
    </div>

    <template v-if="meta.key === 'manager'">
      <div class="preview-real-head">
        <div><b>实际视图组件预览</b><small>与运行时使用同一套科室经理页面；按当前配置的布局展示，页签可点击切换。</small></div>
        <span class="preview-example">示例数据</span>
      </div>
      <div class="preview-real-wrap">
        <ManagerScreen
          :overview="mockOverview"
          :health-cards="mockHealthCards"
          :health-cards-loading="false"
          :days="7"
          :supervisor-id="null"
          view-code=""
          :layout="managerLayout"
          @metric-detail="noop"
          @select-supervisor="noop"
        />
      </div>
    </template>

    <div v-else class="preview-window">
      <div class="preview-topbar">
        <div class="preview-brand"><span class="logo"></span><b>{{ meta.name }}专屏</b><small>结构示意 · 按授权范围加载</small></div>
        <div class="preview-controls"><span>今天</span><span>近7天</span><span>近30天</span><span class="primary">刷新</span><span>全屏</span></div>
      </div>
      <div class="preview-kpi-row">
        <div class="preview-kpi-group">
          <div class="preview-group-title"><i></i>指标基线 <small>本室管理系统及核心指标覆盖底数</small></div>
          <div class="preview-kpi-grid"><div v-for="item in baseline" :key="item" class="preview-kpi"><span>{{ item }}</span><b>--</b><small>当前可见</small></div></div>
        </div>
        <div class="preview-kpi-group todo">
          <div class="preview-group-title"><i></i>闭环与健康态势 <small>待办积压及风险阻断</small></div>
          <div class="preview-todo-grid"><div v-for="item in todos" :key="item.label" class="preview-todo" :class="item.tone"><span>{{ item.label }}</span><b>--</b><small>当前积压</small></div></div>
        </div>
      </div>
      <div v-if="previewLayout === 'split'" class="preview-tabs">
        <span v-for="tab in visibleTabs" :key="tab.key" :class="{ active: tab.key === previewTab }" @click="previewTab = tab.key">{{ tab.label }}</span>
      </div>
      <div v-else class="preview-tabs-note">已按业务闭环顺序展开全部页签</div>
      <div class="preview-content">
        <div v-for="tab in (previewLayout === 'page' ? visibleTabs : [currentTab])" :key="tab.key" class="preview-panel wide">
          <div class="preview-panel-head"><b>{{ tab.label }}</b><small>{{ tab.desc }}</small></div>
          <div class="preview-placeholder-grid"><div v-for="n in 6" :key="n" class="preview-placeholder"><span></span><span></span><span></span></div></div>
        </div>
      </div>
    </div>

    <div class="preview-footer">
      <div><b>默认页签</b><span v-for="tab in activeTabs" :key="tab.key" class="chip">{{ tab.label }}</span></div>
      <div><b>涉及数据模块</b><span v-for="module in moduleLabels" :key="module" class="chip muted">{{ module }}</span></div>
      <p>{{ meta.key === 'manager' ? '预览直接复用实际科室经理视图；示例数据仅用于展示卡片和表格样式，真实数据以运行页面为准。' : '其他模板暂为结构示意；科室经理模板已复用实际视图组件。' }}</p>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import ManagerScreen from './ManagerScreen.vue'
import { templateMeta, modulesForTabs } from './templates'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  templateKey: { type: String, default: 'manager' },
  tabs: { type: Array, default: () => [] },
  modules: { type: Array, default: () => [] },
  layoutMode: { type: String, default: 'split' }
})
const emit = defineEmits(['update:modelValue'])
const noop = () => {}

const meta = computed(() => templateMeta[props.templateKey] || templateMeta.manager)
const activeTabs = computed(() => {
  const selected = props.tabs?.length ? props.tabs : meta.value.tabs.map(tab => tab.key)
  return meta.value.tabs.filter(tab => selected.includes(tab.key))
})
const visibleTabs = computed(() => activeTabs.value.length ? activeTabs.value : meta.value.tabs)
const previewTab = ref('situation')
// 预览内的布局切换：默认跟随专屏配置的 layoutMode，打开后可以手动对比两种布局。
const previewLayout = ref('split')
// ManagerScreen 的布局值是 tabs/page，预览内部用 split/page，这里做一层映射
const managerLayout = computed({
  get: () => previewLayout.value === 'page' ? 'page' : 'tabs',
  set: value => { previewLayout.value = value === 'page' ? 'page' : 'split' }
})
const currentTab = computed(() => visibleTabs.value.find(tab => tab.key === previewTab.value) || visibleTabs.value[0])
const MODULE_LABELS = { health: '健康度', data: '数据汇聚', events: '告警/问题/工单', report: '运营摘要', team: '团队使用', operations: '平台运行' }
const moduleLabels = computed(() => (props.modules?.length ? props.modules : meta.value.modules).map(key => MODULE_LABELS[key] || key))
const baseline = ['接入系统', '指标大类', '指标小类', '指标数量']
const todos = [
  { label: '待处理告警', tone: 'danger' },
  { label: '未解决问题', tone: 'warning' },
  { label: '未闭环工单', tone: 'success' }
]

watch(() => props.modelValue, visible => {
  if (!visible) return
  previewLayout.value = props.layoutMode === 'all' ? 'page' : 'split'
  previewTab.value = visibleTabs.value.some(tab => tab.key === 'situation') ? 'situation' : visibleTabs.value[0]?.key
})

// 预览用示例数据：结构、字段和实际 overview 一致，仅数值为演示值。
const mockOverview = computed(() => {
  const tabs = activeTabs.value.map(tab => tab.key)
  return {
    name: `${meta.value.name}专屏（预览）`,
    template: 'manager',
    layoutMode: previewLayout.value === 'page' ? 'all' : 'split',
    tabs,
    modules: modulesForTabs('manager', tabs),
    counts: { system: 1, category: 1, subcategory: 2, metric: 3 },
    nodes: [
      { key: 'system:1', parent: null, label: 'CRM系统', type: 'system', id: 1 },
      { key: 'category:2', parent: 'system:1', label: '运行效率', type: 'category', id: 2, systemId: 1 },
      { key: 'subcategory:3', parent: 'category:2', label: '系统性能', type: 'subcategory', id: 3, systemId: 1, categoryId: 2 },
      { key: 'metric:4', parent: 'subcategory:3', label: '接口平均响应时长', type: 'metric', id: 4, systemId: 1, categoryId: 2 },
      { key: 'metric:5', parent: 'subcategory:3', label: '接口成功率', type: 'metric', id: 5, systemId: 1, categoryId: 2 },
      { key: 'metric:6', parent: 'subcategory:3', label: '平均处理时长', type: 'metric', id: 6, systemId: 1, categoryId: 2 }
    ],
    catalogMetrics: [
      { id: 4, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '接口平均响应时长', metricCode: 'api_resp', metricType: 'gauge', metricDefinition: '接口平均响应耗时', collectFrequency: '5m', alarmThreshold: 500, unit: 'ms', status: 1 },
      { id: 5, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '接口成功率', metricCode: 'api_success', metricType: 'ratio', metricDefinition: '接口调用成功率', collectFrequency: '5m', alarmThreshold: 99.5, unit: '%', status: 1 },
      { id: 6, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '平均处理时长', metricCode: 'deal_time', metricType: 'gauge', metricDefinition: '工单平均处理耗时', collectFrequency: '1h', alarmThreshold: null, unit: '分钟', status: 1 }
    ],
    dataMetrics: [
      { id: 4, systemName: 'CRM系统', metricName: '接口平均响应时长', categoryName: '运行效率', subCategoryName: '系统性能', records: 48, latestDataTime: '2026-10-27T10:30:00', latestCalculationTime: '2026-10-27T10:30:00' },
      { id: 5, systemName: 'CRM系统', metricName: '接口成功率', categoryName: '运行效率', subCategoryName: '系统性能', records: 48, latestDataTime: '2026-10-27T10:30:00', latestCalculationTime: '2026-10-27T10:30:00' },
      { id: 6, systemName: 'CRM系统', metricName: '平均处理时长', categoryName: '运行效率', subCategoryName: '系统性能', records: 12, latestDataTime: '2026-10-27T10:00:00', latestCalculationTime: '2026-10-27T10:00:00' }
    ],
    dataSources: [
      { metricId: 4, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '接口平均响应时长', metricCode: 'api_resp', sourceTable: 'metric_api_log', sourceField: 'cost_ms', ruleType: 'AVG', collectFrequency: '5m', latestDataTime: '2026-10-27T10:30:00', latestValue: 326, healthScore: 92 },
      { metricId: 5, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '接口成功率', metricCode: 'api_success', sourceTable: 'metric_api_log', sourceField: 'success', ruleType: 'RATIO', collectFrequency: '5m', latestDataTime: '2026-10-27T10:30:00', latestValue: 99.8, healthScore: 78 },
      { metricId: 6, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '平均处理时长', metricCode: 'deal_time', sourceTable: 'metric_deal_log', sourceField: 'cost_min', ruleType: 'AVG', collectFrequency: '1h', latestDataTime: '2026-10-27T10:00:00', latestValue: 42, healthScore: 55 }
    ],
    strategies: [
      { id: 1, metricName: '接口平均响应时长', strategyName: '接口响应时长策略', strategyType: '指标值', alarmEnabled: 1, alarmCondition: '>', alarmThreshold: 500, alarmConsecutiveTimes: 3, gongdanEnabled: 1, gongdanCondition: '>', gongdanThreshold: 800, gongdanConsecutiveTimes: 3, status: 1, updateTime: '2026-10-27T09:00:00' },
      { id: 2, metricName: '接口成功率', strategyName: '接口成功率策略', strategyType: '指标值', alarmEnabled: 1, alarmCondition: '<', alarmThreshold: 99.5, alarmConsecutiveTimes: 2, gongdanEnabled: 0, status: 1, updateTime: '2026-10-27T09:00:00' },
      { id: 3, metricName: '平均处理时长', strategyName: '处理时长策略', strategyType: '指标值', alarmEnabled: 1, alarmCondition: '>', alarmThreshold: null, alarmConsecutiveTimes: 3, gongdanEnabled: 0, status: 0, updateTime: '2026-10-26T18:00:00' }
    ],
    alarm: { pending: 3, created: 5, completed: 2, trend: [{ day: '2026-10-25', created: 1 }, { day: '2026-10-26', created: 2 }, { day: '2026-10-27', created: 2 }] },
    issue: { pending: 1, created: 2, completed: 1, timeliness: { due: 2, onTime: 1, overdue: 1 } },
    gongdan: { pending: 2, created: 4, completed: 2, closure: { created: 4, closed: 2 } },
    responsibility: [{ person: '张主管', pending: 1, overdue: 1, total: 2 }],
    supervisors: [{ id: 1, username: 'zhang', displayName: '张主管', lastLogin: '2026-10-27T09:20:00' }],
    supervisorSummary: [{ id: 1, displayName: '张主管', username: 'zhang', metricCount: 3, alarmPending: 3, issuePending: 1, issueOverdue: 1, gongdanPending: 2, gongdanCreated: 4, gongdanClosed: 2 }],
    team: [{ id: 1, displayName: '张主管', username: 'zhang', usage: { sessions: 6 }, operations: 23, lastLogin: '2026-10-27T09:20:00' }],
    dataSummary: { coveredMetrics: 3, records: 108, latest: '2026-10-27T10:30:00' },
    updatedAt: '2026-10-27T10:30:00'
  }
})
const mockHealthCards = [
  { metricId: 4, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '接口平均响应时长', healthScore: 92, latestValue: 326, trend: [{ recordTime: '2026-10-25T10:00:00', healthScore: 88 }, { recordTime: '2026-10-26T10:00:00', healthScore: 90 }, { recordTime: '2026-10-27T10:00:00', healthScore: 92 }] },
  { metricId: 5, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '接口成功率', healthScore: 78, latestValue: 99.8, trend: [{ recordTime: '2026-10-25T10:00:00', healthScore: 84 }, { recordTime: '2026-10-26T10:00:00', healthScore: 80 }, { recordTime: '2026-10-27T10:00:00', healthScore: 78 }] },
  { metricId: 6, systemName: 'CRM系统', categoryName: '运行效率', subCategoryName: '系统性能', metricName: '平均处理时长', healthScore: 55, latestValue: 42, trend: [{ recordTime: '2026-10-25T10:00:00', healthScore: 68 }, { recordTime: '2026-10-26T10:00:00', healthScore: 61 }, { recordTime: '2026-10-27T10:00:00', healthScore: 55 }] }
]
</script>

<style scoped>
.preview-intro { display: flex; justify-content: space-between; gap: 20px; padding: 2px 2px 14px; }
.preview-title-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.preview-title-line h3 { margin: 0; color: var(--metric-text); font-size: 18px; }
.preview-intro p { margin: 7px 0 5px; max-width: 760px; color: var(--metric-text-secondary); font-size: 13px; line-height: 1.7; }
.preview-intro small { color: var(--metric-text-muted); font-size: 12px; }
.preview-badge { padding: 3px 9px; border-radius: 999px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-size: 11px; font-weight: 650; }
.preview-badge.accent-special { background: var(--metric-special-soft); color: var(--metric-special); }
.preview-badge.accent-warning { background: var(--metric-warning-soft); color: #b45309; }
.preview-badge.ghost { background: #f1f5f9; color: var(--metric-text-secondary); font-weight: 500; }
.preview-side { display: flex; flex-direction: column; align-items: flex-end; gap: 8px; }
.preview-layout-switch { flex: none; }
.preview-tabs-note { padding: 9px 16px; background: var(--ui-surface); border-bottom: 1px solid var(--metric-border); color: var(--metric-text-muted); font-size: 10px; }
.preview-content > .preview-panel + .preview-panel { margin-top: 10px; }
.preview-legend { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; color: var(--metric-text-muted); font-size: 11px; }
.preview-legend span { display: inline-flex; align-items: center; gap: 5px; }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dot.primary { background: var(--metric-primary); }
.dot.danger { background: var(--metric-danger); }
.dot.success { background: var(--metric-success); }

.preview-real-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--metric-primary-border); border-bottom: 0; border-radius: var(--metric-radius) var(--metric-radius) 0 0; background: var(--metric-primary-soft); }
.preview-real-head b { display: block; color: var(--metric-primary-hover); font-size: 12px; }
.preview-real-head small { display: block; margin-top: 3px; color: var(--metric-text-secondary); font-size: 10px; }
.preview-example { padding: 2px 8px; border-radius: 999px; background: var(--ui-surface); color: var(--metric-primary-hover); font-size: 10px; white-space: nowrap; }
.preview-real-wrap { max-height: 68vh; overflow: auto; padding: 12px; border: 1px solid var(--metric-primary-border); border-radius: 0 0 var(--metric-radius) var(--metric-radius); background: var(--metric-canvas); }

.preview-window { overflow: hidden; border: 1px solid var(--metric-border); border-radius: var(--metric-radius-lg); background: var(--metric-canvas); box-shadow: var(--metric-shadow-sm); }
.preview-topbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px 16px; background: var(--ui-surface); border-bottom: 1px solid var(--metric-border); }
.preview-brand { display: flex; align-items: center; gap: 8px; }
.preview-brand .logo { width: 9px; height: 18px; border-radius: 3px; background: linear-gradient(180deg, var(--metric-primary), #38bdf8); }
.preview-brand b { color: var(--metric-text); font-size: 14px; }
.preview-brand small { color: var(--metric-text-muted); font-size: 11px; }
.preview-controls { display: flex; gap: 6px; flex-wrap: wrap; }
.preview-controls span { padding: 4px 8px; border: 1px solid var(--metric-border); border-radius: 7px; background: var(--ui-surface); color: var(--metric-text-secondary); font-size: 10px; }
.preview-controls .primary { border-color: var(--metric-primary-border); background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-weight: 650; }
.preview-kpi-row { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, .8fr); gap: 12px; padding: 14px 16px 4px; }
.preview-kpi-group { padding: 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.preview-kpi-group.todo { background: linear-gradient(135deg, var(--ui-surface) 0%, var(--metric-warning-soft) 100%); }
.preview-group-title { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; color: var(--metric-text); font-size: 12px; font-weight: 650; }
.preview-group-title i { width: 5px; height: 13px; border-radius: 2px; background: var(--metric-primary); }
.preview-group-title small { color: var(--metric-text-muted); font-size: 10px; font-weight: 400; }
.preview-kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; }
.preview-kpi { display: grid; gap: 4px; padding: 9px; border: 1px solid #e8eef6; border-radius: 9px; background: #fbfdff; }
.preview-kpi span, .preview-todo span { color: var(--metric-text-secondary); font-size: 10px; }
.preview-kpi b { color: var(--metric-text); font-size: 18px; line-height: 1; }
.preview-kpi small, .preview-todo small { color: var(--metric-text-muted); font-size: 9px; }
.preview-todo-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; }
.preview-todo { display: grid; gap: 4px; padding: 9px; border: 1px solid #e8eef6; border-radius: 9px; background: var(--ui-surface); }
.preview-todo b { font-size: 18px; line-height: 1; }
.preview-todo.danger b { color: var(--metric-danger); }
.preview-todo.warning b { color: var(--metric-warning); }
.preview-todo.success b { color: var(--metric-success); }
.preview-tabs { display: flex; gap: 18px; overflow-x: auto; padding: 10px 16px 0; background: var(--ui-surface); border-bottom: 1px solid var(--metric-border); }
.preview-tabs span { position: relative; padding: 6px 0 8px; color: var(--metric-text-muted); font-size: 10px; white-space: nowrap; cursor: pointer; }
.preview-tabs span.active { color: var(--metric-primary-hover); font-weight: 650; }
.preview-tabs span.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; border-radius: 2px; background: var(--metric-primary); }
.preview-content { padding: 12px 16px 16px; }
.preview-panel { min-height: 130px; padding: 11px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.preview-panel-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.preview-panel-head b { color: var(--metric-text); font-size: 11px; }
.preview-panel-head small { color: var(--metric-text-muted); font-size: 9px; }
.preview-placeholder-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.preview-placeholder { display: grid; gap: 5px; padding: 10px; border: 1px solid #e8eef6; border-radius: 9px; background: #fbfdff; }
.preview-placeholder span { height: 7px; border-radius: 4px; background: #eef3f8; }
.preview-placeholder span:first-child { width: 60%; background: #dbeafe; }
.preview-footer { padding: 14px 2px 0; }
.preview-footer > div { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 7px; }
.preview-footer b { min-width: 84px; color: var(--metric-text-secondary); font-size: 11px; }
.preview-footer .chip { padding: 2px 7px; border-radius: 999px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-size: 10px; }
.preview-footer .chip.muted { background: #f1f5f9; color: var(--metric-text-secondary); }
.preview-footer p { margin: 4px 0 0; color: var(--metric-text-muted); font-size: 11px; line-height: 1.6; }
@media (max-width: 900px) {
  .preview-intro { flex-direction: column; }
  .preview-kpi-row { grid-template-columns: 1fr; }
  .preview-kpi-grid, .preview-placeholder-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preview-real-wrap { max-height: 62vh; padding: 8px; }
}
</style>
