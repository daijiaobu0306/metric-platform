<template>
    <el-dialog
      v-model="visible"
      width="760px"
      class="workspace-theme metric-detail-dialog metric-config-dialog"
      destroy-on-close
    >
      <template #header>
        <div class="md-head">
          <div class="md-head__title">
            <span class="md-head__name">{{ detailRow.metricName || '指标详情' }}</span>
            <WorkspaceStatusBadge v-if="detailRow.status === 1" tone="success" pulse>已启用</WorkspaceStatusBadge>
            <WorkspaceStatusBadge v-else tone="muted">已停用</WorkspaceStatusBadge>
          </div>
          <div class="md-head__meta">
            <span class="md-head__code">{{ detailRow.metricCode || '-' }}</span>
            <span v-if="detailRow.unit" class="md-head__unit">单位：{{ detailRow.unit }}</span>
          </div>
        </div>
      </template>

      <div class="md-body">
        <!-- 归属路径 -->
        <div class="md-path">
          <el-icon><Grid /></el-icon>
          <span>{{ detailRow.systemName || '-' }}</span>
          <span class="md-path__sep">›</span>
          <span>{{ detailRow.categoryName || '-' }}</span>
          <span class="md-path__sep">›</span>
          <span class="md-path__leaf">{{ detailRow.subCategoryName || '未归属小类' }}</span>
        </div>

        <!-- 指标定义 -->
        <div class="md-block">
          <div class="md-block__label">指标定义</div>
          <div :class="['md-block__text', { 'is-empty': !detailRow.metricDefinition }]">
            {{ detailRow.metricDefinition || '未填写' }}
          </div>
        </div>

        <!-- 统计口径 -->
        <div class="md-block">
          <div class="md-block__label">统计口径</div>
          <div :class="['md-block__text', { 'is-empty': !detailRow.statisticsCaliber }]">
            {{ detailRow.statisticsCaliber || '未填写' }}
          </div>
        </div>

        <!-- 责任与属性 -->
        <div class="md-grid">
          <div class="md-cell">
            <div class="md-cell__label">面向人员</div>
            <div class="md-cell__value">{{ detailRow.responsiblePerson || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">部室</div>
            <div class="md-cell__value">{{ detailRow.department || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">填报人</div>
            <div class="md-cell__value">{{ detailRow.reporter || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">指标类型</div>
            <div class="md-cell__value">{{ detailRow.metricType || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">数据来源</div>
            <div class="md-cell__value">{{ detailRow.dataSource || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">采集频率</div>
            <div class="md-cell__value">{{ detailRow.collectFrequency || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">同步方式</div>
            <div class="md-cell__value">{{ detailRow.dataSyncMode || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">采集状态</div>
            <div class="md-cell__value">{{ detailRow.collectStatus || '—' }}</div>
          </div>
          <div class="md-cell">
            <div class="md-cell__label">趋势方向</div>
            <div class="md-cell__value">{{ detailRow.trendDirection || '—' }}</div>
          </div>
        </div>

        <div class="md-foot">
          创建于 {{ formatDateTime(detailRow.createTime) }} · 最后更新 {{ formatDateTime(detailRow.updateTime) }}
        </div>
      </div>

      <template #footer>
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" @click="emit('strategy')">设置策略阈值</el-button>
      </template>
    </el-dialog>
</template>
<script setup>
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { formatDateTime } from '@/utils'
const visible=defineModel({type:Boolean,default:false})
defineProps({detailRow:{type:Object,required:true}})
const emit=defineEmits(['strategy'])
</script>
<style scoped>
/* ===== 指标详情弹窗 ===== */
.md-head__title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.md-head__name {
  font-size: 17px;
  font-weight: 600;
  color: #1f2d3d;
  line-height: 1.4;
}
.md-head__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ui-muted);
}
.md-head__code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  background: #f4f6f9;
  border-radius: 4px;
  padding: 1px 7px;
  color: var(--ui-text);
}
.md-head__unit {
  color: var(--ui-primary);
  background: var(--ui-primary-soft);
  border-radius: 4px;
  padding: 1px 7px;
}

.md-body { padding: 0 2px; }

.md-path {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ui-text);
  background: #f7f9fc;
  border-radius: 8px;
  padding: 9px 12px;
  margin-bottom: 16px;
}
.md-path .el-icon { color: var(--ui-muted); }
.md-path__sep { color: #c0c4cc; }
.md-path__leaf { color: var(--ui-heading); font-weight: 500; }

.md-block { margin-bottom: 16px; }
.md-block__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ui-muted);
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}
.md-block__text {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ui-heading);
  background: #fbfcfe;
  border: 1px solid #eef1f6;
  border-left: 3px solid #a3c8ff;
  border-radius: 6px;
  padding: 10px 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
.md-block__text.is-empty {
  color: #c0c4cc;
  font-style: normal;
  background: #fcfcfd;
  border-left-color: var(--ui-border);
}

.md-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #eef1f6;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  overflow: hidden;
}
@media (max-width: 640px) {
  .md-grid { grid-template-columns: repeat(2, 1fr); }
}
.md-cell {
  background: var(--ui-surface);
  padding: 10px 12px;
}
.md-cell__label {
  font-size: 12px;
  color: #a0a6b0;
  margin-bottom: 4px;
}
.md-cell__value {
  font-size: 13.5px;
  color: var(--ui-heading);
  font-weight: 500;
  word-break: break-word;
}

.md-foot {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px dashed var(--ui-border-soft);
  font-size: 12px;
  color: var(--ui-subtle);
  text-align: right;
}


</style>
