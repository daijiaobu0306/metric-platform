<template>
  <div class="screen-page workspace-theme dashboard-workspace" :class="{ 'share-page': isSharePage }"><el-tabs v-model="activeTab" class="workspace-tabs dashboard-main-tabs"><el-tab-pane label="视图" name="view"><template #label><WorkspaceTabLabel icon="Monitor">视图</WorkspaceTabLabel></template>
    <div class="toolbar"><div class="title-block"><div class="title-line"><h2>{{ overview?.name || '运营专屏' }}</h2><span v-if="currentView?.visibilitySummary" class="visibility-summary">可见：{{ currentView.visibilitySummary }}</span></div><small>{{ templates[overview?.template] || '' }} · 更新于 {{ time(overview?.updatedAt) }} · 当前范围：{{ currentRangeLabel }}</small></div><div class="actions"><el-radio-group v-model="days" @change="onPresetChange"><el-radio-button :value="1">今天</el-radio-button><el-radio-button :value="7">近7天</el-radio-button><el-radio-button :value="30">近30天</el-radio-button></el-radio-group><el-popover placement="bottom-end" :width="380" trigger="click" :teleported="false" :hide-on-click="false"><template #reference><el-button class="range-trigger" :class="{ 'is-custom': customRangeActive }">{{ customRangeActive ? '自定义' : '自定义区间' }}</el-button></template><div class="range-picker-box"><el-date-picker v-model="customRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" :teleported="false" :clearable="true" size="small" class="range-picker" @change="applyCustomRange"/><small class="range-hint">最长 90 天。选完立即刷新整屏；切换上方「今天/近7天/近30天」也会清空自定义区间。</small><el-button v-if="customRangeActive" link type="primary" size="small" @click="onPresetChange">清除自定义区间</el-button></div></el-popover><el-select v-if="overview?.template==='manager'&&!isSharePage" v-model="supervisorId" clearable placeholder="全部业务主管" @change="loadOverview"><el-option v-for="u in overview?.supervisors||[]" :key="u.id" :value="u.id" :label="u.displayName||u.username"/></el-select><el-select v-if="!isSharePage" v-model="viewCode" @change="switchView"><el-option v-for="v in views" :key="v.viewCode" :value="v.viewCode" :label="v.viewName"/></el-select><el-select v-model="refreshSeconds" class="refresh-interval" aria-label="自动刷新间隔"><el-option label="手动刷新" :value="0"/><el-option label="每 30 秒" :value="30"/><el-option label="每 1 分钟" :value="60"/><el-option label="每 5 分钟" :value="300"/></el-select><el-button @click="loadOverview">刷新</el-button><ScreenReadingControls v-if="isSharePage&&!isManagerTemplate" compact class="manager-reading"/></div></div>
    <!-- 角色（岗位）维度已收敛到「健康度观测」页签里的小卡片，不再在顶部过滤整屏 -->
    <el-result v-if="shareError" icon="warning" title="链接无效、已失效或无访问权限" sub-title="请联系专屏管理员获取新的访问链接。"/><el-empty v-else-if="!loading&&!viewCode" description="当前账号尚未配置可查看的专屏，请联系管理员配置角色或用户授权"/><div v-else v-loading="loading">
      <ManagerScreen v-if="isManagerTemplate" v-model:layout="layout" :overview="overview" :health-cards="healthCards" :health-cards-loading="healthCardsLoading || metricPanelsLoading" :metric-panels-loading="metricPanelsLoading" @refresh="loadOverview" @section-change="section = $event === 'health-observation' ? 'data' : $event === 'situation' ? 'overview' : $event" :days="days" :range="customRange" :supervisor-id="supervisorId" :view-code="viewCode" @metric-detail="openMetricDetail" @select-supervisor="selectManagerSupervisor"/>
      <template v-else-if="isBusinessTemplate"><div class="supervisor-nav"><el-radio-group v-model="layout" size="small"><el-radio-button value="tabs">分栏查看</el-radio-button><el-radio-button value="page">一页全览</el-radio-button></el-radio-group><el-tabs v-if="layout==='tabs'" v-model="section" class="section-tabs workspace-tabs"><el-tab-pane label="总览" name="overview"><template #label><WorkspaceTabLabel icon="DataAnalysis">总览</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane label="指标体系" name="catalog"><template #label><WorkspaceTabLabel icon="Grid">指标体系</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane label="指标与健康" name="data"><template #label><WorkspaceTabLabel icon="TrendCharts">指标与健康</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane label="数据来源" name="sources"><template #label><WorkspaceTabLabel icon="Coin">数据来源</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane label="策略配置" name="strategy"><template #label><WorkspaceTabLabel icon="SetUp">策略配置</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane v-if="isManagerTemplate" label="主管与闭环" name="supervisors"/><el-tab-pane v-if="isManagerTemplate" label="团队使用" name="team"/></el-tabs><span v-else>已按业务闭环顺序展开全部内容</span><ScreenReadingControls v-if="layout==='page'"/></div>
        <section v-show="show('overview')" class="supervisor-section"><div class="numbers"><article v-for="x in cards" :key="x.label" class="number" @click="drill(x)"><span>{{ x.label }}</span><b>{{ x.value }}</b><small>{{ x.note }}</small></article></div><section v-if="has('events')" class="panel events-overview"><header><div><h3>待办闭环</h3><small>点击卡片或数字即可查看受权限控制的明细</small></div></header><div class="events"><article v-for="k in kinds" :key="k.key" class="event" @click="openEvents(k.key)"><h4>{{ k.label }}</h4><b>{{ overview?.[k.key]?.pending??0 }}</b><span>当前待处理</span><svg viewBox="0 0 100 28" preserveAspectRatio="none"><polyline :points="eventSpark(overview?.[k.key]?.trend)"/></svg><div><small>新增 {{ overview?.[k.key]?.created??0 }}</small><small>完成 {{ overview?.[k.key]?.completed??0 }}</small></div></article></div><div v-if="eventVisible" class="event-panel"><header><h3>{{ eventLabel }}明细</h3><el-button link @click="eventVisible=false">收起</el-button></header><div class="event-filters" v-balanced-query><el-input v-model="eventKeyword" clearable placeholder="按内容或责任人搜索"/><el-select v-model="eventState" clearable placeholder="处理状态"><el-option label="待处理" value="pending"/><el-option label="已完成" value="done"/></el-select><el-button @click="loadEventRows">查询</el-button></div><el-table v-loading="eventLoading" :data="filteredEventRows"><el-table-column prop="title" label="内容" min-width="260" show-overflow-tooltip/><el-table-column prop="owner" label="责任人" width="120"/><el-table-column label="发生时间" width="175"><template #default="{row}">{{ time(row.time) }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.done?'success':'warning'">{{ row.done?'已完成':'待处理' }}</el-tag></template></el-table-column></el-table></div></section></section>
        <section v-show="show('catalog')" v-loading="metricPanelsLoading" class="supervisor-section"><CatalogMindMap :rows="overview?.catalogMetrics || []" :health-cards="healthCards" :health-loading="healthCardsLoading" @metric-detail="openMetricDetail" /></section>
        <section v-show="show('data')" v-loading="metricPanelsLoading" class="supervisor-section"><section class="panel"><header><div><h3>指标与健康</h3><small>直接按体系筛选，风险指标优先显示；趋势在每个卡片中直接可见</small></div></header><div class="metric-filters" v-balanced-query><el-select v-model="filters.system" clearable filterable placeholder="全部系统"><el-option v-for="x in options.system" :key="x.id" :value="x.id" :label="x.label"/></el-select><el-select v-model="filters.category" clearable filterable placeholder="全部大类"><el-option v-for="x in options.category" :key="x.id" :value="x.id" :label="x.label"/></el-select><el-select v-model="filters.subcategory" clearable filterable placeholder="全部小类"><el-option v-for="x in options.subcategory" :key="x.key" :value="x.label" :label="x.label"/></el-select><el-input v-model="filters.keyword" clearable placeholder="模糊搜索指标名称"/><el-button text @click="resetFilters">重置</el-button></div><div class="numbers compact"><article class="number"><span>有数据指标</span><b>{{ dataRows.filter(x=>Number(x.records)>0).length }}</b><small>筛选范围内</small></article><article class="number"><span>数据记录</span><b>{{ dataRows.reduce((n,x)=>n+Number(x.records||0),0) }}</b><small>当前时间范围</small></article><article class="number"><span>最近汇聚</span><b class="date-value">{{ time(overview?.dataSummary?.latest) }}</b><small>健康记录生成时间</small></article></div><div v-if="has('health')" class="health-workspace" v-loading="healthCardsLoading"><div class="health-context"><div><span class="risk-label">风险优先展示</span><strong>指标健康总览</strong><small> 已匹配 {{ healthRows.length }} 个指标 · 点击指标卡片查看详情</small></div></div><div v-if="healthGroups.length" class="health-card-groups"><section v-for="group in healthGroups" :key="group.name" class="health-card-group"><h4>{{ group.name }}</h4><div v-for="sub in group.subgroups" :key="sub.name"><h5 v-if="sub.name">{{ sub.name }}</h5><div class="health-card-grid"><article v-for="item in sub.items" :key="item.metricId" class="health-card" :class="[healthClass(item),{selected:Number(selectedMetric?.metricId)===Number(item.metricId)}]" tabindex="0" @click="openMetricDetail(item)" @keyup.enter="openMetricDetail(item)"><div class="health-card-top"><span>{{ item.metricName }}</span><el-tag size="small" :type="healthTag(item)">{{ healthText(item) }}</el-tag></div><div class="health-values"><b>{{ item.healthScore??'--' }}</b><small>健康度</small><strong>{{ item.latestValue??'--' }}</strong><small>最新值</small></div><svg viewBox="0 0 100 34" preserveAspectRatio="none"><polyline :points="spark(item.trend)"/><circle v-if="sparkEnd(item.trend)" :cx="sparkEnd(item.trend).x" :cy="sparkEnd(item.trend).y" r="2.8"/></svg><small class="trend-note">{{ trendNote(item.trend) }} · 查看详情</small></article></div></div></section></div><el-empty v-else description="当前筛选条件下没有指标"/><el-button v-if="healthRows.length>30" class="show-all" @click="showAll=!showAll">{{ showAll?'收起指标':'展开全部指标' }}（{{ healthRows.length }}）</el-button></div><el-table :data="dataRows" max-height="360" class="data-table"><el-table-column prop="systemName" label="系统" min-width="110"/><el-table-column prop="categoryName" label="大类"/><el-table-column prop="subCategoryName" label="小类"/><el-table-column prop="metricName" label="指标" min-width="210"/><el-table-column prop="records" label="记录数" width="90"/><el-table-column label="最新数据" width="175"><template #default="{row}">{{ time(row.latestDataTime) }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="Number(row.records)>0?'success':'danger'">{{ Number(row.records)>0?'有数据':'无数据' }}</el-tag></template></el-table-column></el-table></section></section>
        <section v-show="show('strategy')" v-loading="metricPanelsLoading" class="supervisor-section"><section class="panel"><header><div><h3>策略配置</h3><small>只读展示当前授权指标的告警与工单触发规则</small></div><el-input v-model="strategyKeyword" clearable placeholder="搜索策略或指标名称" class="small-search"/></header><div class="strategy-summary"><span>已配置策略 <b>{{ strategyRows.length }}</b></span><span>启用告警 <b>{{ strategyRows.filter(x=>Number(x.alarmEnabled)===1&&Number(x.status)===1).length }}</b></span><span>启用工单 <b>{{ strategyRows.filter(x=>Number(x.gongdanEnabled)===1&&Number(x.status)===1).length }}</b></span></div><el-table :data="strategyRows" stripe border max-height="560"><el-table-column prop="metricName" label="指标" min-width="180" show-overflow-tooltip/><el-table-column prop="strategyName" label="策略名称" min-width="160" show-overflow-tooltip/><el-table-column prop="strategyType" label="策略类型" width="100"/><el-table-column label="告警规则" min-width="165"><template #default="{row}"><span v-if="Number(row.alarmEnabled)===1">{{ row.alarmCondition||'>' }} {{ row.alarmThreshold??'未配置' }} · 连续 {{ row.alarmConsecutiveTimes||1 }} 次</span><el-tag v-else size="small" type="info">未启用</el-tag></template></el-table-column><el-table-column label="工单规则" min-width="165"><template #default="{row}"><span v-if="Number(row.gongdanEnabled)===1">{{ row.gongdanCondition||'>' }} {{ row.gongdanThreshold??'未配置' }} · 连续 {{ row.gongdanConsecutiveTimes||1 }} 次</span><el-tag v-else size="small" type="info">未启用</el-tag></template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}"><el-tag size="small" :type="Number(row.status)===1?'success':'info'">{{ Number(row.status)===1?'启用':'停用' }}</el-tag></template></el-table-column><el-table-column label="更新时间" width="170"><template #default="{row}">{{ time(row.updateTime) }}</template></el-table-column></el-table></section></section>
        <section v-show="show('overview')" class="supervisor-section"><section class="panel"><header><div><h3>闭环分析</h3><small>三类待办与趋势已在上方卡片统一呈现；此处聚焦责任分布与闭环效率</small><small v-if="platformClosureText" class="platform-closure-line">{{ platformClosureText }}</small></div></header><div class="closure-bottom"><div><h4>问题责任分布</h4><el-table :data="overview?.responsibility||[]" max-height="340"><el-table-column prop="person" label="责任人"/><el-table-column prop="total" label="问题数" width="85"/><el-table-column prop="pending" label="未解决" width="85"/><el-table-column prop="overdue" label="超期" width="80"/></el-table></div><GongdanClosureCard :gongdan="overview?.gongdan" :issue="overview?.issue" /></div></section></section>
        <section v-show="show('sources')" v-loading="metricPanelsLoading" class="supervisor-section"><SourceFlow :sources="overview?.dataSources || []" :data-summary="overview?.dataSummary || {}" :metric-count="Number(overview?.counts?.metric || 0)" :health-count="healthCards.filter(item => item.healthScore != null).length" :alarm-pending="Number(overview?.alarm?.pending || 0)" :issue-pending="Number(overview?.issue?.pending || 0)" :gongdan-pending="Number(overview?.gongdan?.pending || 0)" :active-source="sourceKeyword" @select-source="sourceKeyword = $event" @navigate="navigateSourceFlow" /><section class="panel"><header><div><h3>数据来源</h3><small>展示授权指标的数据来源、最近数据时效与最新计算结果；源表明细需按数据源类型受控下钻</small></div><div class="source-tools"><RowSortToggle v-model="sourceSort"/><el-input v-model="sourceKeyword" clearable placeholder="搜索指标、编码或数据来源" class="small-search"/></div></header><div class="source-summary"><span>已配置数据源 <b>{{ sourceRows.filter(x=>x.dataSource).length }}</b></span><span>已产生计算结果 <b>{{ sourceRows.filter(x=>x.latestCalculationTime).length }}</b></span><span>无最新数据 <b>{{ sourceRows.filter(x=>!x.latestDataTime).length }}</b></span></div><el-table :data="sortedSourceRows" stripe border max-height="560"><el-table-column prop="systemName" label="系统" width="110"/><el-table-column prop="categoryName" label="大类" width="115"/><el-table-column prop="subCategoryName" label="小类" width="120"/><el-table-column prop="metricName" label="指标" min-width="180" show-overflow-tooltip/><el-table-column prop="metricCode" label="编码" width="125" show-overflow-tooltip/><el-table-column prop="dataSource" label="数据来源" min-width="190"><template #default="{row}">{{ row.dataSource||'未配置' }}</template></el-table-column><el-table-column prop="collectFrequency" label="采集频率" width="100"><template #default="{row}">{{ row.collectFrequency||'-' }}</template></el-table-column><el-table-column label="最近数据" width="170"><template #default="{row}">{{ time(row.latestDataTime) }}</template></el-table-column><el-table-column label="最新结果" width="100"><template #default="{row}">{{ row.latestValue??'-' }}</template></el-table-column><el-table-column label="健康度" width="90"><template #default="{row}"><el-tag size="small" :type="healthTag(row)">{{ row.healthScore??'--' }}</el-tag></template></el-table-column></el-table></section></section>
        <ManagerDashboard v-if="isManagerTemplate" :overview="overview" :section="section" :layout="layout" :supervisor-id="supervisorId" @select-supervisor="selectManagerSupervisor"/>
      </template>
      <LeaderDashboard v-else-if="isLeaderTemplate" :overview="overview" :health-cards="scoredHealthCards" :health-cards-loading="healthCardsLoading" @metric-detail="openMetricDetail"/>
      <template v-else><div class="numbers"><article v-for="x in cards" :key="x.label" class="number"><span>{{ x.label }}</span><b>{{ x.value }}</b><small>{{ x.note }}</small></article></div><section v-if="has('health')" class="panel"><header><div><h3>指标与健康度</h3><small>只展示明确授权层级及其下级</small></div></header><div class="scope"><el-tree :data="tree" node-key="key" default-expand-all highlight-current @node-click="loadHealth"/><div v-loading="healthLoading" class="health"><el-empty v-if="!health" description="选择左侧节点查看健康度和趋势"/><div v-else ref="healthChart" class="chart"/></div></div></section></template>
    </div>
  </el-tab-pane><el-tab-pane v-if="canConfigure" label="配置" name="config"><template #label><WorkspaceTabLabel icon="Setting">配置</WorkspaceTabLabel></template><DashboardViewConfigPanel @saved="reloadViews"/></el-tab-pane></el-tabs><ScreenMetricDetailDialog v-model="detailVisible" :metric="selectedMetric" :detail="metricDetail" :loading="metricDetailLoading" @closed="selectedMetric=null;metricDetail=null" /></div>
