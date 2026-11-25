<template>
  <div v-loading="loading" class="health-observation">
    <div class="drill-head">
      <div class="crumbs">
        <button type="button" :class="{ active: level === 'system' }" @click="backToSystem">{{ entityName }}</button>
        <template v-if="level === 'category'">
          <span class="crumb-sep">/</span>
          <span class="crumb-current">{{ selectedCategory?.name }}</span>
        </template>
      </div>
      <small>分数 = 当前时间窗健康度曲线各采样点的平均（区间均分） · {{ healthThresholds.warning }} 为关注分界、{{ healthThresholds.alarm }} 为异常分界</small>
    </div>

    <div class="hero-grid">
      <section class="score-panel">
        <header>
          <div><h3>{{ levelLabel }}</h3><small>{{ summaryText }}</small></div>
          <div class="header-actions">
            <StatusTag :status="statusOf(currentScore)" :label="statusText(currentScore)" />
            <button type="button" class="compare-link" @click="openCompare(level === 'system' ? { type: 'system', name: entityName } : { type: 'category', name: selectedCategory?.name })">环比分析</button>
          </div>
        </header>
        <div class="score-body">
          <div class="score-gauge-surface">
            <HealthScoreGauge compact :score="currentScore" :label="levelLabel"
              :tone="{normal:'score-excellent',warning:'score-good',danger:'score-poor',unknown:'score-unknown'}[statusOf(currentScore)]"
              :description="statusText(currentScore)" />
            <!-- 悬停看算法：公式、参与数、大类明细、最差指标都在这里，数据与左侧分数同源，
                 换任意专屏视图都复用这一处，不需要每个视图单独配置。 -->
            <el-popover placement="bottom" :title="`${levelLabel}计算依据`" width="430" trigger="hover">
              <template #reference>
                <span class="calc-basis-link"><el-icon><InfoFilled /></el-icon> 计算依据</span>
              </template>
              <div class="calc-basis">
                <p><strong>计算公式：</strong></p>
                <p>{{ basis.formula }}</p>
                <p class="calc-note">只对“已算出健康度”的指标取平均；待计算指标不参与，也不会按 0 分拉低分数。</p>
                <p class="calc-note">分数与曲线同源：取当前时间窗（{{ days }} 天）内这张卡片上趋势曲线的各采样点等权平均；曲线还没取到时才回退最新健康度平均。</p>
                <el-divider />
                <p><strong>参与计算：</strong>{{ basis.scored }} / {{ basis.total }} 项 · 覆盖率 {{ basis.coverage }}%</p>
                <ul class="calc-list">
                  <li v-for="item in basis.counts" :key="item.key">
                    <span>{{ item.label }}</span><span><b :class="item.key">{{ item.value }}</b> 项</span>
                  </li>
                </ul>
                <template v-if="basis.categories.length">
                  <el-divider />
                  <p><strong>大类明细（低分在前）：</strong></p>
                  <ul class="calc-list">
                    <li v-for="cat in basis.categories" :key="cat.name">
                      <span>{{ cat.name }}</span>
                      <span><b :class="statusOf(cat.score)">{{ cat.score ?? '-' }}</b> 分 · 指标 {{ cat.counts?.total ?? 0 }} · 待计算 {{ cat.counts?.unknown ?? 0 }}</span>
                    </li>
                  </ul>
                </template>
                <template v-if="basis.worst">
                  <el-divider />
                  <p><strong>最差指标（避免被平均掩盖）：</strong></p>
                  <p>{{ basis.worst.metricName }}：<b :class="statusOf(basis.worst.healthScore)">{{ basis.worst.healthScore }}</b> 分</p>
                </template>
                <el-divider />
                <p class="calc-note">
                  判定口径：分数 &lt; {{ healthThresholds.alarm }} 为异常，{{ healthThresholds.alarm }} ~ {{ healthThresholds.warning }} 为关注，
                  ≥ {{ healthThresholds.warning }} 为正常。
                </p>
                <p class="calc-note">
                  分数为区间均分：与卡片/图表上的健康度曲线同一份采样点，等权平均，不按大类/指标数量加权；
                  无区间采样时回退最新健康度平均。范围随专屏授权变化。
                </p>
              </div>
            </el-popover>
          </div>
          <div class="score-copy">
            <b>已计算 {{ (currentEntity?.counts?.total || 0) - (currentEntity?.counts?.unknown || 0) }} / {{ currentEntity?.counts?.total || 0 }} 项指标</b>
            <div class="score-status-counts"><div v-for="item in statusCounts" :key="item.key" :class="item.key"><span><i></i>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
            <p>{{ scoreHint }}</p>
            <small>{{ days }} 天窗口 · 分数为区间均分</small>
          </div>
        </div>
        <div v-if="level === 'system' && categories.length" class="category-mini-list">
          <button v-for="cat in categories" :key="cat.name" type="button" class="category-mini" @click="openCategory(cat)">
            <span class="status-dot" :class="statusOf(cat.score)"></span>
            <span class="mini-name">{{ cat.name }}</span>
            <b :class="statusOf(cat.score)">{{ cat.score ?? '--' }}</b>
            <small>指标 {{ cat.counts.total }} · 异常 {{ cat.counts.danger }} · 待计算 {{ cat.counts.unknown }}</small>
            <span class="mini-arrow">›</span>
          </button>
        </div>
      </section>

      <section class="trend-panel">
        <header><div><h3>{{ trendTitle }}</h3><small>{{ days }} 天 · 曲线为区间采样，虚线为关注 / 异常分界</small></div></header>
        <HealthTrendChart :points="currentEntity?.trend || []" :height="210" />
      </section>
    </div>

    <section v-if="level === 'system'" class="overview-section">
      <div class="section-head">
        <div><h3>指标健康度总览</h3><small>点击大类卡片下钻查看小类健康度曲线；卡片分数为当前时间窗区间均分</small></div>
        <span class="section-meta">共 {{ categories.length }} 个指标大类</span>
      </div>
      <div v-if="categories.length" class="category-grid">
        <button v-for="cat in categories" :key="cat.name" type="button" class="category-card" :class="statusOf(cat.score)" @click="openCategory(cat)">
          <div class="card-head"><span>{{ cat.name }}</span><span class="card-head-actions"><StatusTag :status="statusOf(cat.score)" :label="statusText(cat.score)" /><span class="compare-link" @click.stop="openCompare({ type: 'category', name: cat.name })">环比</span></span></div>
          <div class="card-score">{{ cat.score ?? '--' }}<small>分</small></div>
          <div class="card-counts">指标 {{ cat.counts.total }} · 异常 {{ cat.counts.danger }} · 待计算 {{ cat.counts.unknown }}</div>
          <div class="spark"><HealthMiniTrend :points="cat.trend || []" /></div>
          <span class="drill-link">点击下钻 ›</span>
        </button>
      </div>
      <el-empty v-else description="当前范围内没有指标健康数据" :image-size="72" />
    </section>

    <section v-if="level === 'category'" class="overview-section">
      <div class="section-head">
        <div><h3>指标小类健康度</h3><small>点击小类卡片可切换下方指标明细和图表；默认展示第一个小类，分数为区间均分</small></div>
        <span class="section-meta">{{ selectedCategory?.subcategories?.length || 0 }} 个指标小类</span>
      </div>
      <div v-if="selectedCategory?.subcategories?.length" class="subcategory-grid">
        <button
          v-for="sub in selectedCategory.subcategories"
          :key="sub.name"
          type="button"
          class="subcategory-card"
          :class="[statusOf(subScore(sub)), { active: selectedSubcategoryName === sub.name }]"
          @click="selectSubcategory(sub)"
        >
          <div class="card-head"><span>{{ sub.name }}</span><span class="card-head-actions"><StatusTag :status="statusOf(subScore(sub))" :label="statusText(subScore(sub))" /><span class="compare-link" @click.stop="openCompare({ type: 'subcategory', name: sub.name, categoryName: selectedCategory?.name })">环比</span></span></div>
          <div class="sub-body"><div class="card-score">{{ subScore(sub) ?? '--' }}<small>分</small></div><div class="card-counts">指标 {{ sub.counts.total }} · 异常 {{ sub.counts.danger }} · 待计算 {{ sub.counts.unknown }}</div></div>
          <div class="spark"><HealthMiniTrend :points="subTrend(sub)" /></div>
          <span class="drill-link">{{ selectedSubcategoryName === sub.name ? '当前小类' : '查看该小类指标 ›' }}</span>
        </button>
      </div>
      <el-empty v-else description="该大类下暂无小类健康数据" :image-size="72" />
    </section>

    <section v-if="level === 'category'" class="metric-section">
      <div class="section-head">
        <div><h3>所有指标详情</h3><small>{{ selectedSubcategory?.name }} · 点击指标行查看定义、阈值和采集明细</small></div>
        <span class="section-meta">{{ currentMetrics.length }} 个指标</span>
      </div>
      <el-table :data="currentMetrics" class="metric-table" size="small" max-height="320" empty-text="该小类下暂无指标" @row-click="row => $emit('metric-detail', row)">
        <el-table-column prop="metricName" label="指标名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="编码" width="140" show-overflow-tooltip><template #default="{ row }">{{ metricMeta.get(Number(row.metricId))?.metricCode || '-' }}</template></el-table-column>
        <el-table-column label="当前值" width="105"><template #default="{ row }">{{ row.latestValue ?? '--' }}{{ metricMeta.get(Number(row.metricId))?.unit ? ` ${metricMeta.get(Number(row.metricId)).unit}` : '' }}</template></el-table-column>
        <el-table-column label="健康度" width="90"><template #default="{ row }"><b class="health-link" :class="statusOf(row.healthScore)" @click.stop="openHealthExplain(row)">{{ row.healthScore ?? '--' }}</b></template></el-table-column>
        <el-table-column label="状态" width="95"><template #default="{ row }"><StatusTag :status="statusOf(row.healthScore)" :label="statusText(row.healthScore)" /></template></el-table-column>
        <el-table-column label="采集频率" width="90"><template #default="{ row }">{{ metricMeta.get(Number(row.metricId))?.collectFrequency || '-' }}</template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary">明细</el-button><span class="compare-link" @click.stop="openCompare(metricTarget(row))">环比</span></template></el-table-column>
      </el-table>

      <div class="chart-section-head">
        <div><b>指标值与健康度趋势</b><small v-if="currentMetrics.length > chartLimit">为保证可读性，下方图表最多展示前 {{ chartLimit }} 项，完整清单见上表</small></div>
        <span class="section-meta">{{ chartMetrics.length }} 张图表</span>
      </div>
      <div v-loading="chartsLoading" class="metric-chart-grid">
        <article v-for="row in chartMetrics" :key="row.metricId" class="metric-chart-card">
          <header>
            <div><b>{{ row.metricName }}</b><small>{{ metricMeta.get(Number(row.metricId))?.metricCode || '' }}</small></div>
            <div class="chart-meta"><span>{{ row.latestValue ?? '--' }}{{ metricMeta.get(Number(row.metricId))?.unit ? ` ${metricMeta.get(Number(row.metricId)).unit}` : '' }}</span><b class="health-link" :class="statusOf(row.healthScore)" @click.stop="openHealthExplain(row)">{{ row.healthScore ?? '--' }}</b><StatusTag :status="statusOf(row.healthScore)" :label="statusText(row.healthScore)" /><span class="compare-link" @click.stop="openCompare(metricTarget(row))">环比分析</span></div>
          </header>
          <MetricValueHealthChart :values="metricValues[Number(row.metricId)] || []" :unit="metricMeta.get(Number(row.metricId))?.unit || ''" />
        </article>
      </div>
      <el-empty v-if="!currentMetrics.length" description="该小类下暂无指标" :image-size="72" />
    </section>

    <PeriodCompareDialog v-model="compareVisible" :target="compareTarget" :view-code="viewCode" :nodes="nodes" :health-cards="healthCards" />
    <HealthExplainDialog v-model="healthExplainVisible" :item="healthExplainItem" :definition="healthExplainDefinition" />
  </div>
