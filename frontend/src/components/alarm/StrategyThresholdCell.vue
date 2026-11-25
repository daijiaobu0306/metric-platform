<template>
  <div class="threshold-cell">
    <span v-if="singleRecord && rules.length" class="raw-default-note">默认规则 · 个性化规则请点击查看</span>
    <template v-if="rules.length">
      <el-tooltip v-for="(rule, index) in rules.slice(0, 2)" :key="`${rule.strategyId}-${rule.level}-${index}`" placement="top"
                  :content="`${rule.strategyName} · ${singleRecord ? `连续 ${rule.consecutiveTimes || 1} 条新记录异常` : `连续 ${rule.consecutiveTimes || 1} 次有效更新异常`} · ${rule.enabled ? '启用' : '停用'}，点击编辑`">
        <button :disabled="!editable" class="threshold-line" :class="{ inactive: !rule.enabled }" @click="$emit('edit', rule.strategyId)">
          <span class="level-dot" :class="`level-${rule.level}`" />
          <span class="level-label">{{ levelName(rule.level) }}</span><span class="threshold-value">{{ rule.condition || '条件未配置' }} {{ rule.threshold ?? '未配置' }}<span v-if="rule.threshold != null && unit" class="unit"> {{ unit }}</span></span>
          <span v-if="!rule.enabled" class="off">停用</span>
        </button>
      </el-tooltip>
      <el-button v-if="rules.length > 2" link type="primary" size="small" @click="$emit('edit', null)">共 {{ rules.length }} 条规则，查看全部</el-button>
    </template>
    <el-button v-else link :type="editable ? 'primary' : 'info'" :disabled="!editable" @click="$emit('edit', null)">未配置</el-button>
  </div>
</template>
<script setup>
import { ruleLevels } from '../../utils/strategyRules'
const levelName = value => ruleLevels.find(level => level.value === value)?.label || '未定级'
defineProps({ rules: { type: Array, default: () => [] }, unit: { type: String, default: '' }, editable: Boolean, singleRecord: Boolean })
defineEmits(['edit'])
</script>
<style scoped>
.raw-default-note { color:var(--ui-muted); font-size:11px; }
.threshold-cell { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 3px 0; }
.threshold-line { display: flex; align-items: baseline; gap: 5px; border: 0; background: transparent; padding: 1px 0; text-align: left; color: var(--ui-primary-strong); font: inherit; font-size: 12px; cursor: pointer; line-height: 1.6; }
.threshold-line:hover .threshold-value { text-decoration: underline; }
.level-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ui-muted); flex-shrink: 0; align-self: center; }
.level-3 { background: var(--ui-warning); }.level-4 { background: var(--ui-danger); }
.unit { color: var(--ui-muted); }.off { color: #a8abb2; font-size: 11px; white-space: nowrap; }
.inactive { color: var(--ui-muted); }
</style>
