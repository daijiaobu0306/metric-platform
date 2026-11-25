<template>
  <div class="manager-screen">
    <!-- 布局切换与业务主管/领导模板保持一致：放在内容区左上角，而不是顶部工具栏。
         分栏查看只看当前页签；一页全览按业务顺序展开全部区块，全屏只在一页全览时提供。 -->
    <div class="manager-nav">
      <el-radio-group :model-value="layout" size="small" class="manager-layout" @update:model-value="value => $emit('update:layout', value)">
        <el-radio-button value="tabs">分栏查看</el-radio-button>
        <el-radio-button value="page">一页全览</el-radio-button>
      </el-radio-group>
      <el-tabs v-if="layout === 'tabs'" v-model="activeTab" class="section-tabs workspace-tabs">
        <el-tab-pane v-for="tab in visibleTabs" :key="tab.key" :label="tab.label" :name="tab.key"><template #label><WorkspaceTabLabel :icon="{ 'health-observation':'TrendCharts',situation:'DataAnalysis',catalog:'Grid',data:'Coin',sources:'Connection',strategy:'SetUp',supervisors:'User',team:'UserFilled' }[tab.key]">{{ tab.label }}</WorkspaceTabLabel></template></el-tab-pane>
      </el-tabs>
      <span v-else class="manager-nav-hint">已按健康度观测、闭环管理、指标体系、指标与健康…的业务顺序展开全部内容</span>
      <ScreenReadingControls v-if="layout === 'page'" />
    </div>

    <section v-if="show('health-observation')" class="manager-section">
      <HealthObservation
        :health-cards="scoringCards"
        :catalog-metrics="overview?.catalogMetrics || []"
        :days="days"
        :range="range"
        :view-code="viewCode"
        :nodes="overview?.nodes || []"
        :loading="healthCardsLoading"
        @metric-detail="$emit('metric-detail', $event)"
      />
      <!-- 角色（岗位）维度收敛在页签最底部：先看整体健康度，再按角色下钻；
           只影响这张卡片，不影响闭环管理等其他页签。 -->
      <AudienceHealthCard :health-cards="scoringCards" :days="days" @metric-detail="$emit('metric-detail', $event)" />
    </section>

    <section v-if="show('situation')" class="manager-section">
      <section class="panel">
        <div class="situation-head">
          <div class="situation-title"><i class="accent-bar"></i><div><h3>待办闭环</h3><small>点击卡片或数字可查看受权限控制的明细</small></div></div>
          <span class="situation-meta">按当前时间范围统计</span>
        </div>
        <div class="todo-board">
          <button v-for="item in todos" :key="item.kind" type="button" class="todo-board-card" :class="item.accent" @click="openEvents(item.kind)">
            <div class="todo-board-head"><i class="todo-board-dot"></i><b>{{ item.boardTitle }}</b><small>{{ item.period }}</small></div>
            <div class="todo-board-stats">
              <div><span>当前待处理</span><b>{{ item.pending ?? 0 }}</b></div>
              <div><span>{{ item.newLabel }}</span><b>{{ item.created ?? 0 }}</b></div>
              <div><span>{{ item.doneLabel }}</span><b>{{ item.completed ?? 0 }}</b></div>
            </div>
            <div class="todo-board-action"><span>{{ item.action }}</span><span>›</span></div>
          </button>
        </div>
        <EventWorkspace
          v-if="eventKind && viewCode"
          :key="eventKind"
          :kind="eventKind"
          :code="viewCode"
          :days="days"
          :range="range"
          :supervisor-id="supervisorId"
          :overview="overview"
          @close="eventKind = ''"
        />
      </section>

      <div class="situation-bottom">
        <section class="panel">
          <div class="situation-head">
            <div class="situation-title"><i class="accent-bar"></i><div><h3>闭环分析</h3><small>重点责任分布与闭环效率</small></div></div>
            <span class="situation-meta">责任视图</span>
          </div>
          <el-table :data="overview?.responsibility || []" max-height="300" size="small" empty-text="当前授权范围内暂无待解决或超期责任人，所有指标运行在预定警戒线内">
            <el-table-column prop="person" label="责任人" min-width="150" />
            <el-table-column prop="total" label="问题数" width="90" />
            <el-table-column prop="pending" label="未解决" width="90" />
            <el-table-column prop="overdue" label="超期" width="80" />
          </el-table>
        </section>
        <GongdanClosureCard :gongdan="overview?.gongdan" :issue="overview?.issue" />
      </div>

      <section v-if="has('events')" class="panel supervisor-compare-panel">
        <div class="situation-head">
          <div class="situation-title"><i class="accent-bar"></i><div><h3>主管业务与闭环</h3><small>按各主管自身有效专屏授权计算；业务范围可能重叠，表格数值不可相加</small></div></div>
          <span class="situation-meta">关联主管 {{ overview?.supervisors?.length || 0 }} · 科室超期问题 {{ overview?.issue?.timeliness?.overdue ?? '--' }}</span>
        </div>
        <div class="supervisor-summary-line"><span>当前查看 <b>{{ selectedSupervisorName }}</b></span><span>主管对比用于聚焦查看，不改变上方科室汇总口径</span></div>
        <el-table :data="overview?.supervisorSummary || []" stripe border size="small" empty-text="当前主管没有可统计的业务数据">
          <el-table-column label="业务主管" min-width="145"><template #default="{ row }">{{ row.displayName || row.username }}</template></el-table-column>
          <el-table-column prop="metricCount" label="可见指标" width="95" />
          <el-table-column prop="alarmPending" label="待处理告警" width="105" />
          <el-table-column prop="issuePending" label="未解决问题" width="105" />
          <el-table-column label="超期问题" width="95"><template #default="{ row }"><el-tag size="small" :type="Number(row.issueOverdue) ? 'danger' : 'success'">{{ row.issueOverdue || 0 }}</el-tag></template></el-table-column>
          <el-table-column prop="gongdanPending" label="未闭环工单" width="105" />
          <el-table-column label="期间闭环率" width="105"><template #default="{ row }">{{ Number(row.gongdanCreated) ? `${supervisorRate(row.gongdanClosed, row.gongdanCreated)}%` : '--' }}</template></el-table-column>
          <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="$emit('select-supervisor', row.id)">聚焦</el-button></template></el-table-column>
        </el-table>
      </section>

      <section class="panel focus-issues-panel">
        <div class="situation-head">
          <div class="situation-title"><i class="accent-bar"></i><div><h3>关注问题</h3><small>重要但未解决的问题</small></div></div>
        </div>
        <el-table :data="overview?.focusIssues || []" stripe border size="small" max-height="300" empty-text="当前授权范围内暂无未解决的关注问题">
          <el-table-column label="登记时间" width="160"><template #default="{ row }">{{ time(row.registerTime) }}</template></el-table-column>
          <el-table-column label="已登记时长" width="120"><template #default="{ row }">{{ elapsedTime(row.registerTime) }}</template></el-table-column>
          <el-table-column prop="issueDescription" label="问题描述" show-overflow-tooltip />
        </el-table>
      </section>
    </section>

    <section v-if="show('catalog')" v-loading="metricPanelsLoading" class="manager-section">
              <section class="panel situation-panel baseline-catalog-panel">
          <div class="situation-head">
            <div class="situation-title"><i class="accent-bar"></i><div><h3>指标基线</h3><small>本室管理系统及核心指标覆盖底数</small></div></div>
            <span class="situation-meta">平台全量 / 本屏可见</span>
          </div>
          <div class="baseline-grid">
            <article v-for="item in baselineCards" :key="item.label" class="baseline-card">
              <div class="baseline-card-top"><el-icon class="baseline-icon" :class="item.tone"><component :is="item.icon" /></el-icon><span class="baseline-status" :class="item.tone">{{ item.status }}</span></div>
              <span class="baseline-label">{{ item.label }}</span>
              <div class="baseline-value"><b>{{ item.value }}</b><small>{{ item.unit }}</small></div>
              <small class="baseline-note">{{ item.note }}</small>
            </article>
          </div>
        </section>
      <CatalogMindMap :rows="overview?.catalogMetrics || []" :health-cards="healthCards" :health-loading="healthCardsLoading" @metric-detail="$emit('metric-detail', $event)" />
    </section>

    <section v-if="show('data')" v-loading="metricPanelsLoading" class="manager-section">
      <section class="panel">
        <div class="data-toolbar">
          <div class="data-info"><el-icon><InfoFilled /></el-icon><span>说明：直接按体系筛选，风险指标优先展示；趋势在每个卡片中直接可见。</span><el-tag size="small" effect="plain">已匹配 {{ filteredHealthCards.length }} 个指标</el-tag></div>
          <div class="data-filters" v-balanced-query>
            <el-select v-model="filters.system" clearable filterable placeholder="全部系统"><el-option v-for="x in options('system')" :key="x.id" :value="x.id" :label="x.label" /></el-select>
            <el-select v-model="filters.category" clearable filterable placeholder="全部大类"><el-option v-for="x in options('category')" :key="x.id" :value="x.id" :label="x.label" /></el-select>
            <el-select v-model="filters.subcategory" clearable filterable placeholder="全部小类"><el-option v-for="x in options('subcategory')" :key="x.key" :value="x.label" :label="x.label" /></el-select>
            <el-input v-model="filters.keyword" clearable placeholder="模糊搜索指标名称" />
            <el-button type="primary" @click="emit('refresh')">查询</el-button>
            <el-button @click="resetFilters">重置</el-button>
          </div>
        </div>
        <div class="data-summary-grid">
          <article class="data-summary-card blue">
            <el-icon class="data-summary-icon"><DataLine /></el-icon>
            <div><span>有数据指标</span><b>{{ activeMetricCount }}</b><small>个指标活跃</small></div>
            <em>覆盖率 {{ coverageRate }}%</em>
          </article>
          <article class="data-summary-card green">
            <el-icon class="data-summary-icon"><Document /></el-icon>
            <div><span>数据记录</span><b>{{ dataRows.reduce((n, x) => n + Number(x.records || 0), 0).toLocaleString('en-US') }}</b><small>条明细记录</small></div>
            <em>持续汇聚入库</em>
          </article>
          <article class="data-summary-card cyan">
            <el-icon class="data-summary-icon"><Clock /></el-icon>
            <div><span>最近汇聚时间</span><b class="time-value">{{ time(overview?.dataSummary?.latest) }}</b><small>实时同步完成</small></div>
            <em>数据时间</em>
          </article>
        </div>
      </section>

      <section class="panel">
        <div class="health-section-head">
          <div class="health-title"><i class="accent-bar"></i><h3>指标健康总览</h3><span class="risk-tag">风险优先展示</span><small>已匹配 {{ filteredHealthCards.length }} 个指标，点击指标卡片查看详情</small></div>
          <span class="section-meta">异常 {{ healthStats.danger }} · 正常 {{ healthStats.normal }} · 待计算 {{ healthStats.unknown }}</span>
        </div>
        <HealthGrid v-if="has('health')" :rows="filteredHealthCards" :loading="healthCardsLoading" :limit="0" @select="$emit('metric-detail', $event)" @health-detail="openHealthExplain" />
      </section>
    </section>

    <section v-if="show('sources')" v-loading="metricPanelsLoading" class="manager-section">
      <SourceFlow
        :sources="overview?.dataSources || []"
        :data-summary="overview?.dataSummary || {}"
        :metric-count="Number(overview?.counts?.metric || 0)"
        :health-count="healthAnalyzedCount"
        :alarm-pending="Number(overview?.alarm?.pending || 0)"
        :issue-pending="Number(overview?.issue?.pending || 0)"
        :gongdan-pending="Number(overview?.gongdan?.pending || 0)"
        :active-source="convergenceSource"
        @select-source="selectDataSource"
        @navigate="navigateFlow"
      />
      <DataWorkspace :view-code="viewCode" :days="days" :range="range" :supervisor-id="supervisorId" :source-name="convergenceSource" />
    </section>

    <section v-if="show('strategy')" v-loading="metricPanelsLoading" class="manager-section">
      <section class="panel">
        <header><div><h3>策略配置</h3><small>只读展示当前授权指标的告警与工单触发规则</small></div><el-input v-model="strategyKeyword" clearable placeholder="搜索策略或指标名称" class="small-search" /></header>
        <div class="summary-strip"><span>已配置策略 <b>{{ strategyRows.length }}</b></span><span>启用告警 <b>{{ strategyRows.filter(x => Number(x.alarmEnabled) === 1 && Number(x.status) === 1).length }}</b></span><span>启用工单 <b>{{ strategyRows.filter(x => Number(x.gongdanEnabled) === 1 && Number(x.status) === 1).length }}</b></span></div>
        <el-table :data="strategyRows" stripe border max-height="560">
          <el-table-column prop="metricName" label="指标" min-width="170" show-overflow-tooltip />
          <el-table-column prop="strategyName" label="策略名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="strategyType" label="类型" width="90" />
          <el-table-column label="告警规则" min-width="160"><template #default="{ row }"><span v-if="Number(row.alarmEnabled) === 1">{{ ruleSummary(row, 'alarm') }}</span><el-tag v-else size="small" type="info">未启用</el-tag></template></el-table-column>
          <el-table-column label="工单规则" min-width="160"><template #default="{ row }"><span v-if="Number(row.gongdanEnabled) === 1">{{ ruleSummary(row, 'gongdan') }}</span><el-tag v-else size="small" type="info">未启用</el-tag></template></el-table-column>
          <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="Number(row.status) === 1 ? 'success' : 'info'">{{ Number(row.status) === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column label="更新时间" width="165"><template #default="{ row }">{{ time(row.updateTime) }}</template></el-table-column>
        </el-table>
      </section>
    </section>

    <section v-if="show('team')" class="manager-section">
      <ManagerDashboard
        :overview="overview"
        section="team"
        layout="tabs"
        :supervisor-id="supervisorId"
        @select-supervisor="$emit('select-supervisor', $event)"
      />
    </section>

    <HealthExplainDialog v-model="healthExplainVisible" :item="healthExplainItem" :definition="healthExplainDefinition" />
  </div>
</template>

<script setup>
import { scoreLevel } from '@/utils/healthThresholds'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import { ruleSummary } from '../../utils/strategyRules'
import { computed, nextTick, ref, watch } from 'vue'
import StatusTag from './ui/StatusTag.vue'
import HealthGrid from './HealthGrid.vue'
import HealthExplainDialog from './HealthExplainDialog.vue'
import HealthObservation from './HealthObservation.vue'
import AudienceHealthCard from './AudienceHealthCard.vue'
import EventWorkspace from './EventWorkspace.vue'
import ManagerDashboard from './ManagerDashboard.vue'
import GongdanClosureCard from './GongdanClosureCard.vue'
import SourceFlow from './SourceFlow.vue'
import DataWorkspace from './DataWorkspace.vue'
import CatalogMindMap from './CatalogMindMap.vue'
import ScreenReadingControls from './ScreenReadingControls.vue'
import { managerTabs } from './templates'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  overview: { type: Object, default: () => ({}) },
  healthCards: { type: Array, default: () => [] },
  healthCardsLoading: { type: Boolean, default: false },
  metricPanelsLoading: { type: Boolean, default: false },
  days: { type: Number, default: 7 },
  // 自定义时间区间 [start,end]（YYYY-MM-DD HH:mm:ss）；为空时按 days 预设取数。
  // 传给会自己发请求的子组件，保证切区间时健康观测、闭环明细、数据明细一起刷新。
  range: { type: Array, default: null },
  supervisorId: { type: [Number, String], default: null },
  viewCode: { type: String, default: '' },
  layout: { type: String, default: 'tabs' }
})
const emit = defineEmits(['metric-detail', 'select-supervisor', 'refresh', 'section-change', 'update:layout'])

