<template>
        <div class="config-summary">
          <span class="cs-total">已启用指标 <b>{{ configSummary.enabledTotal }}</b></span>
          <div class="cs-group">
            <span class="cs-name">告警</span>
            <button type="button"
              class="cs-chip yes"
              :class="{ on: configFilter === 'alarm-yes' }"
              title="只看已配置告警阈值的指标"
              @click="emit('toggle', 'alarm-yes')"
            >已配置 <b>{{ configSummary.alarmYes }}</b></button>
            <button type="button"
              class="cs-chip no"
              :class="{ on: configFilter === 'alarm-no' }"
              title="只看还没配置告警阈值的指标"
              @click="emit('toggle', 'alarm-no')"
            >未配置 <b>{{ configSummary.alarmNo }}</b></button>
          </div>
          <div class="cs-group">
            <span class="cs-name">工单</span>
            <button type="button"
              class="cs-chip yes"
              :class="{ on: configFilter === 'gongdan-yes' }"
              title="只看已配置工单阈值的指标"
              @click="emit('toggle', 'gongdan-yes')"
            >已配置 <b>{{ configSummary.gongdanYes }}</b></button>
            <button type="button"
              class="cs-chip no"
              :class="{ on: configFilter === 'gongdan-no' }"
              title="只看还没配置工单阈值的指标"
              @click="emit('toggle', 'gongdan-no')"
            >未配置 <b>{{ configSummary.gongdanNo }}</b></button>
          </div>
          <button v-if="configFilter" type="button" class="cs-clear" @click="emit('clear')">清除筛选</button>
        </div>
</template>
<script setup>
defineProps({configSummary:{type:Object,required:true},configFilter:String})
const emit=defineEmits(['toggle','clear'])
</script>
<style scoped>
.config-summary { display:flex; align-items:center; flex-wrap:wrap; gap:12px; padding:8px 12px; background:var(--ui-surface); border:1px solid var(--ui-border); border-radius:8px; color:var(--ui-text); font-size:12px; }
.cs-group { display:flex; align-items:center; gap:6px; } .cs-name { color:var(--ui-muted); } .cs-chip { padding:2px 8px; border-radius:10px; cursor:pointer; font-size:11px; } .cs-chip.yes { background:var(--ui-success-soft); color:var(--ui-success); } .cs-chip.no { background:var(--ui-page); color:var(--ui-muted); } .cs-chip.on { background:var(--ui-primary); color:var(--ui-on-primary); } .cs-clear { border:0; background:none; font:inherit; padding:2px 4px; color:var(--ui-primary); cursor:pointer; }
.cs-chip { border:0; font-family:inherit; line-height:inherit; } .cs-chip:focus-visible { outline:2px solid var(--ui-primary); outline-offset:2px; }
</style>
