<!--
  ============================================================================
  策略编辑弹窗（公共组件）—— 告警策略表单的**唯一实现**
  ============================================================================
  【为什么要抽这个组件】
  告警策略表单原本内联在 views/alarm/index.vue 里。后来「指标体系配置」页也需要
  对某个指标直接设置阈值策略。如果各写一套，后续给策略加字段（例如告警等级
  「重要 / 一般」）就必须改两处，极易漏改导致两页行为不一致。

  因此这里把表单抽成组件，「告警管理」页和「指标体系配置」页**共用同一份实现**：
    - 改字段、改校验、改提交映射 → 只改本文件，两个页面同时生效。
    - 规则编辑统一在本组件提交，列表状态切换仅提交状态字段。

  【调用方】
    1. views/alarm/index.vue           → lockTarget=false，可选 系统/大类/小类/指标
    2. views/metric/index.vue          → lockTarget=true，目标固定为当前指标或健康度对象
  详见 doc/告警策略表单复用说明.md

  【Props】
    v-model            : 是否显示
    initial            : 预填数据（null = 新建）。结构：
                         { id, strategyName, strategyType, targetType, targetId,
                           targetName, categoryId, status,
                           alarmEnabled, alarmCondition, alarmThreshold, alarmConsecutiveTimes,
                           gongdanEnabled, gongdanCondition, gongdanThreshold, gongdanConsecutiveTimes }
    lockTarget         : true = 锁定告警对象（指标页场景），隐藏目标选择、只读展示指标身份
    metricContext      : lockTarget 时用于展示的指标上下文
                         { metricName, metricCode, unit, systemName, categoryName, subCategoryName }
  【Emits】
    saved              : 保存成功，父级据此刷新自己的列表
  ============================================================================
