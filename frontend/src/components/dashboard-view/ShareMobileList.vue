<template>
  <div class="mobile-list">
    <p v-if="!rows.length" class="empty">暂无记录</p>
    <ul v-else>
      <li v-for="(row, index) in visibleRows" :key="index">
        <details>
          <summary>
            <span class="row-title">{{ read(row, fields[0]) }}</span>
            <span v-for="field in preview" :key="field.label" class="row-preview">
              <span>{{ field.label }}</span><b>{{ read(row, field) }}</b>
            </span>
          </summary>
          <dl>
            <div v-for="field in fields.slice(1)" :key="field.label">
              <dt>{{ field.label }}</dt>
              <dd>{{ read(row, field) }}</dd>
            </div>
          </dl>
          <el-button v-if="onDetail" link type="primary" @click="onDetail(row)">查看指标详情</el-button>
        </details>
      </li>
    </ul>
    <el-button v-if="visibleRows.length < rows.length" class="more" @click="visibleCount += pageSize">
      显示更多（{{ visibleRows.length }} / {{ rows.length }}）
    </el-button>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  fields: { type: Array, required: true },
  onDetail: Function,
  previewFields: { type: Array, default: () => [] }
})
const preview = computed(() => props.previewFields.length
  ? props.fields.filter(field => props.previewFields.includes(field.label))
  : props.fields.slice(1, 2))
const pageSize = 20
const visibleCount = ref(pageSize)
const visibleRows = computed(() => props.rows.slice(0, visibleCount.value))

watch(() => props.rows, () => { visibleCount.value = pageSize })

function read (row, field) {
  const value = typeof field.value === 'function' ? field.value(row) : row[field.value]
  return value == null || value === '' ? '—' : value
}
</script>

<style scoped>
.mobile-list { min-width: 0; overflow-wrap: anywhere; }
ul { margin: 0; padding: 0; list-style: none; }
li { border-top: 1px solid var(--metric-border, #e2e8f0); }
summary { display: flex; align-items: center; gap: 8px; min-height: 42px; list-style: none; cursor: pointer; }
summary::-webkit-details-marker { display: none; }
summary::after { content: ''; width: 5px; height: 5px; flex: none; margin-right: 3px; border: solid #6a7b8f; border-width: 0 1px 1px 0; transform: rotate(-45deg); }
details[open] summary::after { transform: rotate(45deg); }
.row-title { flex: 1; color: #17324d; font-size: 13px; font-weight: 600; }
.row-preview { max-width: 30%; text-align: right; color: #52667f; font-size: 10px; line-height: 1.25; }
.row-preview b { display: block; color: #223852; font-size: 11px; font-variant-numeric: tabular-nums; }
dl { margin: 0; padding: 0 0 6px; display: grid; gap: 2px; font-size: 12px; line-height: 1.4; }
dl > div { display: grid; grid-template-columns: minmax(76px, .4fr) minmax(0, 1fr); gap: 10px; }
dt, .empty { color: #52667f; }
dd { margin: 0; color: #223852; }
.more { width: 100%; min-height: 44px; }
</style>
