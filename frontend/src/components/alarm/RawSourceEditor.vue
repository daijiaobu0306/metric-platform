<template>
  <section v-if="active" class="raw-source" v-loading="loading">
    <div class="raw-source__head"><strong>原始数据范围</strong><el-tag size="small">统一 ms</el-tag></div>
    <el-alert title="按入库数值直接比较，不做秒到毫秒换算。保存后只检查新入库记录，已有记录不补发。" type="info" :closable="false" show-icon />
    <el-form label-width="90px" style="margin-top:16px">
      <el-row :gutter="16">
        <el-col :span="12"><el-form-item label="业务分类"><el-select v-model="group" placeholder="选择业务分类" @change="resetSource"><el-option v-for="item in groups" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="数据来源"><el-select v-model="model.source" placeholder="选择数据表" @change="resetFields"><el-option v-for="item in sources.filter(item => item.group === group)" :key="item.key" :label="`${item.name}（${item.key}）`" :value="item.key" /></el-select></el-form-item></el-col>
      </el-row>
      <el-form-item label="检查值"><el-select v-model="model.valueField" placeholder="选择检查字段"><el-option v-for="field in current?.values || []" :key="field" :label="label(field)" :value="field" /></el-select><span class="raw-source__muted">阈值单位：ms；空值和非数值不触发</span></el-form-item>
      <el-form-item label="适用范围"><div class="raw-filter"><el-select v-model="model.filter.field" placeholder="全部记录" clearable><el-option v-for="field in current?.filters || []" :key="field" :label="label(field)" :value="field" /></el-select><el-select v-model="model.filter.mode"><el-option label="精确匹配" value="exact" /><el-option label="包含" value="contains" /><el-option label="正则表达式" value="regex" /></el-select><el-input v-model="model.filter.value" :disabled="!model.filter.field" placeholder="留空表示全部记录" maxlength="200" />
        <el-tooltip placement="top" effect="light" popper-class="raw-match-help">
          <template #content>
            <div><strong>精确匹配：</strong>填“客户查询”，只匹配同名业务；留空表示全部记录。</div>
            <div><strong>包含：</strong>填“查询”，匹配名称中含“查询”的业务；留空表示全部记录。</div>
            <div><strong>正则表达式：</strong>填“查询|打印”，匹配含任一词的业务；留空表示全部记录（无需填 *）。</div>
          </template>
          <button type="button" class="raw-filter__help" aria-label="查看匹配方式说明"><el-icon><QuestionFilled /></el-icon></button>
        </el-tooltip>
      </div></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
  <slot />
  <section v-if="active" class="raw-source raw-source--overrides">
    <div class="raw-source__head"><strong>个性化阈值</strong><el-button link type="primary" :disabled="!current || model.overrides.length >= 30" @click="addOverride">＋ 添加个性化阈值</el-button></div>
    <p class="raw-source__muted">例如为打印 URL 配置更高阈值。先匹配精确范围，再按包含、正则的顺序匹配；同类按排列顺序，首条命中覆盖默认规则。</p>
    <div v-for="(item,index) in model.overrides" :key="index" :ref="element => { overrideCards[index] = element }" class="raw-override">
      <div class="raw-source__head"><strong>个性化配置 {{ index + 1 }}</strong><div><el-button link :disabled="index === 0" @click="move(index,-1)">上移</el-button><el-button link :disabled="index === model.overrides.length - 1" @click="move(index,1)">下移</el-button><el-button link type="danger" @click="model.overrides.splice(index,1)">删除</el-button></div></div>
      <div class="raw-filter"><el-select v-model="item.filter.field" placeholder="匹配字段"><el-option v-for="field in current?.filters || []" :key="field" :label="label(field)" :value="field" /></el-select><el-select v-model="item.filter.mode"><el-option label="精确匹配" value="exact" /><el-option label="包含" value="contains" /><el-option label="正则表达式" value="regex" /></el-select><el-input v-model="item.filter.value" placeholder="例如 ^/api/print(?:/|$)" maxlength="200" /></div>
      <div class="raw-override__rules">
        <div v-for="kind in ['alarm','gongdan']" :key="kind" :class="['raw-kind', `raw-kind--${kind}`]">
          <div class="raw-source__head"><strong>{{ kind === 'alarm' ? '个性化告警规则' : '个性化工单规则' }}</strong><el-tag size="small" :type="enabled(kind) ? 'success' : 'info'">{{ enabled(kind) ? '已开启' : '未开启' }}</el-tag></div>
          <StrategyRuleList v-if="enabled(kind)" v-model="item[`${kind}Rules`]" subject="原始值" unit="ms" :raw-records="true" :kind-label="kind === 'alarm' ? '告警' : '工单'" />
          <div v-else class="raw-source__muted">当前策略尚未开启{{ kind === 'alarm' ? '告警' : '工单' }}。<el-button link type="primary" @click="emit('enable-kind',kind)">启用{{ kind === 'alarm' ? '告警' : '工单' }}并配置</el-button></div>
        </div>
      </div>
    </div>
    <div v-if="model.overrides.length" class="raw-filter" style="margin-top:12px"><el-input v-model="sample" placeholder="输入URL等内容，试匹配个性化规则" /><el-button :loading="matching" @click="tryMatch">试匹配</el-button></div>
    <p v-if="matchResult" class="raw-source__muted">{{ matchResult }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { configApi } from '../../api'
import StrategyRuleList from './StrategyRuleList.vue'
const model = defineModel({ type: Object, required: true })
const props = defineProps({ active: { type:Boolean, default:true }, alarmEnabled:Number, gongdanEnabled:Number })
const emit = defineEmits(['enable-kind'])
const overrideCards = ref([])
const generalRule = () => ({ level:2,condition:null,threshold:null,consecutiveTimes:1 })
const enabled = kind => (kind === 'alarm' ? props.alarmEnabled : props.gongdanEnabled) === 1
function prepareOverrides () {
  for(const item of model.value.overrides) for(const kind of ['alarm','gongdan']) {
    if(enabled(kind) && !item[`${kind}Rules`]?.length) item[`${kind}Rules`] = [generalRule()]
  }
}
watch(() => [props.alarmEnabled,props.gongdanEnabled,model.value],prepareOverrides,{ immediate:true })
const sources = ref([]), group = ref(''), loading = ref(false), error = ref(''), sample = ref(''), matchResult = ref(''), matching = ref(false)
const groups = computed(() => [...new Set(sources.value.map(item => item.group))])
const current = computed(() => sources.value.find(item => item.key === model.value.source))
watch(current, value => { if (value) group.value = value.group })
watch(() => model.value.filter.field,field => { if(!field) model.value.filter.value = '' })
const names = { avg_duration_sec:'平均查询耗时',avg_response_sec:'平均响应耗时',interval_sec:'操作间隔耗时',full_duration_sec:'全程办理耗时',full_interval_sec:'全程操作间隔',full_service_sec:'全程服务耗时',metric_value:'原始数值',business_name:'业务名称',step_name:'步骤名称',step_type:'步骤类型',sub_category:'业务子类',object_url:'对象 URL',interface_url:'接口 URL',metric_name:'指标名称',metric_code:'指标编码',object_name:'对象名称',metric_attr:'指标属性',app_name:'应用名称',error_name:'异常名称',error_message:'错误信息',return_code:'返回码' }
const label = field => `${names[field] || field}（${field}）`
function resetSource () { model.value.source = ''; resetFields() }
function resetFields () { model.value.valueField = current.value?.values[0] || ''; model.value.filter = { field:'',mode:'exact',value:'' }; model.value.overrides = [] }
async function addOverride () {
  model.value.overrides.push({ filter:{ field:current.value.filters[0],mode:'regex',value:'' },alarmRules:[generalRule()],gongdanRules:[generalRule()] })
  await nextTick()
  const card = overrideCards.value[model.value.overrides.length - 1]
  card?.scrollIntoView({ block:'start',behavior:'smooth' })
}
function move (index,delta) { const items=model.value.overrides; [items[index],items[index+delta]]=[items[index+delta],items[index]] }
async function tryMatch () {
  matching.value=true; matchResult.value=''
  try { const res=await configApi.rawStrategyMatch({ definition:model.value, sample:sample.value }); matchResult.value=res.data }
  catch(e) { matchResult.value=e.message || '匹配失败，请检查表达式' } finally { matching.value=false }
}
watch(() => props.active,async active => { if(!active || sources.value.length || loading.value) return; loading.value=true; try { sources.value=(await configApi.rawStrategySources()).data || [] } catch(e) { error.value='数据来源加载失败，请关闭后重试' } finally { loading.value=false } },{ immediate:true })
</script>
<style scoped>
.raw-source { border:1px solid var(--ui-border); border-radius:10px; padding:18px; margin-bottom:18px; background:var(--ui-panel); }
.raw-source__head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px; }
.raw-source__muted { color:var(--ui-muted); font-size:12px; line-height:1.7; margin:8px 0; }
.raw-filter { display:flex; gap:8px; width:100%; flex-wrap:wrap; }
/* 提示浮层挂到 body，使用独立类名与公共主题变量。 */
:global(.raw-match-help.el-popper) { background:var(--ui-surface); color:var(--ui-text); border:1px solid var(--ui-primary-border); border-radius:8px; padding:12px 16px; max-width:calc(100vw - 32px); font-size:12px; font-weight:400; line-height:1.9; box-shadow:0 6px 20px #0f172a12; }
:global(.raw-match-help.el-popper .el-popper__arrow::before) { background:var(--ui-surface); border-color:var(--ui-primary-border); }
:global(.raw-match-help strong) { color:var(--ui-primary-strong); font-weight:500; }
.raw-filter__help { display:inline-flex; align-items:center; justify-content:center; align-self:center; flex-shrink:0; width:24px; height:28px; padding:0; border:0; background:transparent; color:var(--ui-muted); cursor:help; }
.raw-filter__help:hover { color:var(--ui-primary); }
.raw-filter__help:focus-visible { outline:2px solid var(--ui-primary); border-radius:4px; }
.raw-filter > .el-select { width:160px; }.raw-filter > .el-input { flex:1; min-width:180px; }
.raw-override__rules { display:grid; gap:14px; margin-top:16px; }
.raw-kind { border-left:3px solid var(--ui-primary); background:var(--ui-page); border-radius:6px; padding:14px; }
.raw-kind--gongdan { border-left-color:var(--ui-warning); }
.raw-source--overrides { margin-top:20px; }
.raw-override { border:1px solid var(--ui-border-soft); border-radius:8px; padding:14px; margin-top:12px; }
</style>
