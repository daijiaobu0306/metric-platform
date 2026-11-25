<template>
  <nav class="screen-dock" aria-label="专屏栏目">
    <button v-for="tab in primaryTabs" :key="tab.key" type="button" :aria-current="active === tab.key ? 'page' : undefined" @click="select(tab.key)">
      <el-icon><component :is="icons[tab.key] || Grid" /></el-icon>
      <span>{{ tab.short || tab.label }}</span>
    </button>
    <button v-if="extraTabs.length" type="button" :aria-expanded="expanded" :class="{ selected: extraActive }" @click="expanded = !expanded">
      <el-icon><MoreFilled /></el-icon>
      <span>{{ extraActive?.short || extraActive?.label || '更多' }}</span>
    </button>
    <div v-if="expanded" class="extra-tabs">
      <button v-for="tab in extraTabs" :key="tab.key" type="button" :aria-current="active === tab.key ? 'page' : undefined" @click="select(tab.key)">
        {{ tab.label }}
      </button>
    </div>
  </nav>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CircleCheck, DataAnalysis, Grid, List, MoreFilled, TrendCharts, Warning } from '@element-plus/icons-vue'

const props = defineProps({
  tabs: { type: Array, required: true },
  active: { type: String, required: true }
})
const emit = defineEmits(['select'])
const expanded = ref(false)
const icons = { 'health-observation': TrendCharts, health: TrendCharts, situation: List, overview: DataAnalysis, risk: Warning, outcome: CircleCheck, catalog: Grid, data: DataAnalysis }
const primaryTabs = computed(() => props.tabs.slice(0, 4))
const extraTabs = computed(() => props.tabs.slice(primaryTabs.value.length))
const extraActive = computed(() => extraTabs.value.find(tab => tab.key === props.active))
watch(() => props.active, () => { expanded.value = false })

function select (key) {
  expanded.value = false
  emit('select', key)
}
</script>

<style scoped>
.screen-dock { position: relative; flex: none; display: flex; background: #fff; border-top: 1px solid #e2e8f0; padding-bottom: env(safe-area-inset-bottom); }
button { display: flex; align-items: center; justify-content: center; gap: 3px; min-height: 46px; border: 0; background: transparent; color: #54657a; font: inherit; cursor: pointer; }
.screen-dock > button { flex: 1; min-width: 0; flex-direction: column; font-size: 11px; white-space: nowrap; }
.screen-dock .el-icon { font-size: 17px; }
button[aria-current], button.selected { color: #0069bd; font-weight: 650; }
.extra-tabs { position: absolute; inset: auto 8px calc(100% + 6px); z-index: 3; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px; padding: 6px; border: 1px solid #dce5ef; border-radius: 10px; background: #fff; box-shadow: 0 8px 28px #172e4724; }
.extra-tabs button { border-radius: 6px; background: #f4f7fb; font-size: 13px; }
</style>
