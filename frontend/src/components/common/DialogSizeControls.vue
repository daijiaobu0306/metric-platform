<template>
  <div v-if="visible" class="dialog-width-controls">
    <el-button @click="adjustWidth(50)" title="放大">+</el-button>
    <el-button @click="adjustWidth(-50)" title="缩小">−</el-button>
  </div>
</template>

<script setup>
defineProps({
  adjustWidth: Function,
  visible: {
    type: Boolean,
    default: false
  }
})
</script>

<style scoped>
.dialog-width-controls {
  position: absolute;
  right: -28px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  gap: 4px;
  pointer-events: auto;
  z-index: 10;
}
.dialog-width-controls .el-button {
  width: 20px;
  height: 24px;
  padding: 0;
  font-size: 14px;
  min-height: 24px;
  line-height: 24px;
}
</style>