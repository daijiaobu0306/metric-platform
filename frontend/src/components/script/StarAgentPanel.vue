<template>
  <section class="staragent-panel workspace-theme">
    <header><div><h3>staragent</h3><p>智云助手 · 使用平台身份免登录接入</p></div><el-button :loading="loading" @click="connect">重新连接</el-button><el-button type="primary" @click="open">新标签页打开</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-else title="若内嵌页面无法显示，请尝试新标签页打开，并确认当前网络可以访问智云内网服务。" type="info" :closable="false" />
    <div v-loading="loading" class="frame-container"><iframe v-if="url" :key="url" :src="url" title="staragent 智云助手" referrerpolicy="no-referrer" sandbox="allow-scripts allow-forms allow-same-origin allow-popups allow-downloads" /><el-empty v-else-if="!loading" description="尚未连接智云助手"><el-button @click="connect">重新连接</el-button></el-empty></div>
  </section>
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { zhiyunApi } from '../../api'
const url = ref(''), error = ref(''), loading = ref(false)
let sequence = 0
async function connect() {
  const n = ++sequence
  loading.value = true; error.value = ''; url.value = ''
  try { const { data } = await zhiyunApi.launch(); if (n === sequence) url.value = data.url }
  catch (e) { if (n === sequence) error.value = e.message || '连接失败，请联系管理员检查智云配置' }
  finally { if (n === sequence) loading.value = false }
}
async function open() {
  const target = window.open('about:blank', '_blank')
  if (!target) { error.value = '浏览器阻止新标签页，请允许本站弹窗后重试'; return }
  target.opener = null
  const policy = target.document.createElement('meta'); policy.name = 'referrer'; policy.content = 'no-referrer'; target.document.head.appendChild(policy)
  try { const { data } = await zhiyunApi.launch(); target.location.replace(data.url) }
  catch (e) { target.close(); error.value = e.message || '打开失败' }
}
onMounted(connect)
onBeforeUnmount(() => { sequence++; url.value = '' })
</script>
<style scoped>
.staragent-panel{padding:16px;border:1px solid var(--ui-border);border-radius:12px;background:var(--ui-surface)}header{display:flex;align-items:center;gap:10px;margin-bottom:14px}header>div{flex:1}h3{margin:0;font-size:16px}header p{margin:4px 0 0;font-size:12px;color:var(--ui-muted)}.frame-container{margin-top:12px;min-height:480px}iframe{display:block;width:100%;height:calc(100vh - 260px);min-height:480px;border:1px solid var(--ui-border);border-radius:8px;background:white}@media(max-width:768px){header{flex-wrap:wrap}header>div{flex-basis:100%}}
</style>
