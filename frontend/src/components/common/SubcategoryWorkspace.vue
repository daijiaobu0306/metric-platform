<template>
  <div v-if="enabled" class="subcategory-workspace" :class="`layout-${layout}`">
    <aside class="subcategory-directory" aria-label="指标小类目录">
      <div class="directory-heading"><span>指标小类</span><span class="directory-count">{{ items.length }}</span></div>
      <el-input v-model="search" placeholder="搜索指标小类" :prefix-icon="Search" clearable size="small" aria-label="搜索指标小类" />
      <nav class="directory-list" aria-label="选择指标小类">
        <button v-for="item in filteredItems" :key="item.code" type="button" class="directory-item"
          :class="{ 'is-active': modelValue === item.code }" :aria-pressed="modelValue === item.code" @click="select(item.code)">
          {{ item.name }}
        </button>
        <div v-if="!filteredItems.length" class="directory-empty">没有匹配的小类</div>
      </nav>
    </aside>
    <div class="mobile-directory">
      <label :for="selectId">指标小类</label>
      <el-select :id="selectId" :model-value="modelValue" filterable placeholder="搜索或选择指标小类" class="mobile-select" @change="select">
        <el-option v-for="item in items" :key="item.code" :label="item.name" :value="item.code" />
      </el-select>
    </div>
    <section class="subcategory-content" :aria-label="currentName || '指标数据'">
      <div class="subcategory-heading"><span class="heading-label">当前小类</span><h3>{{ currentName || '请选择指标小类' }}</h3></div>
      <slot />
    </section>
  </div>
  <slot v-else />
</template>

<script setup>
import { computed, ref, watch, useId } from 'vue'
import { Search } from '@element-plus/icons-vue'

const props = defineProps({
  layout: { type: String, default: 'directory' },
  enabled: { type: Boolean, default: true },
  modelValue: { type: String, default: '' },
  items: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'change'])
const search = ref('')
const selectId = `subcategory-${useId()}`
const filteredItems = computed(() => props.items.filter(item => item.name.toLowerCase().includes(search.value.trim().toLowerCase())))
const currentName = computed(() => props.items.find(item => item.code === props.modelValue)?.name || '')
const select = code => {
  if (code === props.modelValue) return
  emit('update:modelValue', code)
  emit('change', code)
}
watch(() => props.enabled, () => { search.value = '' })
</script>

<style scoped>
.subcategory-workspace { display: flex; align-items: flex-start; gap: 16px; min-width: 0; margin-top: 12px; }
.subcategory-directory { width: 236px; box-sizing: border-box; flex: 0 0 236px; background: #fafbfc; border: 1px solid var(--el-border-color-light); border-radius: 6px; padding: 12px; }
.directory-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; color: var(--el-text-color-primary); font-size: 13px; font-weight: 600; }
.directory-count { color: var(--el-text-color-secondary); background: var(--el-fill-color); border-radius: 10px; padding: 1px 7px; font-weight: 400; }
.directory-list { display: flex; flex-direction: column; gap: 4px; margin-top: 10px; max-height: min(66vh, 640px); overflow-y: auto; overflow-x: hidden; scrollbar-gutter: stable; }
.directory-item { display: block; width: 100%; border: 1px solid transparent; border-radius: 4px; padding: 9px 10px; background: transparent; color: var(--el-text-color-regular); text-align: left; line-height: 1.6; font-size: 13px; font-family: inherit; white-space: normal; overflow-wrap: anywhere; cursor: pointer; }
.directory-item:hover { background: var(--el-fill-color-light); color: var(--el-color-primary); }
.directory-item.is-active { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-7); color: var(--el-color-primary); font-weight: 600; }
.directory-item:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.directory-empty { padding: 20px 4px; font-size: 13px; text-align: center; color: var(--el-text-color-secondary); }
.subcategory-content { flex: 1; min-width: 0; }
.subcategory-heading { padding: 0 0 12px; margin-bottom: 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
.heading-label { display: block; margin-bottom: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.subcategory-heading h3 { margin: 0; font-size: 15px; line-height: 1.6; font-weight: 600; color: var(--el-text-color-primary); overflow-wrap: anywhere; }
.mobile-directory { display: none; }
@media (max-width: 1100px) {
  .subcategory-workspace { flex-direction: column; gap: 12px; }
  .subcategory-directory { display: none; }
  .mobile-directory { display: flex; align-items: center; gap: 10px; width: 100%; min-width: 0; }
  .mobile-directory label { flex-shrink: 0; font-size: 13px; color: var(--el-text-color-regular); }
  .mobile-select { flex: 1; min-width: 0; }
  .subcategory-content { width: 100%; }
}

.layout-tabs { display:block; }
.layout-tabs .subcategory-directory { width:100%; margin-bottom:16px; padding:12px 16px; border:1px solid var(--ui-border); border-radius:12px; background:var(--ui-surface); }
.layout-tabs .directory-heading { justify-content:flex-start; gap:8px; margin-bottom:8px; }
.layout-tabs .subcategory-directory > .el-input { max-width:220px; }
.layout-tabs .directory-list { flex-direction:row; flex-wrap:wrap; gap:8px; max-height:180px; margin-top:10px; }
.layout-tabs .directory-item { width:auto; padding:5px 12px; border:1px solid var(--ui-border); border-radius:20px; font-size:12px; }
.layout-tabs .directory-item.is-active { border-color:var(--ui-primary-border); background:var(--ui-primary-soft); color:var(--ui-primary-strong); }
.layout-tabs .subcategory-heading { padding:0 0 12px; }
@media(max-width:767px) { .layout-tabs .subcategory-directory { display:none; } }
</style>
