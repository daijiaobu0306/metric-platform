<template>
  <!-- 明细/来源表统一的正序、倒序切换：只调当前已取到行的展示顺序，不重新请求 -->
  <el-radio-group
    :model-value="modelValue"
    size="small"
    class="row-sort"
    :title="title || '只调整展示顺序，不重新取数'"
    @update:model-value="value => $emit('update:modelValue', value)"
  >
    <el-radio-button value="desc">倒序</el-radio-button>
    <el-radio-button value="asc">正序</el-radio-button>
  </el-radio-group>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: 'desc' },
  title: { type: String, default: '' }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.row-sort { flex: none; }
</style>