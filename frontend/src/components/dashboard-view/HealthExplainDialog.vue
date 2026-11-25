<template>
  <el-dialog class="workspace-theme dashboard-workspace" :model-value="modelValue" :title="`健康度计算说明 - ${item?.metricName || '指标'}`" width="min(920px, 94vw)" top="7vh" @update:model-value="value => emit('update:modelValue', value)">
    <template v-if="item">
      <div class="explain-head">
        <div>
          <div class="explain-title"><h3>{{ item.metricName }}</h3><StatusTag :status="statusKey" :label="statusLabel" /></div>
          <p>{{ item.metricCode || definition?.code || '-' }} · {{ item.categoryName }} / {{ item.subCategoryName }} · 最近计算 {{ time(trace?.record_time || item.recordTime) }}</p>
        </div>
        <div class="explain-score" :class="statusKey"><b>{{ item.healthScore ?? '--' }}</b><small>健康度</small></div>
      </div>

      <!-- 2026-11-17 P4：解释弹窗改为直接读后端 score_trace（算分过程快照），
           前端不再复刻三套公式。找不到新算法记录时退回通用说明，不伪造参数。 -->
      <template v-if="trace && trace.found">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="trace-mode"
          :title="modeTitle"
          :description="modeDescription"
        />

        <div class="explain-grid">
          <section class="explain-card">
            <h4>计算输入</h4>
            <dl>
              <div><dt>指标当前值</dt><dd>{{ inputValue }} {{ unit }}</dd></div>
              <div><dt>数据来源</dt><dd><el-tag size="small" :type="dataOriginType">{{ dataOriginText }}</el-tag></dd></div>
              <div><dt>数据时间</dt><dd>{{ time(trace.data_time) }}</dd></div>
              <div><dt>规则版本</dt><dd>v{{ trace.rule_version ?? '-' }}</dd></div>
            </dl>
          </section>

          <section class="explain-card">
            <h4>计算结果</h4>
            <dl>
              <div><dt>健康度</dt><dd><b :class="statusKey">{{ item.healthScore ?? trace.health_score ?? '--' }}</b> 分</dd></div>
              <div><dt>状态</dt><dd>{{ statusLabel }}</dd></div>
              <div><dt>使用的基线</dt><dd>{{ baselineText }}</dd></div>
              <div><dt>偏离度</dt><dd>{{ deviationText }}</dd></div>
            </dl>
          </section>
        </div>

        <section class="explain-card algorithm-card">
          <h4>使用的算法 <span>{{ modeTitle }}</span></h4>
          <p class="formula">{{ traceMap.formula || '—' }}</p>
          <ol class="steps">
            <li v-for="(step, index) in traceSteps" :key="index">{{ step }}</li>
          </ol>
          <p class="algorithm-note">{{ traceMap.note || '' }}</p>
        </section>

        <section class="explain-card" v-if="traceMap.sampleCount">
          <h4>统计参数</h4>
          <div class="factor-list">
            <span>样本数 <b>{{ traceMap.sampleCount }}</b></span>
            <span v-if="traceMap.windowDays">窗口 <b>{{ traceMap.windowDays }} 天</b></span>
            <span v-if="traceMap.median != null">中位数 <b>{{ traceMap.median }}</b></span>
            <span v-if="traceMap.mad != null">MAD <b>{{ traceMap.mad }}</b></span>
            <span v-if="traceMap.sigma != null">σ̂ <b>{{ traceMap.sigma }}</b></span>
            <span v-if="traceMap.z != null">z 值 <b>{{ traceMap.z }}</b></span>
            <span v-if="traceMap.sampleFrom">样本来源 <b>{{ traceMap.sampleFrom === 'collected' ? '真实采集' : '真实采集+样例数据' }}</b></span>
          </div>
        </section>
      </template>

      <template v-else>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="该指标还没有新算法的算分记录"
          description="可能是“不计分 / 基线学习中 / 缺数”（这几种状态不写健康度行，避免拿 0 分冒充异常）。可在指标管理的「健康度规则配置」里查看它的规则。"
        />
        <div class="explain-grid" style="margin-top: 10px">
          <section class="explain-card">
            <h4>计算输入</h4>
            <dl>
              <div><dt>指标当前值</dt><dd>{{ item.latestValue ?? '--' }}{{ definition?.unit ? ` ${definition.unit}` : '' }}</dd></div>
              <div><dt>指标小类</dt><dd>{{ item.subCategoryName || '-' }}</dd></div>
              <div><dt>最近计算</dt><dd>{{ time(item.recordTime) }}</dd></div>
            </dl>
          </section>
          <section class="explain-card">
            <h4>为什么会没有分数</h4>
            <dl>
              <div><dt>不计分</dt><dd>白名单指标只观察不评分</dd></div>
              <div><dt>基线学习中</dt><dd>样本不够，不出分也不给 0 分</dd></div>
              <div><dt>缺数</dt><dd>指标值未采集或已停更</dd></div>
            </dl>
          </section>
        </div>
      </template>

      <section class="explain-card" v-if="item.trend?.length">
        <h4>最近健康度记录</h4>
        <div class="health-trend"><div class="trend-label">最近 {{ item.trend.length }} 个记录点</div><SvgLineChart :points="item.trend" /><div class="trend-note">{{ trendNote }}</div></div>
      </section>
    </template>

    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import SvgLineChart from './ui/SvgLineChart.vue'