</template>

<script setup>
import { healthThresholds, scoreLevel } from '@/utils/healthThresholds'
import { InfoFilled } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import HealthScoreGauge from '@/views/observation/components/HealthScoreGauge.vue'
import HealthTrendChart from './HealthTrendChart.vue'
import MetricValueHealthChart from './MetricValueHealthChart.vue'
import PeriodCompareDialog from './PeriodCompareDialog.vue'
import HealthExplainDialog from './HealthExplainDialog.vue'
import HealthMiniTrend from './HealthMiniTrend.vue'
import StatusTag from './ui/StatusTag.vue'
import { screenApi, windowParams } from './api'
import { mergeMetricTrendSeries } from './metricTrendSeries'
import './theme/metric-tokens.css'
import './theme/metric-utilities.css'

const props = defineProps({
  healthCards: { type: Array, default: () => [] },
  catalogMetrics: { type: Array, default: () => [] },
  days: { type: Number, default: 7 },
  // 自定义时间区间 [start,end]（YYYY-MM-DD HH:mm:ss）；为空时按 days 预设取数
  range: { type: Array, default: null },
  viewCode: { type: String, default: '' },
  nodes: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  // 专屏按岗位过滤时传入岗位名称，用于把标题从「系统综合健康度」改成「营业员健康度」
  audienceName: { type: String, default: '' }
})
defineEmits(['metric-detail'])