const activeTab = ref('health-observation')
watch(activeTab, value => emit('section-change', value), { immediate: true })
const eventKind = ref('')
const catalogKeyword = ref(''), strategyKeyword = ref('')
const convergenceSource = ref('')
const healthExplainVisible = ref(false), healthExplainItem = ref(null), healthExplainDefinition = ref(null)
const catalogTreeRef = ref(), catalogNodeKey = ref('')
const filters = ref({ system: null, category: null, subcategory: null, keyword: '' })

const tabs = computed(() => {
  const configured = props.overview?.tabs
  const keys = Array.isArray(configured) && configured.length ? configured : managerTabs.map(tab => tab.key)
  return managerTabs.filter(tab => keys.includes(tab.key))
})
const visibleTabs = computed(() => tabs.value.length ? tabs.value : managerTabs)
const count = key => Number(props.overview?.counts?.[key] || 0)
const counts = computed(() => props.overview?.counts || {})
const selectedSupervisorName = computed(() => {
  if (!props.supervisorId) return '全部主管'
  const person = (props.overview?.supervisors || []).find(item => Number(item.id) === Number(props.supervisorId))
  return person?.displayName || person?.username || '所选主管'
})
function supervisorRate (done, total) { return Number(total) > 0 ? Math.min(100, Math.round(Number(done || 0) * 100 / Number(total))) : 0 }
const coveredSystemCount = computed(() => {
  const names = new Set((props.overview?.catalogMetrics || []).map(item => item.systemName).filter(Boolean))
  return names.size || count('system')
})
const has = name => props.overview?.modules?.includes(name)
const time = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'
const elapsedTime = value => {
  if (!value) return '-'
  const diff = Date.now() - new Date(String(value).replace(' ', 'T')).getTime()
  if (!Number.isFinite(diff)) return '-'
  const hours = Math.floor(diff / 3600000)
  if (hours < 1) return `${Math.max(0, Math.floor(diff / 60000))}分钟`
  if (hours < 24) return `${hours}小时`
  return `${Math.floor(hours / 24)}天`
}
const show = key => props.layout === 'page' || activeTab.value === key

