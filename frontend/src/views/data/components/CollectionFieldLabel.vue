<template>
  <span class="field-label"><span>{{ label }}</span><el-tooltip v-if="help" :content="help" placement="top" :show-after="250" :popper-style="{ maxWidth: '340px', lineHeight: '1.7' }"><button type="button" class="field-help" :aria-label="`${label}说明`" @click.stop><el-icon><QuestionFilled /></el-icon></button></el-tooltip></span>
</template>
<script setup>
import { QuestionFilled } from '@element-plus/icons-vue'
defineProps({ label: { type: String, default: '' }, help: { type: String, default: '' } })
</script>
<style scoped>
.field-label{display:inline-flex;align-items:center;gap:6px;vertical-align:middle}.field-help{display:inline-flex;align-items:center;justify-content:center;flex:none;width:18px;height:18px;padding:0;border:0;background:transparent;color:var(--el-text-color-placeholder);cursor:help;font-size:14px;line-height:1}.field-help:hover,.field-help:focus-visible{color:var(--el-color-primary)}.field-help:focus-visible{outline:2px solid var(--el-color-primary-light-5);outline-offset:2px;border-radius:50%}
</style>
