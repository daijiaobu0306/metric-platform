<template><el-empty :image-size="56" :description="title" class="workspace-empty-state"><p v-if="description">{{ description }}</p><slot /></el-empty></template>
<script setup>
defineProps({ title: {type:String, default:'暂无数据'}, description:String })
</script>
<style scoped>
.workspace-empty-state { padding:36px 16px; }.workspace-empty-state :deep(.el-empty__bottom) { margin-top:10px; }p { margin:0 auto 12px; max-width:360px; color:var(--ui-subtle); font-size:12px; line-height:1.8; }
</style>
