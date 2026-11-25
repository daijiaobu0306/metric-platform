<template>
  <div class="config-page workspace-theme workspace-management">
    <WorkspacePageHeader title="配置管理" description="统一维护插件、平台信息与采集目录，管理用户角色和统一认证配置。" />
    <el-tabs v-model="activeTab" class="config-tabs workspace-tabs management-tabs">
      <!-- 插件配置 Tab -->
      <el-tab-pane label="插件配置" name="plugin"><template #label><WorkspaceTabLabel icon="Connection">插件配置</WorkspaceTabLabel></template>
        <PluginConfigPanel />
      </el-tab-pane>

      <el-tab-pane v-if="isAdmin" label="业务场景权重配置" name="metric-weight-scenarios" lazy>
        <template #label><WorkspaceTabLabel icon="Calendar">业务场景权重配置</WorkspaceTabLabel></template>
        <MetricWeightScenarios />
      </el-tab-pane>

      <!-- 专屏配置 Tab 已搬到「专屏视图」菜单下（view tab = DashboardViewConfigPanel） -->

      <el-tab-pane v-if="isAdmin" label="菜单管理" name="menus"><template #label><WorkspaceTabLabel icon="Menu">菜单管理</WorkspaceTabLabel></template><MenuManagement /></el-tab-pane>
      <el-tab-pane v-if="isAdmin" label="智云嵌入配置" name="zhiyun"><template #label><WorkspaceTabLabel icon="Connection">智云嵌入配置</WorkspaceTabLabel></template><ZhiyunSettings /></el-tab-pane>
      <!-- 平台配置 Tab -->
      <el-tab-pane label="平台配置" name="platform"><template #label><WorkspaceTabLabel icon="Monitor">平台配置</WorkspaceTabLabel></template>
        <div class="platform-config-grid"><PlatformInfoPanel v-model:config="platformConfig" :info-loading="infoLoading" :logo-uploading="logoUploading" :before-logo-upload="beforeLogoUpload" :handle-logo-upload="handleLogoUpload" @save="savePlatformInfo" @preview-logo="showLogoDialog = true" @select-logo="selectHistoryLogo" @delete-logo="deleteLogo" />

        <!-- 采集配置（导入文件根目录，运行期可改，无需重启） -->
        <CollectionStoragePanel v-model:form="uploadPathForm" :upload-path-loading="uploadPathLoading" :current-upload-path="currentUploadPath" :upload-path-config-id="uploadPathConfigId" :directory-preparation="directoryPreparation" @save="saveUploadBasePath" /></div>
      </el-tab-pane>

      <!-- 用户管理 Tab（仅管理员可见） -->
      <el-tab-pane v-if="isAdmin" label="用户管理" name="user"><template #label><WorkspaceTabLabel icon="User">用户管理</WorkspaceTabLabel></template>
        <UserManagement />
      </el-tab-pane>

      <!-- 角色管理 Tab（仅管理员可见） -->
      <el-tab-pane v-if="isAdmin" label="角色管理" name="role"><template #label><WorkspaceTabLabel icon="Lock">角色管理</WorkspaceTabLabel></template>
        <RoleManagement />
      </el-tab-pane>

      <!-- SSO 配置 Tab（仅管理员可见） -->
      <el-tab-pane v-if="isAdmin" label="SSO 配置" name="sso"><template #label><WorkspaceTabLabel icon="Key">SSO 配置</WorkspaceTabLabel></template>
        <SsoSettingsPanel v-model:expanded="activeSsoCollapse" :sso-form="ssoForm" :sso-loading="ssoLoading" :sync-triggering="syncTriggering" @save="saveSsoConfig" @sync="triggerSyncNow" />
      </el-tab-pane>
    </el-tabs>

    <div style="position: relative;">
    <el-dialog class="workspace-theme" v-model="showLogoDialog" title="Logo 预览" :width="logoDialogWidth">
      <div class="logo-preview-dialog">
        <img v-if="platformConfig.logo" :src="platformConfig.logo" alt="logo" />
        <div v-else class="no-logo">暂无 Logo</div>
      </div>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustLogoDialogWidth" :visible="showLogoDialog" />
  </div>
  </div>
