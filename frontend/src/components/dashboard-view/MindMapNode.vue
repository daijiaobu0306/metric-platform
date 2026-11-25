<template>
  <div class="mind-node" :class="[`depth-${depth}`, { 'subcategory-group': node.type === 'subcategory' }]">
    <article
      class="mind-card"
      :class="[node.type, metricLevel(node), { 'has-tree-children': hasChildren && node.type !== 'subcategory', 'is-tree-child': depth > 0 }]"
      tabindex="0"
      @click="onCard"
      @keyup.enter="onCard"
    >
      <div class="card-top">
        <span class="type-tag">{{ typeLabel }}</span>
        <span v-if="hasChildren" class="count-badge">{{ node.childCount }}</span>
      </div>
      <div class="card-main">
        <span class="status-dot" :class="metricLevel(node)" aria-hidden="true"></span>
        <div class="card-text">
          <b>{{ node.label }}</b>
          <small v-if="subtitle">{{ subtitle }}</small>
        </div>
      </div>
      <div class="card-foot" :class="{ 'only-toggle': node.type === 'subcategory' }">
        <span v-if="node.type !== 'subcategory'">{{ hasChildren ? `${node.metricCount} 项指标` : (node.metric?.metricCode || '') }}</span>
        <button
          v-if="hasChildren"
          type="button"
          class="toggle"
          :aria-expanded="isExpanded"
          @click.stop="emit('toggle', node.key)"
        >{{ isExpanded ? '收起' : '展开' }}</button>
      </div>
    </article>

    <template v-if="hasChildren && isExpanded">
      <div v-if="node.type === 'subcategory'" class="mind-metrics-wrap">
        <div class="mind-metrics">
          <button
            v-for="metric in node.children"
            :key="metric.key"
            type="button"
            class="metric-chip"
            :class="metricLevel(metric)"
            :title="metricTitle(metric)"
            @click.stop="emit('metric-detail', metricPayload(metric))"
          >
            <i class="chip-dot" aria-hidden="true"></i>
            <span>{{ metric.label }}</span>
          </button>
        </div>
      </div>
      <div v-else class="mind-children">
        <div v-for="child in node.children" :key="child.key" class="mind-child">
          <MindMapNode
            :node="child"
            :depth="depth + 1"
            :expanded-keys="expandedKeys"
            :keyword="keyword"
            @toggle="key => emit('toggle', key)"
            @metric-detail="metric => emit('metric-detail', metric)"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { scoreLevel } from '@/utils/healthThresholds'
import { computed } from 'vue'

defineOptions({ name: 'MindMapNode' })

const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  expandedKeys: { type: Object, default: () => new Set() },
  keyword: { type: String, default: '' }
})
const emit = defineEmits(['toggle', 'metric-detail'])
const hasChildren = computed(() => (props.node.children || []).length > 0)
const isExpanded = computed(() => Boolean(props.keyword) || props.node.forceOpen || props.expandedKeys.has(props.node.key))
const typeLabel = computed(() => ({ system: 'L1 系统', category: 'L2 大类', subcategory: 'L3 小类', metric: '指标' }[props.node.type] || '节点'))
const subtitle = computed(() => {
  const node = props.node
  if (node.type === 'system') return `${node.childCount} 个大类`
  if (node.type === 'category') return `${node.childCount} 个小类`
  if (node.type === 'subcategory') return ''
  return node.metric?.metricCode || ''
})
function metricLevel (node) {
  if (node.type !== 'metric') return 'branch'
  const score = node.health?.healthScore
  return score == null ? 'unknown' : scoreLevel(score) === 'danger' ? 'danger' : scoreLevel(score) === 'warning' ? 'warning' : 'normal'
}
function metricPayload (metric) {
  return { ...metric.metric, healthScore: metric.health?.healthScore, latestValue: metric.health?.latestValue }
}
function metricTitle (metric) {
  const score = metric.health?.healthScore
  return `${metric.label}${metric.metric?.metricCode ? ` · ${metric.metric.metricCode}` : ''}${score == null ? ' · 待计算' : ` · 健康度 ${score}`}`
}
function onCard () {
  if (hasChildren.value) emit('toggle', props.node.key)
}
</script>