import StatusTag from './ui/StatusTag.vue'
import { healthRuleApi } from '@/api'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  item: { type: Object, default: () => null },
  definition: { type: Object, default: () => null }
})
const emit = defineEmits(['update:modelValue'])

const trace = ref(null)
const statusKey = computed(() => props.item?.healthScore == null ? 'unknown'
  : Number(props.item.healthScore) < 60 ? 'danger'
    : Number(props.item.healthScore) < 80 ? 'warning' : 'normal')
const statusLabel = computed(() => statusKey.value === 'danger' ? '异常' : statusKey.value === 'warning' ? '关注' : statusKey.value === 'unknown' ? '待计算' : '正常')

const traceMap = computed(() => {
  if (!trace.value?.score_trace) return {}
  try {
    return typeof trace.value.score_trace === 'string' ? JSON.parse(trace.value.score_trace) : trace.value.score_trace
  } catch (e) {
    return {}
  }
})
const inputValue = computed(() => trace.value?.current_value ?? props.item?.latestValue ?? '--')
const unit = computed(() => props.definition?.unit || '')
const dataOriginText = computed(() => {
  const origin = trace.value?.data_origin
  if (origin === 'mock') return '样例数据'
  if (origin === 'collected') return '真实采集'
  if (origin === 'import') return '导入'
  return origin || '未知'
})
const dataOriginType = computed(() => (trace.value?.data_origin === 'mock' ? 'warning' : 'success'))

const modeTitle = computed(() => {
  const mode = traceMap.value.mode || trace.value?.mode
  if (mode === 'manual') return '人工基线扣分'
  if (mode === 'exempt') return '不计分（白名单）'
  return '动态基线（中位数 + MAD 稳健偏离度）'
})
const modeDescription = computed(() => {
  const mode = traceMap.value.mode || trace.value?.mode
  if (mode === 'manual') return '按人工配置的基线值扣分：值落在基线（含宽容区）内不扣分，越接近“扣到 0 的值”分越低。'
  if (mode === 'exempt') return '该指标被标记为不计分：只观察、不评分、不参与上层聚合；指标值告警照常生效。'
  return '按近 90 天历史值的中位数判断偏离：正常波动不扣分，只有明显变差才按偏离程度扣分。'
})
const traceSteps = computed(() => {
  const steps = traceMap.value.steps
  return Array.isArray(steps) ? steps : (steps ? [String(steps)] : [])
})
const baselineText = computed(() => {
  if (trace.value?.baseline_used != null) {
    return `${trace.value.baseline_used}${unit.value ? ' ' + unit.value : ''}${traceMap.value.mode === 'dynamic' ? '（历史中位数）' : '（人工基线）'}`
  }
  return '未使用基线'
})
const deviationText = computed(() => {
  if (trace.value?.deviation == null) return '--'
  if (traceMap.value.mode === 'dynamic') return `z = ${trace.value.deviation}（稳健标准差倍数）`
  return `${trace.value.deviation}${unit.value ? ' ' + unit.value : ''}（相对基线的偏离）`
})

