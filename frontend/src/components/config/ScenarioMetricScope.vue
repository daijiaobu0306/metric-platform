<template>
  <div class="metric-scope">
    <section v-for="category in groups" :key="category.id" class="scope-category">
      <h4>{{ category.name }}</h4>
      <div v-for="group in category.groups" :key="group.name" class="scope-group">
        <div class="scope-group-title">{{ group.name }} <span>{{ group.metrics.length }} 项</span></div>
        <ul>
          <li v-for="metric in group.metrics" :key="metric.id">
            <span>{{ metric.metricName }}</span>
            <small v-if="metric.status !== 1">{{ metric.missing ? '已删除' : '已停用' }}</small>
            <el-button v-if="removable" link type="danger" size="small"
              :aria-label="'移除 ' + metric.metricName" @click="$emit('remove', metric.id)">移除</el-button>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<script setup>
defineProps({ groups: { type: Array, required: true }, removable: Boolean })
defineEmits(['remove'])
</script>

<style scoped>
.metric-scope { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); gap: 10px; }
.scope-category {
  min-width: 0; padding: 10px 12px; border: 1px solid var(--el-border-color-light);
  border-radius: 8px; background: var(--el-bg-color);
}
.scope-category h4 { margin: 0; font-size: 13px; }
.scope-group { margin-top: 8px; padding-left: 9px; border-left: 2px solid var(--el-border-color-light); }
.scope-group-title { font-size: 12px; color: var(--el-text-color-secondary); }
.scope-group-title span { margin-left: 4px; font-size: 11px; }
.scope-group ul { list-style: none; padding: 0; margin: 3px 0 0; }
.scope-group li { display: flex; align-items: baseline; gap: 8px; padding: 3px 0; font-size: 12px; }
.scope-group li span { min-width: 0; overflow-wrap: anywhere; }
.scope-group li small { color: var(--el-color-warning-dark-2); white-space: nowrap; }
.scope-group li .el-button { margin-left: auto; }
</style>
