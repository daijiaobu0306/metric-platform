<template>
  <div class="period-compare">
    <!-- 筛选栏 -->
    <div class="pc-filters" v-balanced-query>
      <div class="query-field">
        <span class="filter-label">指标大类</span>
        <el-select v-model="selectedCategories" multiple filterable collapse-tags collapse-tags-tooltip placeholder="可多选" clearable>
          <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
        </el-select>
      </div>
      <div class="query-field">
        <span class="filter-label">指标小类</span>
        <el-select v-model="selectedSubCategories" multiple filterable collapse-tags collapse-tags-tooltip placeholder="可多选" clearable :disabled="!selectedCategories.length">
          <el-option v-for="s in subCategoryOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>
      <div class="query-field">
        <span class="filter-label">指标</span>
        <el-select v-model="selectedMetricIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="可多选" clearable :disabled="!selectedCategories.length">
          <el-option v-for="m in metricOptions" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
      </div>
      <div class="query-field">
        <span class="filter-label">统计方法</span>
        <el-select v-model="method" placeholder="请选择" style="width: 130px">
          <el-option label="平均" value="avg" />
          <el-option label="中位数" value="median" />
        </el-select>
      </div>
      <div class="query-field">
        <span class="filter-label">上周期</span>
        <el-date-picker v-model="period1" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
      </div>
      <div class="query-field">
        <span class="filter-label">本周期</span>
        <el-date-picker v-model="period2" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
      </div>
      <div class="query-actions">
        <el-button @click="reset">重置</el-button>
        <el-button type="primary" :loading="loading" @click="query">查询</el-button>
      </div>
    </div>

    <!-- 结果区 -->
    <div v-if="result" v-loading="loading" class="pc-results">
      <!-- 两周期长度不等时提示：累计型指标的差值会随天数放大，别把「天数少」读成「劣化」 -->
      <el-alert
        v-if="result.periodLength && result.periodLength.warning"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>{{ result.periodLength.warning }}</template>
      </el-alert>
      <section v-if="result.categoryHealth.length" class="pc-section">
        <header class="pc-section-head">
          <h3>指标大类健康度环比</h3>
          <span class="pc-period-note">{{ periodNote }} · {{ methodLabel }} · 单位：分</span>
        </header>
        <p v-if="!hasCategoryHealthData" class="pc-no-history">该时段没有健康度历史：健康度快照要等算分任务跑过才有数据，采集入库本身不写这张表。</p>
        <template v-else>
        <CompareBarChart :rows="result.categoryHealth" health />
        <el-table :data="result.categoryHealth" border size="small" class="pc-table">
          <el-table-column prop="name" label="指标大类" min-width="150" />
          <el-table-column label="上周期" width="100" align="right">
            <template #default="{ row }"><span :class="statusToneClass(row.period1Status)">{{ fmt(row.period1Value) }}</span></template>
          </el-table-column>
          <el-table-column label="本周期" width="100" align="right">
            <template #default="{ row }"><span :class="statusToneClass(row.period2Status)">{{ fmt(row.period2Value) }}</span></template>
          </el-table-column>
          <el-table-column label="差值" width="96" align="right">
            <template #default="{ row }"><span :class="healthToneClass(row.improvement)">{{ fmtDiff(row.diff) }}</span></template>
          </el-table-column>
          <el-table-column label="上周期状态" width="110" align="center">
            <template #default="{ row }"><StatusTag :status="row.period1Status" /></template>
          </el-table-column>
          <el-table-column label="本周期状态" width="110" align="center">
            <template #default="{ row }"><StatusTag :status="row.period2Status" /></template>
          </el-table-column>
          <el-table-column label="环比评价" width="160" align="center">
            <template #default="{ row }">
              <span :class="healthToneClass(row.improvement)">{{ healthVerdict(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="采样数（上 / 本）" width="140" align="center">
            <template #default="{ row }"><span class="muted">{{ row.period1Count }} / {{ row.period2Count }}</span></template>
          </el-table-column>
        </el-table>
        </template>
      </section>

      <section v-if="result.subCategoryHealth.length" class="pc-section">
        <header class="pc-section-head">
          <h3>指标小类健康度环比</h3>
          <span class="pc-period-note">{{ periodNote }} · {{ methodLabel }} · 单位：分</span>
        </header>
        <p v-if="!hasSubCategoryHealthData" class="pc-no-history">该时段没有健康度历史：健康度快照要等算分任务跑过才有数据，采集入库本身不写这张表。</p>
        <template v-else>
        <CompareBarChart :rows="result.subCategoryHealth" health />
        <el-table :data="result.subCategoryHealth" border size="small" class="pc-table">
          <el-table-column prop="categoryName" label="指标大类" width="140" />
          <el-table-column prop="name" label="指标小类" min-width="150" />
          <el-table-column label="上周期" width="100" align="right">
            <template #default="{ row }"><span :class="statusToneClass(row.period1Status)">{{ fmt(row.period1Value) }}</span></template>
          </el-table-column>
          <el-table-column label="本周期" width="100" align="right">
            <template #default="{ row }"><span :class="statusToneClass(row.period2Status)">{{ fmt(row.period2Value) }}</span></template>
          </el-table-column>
          <el-table-column label="差值" width="96" align="right">
            <template #default="{ row }"><span :class="healthToneClass(row.improvement)">{{ fmtDiff(row.diff) }}</span></template>
          </el-table-column>
          <el-table-column label="上周期状态" width="110" align="center">
            <template #default="{ row }"><StatusTag :status="row.period1Status" /></template>
          </el-table-column>
          <el-table-column label="本周期状态" width="110" align="center">
            <template #default="{ row }"><StatusTag :status="row.period2Status" /></template>
          </el-table-column>
          <el-table-column label="环比评价" width="160" align="center">
            <template #default="{ row }">
              <span :class="healthToneClass(row.improvement)">{{ healthVerdict(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="采样数（上 / 本）" width="140" align="center">
            <template #default="{ row }"><span class="muted">{{ row.period1Count }} / {{ row.period2Count }}</span></template>
          </el-table-column>
        </el-table>
        </template>
      </section>

      <section v-if="result.metricValues.length" class="pc-section">
        <header class="pc-section-head">
          <h3>指标值对比</h3>
          <span class="pc-period-note">{{ periodNote }} · {{ methodLabel }}</span>
        </header>
        <CompareBarChart :rows="result.metricValues" />
        <el-table :data="result.metricValues" border size="small" class="pc-table">
          <el-table-column prop="categoryName" label="指标大类" width="130" />
          <el-table-column prop="subCategoryName" label="指标小类" width="130" />
          <el-table-column prop="name" label="指标名称" min-width="160" />
          <el-table-column label="上周期" width="120" align="right">
            <template #default="{ row }">{{ fmtWithUnit(row.period1Value, row.unit) }}</template>
          </el-table-column>
          <el-table-column label="本周期" width="120" align="right">
            <template #default="{ row }">{{ fmtWithUnit(row.period2Value, row.unit) }}</template>
          </el-table-column>
          <el-table-column label="差值" width="110" align="right">
            <template #default="{ row }">
              <span :class="toneClass(row.improvement)">{{ fmtDiffWithUnit(row.diff, row.unit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="变化率" width="96" align="right">
            <template #default="{ row }">{{ fmtRate(row.changeRate) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <el-empty v-if="emptyResult" description="所选范围内暂无环比数据" />
    </div>
    <el-empty v-else-if="!loading" description="选择筛选条件后点击「查询」" />
  </div>
</template>

<script setup>
import { computed, h, nextTick, onMounted, ref, watch } from 'vue'
import { ElTag } from 'element-plus'
import { metricSearchApi, periodComparisonApi } from '@/api'
import CompareBarChart from './CompareBarChart.vue'

const CATEGORY_ORDER = ['对外部客户', '合规管理', '满意度及投诉服务体验', '资源投入', '业务价值', '数据质量', '对内部人员', '运行效率']

/** 健康度状态标签：正常/关注/异常，与全平台三态一致 */
const StatusTag = (props) => {
  const map = {
    normal: { type: 'success', label: '正常' },
    warning: { type: 'warning', label: '关注' },
    alarm: { type: 'danger', label: '异常' }
  }
  const hit = map[props.status]
  if (!hit) return h('span', { class: 'muted' }, '—')
  return h(ElTag, { type: hit.type, size: 'small', effect: 'light' }, () => hit.label)
}
StatusTag.props = { status: { type: String, default: null } }

const catalog = ref([])
const selectedCategories = ref([])
const selectedSubCategories = ref([])
const selectedMetricIds = ref([])
const method = ref('avg')
const period1 = ref(defaultPeriod1())
const period2 = ref(defaultPeriod2())
const loading = ref(false)
const result = ref(null)

const categoryOptions = computed(() => {
  const names = [...new Set(catalog.value.map(m => m.categoryName).filter(Boolean))]
  return names.sort((a, b) => {
    const ia = CATEGORY_ORDER.indexOf(a)
    const ib = CATEGORY_ORDER.indexOf(b)
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
  })
})

const subCategoryOptions = computed(() => {
  const cats = new Set(selectedCategories.value)
  const seen = new Set()
  const out = []
  catalog.value.forEach(m => {
    if (!cats.has(m.categoryName) || !m.subCategoryName) return
    const key = m.categoryName + '|' + m.subCategoryName
    if (seen.has(key)) return
    seen.add(key)
    out.push({ value: key, label: m.categoryName + ' / ' + m.subCategoryName })
  })
  return out
})

const metricOptions = computed(() => {
  const cats = new Set(selectedCategories.value)
  const subs = new Set(selectedSubCategories.value)
  return catalog.value
    .filter(m => cats.has(m.categoryName))
    .filter(m => subs.size === 0 || subs.has(m.categoryName + '|' + m.subCategoryName))
    .map(m => ({
      value: m.metricId,
      label: m.subCategoryName ? m.metricName + '（' + m.categoryName + ' / ' + m.subCategoryName + '）' : m.metricName + '（' + m.categoryName + '）'
    }))
})

const methodLabel = computed(() => method.value === 'median' ? '中位数' : '平均')
const periodNote = computed(() => {
  if (!result.value) return ''
  const p1 = result.value.period1
  const p2 = result.value.period2
  const d = s => String(s || '').slice(0, 10)
  const len = result.value.periodLength
  const span = len ? `（${len.period1Days} 天 / ${len.period2Days} 天）` : ''
  return `上周期 ${d(p1.start)} ~ ${d(p1.end)} → 本周期 ${d(p2.start)} ~ ${d(p2.end)}${span}`
})

const emptyResult = computed(() => {
  if (!result.value) return false
  return !result.value.categoryHealth.length && !result.value.subCategoryHealth.length && !result.value.metricValues.length
})

/**
 * 健康度区块有没有真实分数。
 * category_health_history / sub_category_health_history 只在健康度算分跑过之后才有行，
 * 采集 Worker 直接落 collection_row 的环境里这两张表是空的——那时区块里全是「—」，
 * 看上去像页面坏了，所以整块换成一句说明。
 */
const hasCategoryHealthData = computed(() =>
  (result.value?.categoryHealth || []).some(r => r.period1Value != null || r.period2Value != null))
const hasSubCategoryHealthData = computed(() =>
  (result.value?.subCategoryHealth || []).some(r => r.period1Value != null || r.period2Value != null))

// 级联：上游变化时清理下游已选中的无效项
watch(selectedCategories, () => {
  const validSubs = new Set(subCategoryOptions.value.map(s => s.value))
  selectedSubCategories.value = selectedSubCategories.value.filter(v => validSubs.has(v))
  const validMetrics = new Set(metricOptions.value.map(m => m.value))
  selectedMetricIds.value = selectedMetricIds.value.filter(v => validMetrics.has(v))
})
watch(selectedSubCategories, () => {
  const validMetrics = new Set(metricOptions.value.map(m => m.value))
  selectedMetricIds.value = selectedMetricIds.value.filter(v => validMetrics.has(v))
})

/**
 * 默认周期：本周期 = 本月 1 号 ~ 今天，上周期 = 上月 1 号 ~ 上月同日。
 * 上周期不用「上月整月」：今天 11-23 时上月是 31 天、本月只有 23 天，累计型指标（工单数、告警数）
 * 的差值必然为负，用户会把「天数少」读成「劣化」。同期对齐后两段长度最多差 1 天。
 * 上月没有今天这一天时（3-31 对应 2 月）夹取到上月最后一天，避免 Date 自动溢出到下个月。
 */
function defaultPeriod1 () {
  const now = new Date()
  const prevMonth = now.getMonth() - 1
  const lastDayOfPrevMonth = new Date(now.getFullYear(), now.getMonth(), 0).getDate()
  const start = new Date(now.getFullYear(), prevMonth, 1)
  const end = new Date(now.getFullYear(), prevMonth, Math.min(now.getDate(), lastDayOfPrevMonth))
  return [fmtDate(start), fmtDate(end)]
}
function defaultPeriod2 () {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  return [fmtDate(first), fmtDate(now)]
}
function fmtDate (d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
function toDateTime (date, isEnd) {
  if (!date) return ''
  return isEnd ? `${date} 23:59:59` : `${date} 00:00:00`
}

async function query () {
  if (!selectedCategories.value.length && !selectedSubCategories.value.length && !selectedMetricIds.value.length) {
    result.value = null
    return
  }
  if (!period1.value || period1.value.length !== 2 || !period2.value || period2.value.length !== 2) {
    result.value = null
    return
  }
  loading.value = true
  try {
    const payload = {
      categoryNames: selectedCategories.value,
      subCategories: selectedSubCategories.value.map(v => {
        const idx = v.indexOf('|')
        return { categoryName: v.slice(0, idx), subCategoryName: v.slice(idx + 1) }
      }),
      metricIds: selectedMetricIds.value,
      method: method.value,
      period1Start: toDateTime(period1.value[0], false),
      period1End: toDateTime(period1.value[1], true),
      period2Start: toDateTime(period2.value[0], false),
      period2End: toDateTime(period2.value[1], true)
    }
    const res = await periodComparisonApi.compare(payload)
    result.value = res.data
  } catch (e) {
    // 拦截器已统一提示
  } finally {
    loading.value = false
  }
}

/**
 * 进页默认视图：不选条件时整页是空的，用户不知道该从哪选。
 *
 * <p>默认挑「最近 30 天有采集记录的指标」，每个大类最多 2 个、共 8 个，选好直接查一次。
 * 默认落在指标值维度而不是健康度维度：健康度历史要等算分任务跑过才有行，按大类做默认
 * 在这种环境下只能得到一屏「—」。指标下拉按大类过滤，所以把命中指标所属大类一并选上。
 */
async function applyDefaultSelection () {
  try {
    const res = await periodComparisonApi.activeMetrics({ days: 30, perCategory: 2, limit: 8 })
    const rows = res.data || []
    if (!rows.length) {
      result.value = null
      return
    }
    selectedCategories.value = [...new Set(rows.map(r => r.categoryName).filter(Boolean))]
    await nextTick()
    selectedMetricIds.value = rows.map(r => r.metricId)
    await query()
  } catch (e) {
    // 预选失败就保持空白页，由用户自己选；拦截器已统一提示
  }
}

function reset () {
  selectedCategories.value = []
  selectedSubCategories.value = []
  selectedMetricIds.value = []
  method.value = 'avg'
  period1.value = defaultPeriod1()
  period2.value = defaultPeriod2()
  result.value = null
  applyDefaultSelection()
}

function fmt (v) {
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : '—'
}
function fmtWithUnit (v, unit) {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return String(Math.round(n * 100) / 100) + (unit || '')
}
function fmtDiff (v) {
  if (v == null) return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return (n > 0 ? '+' : '') + String(Math.round(n * 100) / 100)
}
function fmtDiffWithUnit (v, unit) {
  if (v == null) return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return (n > 0 ? '+' : '') + String(Math.round(n * 100) / 100) + (unit || '')
}
function fmtRate (v) {
  if (v == null) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + String(Math.round(n * 100) / 100) + '%'
}

const STATUS_LABEL = { normal: '正常', warning: '关注', alarm: '异常' }
function statusLabel (s) { return STATUS_LABEL[s] || '无数据' }

/**
 * 健康度环比评价：跨档才是实质改善/劣化。
 * 同档内分数变化用中性色只报差值，避免 72→76 这种档内微调被染成绿色「改善」。
 */
function healthVerdict (row) {
  const imp = row.improvement
  if (imp === 'improved') return '↑ 升档（' + statusLabel(row.period1Status) + ' → ' + statusLabel(row.period2Status) + '）'
  if (imp === 'deteriorated') return '↓ 降档（' + statusLabel(row.period1Status) + ' → ' + statusLabel(row.period2Status) + '）'
  if (imp === 'same_level') return '同档微调（' + statusLabel(row.period2Status) + '）'
  if (imp === 'flat') return '持平'
  return '—'
}
function healthToneClass (imp) {
  if (imp === 'improved') return 'tone-good'
  if (imp === 'deteriorated') return 'tone-bad'
  return 'tone-muted'
}

/** 语义色：改善绿、劣化红、持平/方向不明/不变灰 */
function toneClass (v) {
  if (v === 'improved') return 'tone-good'
  if (v === 'deteriorated') return 'tone-bad'
  return 'tone-muted'
}
/** 健康度分数按所处档位着色（异常红 / 关注橙 / 正常绿） */
function statusToneClass (s) {
  return s === 'alarm' ? 'tone-bad' : s === 'warning' ? 'tone-warn' : s === 'normal' ? 'tone-good' : 'tone-muted'
}

onMounted(async () => {
  try {
    const res = await metricSearchApi.catalog()
    catalog.value = res.data || []
  } catch (e) {
    // 拦截器已统一提示
  }
  await applyDefaultSelection()
})
</script>

<style scoped>
.period-compare { display: flex; flex-direction: column; gap: 12px; }
.pc-filters { padding: 12px 14px; border: 1px solid var(--ui-border); border-radius: 8px; background: var(--ui-surface); }
.filter-label { color: var(--ui-muted); font-size: 12px; white-space: nowrap; }
.pc-results { display: flex; flex-direction: column; gap: 16px; }
.pc-section { border: 1px solid var(--ui-border); border-radius: 8px; background: var(--ui-surface); padding: 14px 16px; }
.pc-section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.pc-section-head h3 { margin: 0; font-size: 14px; font-weight: 600; color: var(--ui-heading); }
.pc-period-note { font-size: 12px; color: var(--ui-muted); }
.pc-table { margin-top: 12px; }
.pc-no-history { margin: 6px 0 0; font-size: 12px; color: var(--ui-muted); }
.muted { color: var(--ui-muted); }
.tone-good { color: #2f9e6e; font-weight: 600; }
.tone-bad { color: #d64545; font-weight: 600; }
.tone-warn { color: #b45309; font-weight: 600; }
.tone-muted { color: var(--ui-muted); }
</style>