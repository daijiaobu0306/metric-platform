<template>
  <article
    class="metric-kpi-card"
    :class="['accent-' + accent, { clickable, 'is-highlighted': highlighted }]"
    role="button"
    :tabindex="clickable ? 0 : -1"
    :aria-label="`${label}: ${value}`"
    @click="onClick"
    @keyup.enter="onClick"
    @keyup.space="onClick"
  >
    <span class="metric-bar" :class="`metric-bar-${accent}`" aria-hidden="true"></span>
    <span class="label">{{ label }}</span>
    <b class="value metric-num" :class="isLong ? 'value-long' : 'metric-num-2xl'">{{ formattedValue }}</b>
    <small class="note" v-if="note">{{ note }}</small>
  </article>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label:  { type: String, required: true },
  value:  { type: [String, Number], required: true },
  note:   { type: String, default: '' },
  accent: { type: String, default: 'primary' },
  clickable: { type: Boolean, default: false },
  highlighted: { type: Boolean, default: false }
})
const emit = defineEmits(['click'])

const formattedValue = computed(() => {
  const v = props.value
  if (v == null || v === '') return '--'
  return typeof v === 'number' ? v.toLocaleString('en-US') : String(v)
})
const isLong = computed(() => String(formattedValue.value).length > 8)
const onClick = () => { if (props.clickable) emit('click') }
</script>

<style scoped>
.metric-kpi-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 84px;
  padding: 14px;
  background: var(--ui-surface);
  border: 1px solid var(--metric-border);
  border-radius: var(--metric-radius);
  box-shadow: var(--metric-shadow-xs);
  transition: box-shadow .18s ease, border-color .18s ease, transform .18s ease;
}
.metric-kpi-card.clickable {
  cursor: pointer;
}
.metric-kpi-card.clickable:hover {
  border-color: var(--metric-primary-border);
  box-shadow: var(--metric-shadow-md);
  transform: translateY(-1px);
}
.metric-kpi-card.clickable:focus-visible {
  outline: 2px solid var(--metric-primary);
  outline-offset: 2px;
}
.metric-kpi-card.is-highlighted {
  border-color: var(--metric-primary);
  background: var(--metric-primary-soft);
}
.metric-kpi-card .label {
  font-size: var(--metric-text-xs);
  color: var(--metric-text-secondary);
  font-weight: 500;
}
.metric-kpi-card .value {
  color: var(--metric-text);
  margin-top: 2px;
  line-height: 1.15;
}
.metric-kpi-card .note {
  font-size: var(--metric-text-xs);
  color: var(--metric-text-muted);
}
.metric-kpi-card .value-long {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.35;
  word-break: break-all;
}
.metric-kpi-card.accent-danger  .value { color: var(--metric-danger); }
.metric-kpi-card.accent-warning .value { color: var(--metric-warning); }
.metric-kpi-card.accent-success .value { color: var(--metric-success); }
.metric-kpi-card.accent-special .value { color: var(--metric-special); }
</style>
