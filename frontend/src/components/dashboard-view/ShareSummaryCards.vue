<template>
  <section class="share-summary">
    <header>
      <b>{{ title }}</b>
      <span v-if="note">{{ note }}</span>
    </header>
    <div class="summary-grid">
      <div
        v-for="item in items"
        :key="item.label"
        class="summary-cell"
        :class="`tone-${item.tone || 'primary'}`"
      >
        <span class="cell-label">{{ item.label }}</span>
        <div class="cell-value">
          <b :class="{ 'is-long': String(item.value ?? '').length > 7 }">{{ item.value ?? '—' }}</b>
          <em v-if="item.unit">{{ item.unit }}</em>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
/**
 * 移动端专用汇总卡：2×2 紧凑网格，替代 PC 的四列大卡片。
 * PC 端每张卡有图标、状态徽标、说明文字，单张近 100px 高；
 * 手机上横向四个必然挤成一团，所以这里只保留「标签 + 数值 + 单位」，
 * 用左侧色条区分语义，整卡高度控制在 120px 左右。
 */
defineProps({
  title: { type: String, default: '汇总' },
  note: { type: String, default: '' },
  items: { type: Array, default: () => [] }
})
</script>

<style scoped>
.share-summary {
  margin-bottom: 6px;
  padding: 7px 9px 8px;
  border: 1px solid #dce5ef;
  border-radius: 7px;
  background: #fff;
}
header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
header b { color: #17324d; font-size: 12px; }
header span { color: #7c8ea3; font-size: 10px; }
.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px;
}
.summary-cell {
  position: relative;
  padding: 5px 7px 6px 11px;
  border-radius: 5px;
  background: #f6f9fc;
}
.summary-cell::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 7px;
  bottom: 7px;
  width: 3px;
  border-radius: 2px;
  background: #2563eb;
}
.cell-label {
  display: block;
  color: #6b7f96;
  font-size: 10px;
  line-height: 1.3;
}
.cell-value {
  display: flex;
  align-items: baseline;
  gap: 2px;
  min-width: 0;
}
.cell-value b {
  color: #17324d;
  font-size: 17px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.cell-value b.is-long {
  font-size: 11px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.cell-value em {
  color: #7c8ea3;
  font-size: 9px;
  font-style: normal;
}
.tone-success::before { background: #16a34a; }
.tone-warning::before { background: #d97706; }
.tone-danger::before { background: #dc2626; }
.tone-info::before { background: #0891b2; }
</style>