-->
<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    :width="dialogWidth"
    class="workspace-theme strategy-editor"
    :style="{ maxWidth: 'calc(100vw - 32px)' }"
    append-to-body
    destroy-on-close
    @closed="onClosed"
  >
    <!-- ===== 目标区 ===== -->
    <!-- 指标页场景：锁定目标，只读展示该指标身份，免去再下拉查找 -->
    <div v-if="lockTarget" class="target-card">
      <div class="target-card__icon">
        <el-icon><Odometer /></el-icon>
      </div>
      <div class="target-card__main">
        <div class="target-card__title">
          {{ metricContext?.metricName || initial?.targetName || '当前指标' }}
          <span v-if="metricContext?.unit" class="target-card__unit">{{ metricContext.unit }}</span>
        </div>
        <div class="target-card__path">
          <span v-if="metricContext?.systemName">{{ metricContext.systemName }}</span>
          <template v-if="metricContext?.categoryName">
            <span class="target-card__sep">›</span>{{ metricContext.categoryName }}
          </template>
          <template v-if="metricContext?.subCategoryName">
            <span class="target-card__sep">›</span>{{ metricContext.subCategoryName }}
          </template>
          <span v-if="metricContext?.metricCode" class="target-card__code">{{ metricContext.metricCode }}</span>
        </div>
      </div>
      <el-tag v-if="isEdit" type="success" effect="light" round>已存在策略</el-tag>
      <el-tag v-else type="info" effect="light" round>未配置</el-tag>
    </div>

    <!-- 告警页场景：可选目标 -->
    <template v-else>
      <el-form :model="form" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="策略类型">
              <el-select v-model="form.strategyType" :disabled="isEdit && form.strategyType === 'raw'" style="width: 100%" @change="autoGenerateName">
                <el-option label="指标值" value="metric" />
                <el-option label="健康度" value="health" />
                <el-option label="原始数据" value="raw" :disabled="isEdit && form.strategyType !== 'raw'" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="form.strategyType !== 'raw'" label="告警对象">
              <el-select v-model="form.targetType" style="width: 100%" @change="onTargetTypeChange">
                <el-option label="系统" value="system" />
                <el-option label="指标大类" value="category" />
                <el-option label="指标小类" value="subCategory" />
                <el-option label="具体指标" value="metric" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row v-if="form.strategyType !== 'raw'" :gutter="16">
          <el-col :span="12" v-if="form.targetType === 'subCategory'">
            <el-form-item label="选择大类">
              <el-select v-model="form.categoryId" style="width: 100%" filterable placeholder="请先选择大类"
                         @change="onSubCategoryCategoryChange">
                <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <!-- 指标选择按「大类 → 小类 → 具体指标」逐级收窄：200+ 指标平铺在一个下拉里根本分不清是谁 -->
          <template v-if="form.targetType === 'metric'">
            <el-col :span="8">
              <el-form-item label="指标大类">
                <el-select v-model="form.categoryId" style="width: 100%" filterable clearable placeholder="请选择指标大类"
                           @change="onMetricCategoryChange">
                  <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="指标小类">
                <el-select v-model="form.subCategoryCode" style="width: 100%" filterable clearable
                           :disabled="!form.categoryId" placeholder="请选择指标小类" @change="onMetricSubCategoryChange">
                  <el-option v-for="s in subCategoryOptions" :key="s.code" :label="s.name" :value="s.code" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="具体指标">
                <el-select v-model="form.targetId" style="width: 100%" filterable clearable
                           :disabled="!form.categoryId" placeholder="请选择具体指标"
                           @change="onMetricPicked">
                  <el-option v-for="m in metricCandidates" :key="m.id" :value="m.id"
                             :label="`${m.metricName}${m.unit ? '（' + m.unit + '）' : ''}`">
                    <span>{{ m.metricName }}</span>
                    <span class="metric-option-code">{{ m.metricCode }}</span>
                  </el-option>
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <div class="cascade-hint">先选指标大类，小类和指标的可选项会随之收窄；选定指标后会回填它所属的大类和小类。</div>
            </el-col>
          </template>
          <el-col :span="12" v-if="form.targetType === 'system'">
            <el-form-item label="选择系统">
              <el-select v-model="form.targetId" style="width: 100%" filterable placeholder="请选择系统"
                         @change="autoGenerateName">
                <el-option v-for="s in systemList" :key="s.id" :label="s.systemName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.targetType === 'category'">
            <el-form-item label="选择大类">
              <el-select v-model="form.targetId" style="width: 100%" filterable placeholder="请选择指标大类"
                         @change="autoGenerateName">
                <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.targetType === 'subCategory'">
            <el-form-item label="选择小类">
              <el-select v-model="form.targetId" style="width: 100%" filterable placeholder="请先选择大类"
                         :disabled="!form.categoryId" @change="autoGenerateName">
                <el-option v-for="s in subCategoryList" :key="s.id" :label="s.subCategoryName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="策略名称">
          <el-input v-model="form.strategyName" placeholder="选择目标后自动生成，可手动修改" />
        </el-form-item>
      </el-form>
    </template>

    <!-- 锁定目标时策略名单独一行，保持可编辑 -->
    <el-form v-if="lockTarget" :model="form" label-width="90px" class="lock-name-form">
      <el-form-item label="策略名称">
        <el-input v-model="form.strategyName" placeholder="留空则自动生成" />
      </el-form-item>
    </el-form>

    <RawSourceEditor :active="form.strategyType === 'raw'" v-model="rawDefinition" :alarm-enabled="form.alarmEnabled" :gongdan-enabled="form.gongdanEnabled" @enable-kind="enableRawKind">
    <div v-if="form.strategyType === 'raw'" class="raw-default-title"><strong>默认告警与工单阈值</strong><span>适用于未命中下方个性化配置的记录</span></div>
    <el-alert v-if="rawError" :title="rawError" type="error" :closable="false" />
    <div class="rule-grid">
      <div v-for="kind in ['alarm', 'gongdan']" :key="kind"
           :class="['rule-card', `rule-card--${kind}`, { 'is-off': form[`${kind}Enabled`] !== 1 }]">
        <div class="rule-card__head">
          <div class="rule-card__title">
            <el-icon class="rule-card__icon"><Bell v-if="kind === 'alarm'" /><Tickets v-else /></el-icon>
            {{ kind === 'alarm' ? '告警规则' : '工单规则' }}
            <el-tag size="small" type="info">{{ form[`${kind}Rules`].length }} / 3 档</el-tag>
          </div>
          <el-switch v-model="form[`${kind}Enabled`]" :active-value="1" :inactive-value="0" size="small" @change="onRuleToggle" />
        </div>
        <div class="rule-card__body">
          <StrategyRuleList v-if="form[`${kind}Enabled`] === 1" v-model="form[`${kind}Rules`]"
                            :raw-records="form.strategyType === 'raw'" :subject="subjectLabel" :unit="currentUnit" :kind-label="kind === 'alarm' ? '告警' : '工单'" />
          <div v-else class="rule-card__off-tip">已关闭，保留配置，不会触发{{ kind === 'alarm' ? '告警' : '工单' }}</div>
        </div>
      </div>
    </div>

    </RawSourceEditor>

    <!-- 策略启用状态 -->
    <div class="status-bar">
      <span class="status-bar__label">策略状态</span>
      <el-switch v-model="form.status" :active-value="1" :inactive-value="0"
                 active-text="启用" inactive-text="停用" inline-prompt />
      <span class="status-bar__tip">停用后该策略完全不参与扫描，阈值仅作留档</span>
      <!-- 规则开着、状态却是停用 —— 这是最容易"以为配好了其实没生效"的情况，
           直接给一键启用，不用自己去拨上面那个开关 -->
      <span v-if="rulesOnButDisabled" class="status-bar__warn">
        规则已开启但策略为停用，保存后不会生效
        <el-button link type="primary" size="small" @click="form.status = 1">立即启用</el-button>
      </span>
    </div>

    <el-collapse v-if="form.id && form.strategyType !== 'raw'" @change="loadObservationStatus">
      <el-collapse-item title="有效数据计数状态（不按扫描次数累计）" name="observations">
        <p style="color: #909399; font-size: 12px;">按指标已配置频率判断超时，允许5分钟扫描延迟；频率不明确时不推断缺失。策略修改后，等待下一份新数据重新计数。</p>
        <el-alert v-if="observationError" :title="observationError" type="warning" :closable="false" />
        <el-table :data="observationStates" size="small" max-height="230" v-loading="observationLoading">
          <el-table-column prop="scopeKey" label="计数对象" min-width="140" />
          <el-table-column prop="status" label="状态" min-width="230" />
          <el-table-column prop="counts" label="连续异常次数" min-width="190" />
        </el-table>
        <span v-if="!observationLoading && !observationStates.length" style="color: #909399;">尚无观测记录，等待策略启用后的扫描。</span>
      </el-collapse-item>
    </el-collapse>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="rawLoading || !!rawError" @click="handleSave">确定</el-button>
    </template>
  </el-dialog>

  <DialogSizeControls :adjustWidth="adjustWidth" :visible="visible" />
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell, Tickets, Odometer } from '@element-plus/icons-vue'
import StrategyRuleList from './StrategyRuleList.vue'
import RawSourceEditor from './RawSourceEditor.vue'
import { rulesOf } from '../../utils/strategyRules'
import DialogSizeControls from '../common/DialogSizeControls.vue'
import { useDialogSize } from '../../utils/useDialogSize'
import { configApi, metricApi, systemApi, subcategoryApi, alarmApi } from '../../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  initial: { type: Object, default: null },
  lockTarget: { type: Boolean, default: false },
  metricContext: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])