watch(visibleTabs, list => {
  if (!list.some(tab => tab.key === activeTab.value)) activeTab.value = list[0]?.key || 'situation'
}, { immediate: true })
watch(() => props.viewCode, () => {
  activeTab.value = visibleTabs.value.some(tab => tab.key === 'health-observation')
    ? 'health-observation' : visibleTabs.value[0]?.key || 'situation'
})

// 指标基线：主数值取平台全量（platformCounts），note 写本屏可见（counts）。
// 只显示授权范围会把「1 大类 / 12 指标」误读成平台底数（用户 2026-11-25 反馈）。
const platformCount = key => Number(props.overview?.platformCounts?.[key] ?? props.overview?.counts?.[key] ?? 0)
const baselineCards = computed(() => [
  { label: '接入系统', value: platformCount('system'), unit: '个', icon: 'Monitor', tone: 'primary', status: coveredSystemCount.value ? '稳定' : '暂无', note: `本屏可见 ${coveredSystemCount.value}` },
  { label: '指标大类', value: platformCount('category'), unit: '类', icon: 'Grid', tone: 'indigo', status: count('category') ? '健康' : '暂无', note: `本屏可见 ${count('category')}` },
  { label: '指标小类', value: platformCount('subcategory'), unit: '类', icon: 'CollectionTag', tone: 'cyan', status: count('subcategory') ? '健康' : '暂无', note: `本屏可见 ${count('subcategory')}` },
  { label: '指标数量', value: platformCount('metric'), unit: '项', icon: 'DataLine', tone: 'blue', status: count('metric') ? '关键核心' : '暂无', note: `本屏可见 ${count('metric')}` }
])
const todos = computed(() => ['alarm', 'issue', 'gongdan'].map((key, index) => {
  const data = props.overview?.[key] || {}
  const pending = data.pending ?? 0
  const meta = {
    alarm: { label: '待处理告警', boardTitle: '告警待办处置', period: '今日统计', newLabel: '今日新增', doneLabel: '今日完成', action: '穿透明细', icon: 'Bell' },
    issue: { label: '未解决问题', boardTitle: '问题跟踪闭环', period: '本周统计', newLabel: '本周新增', doneLabel: '本周解决', action: '跟踪看板', icon: 'Warning' },
    gongdan: { label: '未闭环工单', boardTitle: '工单跟踪信息', period: '周期统计', newLabel: '周期新增', doneLabel: '周期完成', action: '工单详情', icon: 'Document' }
  }[key]
  return {
    kind: key,
    ...meta,
    pending,
    created: data.created ?? 0,
    completed: data.completed ?? 0,
    accent: ['danger', 'warning', 'success'][index],
    status: pending > 0 ? '关注' : '良好',
    trend: (data.trend || []).map(x => Math.min(100, Number(x.created || 0) * 10))
  }
}))
// 白名单（不计分）指标只观察、不出分：健康度相关区块统一剔除，避免出现 0 分/待计算的错觉；
// 「数据来源」页签仍按原始取数展示（采集和时效照常可见）。口径来自后端 healthCards/dataMetrics 的 exempt。
const scoringCards = computed(() => (props.healthCards || []).filter(card => !card.exempt))
const healthStats = computed(() => {
  const out = { normal: 0, warning: 0, danger: 0, unknown: 0 }
  scoringCards.value.forEach(item => {
    if (item.healthScore == null) out.unknown++
    else if (scoreLevel(item.healthScore) === 'danger') out.danger++
    else if (scoreLevel(item.healthScore) === 'warning') out.warning++
    else out.normal++
  })
  return out
})
const categoryHealth = computed(() => {
  const map = {}
  scoringCards.value.forEach(item => {
    const name = item.categoryName || '未分类'
    const group = map[name] ?? (map[name] = { name, total: 0, normal: 0, warning: 0, danger: 0, unknown: 0, sum: 0, known: 0 })
    group.total++
    if (item.healthScore == null) group.unknown++
    else {
      group.known++
      group.sum += Number(item.healthScore)
      if (scoreLevel(item.healthScore) === 'danger') group.danger++
      else if (scoreLevel(item.healthScore) === 'warning') group.warning++
      else group.normal++
    }
  })
  return Object.values(map).map(group => ({ ...group, avg: group.known ? Math.round(group.sum / group.known) : null }))
})
const nodes = computed(() => props.overview?.nodes || [])
const options = type => nodes.value.filter(n => n.type === type)
const catalogTree = computed(() => {
  const map = new Map(nodes.value.map(node => [node.key, { ...node, children: [] }]))
  const roots = []
  for (const node of map.values()) {
    if (map.has(node.parent)) map.get(node.parent).children.push(node)
    else roots.push(node)
  }
  return roots
})
const catalogScope = computed(() => nodes.value.find(node => node.key === catalogNodeKey.value))
const catalogScopeLabel = computed(() => catalogScope.value?.label || '全部授权范围')
const parentKeyOf = key => nodes.value.find(node => node.key === key)?.parent
function descendsFrom (key, ancestor) {
  let parent = parentKeyOf(key)
  const seen = new Set()
  while (parent && !seen.has(parent)) {
    if (parent === ancestor) return true
    seen.add(parent)
    parent = parentKeyOf(parent)
  }
  return false
}
// 指标 item 的 category_id 与 metric_sub_category 的归属偶尔不一致；按子树 + 小类名称双条件匹配，
// 避免点击大类后因为 categoryName 不同而查不到指标。
const catalogScopeContext = computed(() => {
  const scope = catalogScope.value
  if (!scope || scope.type === 'metric') return null
  const ids = new Set(), subCategoryLabels = new Set()
  for (const node of nodes.value) {
    if (!descendsFrom(node.key, scope.key)) continue
    if (node.type === 'metric') ids.add(Number(node.id))
    if (node.type === 'subcategory') subCategoryLabels.add(node.label)
  }
  if (scope.type === 'subcategory') subCategoryLabels.add(scope.label)
  return { ids, subCategoryLabels }
})
const catalogRows = computed(() => {
  const q = catalogKeyword.value.trim().toLowerCase()
  const scope = catalogScope.value
  const context = catalogScopeContext.value
  return (props.overview?.catalogMetrics || []).filter(item => {
    const keywordMatch = !q || [item.systemName, item.categoryName, item.subCategoryName, item.metricName, item.metricCode, item.metricDefinition].some(v => String(v || '').toLowerCase().includes(q))
    if (!keywordMatch) return false
    if (!scope) return true
    if (scope.type === 'metric') return Number(item.id) === Number(scope.id)
    if (context?.ids.has(Number(item.id))) return true
    if (context?.subCategoryLabels.has(item.subCategoryName)) return true
    return scope.type === 'system' && item.systemName === scope.label
  })
})
function filterCatalogNode (value, node) {
  if (!value) return true
  const q = value.toLowerCase()
  return String(node.label || '').toLowerCase().includes(q) || String(node.key || '').toLowerCase().includes(q)
}
function selectCatalogNode (node) { catalogNodeKey.value = node.key }
function resetCatalogNode () { catalogNodeKey.value = ''; catalogTreeRef.value?.setCurrentKey(null) }
watch(catalogKeyword, value => catalogTreeRef.value?.filter(value))
const healthCardById = id => props.healthCards.find(item => Number(item.metricId) === Number(id))
const healthStatus = id => {
  const item = healthCardById(id)
  return item?.healthScore == null ? 'unknown' : scoreLevel(item.healthScore) === 'danger' ? 'danger' : scoreLevel(item.healthScore) === 'warning' ? 'warning' : 'normal'
}
const healthLabel = id => {
  const status = healthStatus(id)
  return status === 'danger' ? '异常' : status === 'warning' ? '关注' : status === 'unknown' ? '待计算' : '正常'
}
const thresholdText = (row, key = 'alarmThreshold') => row?.[key] == null ? '未配置' : `${row[key]}${row.unit ? ` ${row.unit}` : ''}`

