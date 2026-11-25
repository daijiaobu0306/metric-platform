<template>
  <span class="collect-time-filter">
    <el-date-picker
      :model-value="date"
      type="date"
      size="small"
      value-format="YYYY-MM-DD"
      :placeholder="datePlaceholder"
      :clearable="clearable"
      :disabled="disabled"
      :style="{ width: dateWidth }"
      @update:model-value="onDate"
    />
    <el-select
      v-if="showTimes"
      :model-value="dataTime"
      size="small"
      class="time-select"
      :placeholder="loading ? '加载时间点…' : '时间点'"
      :disabled="disabled || loading"
      :loading="loading"
      @update:model-value="onTime"
    >
      <el-option :label="latestLabel" value="" />
      <el-option
        v-for="time in options"
        :key="time"
        :label="formatTime(time)"
        :value="time"
      />
    </el-select>
    <el-tag v-else-if="singleTime" size="small" type="info" effect="plain" class="single-time">
      {{ formatTime(singleTime) }}
    </el-tag>
    <span v-if="hint" class="filter-hint">{{ hint }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  date: { type: String, default: '' },
  dataTime: { type: String, default: '' },
  times: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  clearable: { type: Boolean, default: true },
  datePlaceholder: { type: String, default: '数据日期（默认最新）' },
  dateWidth: { type: String, default: '150px' },
  latestLabel: { type: String, default: '最新一批' },
  hint: { type: String, default: '' }
})

const emit = defineEmits(['update:date', 'update:dataTime', 'change'])

// 只有该指标当天真的有多批观测时才出现时间点下拉；日粒度大类看到的是单时间点标签。
const showTimes = computed(() => options.value.length > 1)
const singleTime = computed(() => (options.value.length === 1 ? options.value[0] : ''))

// 已选时间点可能落在后端返回的前 N 条之外（大类批次数很多时），补进选项避免显示成原始值。
const options = computed(() => {
  const list = [...props.times]
  if (props.dataTime && !list.includes(props.dataTime)) list.unshift(props.dataTime)
  return list
})

// 只显示时分秒，日期由左侧日期框承担，避免同一行重复出现两遍日期。
function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  const parts = text.split(' ')
  return parts.length > 1 ? parts[1] : text
}

function onDate(value) {
  emit('update:date', value || '')
  // 换日期后原来的时间点已失效，回到"最新一批"。
  emit('update:dataTime', '')
  emit('change', { date: value || '', dataTime: '' })
}

function onTime(value) {
  const next = value || ''
  emit('update:dataTime', next)
  emit('change', { date: props.date, dataTime: next })
}
</script>

<style scoped>
.collect-time-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.collect-time-filter .time-select { width: 108px; }
.single-time {
  font-variant-numeric: tabular-nums;
  color: var(--ui-muted, #909399);
}
.filter-hint { color: var(--ui-muted, #909399); font-size: 11px; }
</style>