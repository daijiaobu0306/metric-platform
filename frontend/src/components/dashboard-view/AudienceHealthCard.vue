<!--
  人员角色健康度卡片（专屏「健康度观测」页签底部）
  ============================================================================
  2026-11-17 调整：
  1) 角色（岗位）维度不再作为专屏顶层过滤条件 —— 原来在工具栏选岗位会把
     闭环管理等整屏数据一起过滤，权限层级过高；收敛成页签末尾的一张卡片。
  2) 位置放页签最底部：先看「系统综合健康度」，再按角色下钻。
  3) 趋势补刻度：原迷你折线没有坐标参考，看不出高低。

  2026-11-18 调整：
  1) 趋势图直接复用「系统综合健康度趋势」同一个组件（HealthTrendChart）：
     Y 轴刻度 + 「分」单位、X 轴时间刻度、悬停读数、关注/异常分界线、
     最新点标签、区间与纵轴放大提示，不再是只有首尾两个数字的迷你折线。
  2) 角色列表不再只从「当前专屏可见指标」反推 —— 那样没指标的岗位会整块消失，
     看起来像写死了三个角色。现在以岗位字典（metric_audience，启用中的）
     为骨架，再合并指标上实际出现的岗位：
       · 字典里有、本专屏范围内没指标 → 仍然显示，标 0/0 并说明原因；
       · 卡片上有、字典里没有（停用岗位或历史编码）→ 也显示，不让数据凭空消失；
       · 指标改了「面向人员」或字典新增岗位 → 这里自动同步，不需要改代码。

  数据来源：父组件传入的 healthCards（已按当前专屏授权范围取数），
  卡片只做「按角色分组 + 算术平均」，不额外请求业务数据、不扩大可见范围；
  岗位字典只用来补齐角色清单。
  ============================================================================
-->
<template>
  <!-- 有指标数据才出这张卡：卡片要回答「各角色怎么样」，没数据时整块留白比一堆 0/0 更清楚 -->
  <section v-if="healthCards.length && roles.length" class="role-health-card">
    <header>
      <div class="role-head-text">
        <h3>人员角色健康度</h3>
        <small>{{ view.isOverall ? '分数为当前时间窗区间均分（按采样点加权），各角色等权平均' : '分数为当前时间窗区间均分（按采样点加权）' }} · 角色来自指标配置的「面向人员」，随配置自动同步</small>
      </div>
      <el-radio-group v-model="selected" size="small" class="role-switch">
        <el-radio-button :value="OVERALL" class="role-switch-all">
          角色健康度<span class="role-count">{{ eligibleRoles.length }}/{{ roles.length }}</span>
        </el-radio-button>
        <el-radio-button v-for="role in roles" :key="role.code" :value="role.code" :title="roleTitle(role)">
          {{ role.name }}<span class="role-count">{{ role.total ? `${role.scored}/${role.total}` : '本屏无指标' }}</span>
        </el-radio-button>
      </el-radio-group>
    </header>

    <div class="role-body">
      <div class="role-score-block">
        <div class="role-score" :class="tone(view.score)">
          <b>{{ view.score ?? '-' }}</b>
          <span>{{ view.score == null ? (view.total ? '暂无健康度' : '无指标') : statusText(view.score) }}</span>
        </div>
        <small class="role-score-hint">{{ statusHint(view) }}</small>      </div>

      <div class="role-body-main">
        <div v-if="view.isOverall" class="role-meta">
          <span>有数据角色 <b>{{ view.roleCount }}</b><i>/{{ view.totalRoles }}</i></span>
          <span>指标 <b>{{ view.total || 0 }}</b></span>
          <span class="danger">异常角色 <b>{{ view.dangerRoles }}</b></span>
          <span class="warning">关注角色 <b>{{ view.warningRoles }}</b></span>
        </div>
        <div v-else class="role-meta">
          <span>指标 <b>{{ view.total || 0 }}</b></span>
          <span>有健康度 <b>{{ view.scored || 0 }}</b></span>
          <span class="danger">异常 <b>{{ view.danger || 0 }}</b></span>
          <span class="warning">关注 <b>{{ view.warning || 0 }}</b></span>
        </div>

        <!-- 整体视角：列出各角色分数（低的在前），点一下即下钻到该角色 -->
        <template v-if="view.isOverall">
          <div v-if="view.roleCount" class="role-attention">
            <span class="attention-label">各角色</span>
            <button v-for="role in view.roleList" :key="role.code" type="button" class="attention-item" :title="`查看「${role.name}」的指标明细`" @click="selected = role.code">
              {{ role.name }}<b :class="tone(role.score)">{{ role.score ?? '-' }}</b>
            </button>
          </div>
          <div v-else class="role-attention empty">本专屏授权范围内暂无有健康度的角色</div>
        </template>

        <template v-else>
          <div v-if="!view.total" class="role-attention empty">
            <template v-if="view.dictMetricCount">本专屏授权范围内没有该角色的指标（全平台引用 {{ view.dictMetricCount }} 项，可能不在本屏授权范围或为不计分白名单）；该角色不参与整体平均。</template>
            <template v-else>本专屏授权范围内没有该角色的指标，可在「指标体系配置」里给指标设置「面向人员」</template>
          </div>
          <div v-else-if="needAttention.length" class="role-attention">
            <span class="attention-label">需关注</span>
            <button v-for="item in needAttention" :key="item.metricId" type="button" class="attention-item" @click="$emit('metric-detail', item)">
              {{ item.metricName }}<b :class="tone(item.healthScore)">{{ item.healthScore }}</b>
            </button>
            <span v-if="view.danger + view.warning > needAttention.length" class="attention-more">等 {{ view.danger + view.warning }} 项</span>
          </div>
          <div v-else class="role-attention empty">当前角色下没有异常或关注指标</div>
        </template>
      </div>

      <div class="role-trend">
        <div class="trend-head">
          <span>健康度趋势</span>
          <span class="trend-sub">{{ view.isOverall ? `各角色均值 · ${days} 天` : `${days} 天` }} · 虚线为关注 / 异常分界</span>
        </div>
        <HealthTrendChart v-if="view.spark && view.spark.length" :points="view.spark" :height="150" />
        <div v-else class="trend-empty">暂无趋势数据</div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { healthThresholds, scoreLevel } from '@/utils/healthThresholds'
