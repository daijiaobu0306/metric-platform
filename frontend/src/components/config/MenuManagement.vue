<template>
  <section class="menu-management" v-loading="loading">
    <div class="menu-toolbar"><div><h3>菜单管理</h3><p>左侧只显示一级导航，二级页面在右侧横向页签中切换。</p></div><el-button @click="load">刷新</el-button><el-button type="primary" @click="add">新增菜单</el-button></div>
    <el-alert type="info" :closable="false" title="外部服务独立部署，平台只负责导航与登录接入。内嵌页面需由对方允许本站嵌入；配置 token 并不能通用替代外部系统登录。" />
    <el-table :data="items" row-key="id" style="margin-top:16px"><el-table-column label="菜单" min-width="160"><template #default="{row}"><el-icon><component :is="row.icon" /></el-icon> {{ row.name }}</template></el-table-column><el-table-column prop="id" label="编码"/><el-table-column label="描述" min-width="220" show-overflow-tooltip><template #default="{row}">{{ row.description || '—' }}</template></el-table-column><el-table-column label="上级"><template #default="{row}">{{ parentName(row.parent) }}</template></el-table-column><el-table-column label="方式"><template #default="{row}">{{ row.folder ? '目录' : row.mode === 'embed' ? '内嵌页面' : '新标签页' }}</template></el-table-column><el-table-column label="状态" width="80"><template #default="{row}"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column prop="sort" label="排序" width="70"/><el-table-column label="操作" width="140"><template #default="{row}"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column></el-table>
    <el-dialog v-model="show" title="菜单配置" width="760px" class="workspace-theme" append-to-body :close-on-click-modal="false">
      <el-form label-width="110px"><el-row :gutter="16"><el-col :span="12"><el-form-item label="菜单名称"><el-input v-model="form.name" maxlength="40" /></el-form-item></el-col><el-col :span="12"><el-form-item label="唯一编码"><el-input v-model="form.id" :disabled="!!editing" placeholder="如 tools-report" maxlength="40" /></el-form-item></el-col></el-row>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明菜单用途、接入系统或适用场景，方便后续维护（选填）" /></el-form-item>
        <el-form-item label="上级导航"><el-select v-model="form.parent" style="width:100%" clearable placeholder="一级菜单"><el-option v-for="p in parents" :key="p.id" :label="p.name" :value="p.id" /></el-select></el-form-item>
        <el-form-item label="菜单类型"><el-radio-group v-model="form.folder"><el-radio :value="false">页面入口</el-radio><el-radio :value="true" :disabled="!!form.parent">一级目录（右侧页签）</el-radio></el-radio-group></el-form-item>
        <el-alert v-if="form.folder" title="一级目录保存启用后即显示在左侧，无子页面也保留；顶部标题使用菜单名称，子页面在右侧横向页签中展示。" type="info" :closable="false" />
        <el-form-item label="菜单图标"><div class="menu-icons"><button v-for="icon in icons" :key="icon" type="button" :class="{selected:form.icon===icon}" :title="icon" :aria-label="icon" @click="form.icon=icon"><el-icon><component :is="icon" /></el-icon></button></div></el-form-item>
        <template v-if="!form.folder"><el-form-item label="页面地址"><el-input v-model="form.url" placeholder="https://service.example.com/page" /></el-form-item><el-form-item label="打开方式"><el-radio-group v-model="form.mode"><el-radio value="embed">内嵌平台</el-radio><el-radio value="tab">新标签页</el-radio></el-radio-group></el-form-item>
        <el-form-item label="鉴权方式"><el-select v-model="form.auth"><el-option label="不附加凭据 / 对方自行登录" value="none"/><el-option label="短期签名凭证（双方接入）" value="signed"/><el-option label="外部令牌参数（对方已支持）" value="env-query"/></el-select></el-form-item>
        <template v-if="form.auth!=='none'"><el-form-item label="凭据变量名"><el-input v-model="form.credentialEnv" placeholder="EXTERNAL_MENU_REPORT_KEY" /></el-form-item><el-form-item label="URL参数名"><el-input v-model="form.parameter" placeholder="ticket / token" /></el-form-item><el-alert :closable="false" type="warning" title="凭据值配置在后端环境变量，不填写到菜单。URL令牌会进入对方访问日志，建议HTTPS及短期签名方式；平台会话令牌不会外发。" /></template></template>
        <el-form-item label="访问范围"><el-radio-group v-model="form.everyone"><el-radio :value="true">全部登录用户</el-radio><el-radio :value="false">指定角色（未选仅管理员）</el-radio></el-radio-group></el-form-item><el-form-item v-if="!form.everyone" label="授权角色"><el-select v-model="form.roles" multiple style="width:100%"><el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" /></el-select></el-form-item>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="999" /></el-form-item></el-col><el-col :span="12"><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item></el-col></el-row>
      </el-form><template #footer><el-button @click="show=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存并更新导航</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import { ref,computed,onMounted } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { externalMenuApi } from '../../api'
