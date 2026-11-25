<!--
  ============================================================================
  MCP 管理（技能管理 → 第 4 个页签）

  平台把告警/工单/问题三块数据以只读 MCP 服务对外开放：
    - 服务内嵌在 backend 进程里，平台启动即 MCP 启动，Docker 部署不需要额外容器；
    - 开关在这里控制，关闭时端点直接 404，不暴露服务存在；
    - 访问凭据是「新建令牌」发放的 Bearer Token，类比大模型 API Key，默认长期有效。

  安全约束（后端已实现，页面只做呈现）：
    - 只读：没有任何 create/update/delete 工具；
    - 令牌只存 SHA-256，明文只在创建那一次显示，关掉弹窗就再也拿不到；
    - 开开关、发令牌、下接入包都只允许管理员。
  ============================================================================
-->
<template>
  <div class="mcp-management">
    <!-- 概览 -->
    <section class="mcp-overview">
      <div class="overview-stat">
        <span class="label">已开启服务</span>
        <strong>{{ overview.enabledServers }}<em>/ 3</em></strong>
      </div>
      <div class="overview-stat">
        <span class="label">近 7 天调用</span>
        <strong>{{ overview.callCount7d }}</strong>
      </div>
      <div class="overview-stat" :class="{ 'is-warn': overview.failed7d > 0 }">
        <span class="label">近 7 天失败</span>
        <strong>{{ overview.failed7d }}</strong>
      </div>
      <div class="overview-stat">
        <span class="label">有效令牌</span>
        <strong>{{ overview.activeTokens }}</strong>
      </div>
      <p class="overview-hint">
        <el-icon><InfoFilled /></el-icon>
        MCP 服务随平台一起启动，无需单独部署。对方平台接入时填端点地址和令牌即可；
        网络策略需另行开通。所有工具均为只读查询。
      </p>
    </section>

    <!-- 三个服务 -->
    <div v-loading="loading" class="mcp-grid">
      <article v-for="server in servers" :key="server.serverKey"
               class="mcp-card" :class="{ 'is-off': !server.enabled }">
        <header class="card-head">
          <div class="card-title">
            <span class="card-icon"><el-icon><Connection /></el-icon></span>
            <div>
              <h3>{{ server.displayName }}</h3>
              <p>{{ server.description }}</p>
            </div>
          </div>
          <el-tooltip :content="server.enabled ? '点击关闭该 MCP 服务' : '点击开启该 MCP 服务'" placement="top">
            <el-switch :model-value="server.enabled" :loading="toggling === server.serverKey"
                       @change="value => toggleServer(server, value)" />
          </el-tooltip>
        </header>

        <div class="card-endpoint">
          <code :title="server.publicUrl">{{ server.publicUrl }}</code>
          <el-button text size="small" @click="copy(server.publicUrl, '端点地址已复制')">
            <el-icon><CopyDocument /></el-icon>
          </el-button>
        </div>

        <div class="card-tools">
          <span v-for="tool in server.toolNames" :key="tool" class="tool-chip">{{ tool }}</span>
        </div>

        <footer class="card-foot">
          <span class="foot-metric">
            近 7 天 <strong>{{ server.callCount7d }}</strong> 次
            <template v-if="server.lastCallTime">· 最近 {{ formatTime(server.lastCallTime) }}</template>
            <template v-else>· 暂无调用</template>
          </span>
          <el-button size="small" :disabled="!canDownload" @click="openPackageDialog(server)">
            <el-icon><Download /></el-icon>接入包
          </el-button>
        </footer>
      </article>
    </div>

    <!-- 令牌 -->
    <section class="mcp-section">
      <header class="section-head">
        <div>
          <h3><el-icon><Key /></el-icon>访问令牌</h3>
          <p>发给对方的 Bearer Token。平台只存哈希，明文只在创建时显示一次。</p>
        </div>
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>新建令牌
        </el-button>
      </header>

      <el-table :data="tokens" size="small" class="token-table" empty-text="还没有令牌，点击右上角新建">
        <el-table-column prop="tokenName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="令牌前缀" width="130">
          <template #default="{ row }"><code class="token-prefix">{{ row.tokenPrefix }}…</code></template>
        </el-table-column>
        <el-table-column label="可访问范围" min-width="140">
          <template #default="{ row }">
            <span v-for="key in scopeList(row.serverScope)" :key="key" class="scope-chip">{{ scopeName(key) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.statusText === '有效' ? 'success' : 'info'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期" width="110">
          <template #default="{ row }">{{ row.expiresAt ? formatTime(row.expiresAt) : '永久' }}</template>
        </el-table-column>
        <el-table-column label="最后使用" width="150">
          <template #default="{ row }">{{ row.lastUsedAt ? formatTime(row.lastUsedAt) : '未使用' }}</template>
        </el-table-column>
        <el-table-column prop="callCount" label="调用次数" width="90" align="right" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.statusText === '有效'" text type="danger" size="small"
                       @click="revokeToken(row)">吊销</el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 调用日志 -->
    <section class="mcp-section">
      <header class="section-head">
        <div>
          <h3><el-icon><Tickets /></el-icon>调用日志</h3>
          <p>谁在什么时候调了哪个工具、返回多少条、耗时多少。只记元信息，不记数据正文。</p>
        </div>
        <div class="section-actions">
          <el-select v-model="logServer" size="small" style="width: 130px" @change="loadLogs(1)">
            <el-option label="全部服务" value="" />
            <el-option v-for="server in servers" :key="server.serverKey"
                       :label="server.displayName" :value="server.serverKey" />
          </el-select>
          <el-button size="small" @click="loadLogs(logPage)">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </header>

      <el-table v-loading="logLoading" :data="logs" size="small" empty-text="暂无调用记录">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="服务" width="100">
          <template #default="{ row }">{{ serverName(row.serverKey) }}</template>
        </el-table-column>
        <el-table-column prop="toolName" label="工具" min-width="150" show-overflow-tooltip />
        <el-table-column prop="tokenName" label="令牌" width="120" show-overflow-tooltip />
        <el-table-column label="结果" width="80" align="right">
          <template #default="{ row }">{{ row.resultCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="80" align="right">
          <template #default="{ row }">{{ row.durationMs != null ? row.durationMs + 'ms' : '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success === 1 ? 'success' : 'danger'">
              {{ row.success === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clientIp" label="来源 IP" width="130" show-overflow-tooltip />
      </el-table>

      <div v-if="logTotal > logSize" class="log-pager">
        <el-pagination layout="prev, pager, next, total" :total="logTotal" :page-size="logSize"
                       :current-page="logPage" @current-change="loadLogs" />
      </div>
    </section>

    <!-- 新建令牌 -->
    <el-dialog v-model="createVisible" title="新建 MCP 访问令牌" width="560px"
               :close-on-click-modal="false" @closed="resetCreate">
      <template v-if="!issuedToken">
        <el-form label-width="96px" label-position="right">
          <el-form-item label="令牌名称" required>
            <el-input v-model="form.tokenName" maxlength="64" show-word-limit
                      placeholder="例如：运维一组-agent、告警大屏" />
          </el-form-item>
          <el-form-item label="可访问范围">
            <el-checkbox-group v-model="form.scope">
              <el-checkbox v-for="server in servers" :key="server.serverKey" :value="server.serverKey">
                {{ server.displayName }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="有效期">
            <el-input-number v-model="form.expiresInDays" :min="1" :max="3650" :controls="false"
                             placeholder="留空=永久" style="width: 140px" />
            <span class="form-hint">天，留空表示永久有效</span>
          </el-form-item>
        </el-form>
        <p class="dialog-tip">
          令牌明文只在创建成功后显示一次，平台只保存哈希。丢了只能重新创建。
        </p>
      </template>

      <template v-else>
        <el-alert type="success" :closable="false" show-icon title="令牌创建成功" />
        <div class="token-result">
          <p class="result-label">令牌明文（请立即复制）</p>
          <div class="result-value">
            <code>{{ issuedToken }}</code>
            <el-button text @click="copy(issuedToken, '令牌已复制')">
              <el-icon><CopyDocument /></el-icon>
            </el-button>
          </div>
        </div>
        <div class="package-block">
          <p class="result-label">下载接入包（含配置片段、桥接器、接入说明）</p>
          <div class="package-actions">
            <el-button v-for="server in serversInScope" :key="server.serverKey" size="small"
                       :loading="downloading === server.serverKey"
                       @click="downloadPackage(server.serverKey)">
              <el-icon><Download /></el-icon>{{ server.displayName }}
            </el-button>
          </div>
        </div>
      </template>

      <template #footer>
        <template v-if="!issuedToken">
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
        </template>
        <el-button v-else type="primary" @click="createVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- 选择服务的接入包（已有令牌时） -->
    <el-dialog v-model="packageVisible" title="下载接入包" width="520px">
      <el-alert type="warning" :closable="false" show-icon
                title="接入包需要令牌明文，平台不保存明文"
                description="请使用「新建令牌」创建后立即下载；已存在的令牌无法再导出接入包。" />
      <p class="dialog-tip" style="margin-top: 14px">
        当前只能下载配置示例，令牌需手动填入。若需要开箱即用的包，请新建一个令牌。
      </p>
      <div class="package-actions">
        <el-button size="small" @click="downloadConfigOnly(selectedServer)">
          <el-icon><Download /></el-icon>下载配置示例
        </el-button>
      </div>
      <template #footer>
        <el-button @click="packageVisible = false">关闭</el-button>
        <el-button type="primary" @click="packageVisible = false; openCreateDialog()">新建令牌并下载</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Connection, CopyDocument, Download, InfoFilled, Key, Plus, Refresh, Tickets
} from '@element-plus/icons-vue'
import { mcpApi } from '@/api'

const loading = ref(false)
const servers = ref([])
const tokens = ref([])
const overview = reactive({ enabledServers: 0, callCount7d: 0, failed7d: 0, activeTokens: 0 })

const toggling = ref('')
const downloading = ref('')

// 日志
const logs = ref([])
const logLoading = ref(false)
const logPage = ref(1)
const logSize = ref(20)
const logTotal = ref(0)
const logServer = ref('')

// 新建令牌
const createVisible = ref(false)
const creating = ref(false)
const issuedToken = ref('')
const form = reactive({ tokenName: '', scope: [], expiresInDays: null })

// 已存在令牌时的接入包提示
const packageVisible = ref(false)
const selectedServer = ref('')

const canDownload = computed(() => true) // 后端校验管理员；这里不做前端硬拦，避免误伤代管场景

const serversInScope = computed(() => {
  if (!form.scope.length) return servers.value
  return servers.value.filter(item => form.scope.includes(item.serverKey))
})

onMounted(loadAll)

async function loadAll () {
  loading.value = true
  try {
    const [serverRes, tokenRes, overviewRes] = await Promise.all([
      mcpApi.servers(), mcpApi.tokens(), mcpApi.overview()
    ])
    servers.value = serverRes.data || []
    tokens.value = tokenRes.data || []
    Object.assign(overview, overviewRes.data || {})
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
  loadLogs(1)
}

function loadLogs (page = 1) {
  logPage.value = page
  logLoading.value = true
  mcpApi.logs({ page: page - 1, size: logSize.value, serverKey: logServer.value || undefined })
    .then(res => {
      logs.value = res.data?.content || []
      logTotal.value = res.data?.totalElements || 0
    })
    .catch(() => {})
    .finally(() => { logLoading.value = false })
}

async function toggleServer (server, value) {
  const action = value ? '开启' : '关闭'
  try {
    await ElMessageBox.confirm(
      value
        ? `开启后，持有有效令牌的客户端即可读取${server.displayName.replace(' MCP', '')}数据。确认开启？`
        : `关闭后端点会立即返回 404，所有客户端将无法访问。确认关闭？`,
      `${action}${server.displayName}`,
      { type: value ? 'warning' : 'info', confirmButtonText: `确认${action}`, cancelButtonText: '取消' }
    )
  } catch { return }

  toggling.value = server.serverKey
  try {
    await mcpApi.toggle(server.serverKey, value)
    ElMessage.success(`${server.displayName}已${action}`)
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  } finally {
    toggling.value = ''
  }
}

function openCreateDialog () {
  createVisible.value = true
}

function resetCreate () {
  form.tokenName = ''
  form.scope = []
  form.expiresInDays = null
  issuedToken.value = ''
}

async function submitCreate () {
  if (!form.tokenName.trim()) {
    ElMessage.warning('请填写令牌名称')
    return
  }
  if (!form.scope.length) {
    ElMessage.warning('请至少选择一个可访问的服务')
    return
  }
  creating.value = true
  try {
    const res = await mcpApi.createToken({
      tokenName: form.tokenName.trim(),
      scope: form.scope,
      expiresInDays: form.expiresInDays || null
    })
    issuedToken.value = res.data.plaintext
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

async function revokeToken (row) {
  try {
    await ElMessageBox.confirm(
      `吊销后「${row.tokenName}」立即失效，正在使用它的智能体会开始报 401。确认吊销？`,
      '吊销令牌',
      { type: 'warning', confirmButtonText: '确认吊销', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await mcpApi.revokeToken(row.id)
    ElMessage.success('已吊销')
    await loadAll()
  } catch (e) { /* 拦截器已提示 */ }
}

async function downloadPackage (serverKey) {
  if (!issuedToken.value) {
    ElMessage.warning('请先新建令牌，再下载接入包')
    return
  }
  downloading.value = serverKey
  try {
    const res = await mcpApi.downloadPackage(serverKey, issuedToken.value)
    saveBlob(res.data, `metric-mcp-${serverKey}.zip`)
    ElMessage.success('接入包已开始下载')
  } catch (e) {
    ElMessage.error('下载失败，请确认账号为管理员')
  } finally {
    downloading.value = ''
  }
}

function openPackageDialog (server) {
  selectedServer.value = server.serverKey
  packageVisible.value = true
}

function downloadConfigOnly (serverKey) {
  const server = servers.value.find(item => item.serverKey === serverKey)
  const config = {
    mcpServers: {
      [`metric-${serverKey}`]: {
        url: server?.publicUrl || '',
        headers: { Authorization: 'Bearer <在此填入你的令牌>' }
      }
    }
  }
  const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' })
  saveBlob(blob, `metric-mcp-${serverKey}-config.json`)
}

function saveBlob (data, filename) {
  const url = URL.createObjectURL(new Blob([data]))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

/** 内网多为 http，非安全上下文没有 clipboard API，退回 execCommand。 */
function legacyCopy (text) {
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.focus()
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch { return false }
}

async function copy (text, successText = '已复制') {
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      ElMessage.success(successText)
      return
    }
  } catch { /* 继续降级 */ }
  if (legacyCopy(text)) ElMessage.success(successText)
  else ElMessage.warning('当前浏览器不支持自动复制，请手动选择文本')
}

function scopeList (scope) {
  if (!scope || scope === '*') return servers.value.map(item => item.serverKey)
  return scope.split(',').map(item => item.trim()).filter(Boolean)
}

function scopeName (key) {
  return servers.value.find(item => item.serverKey === key)?.displayName || key
}

function serverName (key) {
  return servers.value.find(item => item.serverKey === key)?.displayName || key
}

function formatTime (value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.mcp-management { display: flex; flex-direction: column; gap: 18px; }

/* 概览 */
.mcp-overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 16px 18px;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  box-shadow: var(--ui-shadow-sm);
}
.overview-stat { display: flex; flex-direction: column; gap: 4px; }
.overview-stat .label { font-size: 12px; color: var(--ui-muted); }
.overview-stat strong { font-size: 22px; color: var(--ui-heading); font-weight: 600; }
.overview-stat strong em { font-style: normal; font-size: 13px; color: var(--ui-muted); margin-left: 2px; }
.overview-stat.is-warn strong { color: #d9534f; }
.overview-hint {
  grid-column: 1 / -1;
  display: flex; align-items: flex-start; gap: 6px;
  margin: 4px 0 0; padding-top: 10px;
  border-top: 1px dashed var(--ui-border-soft);
  font-size: 12px; color: var(--ui-muted); line-height: 1.6;
}

/* 服务卡片 */
.mcp-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.mcp-card {
  display: flex; flex-direction: column; gap: 12px;
  padding: 16px 18px;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  box-shadow: var(--ui-shadow-sm);
  transition: box-shadow .2s, border-color .2s;
}
.mcp-card:hover { box-shadow: var(--ui-shadow-hover); border-color: var(--ui-primary-border); }
.mcp-card.is-off { opacity: .72; background: var(--ui-subtle); }

.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.card-title { display: flex; gap: 10px; min-width: 0; }
.card-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; flex: 0 0 34px;
  border-radius: 10px;
  background: var(--ui-primary-soft); color: var(--ui-primary-strong);
}
.card-title h3 { margin: 0; font-size: 15px; color: var(--ui-heading); }
.card-title p { margin: 2px 0 0; font-size: 12px; color: var(--ui-muted); line-height: 1.5; }

.card-endpoint {
  display: flex; align-items: center; gap: 4px;
  padding: 6px 8px;
  background: var(--ui-field);
  border: 1px solid var(--ui-border-soft);
  border-radius: 8px;
}
.card-endpoint code {
  flex: 1; min-width: 0;
  font-size: 12px; color: var(--ui-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.card-tools { display: flex; flex-wrap: wrap; gap: 6px; }
.tool-chip {
  padding: 2px 8px;
  font-size: 11px; color: var(--ui-primary-strong);
  background: var(--ui-primary-soft);
  border: 1px solid var(--ui-primary-border);
  border-radius: 999px;
}

.card-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  margin-top: auto; padding-top: 10px;
  border-top: 1px dashed var(--ui-border-soft);
}
.foot-metric { font-size: 12px; color: var(--ui-muted); }
.foot-metric strong { color: var(--ui-text); }

/* 区块 */
.mcp-section {
  padding: 16px 18px;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-lg);
  box-shadow: var(--ui-shadow-sm);
}
.section-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  margin-bottom: 12px;
}
.section-head h3 {
  display: flex; align-items: center; gap: 6px;
  margin: 0; font-size: 14px; color: var(--ui-heading);
}
.section-head p { margin: 4px 0 0; font-size: 12px; color: var(--ui-muted); }
.section-actions { display: flex; align-items: center; gap: 8px; }

.token-table :deep(.token-prefix) { font-size: 12px; }
.scope-chip {
  display: inline-block; margin-right: 4px; padding: 1px 7px;
  font-size: 11px; color: var(--ui-text);
  background: var(--ui-field);
  border: 1px solid var(--ui-border-soft);
  border-radius: 999px;
}
.muted { color: var(--ui-muted); }
.log-pager { display: flex; justify-content: flex-end; margin-top: 10px; }

/* 弹窗 */
.form-hint { margin-left: 8px; font-size: 12px; color: var(--ui-muted); }
.dialog-tip { margin: 8px 0 0; font-size: 12px; color: var(--ui-muted); line-height: 1.6; }
.token-result { margin-top: 14px; }
.result-label { margin: 0 0 6px; font-size: 12px; color: var(--ui-muted); }
.result-value {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px;
  background: var(--ui-field);
  border: 1px solid var(--ui-primary-border);
  border-radius: 8px;
}
.result-value code { flex: 1; word-break: break-all; font-size: 13px; color: var(--ui-text); }
.package-block { margin-top: 16px; }
.package-actions { display: flex; flex-wrap: wrap; gap: 8px; }

@media (max-width: 1200px) {
  .mcp-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 768px) {
  .mcp-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .mcp-grid { grid-template-columns: 1fr; }
  .section-head { flex-direction: column; }
}
</style>