import { metricApi } from '@/api'
import { computed, onMounted, ref, watch } from 'vue'
import HealthTrendChart from './HealthTrendChart.vue'

const props = defineProps({
  /** 专屏健康度卡片（已按授权范围取数，含 metricId/audienceCode/audienceName/healthScore/trend） */
  healthCards: { type: Array, default: () => [] },
  days: { type: Number, default: 7 },
  /**
   * 是否加载岗位字典。
   * 分享页（H5/APP 只读页）是匿名访问，没有平台会话，调需要登录的字典接口会被 401
   * 并触发全局跳登录，因此那边传 false：角色清单只从授权范围内的卡片反推。
   */
  showDict: { type: Boolean, default: true }
})
defineEmits(['metric-detail'])

const selected = ref('')
/** 整体视角的伪编码：不是真实岗位，只用于页签选中态 */
const OVERALL = '__overall__'
selected.value = OVERALL

/**
 * 岗位字典（启用中的岗位，按 sort_order）。
 * 只用来补齐「角色清单」—— 分数和指标数一律来自当前专屏的 healthCards，
 * 字典里的 metricCount 是全平台口径，不能拿来当本专屏的指标数。
 */
const audienceDict = ref([])
onMounted(async () => {
  // 分享页没有平台会话，不请求需要登录的字典接口
  // （share 页面 showDict 已为 false；这里再用 sessionStorage 兜底，避免 PC 分享页漏传时触发 401）
  if (!props.showDict || !sessionStorage.getItem('sessionKey')) return
  try {
    // 拦截器返回 { code, message, data } 整体，字典在 data 里（2026-11-18 修正解包）
    const res = await metricApi.audienceList()
    const list = res?.data || []
    audienceDict.value = (list || [])
      .filter(item => item && item.audienceCode)
      .map(item => ({
        code: item.audienceCode,
        name: item.audienceName,
        sortOrder: Number(item.sortOrder || 0),
        // 全平台引用指标数：只用于解释「本专屏 0 项」与岗位字典 32 项的差异，不参与本专屏取数
        metricCount: Number(item.metricCount || 0)
      }))
  } catch {
    // 字典取不到不影响主功能：roles 会退回「按卡片上的岗位聚合」
    audienceDict.value = []
  }
})