const observationStates = ref([]), observationLoading = ref(false), observationError = ref('')
async function loadObservationStatus (opened) {
  if (!opened.includes('observations') || !form.value.id) return
  const id = form.value.id
  observationLoading.value = true; observationError.value = ''
  try {
    const res = await alarmApi.observationStatus(id)
    if (id !== form.value.id) return
    observationStates.value = (res.data || []).map(row => {
      const state = JSON.parse(row.observationState)
      const counts = Object.entries(state.counts || {}).map(([key, count]) => `${key.startsWith('a:') ? '告警' : '工单'}${({ 2: '一般', 3: '重要', 4: '严重' })[key.split(':')[1]] || ''} ${count}次`).join('；')
      return { ...row, status: state.status, counts: counts || '尚未累计' }
    })
  } catch (error) { observationError.value = error.message || '计数状态读取失败' }
  finally { observationLoading.value = false }
}

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

// 弹窗宽度可记忆（与告警页原来共用同一个 key，用户调过的宽度不会丢）
const { width: dialogWidth, save: saveWidth } = useDialogSize('alarm-strategy', { width: '920px' })

const adjustWidth = (delta) => {
  dialogWidth.value = `${Math.max(480, parseInt(dialogWidth.value) + delta)}px`
  saveWidth()
}

