<template>
        <el-card class="collection-storage-panel">
          <template #header>
            <div class="card-header">
              <WorkspaceSectionHeading title="采集配置" icon="FolderOpened" description="导入目录与通道文件存储" />
              <el-button type="primary" @click="emit('save')" :loading="uploadPathLoading">
                <el-icon><Edit /></el-icon>保存
              </el-button>
            </div>
          </template>

          <el-alert
            type="info"
            show-icon
            :closable="false"
            title="导入文件根目录（upload.base_path）"
            description="相对路径将拼接在此根目录下；绝对路径保持原值。保存时自动准备已启用采集通道的扫描及归档目录，停用未接入真实解析的旧通道。已有文件不会自动迁移。"
            style="margin-bottom: 16px;"
          />

          <el-form :model="uploadPathForm" label-width="120px" class="info-form">
            <el-form-item label="根目录">
              <el-input
                v-model="uploadPathForm.basePath"
                placeholder="例如 /Users/xx/metric_code/files（容器默认 /app/files）"
              />
              <div class="field-hint">
                当前生效值：<code>{{ currentUploadPath || '(使用 application.yml 默认值)' }}</code>
                <span v-if="uploadPathConfigId" style="margin-left: 8px;">
                  （已自定义）
                </span>
              </div>
            </el-form-item>
          </el-form>
          <el-alert v-if="directoryPreparation" type="success" :closable="false" show-icon style="margin-top: 12px;">
            <template #title>目录准备完成：新建 {{ directoryPreparation.created.length }} 个，已有 {{ directoryPreparation.existing.length }} 个</template>
            <p>已检查 {{ directoryPreparation.channelCount }} 个采集通道；已有文件保持原样。</p>
            <p v-if="directoryPreparation.disabledLegacy?.length">已停用 {{ directoryPreparation.disabledLegacy.length }} 个未接入真实解析的旧通道。</p>
            <div v-for="path in directoryPreparation.created" :key="path">新建：{{ path }}</div>
            <div v-for="path in directoryPreparation.existing" :key="path">已有：{{ path }}</div>
          </el-alert>
        </el-card>
</template>
<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
const uploadPathForm=defineModel('form',{type:Object,required:true})
defineProps({uploadPathLoading:Boolean,currentUploadPath:String,uploadPathConfigId:[Number,String],directoryPreparation:Object})
const emit=defineEmits(['save'])
</script>