const metricNode = item => nodes.value.find(n => n.type === 'metric' && Number(n.id) === Number(item.metricId || item.id))
const matchData = item => {
  const f = filters.value, node = metricNode(item)
  return (!f.keyword || String(item.metricName || '').toLowerCase().includes(f.keyword.toLowerCase()))
    && (!f.subcategory || item.subCategoryName === f.subcategory)
    && (!f.category || Number(node?.categoryId) === Number(f.category))
    && (!f.system || Number(node?.systemId) === Number(f.system))
}
const dataRows = computed(() => (props.overview?.dataMetrics || []).filter(item => !item.exempt).filter(matchData))
const filteredHealthCards = computed(() => scoringCards.value.filter(card => {
  const f = filters.value, node = metricNode(card)
  return (!f.keyword || String(card.metricName || '').toLowerCase().includes(f.keyword.toLowerCase()))
    && (!f.subcategory || card.subCategoryName === f.subcategory)
    && (!f.category || Number(node?.categoryId) === Number(f.category))
    && (!f.system || Number(node?.systemId) === Number(f.system))
}))
const activeMetricCount = computed(() => dataRows.value.filter(row => Number(row.records) > 0).length)
const coverageRate = computed(() => dataRows.value.length ? Math.round(activeMetricCount.value * 100 / dataRows.value.length) : 0)
function resetFilters () { filters.value = { system: null, category: null, subcategory: null, keyword: '' } }