const conditionOptions = [
  { label: '大于', value: '>' },
  { label: '小于', value: '<' },
  { label: '大于等于', value: '>=' },
  { label: '小于等于', value: '<=' }
]

const rawDefinition = ref({ source:'',valueField:'',filter:{ field:'',mode:'exact',value:'' },overrides:[] })
const rawLoading = ref(false), rawError = ref('')
async function loadRawDefinition (id) {
  rawLoading.value=true
  try { const res=await configApi.rawStrategyDefinition(id); if(form.value.id === id) rawDefinition.value=res.data }
  catch(e) { if(form.value.id === id) rawError.value='读取原始数据配置失败，请关闭后重新打开，避免覆盖原配置' }
  finally { rawLoading.value=false }
}
const form = ref(emptyForm())
const saving = ref(false)
const isEdit = computed(() => !!form.value.id)

const dialogTitle = computed(() => {
  if (props.lockTarget) return isEdit.value ? '设置策略阈值' : '设置策略阈值'
  return isEdit.value ? '编辑策略' : '新增策略'
})

// 规则句子里的主语：指标值 / 健康度分值
const subjectLabel = computed(() =>
  form.value.strategyType === 'raw' ? '原始值' : form.value.strategyType === 'health' ? '健康度' : '指标值'
)

const currentUnit = computed(() => form.value.strategyType === 'raw' ? 'ms' : form.value.strategyType === 'health' ? '分'
  : props.metricContext?.unit || metricList.value.find(metric => metric.id === form.value.targetId)?.unit || '')

// 目标列表（仅告警页场景需要，按需加载）
const metricList = ref([])
const systemList = ref([])
const categoryList = ref([])
const subCategoryList = ref([])

// 指标级策略的级联选择：大类 → 小类 → 具体指标。
// 指标数据里只有小类编码没有小类 ID，所以这里按 (大类ID, 小类编码) 推导小类选项。
const subCategoryOptions = computed(() => {
  if (!form.value.categoryId) return []
  const seen = new Map()
  for (const metric of metricList.value) {
    if (metric.categoryId !== form.value.categoryId || !metric.subCategoryCode) continue
    if (!seen.has(metric.subCategoryCode)) {
      seen.set(metric.subCategoryCode, { code: metric.subCategoryCode, name: metric.subCategoryName || metric.subCategoryCode })
    }
  }
  return [...seen.values()]
})
const metricCandidates = computed(() => metricList.value.filter(metric =>
  (!form.value.categoryId || metric.categoryId === form.value.categoryId) &&
  (!form.value.subCategoryCode || metric.subCategoryCode === form.value.subCategoryCode)
))

function onMetricCategoryChange () {
  form.value.subCategoryCode = null
  form.value.targetId = null
  form.value.targetName = ''
  autoGenerateName()
}
function onMetricSubCategoryChange () {
  form.value.targetId = null
  form.value.targetName = ''
  autoGenerateName()
}
// 选中指标后把大/小类回填成它的真实归属，编辑时也能看到自己配的是哪一类
function onMetricPicked (metricId) {
  const metric = metricList.value.find(item => item.id === metricId)
  if (metric) {
    form.value.categoryId = metric.categoryId
    form.value.subCategoryCode = metric.subCategoryCode || null
  }
  autoGenerateName()
}

function emptyForm () {
  return {
    alarmRules: [],
    gongdanRules: [],
    id: null,
    strategyName: '',
    strategyType: 'metric',
    targetType: 'metric',
    targetId: null,
    targetName: '',
    categoryId: null,
    subCategoryCode: null,
    status: 1,
    // 阈值默认 null（表示"未配置"），不填 0，避免把 0 当成有效业务阈值
    alarmEnabled: 1,
    alarmCondition: '>',
    alarmThreshold: null,
    alarmConsecutiveTimes: 1,
    gongdanEnabled: 0,
    gongdanCondition: '>',
    gongdanThreshold: null,
    gongdanConsecutiveTimes: 1
  }
}

