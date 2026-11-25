<template><div class="screen-metric-trend"><el-empty v-if="!points.length" description="当前范围暂无健康度趋势" :image-size="64" /><div v-show="points.length" ref="chartElement" class="chart-canvas"></div></div></template>
<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { readWorkspaceChartTheme } from '@/utils/workspaceTheme'
const props = defineProps({ points:{type:Array,default:()=>[]} })
const chartElement = ref()
let chart
let observer
async function render() {
  await nextTick()
  if (!chartElement.value || !props.points.length || !chartElement.value.clientWidth) return
  chart ||= echarts.init(chartElement.value)
  const theme = readWorkspaceChartTheme(chartElement.value)
  chart.setOption({
    animation:false,
    tooltip:{trigger:'axis',axisPointer:{type:'line'}},
    grid:{left:46,right:24,top:24,bottom:42},
    xAxis:{type:'category',boundaryGap:false,data:props.points.map(point => String(point.recordTime || point.snapshotTime || point.time || '').replace('T',' ').slice(0,19)),axisLabel:{color:theme.muted,fontSize:10},axisLine:{lineStyle:{color:theme.border}}},
    yAxis:{type:'value',min:0,max:100,axisLabel:{color:theme.muted,fontSize:10},splitLine:{lineStyle:{color:theme.border,type:'dashed'}}},
    series:[{name:'健康度',type:'line',smooth:true,symbol:'circle',symbolSize:6,data:props.points.map(point => point.healthScore ?? null),lineStyle:{color:theme.primary,width:2},itemStyle:{color:theme.primary},areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:theme.areaStart},{offset:1,color:theme.areaEnd}])}}]
  },true)
  chart.resize()
}
watch(() => props.points,render,{deep:true})
onMounted(() => {
  observer = new ResizeObserver(() => { void render() })
  if (chartElement.value) observer.observe(chartElement.value)
  void render()
})
onBeforeUnmount(() => { observer?.disconnect();chart?.dispose() })
</script>
<style scoped>.chart-canvas { width:100%; height:280px; }.screen-metric-trend { min-width:0; }</style>