const level = ref('system'), selectedCategoryName = ref(''), selectedSubcategoryName = ref('')
const extraTrend = ref([]), subTrends = ref({}), metricValues = ref({}), chartsLoading = ref(false)
const compareVisible = ref(false), compareTarget = ref({})
const healthExplainVisible = ref(false), healthExplainItem = ref(null), healthExplainDefinition = ref(null)
const chartLimit = 12
const metricMeta = computed(() => new Map((props.catalogMetrics || []).map(item => [Number(item.id), item])))

function averageScore (metrics) {
  // 层级分数口径（回退用）：当前时间窗内该层全部健康度采样点的加权平均（SUM/COUNT，四舍五入）。
  // 后端每张卡已按 metric_health_history 的窗口记录算出 windowHealthScore/windowHealthSamples，
  // 这里再按采样点数加权，避免「各指标均值的简单平均」掩盖采样多的指标。
  const sampled = (metrics || []).filter(item => item.windowHealthScore != null && Number(item.windowHealthSamples) > 0)
  if (sampled.length) {
    const sum = sampled.reduce((total, item) => total + Number(item.windowHealthScore) * Number(item.windowHealthSamples), 0)
    const count = sampled.reduce((total, item) => total + Number(item.windowHealthSamples), 0)
    if (count > 0) return Math.round(sum / count)
  }
  // 回退：接口没有窗口均值（老数据/无采样）时仍按最新健康度求平均，保持原有展示。
  const values = (metrics || []).filter(item => item.healthScore != null).map(item => Number(item.healthScore)).filter(Number.isFinite)
  return values.length ? Math.round(values.reduce((sum, value) => sum + value, 0) / values.length) : null
}
/**
 * 曲线与分数同源：卡片/仪表盘的分数 = 该卡片上**画出来的那条曲线**各采样点的等权平均。
 *
 * <p>旧口径用 metric_health_history 的采样点加权算分，卡片曲线却来自节点快照趋势，两个数据源不同，
 * 于是出现「曲线里一串 90 分、分数仍显示 100」的矛盾（用户 2026-11-25 反馈）。
 * 改成一个数据源后，任何视图中「分数 = 看得见的曲线均值」，不再需要解释两套口径。
 */