// 打开时把父级传入的 initial 摊平到表单
watch(
  () => [props.modelValue, props.initial],
  ([show]) => {
    if (!show) return
    observationStates.value = []; observationError.value = ''
    rawError.value = ''; rawDefinition.value = { source:'',valueField:'',filter:{ field:'',mode:'exact',value:'' },overrides:[] }
    const base = emptyForm()
    const src = props.initial || {}
    form.value = {
      ...base,
      ...src,
      alarmRules: rulesOf(src, 'alarm'),
      gongdanRules: rulesOf(src, 'gongdan'),
      alarmEnabled: src.alarmEnabled ?? (props.lockTarget ? 1 : 1),
      gongdanEnabled: src.gongdanEnabled ?? 0,
      alarmCondition: src.alarmCondition || '>',
      gongdanCondition: src.gongdanCondition || '>',
      alarmConsecutiveTimes: src.alarmConsecutiveTimes || 1,
      gongdanConsecutiveTimes: src.gongdanConsecutiveTimes || 1,
      status: src.status ?? 1
    }
    if (!src.id && !form.value.alarmRules.length) {
      form.value.alarmRules = [{ level: 2, condition: null, threshold: null, consecutiveTimes: 1 }]
    }
    if (src.id && src.strategyType === 'raw') loadRawDefinition(src.id)
    // 锁定目标时，目标由父级指定，这里补齐默认值
    if (props.lockTarget) {
      form.value.targetType = src.targetType || 'metric'
      form.value.strategyType = src.strategyType || 'metric'
      form.value.targetId = src.targetId ?? props.metricContext?.id ?? null
      form.value.targetName = src.targetName || props.metricContext?.metricName || ''
      if (!form.value.strategyName) {
        form.value.strategyName = `${form.value.targetName}-指标值-告警策略`
      }
    } else {
      loadLookups()
    }
  },
  { immediate: true }
)

/**
 * 打开任一规则时，自动把「策略状态」也打开。
 *
 * 原因：策略状态 = 停用 时该策略完全不参与告警扫描，只把告警/工单规则打开、
 * 状态却留在停用，等于什么都没配 —— 用户会以为配好了，但列表里的
 * 「告警概览」仍显示未配置。这里做联动，避免要连点两下才生效。
 * 反向不联动：关掉规则时不自动停用策略（可能还要靠另一条规则生效）。
 */
const enableRawKind = kind => { form.value[`${kind}Enabled`] = 1; onRuleToggle() }

const onRuleToggle = () => {
  for (const kind of ['alarm', 'gongdan']) {
    if (form.value[`${kind}Enabled`] === 1 && !form.value[`${kind}Rules`].length) {
      form.value[`${kind}Rules`] = [{ level: 2, condition: null, threshold: null, consecutiveTimes: 1 }]
    }
  }
  const anyRuleOn = form.value.alarmEnabled === 1 || form.value.gongdanEnabled === 1
  if (anyRuleOn && form.value.status !== 1) {
    form.value.status = 1
  }
}

// 规则开着但策略停用（典型场景：56 条由历史阈值迁移来的停用策略）。
// 这种情况下保存也不会生效，必须显式提醒并提供一键启用。
const rulesOnButDisabled = computed(() =>
  form.value.status !== 1 && (form.value.alarmEnabled === 1 || form.value.gongdanEnabled === 1)
)

async function loadLookups () {
  try {
    const [sys, cats, metrics] = await Promise.all([
      systemApi.listAll(),
      metricApi.categoryListAll(),
      metricApi.itemListAll()
    ])
    systemList.value = sys.data || []
    categoryList.value = cats.data || []
    metricList.value = metrics.data || []
  } catch (e) {
    console.error('加载策略可选目标失败:', e)
  }
  if (form.value.targetType === 'metric' && form.value.targetId) {
    // 指标级策略只存了 targetId；打开时按指标反查它所属的大类和小类，级联下拉才能回显
    const metric = metricList.value.find(item => item.id === form.value.targetId)
    if (metric) {
      form.value.categoryId = metric.categoryId
      form.value.subCategoryCode = metric.subCategoryCode || null
    }
  }
  if (form.value.targetType === 'subCategory') {
    if (!form.value.categoryId && form.value.targetId) {
      try {
        const res = await subcategoryApi.get(form.value.targetId)
        form.value.categoryId = res.data?.categoryId || null
      } catch (error) { ElMessage.error('读取小类所属大类失败，请重新选择告警对象') }
    }
    if (form.value.categoryId) loadSubCategories(form.value.categoryId)
  }
}