</template>

<script setup>
import MetricWeightScenarios from '@/components/config/MetricWeightScenarios.vue'
import ZhiyunSettings from '@/components/config/ZhiyunSettings.vue'
import MenuManagement from '@/components/config/MenuManagement.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import SsoSettingsPanel from '@/components/config/SsoSettingsPanel.vue'
import CollectionStoragePanel from '@/components/config/CollectionStoragePanel.vue'
import PlatformInfoPanel from '@/components/config/PlatformInfoPanel.vue'
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { platformConfigApi, configApi, ssoAdminApi } from '../../api'
import { Monitor, ZoomIn, Upload, Close, Edit } from '@element-plus/icons-vue'
import PluginConfigPanel from '../plugin-config/index.vue'
// DashboardViewConfigPanel 已搬到「专屏视图」菜单下（2026-09-10）
import UserManagement from '../user/index.vue'
import RoleManagement from '../role/index.vue'
import DialogSizeControls from '@/components/common/DialogSizeControls.vue'
import { useDialogSize } from '@/utils/useDialogSize'

const { width: logoDialogWidth, adjustWidth: adjustLogoDialogWidth } = useDialogSize('config-logo-dialog', '400px')

const activeTab = ref('plugin')
const isAdmin = ref(sessionStorage.getItem('isAdmin') === 'true')

const platformConfig = ref({
  platformName: '',
  description: '',
  logo: '',
  logoHistory: []
})
const infoLoading = ref(false)
const logoUploading = ref(false)
const showLogoDialog = ref(false)

// SSO 配置（仅管理员可见）
const ssoForm = reactive({
  // 访问链接 SSO（V41）
  autoCreateUser: false,
  notExistMessage: '',
  // 统一认证客户端参数（V42，启动期绑定）
  clientId: '',
  clientSecret: '',
  issuer: '',
  authorizationUri: '',
  tokenUri: '',
  jwksUri: '',
  userInfoUri: '',
  scope: '',
  redirectUri: '',
  frontendBaseUrl: '',
  // 同步（V42）
  syncEnabled: false,
  syncFixedDelayMs: 3600000,
  syncApiBaseUrl: '',
  syncApiToken: ''
})
const ssoLoading = ref(false)
const syncTriggering = ref(false)
const activeSsoCollapse = ref(['access-link'])  // 默认展开第一组

// V42 新增的 SSO config keys（除 V41 的 2 个外）
//   readOnly=true 的 key 在 UI 中显示但不可编辑 —— 它们由 Spring Security 启动期从
//   application.yml 绑定，运行期改 system_config 不会生效；必须修改 application.yml
//   或环境变量后重启后端。这是 P5 修法 A 的核心：避免误导运维以为改 UI 就能换 IdP。
const NEW_SSO_KEYS = [
  // —— 统一认证客户端（启动期绑定，UI 只读）——
  { key: 'sso.client_id',                field: 'clientId',          isBool: false, readOnly: true  },
  { key: 'sso.client_secret',            field: 'clientSecret',      isBool: false, readOnly: true  },
  { key: 'sso.issuer',                   field: 'issuer',            isBool: false, readOnly: true  },
  { key: 'sso.authorization_uri',        field: 'authorizationUri',  isBool: false, readOnly: true  },
  { key: 'sso.token_uri',                field: 'tokenUri',          isBool: false, readOnly: true  },
  { key: 'sso.jwks_uri',                 field: 'jwksUri',           isBool: false, readOnly: true  },
  { key: 'sso.user_info_uri',            field: 'userInfoUri',       isBool: false, readOnly: true  },
  { key: 'sso.scope',                    field: 'scope',             isBool: false, readOnly: true  },
  { key: 'sso.redirect_uri',             field: 'redirectUri',       isBool: false, readOnly: true  },
  { key: 'sso.frontend_base_url',        field: 'frontendBaseUrl',   isBool: false, readOnly: true  },
  // —— 同步设置（运行期可改） ——
  { key: 'sso.sync.enabled',             field: 'syncEnabled',       isBool: true,  readOnly: false },
  { key: 'sso.sync.fixed_delay_ms',      field: 'syncFixedDelayMs',  isBool: false, isInt: true, readOnly: false },
  { key: 'sso.sync.api_base_url',        field: 'syncApiBaseUrl',    isBool: false, readOnly: false },
  { key: 'sso.sync.api_token',           field: 'syncApiToken',      isBool: false, readOnly: false }
]