function seriesScore (points) {
  const values = (points || []).map(point => Number(point?.healthScore)).filter(value => Number.isFinite(value))
  if (!values.length) return null
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length)
}
function countMetrics (metrics) {
  const out = { total: (metrics || []).length, normal: 0, warning: 0, danger: 0, unknown: 0 }
  ;(metrics || []).forEach(item => {
    if (item.healthScore == null) out.unknown++
    else if (scoreLevel(item.healthScore) === 'danger') out.danger++
    else if (scoreLevel(item.healthScore) === 'warning') out.warning++
    else out.normal++
  })
  return out
}
function aggregateTrend (metrics) {
  const buckets = new Map()
  const total = (metrics || []).length
  ;(metrics || []).forEach(metric => {
    (metric.trend || []).forEach(point => {
      if (point.healthScore == null) return
      const score = Number(point.healthScore)
      const time = String(point.recordTime || point.time || point.snapshotTime || '')
      if (!Number.isFinite(score) || !time) return
      const bucket = buckets.get(time) || { time, sum: 0, count: 0 }
      bucket.sum += score
      bucket.count++
      buckets.set(time, bucket)
    })
  })
  // count/total 一并带出：趋势图上据此识别「只有个别指标刚更新」的低采样时间窗，
  // 避免把 3~8 个指标的新值当成整体健康度（曾出现最新窗口只有 8 条 0 分记录，
  // 图上显示「最新 0 分」，和左侧 76 分的总览自相矛盾）。
  return [...buckets.values()].sort((a, b) => a.time.localeCompare(b.time)).map(bucket => ({ time: bucket.time, healthScore: Math.round(bucket.sum / bucket.count), count: bucket.count, total }))
}
function sortByScore (items) {
  return [...items].sort((a, b) => {
    if (a.score == null) return 1
    if (b.score == null) return -1
    return a.score - b.score
  })
}
const categories = computed(() => {
  const map = new Map()
  ;(props.healthCards || []).forEach(card => {
    const categoryName = card.categoryName || '未分类'
    let category = map.get(categoryName)
    if (!category) { category = { name: categoryName, metrics: [], subcategories: [] }; map.set(categoryName, category) }
    category.metrics.push(card)
    const subCategoryName = card.subCategoryName || '未分小类'
    let subcategory = category.subcategories.find(item => item.name === subCategoryName)
    if (!subcategory) { subcategory = { name: subCategoryName, metrics: [] }; category.subcategories.push(subcategory) }
    subcategory.metrics.push(card)
  })
  const result = [...map.values()].map(category => {
    category.subcategories = sortByScore(category.subcategories.map(subcategory => {
      // 先算趋势再定分：分数取曲线点平均，避免与卡片上的折线对不上
      const trend = aggregateTrend(subcategory.metrics)
      return { ...subcategory, trend, score: seriesScore(trend) ?? averageScore(subcategory.metrics), counts: countMetrics(subcategory.metrics) }
    }))
    const trend = aggregateTrend(category.metrics)
    return { ...category, trend, score: seriesScore(trend) ?? averageScore(category.metrics), counts: countMetrics(category.metrics) }
  })
  return sortByScore(result)
})
/**
 * 当前岗位名（专屏选了「岗位」下拉时由父组件传入）。
 * 选了岗位后，下面的分数是「该岗位」的健康度，标题不能再写「系统综合健康度」，
 * 否则会让人以为是全系统口径。
 */
