<template>
  <el-card class="kpi-card" shadow="hover" body-style="padding: 16px;">
    <div class="kpi-label">{{ label }}</div>
    <div class="kpi-value">{{ displayValue }}</div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: 0 },
})

const displayValue = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') return '-'
  return String(props.value)
})
</script>

<style scoped>
.kpi-card {
  margin-bottom: 12px;
}
.kpi-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.kpi-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
}
</style>
