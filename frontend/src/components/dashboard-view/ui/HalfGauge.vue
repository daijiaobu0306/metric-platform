<template>
  <div class="half-gauge-wrap" :class="{ 'is-ring': ring }">
    <svg class="half-gauge" :viewBox="ring ? '0 0 120 120' : '0 0 190 108'" :aria-label="accessibleLabel" role="img">
      <path class="track" :d="path" pathLength="100" />
      <path
        v-if="percent > 0"
        class="progress"
        :d="path"
        pathLength="100"
        :stroke-dasharray="`${percent} 100`"
        :stroke-linecap="percent === 100 ? 'butt' : 'round'"
      />
      <text :x="ring ? 60 : 95" :y="ring ? 63 : 77" text-anchor="middle" class="value">
        {{ displayValue }}<tspan v-if="hasValue" class="unit">{{ unit }}</tspan>
      </text>
      <text v-if="label" :x="ring ? 60 : 95" :y="ring ? 80 : 94" text-anchor="middle" class="label">{{ label }}</text>
    </svg>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  value: { type: Number, default: null },
  max: { type: Number, default: 100 },
  label: { type: String, default: '' },
  unit: { type: String, default: '%' },
  ariaLabel: { type: String, default: '' },
  ring: Boolean
})

const path = computed(() => props.ring
  ? 'M 60 8 A 52 52 0 1 1 60 112 A 52 52 0 1 1 60 8 Z'
  : 'M 15 94 A 80 80 0 0 1 175 94')
const hasValue = computed(() => Number.isFinite(props.value) && Number.isFinite(props.max) && props.max > 0)
const percent = computed(() => hasValue.value ? Math.min(100, Math.max(0, props.value / props.max * 100)) : 0)
const displayValue = computed(() => hasValue.value ? Math.round(percent.value) : '—')
const accessibleLabel = computed(() => props.ariaLabel || [props.label, hasValue.value ? `${displayValue.value}${props.unit}` : '暂无可计算数据'].filter(Boolean).join(' '))
</script>

<style scoped>
.half-gauge-wrap { display: block; width: 100%; max-width: 190px; }
.half-gauge-wrap.is-ring { max-width: 126px; }
.half-gauge { display: block; width: 100%; height: auto; }
.half-gauge path { fill: none; stroke-width: 10; }
.half-gauge .track { stroke: var(--gauge-track, #e8edf4); stroke-linecap: round; }
.half-gauge .progress { stroke: var(--gauge-progress, #059669); }
.half-gauge .value {
  font-family: var(--metric-font-mono, inherit);
  font-size: 22px;
  font-weight: 800;
  fill: var(--gauge-text, var(--metric-text, #172c43));
  font-variant-numeric: tabular-nums;
}
.half-gauge .unit { font-size: 12px; font-weight: 600; }
.half-gauge .label { font-size: 10px; fill: var(--gauge-label, var(--metric-text-muted, #52667f)); }
</style>