const loadPlatformConfig = async () => {
  try {
    const res = await platformConfigApi.getAll()
    platformConfig.value.platformName = res.data.platform_name || ''
    platformConfig.value.description = res.data.description || ''
    platformConfig.value.logo = res.data.logo || ''

    const historyRes = await platformConfigApi.getLogoHistory()
    platformConfig.value.logoHistory = historyRes.data || []
  } catch (error) {
    console.error('加载平台配置失败:', error)
  }
}

// === 采集配置 / 文件根目录（upload.base_path） ===
// 与 SSO 同款 system_config CRUD 流程，但这个 key 是运行期热更新（SSO 客户端参数是启动期绑定）
const uploadPathForm = ref({ basePath: '' })
const uploadPathConfigId = ref(null)
const currentUploadPath = ref('')
const uploadPathLoading = ref(false)
const directoryPreparation = ref(null)

const loadUploadBasePath = async () => {
  try {
    const res = await configApi.systemByKey('upload.base_path')
    if (res?.data) {
      uploadPathForm.value.basePath = res.data.configValue || ''
      uploadPathConfigId.value = res.data.id || null
      currentUploadPath.value = res.data.configValue || ''
    }
  } catch (error) {
    // 行不存在（404）= 还没自定义过，保留空字符串走 yml 默认值
    uploadPathForm.value.basePath = ''
    uploadPathConfigId.value = null
    currentUploadPath.value = ''
  }
}

const saveUploadBasePath = async () => {
  uploadPathLoading.value = true
  directoryPreparation.value = null
  try {
    const value = (uploadPathForm.value.basePath || '').trim()
    let saved
    if (uploadPathConfigId.value) {
      saved = await configApi.systemUpdate(uploadPathConfigId.value, {
        configKey: 'upload.base_path',
        configValue: value,
        configType: 'upload'
      })
    } else {
      const res = await configApi.systemCreate({
        configKey: 'upload.base_path',
        configValue: value,
        configType: 'upload'
      })
      uploadPathConfigId.value = res.data?.id || null
      saved = res
    }
    directoryPreparation.value = saved.data?.directoryPreparation || null
    currentUploadPath.value = saved.data?.configValue || value
    uploadPathForm.value.basePath = currentUploadPath.value
    ElMessage.success('保存成功，采集目录已准备完成')
    // 通知数据汇聚页面刷新 basePath 提示
    window.dispatchEvent(new Event('upload-base-path-updated'))
  } catch (error) {
    // 后端 IllegalArgumentException 会返回 ApiResponse.error(msg)
    const msg = error?.response?.data?.message || error?.message || '保存失败'
    ElMessage.error(msg)
    console.error('保存文件根目录失败:', error)
  } finally {
    uploadPathLoading.value = false
  }
}

const savePlatformInfo = async () => {
  infoLoading.value = true
  try {
    await platformConfigApi.save({
      configKey: 'platform_name',
      configValue: platformConfig.value.platformName
    })
    await platformConfigApi.save({
      configKey: 'description',
      configValue: platformConfig.value.description
    })
    await platformConfigApi.save({
      configKey: 'logo',
      configValue: platformConfig.value.logo
    })
    ElMessage.success('保存成功')
    window.dispatchEvent(new Event('platform-config-updated'))

    const historyRes = await platformConfigApi.getLogoHistory()
    platformConfig.value.logoHistory = historyRes.data || []
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
  } finally {
    infoLoading.value = false
  }
}

const beforeLogoUpload = (file) => {
  const isValidType = file.type === 'image/png' || file.type === 'image/jpeg'
  const isValidSize = file.size / 1024 / 1024 < 2
  if (!isValidType) {
    ElMessage.error('只支持 PNG、JPG 格式')
    return false
  }
  if (!isValidSize) {
    ElMessage.error('文件大小不能超过 2MB')
    return false
  }
  return true
}