const entityName = computed(() => props.audienceName ? `${props.audienceName}健康度` : '系统综合健康度')
const systemEntity = computed(() => {
  const trend = aggregateTrend(props.healthCards)
  return { name: entityName.value, score: seriesScore(trend) ?? averageScore(props.healthCards), counts: countMetrics(props.healthCards), trend }
})
const selectedCategory = computed(() => categories.value.find(item => item.name === selectedCategoryName.value))
const selectedSubcategory = computed(() => selectedCategory.value?.subcategories.find(item => item.name === selectedSubcategoryName.value))
const currentEntity = computed(() => {
  const entity = level.value === 'category' ? selectedCategory.value : systemEntity.value
  if (!entity) return entity
  return extraTrend.value.length ? { ...entity, trend: extraTrend.value } : entity
})
const currentMetrics = computed(() => sortByScore(selectedSubcategory.value?.metrics || []))
/**
 * 仪表盘分数：与下方「健康度趋势」大图同一条曲线。
 * 大类层级会用服务端节点趋势覆盖 entity.trend，所以分数必须在渲染时跟着曲线算，
 * 否则会出现「图上 90 分、仪表盘 100 分」。
 */
const currentScore = computed(() => seriesScore(currentEntity.value?.trend) ?? currentEntity.value?.score ?? null)
/** 小类卡片分数：卡片画的是服务端小类趋势（拿不到才退回聚合趋势），分数取同一份采样点。 */
function subScore (subcategory) { return seriesScore(subTrend(subcategory)) ?? subcategory.score ?? null }
const chartMetrics = computed(() => currentMetrics.value.slice(0, chartLimit))
const levelLabel = computed(() => level.value === 'system' ? entityName.value : `${selectedCategory.value?.name || ''}大类健康度`)
const trendTitle = computed(() => level.value === 'system' ? `${entityName.value}趋势` : `${currentEntity.value?.name || ''}健康度趋势`)
const statusCounts = computed(() => [
  { key:'normal', label:'正常', value:currentEntity.value?.counts?.normal ?? 0 },
  { key:'warning', label:'关注', value:currentEntity.value?.counts?.warning ?? 0 },
  { key:'danger', label:'异常', value:currentEntity.value?.counts?.danger ?? 0 },
  { key:'unknown', label:'待计算', value:currentEntity.value?.counts?.unknown ?? 0 }
])
const summaryText = computed(() => props.audienceName
  ? `当前岗位「${props.audienceName}」· 共 ${currentEntity.value?.counts?.total ?? 0} 项指标`
  : `当前授权范围 · 共 ${currentEntity.value?.counts?.total ?? 0} 项指标`)

const scoreHint = computed(() => level.value !== 'system'
  ? '点击下方小类卡片切换指标明细；当前小类的指标值和健康度曲线会在下方同步更新。'
  : props.audienceName
    ? `按「${props.audienceName}」岗位下可见指标的当前时间窗健康度曲线，对曲线采样点等权平均；不按大类/指标数量加权。`
    : '按当前可见指标的当前时间窗健康度曲线，对曲线采样点等权平均；不按大类/指标数量加权。')

/**
 * 「计算依据」弹窗的数据。
 * 直接由当前层级的指标卡片现算，不额外请求接口，
 * 保证弹窗里的公式/参与数/最差指标与左侧展示的分数是同一份数据 —— 换任何专屏视图都成立。
 */
