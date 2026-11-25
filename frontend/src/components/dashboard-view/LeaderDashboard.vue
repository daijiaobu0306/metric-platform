<template>
  <div class="leader-screen" :class="{ compact, 'share-leader': activeSection }">
    <div v-if="!activeSection" class="leader-nav">
      <el-radio-group v-model="layout" size="small"><el-radio-button value="tabs">分栏查看</el-radio-button><el-radio-button value="page">一页全览</el-radio-button></el-radio-group>
      <el-tabs v-if="layout==='tabs'" v-model="section" class="leader-tabs workspace-tabs"><el-tab-pane label="整体态势" name="overview"><template #label><WorkspaceTabLabel icon="DataAnalysis">整体态势</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane label="重点风险" name="risk"><template #label><WorkspaceTabLabel icon="Warning">重点风险</WorkspaceTabLabel></template></el-tab-pane><el-tab-pane label="闭环成效" name="outcome"><template #label><WorkspaceTabLabel icon="CircleCheck">闭环成效</WorkspaceTabLabel></template></el-tab-pane></el-tabs>
      <span v-else>按态势、风险、成效顺序展开</span><ScreenReadingControls v-if="layout==='page'"/>
    </div>

    <section v-show="show('overview')" class="leader-section">
      <div v-if="showHealth" class="lead"><div><span>运营态势</span><h3>{{ healthCardsLoading ? '健康数据加载中' : healthCardsError ? '健康数据暂不可用' : statusTitle }}</h3><p>{{ healthCardsLoading ? '基础概览已就绪，正在读取指标健康度' : healthCardsError ? '可在重点风险栏目重试。' : statusDescription }}</p></div><div class="lead-score"><small>风险指标占比</small><b>{{ (healthCardsLoading || healthCardsError) ? '—' : `${riskRate}%` }}</b><span>{{ healthCardsLoading ? '健康数据加载中' : `${healthCards.length} 项已纳入观察` }}</span></div></div>
      <div class="leader-numbers">
        <article class="leader-number"><span>覆盖系统</span><b>{{ overview?.counts?.system || 0 }}</b><small>当前授权范围</small></article><article class="leader-number"><span>覆盖指标</span><b>{{ overview?.counts?.metric || 0 }}</b><small>当前授权范围</small></article><article v-if="showHealth" class="leader-number warning" @click="go('risk')" tabindex="0" @keyup.enter="go('risk')"><span>关注指标</span><b>{{ (healthCardsLoading || healthCardsError) ? '—' : warningCount }}</b><small>健康度 70–89</small></article><article v-if="showHealth" class="leader-number danger" @click="go('risk')" tabindex="0" @keyup.enter="go('risk')"><span>异常指标</span><b>{{ (healthCardsLoading || healthCardsError) ? '—' : dangerCount }}</b><small>健康度低于 70</small></article><article v-if="has('events')" class="leader-number danger" @click="go('outcome')" tabindex="0" @keyup.enter="go('outcome')"><span>超期问题</span><b>{{ overview?.issue?.timeliness?.overdue ?? 0 }}</b><small>当前范围内</small></article><article v-if="has('events')" class="leader-number" @click="go('outcome')" tabindex="0" @keyup.enter="go('outcome')"><span>待闭环事项</span><b>{{ pendingTotal }}</b><small>告警、问题、工单</small></article></div>
      <div class="leader-grid"><section v-if="showHealth" class="leader-panel" v-loading="healthCardsLoading"><header><div><h3>健康度分布</h3><small>依据每项指标最新健康度</small></div></header><div class="distribution"><button v-for="item in distribution" :key="item.label" :class="item.class" @click="go('risk')"><b>{{ healthCardsError ? '—' : item.value }}</b><span>{{ item.label }}</span></button></div><small class="note">待计算指标 {{ healthCardsError ? '—' : unknownCount }} 项，不计入风险占比。</small></section><section v-if="has('events')" class="leader-panel"><header><div><h3>待决事项</h3><small>优先关注积压和时效风险</small></div></header><div class="decisions"><div v-for="item in decisions" :key="item.label"><span>{{ item.label }}</span><b>{{ item.value }}</b><small>{{ item.note }}</small></div></div></section></div>
    </section>

    <section v-show="show('risk')" v-loading="healthCardsLoading" class="leader-section"><section class="leader-panel"><header><div><h3>重点风险</h3><small>按健康度从低到高排列；点击指标查看历史趋势与最近值</small></div><el-input v-model="keyword" clearable class="risk-search" placeholder="搜索指标名称"/></header><ShareMobileList v-if="compact && has('health')" :rows="riskRows" :fields="riskFields" :preview-fields="['健康度', '最新值']" :on-detail="row => emit('metric-detail', row)" /><el-table v-else-if="has('health')" :data="riskRows" stripe border max-height="560" empty-text="当前没有已计算的风险指标"><el-table-column label="风险级别" width="100"><template #default="{row}"><el-tag :type="tag(row)">{{ text(row) }}</el-tag></template></el-table-column><el-table-column prop="systemName" label="系统" width="130"/><el-table-column prop="categoryName" label="指标大类" width="140"/><el-table-column prop="metricName" label="指标" min-width="210" show-overflow-tooltip/><el-table-column label="健康度" width="100"><template #default="{row}"><b>{{ row.healthScore }}</b></template></el-table-column><el-table-column label="最新值" width="120"><template #default="{row}">{{ row.latestValue ?? '--' }} {{ row.latestValue != null ? row.unit : '' }}</template></el-table-column><el-table-column label="趋势" min-width="140"><template #default="{row}"><svg class="risk-spark" viewBox="0 0 100 30" preserveAspectRatio="none"><polyline :points="spark(row.trend)"/></svg></template></el-table-column><el-table-column label="查看" width="85" fixed="right"><template #default="{row}"><el-button link type="primary" @click="$emit('metric-detail', row)">详情</el-button></template></el-table-column></el-table><el-empty v-else description="此视图未开放健康度模块"/></section></section>

    <section v-show="show('outcome')" class="leader-section"><div class="outcome-grid"><section v-if="has('events')" class="leader-panel"><header><div><h3>闭环成效</h3><small>当前时间范围内新建事项的处理结果</small></div></header><div class="outcomes"><div><span>工单闭环率</span><b>{{ overview?.gongdan?.closure?.created ? `${closureRate}%` : '--' }}</b><el-progress v-if="overview?.gongdan?.closure?.created" :percentage="closureRate" :stroke-width="10"/></div><div><span>问题按期解决率</span><b>{{ overview?.issue?.timeliness?.due ? `${onTimeRate}%` : '--' }}</b><el-progress v-if="overview?.issue?.timeliness?.due" :percentage="onTimeRate" :stroke-width="10"/></div></div><small class="note">无可计算记录时显示“--”。</small></section><section v-if="has('events')" class="leader-panel"><header><div><h3>问题责任分布</h3><small>定位积压和超期事项的责任归属</small></div></header><ShareMobileList v-if="compact" :rows="overview?.responsibility || []" :fields="responsibilityFields" /><el-table v-else :data="overview?.responsibility || []" max-height="220" empty-text="暂无问题责任记录"><el-table-column prop="person" label="责任人" min-width="160"/><el-table-column prop="total" label="问题数" width="95"/><el-table-column prop="pending" label="未解决" width="95"/><el-table-column prop="overdue" label="超期" width="95"/></el-table></section><section v-if="has('team')" class="leader-panel" :class="{ 'outcome-team-wide': has('events') }"><header><div><h3>团队使用概况</h3><small>仅作为平台协同情况参考</small></div></header><ShareMobileList v-if="compact" :rows="overview?.team || []" :fields="teamFields" /><el-table v-else :data="overview?.team || []" max-height="280" empty-text="暂无团队使用记录"><el-table-column label="人员" min-width="130"><template #default="{row}">{{ row.displayName || row.username }}</template></el-table-column><el-table-column label="期间登录" width="100"><template #default="{row}">{{ row.usage?.sessions || 0 }}</template></el-table-column><el-table-column label="期间操作" width="100"><template #default="{row}">{{ row.operations || 0 }}</template></el-table-column></el-table></section></div></section>
  </div>
