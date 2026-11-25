<template>
  <section ref="root" class="external-page workspace-theme" v-loading="loading">
    <header>
      <h2>{{ entry.name||'外部页面' }}</h2>
      <el-button @click="load">重新连接</el-button>
      <el-button v-if="entry.url" @click="open">在新标签页打开</el-button>
      <!-- 全屏作用于整个 section：header 一起全屏，用户才能再点按钮退出；iframe 靠 :fullscreen 样式撑满剩余高度 -->
      <!-- title 放在外层 span 上：按钮 disabled 时原生 tooltip 不触发，套一层才能把「不支持」提示显示出来 -->
      <span class="fullscreen-btn" :title="supported ? (isFullscreen ? '退出全屏（按 Esc 也可以）' : '全屏显示嵌入页') : '当前浏览器不支持全屏 API，可改用新标签页打开'">
        <el-button :disabled="!supported" @click="toggleFullscreen">
          <el-icon><FullScreen /></el-icon>{{ isFullscreen ? '退出全屏' : '全屏' }}
        </el-button>
      </span>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-else-if="entry.mode==='embed'" title="若下方无法显示或需登录，请使用新标签页打开；外部系统需允许内嵌，跨站登录可能受浏览器限制。" type="info" :closable="false" />
    <iframe v-if="entry.url&&entry.mode==='embed'" :key="entry.url" :src="entry.url" :title="entry.name" referrerpolicy="no-referrer" sandbox="allow-scripts allow-forms allow-same-origin allow-popups allow-downloads" />
    <el-empty v-else-if="entry.url" description="此菜单使用新标签页打开"><el-button type="primary" @click="open">打开页面</el-button></el-empty>
  </section>
</template>
<script setup>
import {ref,onMounted,onBeforeUnmount,watch} from 'vue'
import {useRoute} from 'vue-router'
import {FullScreen} from '@element-plus/icons-vue'
import {externalMenuApi} from '../api'
const route=useRoute(),entry=ref({}),error=ref(''),loading=ref(false)
const root=ref(null),isFullscreen=ref(false)
// 全屏 API 不可用时按钮直接禁用，避免点了没反应
const supported=!!document.fullscreenEnabled
let sequence=0
async function load(){const n=++sequence;entry.value={};error.value='';loading.value=true;try{const r=await externalMenuApi.launch(route.params.id);if(n===sequence)entry.value=r.data}catch(e){if(n===sequence)error.value=e.message||'页面连接失败'}finally{if(n===sequence)loading.value=false}}
async function open(){const tab=window.open('about:blank','_blank');if(tab){tab.opener=null;const policy=tab.document.createElement('meta');policy.name='referrer';policy.content='no-referrer';tab.document.head.appendChild(policy)};try{const r=await externalMenuApi.launch(route.params.id);if(tab)tab.location.replace(r.data.url);else error.value='浏览器阻止新窗口，请允许本站弹窗后重试'}catch(e){tab?.close();error.value=e.message||'打开失败'}}
// Esc 退出由浏览器原生处理，会触发 fullscreenchange，这里只负责同步按钮文案和状态
function onFullscreenChange(){isFullscreen.value=!!root.value&&document.fullscreenElement===root.value}
async function toggleFullscreen(){if(!supported)return;try{if(document.fullscreenElement)await document.exitFullscreen();else await root.value?.requestFullscreen?.()}catch(e){error.value=e?.message||'全屏切换失败，可改用新标签页打开'}}
onMounted(()=>document.addEventListener('fullscreenchange',onFullscreenChange))
onBeforeUnmount(()=>document.removeEventListener('fullscreenchange',onFullscreenChange))
watch(()=>route.params.id,load,{immediate:true})
</script>
<style scoped>.external-page{padding:20px}.external-page header{display:flex;align-items:center;gap:12px;margin-bottom:16px}.external-page h2{flex:1;font-size:20px}.external-page iframe{width:100%;height:calc(100vh - 240px);min-height:480px;border:1px solid var(--ui-border);border-radius:10px;margin-top:12px;background:white}
/* 全屏时 section 自身撑满视口：header 固定高度，iframe 用 flex 吃掉剩余空间 */
.external-page:fullscreen{display:flex;flex-direction:column;padding:16px 20px;overflow:hidden;background:var(--ui-surface,#fff)}
.external-page:fullscreen header{flex:none}
.external-page:fullscreen iframe{flex:1;height:auto;min-height:0}
.external-page:fullscreen .el-empty{flex:1}
.external-page .fullscreen-btn{display:inline-flex;flex:none}
</style>