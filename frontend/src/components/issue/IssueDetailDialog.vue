<template>
      <el-dialog class="workspace-theme" v-model="visible" title="问题详情" :width="width">
      <div class="detail-container" v-if="detailData">
        <dl class="workspace-detail-grid">
          <div><dt>问题类型</dt><dd>{{ detailData.issueType || '-' }}</dd></div>
          <div><dt>问题属性</dt><dd>{{ detailData.issueAttribute || '-' }}</dd></div>
          <div><dt>问题来源</dt><dd>{{ detailData.issueSource || '-' }}</dd></div>
          <div><dt>发现者</dt><dd>{{ detailData.discoverer || '-' }}</dd></div>
          <div><dt>责任人</dt><dd>{{ detailData.responsiblePerson || '-' }}</dd></div>
          <div><dt>解决状态</dt><dd>
            <el-tag :type="detailData.resolveStatus === '已解决' ? 'success' : 'danger'">
              {{ detailData.resolveStatus || '-' }}
            </el-tag>
          </dd></div>
          <div><dt>系统</dt><dd>{{ detailData.systemName || '-' }}</dd></div>
          <div><dt>指标大类</dt><dd>{{ detailData.categoryName || '-' }}</dd></div>
          <div><dt>指标小类</dt><dd>{{ detailData.subCategoryName || '-' }}</dd></div>
          <div><dt>指标</dt><dd>{{ detailData.metricName || '-' }}</dd></div>
          <div><dt>预计解决时间</dt><dd>{{ formatDateTime(detailData.planResolveTime) || '-' }}</dd></div>
          <div><dt>登记时间</dt><dd>{{ formatDateTime(detailData.registerTime) || '-' }}</dd></div>
          <div><dt>修改时间</dt><dd>{{ formatDateTime(detailData.updateTime) || '-' }}</dd></div>
          <div class="field-wide"><dt>问题描述</dt><dd>{{ detailData.issueDescription || '-' }}</dd></div>
          <div class="field-wide"><dt>核查原因</dt><dd>{{ detailData.checkReason || '-' }}</dd></div>
          <div class="field-wide"><dt>后评估</dt><dd>{{ detailData.postEvaluation || '-' }}</dd></div>
          <div><dt>是否关注</dt><dd>
            <el-tag :type="detailData.tagStatus === 1 ? 'success' : 'info'" size="small">
              {{ detailData.tagStatus === 1 ? '关注' : '未关注' }}
            </el-tag>
          </dd></div>
          <div><dt>附件</dt><dd>
            <el-link v-if="detailData.attachmentName" type="primary" @click="emit('download', detailData)">
              {{ detailData.attachmentName }}
            </el-link>
            <span v-else>无</span>
          </dd></div>
        </dl>
      </div>
      <template #footer>
        <el-button type="primary" @click="emit('edit')">
          <el-icon><Edit /></el-icon>编辑
        </el-button>
        <el-button @click="visible = false">关闭</el-button>
      </template>
    </el-dialog>
</template>
<script setup>
import { formatDateTime } from '@/utils'
const visible=defineModel({type:Boolean,default:false})
defineProps({width:[Number,String],detailData:Object})
const emit=defineEmits(['edit','download'])
</script>
