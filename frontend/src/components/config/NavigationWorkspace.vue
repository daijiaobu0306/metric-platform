<template>
  <section class="navigation-workspace workspace-theme">
    <el-skeleton v-if="!ready && route.path.startsWith('/external/')" :rows="5" animated style="padding:24px" />
    <template v-else>
      <header v-if="group && !group.builtin" class="navigation-workspace__heading"><h1>{{ group.name }}</h1><p>选择功能页签，访问已接入的应用页面。</p></header>
      <div v-if="group && tabs.length" class="navigation-workspace__tabs"><el-tabs :model-value="active" class="workspace-tabs management-tabs" @tab-change="select"><el-tab-pane v-for="item in tabs" :key="item.path" :name="item.path"><template #label><el-icon><component :is="item.icon||'Link'" /></el-icon><span>{{ item.name }}</span><el-icon v-if="item.mode==='tab'" class="external-mark"><TopRight /></el-icon></template></el-tab-pane></el-tabs></div>
      <el-empty v-if="folder && !tabs.length" description="暂无可访问的子页面，请在菜单管理中为此目录添加页面入口。" />
      <slot v-else-if="!folder" />
    </template>
  </section>
</template>
<script setup>
import {computed,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {externalMenuApi} from '../../api'
const props=defineProps({ready:{type:Boolean,default:true},menus:{type:Array,default:()=>[]},builtins:{type:Array,default:()=>[]}})
const route=useRoute(),router=useRouter()
const selected=computed(()=>props.menus.find(m=>`/external/${m.id}`===route.path))
const folder=computed(()=>!!selected.value?.folder)
const group=computed(()=>{
 const item=selected.value
 const parent=item?.folder?item.id:item?.parent
 if(parent){const builtin=props.builtins.find(b=>b.path===parent);if(builtin)return {...builtin,id:parent,builtin:true};return props.menus.find(m=>m.id===parent&&m.folder)||null}
 const builtin=props.builtins.find(b=>b.path===route.path)
 return builtin&&props.menus.some(m=>m.parent===builtin.path)?{...builtin,id:builtin.path,builtin:true}:null
})
const tabs=computed(()=>{
 if(!group.value)return []
 const children=props.menus.filter(m=>m.parent===group.value.id&&!m.folder).map(m=>({...m,path:`/external/${m.id}`}))
 return group.value.builtin?[{path:group.value.id,name:`${group.value.name}首页`,icon:group.value.icon},...children]:children
})
const active=computed(()=>route.path)
watch(()=>[folder.value,tabs.value.map(t=>t.path).join('|')],()=>{if(folder.value&&tabs.value.length)router.replace(tabs.value[0].path)},{immediate:true})
async function select(path){
 const tab=tabs.value.find(t=>t.path===path)
 if(tab?.mode==='tab'){
  const target=window.open('about:blank','_blank');if(!target){ElMessage.warning('请允许本站弹窗后重试');return}
  target.opener=null;const policy=target.document.createElement('meta');policy.name='referrer';policy.content='no-referrer';target.document.head.appendChild(policy)
  try{const r=await externalMenuApi.launch(tab.id);target.location.replace(r.data.url)}catch(e){target.close()}
 }else if(path!==route.path)router.push(path)
}
</script>
<style scoped>
.navigation-workspace__heading { padding:12px 20px 10px; background:var(--ui-surface); border-bottom:1px solid var(--ui-border); }
.navigation-workspace__heading h1 { margin:0; font-size:18px; line-height:1.4; color:var(--ui-heading); }
.navigation-workspace__heading p { margin:3px 0 0; font-size:12px; line-height:1.5; color:var(--ui-muted); }
.navigation-workspace__tabs{padding:8px 20px 0;background:var(--ui-page)}
.navigation-workspace__tabs :deep(.el-tabs__header) { padding:0 !important; margin:0 !important; }
.navigation-workspace__tabs :deep(.el-tabs__nav-wrap) { padding-bottom:6px; }
.navigation-workspace__tabs :deep(.el-tabs__item) { height:32px !important; padding:0 12px !important; font-size:12px; }
@media(max-width:768px) { .navigation-workspace__heading { padding:10px 14px; } .navigation-workspace__tabs { padding:6px 14px 0; } }
.navigation-workspace__tabs :deep(.el-tabs__item){gap:8px}.navigation-workspace__tabs :deep(.el-tabs__content){display:none}.external-mark{font-size:12px;color:var(--ui-muted)}
</style>