const tone = (score) => {
  if (score == null) return 'unknown'
  if (scoreLevel(score) === 'danger') return 'danger'
  if (scoreLevel(score) === 'warning') return 'warning'
  return 'healthy'
}
const statusText = (score) => {
  const value = tone(score)
  return value === 'danger' ? '异常' : value === 'warning' ? '关注' : value === 'unknown' ? '暂无健康度' : '正常'
}
const statusHint = (target) => {
  if (!target) return ''
  if (target.isOverall) {
    if (!target.roleCount) return '本专屏范围内暂无可计算的角色'
    return `${target.roleCount} 个有指标角色等权平均（区间均分按采样点加权；本专屏无指标岗位不参与）`
  }
  if (!target.total) {
    // 专屏只统计授权范围内的指标：岗位字典里的全平台引用数可能与这里不一致，
    // 直接把两个口径写出来，避免被误读成「该岗位健康度 0 分」。
    return target.dictMetricCount
      ? `本专屏授权范围 0 项（全平台引用 ${target.dictMetricCount} 项），不参与角色平均`
      : '本专屏范围内暂无该角色指标'
  }
  const value = tone(target.score)
  if (value === 'danger') return `低于异常线 ${healthThresholds.alarm}`
  if (value === 'warning') return `低于关注线 ${healthThresholds.warning}`
  if (value === 'healthy') return `高于关注线 ${healthThresholds.warning}`
  return '暂无健康度数据'
}

/** 角色页签悬停说明：本屏指标数与全平台引用数分开写。 */
const roleTitle = (role) => {
  if (!role.total) {
    return role.dictMetricCount
      ? `全平台引用 ${role.dictMetricCount} 项指标，但这些指标不在当前专屏授权范围，本屏不参与角色平均`
      : '当前专屏授权范围内没有该角色的指标'
  }
  return `本屏 ${role.scored}/${role.total} 项有健康度；分数为当前时间窗区间均分`
}

/**
 * 先按岗位把卡片聚合起来。
 *
 * <p>分数口径与健康度观测页一致：优先用后端算好的「当前时间窗区间均分」
 * （windowHealthScore/windowHealthSamples，按采样点数加权），没有窗口均值的旧数据
 * 才退回最新 healthScore。这样角色分数、趋势曲线、指标卡片的读数来自同一时间窗，
 * 不会出现「趋势里是 0/99 分、角色分却是 100 分」的错位。
 */
const grouped = computed(() => {
  const map = new Map()
  ;(props.healthCards || []).forEach(card => {
    const name = card.audienceName
    if (!name) return
    const code = card.audienceCode || name
    let role = map.get(code)
    if (!role) {
      role = { code, name, total: 0, scored: 0, sum: 0, latestCount: 0, windowSum: 0, windowSamples: 0, danger: 0, warning: 0, metrics: [], trend: new Map() }
      map.set(code, role)
    }
    role.total++
    role.metrics.push(card)
    const latest = card.healthScore != null && Number.isFinite(Number(card.healthScore)) ? Number(card.healthScore) : null
    const windowSamples = card.windowHealthScore != null ? Number(card.windowHealthSamples || 0) : 0
    const windowScore = windowSamples > 0 && Number.isFinite(Number(card.windowHealthScore)) ? Number(card.windowHealthScore) : null
    const effective = windowScore != null ? windowScore : latest
    if (effective != null) {
      role.scored++
      if (windowScore != null) {
        role.windowSum += windowScore * windowSamples
        role.windowSamples += windowSamples
      } else if (latest != null) {
        role.sum += latest
        role.latestCount++
      }
      if (scoreLevel(effective) === 'danger') role.danger++
      else if (scoreLevel(effective) === 'warning') role.warning++
    }
    ;(card.trend || []).forEach(point => {
      const scoreValue = Number(point.healthScore)
      const time = String(point.recordTime || point.time || '')
      if (!Number.isFinite(scoreValue) || !time) return
      const bucket = role.trend.get(time) || { sum: 0, count: 0 }
      bucket.sum += scoreValue
      bucket.count++
      role.trend.set(time, bucket)
    })
  })
  return map
})

/** 角色区间均分：窗口采样点加权；没有窗口均值时退回最新分平均。 */
function roleAverage (role) {
  if (!role) return null
  if (role.windowSamples > 0) return role.windowSum / role.windowSamples
  if (role.latestCount > 0) return role.sum / role.latestCount
  return null
}

/**
 * 角色清单 = 岗位字典（启用中的，按 sort_order）+ 卡片上多出来的岗位。
 * 字典在前保证顺序稳定；卡片上多出来的（停用岗位/历史编码）追加在后面，
 * 这样「指标挂了这个岗位」永远不会因为字典没开就查不到数据。
 */
