<template>
  <span class="metric-status-tag" :class="['tone-' + tone, size]" :title="title">
    <span v-if="dot" class="metric-dot" :class="dotClass" aria-hidden="true"></span>
    <slot>{{ displayLabel }}</slot>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  status:    { type: String, required: true },
  label:     { type: String, default: '' },
  tone:      { type: String, default: '' },
  dot:       { type: Boolean, default: false },
  pulse:     { type: Boolean, default: false },
  size:      { type: String, default: 'md' }
})

const STATUS_LABEL = {
  normal:   '正常',
  success:  '成功',
  healthy:  '正常',
  warning:  '关注',
  pending:  '待处理',
  danger:   '异常',
  unknown:  '待计算',
  info:     '提示',
  enabled:  '启用',
  disabled: '停用',
  done:     '已完成',
  closed:   '已关单',
  open:     '待处理'
}
const STATUS_TONE = {
  normal:   'success',
  success:  'success',
  healthy:  'success',
  warning:  'warning',
  pending:  'warning',
  danger:   'danger',
  unknown:  'neutral',
  info:     'primary',
  enabled:  'success',
  disabled: 'neutral',
  done:     'success',
  closed:   'success',
  open:     'warning'
}

const inferredTone = computed(() => props.tone || STATUS_TONE[props.status] || 'neutral')
const displayLabel = computed(() => props.label || STATUS_LABEL[props.status] || props.status)
const dotClass = computed(() => {
  const t = inferredTone.value
  return t === 'neutral' ? 'metric-dot' : `metric-dot-${t}`
})
</script>

<style scoped>
.metric-status-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--metric-radius-pill);
  font-size: var(--metric-text-2xs);
  font-weight: 500;
  line-height: 1.4;
  white-space: nowrap;
  border: 1px solid transparent;
}
.metric-status-tag.sm {
  padding: 1px 6px;
  font-size: 10px;
}
.metric-status-tag .metric-dot {
  width: 6px;
  height: 6px;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.5);
}
</style>
