<template>
  <div class="drilldown-breadcrumb" v-if="breadcrumbs.length > 1">
    <span class="breadcrumb-label">当前位置：</span>
    <template v-for="(item, index) in breadcrumbs" :key="index">
      <span
        v-if="index < breadcrumbs.length - 1"
        class="breadcrumb-item clickable"
        @click="handleClick(index)"
      >
        {{ item.label }}
      </span>
      <span v-else class="breadcrumb-item current">
        {{ item.label }}
      </span>
      <span v-if="index < breadcrumbs.length - 1" class="breadcrumb-separator">&gt;</span>
    </template>
    <!-- 明确写出当前聚焦的绝对时间窗，避免只看“1天/小时”标签误以为还是最近 N 小时 -->
    <span v-if="focusRange" class="breadcrumb-focus">聚焦：{{ focusRange }}</span>
    <el-button
      v-if="breadcrumbs.length > 1"
      class="reset-btn"
      size="small"
      link
      type="primary"
      @click="handleReset"
    >
      重置
    </el-button>
  </div>
</template>

<script setup>
import { defineProps, defineEmits } from 'vue'

const props = defineProps({
  breadcrumbs: {
    type: Array,
    default: () => []
    // 每项: { label: string, timeRange: string, startTime?: string, endTime?: string, custom?: boolean }
  },
  // 当前聚焦时间窗（下钻/自定义区间生效时展示，格式：起始 ~ 结束）
  focusRange: { type: String, default: '' }
})

const emit = defineEmits(['navigate', 'reset'])

const handleClick = (index) => {
  const item = props.breadcrumbs[index]
  emit('navigate', { index, ...item })
}

const handleReset = () => {
  emit('reset')
}
</script>

<style scoped>
.drilldown-breadcrumb {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  background: var(--ui-page);
  border-radius: 4px;
  margin-bottom: 12px;
  font-size: 13px;
  flex-wrap: wrap;
}

.breadcrumb-label {
  color: var(--ui-muted);
  margin-right: 4px;
}

.breadcrumb-item {
  padding: 2px 6px;
  border-radius: 3px;
}

.breadcrumb-item.clickable {
  color: var(--ui-primary);
  cursor: pointer;
  transition: background 0.2s;
}

.breadcrumb-item.clickable:hover {
  background: #ecf5ff;
}

.breadcrumb-item.current {
  color: var(--ui-heading);
  font-weight: 500;
  background: #e4e7ed;
}

.breadcrumb-separator {
  color: #c0c4cc;
  margin: 0 2px;
}

.breadcrumb-focus {
  margin-left: auto;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--ui-primary-soft, #ecf5ff);
  color: var(--ui-primary, #3b82f6);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.reset-btn {
  margin-left: 8px;
}
</style>
