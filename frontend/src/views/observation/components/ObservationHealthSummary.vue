<template>
  <div class="system-health-section">
    <div class="section-title">
      <span>系统健康度</span>
      <WorkspaceStatusBadge :tone="badgeTone">{{ badgeText }}</WorkspaceStatusBadge>
      <el-select
        v-if="systemOptions.length > 1"
        :model-value="selectedSystemId"
        size="small"
        class="system-switch"
        @update:model-value="value => emit('switch-system', value)"
      >
        <el-option v-for="item in systemOptions" :key="item.systemId" :value="item.systemId" :label="item.systemName" />
      </el-select>
      <div class="health-summary-meta">
        <!-- 自定义区间/下钻聚焦窗：外层分数按该区间采样点平均，计算依据里的覆盖率/参与数仍是最新口径 -->
        <span v-if="windowLabel" class="health-window-note" title="外层分数按该区间采样点平均；计算依据弹窗里的覆盖率/参与数仍是当前最新口径">趋势区间 {{ windowLabel }}</span>
        <span :title="'按当前所选时间范围内最后两个有效采样点对比，不是周同比或环比'">{{ sampleDelta == null ? '暂无采样对比' : `较上一有效采样点 ${sampleDelta > 0 ? '+' : ''}${sampleDelta} 分` }}</span>
        <span>大类异常合计 {{ displayCategories.reduce((sum, cat) => sum + (Number(cat.alarmCount) || 0), 0) }} 项</span>
      </div>
    </div>

    <div class="health-overview">
      <div class="health-gauge-panel">
        <HealthScoreGauge :score="score" :tone="getScoreClass(score)" :description="scoreDesc" :loading="scoreLoading" />

        <!-- 口径标注：外层分数是所选区间的采样点均分；计算依据里的覆盖率/参与数仍是实时最新口径 -->
        <span class="score-mode-note" :title="scoreModeTitle">{{ scoreModeText }}</span>

        <!-- 鼠标悬停看算法：公式、权重、覆盖度、最差指标都在这里，和实际算分是同一份数据 -->
        <el-popover placement="bottom" title="系统健康度计算依据" width="420" trigger="hover">
          <template #reference>
            <span class="score-info-icon"><el-icon><InfoFilled /></el-icon> 计算依据</span>
          </template>
          <div class="calculation-detail">
            <p class="calc-note">
              外层分数 = 所选时间区间内系统健康度趋势有效采样点的算术平均（四舍五入）；
              下方权重、覆盖率与参与数仍是系统实时最新口径，不受所选区间回溯。
            </p>
            <el-divider />
            <p><strong>计算公式：</strong></p>
            <p>{{ summary?.formula || '系统健康度 = Σ(大类分数 × 大类权重) ÷ Σ(权重)' }}</p>
            <p class="calc-note">
              权重只对“本次真正参与计算”的大类重新归一：缺数大类不占权重，所以覆盖率会单独展示，不会被平均分掩盖。
            </p>
            <el-divider />
            <p><strong>大类明细：</strong></p>
            <ul class="calc-list">
              <li v-for="cat in displayCategories" :key="cat.name">
                <span>{{ cat.name }}</span>
                <span>
                  <b :class="getScoreClass(cat.score)">{{ cat.score ?? '-' }}</b> 分
                  · 权重 {{ weightText(cat) }}
                  · 参与 {{ cat.participants ?? '-' }}/{{ cat.expected ?? '-' }}
                </span>
              </li>
            </ul>
            <template v-if="summary?.worst?.metricName">
              <el-divider />
              <p><strong>最差指标（避免被平均掩盖）：</strong></p>
              <p>{{ summary.worst.metricName }}：<b :class="getScoreClass(summary.worst.score)">{{ summary.worst.score }}</b> 分</p>
            </template>
            <el-divider />
            <p><strong>覆盖率：</strong></p>
            <p class="calc-note">
              参与 {{ coverage.participants ?? '-' }}/{{ coverage.expected ?? '-' }}
              · 学习中 {{ coverage.learning ?? 0 }}
              · 不计分 {{ coverage.exempt ?? 0 }}
              · 缺数 {{ coverage.missing ?? 0 }}
            </p>
            <p class="calc-note">
              判定口径：分数 &lt; {{ summary?.alarmThreshold ?? 60 }} 为异常，{{ summary?.alarmThreshold ?? 60 }}~{{ summary?.warningThreshold ?? 80 }} 为关注；
              聚合层“异常指标占比 ≥ 20% 或存在 &lt; 40 分的指标”判为异常。
            </p>
          </div>
        </el-popover>

        <!-- 覆盖率条：一眼看出这个分数是“多少指标算出来的” -->
        <div v-if="summary" class="coverage-bar" :title="coverageTitle">
          <div class="coverage-head">
            <span>健康度覆盖率</span>
            <b>{{ coverageRate }}%</b>
          </div>
          <div class="coverage-track">
            <i class="seg scored" :style="{ width: seg('participants') }"></i>
            <i class="seg learning" :style="{ width: seg('learning') }"></i>
            <i class="seg missing" :style="{ width: seg('missing') }"></i>
            <i class="seg exempt" :style="{ width: seg('exempt') }"></i>
          </div>
          <div class="coverage-legend">
            <span><i class="dot scored"></i>参与 {{ coverage.participants ?? 0 }}</span>
            <span><i class="dot learning"></i>学习中 {{ coverage.learning ?? 0 }}</span>
            <span><i class="dot missing"></i>缺数 {{ coverage.missing ?? 0 }}</span>
            <span><i class="dot exempt"></i>不计分 {{ coverage.exempt ?? 0 }}</span>
          </div>
        </div>
      </div>

      <div class="category-briefs">
        <div class="category-briefs-heading"><span>指标大类简要得分（点击定位）</span><span>权重 / 覆盖率 / 状态</span></div>
        <!-- 白名单过滤后没有可展示大类 / 汇总未返回时不渲染空行，避免闪出 8 个硬编码大类 -->
        <div v-if="!displayCategories.length" class="category-briefs-empty">
          {{ summaryLoading ? '正在计算各大类健康度…' : '暂无需要展示的指标大类' }}
        </div>
        <div v-for="cat in displayCategories" :key="cat.name" class="category-brief-item-wrap">
          <span
            class="category-brief-name" role="button" tabindex="0"
            @keydown.enter="locate(cat)" @keydown.space.self.prevent="locate(cat)" @click="locate(cat)"
          >{{ cat.name }}</span>
          <span class="category-brief-score" :class="getScoreClass(cat.score)" role="button" tabindex="0" :title="cat.intervalSamples ? `区间均分 · ${cat.intervalSamples} 个采样点` : '区间趋势未加载或无有效采样，暂显示实时最新分'" @click="locate(cat)">
            {{ cat.score ?? '-' }}<small v-if="cat.score != null"> 分</small>
          </span>
          <span class="category-weight" title="权重按“本次参与计算的大类”重新归一，等于算分时实际使用的比例">
            权重 {{ weightText(cat) }}
          </span>
          <span class="category-coverage" :class="{ low: (cat.coverageRate ?? 100) < 100 }" :title="`参与 ${cat.participants ?? '-'}/${cat.expected ?? '-'}`">
            {{ cat.participants ?? '-' }}/{{ cat.expected ?? '-' }}
          </span>
          <WorkspaceStatusBadge :tone="cat.status === 'alarm' ? 'danger' : cat.status === 'warning' ? 'warning' : cat.score == null ? 'muted' : 'success'">
            {{ cat.status === 'alarm' ? '异常' : cat.status === 'warning' ? '关注' : cat.score == null ? '暂无数据' : '正常' }}
          </WorkspaceStatusBadge>
          <el-popover placement="bottom" :title="cat.name + ' 健康度计算依据'" width="380" trigger="hover">
            <template #reference>
              <el-icon class="category-info-icon"><InfoFilled /></el-icon>
            </template>
            <div class="calculation-detail">
              <p><strong>计算公式：</strong></p>
              <p>大类健康度 = Σ(小类分数 × 小类权重) ÷ Σ(权重)</p>
              <el-divider />
              <p><strong>小类明细：</strong></p>
              <ul class="calc-list">
                <li v-for="sub in cat.subcategories || []" :key="sub.name">
                  <span>{{ sub.name }}</span>
                  <span>
                    <b :class="getScoreClass(sub.score)">{{ sub.score ?? '-' }}</b> 分
                    · 参与 {{ sub.participants ?? '-' }}/{{ sub.expected ?? '-' }}
                  </span>
                </li>
              </ul>
              <template v-if="cat.worst && cat.worst.metricName">
                <el-divider />
                <p>最差指标：{{ cat.worst.metricName }} <b :class="getScoreClass(cat.worst.score)">{{ cat.worst.score }}</b> 分</p>
              </template>
            </div>
          </el-popover>
        </div>
      </div>

      <div class="system-trend-wrapper">
        <div class="system-trend-title">系统健康度趋势</div>
        <div id="system-health-trend-chart" class="system-health-trend-chart"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import HealthScoreGauge from './HealthScoreGauge.vue'

