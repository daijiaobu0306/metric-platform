<template>
  <el-dialog class="workspace-theme observation-record-dialog" :model-value="modelValue" :title="metric?.metricName || '指标采集记录'" width="min(900px, 94vw)" append-to-body @update:model-value="$emit('update:modelValue', $event)">
    <div v-loading="loading">
      <p class="source-note">{{ notice }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table :data="rows" border max-height="440" empty-text="所选范围暂无留存记录">
        <el-table-column label="数据时间" min-width="185"><template #default="{ row }">{{ row.missing ? `${row.dataTime.slice(0, 10)}（缺数）` : row.dataTime || '—' }}</template></el-table-column>
        <el-table-column label="指标值" min-width="130"><template #default="{ row }">{{ row.metricValue ?? '缺失' }}</template></el-table-column>
        <el-table-column label="单位" min-width="90"><template #default="{ row }">{{ row.unit || '未配置' }}</template></el-table-column>
        <el-table-column prop="status" label="记录状态" min-width="115" />
      </el-table>
    </div>
  </el-dialog>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { observationApi } from '../../api'
const props = defineProps({ modelValue: Boolean, metric: Object, timeRange: String, startTime: String, endTime: String })
defineEmits(['update:modelValue'])
const loading = ref(false), rows = ref([]), notice = ref(''), error = ref('')
let request = 0
watch(() => [props.modelValue, props.metric?.metricId, props.timeRange, props.startTime, props.endTime], async () => {
  const current = ++request
  if (!props.modelValue || !props.metric?.metricId) return
  rows.value = []; error.value = ''; notice.value = ''; loading.value = true
  try {
    const response = await observationApi.getMetricValueTrend({ metricId: props.metric.metricId, timeRange: props.timeRange || '7d', startTime: props.startTime, endTime: props.endTime })
    if (current !== request) return
    rows.value = [...(response.data?.data || [])].reverse()
    notice.value = response.data?.notice || ''
  } catch (err) {
    if (current === request) error.value = err.message || '读取失败'
  } finally {
    if (current === request) loading.value = false
  }
}, { immediate: true })
onBeforeUnmount(() => { request++ })
</script>

<style scoped>
.source-note { font-size: 12px; color: #7d8897; line-height: 1.8; margin: 0 0 16px; }
</style>
