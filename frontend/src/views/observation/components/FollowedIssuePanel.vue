<template>
<section class="followed-issues-section">
<header class="obs-section-header"><h2><i></i>关注问题 <span class="issue-count">{{ total ?? issues.length }} 项关注未结</span></h2><el-button link type="primary" @click="emit('more')">查看更多 <el-icon><Right /></el-icon></el-button></header>
<div class="followed-issue-list"><article v-for="issue in issues" :key="issue.id" class="followed-issue-card"><div class="issue-card-top"><span class="issue-card-label"><span class="issue-dot"></span>{{ issue.issueType || '关注问题' }}</span><span class="issue-elapsed">已登记 {{ calcElapsedTime(issue.registerTime) }}</span></div><p>{{ issue.issueDescription || '-' }}</p><footer><span>登记时间：{{ formatDateTime(issue.registerTime) }}</span><el-button link type="primary" @click="emit('more')">前往问题管理 <el-icon><ArrowRight /></el-icon></el-button></footer></article><el-empty v-if="!issues.length" description="暂无关注问题" :image-size="60" /></div>
</section>
</template>
<script setup>
import { formatDateTime } from '@/utils'
defineProps({total:{type:Number,default:null},issues:{type:Array,default:()=>[]},calcElapsedTime:{type:Function,required:true}})
const emit=defineEmits(['more'])
</script>
<style scoped src="@/views/observation/followed-issues.css"></style>
