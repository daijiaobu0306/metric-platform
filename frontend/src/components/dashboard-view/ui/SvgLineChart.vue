<template>
  <svg
    class="svg-line-chart"
    :viewBox="`0 0 ${vbW} ${vbH}`"
    preserveAspectRatio="none"
    :aria-label="ariaLabel || '趋势折线图'"
    role="img"
  >
    <defs v-if="showArea">
      <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="resolvedColor" stop-opacity="0.32" />
        <stop offset="100%" :stop-color="resolvedColor" stop-opacity="0" />
      </linearGradient>
    </defs>
    <polygon
      v-if="showArea && hasData"
      :points="fillPoints"
      :fill="`url(#${gradientId})`"
    />
    <polyline
      v-if="hasData"
      :points="linePoints"
      :stroke="resolvedColor"
      :stroke-width="strokeWidth"
      fill="none"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <circle
      v-if="hasData && showEndPoint"
      :cx="endPoint.x"
      :cy="endPoint.y"
      :r="endRadius"
      fill="#fff"
      :stroke="resolvedColor"
      :stroke-width="endStrokeWidth"
    />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  points:      { type: Array, required: true },
  viewBoxWidth:  { type: Number, default: 100 },
  viewBoxHeight: { type: Number, default: 34 },
  color:       { type: String, default: '' },       // empty = currentColor
  showArea:    { type: Boolean, default: false },
  showEndPoint:{ type: Boolean, default: true },
  ariaLabel:   { type: String, default: '' }
})

const vbW = computed(() => props.viewBoxWidth)
const vbH = computed(() => props.viewBoxHeight)
const strokeWidth = computed(() => vbW.value <= 100 ? 2.4 : 2.6)
const endRadius = computed(() => vbW.value <= 100 ? 2.8 : 3.5)
const endStrokeWidth = computed(() => vbW.value <= 100 ? 1.8 : 2)
const resolvedColor = computed(() => props.color || 'currentColor')

const gradientId = computed(() => 'line-grad-' + Math.random().toString(36).slice(2, 9))

const numericPoints = computed(() =>
  (props.points || [])
    .map(p => {
      if (typeof p === 'number') return p
      if (p && typeof p === 'object') {
        return Number(p.healthScore ?? p.value ?? p.score)
      }
      return Number(p)
    })
    .filter(Number.isFinite)
)
const hasData = computed(() => numericPoints.value.length > 0)
const linePoints = computed(() => {
  const v = numericPoints.value
  if (!v.length) return ''
  const w = vbW.value, h = vbH.value
  const padTop = 2
  const innerH = h - padTop * 2
  return v.map((x, i) => {
    const xPos = (i * w) / ((v.length - 1) || 1)
    const clamped = Math.max(0, Math.min(100, x))
    const yPos = padTop + (innerH * (100 - clamped)) / 100
    return `${xPos.toFixed(2)},${yPos.toFixed(2)}`
  }).join(' ')
})
const fillPoints = computed(() => {
  if (!linePoints.value) return ''
  return `0,${vbH.value} ${linePoints.value} ${vbW.value},${vbH.value}`
})
const endPoint = computed(() => {
  if (!hasData.value) return null
  const pts = linePoints.value.split(' ')
  const last = pts[pts.length - 1].split(',')
  return { x: Number(last[0]), y: Number(last[1]) }
})
</script>

<style scoped>
.svg-line-chart {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}
</style>