<style scoped>
.mind-node { display: flex; align-items: flex-start; }
.mind-node.subcategory-group { padding: 5px 7px 5px 6px; margin-left: -6px; border: 1px solid #cfeef6; border-radius: 10px; background: linear-gradient(90deg, #f1fbfe 0%, #f7fcfe 100%); }
.mind-card {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: none;
  gap: 3px;
  width: 168px;
  min-height: 64px;
  padding: 7px 8px;
  border: 1px solid var(--metric-border);
  border-left: 4px solid var(--metric-primary);
  border-radius: 8px;
  background: var(--ui-surface);
  box-shadow: var(--metric-shadow-xs);
  cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}
.mind-card:hover,
.mind-card:focus-visible {
  outline: none;
  border-color: var(--metric-primary);
  box-shadow: var(--metric-shadow-md);
  transform: translateY(-1px);
}
.mind-card.system {
  width: 218px;
  min-height: 96px;
  border-color: #0ea5e9;
  background: linear-gradient(135deg, #0369a1 0%, #0284c7 52%, #0ea5e9 100%);
  color: var(--ui-surface);
  box-shadow: 0 6px 16px rgb(2 132 199 / .22);
}
.mind-card.category { width: 168px; border-left-color: #6366f1; background: linear-gradient(180deg, var(--ui-surface) 0%, #f5f7ff 100%); }
.mind-card.subcategory { width: 158px; border-left-color: #06b6d4; background: linear-gradient(180deg, var(--ui-surface) 0%, #f0fdff 100%); }
.card-top { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.type-tag { color: var(--metric-text-muted); font-size: 8px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; }
.mind-card.system .type-tag { color: #e0f2fe; }
.count-badge { display: inline-grid; place-items: center; min-width: 17px; height: 16px; padding: 0 4px; border-radius: 999px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-family: var(--metric-font-mono); font-size: 8px; font-weight: 700; }
.mind-card.system .count-badge { background: rgb(255 255 255 / .2); color: var(--ui-surface); }
.card-main { display: flex; align-items: center; gap: 6px; min-width: 0; }
.status-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--metric-text-muted); box-shadow: 0 0 0 3px rgb(148 163 184 / .14); }
.status-dot.branch { background: var(--metric-primary); box-shadow: 0 0 0 3px var(--metric-primary-soft); }
.mind-card.system .status-dot { background: #a7f3d0; box-shadow: 0 0 0 3px rgb(255 255 255 / .16); }
.card-text { display: grid; gap: 1px; min-width: 0; }
.card-text b { overflow: hidden; color: var(--metric-text); font-size: 11px; font-weight: 650; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.card-text small { overflow: hidden; color: var(--metric-text-muted); font-size: 8px; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.mind-card.system .card-text b { color: var(--ui-surface); font-size: 13px; }
.mind-card.system .card-text small { color: #bae6fd; }
.card-foot { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-top: auto; padding-top: 3px; border-top: 1px solid #f1f5f9; color: var(--metric-text-muted); font-size: 8px; }
.card-foot.only-toggle { justify-content: flex-end; border-top: 0; padding-top: 1px; }
.mind-card.system .card-foot { border-top-color: rgb(255 255 255 / .2); color: #bae6fd; }
.toggle { padding: 1px 5px; border: 1px solid var(--metric-primary-border); border-radius: 999px; background: var(--ui-surface); color: var(--metric-primary-hover); font-size: 8px; cursor: pointer; }
.toggle:hover { background: var(--metric-primary-soft); }
.mind-card.system .toggle { border-color: rgb(255 255 255 / .35); background: rgb(255 255 255 / .12); color: var(--ui-surface); }
.mind-children { position: relative; display: flex; flex-direction: column; gap: 7px; padding-left: 30px; margin-left: 2px; }
.mind-children::before { content: ''; position: absolute; left: 14px; top: 32px; bottom: 32px; width: 2px; border-radius: 2px; background: linear-gradient(180deg, var(--metric-primary) 0%, var(--metric-primary-border) 52%, var(--metric-primary) 100%); }
.mind-child { position: relative; display: flex; align-items: center; animation: mind-in .18s ease-out both; }
.mind-card.has-tree-children::after { content: ''; position: absolute; left: 100%; top: 50%; width: 16px; height: 2px; border-radius: 2px; background: var(--metric-primary-border); }
.mind-card.is-tree-child::before { content: ''; position: absolute; left: -16px; top: 50%; width: 16px; height: 2px; border-radius: 2px; background: var(--metric-primary-border); }
.mind-metrics-wrap { position: relative; padding-left: 28px; margin-left: 2px; }
.mind-metrics-wrap::before { content: ''; position: absolute; left: 0; top: 32px; width: 28px; height: 2px; border-radius: 2px; background: var(--metric-primary-border); }
.mind-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  width: clamp(420px, 38vw, 720px);
  min-width: 260px;
  max-height: 300px;
  overflow: auto;
  padding: 7px 9px;
  border: 1px solid var(--metric-border);
  border-radius: 8px;
  background: rgb(255 255 255 / .78);
}
.metric-chip { display: inline-flex; align-items: center; gap: 5px; max-width: 190px; padding: 3px 7px; border: 1px solid #e2e8f0; border-radius: 5px; background: var(--ui-surface); color: var(--metric-text-secondary); font-size: 10px; line-height: 1.25; cursor: pointer; transition: border-color .15s ease, box-shadow .15s ease, transform .15s ease; }
.metric-chip span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.metric-chip:hover { border-color: var(--metric-primary-border); box-shadow: var(--metric-shadow-xs); transform: translateY(-1px); }
.chip-dot { width: 5px; height: 5px; flex: none; border-radius: 50%; background: var(--metric-text-muted); }
.metric-chip.normal { border-color: #d1fae5; background: #fbfffd; }
.metric-chip.normal .chip-dot { background: var(--metric-success); }
.metric-chip.warning { border-color: #fde68a; background: var(--metric-warning-soft); color: #92400e; }
.metric-chip.warning .chip-dot { background: var(--metric-warning); }
.metric-chip.danger { border-color: #fecdd3; background: var(--metric-danger-soft); color: #9f1239; }
.metric-chip.danger .chip-dot { background: var(--metric-danger); }
.metric-chip.unknown { border-color: #e2e8f0; background: #fbfdff; color: var(--metric-text-muted); }
@keyframes mind-in {
  from { opacity: 0; transform: translateX(-4px); }
  to { opacity: 1; transform: translateX(0); }
}
@media (max-width: 1100px) { .mind-metrics { width: 420px; } }
@media (max-width: 760px) {
  .mind-card, .mind-card.system, .mind-card.category, .mind-card.subcategory { width: 160px; }
  .mind-metrics { width: 300px; min-width: 220px; }
  .mind-children { padding-left: 24px; }
  .mind-children::before { left: 11px; }
  .mind-card.has-tree-children::after { width: 13px; }
  .mind-card.is-tree-child::before { left: -13px; width: 13px; }
}
</style>