const handleLogoUpload = async (options) => {
  logoUploading.value = true
  try {
    const res = await platformConfigApi.uploadLogo(options.file)
    platformConfig.value.logo = res.data
    ElMessage.success('Logo 上传成功')
  } catch (error) {
    console.error('上传失败:', error)
    ElMessage.error('上传失败')
  } finally {
    logoUploading.value = false
  }
}

const selectHistoryLogo = (logo) => {
  platformConfig.value.logo = logo
  ElMessage.success('已选择该 Logo，点击保存生效')
}

const deleteLogo = async (logo) => {
  try {
    await ElMessageBox.confirm('确定删除该 Logo 吗？', '提示', { type: 'warning' })
    await platformConfigApi.deleteLogo(logo)
    platformConfig.value.logoHistory = platformConfig.value.logoHistory.filter(l => l !== logo)
    if (platformConfig.value.logo === logo) {
      platformConfig.value.logo = ''
    }
    ElMessage.success('删除成功')
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

// === SSO 配置（V41 两条 + V42 新增 14 条 system_config 记录） ===

const loadSsoConfig = async () => {
  if (!isAdmin.value) return
  try {
    // V41/V42 两条（V42 后自动创建开关统一为带点 key；UI 读带点，找不到 fallback 无点）
    const [autoCreateRes, autoCreateLegacyRes, msgRes] = await Promise.all([
      configApi.systemByKey('sso.auto_create_user').catch(() => ({ data: null })),
      configApi.systemByKey('sso_auto_create_user').catch(() => ({ data: null })),
      configApi.systemByKey('sso_user_not_exist_message').catch(() => ({ data: null }))
    ])
    const acVal = autoCreateRes?.data?.configValue
      ?? autoCreateLegacyRes?.data?.configValue
    ssoForm.autoCreateUser = acVal === 'true'
    ssoForm.notExistMessage = msgRes?.data?.configValue || ''

    // V42 新增
    await Promise.all(NEW_SSO_KEYS.map(async cfg => {
      try {
        const r = await configApi.systemByKey(cfg.key)
        const v = r?.data?.configValue
        if (v == null) return
        if (cfg.isBool) {
          ssoForm[cfg.field] = v === 'true'
        } else if (cfg.isInt) {
          ssoForm[cfg.field] = parseInt(v, 10) || 0
        } else {
          ssoForm[cfg.field] = v
        }
      } catch (e) {
        // 单个 key 读不到不影响其它
      }
    }))
  } catch (error) {
    console.error('加载 SSO 配置失败:', error)
  }
}

const saveSsoConfig = async () => {
  ssoLoading.value = true
  try {
    // 重新拉一次 id（V42 的 key 可能在首次保存时还没有 id，需要创建）
    // 写 sso.auto_create_user（V42 新 key）；sso_user_not_exist_message 仍用 V41 旧 key（兼容）
    // 跳过 readOnly=true 的统一认证 key —— 它们由 application.yml 启动期绑定，UI 不能改
    const updates = [
      { key: 'sso.auto_create_user', value: String(ssoForm.autoCreateUser), description: 'SSO 访问链接 / 统一认证首次登录是否自动创建账号（true/false，V42 统一开关）' },
      { key: 'sso_user_not_exist_message', value: ssoForm.notExistMessage, description: '关闭自动创建时，用户不存在时展示给前端的提示文案（V41 沿用）' },
      ...NEW_SSO_KEYS
        .filter(cfg => !cfg.readOnly)   // 跳过统一认证启动期配置
        .map(cfg => ({
          key: cfg.key,
          value: cfg.isBool ? String(ssoForm[cfg.field])
                : cfg.isInt ? String(ssoForm[cfg.field])
                : ssoForm[cfg.field],
          description: '同步配置（V42）'
        }))
    ]

    await Promise.all(updates.map(async u => {
      try {
        const idRes = await configApi.systemByKey(u.key)
        if (idRes?.data?.id) {
          await configApi.systemUpdate(idRes.data.id, {
            configKey: u.key,
            configValue: u.value,
            configType: 'sso',
            description: u.description,
            status: 1
          })
        } else {
          // 第一次保存：用 POST 创建
          await configApi.systemCreate({
            configKey: u.key,
            configValue: u.value,
            configType: 'sso',
            description: u.description,
            status: 1
          })
        }
      } catch (e) {
        console.warn(`保存 ${u.key} 失败:`, e)
      }
    }))
    ElMessage.success('SSO 配置已保存')
  } catch (error) {
    console.error('保存 SSO 配置失败:', error)
    ElMessage.error('保存失败')
  } finally {
    ssoLoading.value = false
  }
}

const triggerSyncNow = async () => {
  syncTriggering.value = true
  try {
    const res = await ssoAdminApi.triggerSync()
    if (res.code === 200) {
      const data = res.data
      if (data.status === 'SUCCESS') {
        ElMessage.success(`同步完成：roles=${data.rolesSynced}, users=${data.usersSynced}`)
      } else {
        ElMessage.warning(`同步失败：${data.error || 'unknown'}`)
      }
    } else {
      ElMessage.error(res.message || '触发同步失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '触发同步失败')
  } finally {
    syncTriggering.value = false
  }
}

onMounted(() => {
  loadPlatformConfig()
  loadSsoConfig()
  loadUploadBasePath()
})
</script>

<style scoped>
.config-page {
  padding: 0;
}

.config-tabs {
  height: 100%;
}

.config-tabs :deep(.el-tabs__header) {
  margin-bottom: 15px;
}

.config-tabs :deep(.el-tabs__content) {
  overflow: auto;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.platform-info {
  display: flex;
  gap: 40px;
}

.platform-left {
  flex-shrink: 0;
  width: 260px;
}

.platform-right {
  flex: 1;
  min-width: 0;
}

.section-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--ui-heading);
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--ui-border-soft);
}

.logo-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.logo-preview {
  width: 180px;
  height: 72px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--ui-page) 0%, var(--ui-border) 100%);
  cursor: pointer;
  position: relative;
  overflow: hidden;
  transition: all 0.2s;
}