const items=ref([]),roles=ref([]),version=ref(''),loading=ref(false),saving=ref(false),show=ref(false),editing=ref(''),form=ref({})
const icons=['Link','Monitor','DataBoard','Setting','Document','Tools','Connection','Grid','Histogram','Folder','Tickets','Compass','Search','Management']
const builtins=[['/observation','运营观测'],['/report','运营报表'],['/data','数据汇聚'],['/alarm','告警管理'],['/gongdan','工单管理'],['/issue','问题管理'],['/metric','指标体系配置'],['/audit','变更审计'],['/config','配置管理'],['/dashboard-view','专屏视图'],['/script','技能管理']].map(([id,name])=>({id,name}))
const parents=computed(()=>[...builtins,...items.value.filter(i=>i.folder&&!i.parent&&i.id!==editing.value)])
const parentName=id=>[...builtins,...items.value].find(i=>i.id===id)?.name||'一级菜单'
async function load(){loading.value=true;try{const r=(await externalMenuApi.admin()).data;items.value=r.items;roles.value=r.roles;version.value=r.version}finally{loading.value=false}}
function add(){editing.value='';form.value={id:'',name:'',description:'',parent:'',folder:false,icon:'Link',sort:20,enabled:true,url:'',mode:'embed',auth:'none',credentialEnv:'',parameter:'ticket',everyone:false,roles:[]};show.value=true}
function edit(row){editing.value=row.id;form.value={...JSON.parse(JSON.stringify(row)),description:row.description||''};show.value=true}
async function persist(next){saving.value=true;try{const r=await externalMenuApi.save({version:version.value,items:next});version.value=r.data.version;items.value=next;window.dispatchEvent(new Event('external-menus-changed'));return true}finally{saving.value=false}}
async function save(){if(!form.value.name||!form.value.id){ElMessage.warning('请填写名称和唯一编码');return}if(!editing.value&&items.value.some(i=>i.id===form.value.id)){ElMessage.warning('编码已存在');return}const next=items.value.filter(i=>i.id!==editing.value);next.push(JSON.parse(JSON.stringify(form.value)));if(await persist(next)){show.value=false;ElMessage.success('菜单已更新')}}
async function remove(row){if(items.value.some(i=>i.parent===row.id)){ElMessage.warning('请先删除或移走子菜单');return}try{await ElMessageBox.confirm(`删除菜单“${row.name}”？外部服务不会被删除。`,'删除菜单',{type:'warning'});await persist(items.value.filter(i=>i.id!==row.id))}catch(e){if(e!=='cancel'&&e!=='close')console.error(e)}}
onMounted(load)
</script>
<style scoped>
.menu-toolbar{display:flex;align-items:center;gap:12px;margin-bottom:16px}.menu-toolbar>div{flex:1}.menu-toolbar h3{margin:0 0 8px}.menu-toolbar p{color:var(--ui-muted);margin:0;font-size:13px}.menu-icons{display:flex;gap:8px;flex-wrap:wrap}.menu-icons button{width:42px;height:38px;border:1px solid var(--ui-border);border-radius:8px;background:var(--ui-surface);color:var(--ui-text);cursor:pointer}.menu-icons button.selected{background:var(--ui-primary-soft);border-color:var(--ui-primary);color:var(--ui-primary)}
</style>
