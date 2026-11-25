<template>
        <el-card>
          <template #header>
            <div class="card-header">
              <WorkspaceSectionHeading title="平台信息" icon="Monitor" description="平台名称、描述与标识" />
              <el-button type="primary" @click="emit('save')" :loading="infoLoading">
                <el-icon><Edit /></el-icon>保存
              </el-button>
            </div>
          </template>

          <div class="platform-info">
            <div class="platform-left">
              <div class="section-label">平台标识</div>
              <div class="logo-area">
                <div class="logo-preview" role="button" tabindex="0" aria-label="预览平台 Logo" @keydown.enter="emit('preview-logo')" @keydown.space.prevent="emit('preview-logo')" @click="emit('preview-logo')">
                  <img v-if="platformConfig.logo" :src="platformConfig.logo" alt="logo" />
                  <div v-else class="logo-placeholder">
                    <el-icon :size="40"><Monitor /></el-icon>
                    <span>暂无 Logo</span>
                  </div>
                  <div class="logo-mask">
                    <el-icon><ZoomIn /></el-icon>
                    <span>查看大图</span>
                  </div>
                </div>
                <div class="logo-actions">
                  <el-upload
                    :show-file-list="false"
                    :before-upload="beforeLogoUpload"
                    :http-request="handleLogoUpload"
                    accept="image/png,image/jpeg"
                  >
                    <el-button type="primary" :loading="logoUploading">
                      <el-icon><Upload /></el-icon>{{ platformConfig.logo ? '更换 Logo' : '上传 Logo' }}
                    </el-button>
                  </el-upload>
                  <div class="logo-hint">支持 PNG、JPG，建议 200×60，最大 2MB</div>
                </div>
              </div>

              <div class="history-section" v-if="platformConfig.logoHistory.length > 0">
                <div class="history-label">最近使用</div>
                <div class="history-list">
                  <div
                    v-for="(logo, index) in platformConfig.logoHistory"
                    :key="index"
                    class="history-item"
                    :class="{ active: platformConfig.logo === logo }"
                    @click="emit('select-logo', logo)"
                  >
                    <img :src="logo" alt="logo" />
                    <el-icon class="delete-btn" @click.stop="emit('delete-logo', logo)"><Close /></el-icon>
                  </div>
                </div>
              </div>
            </div>

            <div class="platform-right">
              <div class="section-label">基本信息</div>
              <el-form :model="platformConfig" label-width="90px" class="info-form">
                <el-form-item label="平台名称">
                  <el-input v-model="platformConfig.platformName" placeholder="请输入平台名称" maxlength="50" />
                </el-form-item>
                <el-form-item label="平台描述">
                  <el-input
                    v-model="platformConfig.description"
                    type="textarea"
                    :rows="3"
                    placeholder="请输入平台描述"
                    maxlength="200"
                    show-word-limit
                  />
                </el-form-item>
              </el-form>
            </div>
          </div>
        </el-card>
</template>
<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
const platformConfig=defineModel('config',{type:Object,required:true})
defineProps({infoLoading:Boolean,logoUploading:Boolean,beforeLogoUpload:{type:Function,required:true},handleLogoUpload:{type:Function,required:true}})
const emit=defineEmits(['save','preview-logo','select-logo','delete-logo'])
</script>