.logo-preview:hover {
  border-color: var(--ui-primary);
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.logo-preview img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.logo-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--ui-muted);
}

.logo-placeholder span {
  font-size: 12px;
}

.logo-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--ui-surface);
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.2s;
}

.logo-preview:hover .logo-mask {
  opacity: 1;
}

.logo-actions {
  text-align: center;
}

.logo-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ui-muted);
}

.history-section {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--ui-border);
}

.history-label {
  font-size: 12px;
  color: var(--ui-muted);
  margin-bottom: 10px;
}

.history-list {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.history-item {
  width: 44px;
  height: 32px;
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.history-item:hover {
  border-color: var(--ui-primary);
}

.history-item.active {
  border-color: var(--ui-primary);
  border-style: solid;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.2);
}

.history-item img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.history-item .delete-btn {
  position: absolute;
  top: -5px;
  right: -5px;
  width: 14px;
  height: 14px;
  background: var(--ui-danger);
  border-radius: 50%;
  color: var(--ui-surface);
  font-size: 9px;
  display: none;
  align-items: center;
  justify-content: center;
}

.history-item:hover .delete-btn {
  display: flex;
}

.info-form {
  padding-top: 4px;
}

.info-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.info-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.sso-form {
  max-width: 800px;
}

.sso-form .field-hint {
  font-size: 12px;
  color: var(--ui-muted);
  line-height: 1.6;
  margin-top: 8px;
}

.sso-notice {
  margin-bottom: 16px;
}

.sso-collapse {
  margin-top: 8px;
}
.sso-collapse :deep(.el-collapse-item__header) {
  font-weight: 500;
  font-size: 15px;
}

.logo-preview-dialog {
  text-align: center;
  padding: 20px;
}

.logo-preview-dialog img {
  max-width: 100%;
  max-height: 300px;
  object-fit: contain;
}

.no-logo {
  color: var(--ui-muted);
  padding: 60px 0;
}

@media screen and (max-width: 768px) {
  .platform-info {
    flex-direction: column;
    gap: 24px;
  }

  .platform-left {
    width: 100%;
  }

  .platform-right {
    width: 100%;
  }

  .card-header {
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
  }

  .card-header .el-button {
    width: 100%;
  }
}
</style>

<style src="@/views/config/prototype-theme.css"></style>
