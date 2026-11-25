<template>
  <section class="zhiyun-settings" v-loading="loading">
    <header><div><h3>智云嵌入配置</h3><p>配置技能管理中 staragent 的页面与登录参数。</p></div><el-button @click="load">刷新配置</el-button></header>
    <el-alert title="当前使用共享账号：不同平台用户保留各自稳定 ID，但在智云侧共享权限、会话、文件和审计身份。" type="warning" :closable="false" />
    <el-form label-position="top" class="settings-form" @submit.prevent="save">
      <div class="settings-grid">
        <el-form-item label="接入状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        <el-form-item label="共享密钥"><el-tag :type="secretConfigured ? 'success' : 'warning'">{{ secretConfigured ? '后端已配置' : '后端未配置' }}</el-tag><p class="hint">由后端环境变量维护，页面不读取或保存密钥。</p></el-form-item>
        <el-form-item label="登录回调地址" class="full"><el-input v-model="form.callbackUrl" maxlength="2048" placeholder="http://主机:端口/staragent/auth/callback/common" /><p class="hint">不含 token、redirect 等查询参数，登录时自动生成。</p></el-form-item>
        <el-form-item label="分享目标路径" class="full"><el-input v-model="form.redirect" type="textarea" :rows="3" maxlength="4096" placeholder="/share/a/分享标识" /><p class="hint">填写未编码的站内路径，以 /share/a/ 开头。</p></el-form-item>
        <el-form-item label="系统标识"><el-input v-model="form.issuer" maxlength="100" /><p class="hint">需与智云登记的系统标识一致。</p></el-form-item>
        <el-form-item label="智云共享登录账号"><el-input v-model="form.username" maxlength="100" /><p class="hint">所有获授权的平台用户均使用此智云账号。</p></el-form-item>
        <el-form-item label="外部用户 ID"><el-input model-value="自动使用当前登录用户的数据库 ID" disabled /><p class="hint">管理员为 1；其他用户各用自己的 ID，不随登录次数改变。</p></el-form-item>
        <el-form-item label="昵称（选填）"><el-input v-model="form.nickname" maxlength="200" placeholder="留空不传" /></el-form-item>
        <el-form-item label="邮箱（选填）"><el-input v-model="form.email" maxlength="200" placeholder="留空不传" /></el-form-item>
        <el-form-item label="手机号（选填）"><el-input v-model="form.phone" maxlength="200" placeholder="留空不传" /></el-form-item>
      </div>
      <el-button type="primary" native-type="submit" :loading="saving" :disabled="loading || !version">保存配置</el-button>
      <span class="hint save-hint">保存后，在 staragent 页签点击“重新连接”生效。</span>
    </el-form>
  </section>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { zhiyunApi } from '../../api'
const form = ref({}), version = ref(''), secretConfigured = ref(false), loading = ref(false), saving = ref(false)
async function load() {
  loading.value = true
  try { const { data } = await zhiyunApi.admin(); form.value = data.config; version.value = data.version; secretConfigured.value = data.secretConfigured }
  finally { loading.value = false }
}
async function save() {
  saving.value = true
  try { const { data } = await zhiyunApi.save({ config: form.value, version: version.value }); version.value = data.version; ElMessage.success('智云配置已保存') }
  finally { saving.value = false }
}
onMounted(load)
</script>
<style scoped>
.zhiyun-settings{max-width:1080px;padding:20px;border:1px solid var(--ui-border);border-radius:12px;background:var(--ui-surface)}
header{display:flex;align-items:center;gap:16px;margin-bottom:16px}header>div{flex:1}h3{margin:0 0 6px;font-size:16px}header p,.hint{font-size:12px;color:var(--ui-muted);line-height:1.6;margin:5px 0 0}
.settings-form{margin-top:20px}.settings-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px}.full{grid-column:1/-1}.save-hint{margin-left:12px}
@media(max-width:768px){.settings-grid{grid-template-columns:1fr}.save-hint{display:block;margin:8px 0 0}}
</style>