const roles = computed(() => {
  const map = grouped.value
  const list = []
  const seen = new Set()
  const push = (code, name, sortOrder, fromDict, dictMetricCount = 0) => {
    seen.add(code)
    const role = map.get(code)
    const avg = roleAverage(role)
    list.push({
      code,
      name: name || role?.name || code,
      sortOrder,
      fromDict,
      dictMetricCount,
      total: role?.total || 0,
      scored: role?.scored || 0,
      score: avg == null ? null : Math.round(avg),
      // 未四舍五入的均值：整体视角要对「各角色均值」再平均，先舍入会引入偏差
      avg,
      // 原始时间桶（sum/count），整体趋势要按角色均值再合并
      trend: role ? role.trend : new Map(),
      danger: role?.danger || 0,
      warning: role?.warning || 0,
      metrics: role?.metrics || [],
      spark: role
        ? [...role.trend.entries()]
          .sort((a, b) => a[0].localeCompare(b[0]))
          .map(([time, bucket]) => ({
            time,
            healthScore: Math.round(bucket.sum / bucket.count),
            // count/total 交给趋势图判断低采样时间窗，避免个别指标刚更新就被当成整体曲线
            count: bucket.count,
            total: role.total
          }))
        : []
    })
  }
  audienceDict.value.forEach(item => push(item.code, item.name, item.sortOrder, true, item.metricCount))
  ;[...map.values()]
    .filter(role => !seen.has(role.code))
    .sort((a, b) => (b.scored - a.scored) || a.name.localeCompare(b.name))
    .forEach(role => push(role.code, role.name, 999, false, 0))
  return list
})

const current = computed(() => roles.value.find(role => role.code === selected.value) || roles.value[0] || {})

/**
 * 参与「整体健康度」的角色：有健康度分数 + 启用中。
 *
 * <p>启用与否以岗位字典为准（字典接口只返回启用岗位）；若字典没取到（网络异常），
 * 就退化成「所有有分数的角色」，避免整块变成 '-'。
 */
const eligibleRoles = computed(() => {
  const dictLoaded = audienceDict.value.length > 0
  return roles.value.filter(role => role.scored > 0 && (!dictLoaded || role.fromDict))
})

/**
 * 整体视角（总-分里的“总”）：各角色健康度的等权平均。
 * 不用「所有指标平均」，否则指标多的角色会淹没指标少的角色 —— 用户口径是“按角色求平均”。
 */
const overall = computed(() => {
  const list = eligibleRoles.value
  const buckets = new Map()
  list.forEach(role => {
    role.trend.forEach((bucket, time) => {
      if (!bucket.count) return
      const cur = buckets.get(time) || { sum: 0, count: 0 }
      // 该角色在该时刻的均值，再对所有角色取平均（角色等权）
      cur.sum += bucket.sum / bucket.count
      cur.count++
      buckets.set(time, cur)
    })
  })
  const spark = [...buckets.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([time, bucket]) => ({
      time,
      healthScore: Math.round(bucket.sum / bucket.count),
      count: bucket.count,
      total: list.length
    }))
  return {
    code: OVERALL,
    name: '角色健康度',
    isOverall: true,
    total: roles.value.reduce((n, role) => n + role.total, 0),
    scored: roles.value.reduce((n, role) => n + role.scored, 0),
    score: list.length ? Math.round(list.reduce((n, role) => n + role.avg, 0) / list.length) : null,
    roleCount: list.length,
    totalRoles: roles.value.length,
    dangerRoles: list.filter(role => tone(role.score) === 'danger').length,
    warningRoles: list.filter(role => tone(role.score) === 'warning').length,
    roleList: [...list].sort((a, b) => (a.avg ?? 0) - (b.avg ?? 0)),
    spark
  }
})

/** 当前展示的数据：整体 or 具体角色 */
const view = computed(() => selected.value === OVERALL ? overall.value : current.value)

/** 该角色下分数最低的 3 个指标，经理一眼看到是谁拖后腿。 */
const needAttention = computed(() => (current.value.metrics || [])
  .filter(item => item.healthScore != null && scoreLevel(item.healthScore) !== 'normal')
  .sort((a, b) => Number(a.healthScore) - Number(b.healthScore))
  .slice(0, 3))

/**
 * 默认固定为「角色健康度」（整体视角），每次进入专屏都是这个。
 * 用户已经选中某个角色时保留选择；只有该角色整条消失才回落到整体。
 */
