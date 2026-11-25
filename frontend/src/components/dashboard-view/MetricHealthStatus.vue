<template><WorkspaceStatusBadge :tone="tone">{{ label }}<span v-if="showScore && score != null">{{ score }}</span></WorkspaceStatusBadge></template>
<script setup>
import { computed } from 'vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { scoreLevel } from '@/utils/healthThresholds'
const props = defineProps({ score:[Number,String], showScore:Boolean })
// 分档阈值统一来自 system_config（60/80），不再各自写死 70/90
const state = computed(() => scoreLevel(props.score))
const tone = computed(() => ({ unknown:'muted', danger:'danger', warning:'warning', normal:'success' })[state.value])
const label = computed(() => ({ unknown:'待计算', danger:'异常', warning:'关注', normal:'正常' })[state.value])
</script>
