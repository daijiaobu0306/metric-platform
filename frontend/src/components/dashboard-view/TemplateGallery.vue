<template>
  <section class="template-gallery">
    <div class="gallery-head">
      <div>
        <h3>模板样式</h3>
        <p>先看结构再建专屏。模板决定页签、默认模块和页面骨架，创建后仍可调整。</p>
      </div>
      <span class="gallery-count">共 {{ list.length }} 类模板</span>
    </div>
    <div class="template-grid">
      <article
        v-for="item in list"
        :key="item.key"
        class="template-card"
        :class="[`accent-${item.accent}`, { priority: item.status === 'priority', disabled: item.status === 'disabled' }]"
      >
        <div class="card-head">
          <div class="card-title">
            <span class="template-icon" aria-hidden="true">{{ shortName(item) }}</span>
            <div>
              <h4>{{ item.name }}<span v-if="item.badge" class="badge">{{ item.badge }}</span></h4>
              <small>{{ item.audience }}</small>
            </div>
          </div>
          <span class="usage">{{ counts[item.key] || 0 }} 个视图</span>
        </div>
        <p class="desc">{{ item.desc }}</p>
        <div class="mini-wireframe" aria-hidden="true">
          <div class="wire-top"><i></i><i></i><i></i><span></span></div>
          <div class="wire-kpis"><b v-for="n in 4" :key="n"></b><em v-for="n in 3" :key="n"></em></div>
          <div class="wire-tabs"><i v-for="tab in item.tabs.slice(0, 5)" :key="tab.key"></i></div>
          <div class="wire-panels"><b></b><b></b></div>
        </div>
        <div class="tab-chips">
          <span v-for="tab in item.tabs.slice(0, 4)" :key="tab.key">{{ tab.label }}</span>
          <span v-if="item.tabs.length > 4">+{{ item.tabs.length - 4 }}</span>
        </div>
        <div class="card-actions">
          <el-button type="primary" :disabled="item.status === 'disabled'" @click="emit('use', item.key)">用此模板新建</el-button>
          <el-button link type="primary" @click="emit('preview', item.key)">结构预览</el-button>
        </div>
        <p v-if="item.note" class="card-note">{{ item.note }}</p>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { templateList } from './templates'

const props = defineProps({
  counts: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['use', 'preview'])
const list = computed(() => templateList)
const shortName = item => item.name.slice(0, 1)
</script>

<style scoped>
.template-gallery { margin: 16px 0 20px; padding: 18px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius-lg); background: var(--ui-surface); box-shadow: var(--metric-shadow-xs); }
.gallery-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.gallery-head h3 { margin: 0; color: var(--metric-text); font-size: 15px; }
.gallery-head p { margin: 5px 0 0; color: var(--metric-text-muted); font-size: 12px; }
.gallery-count { padding: 4px 9px; border-radius: 999px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-size: 11px; white-space: nowrap; }
.template-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.template-card { display: flex; flex-direction: column; min-height: 300px; padding: 13px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: #fbfdff; transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease; }
.template-card:hover { border-color: var(--metric-primary-border); box-shadow: var(--metric-shadow-md); transform: translateY(-1px); }
.template-card.priority { border-color: var(--metric-primary); background: linear-gradient(180deg, var(--metric-primary-soft) 0%, var(--ui-surface) 46%); }
.template-card.disabled { opacity: .72; }
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.card-title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.template-icon { display: grid; place-items: center; width: 28px; height: 28px; flex: none; border: 1px solid var(--metric-primary-border); border-radius: 9px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-size: 12px; font-weight: 800; }
.accent-special .template-icon { border-color: #ddd6fe; background: var(--metric-special-soft); color: var(--metric-special); }
.accent-warning .template-icon { border-color: #fde68a; background: var(--metric-warning-soft); color: #b45309; }
.card-title h4 { display: flex; align-items: center; gap: 5px; margin: 0; color: var(--metric-text); font-size: 13px; }
.card-title small { display: block; margin-top: 2px; color: var(--metric-text-muted); font-size: 10px; }
.badge { padding: 1px 5px; border-radius: 999px; background: var(--metric-primary-soft); color: var(--metric-primary-hover); font-size: 9px; font-weight: 600; white-space: nowrap; }
.usage { flex: none; color: var(--metric-text-muted); font-size: 10px; }
.desc { min-height: 48px; margin: 10px 0; color: var(--metric-text-secondary); font-size: 11px; line-height: 1.55; }
.mini-wireframe { display: grid; gap: 5px; padding: 8px; border: 1px solid var(--ui-border); border-radius: 9px; background: var(--ui-surface); }
.wire-top { display: flex; align-items: center; gap: 4px; }
.wire-top i { width: 12px; height: 5px; border-radius: 2px; background: #e8eef6; }
.wire-top i:first-child { width: 22px; background: var(--metric-primary-border); }
.wire-top span { flex: 1; height: 5px; border-radius: 2px; background: #f1f5f9; }
.wire-kpis { display: grid; grid-template-columns: repeat(4, 1fr) repeat(3, 1fr); gap: 3px; }
.wire-kpis b, .wire-kpis em { height: 14px; border-radius: 3px; background: #f1f5f9; }
.wire-kpis b { background: #e0f2fe; }
.wire-kpis em { background: #fef3c7; }
.wire-tabs { display: flex; gap: 4px; padding-top: 2px; border-bottom: 1px solid #eef3f8; }
.wire-tabs i { width: 16px; height: 5px; border-radius: 2px 2px 0 0; background: #e8eef6; }
.wire-tabs i:first-child { background: var(--metric-primary); }
.wire-panels { display: grid; grid-template-columns: 1.3fr .7fr; gap: 4px; }
.wire-panels b { height: 40px; border-radius: 4px; background: var(--ui-page); border: 1px solid #eef3f8; }
.tab-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 10px; }
.tab-chips span { padding: 2px 6px; border-radius: 999px; background: #f1f5f9; color: var(--metric-text-secondary); font-size: 9px; }
.priority .tab-chips span { background: var(--ui-surface); border: 1px solid var(--metric-primary-border); color: var(--metric-primary-hover); }
.card-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: auto; padding-top: 12px; }
.card-note { margin: 8px 0 0; color: var(--metric-text-muted); font-size: 10px; line-height: 1.5; }
@media (max-width: 1200px) { .template-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .template-grid { grid-template-columns: 1fr; } .gallery-head { flex-direction: column; } }
</style>