const basis = computed(() => {
  const counts = currentEntity.value?.counts || {}
  const total = Number(counts.total || 0)
  const scored = Math.max(0, total - Number(counts.unknown || 0))
  const scoped = level.value === 'system'
    ? (props.healthCards || [])
    : (selectedCategory.value?.metrics || [])
  const worst = scoped
    .filter(item => item.healthScore != null && Number.isFinite(Number(item.healthScore)))
    .sort((a, b) => Number(a.healthScore) - Number(b.healthScore))[0]
  return {
    formula: level.value === 'system'
      ? `${entityName.value} = 当前时间窗健康度趋势曲线各采样点之和 ÷ 采样点数（等权），不做大类加权。`
      : `${selectedCategory.value?.name || '大类'}健康度 = 该大类在当前时间窗内的趋势曲线各采样点之和 ÷ 采样点数（等权）。`,
    total,
    scored,
    coverage: total ? Math.round(scored * 10000 / total) / 100 : 0,
    counts: [
      { key: 'normal', label: '正常', value: Number(counts.normal || 0) },
      { key: 'warning', label: '关注', value: Number(counts.warning || 0) },
      { key: 'danger', label: '异常', value: Number(counts.danger || 0) },
      { key: 'unknown', label: '待计算', value: Number(counts.unknown || 0) }
    ],
    categories: level.value === 'system'
      ? [...(categories.value || [])].sort((a, b) => (a.score ?? 999) - (b.score ?? 999))
      : [],
    worst: worst ? { metricName: worst.metricName, healthScore: worst.healthScore } : null
  }
})
function statusOf (score) {
  if (score == null) return 'unknown'
  if (scoreLevel(score) === 'danger') return 'danger'
  if (scoreLevel(score) === 'warning') return 'warning'
  return 'normal'
}
function statusText (score) {
  const status = statusOf(score)
  return status === 'danger' ? '异常' : status === 'warning' ? '关注' : status === 'unknown' ? '待计算' : '正常'
}
function subTrend (subcategory) { return subTrends.value[subcategory.name] || subcategory.trend || [] }
function metricTarget (row) {
  const meta = metricMeta.value.get(Number(row.metricId))
  return { type: 'metric', name: row.metricName, metricId: row.metricId, categoryName: row.categoryName, subCategoryName: row.subCategoryName, unit: meta?.unit || '', threshold: meta?.alarmThreshold ?? null }
}
function openCompare (target) { compareTarget.value = target; compareVisible.value = true }
function openHealthExplain (row) { healthExplainItem.value = row; healthExplainDefinition.value = metricMeta.value.get(Number(row.metricId)) || null; healthExplainVisible.value = true }
function findNode (type, label, categoryName) {
  return (props.nodes || []).find(node => node.type === type && node.label === label && (!categoryName || node.categoryName === categoryName))
}
async function loadCategoryTrend () {
  extraTrend.value = []
  if (!props.viewCode) return
  const node = findNode('category', selectedCategoryName.value)
  if (!node) return
  try { const result = await screenApi.health(props.viewCode, { node: node.key, days: props.days, ...windowParams(props.range) }); extraTrend.value = result?.data || [] } catch { extraTrend.value = [] }
}
async function loadSubcategoryTrends (category) {
  const next = {}
  await Promise.all((category?.subcategories || []).map(async subcategory => {
    const node = findNode('subcategory', subcategory.name, category.name)
    if (!node) return
    try { const result = await screenApi.health(props.viewCode, { node: node.key, days: props.days, ...windowParams(props.range) }); if (result?.data?.length) next[subcategory.name] = result.data } catch {}
  }))
  subTrends.value = next
}
async function loadMetricCharts () {
  const metrics = chartMetrics.value
  const next = {}
  chartsLoading.value = true
  await Promise.all(metrics.map(async card => {
    const node = (props.nodes || []).find(item => item.type === 'metric' && Number(item.id) === Number(card.metricId))
    if (!node) return
    try {
      const result = await screenApi.health(props.viewCode, { node: node.key, days: props.days, ...windowParams(props.range) })
      // 指标值与健康度是两条独立序列：values 只有采集值、healthTrend 只有健康度，
      // 按分钟合并后一次传给图表，蓝线（指标值）与绿线（健康度）才会同时出现。
      const series = mergeMetricTrendSeries(result?.values, result?.healthTrend)
      if (series.length) next[Number(card.metricId)] = series
    } catch {}
  }))
  metricValues.value = next
  chartsLoading.value = false
}
async function openCategory (category) {
  level.value = 'category'
  selectedCategoryName.value = category.name
  selectedSubcategoryName.value = category.subcategories?.[0]?.name || ''
  extraTrend.value = []
  subTrends.value = {}
  metricValues.value = {}
  await loadCategoryTrend()
  await loadSubcategoryTrends(category)
  await loadMetricCharts()
}
async function selectSubcategory (subcategory) {
  selectedSubcategoryName.value = subcategory.name
  // 不先清空 metricValues：清空会让下方 12 张图表瞬间退回空态、页面高度先矮后高，
  // 滚动条与栅格列数跟着抖动。保留旧图，等新数据回来直接替换（chartsLoading 有遮罩提示）。
  await loadMetricCharts()
}
function backToSystem () {
  level.value = 'system'
  selectedCategoryName.value = ''
  selectedSubcategoryName.value = ''
  extraTrend.value = []
  subTrends.value = {}
  metricValues.value = {}
}
watch(() => props.healthCards, () => {
  if (level.value !== 'system' && !selectedCategory.value) backToSystem()
  else if (level.value === 'category' && selectedCategory.value && !selectedSubcategory.value) {
    selectedSubcategoryName.value = selectedCategory.value.subcategories?.[0]?.name || ''
  }
})
// 切预设或切自定义区间都要重拉当前层级的趋势，否则大屏会停在旧时间窗。
watch(() => [props.days, props.range], () => {
  if (level.value === 'category') {
    loadCategoryTrend()
    loadSubcategoryTrends(selectedCategory.value)
    loadMetricCharts()
  }
})
</script>

<style scoped>
/* min-width:0：作为栅格项时默认 min-width:auto 会被最长指标名撑宽，
   一旦超出父容器就会出现横向溢出（页面找不到右边界、卡片像在“找边界”）。 */