const props = defineProps({
  categoryWeights: { type: Object, default: () => ({}) },
  samplingState: String,
  sampleDelta: { type: Number, default: null },
  systemHealthScore: [Number, String],
  // 所选时间区间内系统健康度趋势的均分（外层分数主口径）；无有效采样时为 null → 回退实时汇总分
  systemIntervalScore: { type: Number, default: null },
  systemIntervalSamples: { type: Number, default: 0 },
  // 已按“整个大类 expected=0 隐藏”过滤后的大类行（分数优先区间均分），全部来自后端汇总
  categories: { type: Array, default: () => [] },
  healthSummary: { type: Object, default: null },
  systemOptions: { type: Array, default: () => [] },
  selectedSystemId: { type: Number, default: 1 },
  getScoreClass: { type: Function, default: () => 'score-good' },
  getScoreDesc: { type: Function, default: () => '' },
  // 汇总请求进行中（与采样加载状态分开：刷新时保留旧分数只加遮罩）
  summaryLoading: Boolean,
  // 当前自定义区间/聚焦窗文案：外层分数按该区间采样点平均，这里标出实际区间范围
  windowLabel: { type: String, default: '' }
})
const emit = defineEmits(['locate', 'switch-system'])

const summary = computed(() => props.healthSummary || null)
// 外层分数主口径 = 区间均分；无有效采样点才回退实时汇总分。
// 区间均分还没算出来且正在加载时返回 null：宁可显示“计算中”骨架，也不要先闪一个实时分再跳成区间分。
const score = computed(() => {
  if (props.systemIntervalScore != null) return props.systemIntervalScore
  if (props.summaryLoading || props.samplingState === 'loading') return null
  if (summary.value?.score != null) return summary.value.score
  return props.systemHealthScore
})
// 分数未就绪或正在刷新都交给仪表盘：有值=遮罩刷新，无值=骨架“计算中”
const scoreLoading = computed(() => props.summaryLoading || props.samplingState === 'loading')

