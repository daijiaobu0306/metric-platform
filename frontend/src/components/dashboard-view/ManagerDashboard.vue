<template>
  <div class="manager-management">
    <section v-show="show('supervisors')" class="management-section">
      <section class="panel">
        <header><div><h3>主管业务与闭环</h3><small>按各主管自身有效专屏授权计算；业务范围可能重叠，表格数值不可相加</small></div></header>
        <div class="summary-strip"><span>关联主管 <b>{{ overview?.supervisors?.length || 0 }}</b></span><span>当前查看 <b>{{ selectedName }}</b></span><span>科室超期问题 <b>{{ overview?.issue?.timeliness?.overdue ?? '--' }}</b></span></div>
        <el-table v-if="has('events')" :data="overview?.supervisorSummary || []" stripe border empty-text="当前主管没有可统计的业务数据"><el-table-column label="业务主管" min-width="145"><template #default="{row}">{{ row.displayName || row.username }}</template></el-table-column><el-table-column prop="metricCount" label="可见指标" width="100"/><el-table-column prop="alarmPending" label="待处理告警" width="110"/><el-table-column prop="issuePending" label="未解决问题" width="110"/><el-table-column label="超期问题" width="100"><template #default="{row}"><el-tag :type="Number(row.issueOverdue) ? 'danger' : 'success'">{{ row.issueOverdue || 0 }}</el-tag></template></el-table-column><el-table-column prop="gongdanPending" label="未闭环工单" width="110"/><el-table-column label="期间闭环率" width="110"><template #default="{row}">{{ Number(row.gongdanCreated) ? `${rate(row.gongdanClosed, row.gongdanCreated)}%` : '--' }}</template></el-table-column><el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="$emit('select-supervisor', row.id)">聚焦</el-button></template></el-table-column></el-table>
        <el-empty v-else description="关联主管尚未开放待办模块，无法生成业务闭环对比"/>
      </section>
      <div v-if="has('events')" class="closure-grid"><section class="panel"><header><div><h3>闭环效率</h3><small>当前筛选范围；无可计算记录时显示“--”</small></div></header><div class="rates"><div><span>工单闭环率</span><b>{{ overview?.gongdan?.closure?.created ? `${closureRate}%` : '--' }}</b><el-progress v-if="overview?.gongdan?.closure?.created" :percentage="closureRate" :stroke-width="10"/></div><div><span>问题按期解决率</span><b>{{ overview?.issue?.timeliness?.due ? `${issueOnTimeRate}%` : '--' }}</b><el-progress v-if="overview?.issue?.timeliness?.due" :percentage="issueOnTimeRate" :stroke-width="10"/></div></div></section><section class="panel"><header><div><h3>问题责任分布</h3><small>按超期与未解决数量排序</small></div></header><el-table :data="overview?.responsibility || []" max-height="300" empty-text="暂无问题责任记录"><el-table-column prop="person" label="责任人" min-width="130"/><el-table-column prop="pending" label="未解决" width="86"/><el-table-column prop="overdue" label="超期" width="75"/></el-table></section></div>
    </section>
    <section v-show="show('team')" class="management-section"><section class="panel"><header><div><h3>团队使用与协同</h3><small>账号登录与操作记录仅作为平台使用参考，不等同于业务处理绩效</small></div></header><el-table v-if="has('team')" :data="overview?.team || []" stripe border empty-text="暂无关联主管使用记录"><el-table-column label="业务主管" min-width="145"><template #default="{row}">{{ row.displayName || row.username }}</template></el-table-column><el-table-column label="期间登录" width="105"><template #default="{row}">{{ row.usage?.sessions || 0 }}</template></el-table-column><el-table-column label="期间操作" width="105"><template #default="{row}">{{ row.operations || 0 }}</template></el-table-column><el-table-column label="最近登录" min-width="170"><template #default="{row}">{{ time(row.lastLogin) }}</template></el-table-column><el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="primary" @click="$emit('select-supervisor', row.id)">聚焦</el-button></template></el-table-column></el-table><el-empty v-else description="此视图未开放团队使用模块"/></section></section>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ overview: Object, section: String, layout: String, supervisorId: [Number, String] })
defineEmits(['select-supervisor'])
const has = name => props.overview?.modules?.includes(name)
const show = name => props.layout === 'page' || props.section === name
const time = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'
const rate = (done, total) => Number(total) > 0 ? Math.min(100, Math.round(Number(done || 0) * 100 / Number(total))) : 0
const closureRate = computed(() => rate(props.overview?.gongdan?.closure?.closed, props.overview?.gongdan?.closure?.created))
const issueOnTimeRate = computed(() => rate(props.overview?.issue?.timeliness?.onTime, props.overview?.issue?.timeliness?.due))
const selectedName = computed(() => { if (!props.supervisorId) return '全部主管'; const person = (props.overview?.supervisors || []).find(x => Number(x.id) === Number(props.supervisorId)); return person?.displayName || person?.username || '所选主管' })
</script>

<style scoped>
.management-section{margin-bottom:18px}.panel{padding:18px;margin-bottom:16px;border:1px solid var(--ui-border);border-radius:12px;background:var(--ui-surface)}.panel header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.panel h3{margin:0;color:var(--ui-heading);font-size:16px}.panel header small{color:var(--ui-subtle)}.summary-strip{display:flex;gap:26px;flex-wrap:wrap;padding:11px 14px;margin-bottom:14px;background:var(--ui-page);border-radius:8px;color:var(--ui-muted)}.summary-strip b{color:var(--ui-primary);margin-left:5px}.closure-grid{display:grid;grid-template-columns:minmax(280px,.7fr) minmax(0,1.3fr);gap:16px}.rates{display:grid;gap:20px}.rates div{display:grid;gap:6px}.rates span{font-size:12px;color:#758397}.rates b{font-size:24px;color:var(--ui-heading)}@media(max-width:850px){.closure-grid{grid-template-columns:1fr}.panel header{align-items:flex-start;flex-direction:column}}
</style>
