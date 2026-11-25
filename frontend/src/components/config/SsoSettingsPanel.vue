<template>
        <el-card>
          <template #header>
            <div class="card-header">
              <WorkspaceSectionHeading title="统一认证登录" icon="Key" description="公司账号一键登录与同步配置" />
              <el-button type="primary" @click="emit('save')" :loading="ssoLoading">
                <el-icon><Edit /></el-icon>保存
              </el-button>
            </div>
          </template>

          <div class="sso-notice">
            <el-alert
              title="统一认证的 client_id / 接入地址等配置，改完后端 application.yml 后必须重启后端才生效。"
              type="info"
              show-icon
              :closable="false"
              description="修改后端 application.yml 或环境变量后需重启后端进程。访问链接 / 同步 / 自动创建 等开关可运行时生效。"
            />
          </div>

          <el-collapse v-model="activeSsoCollapse" class="sso-collapse">
            <!-- 1. 访问链接 SSO（V41 既有） -->
            <el-collapse-item title="统一认证首次进入" name="access-link">
              <el-form :model="ssoForm" label-width="180px" class="sso-form">
                <el-form-item label="自动创建用户">
                  <el-switch
                    v-model="ssoForm.autoCreateUser"
                    active-text="开启"
                    inactive-text="关闭"
                  />
                  <div class="field-hint">
                    开启后，首次通过 usermanager 统一认证进入的用户会自动创建本地账号并关联基础角色。旧访问链接也先跳转到统一认证，链接中的用户名不会直接作为登录身份。
                    关闭时维持原 404 行为,展示下方提示文案。
                  </div>
                </el-form-item>
                <el-form-item label="用户不存在提示文案">
                  <el-input
                    v-model="ssoForm.notExistMessage"
                    type="textarea"
                    :rows="3"
                    placeholder="关闭自动创建时,SSO 链接访问的用户不存在时展示给前端的提示"
                    maxlength="200"
                    show-word-limit
                  />
                </el-form-item>
              </el-form>
            </el-collapse-item>

            <!-- 2. 统一认证客户端（启动期绑定，UI 只读） -->
            <el-collapse-item title="统一认证客户端参数（client_id / 接入地址）" name="oidc">
              <el-alert
                type="warning"
                show-icon
                :closable="false"
                title="以下配置需要运维改后端 application.yml 后重启，UI 不可修改"
                description="client_id / 客户端密钥 / 接入地址 / 回调地址 这些值，是后端启动时从 application.yml 读进来的，运行期改本页不会生效。要换统一认证平台或调整接入地址，请联系运维修改后端 application.yml 后重启服务即可。下方字段仅展示当前生效值。"
                style="margin-bottom: 16px;"
              />
              <el-form :model="ssoForm" label-width="180px" class="sso-form">
                <el-form-item label="client_id（应用 ID）">
                  <el-input v-model="ssoForm.clientId" disabled placeholder="统一认证平台分发的应用 ID" />
                </el-form-item>
                <el-form-item label="client_secret（应用密钥）">
                  <el-input v-model="ssoForm.clientSecret" type="password" show-password disabled placeholder="应用密钥（启动期绑定）" />
                </el-form-item>
                <el-form-item label="issuer（平台地址）">
                  <el-input v-model="ssoForm.issuer" disabled placeholder="如 http://idp.host:7200/usermanager" />
                </el-form-item>
                <el-form-item label="授权地址">
                  <el-input v-model="ssoForm.authorizationUri" disabled placeholder="统一认证的登录页面地址" />
                </el-form-item>
                <el-form-item label="令牌地址">
                  <el-input v-model="ssoForm.tokenUri" disabled placeholder="换取 access_token 的地址" />
                </el-form-item>
                <el-form-item label="签名公钥地址">
                  <el-input v-model="ssoForm.jwksUri" disabled placeholder="验证 token 签名的公钥地址" />
                </el-form-item>
                <el-form-item label="用户信息地址">
                  <el-input v-model="ssoForm.userInfoUri" disabled placeholder="拉用户详细信息的地址" />
                </el-form-item>
                <el-form-item label="请求范围">
                  <el-input v-model="ssoForm.scope" disabled placeholder="如 openid profile" />
                </el-form-item>
                <el-form-item label="回调地址">
                  <el-input v-model="ssoForm.redirectUri" disabled placeholder="统一认证完成后回调到本系统的地址（端口 18080）" />
                </el-form-item>
                <el-form-item label="前端 base URL">
                  <el-input v-model="ssoForm.frontendBaseUrl" disabled placeholder="登录完成后跳回的前端地址；生产可置空走 nginx" />
                </el-form-item>
              </el-form>
            </el-collapse-item>

            <!-- 3. 定时同步（V42 新增） -->
            <el-collapse-item title="定时从统一认证同步用户/角色" name="sync">
              <el-form :model="ssoForm" label-width="180px" class="sso-form">
                <el-form-item label="启用同步">
                  <el-switch
                    v-model="ssoForm.syncEnabled"
                    active-text="开启"
                    inactive-text="关闭"
                  />
                  <div class="field-hint">
                    开启后,按下方间隔定时从统一认证平台拉取用户/角色到本地。
                  </div>
                </el-form-item>
                <el-form-item label="同步间隔（毫秒）">
                  <el-input-number v-model="ssoForm.syncFixedDelayMs" :min="60000" :step="60000" />
                  <div class="field-hint">默认 3600000（1 小时）；最少 60000（1 分钟）</div>
                </el-form-item>
                <el-form-item label="统一认证 API 地址">
                  <el-input v-model="ssoForm.syncApiBaseUrl" placeholder="如 http://idp.host:7200/usermanager" />
                </el-form-item>
                <el-form-item label="服务账号 Token">
                  <el-input v-model="ssoForm.syncApiToken" type="password" show-password placeholder="服务账号 token（脱敏存储）" />
                </el-form-item>
                <el-form-item>
                  <el-button @click="emit('sync')" :loading="syncTriggering" type="warning" plain>
                    立即同步（手动触发）
                  </el-button>
                  <span class="field-hint" style="margin-left: 12px;">
                    结果查看：<router-link to="/audit">审计日志</router-link>
                  </span>
                </el-form-item>
              </el-form>
            </el-collapse-item>
          </el-collapse>
        </el-card>
</template>
<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
const activeSsoCollapse=defineModel('expanded',{type:Array,required:true})
defineProps({ssoForm:{type:Object,required:true},ssoLoading:Boolean,syncTriggering:Boolean})
const emit=defineEmits(['save','sync'])
</script>
