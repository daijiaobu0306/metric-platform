<template><section class="metric-overview" aria-label="指标体系概览"><WorkspaceStatCard compact variant="hierarchy" v-for="card in cards" :key="card.key" :label="card.label" :value="card.value" :icon="card.icon" :tone="card.tone" :unit="card.unit" hint="配置明细" @click="emit('configure',card.key)" /></section></template>
<script setup>
import { computed } from 'vue'
import WorkspaceStatCard from '@/components/workspace/WorkspaceStatCard.vue'
const props=defineProps({stats:{type:Object,required:true},subcategoryCount:{type:Number,default:0}})
const emit=defineEmits(['configure'])
const cards=computed(()=>[
 {key:'system',label:'系统总数',value:props.stats.systemCount || 0,icon:'Monitor',unit:'个纳管域'},
 {key:'category',label:'指标大类总数',value:props.stats.categoryCount || 0,icon:'Grid',unit:'个分类',tone:'indigo'},
 {key:'subcategory',label:'指标小类总数',value:props.subcategoryCount,icon:'FolderOpened',unit:'个分支',tone:'success'},
 {key:'metric',label:'具体指标权重总数',value:props.stats.itemCount || 0,icon:'DataAnalysis',unit:'个指标',tone:'violet'}
])
</script>
<style scoped>.metric-overview { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; } @media(max-width:1100px) { .metric-overview { grid-template-columns:repeat(2,minmax(0,1fr)); } } @media(max-width:768px) { .metric-overview { gap:10px; } }</style>