// 口径文案：区间均分 / 计算中 / 无有效采样回退实时分
const scoreModeText = computed(() => {
  if (props.systemIntervalScore != null) return `区间均分 · ${props.systemIntervalSamples} 个采样点`
  if (score.value == null) return '区间均分计算中…'
  return '实时最新分 · 当前区间无有效采样'
})
const scoreModeTitle = computed(() => props.systemIntervalScore != null
  ? '外层分数 = 所选时间区间内有效采样点平均；计算依据弹窗里的覆盖率/参与数仍是当前最新口径'
  : '当前区间没有可用采样点，暂时回退到实时汇总分；计算依据弹窗里仍是当前最新口径')
const coverage = computed(() => summary.value?.coverage || {})
const coverageRate = computed(() => {
  if (summary.value?.coverageRate != null) return summary.value.coverageRate
  const participants = Number(coverage.value.participants || 0)
  const expected = Number(coverage.value.expected || 0)
  return expected ? Math.round(participants * 10000 / expected) / 100 : 0
})

const badgeText = computed(() => {
  // 汇总还在算、且没有上一次分数时明确显示“计算中”，不要先报“稳健”
  if (props.scoreLoading && score.value == null) return '计算中'
  if (props.samplingState && props.samplingState !== 'ready') return props.samplingState === 'loading' ? '加载中' : '暂无有效采样'
  if (score.value == null) return '暂无数据'
  const status = summary.value?.status
  if (status === 'alarm') return '需要处理'
  if (status === 'warning') return '需要关注'
  return '稳健'
})
const badgeTone = computed(() => {
  if (score.value == null) return 'muted'
  if (props.samplingState && props.samplingState !== 'ready') return 'muted'
  const status = summary.value?.status
  if (status === 'alarm') return 'danger'
  if (status === 'warning') return 'warning'
  return 'success'
})
// 描述跟着“外层实际展示的分数”走（区间均分），避免分数 70 却写着“整体正常”
const scoreDesc = computed(() => {
  if (score.value == null) return '暂无数据'
  if (typeof props.getScoreDesc === 'function') return props.getScoreDesc(score.value)
  const status = summary.value?.status
  if (status === 'alarm') return '存在需要处理的指标'
  if (status === 'warning') return '有指标偏离正常水平'
  return '整体处于正常范围'
})