</template>

<script setup>
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import { computed, ref } from 'vue'
import ShareMobileList from './ShareMobileList.vue'
import ScreenReadingControls from './ScreenReadingControls.vue'
import { ensureHealthThresholds, scoreLevel } from '@/utils/healthThresholds'
ensureHealthThresholds()
const props=defineProps({overview:Object,healthCards:{type:Array,default:()=>[]},healthCardsLoading:Boolean, healthCardsError:Boolean, healthAvailable:{type:Boolean,default:true}, activeSection:String, compact:Boolean})
const emit = defineEmits(['metric-detail', 'select-section'])
const layout=ref('tabs'),section=ref('overview'),keyword=ref('')
const has = name => props.overview?.modules?.includes(name)
const showHealth = computed(() => !props.activeSection || props.healthAvailable)
const show = name => props.activeSection ? props.activeSection === name : layout.value === 'page' || section.value === name
function go (name) {
  if (props.activeSection) {
    emit('select-section', name)
    return
  }
  layout.value = 'tabs'
  section.value = name
}
const riskFields = [
  { label: '指标', value: 'metricName' },
  { label: '风险级别', value: row => text(row) },
  { label: '系统', value: 'systemName' },
  { label: '指标大类', value: 'categoryName' },
  { label: '健康度', value: 'healthScore' },
  { label: '最新值', value: row => row.latestValue == null ? '—' : `${row.latestValue} ${row.unit || ''}`.trim() }
]
const responsibilityFields = [
  { label: '责任人', value: 'person' },
  { label: '问题数', value: 'total' },
  { label: '未解决', value: 'pending' },
  { label: '超期', value: 'overdue' }
]
const teamFields = [
  { label: '人员', value: row => row.displayName || row.username },
  { label: '期间登录', value: row => row.usage?.sessions ?? 0 },
  { label: '期间操作', value: 'operations' }
]
const known=computed(()=>props.healthCards.filter(x=>x.healthScore!=null)),dangerCount=computed(()=>known.value.filter(x=>scoreLevel(x.healthScore)==='danger').length),warningCount=computed(()=>known.value.filter(x=>scoreLevel(x.healthScore)==='warning').length),unknownCount=computed(()=>props.healthCards.length-known.value.length),riskRate=computed(()=>known.value.length?Math.round((dangerCount.value+warningCount.value)*100/known.value.length):0)
const pendingTotal=computed(()=>['alarm','issue','gongdan'].reduce((n,key)=>n+Number(props.overview?.[key]?.pending||0),0)),closureRate=computed(()=>rate(props.overview?.gongdan?.closure?.closed,props.overview?.gongdan?.closure?.created)),onTimeRate=computed(()=>rate(props.overview?.issue?.timeliness?.onTime,props.overview?.issue?.timeliness?.due))
const statusTitle=computed(()=>dangerCount.value?`当前有 ${dangerCount.value} 项指标需要优先处置`:warningCount.value?`当前有 ${warningCount.value} 项指标需要持续关注`:'当前已计算指标整体处于稳定状态'),statusDescription=computed(()=>dangerCount.value?'请优先查看异常指标和超期问题，明确责任与完成时限。':warningCount.value?'建议关注趋势变化，提前安排业务核查。':'仍建议按周期查看闭环事项与新出现的风险。')
const distribution=computed(()=>[{label:'正常',value:known.value.length-dangerCount.value-warningCount.value,class:'normal'},{label:'关注',value:warningCount.value,class:'warning'},{label:'异常',value:dangerCount.value,class:'danger'}]),decisions=computed(()=>[{label:'待处理告警',value:props.overview?.alarm?.pending??0,note:'优先核查影响范围'},{label:'未解决问题',value:props.overview?.issue?.pending??0,note:'关注责任与时限'},{label:'未闭环工单',value:props.overview?.gongdan?.pending??0,note:'跟进处理进度'}])
const riskRows=computed(()=>known.value.filter(x=>scoreLevel(x.healthScore)!=='normal'&&(!keyword.value||String(x.metricName||'').toLowerCase().includes(keyword.value.toLowerCase()))).sort((a,b)=>Number(a.healthScore)-Number(b.healthScore)))
const rate=(done,total)=>Number(total)>0?Math.round(Number(done||0)*100/Number(total)):0
const tag=row=>scoreLevel(row.healthScore)==='danger'?'danger':'warning',text=row=>scoreLevel(row.healthScore)==='danger'?'异常':'关注'
function spark(trend){const values=(trend||[]).filter(x=>x.healthScore!=null).map(x=>Number(x.healthScore)).filter(Number.isFinite);return values.map((v,i)=>`${i*100/(values.length-1||1)},${27-Math.max(0,Math.min(100,v))*23/100}`).join(' ')}
</script>