.health-observation { display: grid; gap: 12px; min-width: 0; }
.drill-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.crumbs { display: flex; align-items: center; gap: 6px; color: var(--metric-text-muted); font-size: 12px; }
.crumbs button { padding: 0; border: 0; background: transparent; color: var(--metric-text-secondary); font-size: 12px; cursor: pointer; }
.crumbs button.active { color: var(--metric-primary-hover); font-weight: 650; }
.crumb-sep { color: var(--metric-text-muted); }
.crumb-current { color: var(--metric-text); font-weight: 600; }
.drill-head > small { color: var(--metric-text-muted); font-size: 10px; }
.hero-grid { display: grid; grid-template-columns: minmax(300px, .85fr) minmax(0, 1.15fr); gap: 12px; }
.score-panel, .trend-panel, .overview-section, .metric-section { padding: 14px; min-width: 0; border: 1px solid var(--metric-border); border-radius: var(--metric-radius-lg); background: var(--ui-surface); box-shadow: var(--metric-shadow-xs); }
.score-panel header, .trend-panel header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.score-panel h3, .trend-panel h3, .section-head h3 { margin: 0; color: var(--metric-text); font-size: 14px; }
.score-panel header small, .trend-panel header small, .section-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 10px; }
.score-body { display: flex; align-items: center; gap: 16px; padding: 4px 0 12px; }
.score-copy { display: grid; gap: 5px; min-width: 0; }
.score-copy b { color: var(--metric-text); font-size: 14px; }
.score-copy p { margin: 0; color: var(--metric-text-secondary); font-size: 11px; line-height: 1.55; }
.score-copy small { color: var(--metric-text-muted); font-size: 10px; }
.category-mini-list { display: grid; gap: 5px; }
.category-mini { display: flex; align-items: center; gap: 7px; padding: 6px 8px; border: 1px solid var(--metric-border); border-radius: 8px; background: var(--metric-surface-soft); text-align: left; cursor: pointer; }
.category-mini:hover { border-color: var(--metric-primary-border); background: var(--metric-primary-soft); }
.category-mini .mini-name { flex: 1; min-width: 0; overflow: hidden; color: var(--metric-text); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.category-mini b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 13px; }
.category-mini small { color: var(--metric-text-muted); font-size: 9px; }
.mini-arrow { color: var(--metric-text-muted); font-size: 15px; }
.status-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--metric-text-muted); }
.status-dot.normal { background: var(--metric-success); }
.status-dot.warning { background: var(--metric-warning); }
.status-dot.danger { background: var(--metric-danger); }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.section-meta { color: var(--metric-text-muted); font-size: 10px; }
.category-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(230px, 100%), 1fr)); gap: 9px; }
/* 过渡只挂在不影响布局的属性上：早期写成 `transition:.18s`（all），
   点选卡片时连 border-width 一起补间，每帧都在改边框宽度 → 文字重新折行、
   行高来回跳，看起来就是卡片“逐步变大又变小、一直在找边界”。 */
.category-card, .subcategory-card { display: grid; gap: 5px; min-width: 0; padding: 11px; border: 1px solid var(--metric-border); border-left: 4px solid var(--metric-success); border-radius: var(--metric-radius); background: var(--ui-surface); text-align: left; cursor: pointer; transition: box-shadow .18s, transform .18s, border-color .18s, background-color .18s; }
.category-card:hover, .subcategory-card:hover { box-shadow: var(--metric-shadow-md); transform: translateY(-1px); }
.category-card.warning, .subcategory-card.warning { border-left-color: var(--metric-warning); background: var(--metric-warning-soft); }
.category-card.danger, .subcategory-card.danger { border-left-color: var(--metric-danger); background: var(--metric-danger-soft); }
.category-card.unknown, .subcategory-card.unknown { border-left-color: var(--metric-text-muted); }
.subcategory-card { border-left-color: var(--metric-primary); }
/* 选中态用内阴影描边，不再把 1px 边框改成 2px：盒子尺寸不变，点选前后卡片既不会位移，
   也不会因为边框宽度变化让卡片内的名字重新折行。
   选择器多带一层 .subcategory-grid，是为了压过 dashboard-theme.css 里
   `.screen-page.dashboard-workspace .health-observation .subcategory-card` 的边框色。 */