const displayCategories = computed(() => (props.categories || []).map(category => ({
  ...category,
  weightShare: category.weightShare ?? props.categoryWeights?.[category.name],
  alarmCount: category.alarmCount ?? (category.status === 'alarm' ? 1 : 0)
})))

function locate (cat) {
  if (cat.code) emit('locate', cat.code)
}

function weightText (cat) {
  const share = cat.weightShare ?? props.categoryWeights?.[cat.name]
  if (share == null) return '未计算'
  return `${Number((Number(share) * 100).toFixed(2))}%`
}

const coverageTotal = computed(() => {
  const rows = summary.value?.coverage
  if (!rows) return 0
  return ['participants', 'learning', 'missing', 'exempt'].reduce((sum, key) => sum + Number(rows[key] || 0), 0)
})

function seg (key) {
  const total = coverageTotal.value
  if (!total) return '0%'
  return `${(Number(coverage.value[key] || 0) * 100 / total).toFixed(2)}%`
}

const coverageTitle = computed(() => `参与 ${coverage.value.participants ?? 0} / 应有 ${coverage.value.expected ?? 0}，学习中 ${coverage.value.learning ?? 0}，不计分 ${coverage.value.exempt ?? 0}，缺数 ${coverage.value.missing ?? 0}`)
</script>

<style scoped src="@/views/observation/health-summary.css"></style>

<style scoped>
.system-switch { width: 170px; margin-left: 4px; }
/* 只补上间距和配色：居中、边框、内边距由 health-summary.css 统一定义。
   这里若再写 display:inline-flex 会覆盖掉那边的 display:flex + margin:0 auto，导致按钮贴左。 */
.score-info-icon { margin-top: 6px; font-size: 11px; color: var(--ui-primary, #3b82f6); }
.calc-note { margin: 4px 0; font-size: 11px; color: #8a94a6; line-height: 1.6; }
.calc-list { margin: 6px 0 0; padding-left: 16px; font-size: 12px; line-height: 1.9; }
.calc-list li { display: flex; justify-content: space-between; gap: 12px; }
.calc-list b.score-excellent { color: #2f9e6e; } .calc-list b.score-good { color: #d68910; } .calc-list b.score-poor { color: #d64545; } .calc-list b.score-unknown { color: #a8b1c0; }
.coverage-bar { width: 220px; margin: 2px auto 0; }
.coverage-head { display: flex; align-items: baseline; justify-content: space-between; font-size: 11px; color: var(--ui-subtle, #8a94a6); }
.coverage-head b { font-size: 13px; color: var(--ui-heading, #1f2d3d); }
.coverage-track { display: flex; height: 6px; margin-top: 4px; border-radius: 999px; overflow: hidden; background: #eef2f7; }
.coverage-track .seg { display: block; height: 100%; }
.seg.scored, .dot.scored { background: #3b82f6; }
.seg.learning, .dot.learning { background: #e6a23c; }
.seg.missing, .dot.missing { background: #c0c4cc; }
.seg.exempt, .dot.exempt { background: #dcdfe6; }
.coverage-legend { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 5px; font-size: 10px; color: var(--ui-subtle, #8a94a6); }
.coverage-legend .dot { display: inline-block; width: 6px; height: 6px; margin-right: 3px; border-radius: 50%; }
.category-coverage { font-size: 11px; color: var(--ui-subtle, #8a94a6); font-variant-numeric: tabular-nums; }
.category-coverage.low { color: #d68910; font-weight: 600; }
.category-briefs-empty { padding: 18px 12px; border: 1px dashed var(--ui-border, #e4e7ed); border-radius: 8px; color: var(--ui-subtle, #8a94a6); font-size: 11px; text-align: center; }
.health-window-note { color: var(--ui-primary, #3b82f6); background: var(--ui-primary-soft, #ecf5ff) !important; }
/* 外层分数口径标注：紧贴仪表盘，说明这是区间均分而不是实时最新分 */
.score-mode-note {
  display: block;
  width: fit-content;
  max-width: 100%;
  margin: 2px auto 0;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--ui-primary-soft, #ecf5ff);
  color: var(--ui-primary, #3b82f6);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  text-align: center;
  cursor: help;
}
@media (max-width: 1400px) {
  .health-gauge-panel { flex-wrap: wrap; justify-content: center; }
}
</style>