async function loadSubCategories (categoryId) {
  try {
    const res = await subcategoryApi.list(categoryId)
    subCategoryList.value = res.data || []
  } catch (e) {
    console.error('加载小类列表失败:', e)
    subCategoryList.value = []
  }
}

function onTargetTypeChange () {
  form.value.targetId = null
  if (form.value.targetType !== 'metric') {
    // 系统 / 大类 / 小类 只有健康度概念，指标值阈值无意义
    form.value.strategyType = 'health'
  }
  if (!['metric', 'subCategory'].includes(form.value.targetType)) {
    form.value.categoryId = null
    form.value.subCategoryCode = null
  }
  if (form.value.targetType === 'subCategory') {
    form.value.subCategoryCode = null
    subCategoryList.value = []
  }
  autoGenerateName()
}

function onSubCategoryCategoryChange (categoryId) {
  form.value.targetId = null
  subCategoryList.value = []
  if (categoryId) loadSubCategories(categoryId)
}

function autoGenerateName () {
  if (form.value.strategyType === 'raw') { form.value.strategyName = form.value.strategyName || '原始数据告警策略'; return }
  let targetName = ''
  const t = form.value.targetType
  if (t === 'metric') {
    targetName = metricList.value.find(m => m.id === form.value.targetId)?.metricName || '指标'
  } else if (t === 'system') {
    targetName = systemList.value.find(s => s.id === form.value.targetId)?.systemName || '系统'
  } else if (t === 'category') {
    targetName = categoryList.value.find(c => c.id === form.value.targetId)?.categoryName || '大类'
  } else if (t === 'subCategory') {
    targetName = subCategoryList.value.find(s => s.id === form.value.targetId)?.subCategoryName || '小类'
  }
  form.value.targetName = targetName
  form.value.strategyName = `${targetName}-${subjectLabel.value}-告警策略`
}

async function handleSave () {
  // 校验：目标和名称
  if (form.value.strategyType !== 'raw' && !form.value.targetId) {
    ElMessage.warning(props.lockTarget ? '缺少指标信息，无法保存' : '请先选择告警对象')
    return
  }
  for (const kind of ['alarm', 'gongdan']) {
    if (form.value[`${kind}Enabled`] !== 1) continue
    const label = kind === 'alarm' ? '告警' : '工单'
    const rules = form.value[`${kind}Rules`]
    if (!rules.length) { ElMessage.warning(`请至少添加一条${label}规则`); return }
    for (const [index, rule] of rules.entries()) {
      if (rule.threshold == null || !rule.condition || !Number.isInteger(rule.consecutiveTimes) || rule.consecutiveTimes < 1) {
        ElMessage.warning(`${label}第 ${index + 1} 条：请填写比较条件、阈值和${form.value.strategyType === 'raw' ? '连续异常记录条数' : '连续有效更新次数'}`)
        return
      }
    }
  }
  if (!form.value.strategyName) {
    form.value.strategyName = `${form.value.targetName}-${subjectLabel.value}-告警策略`
  }

  // 提交结构：与后端 AlarmStrategy 字段一一对应。
  // 后续新增字段（如告警等级）只需在这里加一处，两个页面同时生效。
  const submitData = {
    alarmRules: form.value.alarmRules,
    gongdanRules: form.value.gongdanRules,
    strategyName: form.value.strategyName,
    strategyType: form.value.strategyType,
    targetType: form.value.targetType,
    targetId: form.value.targetId,
    targetName: form.value.targetName,
    categoryId: form.value.categoryId,
    status: form.value.status,
    alarmEnabled: form.value.alarmEnabled,
    gongdanEnabled: form.value.gongdanEnabled
  }

  saving.value = true
  try {
    if (form.value.strategyType === 'raw') {
      await configApi.rawStrategySave({ strategy:{ ...submitData,id:form.value.id },definition:rawDefinition.value })
    } else if (form.value.id) {
      await configApi.strategyUpdate(form.value.id, submitData)
    } else {
      await configApi.strategyCreate(submitData)
    }
    ElMessage.success('保存成功')
    visible.value = false
    emit('saved')
  } catch (e) {
    console.error('保存策略失败:', e)
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

function onClosed () {
  form.value = emptyForm()
  subCategoryList.value = []
}
</script>

<style scoped>
.raw-default-title { display:flex; align-items:center; gap:14px; flex-wrap:wrap; margin:4px 0 12px; }
.raw-default-title span { color:var(--ui-muted); font-size:12px; }
/* 目标身份卡 */
.target-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
  border-radius: 10px;
  background: linear-gradient(135deg, #f2f7ff 0%, #f7fbff 100%);
  border: 1px solid #dbe8ff;
}
.target-card__icon {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ui-primary);
  color: var(--ui-surface);
  font-size: 19px;
  flex-shrink: 0;
}
.target-card__main { flex: 1; min-width: 0; }
.target-card__title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 1.4;
}
.target-card__unit {
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-primary);
  background: var(--ui-primary-soft);
  border-radius: 4px;
  padding: 1px 6px;
}
.target-card__path {
  margin-top: 3px;
  font-size: 12px;
  color: #8492a6;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.target-card__sep { color: #c0c4cc; }
.target-card__code {
  margin-left: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #a8abb2;
}

.lock-name-form { margin-bottom: 4px; }
.metric-option-code { float: right; margin-left: 12px; color: var(--ui-muted); font-size: 12px; }
.cascade-hint { margin: -6px 0 12px; color: var(--ui-muted); font-size: 12px; line-height: 1.6; }

/* 规则卡片 */
.rule-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
}
@media (max-width: 720px) {
  .rule-grid { grid-template-columns: 1fr; }
}