.subcategory-grid .subcategory-card.active { border-color: var(--metric-primary); box-shadow: inset 0 0 0 2px var(--metric-primary), var(--metric-shadow-md); }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
/* 名字可以换行但不能把卡片（进而把整块内容）撑宽：长指标名一律在卡片内折行。 */
.card-head > span { min-width: 0; color: var(--metric-text); font-size: 12px; font-weight: 650; overflow-wrap: anywhere; }
.card-score { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 25px; line-height: 1; }
.card-score small { margin-left: 3px; color: var(--metric-text-muted); font-family: var(--metric-font-sans); font-size: 9px; }
.card-counts { color: var(--metric-text-muted); font-size: 9px; }
.spark { height: 104px; color: var(--metric-primary); }
.category-card.warning .spark { color: var(--metric-warning); }
.category-card.danger .spark { color: var(--metric-danger); }
.drill-link { color: var(--metric-primary-hover); font-size: 10px; }
.subcategory-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(300px, 100%), 1fr)); gap: 9px; }
.sub-body { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.metric-table { width: 100%; cursor: pointer; }
.metric-table :deep(.el-table__row) { cursor: pointer; }
.metric-table b.normal { color: var(--metric-success); }
.metric-table b.warning { color: var(--metric-warning); }
.metric-table b.danger { color: var(--metric-danger); }
.chart-section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; margin: 14px 0 8px; }
.chart-section-head b { color: var(--metric-text); font-size: 12px; }
.chart-section-head small { display: block; margin-top: 3px; color: var(--metric-text-muted); font-size: 10px; }
.metric-chart-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(420px, 100%), 1fr)); gap: 10px; }
.metric-chart-card { min-width: 0; padding: 10px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: var(--ui-surface); }
.metric-chart-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-bottom: 4px; min-width: 0; }
.metric-chart-card header b { display: block; min-width: 0; color: var(--metric-text); font-size: 11px; line-height: 1.4; overflow-wrap: anywhere; }
.metric-chart-card header small { color: var(--metric-text-muted); font-size: 9px; }
.chart-meta { display: grid; justify-items: end; gap: 3px; color: var(--metric-text-secondary); font-family: var(--metric-font-mono); font-size: 10px; }
.metric-chart-card :deep(.metric-value-chart) { min-height: 180px; }
.header-actions { display: flex; align-items: center; gap: 7px; }
.compare-link { display: inline-flex; align-items: center; padding: 1px 5px; border: 1px solid var(--metric-primary-border); border-radius: 999px; background: var(--ui-surface); color: var(--metric-primary-hover); font-size: 9px; cursor: pointer; white-space: nowrap; }
.compare-link:hover { background: var(--metric-primary-soft); }
.card-head-actions { display: flex; flex: none; align-items: center; gap: 5px; }
.metric-table .compare-link { margin-left: 6px; }
.chart-meta .compare-link { margin-top: 1px; }
.health-link { cursor: pointer; text-decoration: underline dotted; text-underline-offset: 2px; }
.health-link:hover { filter: brightness(.8); }
@media (max-width: 1000px) { .hero-grid { grid-template-columns: 1fr; } .metric-chart-grid { grid-template-columns: 1fr; } }
@media (max-width: 680px) { .score-body { align-items: flex-start; flex-direction: column; } .drill-head { align-items: flex-start; flex-direction: column; } }
.score-gauge-surface { flex:0 0 180px; padding:8px; border:1px solid var(--ui-border-soft); border-radius:14px; background:linear-gradient(145deg,var(--ui-page),var(--ui-surface)); box-sizing:border-box; }
/* 「计算依据」入口：和运营观测页保持同一位置（仪表盘正下方居中）与同一套视觉 */
.calc-basis-link { display:flex; align-items:center; justify-content:center; gap:5px; width:fit-content; margin:6px auto 0; padding:3px 8px; border:1px solid var(--metric-border); border-radius:7px; background:var(--ui-surface); color:var(--metric-primary-hover); font-size:10px; cursor:help; }
.calc-basis-link:hover { border-color:var(--metric-primary-border); background:var(--metric-primary-soft); }
.calc-basis { color:var(--metric-text); font-size:12px; line-height:1.75; }
.calc-basis :deep(.el-divider--horizontal) { margin:8px 0; }
.calc-basis p { margin:0 0 4px; }
.calc-basis .calc-note { color:var(--metric-text-muted); font-size:11px; line-height:1.6; }
.calc-list { margin:6px 0 0; padding-left:16px; font-size:12px; line-height:1.85; }
.calc-list li { display:flex; justify-content:space-between; gap:12px; }
.calc-list b { font-variant-numeric:tabular-nums; }
.calc-basis b.normal { color:var(--metric-success); }
.calc-basis b.warning { color:var(--metric-warning); }
.calc-basis b.danger { color:var(--metric-danger); }
.calc-basis b.unknown { color:var(--metric-text-muted); }
.score-body { gap:16px; padding:4px 0 10px; }
@media(max-width:600px) { .score-body { flex-direction:column; align-items:stretch; }.score-gauge-surface { flex-basis:auto; width:100%; }.score-copy { text-align:center; } }
</style>

<style scoped>
.score-status-counts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px; }
.score-status-counts > div { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:6px 8px; border:1px solid var(--ui-border-soft); border-radius:7px; background:var(--ui-page); }
.score-status-counts span { display:inline-flex; align-items:center; gap:5px; color:var(--ui-muted); font-size:10px; }
.score-status-counts i { width:5px; height:5px; border-radius:50%; background:var(--ui-subtle); }
.score-status-counts .normal i { background:var(--ui-success); }.score-status-counts .warning i { background:var(--ui-warning); }.score-status-counts .danger i { background:var(--ui-danger); }
.score-status-counts strong { color:var(--ui-heading); font-size:14px; font-variant-numeric:tabular-nums; }
@media(max-width:680px) { .score-body { flex-direction:row; align-items:center; }.score-gauge-surface { flex:0 0 180px; width:180px; }.score-copy { text-align:left; } }
@media(max-width:480px) { .score-body { flex-direction:column; align-items:stretch; }.score-gauge-surface { align-self:center; flex-basis:auto; }.score-copy { width:100%; } }
</style>