watch(roles, list => {
  if (selected.value === OVERALL) return
  if (list.some(role => role.code === selected.value)) return
  selected.value = OVERALL
}, { immediate: true })
</script>

<style scoped>
.role-health-card { display: grid; gap: 10px; padding: 14px 16px; border-radius: 12px; background: var(--ui-surface); border: 1px solid var(--ui-border); }
.role-health-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.role-head-text h3 { margin: 0 0 3px; font-size: 15px; color: var(--ui-heading); }
.role-head-text small { color: var(--ui-subtle); font-size: 11px; }
.role-switch { max-width: 100%; overflow-x: auto; }
.role-count { margin-left: 4px; font-size: 10px; opacity: .7; }
.role-body { display: grid; grid-template-columns: 132px minmax(0, 1fr) minmax(320px, 1.1fr); align-items: center; gap: 18px; }
.role-score-block { display: grid; gap: 2px; min-width: 0; }
.role-score { display: flex; align-items: baseline; gap: 6px; }
.role-score b { font-size: 34px; line-height: 1; color: var(--ui-heading); font-variant-numeric: tabular-nums; }
.role-score span { font-size: 12px; color: var(--ui-muted); }
.role-score.healthy b { color: #14855f; }
.role-score.warning b { color: #b7791f; }
.role-score.danger b { color: #c53030; }
.role-score-hint { color: var(--ui-subtle); font-size: 10px; line-height: 1.4; }
.role-body-main { display: grid; gap: 7px; min-width: 0; }
.role-meta { display: flex; gap: 16px; flex-wrap: wrap; color: var(--ui-muted); font-size: 12px; }
.role-meta b { color: var(--ui-heading); font-variant-numeric: tabular-nums; }
.role-meta i { font-style: normal; margin-left: 1px; color: var(--ui-subtle); font-size: 11px; }
/*
 * 「角色健康度」是整体视角，不是真实岗位，用常驻浅蓝做区分：
 * 未选中时浅蓝底 + 蓝字，选中时加深一档但仍是浅蓝（不改实心主色），
 * 这样在一排角色页签里一眼能认出哪个是「总」。
 */
.role-switch :deep(.role-switch-all .el-radio-button__inner) {
  background: #e8f3ff;
  border-color: #b7d8fb;
  color: #1d6fb8;
  font-weight: 600;
}
.role-switch :deep(.role-switch-all:hover .el-radio-button__inner) {
  background: #dcefff;
  border-color: #8fc2f0;
  color: #1d6fb8;
}
.role-switch :deep(.role-switch-all .el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: #cbe4ff;
  border-color: #5b9fe0;
  color: #124f88;
  box-shadow: -1px 0 0 0 #5b9fe0;
}
.role-meta .danger b { color: #c53030; }
.role-meta .warning b { color: #b7791f; }
.role-attention { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 11px; }
.role-attention.empty { color: var(--ui-subtle); line-height: 1.5; }
.attention-label { color: var(--ui-subtle); }
.attention-item { display: inline-flex; align-items: center; gap: 4px; max-width: 200px; padding: 2px 8px; border: 1px solid var(--ui-border-soft); border-radius: 999px; background: var(--ui-page); color: var(--ui-heading); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.attention-item:hover { border-color: var(--ui-primary); }
.attention-item b { font-variant-numeric: tabular-nums; }
.attention-item b.danger { color: #c53030; }
.attention-item b.warning { color: #b7791f; }
.attention-more { color: var(--ui-subtle); }
.role-trend { display: grid; gap: 4px; min-width: 0; }
.trend-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; color: var(--metric-text, var(--ui-heading)); font-size: 11px; font-weight: 600; }
.trend-sub { color: var(--ui-subtle); font-size: 10px; font-weight: 400; }
.trend-empty { display: grid; place-items: center; min-height: 96px; border: 1px dashed var(--ui-border-soft); border-radius: 8px; color: var(--ui-subtle); font-size: 11px; }
@media (max-width: 1280px) { .role-body { grid-template-columns: 120px minmax(0, 1fr); } .role-trend { grid-column: 1 / -1; } }
@media (max-width: 760px) { .role-body { grid-template-columns: 1fr; gap: 12px; } .role-trend { grid-column: auto; } .role-health-card header { flex-direction: column; align-items: stretch; gap: 8px; } .role-switch { width: 100%; } .role-attention { gap: 5px; } .attention-item { max-width: 100%; } }
</style>