const trendNote = computed(() => {
  const values = (props.item?.trend || []).map(point => Number(point.healthScore)).filter(Number.isFinite)
  if (!values.length) return '暂无历史健康度记录'
  if (values.length === 1) return '仅有一条健康度记录'
  const delta = values.at(-1) - values[0]
  return `最近 ${values.length} 个记录点，变化 ${delta > 0 ? '+' : ''}${delta} 分`
})

function time (value) {
  if (!value) return '--'
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

async function loadTrace () {
  trace.value = null
  const metricId = props.item?.metricId
  if (!metricId) return
  // 分享页（匿名只读）没有平台登录态，规则溯源接口要鉴权；
  // 这里直接跳过，避免 401 被全局拦截器当成会话失效把访客踢走。
  if (!sessionStorage.getItem('sessionKey')) return
  try {
    const res = await healthRuleApi.metricTrace(metricId)
    trace.value = res.data || null
  } catch (e) {
    trace.value = null
  }
}

watch(() => [props.modelValue, props.item?.metricId], () => {
  if (props.modelValue) loadTrace()
}, { immediate: true })
</script>

<style scoped>
.explain-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 12px; margin-bottom: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--metric-surface-soft); }
.explain-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.explain-title h3 { margin: 0; color: var(--metric-text); font-size: 15px; }
.explain-head p { margin: 5px 0 0; color: var(--metric-text-muted); font-size: 10px; }
.explain-score { display: grid; place-items: center; align-content: center; width: 82px; height: 82px; flex: none; border-radius: 50%; background: var(--ui-surface); }
.explain-score b { font-family: var(--metric-font-mono); font-size: 28px; line-height: 1; }
.explain-score small { margin-top: 3px; color: var(--metric-text-muted); font-size: 9px; }
.explain-score.normal b { color: var(--metric-success); }
.explain-score.warning b { color: var(--metric-warning); }
.explain-score.danger b { color: var(--metric-danger); }
.explain-score.unknown b { color: var(--metric-text-muted); }
.trace-mode { margin-bottom: 10px; }
.explain-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.explain-card { padding: 11px 12px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.explain-card h4 { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0 0 8px; color: var(--metric-text); font-size: 12px; }
.explain-card h4 span { color: var(--metric-primary-hover); font-size: 10px; font-weight: 500; }
.explain-card dl { display: grid; gap: 7px; margin: 0; }
.explain-card dl > div { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.explain-card dt { color: var(--metric-text-muted); font-size: 10px; }
.explain-card dd { margin: 0; color: var(--metric-text); font-size: 11px; text-align: right; }
.explain-card dd b.danger { color: var(--metric-danger); }
.explain-card dd b.warning { color: var(--metric-warning); }
.explain-card dd b.normal { color: var(--metric-success); }
.algorithm-card { margin-bottom: 10px; }
.formula { padding: 8px 10px; border-radius: 7px; background: var(--metric-surface-soft); color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 11px; line-height: 1.6; }
.steps { margin: 8px 0 0; padding-left: 18px; color: var(--metric-text-secondary); font-size: 10px; line-height: 1.7; }
.algorithm-note { margin: 7px 0 0; color: var(--metric-text-muted); font-size: 9px; line-height: 1.5; }
.factor-list { display: flex; flex-wrap: wrap; gap: 6px; }
.factor-list span { padding: 4px 7px; border: 1px solid var(--metric-border); border-radius: 6px; color: var(--metric-text-secondary); font-size: 9px; }
.factor-list b { color: var(--metric-text); margin-left: 3px; }
.health-trend { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--metric-border); }
.trend-label { margin-bottom: 4px; color: var(--metric-text-muted); font-size: 9px; }
.health-trend :deep(svg) { height: 38px; color: var(--metric-primary); }
.trend-note { color: var(--metric-text-muted); font-size: 9px; }
@media (max-width: 700px) { .explain-grid { grid-template-columns: 1fr; } .explain-head { align-items: flex-start; } }
</style>