const healthAnalyzedCount = computed(() => scoringCards.value.filter(item => item.healthScore != null).length)
function navigateFlow (path) {
  if (path === '/data') { nextTick(() => document.querySelector('.data-workspace')?.scrollIntoView({ behavior: 'smooth', block: 'start' })); return }
  const tab = path === '/report' ? 'data' : path === '/observation' ? 'health-observation' : 'situation'
  if (!visibleTabs.value.some(item => item.key === tab)) return
  if (tab === 'situation') eventKind.value = path.slice(1)
  activeTab.value = tab
}
function selectDataSource (name) {
  convergenceSource.value = name
  if (name) nextTick(() => document.querySelector('.data-workspace')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
const strategyRows = computed(() => {
  const q = strategyKeyword.value.trim().toLowerCase()
  return (props.overview?.strategies || []).filter(item => !q || [item.metricName, item.strategyName, item.strategyType, item.targetName].some(v => String(v || '').toLowerCase().includes(q)))
})
function openHealthExplain (item) {
  healthExplainItem.value = item
  healthExplainDefinition.value = (props.overview?.catalogMetrics || []).find(meta => Number(meta.id) === Number(item.metricId)) || null
  healthExplainVisible.value = true
}
function openEvents (kind) { eventKind.value = eventKind.value === kind ? '' : kind }
</script>

<style scoped>
.manager-screen { min-width: 0; width: 100%; padding-bottom: 16px; }
/* 布局切换行：与领导模板 .leader-nav 同一套视觉（左侧切换、右侧页签、全览时给全屏） */
.manager-nav { display: flex; align-items: center; gap: 16px; padding: 10px 14px; margin-bottom: 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.manager-layout { flex: none; }
.manager-nav-hint { flex: 1; min-width: 0; color: var(--metric-text-muted); font-size: 12px; }
.manager-nav :deep(.reading-controls) { margin-left: auto; flex: none; }
.section-tabs { flex: 1; min-width: 0; margin: 0 0 -15px; }
.manager-nav :deep(.el-tabs__header) { margin: 0; }
.manager-nav :deep(.el-tabs__nav-wrap::after) { height: 1px; }
.manager-section { display: grid; min-width: 0; width: 100%; gap: 12px; margin-bottom: 14px; }
.panel { min-width: 0; width: 100%; padding: 16px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius-lg); background: var(--ui-surface); box-shadow: var(--metric-shadow-xs); }
.panel header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.panel h3 { margin: 0; color: var(--metric-text); font-size: 15px; }
.panel header small { display: block; margin-top: 4px; color: var(--metric-text-muted); font-size: 10px; }
.kpi-layout { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr); gap: 12px; }
.kpi-grid { display: grid; gap: 8px; }
.kpi-grid.four { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.kpi-grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.kpi-grid.compact { margin-bottom: 12px; }
.todo-panel { background: linear-gradient(135deg, var(--ui-surface) 0%, var(--metric-warning-soft) 100%); }
.health-kpis { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; margin-bottom: 14px; }
.closure-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.closure-card { display: grid; gap: 4px; padding: 11px 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: #fbfdff; text-align: left; cursor: pointer; }
.closure-card:hover { border-color: var(--metric-primary-border); box-shadow: var(--metric-shadow-sm); }
.closure-card span { color: var(--metric-text-secondary); font-size: 11px; }
.closure-card b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 24px; line-height: 1; }
.closure-card small { color: var(--metric-text-muted); font-size: 10px; }
.closure-card.danger b { color: var(--metric-danger); }
.closure-card.warning b { color: var(--metric-warning); }
.closure-card.success b { color: var(--metric-success); }
.closure-bottom { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(0, 1.2fr); gap: 16px; }
.closure-left { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 16px; padding: 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--metric-surface-soft); }
.gauge-box { display: grid; justify-items: center; gap: 4px; }
.gauge-box small { color: var(--metric-text-muted); font-size: 10px; }
.rate-list { display: grid; gap: 16px; }
.rate-list > div { display: grid; gap: 5px; }
.rate-list span { color: var(--metric-text-secondary); font-size: 11px; }
.rate-list b { color: var(--metric-text); font-size: 21px; }
.closure-right h4 { margin: 0 0 8px; color: var(--metric-text); font-size: 12px; }
.summary-strip { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; padding: 9px 12px; margin-bottom: 10px; border-radius: var(--metric-radius-sm); background: var(--metric-surface-soft); color: var(--metric-text-secondary); font-size: 11px; }
.summary-strip b { color: var(--metric-primary-hover); margin-left: 4px; }
.small-search { width: 210px; }
.metric-filters { display: grid; grid-template-columns: repeat(4, minmax(140px, 1fr)) auto; gap: 8px; margin-bottom: 12px; }
.catalog-layout { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 12px; }
.catalog-tree-panel { min-width: 0; padding: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--metric-surface-soft); }
.catalog-tree-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: var(--metric-text); font-size: 12px; }
.catalog-tree-panel :deep(.el-tree) { background: transparent; color: var(--metric-text-secondary); font-size: 11px; }
.catalog-tree-panel :deep(.el-tree-node__content) { height: 26px; border-radius: 5px; }
.catalog-table-panel { min-width: 0; }
.catalog-table, .data-table { margin-top: 4px; }
.category-health { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 8px; margin-bottom: 14px; }
.category-health-card { display: grid; gap: 5px; padding: 10px 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--metric-surface-soft); }
.category-health-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.category-health-head b { color: var(--metric-text); font-size: 12px; }
.category-health-head span { color: var(--metric-text-muted); font-size: 10px; }
.category-health-score { display: flex; align-items: baseline; gap: 6px; }
.category-health-score b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 20px; }
.category-health-score small { color: var(--metric-text-muted); font-size: 10px; }
.category-health-bar { height: 5px; overflow: hidden; border-radius: 999px; background: #e8eef6; }
.category-health-bar i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--metric-warning), var(--metric-success)); }
.category-health-counts { display: flex; gap: 8px; flex-wrap: wrap; font-size: 10px; }
.category-health-counts .ok { color: var(--metric-success); }
.category-health-counts .warn { color: var(--metric-warning); }
.category-health-counts .bad { color: var(--metric-danger); }
.category-health-counts .muted { color: var(--metric-text-muted); }
.situation-top { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, .9fr); gap: 12px; }
.situation-top-single { grid-template-columns: 1fr; }
.situation-panel { padding: 14px; }
.situation-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 11px; }
.situation-title { display: flex; align-items: flex-start; gap: 7px; min-width: 0; }
.accent-bar { display: inline-block; width: 4px; height: 16px; flex: none; margin-top: 1px; border-radius: 2px; background: var(--metric-primary); }
.situation-title h3 { margin: 0; color: var(--metric-text); font-size: 13px; }
.situation-title small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 9px; }
.situation-meta { color: var(--metric-text-muted); font-size: 9px; white-space: nowrap; }
.baseline-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.baseline-card { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: linear-gradient(180deg, var(--ui-surface) 0%, var(--metric-surface-soft) 100%); }
.baseline-card-top { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.baseline-icon { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 6px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-size: 12px; }
.baseline-icon.indigo { background: #eef2ff; color: #4f46e5; }
.baseline-icon.cyan { background: #ecfeff; color: #0891b2; }
.baseline-icon.blue { background: #eff6ff; color: #2563eb; }
.baseline-status { padding: 1px 5px; border-radius: 999px; background: #f1f5f9; color: var(--metric-text-muted); font-size: 8px; }
.baseline-status.primary { background: var(--metric-primary-soft); color: var(--metric-primary-hover); }
.baseline-status.indigo { background: #eef2ff; color: #4f46e5; }
.baseline-status.cyan { background: #ecfeff; color: #0891b2; }
.baseline-status.blue { background: #eff6ff; color: #2563eb; }
.baseline-label { color: var(--metric-text-secondary); font-size: 10px; }
.baseline-value { display: flex; align-items: baseline; gap: 3px; }
.baseline-value b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 24px; line-height: 1; }
.baseline-value small { color: var(--metric-text-muted); font-size: 9px; }
.baseline-note { color: var(--metric-text-muted); font-size: 9px; }
.health-situation-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.situation-todo { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); text-align: left; cursor: pointer; transition: .18s; }
.situation-todo.danger { background: linear-gradient(135deg, var(--ui-surface) 0%, var(--metric-danger-soft) 100%); border-color: #fecdd3; }
.situation-todo.warning { background: linear-gradient(135deg, var(--ui-surface) 0%, var(--metric-warning-soft) 100%); border-color: #fde68a; }
.situation-todo.success { background: linear-gradient(135deg, var(--ui-surface) 0%, var(--metric-success-soft) 100%); border-color: #a7f3d0; }
.situation-todo:hover { box-shadow: var(--metric-shadow-md); transform: translateY(-1px); }
.situation-todo-top { display: flex; align-items: center; justify-content: space-between; }
.situation-todo-icon { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 6px; background: var(--ui-surface); color: var(--metric-text-secondary); font-size: 12px; }
.situation-todo.danger .situation-todo-icon { color: var(--metric-danger); }
.situation-todo.warning .situation-todo-icon { color: var(--metric-warning); }
.situation-todo.success .situation-todo-icon { color: var(--metric-success); }
.situation-todo-status { padding: 1px 5px; border-radius: 999px; background: var(--ui-surface); color: var(--metric-text-secondary); font-size: 8px; }
.situation-todo-label { color: var(--metric-text-secondary); font-size: 10px; }
.situation-todo-value { display: flex; align-items: baseline; gap: 3px; }
.situation-todo-value b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 24px; line-height: 1; }
.situation-todo.danger .situation-todo-value b { color: var(--metric-danger); }
.situation-todo.warning .situation-todo-value b { color: var(--metric-warning); }
.situation-todo.success .situation-todo-value b { color: var(--metric-success); }
.situation-todo-value small { color: var(--metric-text-muted); font-size: 9px; }
.situation-todo-foot { display: flex; align-items: center; justify-content: space-between; padding-top: 4px; border-top: 1px solid rgb(226 232 240 / .7); color: var(--metric-text-muted); font-size: 8px; }
.status-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--metric-text-muted); }
.status-dot.danger { background: var(--metric-danger); }
.status-dot.warning { background: var(--metric-warning); }
.status-dot.success { background: var(--metric-success); }
.todo-board { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.todo-board-card { display: grid; gap: 9px; padding: 11px 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); text-align: left; cursor: pointer; transition: .18s; }
.todo-board-card:hover { box-shadow: var(--metric-shadow-md); transform: translateY(-1px); }
.todo-board-card.danger { border-left: 3px solid var(--metric-danger); }
.todo-board-card.warning { border-left: 3px solid var(--metric-warning); }
.todo-board-card.success { border-left: 3px solid var(--metric-success); }
.todo-board-head { display: flex; align-items: center; gap: 5px; }
.todo-board-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--metric-primary); }
.todo-board-card.danger .todo-board-dot { background: var(--metric-danger); }
.todo-board-card.warning .todo-board-dot { background: var(--metric-warning); }
.todo-board-card.success .todo-board-dot { background: var(--metric-success); }
.todo-board-head b { color: var(--metric-text); font-size: 11px; }
.todo-board-head small { margin-left: auto; color: var(--metric-text-muted); font-size: 9px; }
.todo-board-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.todo-board-stats > div { display: grid; gap: 3px; padding: 6px 8px; border-radius: 6px; background: var(--metric-surface-soft); }
.todo-board-stats span { color: var(--metric-text-muted); font-size: 9px; }
.todo-board-stats b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 15px; }
.todo-board-action { display: flex; align-items: center; justify-content: flex-end; gap: 4px; color: var(--metric-primary-hover); font-size: 10px; }
.situation-bottom { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(300px, .65fr); gap: 12px; }

.data-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; padding: 8px 10px; border-radius: var(--metric-radius-sm); background: var(--metric-surface-soft); }
/* 说明行与筛选行各自占据由父容器决定的宽度：筛选行用固定的 flex-basis（约一行放下全部控件的宽度），
   不再由内部控件撑开，避免「控件宽度 → 容器宽度 → 重新分配控件宽度」的自激循环
   （表现为按钮持续漂移、忽大忽小）。控件增删时同步调整这里的 800px。 */
.data-info { flex: 0 1 auto; min-width: 0; display: flex; align-items: center; gap: 6px; color: var(--metric-text-muted); font-size: 10px; }
.data-info .el-icon { flex: none; color: var(--metric-primary); }
.data-info .el-tag { flex: none; margin-left: 2px; }
.data-filters { flex: 1 1 800px; min-width: 0; max-width: 100%; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.data-filters .el-select { width: 118px; }
.data-filters .el-input { width: 170px; }
.data-summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; margin-top: 11px; }
.data-summary-card { display: flex; align-items: center; gap: 10px; padding: 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.data-summary-card.blue { background: linear-gradient(135deg, var(--ui-surface) 0%, #eff6ff 100%); }
.data-summary-card.green { background: linear-gradient(135deg, var(--ui-surface) 0%, #ecfdf5 100%); }
.data-summary-card.cyan { background: linear-gradient(135deg, var(--ui-surface) 0%, #ecfeff 100%); }
.data-summary-icon { display: grid; place-items: center; width: 38px; height: 38px; flex: none; border-radius: 9px; background: var(--ui-surface); color: var(--metric-primary); font-size: 18px; box-shadow: var(--metric-shadow-xs); }
.data-summary-card.green .data-summary-icon { color: var(--metric-success); }
.data-summary-card.cyan .data-summary-icon { color: #0891b2; }
.data-summary-card > div { display: grid; gap: 2px; min-width: 0; }
.data-summary-card span { color: var(--metric-text-secondary); font-size: 10px; }
.data-summary-card b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 24px; line-height: 1; }
.data-summary-card b.time-value { font-size: 15px; line-height: 1.3; }
.data-summary-card small { color: var(--metric-text-muted); font-size: 9px; }
.data-summary-card em { margin-left: auto; color: var(--metric-text-muted); font-size: 9px; font-style: normal; white-space: nowrap; }
.health-section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.health-title { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.health-title h3 { margin: 0; color: var(--metric-text); font-size: 13px; }
.health-title small { color: var(--metric-text-muted); font-size: 10px; }
.risk-tag { padding: 1px 6px; border-radius: 999px; background: var(--metric-danger-soft); color: #be123c; font-size: 9px; }
.section-meta { color: var(--metric-text-muted); font-size: 10px; white-space: nowrap; }
.supervisor-summary-line { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 7px 9px; margin-bottom: 8px; border-radius: 6px; background: var(--metric-surface-soft); color: var(--metric-text-muted); font-size: 9px; }
.supervisor-summary-line b { color: var(--metric-text); margin-left: 3px; }
.source-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.source-head h3 { margin: 0; color: var(--metric-text); font-size: 13px; }
.source-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 9px; }
.source-filters { display: grid; grid-template-columns: 130px 130px 160px 105px minmax(160px, 1fr) auto auto; gap: 7px; margin-bottom: 9px; }
.source-filters .el-select, .source-filters .el-input { width: 100%; }
.source-summary-line { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 7px 9px; margin-bottom: 8px; border-radius: 6px; background: var(--metric-surface-soft); color: var(--metric-text-muted); font-size: 9px; }
.source-summary-line b { color: var(--metric-text); margin-left: 3px; }
.source-summary-line .danger b { color: var(--metric-danger); }
.source-summary-line .warning b { color: var(--metric-warning); }
.active-source { display: inline-flex; align-items: center; gap: 5px; color: var(--metric-primary-hover); }
.active-source button { padding: 0; border: 0; background: transparent; color: var(--metric-primary-hover); font-size: 9px; text-decoration: underline; cursor: pointer; }
.source-code { display: block; color: var(--metric-text-muted); font-size: 9px; }
.source-table :deep(.el-table__cell) { padding: 6px 0; }
.source-pager { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 9px; color: var(--metric-text-muted); font-size: 9px; }
@media (max-width: 1100px) { .health-kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); } .kpi-layout, .closure-bottom, .catalog-layout, .situation-top, .situation-bottom, .data-summary-grid { grid-template-columns: 1fr; } .baseline-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1500px) {
  .toolbar { flex-wrap: wrap; }
  .title-block { flex: 1 1 100%; }
  .actions { width: 100%; margin-left: 0; justify-content: flex-start; flex-wrap: wrap; }
  .actions > .el-select { width: 150px; }
  .source-filters { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .source-filters .el-input { grid-column: span 2; }
}
@media (max-width: 760px) { .manager-nav { flex-wrap: wrap; } .manager-nav-hint { width: 100%; } .section-tabs { width: 100%; } .kpi-grid.four, .kpi-grid.three, .closure-strip, .health-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } .metric-filters { grid-template-columns: 1fr; } .closure-left { grid-template-columns: 1fr; } .health-situation-grid, .todo-board, .closure-metrics, .data-summary-grid { grid-template-columns: 1fr; } .baseline-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .data-toolbar { align-items: flex-start; flex-direction: column; } .data-filters { flex: 1 1 100%; width: auto; } .data-filters .el-select, .data-filters .el-input { width: calc(50% - 3px); } .source-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); } .source-filters .el-input { grid-column: span 2; } .source-head { flex-direction: column; } }
</style>
