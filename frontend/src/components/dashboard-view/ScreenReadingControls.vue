<template>
  <div class="reading-controls" :class="{ compact }" aria-label="全览控制">
    <el-select v-if="!compact" v-model="scrollMode" size="small" class="scroll-mode" aria-label="自动滚动方式">
      <el-option label="手动滚动" value="off"/>
      <el-option label="自动向下循环" value="repeat"/>
      <el-option label="自动上下往返" value="bounce"/>
    </el-select>
    <el-select v-if="!compact && scrollMode !== 'off'" v-model="speed" size="small" class="scroll-speed" aria-label="自动滚动速度">
      <el-option label="慢速" :value="0.018"/>
      <el-option label="标准速度" :value="0.035"/>
      <el-option label="快速" :value="0.065"/>
    </el-select>
    <el-button size="small" :type="isFullscreen ? 'primary' : 'default'" @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : (compact ? '全屏' : '全屏展示') }}</el-button>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ compact: { type: Boolean, default: false } })

const scrollMode=ref('off'),speed=ref(0.035),isFullscreen=ref(false)
let frame=0,direction=1,last=0
const root=()=>document.querySelector('.screen-page')
function stop(){if(frame)cancelAnimationFrame(frame);frame=0;last=0}
function tick(now){
  if(scrollMode.value==='off')return stop()
  const elapsed=last?Math.min(50,now-last):16;last=now
  const container=isFullscreen.value?root():document.scrollingElement
  if(!container){frame=requestAnimationFrame(tick);return}
  const max=Math.max(0,container.scrollHeight-container.clientHeight)
  if(!max){frame=requestAnimationFrame(tick);return}
  let next=container.scrollTop+direction*elapsed*speed.value
  if(scrollMode.value==='repeat'&&next>=max){container.scrollTo({top:0,behavior:'auto'});next=0}
  else if(scrollMode.value==='bounce'&&next>=max){direction=-1;next=max}
  else if(scrollMode.value==='bounce'&&next<=0){direction=1;next=0}
  container.scrollTo({top:Math.max(0,Math.min(max,next)),behavior:'auto'})
  frame=requestAnimationFrame(tick)
}
function start(){stop();direction=1;if(scrollMode.value!=='off')frame=requestAnimationFrame(tick)}
async function toggleFullscreen(){
  try{if(document.fullscreenElement)await document.exitFullscreen();else await root()?.requestFullscreen()}catch{}
}
function syncFullscreen(){isFullscreen.value=document.fullscreenElement===root()}
watch(scrollMode,start)
onMounted(()=>document.addEventListener('fullscreenchange',syncFullscreen))
onBeforeUnmount(()=>{stop();document.removeEventListener('fullscreenchange',syncFullscreen)})
</script>

<style scoped>
.reading-controls{display:flex;align-items:center;gap:8px}.scroll-mode{width:150px}.scroll-speed{width:112px}@media(max-width:680px){.reading-controls{width:100%}.scroll-mode{flex:1}.scroll-speed{width:112px}}
</style>