.rule-card {
  border: 1px solid #e4e8ef;
  border-radius: 10px;
  overflow: hidden;
  transition: all 0.2s;
  background: var(--ui-surface);
}
.rule-card--alarm { border-left: 3px solid var(--ui-danger); }
.rule-card--gongdan { border-left: 3px solid var(--ui-warning); }
.rule-card.is-off { background: #fafafa; opacity: 0.85; }
.rule-card.is-off.rule-card--alarm { border-left-color: #dcdfe6; }
.rule-card.is-off.rule-card--gongdan { border-left-color: #dcdfe6; }

.rule-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: #fbfcfe;
  border-bottom: 1px solid #f0f2f5;
}
.rule-card__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-heading);
}
.rule-card--alarm .rule-card__icon { color: var(--ui-danger); }
.rule-card--gongdan .rule-card__icon { color: var(--ui-warning); }

.rule-card__body { padding: 12px 14px; }

.rule-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rule-line--sub { margin-top: 8px; }
.rule-line__label {
  font-size: 13px;
  color: var(--ui-text);
  white-space: nowrap;
}
.rule-line__number { width: 108px; }
.rule-line__small { width: 64px; }
.rule-line__unit {
  font-size: 12px;
  color: var(--ui-muted);
  min-width: 20px;
}
.rule-card__off-tip {
  font-size: 12px;
  color: #a8abb2;
  padding: 6px 0;
}

/* 状态条 */
.status-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--ui-page);
  border: 1px solid #f0f2f5;
}
.status-bar__label {
  font-size: 13px;
  color: var(--ui-text);
  font-weight: 500;
}
.status-bar__tip {
  font-size: 12px;
  color: #a8abb2;
}

/* 规则开着但策略停用时的提醒：橙色，带一键启用 */
.status-bar__warn {
  margin-left: auto;
  font-size: 12px;
  color: var(--ui-warning);
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.rule-card { box-shadow:var(--ui-shadow-sm); border-radius:12px; }
.rule-card__head { background:var(--ui-page); border-bottom:1px solid var(--ui-border-soft); }
.rule-card--alarm .rule-card__icon { color:var(--ui-warning); }
.rule-card--gongdan .rule-card__icon { color:var(--ui-primary); }
.rule-card.is-off { box-shadow:none; }
.status-bar { border:1px solid var(--ui-border-soft); border-radius:9px; background:var(--ui-page); }
</style>
