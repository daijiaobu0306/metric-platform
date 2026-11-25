<template>
  <div class="rule-list">
    <div class="rule-list__scroll">
      <div v-for="(rule, index) in modelValue" :key="index" class="rule-row">
        <el-select v-model="rule.level" aria-label="规则级别" class="rule-level">
          <el-option v-for="level in ruleLevels" :key="level.value" :label="level.label" :value="level.value"
                     :disabled="modelValue.some((other, otherIndex) => otherIndex !== index && other.level === level.value)" />
        </el-select>
        <span>当{{ subject }}</span>
        <el-select v-model="rule.condition" aria-label="比较条件" placeholder="选择条件" class="rule-condition">
          <el-option v-for="option in conditions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-input-number v-model="rule.threshold" :ref="element => { thresholdInputs[index] = element }" :controls="false" :step="1" placeholder="未配置" aria-label="阈值" class="rule-threshold" />
        <span v-if="unit" class="rule-unit">{{ unit }}</span>
        <span v-if="!singleRecord">连续</span>
        <el-input-number v-if="!singleRecord" v-model="rule.consecutiveTimes" :min="1" :precision="0" :controls="false" :aria-label="rawRecords ? '连续异常记录条数' : '连续有效数据更新次数'" class="rule-count" />
        <span v-if="singleRecord">单条记录满足即触发</span>
        <el-tooltip v-else-if="rawRecords" content="同一对象按新入库记录计次；正常值或空值打断连续计数，重复扫描不计次。"><span>条新记录异常时触发</span></el-tooltip>
        <el-tooltip v-else content="每收到一个新的数据时点才计1次；同一时点的重复扫描、重传和修订不增加次数。"><span>次有效更新异常时触发</span></el-tooltip>
        <el-button link type="danger" @click="remove(index)" :aria-label="`删除第${index + 1}条规则`">删除</el-button>
      </div>
    </div>
    <div class="rule-list__actions">
      <el-button v-if="modelValue.length < 3" link type="primary" @click="add">＋ 添加{{ kindLabel }}规则</el-button>
      <span v-else>已配置全部三个档次</span>
      <span class="rule-list__hint">多个档次同时满足时，仅触发{{ singleRecord ? '满足条件' : rawRecords ? '满足连续异常记录次数' : '满足连续有效更新次数' }}的最高级别</span>
    </div>
    <p v-if="rawRecords" class="rule-list__hint">同一对象连续N条新记录异常才触发；正常或空值中断，重复扫描不计次。告警和工单分别累计；达到次数后，后续异常记录继续满足触发条件，未关闭的同级工单不会重复创建。</p>
    <p v-if="!singleRecord && !rawRecords" class="rule-list__hint">按新数据计次，不按5分钟扫描计次。例如每小时更新的指标，连续3次指3份新数据；正常等待不清零，正常值或确认超时缺数会中断连续计数。</p>
    <p v-if="!singleRecord && !rawRecords" class="rule-list__hint">首次扫描仅建立当前数据基线，不补算历史；随后收到新数据开始累计。同一数据时点的重传、修订与历史补传不增加次数。</p>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue'
import { ruleLevels } from '../../utils/strategyRules'
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  subject: { type: String, default: '指标值' },
  unit: { type: String, default: '' },
  rawRecords: { type: Boolean, default: false },
  singleRecord: { type: Boolean, default: false },
  kindLabel: { type: String, default: '告警' }
})
const thresholdInputs = ref([])
const emit = defineEmits(['update:modelValue'])
const conditions = [{ label: '大于', value: '>' }, { label: '大于等于', value: '>=' },
  { label: '小于', value: '<' }, { label: '小于等于', value: '<=' }, { label: '等于', value: '=' }]
async function add () {
  const level = ruleLevels.find(item => !props.modelValue.some(rule => rule.level === item.value))
  if (!level) return
  emit('update:modelValue', [...props.modelValue, { level: level.value, condition: props.modelValue.at(-1)?.condition ?? null, threshold: null, consecutiveTimes: 1 }])
  await nextTick()
  thresholdInputs.value[props.modelValue.length - 1]?.focus()
}
function remove (index) { emit('update:modelValue', props.modelValue.filter((_, i) => i !== index)) }
</script>

<style scoped>
.rule-list__scroll { overflow-x: auto; }
.rule-row { display: flex; align-items: center; gap: 8px; flex-wrap: nowrap; min-width: 700px; padding: 8px 0; font-size: 13px; color: var(--ui-text); white-space: nowrap; }
.rule-row + .rule-row { border-top: 1px solid var(--ui-border-soft); }
.rule-row > * { flex-shrink: 0; }
.rule-level { width: 88px; }
.rule-condition { width: 115px; }
.rule-threshold { width: 125px; }
.rule-count { width: 62px; }
.rule-unit { max-width: 90px; overflow: hidden; text-overflow: ellipsis; color: var(--ui-muted); }
.rule-list__actions { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding-top: 8px; font-size: 12px; color: var(--ui-muted); }
.rule-list__hint { font-size: 12px; }

.rule-row { padding:12px 10px; border-radius:8px; }
.rule-row:hover { background:var(--ui-page); }
.rule-list__actions { border-top:1px solid var(--ui-border-soft); margin-top:8px; padding:12px 10px 0; }
.rule-row :deep(.el-input-number), .rule-row :deep(.el-select__wrapper) { font-variant-numeric:tabular-nums; }
</style>
