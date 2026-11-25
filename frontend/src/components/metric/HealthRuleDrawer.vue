<template>
  <el-drawer
    :model-value="modelValue"
    class="workspace-theme hr-drawer-wrap"
    size="94%"
    :with-header="false"
    destroy-on-close
    @update:model-value="value => emit('update:modelValue', value)"
  >
    <div class="hr-drawer">
      <header class="hr-head">
        <div class="hr-title">
          <h2>健康度规则配置</h2>
          <p>白名单、人工基线、动态基线都在这里配；勾选即“不计分”，改完保存会立即重算并留审计。</p>
        </div>
        <div class="hr-head-stats">
          <span class="hr-chip">白名单 <b>{{ summary.exempt ?? '-' }}</b></span>
          <span class="hr-chip">参与计分 <b>{{ summary.eligible ?? '-' }}</b></span>
          <span class="hr-chip">已出分 <b>{{ summary.scored ?? '-' }}</b></span>
          <span class="hr-chip" :class="{ warn: (summary.learning || 0) > 0 }">学习中 <b>{{ summary.learning ?? '-' }}</b></span>
          <span class="hr-chip" :class="{ warn: (summary.missing || 0) > 0 }">缺数 <b>{{ summary.missing ?? '-' }}</b></span>
          <span class="hr-chip">覆盖率 <b>{{ summary.coverageRate ?? 0 }}%</b></span>
        </div>
        <div class="hr-head-actions">
          <el-tooltip content="按当前规则立即重算一遍，并刷新聚合" placement="bottom">
            <el-button :loading="recalculating" @click="recalculate">
              <el-icon><Refresh /></el-icon>立即重算
            </el-button>
          </el-tooltip>
          <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        </div>
      </header>

      <el-alert
        v-if="config.sampleMode && config.sampleMode.includes('mock')"
        type="warning"
        :closable="false"
        show-icon
        class="hr-sample-hint"
        title="当前库为样例/演练数据：动态基线会把样例数据一起当样本（页面标注“含样例数据”）。生产环境把 system_config 的 metric.health.baseline.sample-mode 配回 collected 即可。"
      />

      <el-tabs v-model="tab" class="hr-tabs">
        <!-- ================= 规则配置 ================= -->
        <el-tab-pane label="规则配置" name="rules">
          <div class="hr-body">
            <aside class="hr-left" v-loading="loading" :style="{ width: leftWidth + 'px' }">
              <div class="hr-left-tools">
                <el-input v-model="keyword" size="small" placeholder="搜索指标名 / 编码" clearable />
                <el-button size="small" @click="checkVisible(true)">全选结果</el-button>
                <el-button size="small" @click="checkVisible(false)">清空结果</el-button>
              </div>
              <p class="hr-left-hint">勾选 = 不计分（仅观察）。勾大类/小类会级联子级，取消任一子指标父级变半选。</p>
              <el-tree
                ref="treeRef"
                :data="treeData"
                node-key="id"
                :props="{ children: 'children', label: 'label' }"
                show-checkbox
                :check-on-click-leaf="false"
                default-expand-all
                :default-checked-keys="checkedKeys"
                :filter-node-method="filterNode"
                :expand-on-click-node="false"
                class="hr-tree"
                @node-click="onNodeClick"
                @check="onCheck"
              >
                <template #default="{ data }">
                  <span class="hr-node">
                    <span class="hr-node-label" :class="'is-' + data.type">{{ data.label }}</span>
                    <template v-if="data.type === 'metric'">
                      <el-tag v-if="data.state === 'exempt'" size="small" type="info">不计分</el-tag>
                      <el-tag v-else-if="data.state === 'learning'" size="small" type="warning">学习中</el-tag>
                      <el-tag v-else-if="data.state === 'missing'" size="small" type="info">缺数</el-tag>
                      <el-tag v-else-if="data.mode === 'manual'" size="small" type="success">人工</el-tag>
                      <el-tag v-else size="small">动态</el-tag>
                      <b class="hr-node-score" :class="scoreClass(data.score)">{{ data.score ?? '--' }}</b>
                    </template>
                    <span v-else class="hr-node-count">{{ data.exemptCount }}/{{ data.metricCount }}</span>
                  </span>
                </template>
              </el-tree>
            </aside>

            <!-- 拖动分隔条调整左栏宽度：指标名过长时把左栏拉宽，配合树内横向滚动条看全名。
                 双击恢复默认宽度，聚焦后左右方向键可微调（键盘用户也能用）。 -->
            <div
              class="hr-splitter"
              :class="{ 'is-dragging': resizing }"
              role="separator"
              aria-orientation="vertical"
              aria-label="拖动调整左侧指标树宽度"
              tabindex="0"
              title="拖动调整左侧宽度（双击恢复默认）"
              @pointerdown.prevent="startResize"
              @dblclick.prevent="resetLeftWidth"
              @keydown.left.prevent="nudgeLeftWidth(-24)"
              @keydown.right.prevent="nudgeLeftWidth(24)"
            ></div>

            <main class="hr-right">
              <div v-if="!selectedMetric" class="hr-empty">
                左侧选择一个指标，配置它的计分模式、方向和基线；配置完点「应用修改」，最后在底部填原因保存。
              </div>
              <template v-else>
                <div class="hr-metric-head">
                  <div>
                    <h3>{{ selectedMetric.metricName }}</h3>
                    <p>
                      {{ selectedMetric.metricCode }} · {{ selectedMetric.unit || '无单位' }} ·
                      当前值 {{ selectedMetric.currentValue ?? '--' }}
                      <span v-if="selectedMetric.currentValueTime">（{{ shortTime(selectedMetric.currentValueTime) }}）</span>
                      <el-tag v-if="selectedMetric.dataOrigin === 'mock'" size="small" type="warning">样例数据</el-tag>
                    </p>
                  </div>
                  <div class="hr-metric-score">
                    <b :class="scoreClass(selectedMetric.score)">{{ selectedMetric.score ?? '--' }}</b>
                    <small>{{ stateText(selectedMetric) }}</small>
                  </div>
                </div>

                <el-radio-group v-model="form.mode" class="hr-mode" @change="onModeChange">
                  <el-radio-button value="exempt">不计分</el-radio-button>
                  <el-radio-button value="manual">人工基线</el-radio-button>
                  <el-radio-button value="dynamic">动态基线</el-radio-button>
                </el-radio-group>
                <p class="hr-mode-hint">{{ modeHint }}</p>

                <el-form label-width="104px" class="hr-form" :disabled="form.mode === 'exempt'">
                  <el-form-item label="变差方向">
                    <el-select v-model="form.direction" style="width: 180px">
                      <el-option value="lower_better" label="越小越好（时长/耗时/数量）" />
                      <el-option value="higher_better" label="越大越好（率/及时率）" />
                      <el-option value="zero_tolerance" label="零容忍（异常必须为 0）" />
                      <el-option value="range" label="区间型（落在区间内满分）" />
                    </el-select>
                  </el-form-item>

                  <template v-if="form.mode === 'manual'">
                    <el-form-item label="基线值">
                      <el-input-number v-model="form.baselineValue" :min="0" :step="1" controls-position="right" style="width: 180px" />
                      <span class="hr-unit">{{ selectedMetric.unit || '' }}</span>
                    </el-form-item>
                    <el-form-item label="宽容区">
                      <el-input-number v-model="form.tolerance" :min="0" :step="1" controls-position="right" style="width: 180px" />
                      <span class="hr-form-tip">基线 ± 宽容区之内不扣分，一般填 0</span>
                    </el-form-item>
                    <el-form-item label="扣到 0 的值" v-if="form.direction !== 'zero_tolerance'">
                      <el-input-number v-model="form.penaltyValue" :min="0" :step="1" controls-position="right" style="width: 180px" />
                      <span class="hr-form-tip">值到这个数就是 0 分（硬性要求定得越严，扣分越快）</span>
                    </el-form-item>
                    <template v-if="form.direction === 'range'">
                      <el-form-item label="区间下限">
                        <el-input-number v-model="form.rangeMin" :step="1" controls-position="right" style="width: 180px" />
                      </el-form-item>
                      <el-form-item label="区间上限">
                        <el-input-number v-model="form.rangeMax" :step="1" controls-position="right" style="width: 180px" />
                      </el-form-item>
                    </template>
                  </template>

                  <template v-if="form.direction === 'zero_tolerance'">
                    <el-form-item label="扣分档位">
                      <div class="hr-tiers">
                        <div v-for="(tier, index) in form.tiers" :key="index" class="hr-tier-row">
                          <span>异常量 ≤</span>
                          <el-input-number v-model="tier.max" :min="0" :step="1" size="small" controls-position="right" />
                          <span>→</span>
                          <el-input-number v-model="tier.score" :min="0" :max="100" :step="5" size="small" controls-position="right" />
                          <span>分</span>
                          <el-button link type="danger" @click="form.tiers.splice(index, 1)">删除</el-button>
                        </div>
                        <el-button size="small" @click="form.tiers.push({ max: 100000, score: 0 })">+ 加一档</el-button>
                      </div>
                    </el-form-item>
                  </template>

                  <template v-if="form.mode === 'dynamic'">
                    <el-form-item label="基线窗口">
                      <el-input-number v-model="form.windowDays" :min="7" :max="365" :step="1" controls-position="right" style="width: 140px" />
                      <span class="hr-unit">天</span>
                      <span class="hr-form-tip">取这段时间的历史值算中位数（默认 90 天）</span>
                    </el-form-item>
                    <el-form-item label="冷启动样本">
                      <el-input-number v-model="form.minSamples" :min="3" :max="1000" :step="1" controls-position="right" style="width: 140px" />
                      <span class="hr-unit">个</span>
                      <span class="hr-form-tip">样本不够时显示“基线学习中”，不出分也不给 0 分</span>
                    </el-form-item>
                    <el-form-item label="季节性">
                      <el-select v-model="form.seasonality" style="width: 180px">
                        <el-option value="none" label="不考虑" />
                        <el-option value="workday_weekend" label="工作日/周末分开算" />
                        <el-option value="weekday" label="按星期几分开算" />
                      </el-select>
                    </el-form-item>
                  </template>
                </el-form>

                <div v-if="form.mode === 'manual' && form.baselineValue == null" class="hr-warn">
                  人工基线需要同时填「基线值」和「扣到 0 的值」，否则保存会被拒绝。
                </div>

                <div v-if="stats.sampleCount" class="hr-suggest">
                  <span>
                    近 90 天建议基线：中位数 <b>{{ stats.median }}</b>
                    （样本 {{ stats.sampleCount }} 个，P25 {{ stats.p25 }} / P75 {{ stats.p75 }}）
                    <el-tag v-if="stats.sampleBasedOnMock" size="small" type="warning">样本含样例数据</el-tag>
                  </span>
                  <el-button size="small" :disabled="form.mode === 'exempt'" @click="adoptSuggested">采纳为人工基线</el-button>
                </div>

                <div class="hr-preview">
                  <div class="hr-preview-head">
                    <span>分数预览（横轴指标值，纵轴得分）</span>
                    <div class="hr-preview-tools">
                      <span>试算当前值</span>
                      <el-input-number v-model="previewValue" :step="1" size="small" controls-position="right" style="width: 120px" />
                      <el-button size="small" :loading="previewing" @click="runPreview">试算</el-button>
                    </div>
                  </div>
                  <div ref="chartEl" class="hr-chart"></div>
                  <div class="hr-preview-result" v-if="previewResult">
                    <span v-if="previewResult.ok">
                      {{ previewResult.value }} → <b :class="scoreClass(previewResult.score)">{{ previewResult.score ?? '不出分' }}</b>
                      <template v-if="previewResult.score != null"> 分（{{ previewResult.statusText }}）</template>
                      <em v-if="previewResult.message">· {{ previewResult.message }}</em>
                    </span>
                    <span v-else class="hr-error">{{ previewResult.message }}</span>
                  </div>
                </div>

                <div class="hr-editor-actions">
                  <el-button type="primary" plain :disabled="!formChanged" @click="applyEdit">应用修改</el-button>
                  <el-button :disabled="!formChanged" @click="resetForm">还原</el-button>
                  <span v-if="pendingRules.has(selectedMetric.metricId) || pendingExempt.has(selectedMetric.metricId)" class="hr-pending">
                    该指标有暂存修改，保存后生效
                  </span>
                </div>
              </template>
            </main>
          </div>

          <footer class="hr-footer">
            <div class="hr-change-summary">
              本次变更：
              <b>{{ addCount }}</b> 个加白名单 /
              <b>{{ removeCount }}</b> 个移出白名单 /
              <b>{{ pendingRules.size }}</b> 个改基线
            </div>
            <el-select v-model="quickReason" placeholder="快捷原因" size="small" style="width: 200px" @change="applyQuickReason">
              <el-option v-for="item in quickReasons" :key="item" :value="item" :label="item" />
            </el-select>
            <el-input v-model="reason" size="small" placeholder="变更原因（必填，会写进审计）" style="flex: 1; min-width: 240px" />
            <el-button @click="resetPending">取消变更</el-button>
            <el-button type="primary" :disabled="!hasChanges || !reason" :loading="saving" @click="save">
              保存并重算
            </el-button>
          </footer>
        </el-tab-pane>

        <!-- ================= 变更历史 ================= -->
        <el-tab-pane label="变更历史" name="history">
          <div class="hr-table-wrap" v-loading="historyLoading">
            <el-table :data="historyRows" size="small" stripe border max-height="calc(94vh - 260px)">
              <el-table-column label="时间" width="170">
                <template #default="{ row }">{{ shortTime(row.changedAt) }}</template>
              </el-table-column>
              <el-table-column prop="operatorName" label="操作人" width="110" />
              <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
              <el-table-column label="变更内容" min-width="380">
                <template #default="{ row }">
                  <span class="hr-diff">{{ diffText(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="版本" width="80" prop="version" />
            </el-table>
          </div>
        </el-tab-pane>

        <!-- ================= 新旧对比 ================= -->
        <el-tab-pane label="新旧算法对比" name="compare">
          <div class="hr-table-wrap" v-loading="compareLoading">
            <el-alert type="info" :closable="false" show-icon :title="compareNote" class="hr-compare-note" />
            <el-table :data="compareRows" size="small" stripe border max-height="calc(94vh - 300px)">
              <el-table-column prop="metricName" label="指标" min-width="220" show-overflow-tooltip />
              <el-table-column prop="categoryName" label="大类" width="150" />
              <el-table-column prop="oldScore" label="旧算法" width="90" />
              <el-table-column label="新算法" width="90">
                <template #default="{ row }"><b :class="scoreClass(row.newScore)">{{ row.newScore }}</b></template>
              </el-table-column>
              <el-table-column label="差值" width="90">
                <template #default="{ row }">
                  <span :class="row.delta < 0 ? 'hr-down' : row.delta > 0 ? 'hr-up' : ''">
                    {{ row.delta > 0 ? '+' : '' }}{{ row.delta }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="模式" width="100">
                <template #default="{ row }">{{ row.newMode === 'manual' ? '人工' : '动态' }}</template>
              </el-table-column>
              <el-table-column label="数据来源" width="110">
                <template #default="{ row }">{{ row.dataOrigin === 'mock' ? '样例' : row.dataOrigin === 'collected' ? '采集' : row.dataOrigin }}</template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { healthRuleApi } from '@/api'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const tab = ref('rules')
const loading = ref(false)
const saving = ref(false)
const recalculating = ref(false)
const previewing = ref(false)
const historyLoading = ref(false)
const compareLoading = ref(false)
const keyword = ref('')
const reason = ref('')
const quickReason = ref('')
const quickReasons = [
  '稽核类不计分（业务确认）',
  '数量类为中性指标，不计分',
  '按 SLA 配置人工基线',
  '历史阈值未经业务确认，改为动态基线',
  '临时观察，先移出白名单'
]

const raw = ref({ systems: [], summary: {}, thresholds: {}, config: {} })
const summary = computed(() => raw.value.summary || {})
const config = computed(() => raw.value.config || {})
const thresholds = computed(() => raw.value.thresholds || { alarm: 60, warning: 80 })
const treeRef = ref(null)

// ---------- 左侧指标树宽度（可拖动 / 记忆） ----------
// 指标名（如 BOSS142_资费备案: TF_B_FILINGS_ORDER）经常长于默认面板宽度，
// 所以左栏改成可拖动宽度，并允许树内横向滚动；宽度记在本地，下次打开沿用。
const LEFT_MIN = 300
const LEFT_MAX = 760
const LEFT_DEFAULT = 420
const leftWidth = ref(readLeftWidth())
const resizing = ref(false)
let resizeStartX = 0
let resizeStartWidth = LEFT_DEFAULT

function readLeftWidth () {
  const saved = Number(window.localStorage.getItem('metric_health_rule_left_width'))
  if (!Number.isFinite(saved) || saved <= 0) return LEFT_DEFAULT
  return Math.min(LEFT_MAX, Math.max(LEFT_MIN, saved))
}
function clampLeftWidth (width) {
  // 上限取「760px」和「窗口 65%」中较小者：右栏还要放计分模式、基线和预览图，不能被挤没
  const maxByWindow = Math.max(LEFT_MIN, Math.floor(window.innerWidth * 0.65))
  return Math.round(Math.min(Math.min(LEFT_MAX, maxByWindow), Math.max(LEFT_MIN, width)))
}
function saveLeftWidth () {
  window.localStorage.setItem('metric_health_rule_left_width', String(leftWidth.value))
}
function startResize (event) {
  resizing.value = true
  resizeStartX = event.clientX
  resizeStartWidth = leftWidth.value
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', stopResize)
  window.addEventListener('pointercancel', stopResize)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}
function onResizeMove (event) {
  if (!resizing.value) return
  leftWidth.value = clampLeftWidth(resizeStartWidth + event.clientX - resizeStartX)
}
function stopResize () {
  if (!resizing.value) return
  resizing.value = false
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  saveLeftWidth()
}
function nudgeLeftWidth (delta) {
  leftWidth.value = clampLeftWidth(leftWidth.value + delta)
  saveLeftWidth()
}
function resetLeftWidth () {
  leftWidth.value = clampLeftWidth(LEFT_DEFAULT)
  saveLeftWidth()
}

const metricsFlat = computed(() => {
  const rows = []
  for (const system of raw.value.systems || []) {
    for (const category of system.categories || []) {
      for (const sub of category.subcategories || []) {
        for (const metric of sub.metrics || []) rows.push(metric)
      }
    }
  }
  return rows
})
const metricById = computed(() => {
  const map = new Map()
  metricsFlat.value.forEach(metric => map.set(metric.metricId, metric))
  return map
})

const treeData = computed(() => (raw.value.systems || []).map(system => {
  let metricCount = 0
  let exemptCount = 0
  const categories = (system.categories || []).map(category => {
    let catCount = 0
    let catExempt = 0
    const subcategories = (category.subcategories || []).map(sub => {
      catCount += sub.metricCount || 0
      catExempt += sub.exemptCount || 0
      return {
        id: `sub-${category.categoryId}-${sub.name}`,
        label: sub.name,
        type: 'subcategory',
        metricCount: sub.metricCount || 0,
        exemptCount: sub.exemptCount || 0,
        children: (sub.metrics || []).map(metric => ({ ...metric, id: `m-${metric.metricId}`, label: metric.metricName, type: 'metric' }))
      }
    })
    metricCount += catCount
    exemptCount += catExempt
    return {
      id: `cat-${category.categoryId}`,
      label: category.name,
      type: 'category',
      metricCount: catCount,
      exemptCount: catExempt,
      children: subcategories
    }
  })
  return {
    id: `sys-${system.systemId}`,
    label: system.systemName,
    type: 'system',
    metricCount,
    exemptCount,
    children: categories
  }
}))

const checkedKeys = computed(() => metricsFlat.value.filter(metric => metric.exempt).map(metric => `m-${metric.metricId}`))
const exemptKeys = computed(() => metricsFlat.value.filter(metric => metric.exempt).map(metric => metric.metricId))

// ---------- 选择与表单 ----------
const selectedMetric = ref(null)
const form = reactive(emptyForm())
const originalForm = ref(null)
const stats = ref({})
const previewValue = ref(0)
const previewResult = ref(null)
const chartEl = ref(null)
let chart = null
let previewTimer = 0
let suppressPreview = false

function emptyForm () {
  return {
    mode: 'dynamic',
    direction: 'lower_better',
    baselineValue: null,
    tolerance: 0,
    penaltyValue: null,
    rangeMin: null,
    rangeMax: null,
    tiers: [],
    windowDays: 90,
    minSamples: 30,
    seasonality: 'none'
  }
}

function parseTiers (tiers) {
  if (!tiers) return []
  try {
    const rows = typeof tiers === 'string' ? JSON.parse(tiers) : tiers
    if (!Array.isArray(rows)) return []
    return rows.map(row => ({ max: Number(row.max), score: Number(row.score) })).sort((a, b) => a.max - b.max)
  } catch (e) {
    return []
  }
}

function formFromMetric (metric) {
  const mode = metric.exempt ? 'exempt'
    : (metric.baselineValue != null && metric.baselineStatus === 'confirmed') ? 'manual' : 'dynamic'
  return {
    mode,
    direction: metric.direction || 'lower_better',
    baselineValue: metric.baselineValue != null ? Number(metric.baselineValue) : null,
    tolerance: metric.tolerance != null ? Number(metric.tolerance) : 0,
    penaltyValue: metric.penaltyValue != null ? Number(metric.penaltyValue) : null,
    rangeMin: metric.rangeMin != null ? Number(metric.rangeMin) : null,
    rangeMax: metric.rangeMax != null ? Number(metric.rangeMax) : null,
    tiers: parseTiers(metric.tiers).length ? parseTiers(metric.tiers) : defaultTiers(),
    windowDays: metric.windowDays || 90,
    minSamples: metric.minSamples || 30,
    seasonality: metric.seasonality || 'none'
  }
}

function defaultTiers () {
  return [
    { max: 0, score: 100 },
    { max: 1, score: 80 },
    { max: 5, score: 60 },
    { max: 20, score: 30 },
    { max: 100, score: 10 }
  ]
}

const formChanged = computed(() => {
  if (!selectedMetric.value || !originalForm.value) return false
  return JSON.stringify(form) !== JSON.stringify(originalForm.value)
})

const modeHint = computed(() => {
  if (form.mode === 'exempt') return '不计分 ≠ 不监控：指标值照常展示、指标值告警照常生效，只是不参与健康分和上层聚合。'
  if (form.mode === 'manual') return '人工基线用于 SLA / 硬性要求：按“离基线多远”扣分，扣分速度由「扣到 0 的值」决定。'
  return '动态基线用于没有硬性指标的项：用近 90 天历史值的中位数判断偏离，只对明显变差扣分。'
})

function scoreClass (score) {
  if (score == null) return 'is-empty'
  if (score < thresholds.value.alarm) return 'is-danger'
  if (score < thresholds.value.warning) return 'is-warning'
  return 'is-normal'
}

function stateText (metric) {
  if (!metric) return ''
  if (metric.state === 'exempt') return '不计分'
  if (metric.state === 'learning') return '基线学习中'
  if (metric.state === 'missing') return '缺数'
  return metric.status === 'alarm' ? '异常' : metric.status === 'warning' ? '关注' : '正常'
}

function shortTime (value) {
  if (!value) return '--'
  return String(value).replace('T', ' ').slice(0, 16)
}

// ---------- 加载 ----------
async function loadTree () {
  loading.value = true
  try {
    const res = await healthRuleApi.tree()
    raw.value = res.data || { systems: [] }
    // data 换成新对象后 el-tree 需要重新建节点，等一帧再回显勾选状态
    await nextTick()
    setTimeout(() => syncTreeChecked(checkedKeys.value), 80)
  } catch (error) {
    ElMessage.error('加载健康度规则失败：' + (error.message || error))
  } finally {
    loading.value = false
  }
}

// 程序化设置勾选状态时，el-tree 可能回抛 check 事件；用标记位挡住，避免把“回显”误记成用户变更
let syncingTree = false
function syncTreeChecked (keys) {
  const tree = treeRef.value
  if (!tree) return
  syncingTree = true
  tree.setCheckedKeys(keys)
  nextTick(() => { syncingTree = false })
}

const pendingExempt = reactive(new Map())
const pendingRules = reactive(new Map())
const addCount = computed(() => [...pendingExempt.values()].filter(Boolean).length)
const removeCount = computed(() => [...pendingExempt.values()].filter(value => !value).length)
const hasChanges = computed(() => pendingExempt.size > 0 || pendingRules.size > 0)

function resetPending () {
  pendingExempt.clear()
  pendingRules.clear()
  syncTreeChecked(checkedKeys.value)
  resetForm()
}

watch(keyword, value => treeRef.value?.filter(value || ''))

function filterNode (value, data) {
  if (!value) return true
  if (data.type !== 'metric') return true
  const text = `${data.label || ''}${data.metricCode || ''}`.toLowerCase()
  return text.includes(value.toLowerCase())
}

function onNodeClick (data) {
  if (data.type !== 'metric') return
  selectMetric(data.metricId)
}

function selectMetric (metricId) {
  const metric = metricById.value.get(metricId)
  if (!metric) return
  selectedMetric.value = metric
  Object.assign(form, formFromMetric(metric))
  // 若该指标已有暂存的白名单变更，右侧模式跟随暂存状态显示
  if (pendingExempt.has(metricId)) {
    const desired = pendingExempt.get(metricId)
    if (desired) form.mode = 'exempt'
    else if (form.mode === 'exempt') form.mode = metric.baselineStatus === 'confirmed' ? 'manual' : 'dynamic'
  }
  originalForm.value = JSON.stringify(form)
  previewValue.value = metric.currentValue != null ? Number(metric.currentValue) : 0
  previewResult.value = null
  loadStats(metricId)
  nextTick(() => {
    initChart()
    runPreview()
  })
}

async function loadStats (metricId) {
  try {
    const res = await healthRuleApi.suggest(metricId)
    stats.value = res.data?.stats || {}
  } catch (e) {
    stats.value = {}
  }
}

function onCheck () {
  if (syncingTree) return
  // el-tree 勾选只表达“是否不计分”，与右侧规则编辑解耦
  const checked = new Set((treeRef.value?.getCheckedKeys(true) || [])
    .filter(key => String(key).startsWith('m-'))
    .map(key => Number(String(key).slice(2))))
  for (const metric of metricsFlat.value) {
    const desired = checked.has(metric.metricId)
    if (desired !== Boolean(metric.exempt)) pendingExempt.set(metric.metricId, desired)
    else pendingExempt.delete(metric.metricId)
  }
  // 选中指标的模式跟随勾选变化
  if (selectedMetric.value) {
    const desired = checked.has(selectedMetric.value.metricId)
    if (desired && form.mode !== 'exempt') form.mode = 'exempt'
    if (!desired && form.mode === 'exempt') form.mode = selectedMetric.value.baselineStatus === 'confirmed' ? 'manual' : 'dynamic'
  }
}

function checkVisible (checked) {
  const visible = metricsFlat.value.filter(metric => {
    if (!keyword.value) return true
    return `${metric.metricName || ''}${metric.metricCode || ''}`.toLowerCase().includes(keyword.value.toLowerCase())
  })
  syncingTree = true
  visible.forEach(metric => treeRef.value?.setChecked(`m-${metric.metricId}`, checked, false))
  nextTick(() => { syncingTree = false })
  onCheck()
}

function onModeChange () {
  if (form.mode !== 'exempt' && form.baselineValue == null && form.mode === 'manual') {
    const suggested = stats.value?.median != null ? Number(stats.value.median) : null
    if (suggested != null) form.baselineValue = suggested
  }
  if (form.mode === 'manual' && form.penaltyValue == null && form.baselineValue != null) {
    form.penaltyValue = form.direction === 'higher_better'
      ? Math.max(0, Number(form.baselineValue) - 10)
      : Number(form.baselineValue) * 2
  }
}

function adoptSuggested () {
  if (stats.value?.median == null) return
  form.mode = 'manual'
  form.baselineValue = Number(stats.value.median)
  if (form.direction === 'higher_better') {
    form.penaltyValue = Math.max(0, form.baselineValue - 10)
  } else {
    form.penaltyValue = Math.max(Number(form.baselineValue) * 2, Number(form.baselineValue) + 1)
  }
  runPreview()
}

function resetForm () {
  if (!selectedMetric.value) return
  Object.assign(form, formFromMetric(selectedMetric.value))
  originalForm.value = JSON.stringify(form)
  runPreview()
}

function applyEdit () {
  if (!selectedMetric.value) return
  const metricId = selectedMetric.value.metricId
  if (form.mode === 'exempt') {
    pendingExempt.set(metricId, true)
    pendingRules.delete(metricId)
    syncingTree = true
    treeRef.value?.setChecked(`m-${metricId}`, true, false)
    nextTick(() => { syncingTree = false })
  } else {
    pendingExempt.set(metricId, false)
    pendingRules.set(metricId, {
      metricId,
      direction: form.direction,
      baselineValue: form.mode === 'manual' ? form.baselineValue : null,
      baselineStatus: form.mode === 'manual' ? 'confirmed' : 'none',
      tolerance: form.tolerance || 0,
      penaltyValue: form.penaltyValue,
      rangeMin: form.rangeMin,
      rangeMax: form.rangeMax,
      tiers: form.direction === 'zero_tolerance' ? form.tiers : null,
      windowDays: form.windowDays,
      minSamples: form.minSamples,
      seasonality: form.seasonality
    })
    syncingTree = true
    treeRef.value?.setChecked(`m-${metricId}`, false, false)
    nextTick(() => { syncingTree = false })
  }
  originalForm.value = JSON.stringify(form)
  ElMessage.success('已暂存，底部填原因后统一保存')
}

// ---------- 预览 ----------
function initChart () {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  chart.resize()
}

function buildPreviewPayload () {
  return {
    metricId: selectedMetric.value.metricId,
    direction: form.direction,
    baselineValue: form.mode === 'manual' ? form.baselineValue : null,
    baselineStatus: form.mode === 'manual' ? 'confirmed' : 'none',
    tolerance: form.tolerance || 0,
    penaltyValue: form.penaltyValue,
    rangeMin: form.rangeMin,
    rangeMax: form.rangeMax,
    tiers: form.direction === 'zero_tolerance' ? form.tiers : null,
    windowDays: form.windowDays,
    minSamples: form.minSamples,
    seasonality: form.seasonality,
    currentValue: previewValue.value,
    excluded: form.mode === 'exempt'
  }
}

async function runPreview () {
  if (!selectedMetric.value || form.mode === 'exempt') {
    previewResult.value = { ok: false, message: '当前设为不计分，不产生分数' }
    renderChart([], null)
    return
  }
  previewing.value = true
  try {
    const res = await healthRuleApi.preview(buildPreviewPayload())
    const data = res.data || {}
    const current = data.current || {}
    previewResult.value = {
      ok: current.score !== undefined,
      value: data.currentValue,
      score: current.score,
      statusText: current.status === 'alarm' ? '异常' : current.status === 'warning' ? '关注' : '正常',
      message: current.message || ''
    }
    stats.value = data.stats || stats.value
    renderChart(data.curve || [], current.score)
  } catch (error) {
    previewResult.value = { ok: false, message: error.message || '试算失败' }
  } finally {
    previewing.value = false
  }
}

function renderChart (curve, currentScore) {
  if (!chart) return
  chart.setOption({
    grid: { left: 46, right: 16, top: 16, bottom: 30 },
    tooltip: { trigger: 'axis', formatter: params => `值 ${params[0].axisValue} → ${params[0].data} 分` },
    xAxis: {
      type: 'category',
      data: curve.map(point => point.value),
      name: '指标值',
      axisLabel: { fontSize: 10, hideOverlap: true }
    },
    yAxis: { type: 'value', min: 0, max: 100, splitNumber: 4, axisLabel: { fontSize: 10 } },
    series: [{
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: curve.map(point => point.score ?? null),
      lineStyle: { width: 2, color: '#3b82f6' },
      areaStyle: { color: 'rgba(59,130,246,0.12)' },
      markLine: currentScore == null ? undefined : {
        silent: true,
        symbol: 'none',
        label: { formatter: `当前 ${currentScore} 分`, fontSize: 10 },
        data: [{ yAxis: currentScore }]
      }
    }]
  }, true)
}

function schedulePreview () {
  if (suppressPreview) return
  clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, 350)
}

watch(form, schedulePreview, { deep: true })

// ---------- 保存 ----------
function applyQuickReason () {
  if (quickReason.value && !reason.value) reason.value = quickReason.value
}

async function save () {
  if (!reason.value) {
    ElMessage.warning('必须填写变更原因')
    return
  }
  const exemptChanges = [...pendingExempt.entries()].map(([metricId, exempt]) => ({
    metricId,
    exempt,
    version: metricById.value.get(metricId)?.version,
    reason: reason.value
  }))
  const ruleChanges = [...pendingRules.values()].map(row => ({
    ...row,
    version: metricById.value.get(row.metricId)?.version
  }))
  saving.value = true
  try {
    const res = await healthRuleApi.batch({ reason: reason.value, exemptChanges, ruleChanges })
    const recalculated = res.data?.recalculated || {}
    ElMessage.success(`已保存并重算：出分 ${recalculated.scored ?? '-'} 个，学习中 ${recalculated.learning ?? '-'} 个`)
    pendingExempt.clear()
    pendingRules.clear()
    reason.value = ''
    quickReason.value = ''
    await loadTree()
    if (selectedMetric.value) {
      const fresh = metricById.value.get(selectedMetric.value.metricId)
      if (fresh) selectMetric(fresh.metricId)
    }
    emit('saved')
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function recalculate () {
  recalculating.value = true
  try {
    const res = await healthRuleApi.recalculate()
    ElMessage.success(`重算完成：出分 ${res.data?.scored ?? '-'} 个`)
    await loadTree()
  } catch (error) {
    ElMessage.error(error.message || '重算失败')
  } finally {
    recalculating.value = false
  }
}

// ---------- 历史 / 对比 ----------
const historyRows = ref([])
const compareRows = ref([])
const compareNote = ref('')

async function loadHistory () {
  historyLoading.value = true
  try {
    const res = await healthRuleApi.history({ limit: 100 })
    historyRows.value = res.data || []
  } finally {
    historyLoading.value = false
  }
}

function diffText (row) {
  const before = row.before || {}
  const after = row.after || {}
  const parts = []
  if (before.exempt !== after.exempt) parts.push(`白名单 ${before.exempt ? '是' : '否'} → ${after.exempt ? '是' : '否'}`)
  if (before.direction !== after.direction) parts.push(`方向 ${before.direction || '-'} → ${after.direction || '-'}`)
  if (String(before.baselineValue ?? '') !== String(after.baselineValue ?? '')) {
    parts.push(`基线 ${before.baselineValue ?? '空'} → ${after.baselineValue ?? '空'}`)
  }
  if (before.baselineStatus !== after.baselineStatus) parts.push(`状态 ${before.baselineStatus || '-'} → ${after.baselineStatus || '-'}`)
  if (String(before.penaltyValue ?? '') !== String(after.penaltyValue ?? '')) {
    parts.push(`扣到0 ${before.penaltyValue ?? '空'} → ${after.penaltyValue ?? '空'}`)
  }
  return parts.length ? parts.join('；') : '（无字段变化）'
}

async function loadCompare () {
  compareLoading.value = true
  try {
    const res = await healthRuleApi.compare()
    compareRows.value = res.data?.rows || []
    compareNote.value = `对比 ${res.data?.compared ?? 0} 个指标，其中变化超过 5 分的有 ${res.data?.changedOver5 ?? 0} 个。${res.data?.note || ''}`
  } finally {
    compareLoading.value = false
  }
}

watch(tab, value => {
  if (value === 'history') loadHistory()
  if (value === 'compare') loadCompare()
})

watch(() => props.modelValue, value => {
  if (value) {
    loadTree()
    reason.value = ''
    quickReason.value = ''
    tab.value = 'rules'
  } else {
    pendingExempt.clear()
    pendingRules.clear()
    selectedMetric.value = null
    if (chart) {
      chart.dispose()
      chart = null
    }
  }
})

function resizeChart () {
  chart?.resize()
}
window.addEventListener('resize', resizeChart)
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  stopResize()
  if (chart) chart.dispose()
})
</script>

<style scoped>
.hr-drawer { display: flex; flex-direction: column; height: 100%; gap: 10px; }
.hr-head { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 2px 4px; }
.hr-title h2 { margin: 0; font-size: 18px; color: var(--ui-heading, #1f2d3d); }
.hr-title p { margin: 4px 0 0; font-size: 12px; color: #7a8699; }
.hr-head-stats { display: flex; gap: 8px; flex-wrap: wrap; margin-left: auto; }
.hr-chip { padding: 3px 9px; border: 1px solid #e3e8f0; border-radius: 999px; font-size: 12px; color: #5a6678; background: #fafbfd; }
.hr-chip b { color: #1f2d3d; }
.hr-chip.warn { border-color: #f3d19e; background: #fdf6ec; color: #b88230; }
.hr-head-actions { display: flex; gap: 8px; }
.hr-sample-hint { margin: 0 2px; }
.hr-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.hr-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; }
.hr-tabs :deep(.el-tab-pane) { height: 100%; }
.hr-body { display: flex; gap: 0; height: calc(94vh - 246px); }
/* width 由脚本按拖动结果写成内联样式；max-width 兜住「窗口缩小后旧宽度过大」的情况 */
.hr-left { flex: none; min-width: 0; max-width: 65%; display: flex; flex-direction: column; border: 1px solid #e6ebf2; border-radius: 10px; padding: 10px; background: #fff; }
.hr-left-tools { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.hr-left-tools :deep(.el-input) { flex: 1 1 130px; min-width: 0; }
.hr-left-hint { margin: 8px 0; font-size: 11px; color: #8a94a6; line-height: 1.5; }
/* 树内横向滚动：长指标名不折行、不截断，直接左右拖看全名 */
.hr-tree { flex: 1; min-height: 0; overflow: auto; }
.hr-tree :deep(.el-tree-node__content) { height: auto; min-height: 26px; }
.hr-splitter { flex: none; width: 12px; display: flex; align-items: center; justify-content: center; cursor: col-resize; touch-action: none; border-radius: 6px; }
.hr-splitter::after { content: ''; width: 4px; height: 46px; border-radius: 3px; background: #d8e0ec; transition: background .15s, height .15s; }
.hr-splitter:hover::after, .hr-splitter.is-dragging::after, .hr-splitter:focus-visible::after { height: 74px; background: #3b82f6; }
.hr-splitter:focus-visible { outline: 2px solid #bfdbfe; outline-offset: -1px; }
.hr-node { display: flex; align-items: center; gap: 6px; width: max-content; min-width: 100%; padding-right: 6px; }
.hr-node-label { white-space: nowrap; }
.hr-node-label.is-system { font-weight: 600; }
.hr-node-label.is-category { font-weight: 500; color: #43506a; }
.hr-node-label.is-metric { color: #56617a; }
.hr-node-count { flex: none; margin-left: auto; font-size: 11px; color: #9aa4b5; }
.hr-node-score { flex: none; margin-left: auto; font-size: 12px; font-variant-numeric: tabular-nums; }
.is-normal { color: #2f9e6e; } .is-warning { color: #d68910; } .is-danger { color: #d64545; } .is-empty { color: #a8b1c0; }
.hr-right { flex: 1; min-width: 0; display: flex; flex-direction: column; border: 1px solid #e6ebf2; border-radius: 10px; padding: 14px 16px; background: #fff; overflow: auto; }
.hr-empty { display: flex; align-items: center; justify-content: center; height: 100%; color: #98a2b3; font-size: 13px; }
.hr-metric-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid #eef2f7; }
.hr-metric-head h3 { margin: 0; font-size: 16px; }
.hr-metric-head p { margin: 5px 0 0; font-size: 12px; color: #7a8699; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.hr-metric-score { text-align: center; }
.hr-metric-score b { display: block; font-size: 28px; line-height: 1; font-variant-numeric: tabular-nums; }
.hr-metric-score small { font-size: 11px; color: #8a94a6; }
.hr-mode { margin-top: 12px; }
.hr-mode-hint { margin: 8px 0 10px; font-size: 12px; color: #7a8699; }
.hr-form { margin-top: 4px; }
.hr-form :deep(.el-form-item) { margin-bottom: 12px; }
.hr-unit { margin-left: 6px; font-size: 12px; color: #7a8699; }
.hr-form-tip { margin-left: 8px; font-size: 11px; color: #98a2b3; }
.hr-tiers { display: flex; flex-direction: column; gap: 6px; }
.hr-tier-row { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #5a6678; }
.hr-tier-row :deep(.el-input-number) { width: 110px; }
.hr-suggest { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 4px; padding: 9px 12px; border: 1px dashed #cfd8e6; border-radius: 8px; background: #f8fafd; font-size: 12px; color: #5a6678; }
.hr-suggest b { color: #1f2d3d; }
.hr-warn { margin-top: 6px; padding: 8px 12px; border-radius: 8px; background: #fdf6ec; color: #b88230; font-size: 12px; }
.hr-preview { margin-top: 10px; border-top: 1px solid #eef2f7; padding-top: 10px; }
.hr-preview-head { display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: #5a6678; }
.hr-preview-tools { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #8a94a6; }
.hr-chart { height: 170px; margin-top: 6px; }
.hr-preview-result { font-size: 12px; color: #5a6678; min-height: 18px; }
.hr-preview-result em { color: #8a94a6; font-style: normal; }
.hr-error { color: #d64545; }
.hr-editor-actions { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding-top: 10px; border-top: 1px solid #eef2f7; }
.hr-pending { font-size: 11px; color: #d68910; }
.hr-footer { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 4px 2px; border-top: 1px solid #eef2f7; }
.hr-change-summary { font-size: 12px; color: #5a6678; }
.hr-change-summary b { color: #1f2d3d; }
.hr-table-wrap { height: calc(94vh - 240px); }
.hr-compare-note { margin-bottom: 8px; }
.hr-diff { font-size: 11px; color: #5a6678; }
.hr-up { color: #2f9e6e; } .hr-down { color: #d64545; }
</style>
