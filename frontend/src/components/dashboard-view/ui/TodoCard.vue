<template>
  <article
    class="metric-todo-card"
    :class="['accent-' + accent, { clickable }]"
    role="button"
    :tabindex="clickable ? 0 : -1"
    :aria-label="`${title}: ${pending} ${sub}`"
    @click="emit('click')"
    @keyup.enter="emit('click')"
    @keyup.space="emit('click')"
  >
    <header>
      <h4>{{ title }}</h4>
      <span v-if="showDot" class="metric-dot" :class="`metric-dot-${accent === 'primary' ? 'primary' : accent}`" aria-hidden="true"></span>
    </header>
    <b class="pending metric-num metric-num-3xl">{{ pendingLabel }}</b>
    <span class="sub">{{ sub }}</span>
    <svg
      v-if="hasTrend"
      class="spark"
      viewBox="0 0 100 28"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <defs>
        <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" :stop-color="accentVar" stop-opacity="0.32" />
          <stop offset="1" :stop-color="accentVar" stop-opacity="0" />
        </linearGradient>
      </defs>
      <polygon :points="fillPoints" :fill="`url(#${gradientId})`" />
      <polyline :points="linePoints" :stroke="accentVar" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <div v-if="hasTrend" class="stats">
      <small>新增 {{ formatNumber(created) }}</small>
      <small>完成 {{ formatNumber(completed) }}</small>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title:     { type: String, required: true },
  pending:   { type: [String, Number], required: true },
  sub:       { type: String, default: '当前待处理' },
  created:   { type: [String, Number], default: 0 },
  completed: { type: [String, Number], default: 0 },
  trend:     { type: Array, default: () => [] },
  accent:    { type: String, default: 'primary' },
  clickable: { type: Boolean, default: true },
  showDot:   { type: Boolean, default: true }
})
const emit = defineEmits(['click'])

const ACCENT_VAR = {
  primary: 'var(--metric-primary)',
  danger:  'var(--metric-danger)',
  warning: 'var(--metric-warning)',
  success: 'var(--metric-success)',
  special: 'var(--metric-special)'
}
const accentVar = computed(() => ACCENT_VAR[props.accent] || ACCENT_VAR.primary)
const gradientId = computed(() => 'grad-' + Math.random().toString(36).slice(2, 9))

const trendValues = computed(() =>
  (props.trend || []).map(x => Number(x)).filter(Number.isFinite)
)
const hasTrend = computed(() => trendValues.value.length > 0)
const pendingLabel = computed(() => formatNumber(props.pending))
const linePoints = computed(() => {
  const v = trendValues.value
  if (!v.length) return ''
  return v.map((x, i) => {
    const xPos = (i * 100) / ((v.length - 1) || 1)
    const yPos = 27 - (Math.max(0, Math.min(100, x)) * 24) / 100
    return `${xPos.toFixed(2)},${yPos.toFixed(2)}`
  }).join(' ')
})
const fillPoints = computed(() => {
  if (!linePoints.value) return ''
  return `0,28 ${linePoints.value} 100,28`
})
function formatNumber (v) {
  if (v == null || v === '') return '--'
  return typeof v === 'number' ? v.toLocaleString('en-US') : String(v)
}
</script>

<style scoped>
.metric-todo-card {
  position: relative;
  display: grid;
  gap: 4px;
  padding: 14px;
  border-radius: var(--metric-radius-lg);
  background: var(--ui-surface);
  border: 1px solid var(--metric-border);
  box-shadow: var(--metric-shadow-xs);
  transition: box-shadow .18s ease, transform .18s ease, border-color .18s ease;
}
.metric-todo-card.clickable {
  cursor: pointer;
}
.metric-todo-card.clickable:hover {
  box-shadow: var(--metric-shadow-md);
  transform: translateY(-1px);
}
.metric-todo-card.clickable:focus-visible {
  outline: 2px solid var(--metric-primary);
  outline-offset: 2px;
}
.metric-todo-card.accent-danger {
  background: linear-gradient(135deg, var(--metric-danger-soft) 0%, var(--metric-warning-soft) 100%);
  border-color: #fecdd3;
}
.metric-todo-card.accent-warning {
  background: linear-gradient(135deg, var(--metric-warning-soft) 0%, #fef9c3 100%);
  border-color: #fde68a;
}
.metric-todo-card.accent-success {
  background: linear-gradient(135deg, var(--metric-success-soft) 0%, #ecfeff 100%);
  border-color: #a7f3d0;
}
.metric-todo-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0;
}
.metric-todo-card h4 {
  margin: 0;
  font-size: var(--metric-text-sm);
  font-weight: 600;
  color: var(--metric-text);
}
.metric-todo-card .pending {
  color: var(--metric-text);
  line-height: 1.05;
}
.metric-todo-card .sub {
  font-size: var(--metric-text-xs);
  color: var(--metric-text-secondary);
}
.metric-todo-card .spark {
  width: 100%;
  height: 28px;
  margin-top: 2px;
  overflow: visible;
}
.metric-todo-card .stats {
  display: flex;
  justify-content: space-between;
  margin-top: 2px;
}
.metric-todo-card .stats small {
  font-size: var(--metric-text-xs);
  color: var(--metric-text-secondary);
}
</style>