</template>
<script setup>
import GongdanClosureCard from '@/components/dashboard-view/GongdanClosureCard.vue'
import SourceFlow from '@/components/dashboard-view/SourceFlow.vue'
import CatalogMindMap from '@/components/dashboard-view/CatalogMindMap.vue'
import ScreenMetricDetailDialog from '@/components/dashboard-view/ScreenMetricDetailDialog.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import { ensureHealthThresholds, scoreLevel } from '@/utils/healthThresholds'
ensureHealthThresholds()
import {computed,onBeforeUnmount,onMounted,nextTick,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import * as echarts from 'echarts'
import DashboardViewConfigPanel from '../../components/dashboard-view/ConfigPanel.vue'
import ManagerDashboard from '../../components/dashboard-view/ManagerDashboard.vue'
import LeaderDashboard from '../../components/dashboard-view/LeaderDashboard.vue'
import ManagerScreen from '../../components/dashboard-view/ManagerScreen.vue'
import ScreenReadingControls from '../../components/dashboard-view/ScreenReadingControls.vue'
import RowSortToggle from '../../components/dashboard-view/RowSortToggle.vue'
import { sortRowsByTime } from '../../components/dashboard-view/sortRows'
import { screenApi, templates, message } from '../../components/dashboard-view/api'
import { shareMode } from '../../components/dashboard-view/share-mode'
const route=useRoute(),router=useRouter(),activeTab=ref(route.query.tab==='config'?'config':'view'),canConfigure=ref(sessionStorage.getItem('isAdmin')==='true'),views=ref([]),viewCode=ref(''),days=ref(7),customRange=ref(null),supervisorId=ref(null),refreshSeconds=ref(0),overview=ref(),loading=ref(false),health=ref(),healthLoading=ref(false),healthCards=ref([]),healthCardsLoading=ref(false),healthChart=ref(),selectedMetric=ref(null),metricDetail=ref(null),metricDetailLoading=ref(false),detailVisible=ref(false),layout=ref('tabs'),section=ref('overview'),catalogKeyword=ref(''),strategyKeyword=ref(''),sourceKeyword=ref(''),sourceSort=ref('desc'),showAll=ref(false),filters=ref({system:null,category:null,subcategory:null,keyword:''}),eventVisible=ref(false),eventLoading=ref(false),eventRows=ref([]),eventKind=ref('alarm'),eventKeyword=ref(''),eventState=ref('');let charts=[],refreshTimer=0,layoutViewCode=''
// PC 分享页（/screen/pc/s/:publicId）：复用本页面组件，只把取数换成匿名只读分享接口。
const isSharePage=/\/screen\/pc\/s\//.test(window.location.pathname)
const shareError=ref(false)
const types={system:'系统',category:'大类',subcategory:'小类',metric:'指标'},kinds=[{key:'alarm',label:'告警'},{key:'issue',label:'问题'},{key:'gongdan',label:'工单'}],has=k=>overview.value?.modules?.includes(k),isManagerTemplate=computed(()=>overview.value?.template==='manager'),isLeaderTemplate=computed(()=>overview.value?.template==='leader'),isBusinessTemplate=computed(()=>['supervisor','manager'].includes(overview.value?.template)),eventLabel=computed(()=>kinds.find(x=>x.key===eventKind.value)?.label||''),tree=computed(()=>toTree(overview.value?.nodes||[])),catalogTree=computed(()=>toTree((overview.value?.nodes||[]).filter(n=>!catalogKeyword.value||n.label.toLowerCase().includes(catalogKeyword.value.toLowerCase())||descendantMatch(n)))),options=computed(()=>({system:nodes('system'),category:nodes('category'),subcategory:nodes('subcategory')}))
// 自定义时间区间：el-date-picker(datetimerange) 的值是 [start,end]（YYYY-MM-DD HH:mm:ss）。
// 为空时按 days 预设取数，请求参数与老版本一致（只带 days），老链接/老视图不受影响。
const customRangeActive=computed(()=>Array.isArray(customRange.value)&&customRange.value.length===2)
const currentRangeLabel=computed(()=>customRangeActive.value?`${customRange.value[0]} ~ ${customRange.value[1]}`:({1:'今天',7:'近7天',30:'近30天'}[days.value]||`近${days.value}天`))
// 后端趋势/健康度快照只有 1d/7d/30d 三档，按窗口长度向上取最接近的一档，与后端 resolveRange 口径一致。
function rangeDays(value){if(!Array.isArray(value)||value.length!==2)return 7;const start=new Date(String(value[0]).replace(' ','T')).getTime(),end=new Date(String(value[1]).replace(' ','T')).getTime();const hours=(end-start)/3600000;if(!Number.isFinite(hours)||hours<=0)return 7;return hours<=24?1:hours<=7*24?7:30}
// 切预设时清空自定义区间，避免「预设高亮但数据还是旧自定义区间」。
function onPresetChange(){customRange.value=null;loadOverview()}
function applyCustomRange(value){if(Array.isArray(value)&&value.length===2)days.value=rangeDays(value);loadOverview()}
function windowQuery(){return customRangeActive.value?{startTime:customRange.value[0],endTime:customRange.value[1]}:{}}
// 指标基线四张卡：主数值=平台全量，备注=本屏授权范围。只显示范围数会把「1 大类 / 12 指标」误读成平台底数。
const cards=computed(()=>{const c=overview.value?.counts||{},pc=overview.value?.platformCounts||{};const platform=k=>Number(pc[k]??c[k]??0);return[{label:'接入系统',value:platform('system'),note:`本屏可见 ${c.system||0}`},{label:'指标大类',value:platform('category'),note:`本屏可见 ${c.category||0}`},{label:'指标小类',value:platform('subcategory'),note:`本屏可见 ${c.subcategory||0}`},{label:'指标数量',value:platform('metric'),note:`本屏可见 ${c.metric||0}`},{label:'待处理告警',value:overview.value?.alarm?.pending||0,note:'当前积压',kind:'alarm'},{label:'未解决问题',value:overview.value?.issue?.pending||0,note:'当前积压',kind:'issue'},{label:'未闭环工单',value:overview.value?.gongdan?.pending||0,note:'当前积压',kind:'gongdan'}]})
const scoredHealthCards=computed(()=>healthCards.value.filter(item=>!item.exempt))
// 平台全量闭环口径：本屏授权范围可能不含工单/问题所在大类，单独给一行平台累计，避免被读成「平台没有闭环」。
const platformClosureText=computed(()=>{const p=overview.value?.platformClosure;if(!p||p.gongdanCreated==null)return '';const created=Number(p.gongdanCreated||0),closed=Number(p.gongdanClosed||0);const rate=created>0?Math.round(closed*100/created):0;return `平台累计：工单 ${created} 单，已闭环 ${closed}（${rate}%）；待处理告警 ${p.alarmPending??0} · 未解决问题 ${p.issuePending??0}`})
const catalogRows=computed(()=>((overview.value?.catalogMetrics)||[]).filter(x=>{const q=catalogKeyword.value.trim().toLowerCase();return !q||[x.systemName,x.categoryName,x.subCategoryName,x.metricName,x.metricCode,x.metricDefinition].some(v=>String(v||'').toLowerCase().includes(q))})),strategyRows=computed(()=>((overview.value?.strategies)||[]).filter(x=>{const q=strategyKeyword.value.trim().toLowerCase();return !q||[x.metricName,x.strategyName,x.strategyType,x.targetName].some(v=>String(v||'').toLowerCase().includes(q))})),sourceRows=computed(()=>((overview.value?.dataSources)||[]).filter(x=>{const q=sourceKeyword.value.trim().toLowerCase();return !q||[x.systemName,x.categoryName,x.subCategoryName,x.metricName,x.metricCode,x.dataSource].some(v=>String(v||'').toLowerCase().includes(q))})),healthRows=computed(()=>scoredHealthCards.value.filter(match)),dataRows=computed(()=>((overview.value?.dataMetrics)||[]).filter(x=>!x.exempt).filter(match)),healthGroups=computed(()=>groups(showAll.value?healthRows.value:healthRows.value.slice(0,30))),filteredEventRows=computed(()=>eventRows.value.filter(x=>(!eventKeyword.value||`${x.title||''}${x.owner||''}`.includes(eventKeyword.value))&&(!eventState.value||(eventState.value==='done')===!!x.done)))
// 数据来源表：按最近数据时间倒序（默认，最新在前）或正序；只调展示顺序，不重新取数
const sortedSourceRows=computed(()=>sortRowsByTime(sourceRows.value,sourceSort.value))
watch(activeTab,v=>router.replace({query:{...route.query,tab:v}}));watch(filters,()=>showAll.value=isManagerTemplate.value,{deep:true});watch(refreshSeconds,configureAutoRefresh)
const time=v=>v?String(v).replace('T',' ').slice(0,19):'-'
function nodes(type){return(overview.value?.nodes||[]).filter(n=>n.type===type)}function toTree(list){const map=new Map(list.map(x=>[x.key,{...x,children:[]}])),roots=[];for(const x of map.values()){if(map.has(x.parent))map.get(x.parent).children.push(x);else roots.push(x)}return roots}function descendantMatch(n){const q=catalogKeyword.value.toLowerCase();return(overview.value?.nodes||[]).some(x=>x.key.startsWith(`${n.key}/`)&&x.label.toLowerCase().includes(q))}
function metricNode(item){return(overview.value?.nodes||[]).find(n=>n.type==='metric'&&Number(n.id)===Number(item.metricId||item.id))}function match(item){const f=filters.value,n=metricNode(item);return(!f.keyword||String(item.metricName||'').toLowerCase().includes(f.keyword.toLowerCase()))&&(!f.subcategory||item.subCategoryName===f.subcategory)&&(!f.category||Number(n?.categoryId)===Number(f.category))&&(!f.system||Number(n?.systemId)===Number(f.system))}function resetFilters(){filters.value={system:null,category:null,subcategory:null,keyword:''}}function show(name){return layout.value==='page'||section.value===name}function groups(rows){const out={};rows.forEach(x=>{const g=out[x.categoryName]??(out[x.categoryName]={name:x.categoryName,subgroups:{}}),k=x.subCategoryName||'其他',s=g.subgroups[k]??(g.subgroups[k]={name:x.subCategoryName||'',items:[]});s.items.push(x)});return Object.values(out).map(x=>({...x,subgroups:Object.values(x.subgroups)}))}
const levelOf=x=>scoreLevel(x?.healthScore),healthClass=x=>levelOf(x)==='unknown'?'unknown':levelOf(x)==='danger'?'danger':levelOf(x)==='warning'?'warning':'normal',healthTag=x=>levelOf(x)==='unknown'?'info':levelOf(x)==='danger'?'danger':levelOf(x)==='warning'?'warning':'success',healthText=x=>levelOf(x)==='unknown'?'待计算':levelOf(x)==='danger'?'异常':levelOf(x)==='warning'?'关注':'正常'
function spark(t){const v=(t||[]).map(x=>Number(x.healthScore)).filter(Number.isFinite);return v.map((x,i)=>`${i*100/(v.length-1||1)},${29-Math.max(0,Math.min(100,x))*24/100}`).join(' ')}function sparkEnd(t){const p=spark(t).split(' ').at(-1);if(!p)return null;const[x,y]=p.split(',');return{x,y}}function trendNote(t){const v=(t||[]).map(x=>Number(x.healthScore)).filter(Number.isFinite);if(!v.length)return'暂无趋势数据';if(v.length===1)return'首次计算';if(v.every(x=>x===0))return'持续异常';const d=v.at(-1)-v[0];return Math.abs(d)<3?'趋势平稳':d>0?'健康度回升':'健康度下降'}function catalogHealth(id){return healthCards.value.find(x=>Number(x.metricId)===Number(id))||{healthScore:null}}function catalogStatus(id){const x=catalogHealth(id);return x.healthScore==null?'待计算':`${healthText(x)} ${x.healthScore}`}
function scrollContainer(){const page=document.querySelector('.screen-page');return document.fullscreenElement===page?page:document.scrollingElement}function configureAutoRefresh(){if(refreshTimer)window.clearInterval(refreshTimer);refreshTimer=0;if(Number(refreshSeconds.value)>0)refreshTimer=window.setInterval(()=>{if(!loading.value)loadOverview()},Number(refreshSeconds.value)*1000)}
async function reloadViews() {
  if (shareMode.active) {
    // 分享页只有一条只读视图，没有视图列表；viewCode 仅用于子组件传参，
    // 真正的可见范围由分享接口按「已发布专屏的授权范围」解析，前端改不了。
    viewCode.value = 'shared'
    await loadOverview()
    return
  }
  try {
    views.value = await screenApi.list()
    const requested = route.params.viewCode
    const managerView = views.value.find(view => ['开发室经理专屏', '开发室经理'].includes(view.viewName))
    const fallback = managerView || views.value.find(view => Number(view.isDefault) === 1) || views.value[0]
    viewCode.value = requested && views.value.some(view => view.viewCode === requested)
      ? requested : fallback?.viewCode || ''
    await loadOverview()
  } catch (error) {
    ElMessage.error(message(error))
  }
}
watch(() => route.params.viewCode, (code, previous) => {
  if (!code && previous) void reloadViews()
})
function switchView(code){supervisorId.value=null;resetFilters();router.push(`/dashboard-view/${code}`);loadOverview()}function selectManagerSupervisor(id){supervisorId.value=id;loadOverview()}let overviewGeneration = 0
let loadedPanels = new Set()
let pendingPanels = new Set()
let healthLoaded = false
let heavyPanelInFlight = false
let panelLoadQueued = false
let panelLoadTimer = 0
let viewDisposed = false
const metricPanelsLoading = ref(false)

function currentQuery() {
  // 角色（岗位）不再作为整屏过滤条件；角色维度只在「健康度观测」页签的小卡片里使用。
  // 自定义区间时额外带 startTime/endTime；未选则只带 days，与老版本请求一致。
  return { days: days.value, supervisorId: supervisorId.value || undefined, ...windowQuery() }
}

async function loadVisiblePanels() {
  if (viewDisposed || loading.value || !overview.value) return
  const generation = overviewGeneration
  const panel = layout.value === 'page' ? 'all' : section.value
  if (['all', 'catalog', 'data', 'sources', 'strategy'].includes(panel)
      && !loadedPanels.has('all') && !loadedPanels.has(panel) && !pendingPanels.has(panel)) {
    const heavyPanel = ['all', 'data', 'sources'].includes(panel)
    if (heavyPanel && heavyPanelInFlight) {
      panelLoadQueued = true
      return
    }
    pendingPanels.add(panel)
    if (heavyPanel) heavyPanelInFlight = true
    metricPanelsLoading.value = true
    try {
      const data = await screenApi.metricPanel(viewCode.value, { ...currentQuery(), panel })
      if (generation !== overviewGeneration) return
      overview.value = { ...overview.value, ...data }
      if (data.healthCards) {
        healthCards.value = data.healthCards.rows || []
        healthLoaded = true
      }
      loadedPanels.add(panel)
      // Data and source views are two projections of the same observation rows.
      // Mark both loaded when the unified response contains both projections.
      if (data.dataSources && data.dataMetrics) {
        loadedPanels.add('data')
        loadedPanels.add('sources')
      }
    } catch (error) {
      if (generation === overviewGeneration) ElMessage.error(message(error))
    } finally {
      if (generation === overviewGeneration) {
        pendingPanels.delete(panel)
        metricPanelsLoading.value = pendingPanels.size > 0
      }
      if (heavyPanel) heavyPanelInFlight = false
      if (heavyPanel && panelLoadQueued) {
        panelLoadQueued = false
        if (!viewDisposed && !loading.value && overview.value) scheduleVisiblePanels()
      }
    }
  }
  if (isBusinessTemplate.value && show('data') && has('data') && !loadedPanels.has('data') && !loadedPanels.has('all')) return
  const healthVisible = show('data') || show('catalog') || overview.value?.template === 'leader'
  if (generation !== overviewGeneration || !healthVisible || !has('health') || healthLoaded || healthCardsLoading.value) return
  if (heavyPanelInFlight) {
    panelLoadQueued = true
    return
  }
  healthCardsLoading.value = true
  heavyPanelInFlight = true
  try {
    const data = await screenApi.healthCards(viewCode.value, currentQuery())
    if (generation !== overviewGeneration) return
    healthCards.value = data.rows || []
    healthLoaded = true
  } catch (error) {
    if (generation === overviewGeneration) ElMessage.error(message(error))
  } finally {
    if (generation === overviewGeneration) healthCardsLoading.value = false
    heavyPanelInFlight = false
    if (panelLoadQueued) {
      panelLoadQueued = false
      if (!viewDisposed && !loading.value && overview.value) scheduleVisiblePanels()
    }
  }
}

function scheduleVisiblePanels() {
  if (viewDisposed) return
  if (panelLoadTimer) window.clearTimeout(panelLoadTimer)
  panelLoadTimer = window.setTimeout(() => {
    panelLoadTimer = 0
    void loadVisiblePanels()
  }, 140)
}

watch([section, layout], scheduleVisiblePanels)

async function loadOverview() {
  if (!viewCode.value) return
  const generation = ++overviewGeneration
  loading.value = true
  loadedPanels = new Set()
  pendingPanels = new Set()
  healthLoaded = false
  metricPanelsLoading.value = false
  healthCardsLoading.value = false
  healthCards.value = []
  try {
    // 首屏只取概要（includeMetrics=false）：指标明细、健康卡片交给 loadVisiblePanels 按当前页签分块加载。
    // 原逻辑对非 manager/leader 模板会再全量拉一次 metrics，首屏多一次重查询且字段大多用不上，已移除。
    const nextOverview = await screenApi.overview(viewCode.value, { ...currentQuery(), includeMetrics: false })
    if (generation !== overviewGeneration) return
    const container = scrollContainer()
    const scrollTop = container?.scrollTop || 0
    overview.value = nextOverview
    if (nextOverview?.template === 'manager' && layoutViewCode !== viewCode.value) {
      layout.value = 'tabs'
      layoutViewCode = viewCode.value
    } else if (nextOverview?.template !== 'manager' && layoutViewCode !== '__other__') {
      layout.value = 'tabs'
      layoutViewCode = '__other__'
    }
    showAll.value = nextOverview?.template === 'manager'
    await nextTick()
    scrollContainer()?.scrollTo({ top: scrollTop, behavior: 'auto' })
  } catch (error) {
    if (generation === overviewGeneration) ElMessage.error(message(error))
  } finally {
    if (generation === overviewGeneration) {
      loading.value = false
      void loadVisiblePanels()
    }
  }
}
async function loadHealth(n){healthLoading.value=true;try{health.value=await screenApi.health(viewCode.value,{node:n.key,days:days.value,...windowQuery()});await nextTick();const d=health.value.data||[];const old=echarts.getInstanceByDom(healthChart.value);old?.dispose();const c=echarts.init(healthChart.value);c.setOption({xAxis:{type:'category',data:d.map(x=>time(x.recordTime||x.snapshotTime))},yAxis:{type:'value',min:0,max:100},series:[{type:'line',smooth:true,areaStyle:{opacity:.14},data:d.map(x=>x.healthScore)}]});charts.push(c)}catch(e){ElMessage.error(message(e))}finally{healthLoading.value=false}}
function navigateSourceFlow(path) {
  if (path === '/data') { nextTick(() => document.querySelector('.source-summary')?.scrollIntoView({ behavior: 'smooth', block: 'start' })); return }
  if (path === '/report' || path === '/observation') { if (has('data')) section.value = 'data'; return }
  if (has('events')) void openEvents(path.slice(1))
}
async function openMetricDetail(item) {
  selectedMetric.value = item
  metricDetail.value = null
  detailVisible.value = true
  metricDetailLoading.value = true
  try {
    const node = metricNode(item)
    if (!node) return
    metricDetail.value = await screenApi.health(viewCode.value, {
      node: node.key, days: days.value, supervisorId: supervisorId.value || undefined, ...windowQuery()
    })
  } catch (error) {
    ElMessage.error(message(error))
  } finally {
    metricDetailLoading.value = false
  }
}

const eventSpark=t=>spark((t||[]).map(x=>({healthScore:Math.min(100,Number(x.created||0)*10)})));async function drill(x){if(x.kind)await openEvents(x.kind);else{layout.value='tabs';section.value='catalog'}}async function openEvents(kind){if(layout.value==='tabs')section.value='overview';eventKind.value=kind;eventVisible.value=true;eventLoading.value=true;try{eventRows.value=(await screenApi.events(viewCode.value,{kind,days:days.value,supervisorId:supervisorId.value||undefined,...windowQuery()})).rows||[]}catch(e){ElMessage.error(message(e))}finally{eventLoading.value=false}}const loadEventRows=()=>openEvents(eventKind.value)
onMounted(async()=>{viewDisposed=false;window.addEventListener('resize',()=>charts.forEach(c=>c.resize()));
  if(isSharePage){
    // 先用 URL 里的密钥换访客会话，再按正常专屏页流程取数；失败只显示失效提示，不跳登录。
    try{
      const publicId=(window.location.pathname.match(/\/screen\/pc\/s\/([A-Za-z0-9_-]+)/)||[])[1]||'';
      const secretKey='metric-dashboard-share-pc:'+publicId;
      const secret=new URLSearchParams(window.location.hash.slice(1)).get('k')||sessionStorage.getItem(secretKey)||'';
      if(!publicId||!secret)throw new Error('缺少分享密钥');
      await shareMode.open(publicId,secret);
      // 记住密钥：刷新页面时 hash 会保留，但从平台内跳转进来时兜底
      sessionStorage.setItem(secretKey,secret);
      canConfigure.value=false;
      await reloadViews();
    }catch(error){shareError.value=true;loading.value=false;}
    return;
  }
  try{canConfigure.value=canConfigure.value||(await screenApi.capabilities()).canConfigure}catch{}
  await reloadViews();
});onBeforeUnmount(()=>{viewDisposed=true;overviewGeneration++;if(panelLoadTimer)window.clearTimeout(panelLoadTimer);if(refreshTimer)window.clearInterval(refreshTimer);charts.forEach(c=>c.dispose())})
const currentView=computed(()=>views.value.find(v=>v.viewCode===viewCode.value))
</script>
<style scoped>
.screen-page{padding:0 16px 28px;background:var(--ui-page);min-height:100%}
/* PC 分享页是独立路由、没有平台外框，需要自己撑满视口 */
.screen-page.share-page{box-sizing:border-box;min-height:100dvh;padding-top:12px}.toolbar,.actions,.panel header,.supervisor-nav,.health-context,.event-panel header,.metric-detail header{display:flex;align-items:center;justify-content:space-between;gap:14px}.toolbar{margin:10px 0 18px;flex-wrap:wrap}.toolbar h2,.panel h3{margin:0;color:var(--ui-heading)}.toolbar small,.panel header small{color:var(--ui-subtle)}.actions{flex-wrap:wrap}.supervisor-nav{padding:10px 14px;margin-bottom:16px;background:var(--ui-surface);border:1px solid var(--ui-border);border-radius:10px}.section-tabs{flex:1;margin:0 0 -15px 20px}.supervisor-section{margin-bottom:18px}.numbers{display:grid;grid-template-columns:repeat(7,minmax(100px,1fr));gap:12px;margin-bottom:16px}.number{min-height:84px;padding:14px;border-radius:10px;background:var(--ui-surface);border:1px solid var(--ui-border);display:flex;flex-direction:column;gap:5px;cursor:pointer}.number:hover,.event:hover{border-color:#75b5f3;box-shadow:0 5px 14px #3286d21c}.number span,.number small,.health-context small{color:var(--ui-muted);font-size:12px}.number b{font-size:24px;color:var(--ui-heading)}.panel{padding:18px;margin-bottom:16px;border-radius:12px;background:var(--ui-surface);border:1px solid var(--ui-border)}.panel header{margin-bottom:16px}.panel h3{font-size:16px}.catalog-stats{display:flex;gap:28px;padding:11px 14px;margin-bottom:12px;border-radius:8px;background:var(--ui-page);color:var(--ui-muted)}.catalog-stats b{color:var(--ui-primary)}.small-search{width:220px}.metric-filters{display:grid;grid-template-columns:repeat(4,minmax(145px,1fr)) auto;gap:10px;margin-bottom:16px}.compact{grid-template-columns:repeat(3,minmax(160px,1fr))}.date-value{font-size:15px!important}.health-workspace{border-top:1px solid var(--ui-border-soft);padding-top:16px}.risk-label{font-size:12px;color:#b35151;background:var(--ui-danger-soft);border-radius:12px;padding:3px 8px;margin-right:8px}.health-card-groups{display:grid;gap:16px;margin-top:12px}.health-card-group{padding:14px;border-radius:10px;background:var(--ui-page);border:1px solid var(--ui-border)}.health-card-group h4{margin:0 0 10px;color:var(--ui-heading)}.health-card-group h5{margin:13px 0 8px;color:var(--ui-muted);font-size:12px}.health-card-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(205px,1fr));gap:10px}.health-card{cursor:pointer;color:var(--ui-primary);padding:12px;border-radius:10px;background:var(--ui-surface);border:1px solid var(--ui-border);border-left:4px solid currentColor;display:grid;gap:8px;transition:.18s}.health-card:hover,.health-card:focus,.health-card.selected{outline:none;border-color:currentColor;box-shadow:0 5px 16px currentColor;transform:translateY(-1px)}.health-card.warning{color:var(--ui-warning);background:var(--ui-warning-soft)}.health-card.danger{color:var(--ui-danger);background:var(--ui-danger-soft)}.health-card.unknown{color:var(--ui-subtle)}.health-card-top{display:flex;justify-content:space-between;gap:8px}.health-card-top>span{font-weight:650;color:var(--ui-heading)}.health-values{display:grid;grid-template-columns:auto 1fr;align-items:baseline;gap:2px 6px}.health-values b{font-size:22px}.health-values strong{justify-self:end;color:var(--ui-text)}.health-values small,.trend-note{font-size:11px;color:var(--ui-subtle)}.health-card svg{height:38px;width:100%;overflow:visible}.health-card polyline{fill:none;stroke:currentColor;stroke-width:2.4}.health-card circle{fill:var(--ui-surface);stroke:currentColor;stroke-width:1.8}.show-all{margin-top:12px}.metric-detail{margin-top:18px;padding:18px;border:1px solid #b9d9f7;border-radius:10px;background:#fbfdff}.metric-detail h3{display:inline;margin:0 8px 0 0}.metric-detail-grid{display:grid;grid-template-columns:minmax(360px,1.2fr) minmax(280px,.8fr);gap:18px;margin:14px 0}.detail-chart{height:245px}.metric-detail h4{margin:14px 0 8px;color:#41566d}.events{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.event{cursor:pointer;padding:14px;border-radius:10px;border:1px solid var(--ui-border);background:#fbfdff}.event h4{margin:0;color:#52657a}.event>b{font-size:26px;display:block;margin-top:5px}.event>span,.event small{color:#7e8b9a;font-size:12px}.event svg{width:100%;height:34px;color:#409eff;margin:6px 0}.event polyline{fill:none;stroke:currentColor;stroke-width:2.5}.event>div{display:flex;justify-content:space-between}.event-panel{margin-top:16px;padding:16px;border:1px solid #dbe6f2;border-radius:9px;background:#fbfdff}.event-filters{display:flex;gap:12px;margin:14px 0}.event-filters .el-input{max-width:280px}.scope{display:grid;grid-template-columns:minmax(260px,32%) 1fr;gap:16px}.health{min-height:320px}.chart{height:320px}@media(max-width:1000px){.numbers{grid-template-columns:repeat(4,1fr)}.metric-filters{grid-template-columns:repeat(2,1fr)}.metric-detail-grid{grid-template-columns:1fr}}@media(max-width:680px){.numbers,.compact,.events{grid-template-columns:repeat(2,1fr)}.metric-filters{grid-template-columns:1fr}.supervisor-nav{align-items:flex-start;flex-direction:column}.section-tabs{margin:0;width:100%}.scope{grid-template-columns:1fr}}
.health-values{display:flex;align-items:baseline;justify-content:space-between;gap:8px;white-space:nowrap}.health-values small:first-of-type{order:1}.health-values b{order:2;font-size:18px}.health-values small:last-of-type{order:3}.health-values strong{order:4;font-size:14px;justify-self:auto}.health-values small{font-size:11px}
.strategy-summary{display:flex;gap:28px;padding:11px 14px;margin-bottom:14px;border-radius:8px;background:var(--ui-page);color:var(--ui-muted)}.strategy-summary b{color:var(--ui-primary);margin-left:5px}.closure-flow{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:18px}.closure-step{cursor:pointer;padding:18px;border:1px solid #dfeaf5;border-radius:10px;background:#fbfdff;display:grid;gap:5px;transition:.18s}.closure-step:hover{border-color:#70aee9;box-shadow:0 5px 14px #3286d21c}.closure-step span{color:#62758a;font-weight:600}.closure-step b{font-size:28px;color:#283c52}.closure-step small,.closure-step i,.closure-notes small{font-size:12px;color:#8190a1;font-style:normal}.closure-bottom{display:grid;grid-template-columns:minmax(420px,1.3fr) minmax(240px,.7fr);gap:18px}.closure-bottom h4{margin:0 0 12px;color:#41566d}.closure-notes{padding:16px;border-radius:10px;background:#f7fbff;border:1px solid #dfebf6}.closure-notes p{font-size:13px;line-height:1.7;color:#68788a}@media(max-width:800px){.closure-flow,.closure-bottom{grid-template-columns:1fr}.strategy-summary{gap:12px;flex-wrap:wrap}}
.source-summary{display:flex;gap:28px;padding:11px 14px;margin-bottom:14px;border-radius:8px;background:var(--ui-page);color:var(--ui-muted)}.source-summary b{color:var(--ui-primary);margin-left:5px}.source-tools{display:flex;align-items:center;gap:8px;flex:none}@media(max-width:800px){.source-summary{gap:12px;flex-wrap:wrap}}
.toolbar{flex-wrap:nowrap}.title-block{min-width:0;flex:1}.title-line{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.visibility-summary{max-width:min(42vw,560px);padding:4px 10px;border-radius:6px;background:#eef6ff;color:#52718f;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.actions{margin-left:auto;display:flex;align-items:center;gap:10px;flex-wrap:nowrap;justify-content:flex-end;flex-shrink:0}.actions>.el-radio-group{display:flex;flex-wrap:nowrap;white-space:nowrap}.actions>.el-radio-group .el-radio-button{flex:none}.actions>.el-select{width:170px}.actions>.refresh-interval{width:128px}.actions>.layout-switch{flex:none}.manager-reading{flex:none}.actions>.el-button{height:32px;padding:0 16px}@media(max-width:1500px){.toolbar{align-items:flex-start;flex-wrap:wrap}.title-block{flex:1 1 100%}.actions{width:100%;margin-left:0;justify-content:flex-start;flex-wrap:wrap}.actions>.el-select{width:150px}.actions>.refresh-interval{width:128px}.actions>.layout-switch{flex:none}.manager-reading{flex:none}}@media(max-width:900px){.toolbar{align-items:flex-start;flex-wrap:wrap}.actions{width:100%;margin-left:0;justify-content:flex-start;flex-wrap:wrap}.actions>.el-radio-group{max-width:100%;overflow-x:auto}.actions>.el-select{width:170px}.actions>.refresh-interval{width:128px}.actions>.layout-switch{flex:none}.manager-reading{flex:none}}
.screen-page:fullscreen{box-sizing:border-box;width:100%;height:100%;min-height:100dvh;padding:16px 22px 30px;overflow:auto;background:var(--ui-page)}.screen-page:fullscreen>.el-tabs>.el-tabs__header,.screen-page:fullscreen .toolbar{display:none}.screen-page:fullscreen .supervisor-nav,.screen-page:fullscreen :deep(.leader-nav){position:sticky;top:0;z-index:10;box-shadow:0 3px 12px #294d7217}.screen-page:fullscreen :deep(.reading-controls){margin-left:auto}@media(max-width:680px){.screen-page:fullscreen{padding:10px}.screen-page:fullscreen :deep(.reading-controls){margin-left:0}}
.health-card-grid{grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:8px}.health-card{padding:9px;gap:6px}.health-card-top{align-items:flex-start}.health-card-top>span{font-size:12px;line-height:1.3;font-weight:600}.health-card-top :deep(.el-tag){font-size:11px;line-height:18px;height:20px}.health-values b{font-size:16px}.health-values strong{font-size:13px}.health-card svg{height:28px}.health-card .trend-note{font-size:10px}
</style>

<style scoped>
.events-overview { padding:14px 16px; }
.events-overview > header { margin-bottom:10px; }
.events-overview > header small { font-size:11px; }
.events-overview .events { gap:10px; }
.events-overview .event { display:grid; grid-template-columns:auto minmax(0,1fr) minmax(60px,.8fr); align-items:baseline; gap:4px 8px; padding:10px 12px; background:var(--ui-page); }
.events-overview .event h4 { grid-column:1 / 3; grid-row:1; font-size:12px; color:var(--ui-muted); }
.events-overview .event > b { grid-column:1; grid-row:2; margin:0; font-size:24px; line-height:1.2; color:var(--ui-heading); font-variant-numeric:tabular-nums; }
.events-overview .event > span { grid-column:2; grid-row:2; font-size:10px; color:var(--ui-subtle); }
.events-overview .event svg { grid-column:3; grid-row:1 / 3; align-self:center; width:100%; height:28px; margin:0; color:var(--ui-primary); }
.events-overview .event > div { grid-column:1 / -1; gap:8px; padding-top:4px; }
.events-overview .event small { font-size:10px; color:var(--ui-muted); }
@media(max-width:680px) { .events-overview .events { grid-template-columns:1fr; } }
</style>

<style scoped>
/* 自定义时间区间：按钮与预设同高，选中的自定义区间用主题色描边，避免和预设混淆 */
.range-trigger{height:32px;padding:0 12px}
.range-trigger.is-custom{color:var(--ui-primary);border-color:var(--ui-primary);background:var(--ui-primary-soft)}
.range-picker-box{display:grid;gap:8px}
.range-picker{width:100%!important}
.range-picker-box :deep(.el-date-editor){width:100%}
.range-hint{color:var(--ui-subtle);font-size:11px;line-height:1.5}
@media(max-width:900px){.range-trigger{height:32px;padding:0 10px}}
.platform-closure-line{display:block;margin-top:4px;color:var(--ui-muted);font-size:11px;line-height:1.5}
</style>