<style scoped>
.leader-nav,.leader-panel,.leader-number,.lead{background:var(--ui-surface);border:1px solid var(--ui-border);border-radius:10px}.leader-nav{display:flex;align-items:center;gap:16px;padding:10px 14px;margin-bottom:16px}.leader-tabs{flex:1;margin-bottom:-15px}.leader-nav>span,.note{font-size:12px;color:var(--ui-subtle)}.leader-section{margin-bottom:18px}.lead{display:flex;justify-content:space-between;gap:20px;padding:22px;margin-bottom:16px;background:#fbfdff}.lead>div>span{font-size:12px;color:#5787b3}.lead h3{margin:7px 0;color:var(--ui-heading);font-size:20px}.lead p{margin:0;color:var(--ui-muted);font-size:13px}.lead-score{min-width:150px;display:grid;align-content:center;gap:4px;text-align:right}.lead-score small,.lead-score span{font-size:12px;color:var(--ui-subtle)}.lead-score b{font-size:30px;color:#c97934}.leader-numbers{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:16px}.leader-number{display:grid;gap:5px;padding:14px;min-height:85px}.leader-number[tabindex]{cursor:pointer}.leader-number span,.outcomes span{font-size:12px;color:#758397}.leader-number b{font-size:25px;color:var(--ui-heading)}.leader-number small{font-size:12px;color:var(--ui-subtle)}.leader-number.warning b{color:#c97934}.leader-number.danger b{color:#c45a5a}.leader-number[tabindex]:hover{border-color:#75b5f3;box-shadow:0 5px 14px #3286d21c}.leader-number:focus-visible{outline:2px solid #409eff;outline-offset:2px}.leader-grid,.outcome-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.leader-panel{padding:18px;margin-bottom:16px}.leader-panel header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.leader-panel h3{margin:0;font-size:16px;color:var(--ui-heading)}.leader-panel header small{color:var(--ui-subtle)}.distribution,.decisions{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.distribution button{border:1px solid var(--ui-border);border-radius:8px;background:#fbfdff;padding:13px;text-align:left;cursor:pointer;display:grid;gap:5px}.distribution b{font-size:24px}.distribution span,.decisions span,.decisions small{font-size:12px;color:#7d8b9a}.distribution.warning b{color:#c97934}.distribution.danger b{color:#c45a5a}.distribution.normal b{color:#4e9a72}.decisions>div{padding:12px;border-left:3px solid #b8cee2;background:#fbfdff;display:grid;gap:4px}.decisions b{font-size:24px}.risk-search{width:220px}.risk-spark{width:100%;height:28px;color:#5b93c7}.risk-spark polyline{fill:none;stroke:currentColor;stroke-width:2.4}.outcomes{display:grid;gap:20px}.outcomes>div{display:grid;gap:6px}.outcomes b{font-size:25px}.outcomes span{color:#758397}@media(max-width:900px){.leader-numbers{grid-template-columns:repeat(2,1fr)}.leader-grid,.outcome-grid{grid-template-columns:1fr}}@media(max-width:680px){.leader-nav,.lead,.leader-panel header{align-items:flex-start;flex-direction:column}.leader-tabs{width:100%}.leader-numbers,.leader-grid,.outcome-grid{grid-template-columns:1fr}.distribution,.decisions{grid-template-columns:1fr}.lead-score{text-align:left}.risk-search{width:100%}}
</style>

<style scoped>
.outcome-grid { align-items:stretch; gap:16px; }
.outcome-grid > .leader-panel { min-width:0; margin-bottom:0; padding:18px 20px; }
.outcome-grid > .leader-panel header { margin-bottom:12px; }
.outcome-team-wide { grid-column:1 / -1; }
@media(max-width:680px) { .outcome-grid > .leader-panel { padding:16px; } }
.share-leader {
  --ui-surface: #fff;
  --ui-border: var(--metric-border);
  --ui-heading: var(--metric-text);
  --ui-muted: var(--metric-text-secondary);
  --ui-subtle: var(--metric-text-muted);
}
.compact .lead { flex-direction: row; gap: 8px; padding: 10px; margin-bottom: 6px; }
.compact .lead h3 { font-size: 14px; }
.compact .lead p { font-size: 11px; }
.compact .lead-score { min-width: 88px; text-align: right; }
.compact .lead-score b { font-size: 20px; }
.compact .leader-numbers, .compact .distribution, .compact .decisions { grid-template-columns: repeat(3,minmax(0,1fr)); gap: 4px; margin-bottom: 6px; }
.compact .leader-number, .compact .distribution button, .compact .decisions > div { min-height: 0; padding: 6px; gap: 2px; }
.compact .leader-number b, .compact .distribution b, .compact .decisions b, .compact .outcomes b { font-size: 17px; }
.compact .leader-number small, .compact .decisions small { font-size: 10px; }
.compact .leader-grid, .compact .outcome-grid { gap: 6px; }
.compact .leader-panel, .compact .outcome-grid > .leader-panel { padding: 8px 10px; margin-bottom: 0; }
.compact .leader-panel header { gap: 6px; margin-bottom: 6px; }
.compact .leader-panel h3 { font-size: 13px; }
.compact .outcomes { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; }
</style>
