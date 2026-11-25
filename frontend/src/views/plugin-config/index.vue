<template>
  <div class="plugin-config-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <WorkspaceSectionHeading title="插件配置" description="扩展接口与触发方式管理" icon="Connection" />
          <div class="header-actions">
            <el-button size="small" @click="handleDownloadTemplate">
              <el-icon><Download /></el-icon>下载模板
            </el-button>
            <el-button size="small" @click="handleImport">
              <el-icon><Upload /></el-icon>导入
            </el-button>
            <el-button size="small" @click="openAuthDialog">认证配置</el-button>
            <el-button type="primary" @click="handleAdd">
              <el-icon><Plus /></el-icon>新建插件
            </el-button>
          </div>
        </div>
      </template>

      <!-- 筛选工具栏 -->
      <div class="filter-toolbar">
        <div class="filter-row" v-balanced-query>
          <el-input v-model="queryParams.name" placeholder="插件名称" clearable @keyup.enter="loadData" @clear="loadData" style="width: 120px;" size="small" />
          <el-select v-model="queryParams.pluginType" placeholder="插件类型" clearable @change="loadData" style="width: 120px;">
            <el-option label="HTTP_API" value="HTTP_API" />
          </el-select>
          <el-select v-model="queryParams.enabled" placeholder="状态" clearable @change="loadData" style="width: 100px;">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
          <div class="query-actions"><el-button type="primary" size="small" @click="loadData">
            <el-icon><Search /></el-icon>查询
          </el-button><el-button size="small" @click="resetFilters">重置</el-button></div>
        </div>
      </div>

      <!-- 表格 -->
      <el-table v-sticky-header v-mirror-scroll :data="tableData" v-loading="tableLoading" border stripe style="width: 100%;"><template #empty><WorkspaceEmptyState title="暂无匹配的插件" description="可调整筛选条件，或新建 HTTP API 插件接入扩展能力。"><el-button link type="primary" @click="handleAdd"><el-icon><Plus /></el-icon>新建插件</el-button></WorkspaceEmptyState></template>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="插件名称" min-width="120" class-name="wrap-cell" />
        <el-table-column prop="nameEn" label="英文名称" min-width="120" class-name="wrap-cell" />
        <el-table-column prop="pluginType" label="类型" width="100" />
        <el-table-column prop="triggerMode" label="触发方式" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.triggerMode === 'MANUAL'" type="info" size="small">手工</el-tag>
            <el-tag v-else-if="row.triggerMode === 'TIMER'" type="warning" size="small">定时</el-tag>
            <el-tag v-else-if="row.triggerMode === 'AUTO'" type="success" size="small">自动</el-tag>
            <span v-else>{{ row.triggerMode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="requestMethod" label="请求方法" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.requestMethod === 'GET'" type="success" size="small">GET</el-tag>
            <el-tag v-else-if="row.requestMethod === 'POST'" type="warning" size="small">POST</el-tag>
            <el-tag v-else-if="row.requestMethod === 'PUT'" type="info" size="small">PUT</el-tag>
            <el-tag v-else-if="row.requestMethod === 'DELETE'" type="danger" size="small">DELETE</el-tag>
            <span v-else>{{ row.requestMethod }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="requestUrl" label="请求地址" min-width="200" class-name="wrap-cell" />
        <el-table-column prop="enabled" label="状态" width="80">
          <template #default="{ row }">
            <WorkspaceStatusBadge :tone="row.enabled === 1 ? 'success' : 'muted'" :pulse="row.enabled === 1">
              {{ row.enabled === 1 ? '启用' : '禁用' }}
            </WorkspaceStatusBadge>
          </template>
        </el-table-column>
        <el-table-column prop="debugStatus" label="调试状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.debugStatus === 'SUCCESS'" type="success" size="small">成功</el-tag>
            <el-tag v-else-if="row.debugStatus === 'FAILED'" type="danger" size="small">失败</el-tag>
            <el-tag v-else type="info" size="small">未调试</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="warning" size="small" @click="handleDebug(row)">调试</el-button>
            <el-button link :type="row.enabled === 1 ? 'danger' : 'success'" size="small" @click="handleToggle(row)">
              {{ row.enabled === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="primary" size="small" @click="handleViewLogs(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 20px; justify-content: flex-end;"
        @size-change="loadData"
        @current-change="loadData"
      />
    </el-card>

    <!-- 编辑对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="editDialogVisible" :title="form.id ? '编辑插件' : '新建插件'" :width="pluginEditWidth" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="插件名称" required>
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="英文名称">
              <el-input v-model="form.nameEn" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="插件类型">
              <el-select v-model="form.pluginType" style="width: 100%;">
                <el-option label="HTTP API" value="HTTP_API" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="触发方式">
              <el-select v-model="form.triggerMode" style="width: 100%;">
                <el-option label="手工" value="MANUAL" />
                <el-option label="定时任务" value="TIMER" />
                <el-option label="自动触发" value="AUTO" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="动态认证">
          <el-select v-model="form.authId" clearable style="width: 100%;" placeholder="不使用动态认证">
            <el-option v-for="auth in authList" :key="auth.id" :label="auth.name" :value="auth.id" />
          </el-select>
          <div class="field-tip">关联后会先调用认证接口获取 Token，并自动注入认证请求头。</div>
        </el-form-item>
        <el-row :gutter="20">
          <el-col :span="16">
            <el-form-item label="请求地址" required>
              <el-input v-model="form.requestUrl" placeholder="http://api.example.com/endpoint" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="请求方法">
              <el-select v-model="form.requestMethod" style="width: 100%;">
                <el-option label="GET" value="GET" />
                <el-option label="POST" value="POST" />
                <el-option label="PUT" value="PUT" />
                <el-option label="DELETE" value="DELETE" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 定时Cron -->
        <el-form-item v-if="form.triggerMode === 'TIMER'" label="定时Cron">
          <el-input v-model="form.timerCron" placeholder="0 */5 * * * ?" />
        </el-form-item>

        <!-- 请求头 -->
        <el-form-item label="请求头">
          <el-radio-group v-model="headerEditorMode" size="small" style="margin-bottom: 8px;" @change="handleHeaderModeChange">
            <el-radio-button label="json">JSON</el-radio-button>
            <el-radio-button label="form">表格</el-radio-button>
          </el-radio-group>
          <el-input v-if="headerEditorMode === 'json'" v-model="headersJson" type="textarea" :rows="5" spellcheck="false" placeholder='{"Content-Type":"application/json","X-Session-Key":"${auth.token}"}' />
          <div v-if="headerEditorMode === 'json'" class="field-tip">填写 JSON 对象：键是 Header 名称，值是 Header 内容；动态 Token 使用 ${auth.token}，例如 X-Session-Key 或 Authorization。</div>
          <template v-else>
          <el-button size="small" @click="addHeader" style="margin-bottom: 8px;">添加请求头</el-button>
          <el-table v-sticky-header v-mirror-scroll :data="headers" border size="small" max-height="200">
            <el-table-column label="名称" width="150">
              <template #default="{ row }">
                <el-input v-model="row.name" placeholder="header名" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="值" min-width="150">
              <template #default="{ row }">
                <el-input v-model="row.value" placeholder="支持${变量}" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="描述" width="150">
              <template #default="{ row }">
                <el-input v-model="row.description" placeholder="描述" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }">
                <el-button link type="danger" size="small" @click="removeHeader($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          </template>
        </el-form-item>

        <!-- 请求参数 -->
        <el-form-item label="请求参数">
          <el-radio-group v-model="paramEditorMode" size="small" style="margin-bottom: 8px;" @change="handleParamModeChange">
            <el-radio-button label="json">JSON</el-radio-button>
            <el-radio-button label="form">表格</el-radio-button>
          </el-radio-group>
          <el-input v-if="paramEditorMode === 'json'" v-model="paramsJson" type="textarea" :rows="7" spellcheck="false" placeholder='{"systemId": 1, "keyword": "cpu"}' />
          <div v-if="paramEditorMode === 'json'" class="field-tip">填写接口实际接收的 JSON 参数；无参数填写 {}。GET 接口的查询参数请按目标接口要求填写。</div>
          <template v-else>
          <el-button size="small" @click="addParam" style="margin-bottom: 8px;">添加参数</el-button>
          <el-table v-sticky-header v-mirror-scroll :data="params" border size="small" max-height="250">
            <el-table-column label="名称" width="120">
              <template #default="{ row }">
                <el-input v-model="row.name" placeholder="参数名" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small" style="width: 100%;">
                  <el-option label="string" value="string" />
                  <el-option label="number" value="number" />
                  <el-option label="boolean" value="boolean" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="位置" width="100">
              <template #default="{ row }">
                <el-select v-model="row.position" size="small" style="width: 100%;">
                  <el-option label="query" value="query" />
                  <el-option label="body" value="body" />
                  <el-option label="path" value="path" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="70">
              <template #default="{ row }">
                <el-checkbox v-model="row.required" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="120">
              <template #default="{ row }">
                <el-input v-model="row.defaultValue" placeholder="默认值" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="来源" width="130">
              <template #default="{ row }">
                <el-input v-model="row.source" placeholder="${gongdan.field}" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="描述" min-width="100">
              <template #default="{ row }">
                <el-input v-model="row.description" placeholder="描述" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }">
                <el-button link type="danger" size="small" @click="removeParam($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          </template>
        </el-form-item>

        <!-- 响应参数 -->
        <el-form-item label="响应参数">
          <el-button size="small" @click="addResponseParam" style="margin-bottom: 8px;">添加响应参数</el-button>
          <el-table v-sticky-header v-mirror-scroll :data="responseParams" border size="small" max-height="200">
            <el-table-column label="名称" width="120">
              <template #default="{ row }">
                <el-input v-model="row.name" placeholder="字段名" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small" style="width: 100%;">
                  <el-option label="string" value="string" />
                  <el-option label="number" value="number" />
                  <el-option label="boolean" value="boolean" />
                  <el-option label="object" value="object" />
                  <el-option label="array" value="array" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="JSONPath" min-width="150">
              <template #default="{ row }">
                <el-input v-model="row.path" placeholder="$.data.id" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="描述" min-width="100">
              <template #default="{ row }">
                <el-input v-model="row.description" placeholder="描述" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }">
                <el-button link type="danger" size="small" @click="removeResponseParam($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveData">确定</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustPluginEditWidth" :visible="editDialogVisible" />
    </div>

    <!-- 调试对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="debugDialogVisible" title="插件调试" :width="pluginDebugWidth" destroy-on-close>
      <el-alert v-if="currentPlugin?.debugStatus === 'SUCCESS'" title="上次调试成功" type="success" :closable="false" style="margin-bottom: 15px;" />
      <el-alert v-else-if="currentPlugin?.debugStatus === 'FAILED'" title="上次调试失败" type="error" :closable="false" style="margin-bottom: 15px;" show-icon />

      <el-form label-width="100px">
        <el-form-item label="请求地址">
          <el-input v-model="debugUrl" disabled />
        </el-form-item>
        <el-form-item label="测试参数">
          <el-input v-model="debugParamsJson" type="textarea" :rows="6" placeholder='{"key": "value"}' />
        </el-form-item>
      </el-form>

      <el-divider content-position="left">调试结果</el-divider>
      <div v-if="debugResult">
        <el-alert v-if="debugResult.success" title="执行成功" type="success" :closable="false" style="margin-bottom: 10px;" />
        <el-alert v-else title="执行失败" type="error" :closable="false" style="margin-bottom: 10px;" />
        <div v-if="debugResult.responseData" style="margin-bottom: 10px;">
          <div style="font-weight: bold; margin-bottom: 5px;">解析结果:</div>
          <el-tag v-for="(value, key) in debugResult.responseData" :key="key" style="margin-right: 5px; margin-bottom: 5px;">
            {{ key }}: {{ typeof value === 'object' ? JSON.stringify(value) : value }}
          </el-tag>
        </div>
        <div v-if="debugResult.rawResponse">
          <div style="font-weight: bold; margin-bottom: 5px;">原始响应:</div>
          <pre style="background: #f5f7fa; padding: 10px; border-radius: 4px; max-height: 300px; overflow: auto; font-size: 12px;">{{ debugResult.rawResponse }}</pre>
        </div>
        <div v-if="debugResult.message && !debugResult.success">
          <div style="font-weight: bold; margin-bottom: 5px;">错误信息:</div>
          <div style="color: #f56c6c;">{{ debugResult.message }}</div>
        </div>
      </div>
      <div v-else style="color: #909399; text-align: center; padding: 30px;">
        点击"执行调试"按钮进行测试
      </div>
      <template #footer>
        <el-button @click="debugDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="debugLoading" @click="executeDebug">执行调试</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustPluginDebugWidth" :visible="debugDialogVisible" />
    </div>

    <!-- 导入对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="importDialogVisible" title="导入插件配置" :width="pluginImportWidth" destroy-on-close>
      <el-form>
        <el-form-item label="选择文件">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            accept=".xlsx"
            :file-list="uploadFileList"
            on-change="handleFileChange"
          >
            <el-button size="small" type="primary">选择Excel文件</el-button>
            <template #tip>
              <div class="el-upload__tip">支持 .xlsx 格式，请先下载模板填写数据</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importLoading" @click="executeImport">开始导入</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustPluginImportWidth" :visible="importDialogVisible" />
    </div>

    <!-- 日志对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="logDialogVisible" title="执行日志" :width="pluginLogWidth" destroy-on-close>
      <el-table v-sticky-header v-mirror-scroll :data="logData" border stripe max-height="400" size="small">
        <el-table-column prop="execTime" label="执行时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.execTime) }}</template>
        </el-table-column>
        <el-table-column prop="businessType" label="业务类型" width="100" />
        <el-table-column prop="businessId" label="业务ID" width="80" />
        <el-table-column prop="execStatus" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.execStatus === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.execStatus === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" class-name="wrap-cell" />
      </el-table>
      <el-pagination
        v-model:current-page="logPagination.page"
        v-model:page-size="logPagination.size"
        :total="logPagination.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 15px; justify-content: flex-end;"
        @size-change="loadLogs"
        @current-change="loadLogs"
      />
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustPluginLogWidth" :visible="logDialogVisible" />
    </div>

    <!-- 认证配置对话框 -->
    <el-dialog class="workspace-theme" v-model="authDialogVisible" title="插件认证配置" width="900px" destroy-on-close>
      <div class="auth-toolbar">
        <el-button type="primary" size="small" @click="newAuth">新建认证</el-button>
        <span class="field-tip">Token 只在后端内存缓存，页面不展示真实 Token。</span>
      </div>
      <el-table :data="authList" border stripe size="small" v-loading="authLoading">
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column prop="loginUrl" label="认证地址" min-width="260" show-overflow-tooltip />
        <el-table-column label="Token 配置" min-width="180">
          <template #default="{ row }">{{ row.tokenHeaderName || 'Authorization' }} / {{ row.tokenPath }}</template>
        </el-table-column>
        <el-table-column label="最近 Token" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.tokenStatus?.token || '未获取' }}</template>
        </el-table-column>
        <el-table-column label="获取时间" width="165">
          <template #default="{ row }">{{ row.tokenStatus?.obtainedAt ? formatDateTime(row.tokenStatus.obtainedAt) : '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><WorkspaceStatusBadge :tone="row.enabled === 1 ? 'success' : 'muted'" :pulse="row.enabled === 1">{{ row.enabled === 1 ? '启用' : '禁用' }}</WorkspaceStatusBadge></template></el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="editAuth(row)">编辑</el-button>
            <el-button link type="success" size="small" @click="testAuth(row)">测试</el-button>
            <el-button link type="warning" size="small" @click="clearAuthCache(row)">清缓存</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="authDialogVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog class="workspace-theme" v-model="authEditVisible" :title="authForm.id ? '编辑认证配置' : '新建认证配置'" width="650px" destroy-on-close>
      <el-form :model="authForm" label-width="110px">
        <el-form-item label="名称" required><el-input v-model="authForm.name" placeholder="例如：本平台会话" /></el-form-item>
        <el-form-item label="认证地址" required><el-input v-model="authForm.loginUrl" placeholder="http://host.docker.internal:18080/metric-code/api/session/login" /></el-form-item>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="方法"><el-select v-model="authForm.loginMethod" style="width:100%"><el-option label="POST" value="POST" /><el-option label="GET" value="GET" /></el-select></el-form-item></el-col><el-col :span="12"><el-form-item label="Content-Type"><el-input v-model="authForm.loginContentType" /></el-form-item></el-col></el-row>
        <el-form-item label="请求头 JSON"><el-input v-model="authForm.loginHeaders" type="textarea" :rows="2" placeholder='[{"name":"X-App-Key","value":"xxx"}]' /></el-form-item>
        <el-form-item label="请求体"><el-input v-model="authForm.loginBody" type="textarea" :rows="3" placeholder='{"username":"账号","password":"密码"}' /></el-form-item>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="Token 路径" required><el-input v-model="authForm.tokenPath" placeholder="$.data.token" /></el-form-item></el-col><el-col :span="12"><el-form-item label="Header 名称"><el-input v-model="authForm.tokenHeaderName" placeholder="Authorization" /></el-form-item></el-col></el-row>
        <div class="field-tip auth-tip">Token 路径使用 JSONPath：返回 {"data":{"token":"abc"}} 填 $.data.token；返回 {"access_token":"abc"} 填 $.access_token。</div>
        <div class="field-tip auth-tip">Header 名称是业务接口接收 Token 的请求头，常见为 Authorization、X-Access-Token、X-Session-Key。</div>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="Token 前缀"><el-input v-model="authForm.tokenValuePrefix" placeholder="Bearer " /></el-form-item></el-col><el-col :span="12"><el-form-item label="缓存秒数"><el-input-number v-model="authForm.ttlSeconds" :min="0" :max="86400" style="width:100%" /></el-form-item></el-col></el-row>
        <div class="field-tip auth-tip">Authorization 通常填写 Bearer 加一个空格；X-Session-Key、X-Access-Token 通常留空。缓存秒数填 0 表示每次请求重新认证。</div>
        <el-form-item label="启用"><el-switch v-model="authEnabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="authEditVisible = false">取消</el-button><el-button type="primary" @click="saveAuth">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { pluginConfigApi, pluginAuthApi } from '../../api'
import { formatDateTime } from '../../utils'
import { useDialogSize } from '../../utils/useDialogSize'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'

// 弹窗宽度控制
const { width: pluginEditWidth, adjustWidth: adjustPluginEditWidth } = useDialogSize('plugin-edit', '800px')
const { width: pluginDebugWidth, adjustWidth: adjustPluginDebugWidth } = useDialogSize('plugin-debug', '900px')
const { width: pluginImportWidth, adjustWidth: adjustPluginImportWidth } = useDialogSize('plugin-import', '500px')
const { width: pluginLogWidth, adjustWidth: adjustPluginLogWidth } = useDialogSize('plugin-log', '900px')

const tableData = ref([])
const tableLoading = ref(false)
const queryParams = ref({
  name: '',
  pluginType: null,
  enabled: null
})
const pagination = ref({
  page: 1,
  size: 10,
  total: 0
})

// 编辑相关
const editDialogVisible = ref(false)
const currentPlugin = ref(null)
const form = ref({
  id: null,
  name: '',
  nameEn: '',
  pluginType: 'HTTP_API',
  description: '',
  requestUrl: '',
  requestMethod: 'POST',
  requestHeaders: '',
  requestParams: '',
  responseParams: '',
  triggerMode: 'MANUAL',
  timerCron: '',
  enabled: 1
  ,authId: null
})
const headers = ref([])
const params = ref([])
const responseParams = ref([])
const headerEditorMode = ref('json')
const paramEditorMode = ref('json')
const headersJson = ref('{}')
const paramsJson = ref('{}')

// 调试相关
const debugDialogVisible = ref(false)
const debugLoading = ref(false)
const debugUrl = ref('')
const debugParamsJson = ref('{}')
const debugResult = ref(null)

// 日志相关
const logDialogVisible = ref(false)
const logData = ref([])
const logPagination = ref({
  page: 1,
  size: 20,
  total: 0
})

// 导入相关
const importDialogVisible = ref(false)
const importLoading = ref(false)
const uploadFileList = ref([])
const uploadRef = ref(null)
const selectedFile = ref(null)
const authList = ref([])
const authLoading = ref(false)
const authDialogVisible = ref(false)
const authEditVisible = ref(false)
const authEnabled = ref(true)
const authForm = ref({ id: null, name: '', loginUrl: '', loginMethod: 'POST', loginContentType: 'application/json', loginBody: '', loginHeaders: '', tokenPath: '$.data.token', tokenHeaderName: 'Authorization', tokenValuePrefix: 'Bearer ', ttlSeconds: 3600, enabled: 1 })

const loadAuthList = async () => {
  authLoading.value = true
  try {
    const res = await pluginAuthApi.list()
    authList.value = await Promise.all((res.data || []).map(async auth => {
      try { const status = await pluginAuthApi.status(auth.id); return { ...auth, tokenStatus: status.data || {} } } catch { return auth }
    }))
  } catch (error) { ElMessage.error(error.message || '加载认证配置失败') } finally { authLoading.value = false }
}
const openAuthDialog = async () => { await loadAuthList(); authDialogVisible.value = true }
const newAuth = () => { authForm.value = { id: null, name: '', loginUrl: '', loginMethod: 'POST', loginContentType: 'application/json', loginBody: '', loginHeaders: '', tokenPath: '$.data.token', tokenHeaderName: 'Authorization', tokenValuePrefix: 'Bearer ', ttlSeconds: 3600, enabled: 1 }; authEnabled.value = true; authEditVisible.value = true }
const editAuth = (row) => { authForm.value = { ...row }; authEnabled.value = row.enabled === 1; authEditVisible.value = true }
const saveAuth = async () => { try { const data = { ...authForm.value, enabled: authEnabled.value ? 1 : 0 }; if (!data.name || !data.loginUrl || !data.tokenPath) return ElMessage.warning('请填写名称、认证地址和 Token 路径'); if (data.id) await pluginAuthApi.update(data.id, data); else await pluginAuthApi.create(data); ElMessage.success('认证配置已保存'); authEditVisible.value = false; await loadAuthList(); loadData() } catch (error) { ElMessage.error(error.message || '保存认证配置失败') } }
const testAuth = async (row) => { try { await pluginAuthApi.test(row.id); ElMessage.success('认证成功，Token 已正确提取'); await loadAuthList() } catch (error) { ElMessage.error(error.message || '认证失败') } }
const clearAuthCache = async (row) => { try { await pluginAuthApi.clearCache(row.id); ElMessage.success('Token 缓存已清除') } catch (error) { ElMessage.error(error.message || '清理缓存失败') } }

const loadData = async () => {
  tableLoading.value = true
  try {
    const p = {
      page: pagination.value.page - 1,
      size: pagination.value.size
    }
    if (queryParams.value.name) p.name = queryParams.value.name
    if (queryParams.value.pluginType) p.pluginType = queryParams.value.pluginType
    if (queryParams.value.enabled !== null && queryParams.value.enabled !== '') p.enabled = queryParams.value.enabled
    const res = await pluginConfigApi.list(p)
    tableData.value = res.data.content || []
    pagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载插件列表失败:', error)
  } finally {
    tableLoading.value = false
  }
}

const resetFilters = () => {
  queryParams.value = { name: '', pluginType: null, enabled: null }
  pagination.value.page = 1
  loadData()
}

const handleAdd = () => {
  currentPlugin.value = null
  form.value = {
    id: null,
    name: '',
    nameEn: '',
    pluginType: 'HTTP_API',
    description: '',
    requestUrl: '',
    requestMethod: 'POST',
    requestHeaders: '',
    requestParams: '',
    responseParams: '',
    triggerMode: 'MANUAL',
    timerCron: '',
    enabled: 1
    ,authId: null
  }
  headers.value = []
  params.value = []
  responseParams.value = []
  headersJson.value = '{}'
  paramsJson.value = '{}'
  headerEditorMode.value = 'json'
  paramEditorMode.value = 'json'
  editDialogVisible.value = true
}

const handleEdit = (row) => {
  currentPlugin.value = row
  form.value = { ...row }
  try {
    headers.value = toEditorRows(row.requestHeaders ? JSON.parse(row.requestHeaders) : {}, 'header')
  } catch { headers.value = [] }
  headersJson.value = toJsonEditorValue(headers.value)
  try {
    params.value = toEditorRows(row.requestParams ? JSON.parse(row.requestParams) : {}, 'param')
  } catch { params.value = [] }
  paramsJson.value = toJsonEditorValue(params.value, true)
  try {
    responseParams.value = row.responseParams ? JSON.parse(row.responseParams) : []
  } catch { responseParams.value = [] }
  editDialogVisible.value = true
}

const handleDebug = (row) => {
  currentPlugin.value = row
  debugUrl.value = row.requestUrl
  debugParamsJson.value = '{}'
  debugResult.value = null
  debugDialogVisible.value = true
}

const executeDebug = async () => {
  debugLoading.value = true
  try {
    let testParams = {}
    try {
      testParams = JSON.parse(debugParamsJson.value)
    } catch {
      ElMessage.warning('请输入有效的JSON格式参数')
      return
    }
    const res = await pluginConfigApi.debug(currentPlugin.value.id, testParams)
    debugResult.value = res.data
  } catch (error) {
    debugResult.value = { success: false, message: error.message || '调试失败' }
  } finally {
    debugLoading.value = false
  }
}

const handleToggle = async (row) => {
  try {
    await pluginConfigApi.toggle(row.id)
    ElMessage.success(row.enabled === 1 ? '已禁用' : '已启用')
    loadData()
  } catch (error) {
    console.error('切换状态失败:', error)
  }
}

const handleViewLogs = async (row) => {
  currentPlugin.value = row
  logPagination.value = { page: 1, size: 20, total: 0 }
  await loadLogs()
  logDialogVisible.value = true
}

const loadLogs = async () => {
  if (!currentPlugin.value) return
  try {
    const res = await pluginConfigApi.getLogs(currentPlugin.value.id, {
      page: logPagination.value.page - 1,
      size: logPagination.value.size
    })
    logData.value = res.data.content || []
    logPagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载日志失败:', error)
  }
}

const saveData = async () => {
  if (!form.value.name) {
    ElMessage.warning('请输入插件名称')
    return
  }
  if (!form.value.requestUrl) {
    ElMessage.warning('请输入请求地址')
    return
  }
  try {
    if (headerEditorMode.value === 'json') {
      const parsed = parseJsonEditor(headersJson.value, '请求头')
      if (parsed === null) return
      form.value.requestHeaders = JSON.stringify(parsed)
    } else {
      form.value.requestHeaders = JSON.stringify(headers.value.filter(h => h.name))
    }
    if (paramEditorMode.value === 'json') {
      const parsed = parseJsonEditor(paramsJson.value, '请求参数')
      if (parsed === null) return
      form.value.requestParams = JSON.stringify(parsed)
    } else {
      form.value.requestParams = JSON.stringify(params.value.filter(p => p.name))
    }
    form.value.responseParams = JSON.stringify(responseParams.value.filter(r => r.name))
    if (form.value.id) {
      await pluginConfigApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await pluginConfigApi.create(form.value)
      ElMessage.success('创建成功')
    }
    editDialogVisible.value = false
    loadData()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

const parseJsonEditor = (value, label) => {
  if (!value || !value.trim()) return {}
  try {
    const parsed = JSON.parse(value)
    if (parsed === null || (typeof parsed !== 'object')) {
      ElMessage.warning(`${label}必须是JSON对象或数组`)
      return null
    }
    return parsed
  } catch (error) {
    ElMessage.warning(`${label} JSON格式错误：${error.message}`)
    return null
  }
}

const toJsonEditorValue = (value, paramsMode = false) => {
  if (!value || (Array.isArray(value) && value.length === 0)) return '{}'
  if (!Array.isArray(value)) return JSON.stringify(value, null, 2)
  const result = {}
  value.filter(item => item && item.name).forEach(item => {
    // 请求头行使用 value，请求参数行使用 defaultValue。
    // 两者不能共用同一个字段，否则重新打开插件时请求头 value 会被清空。
    result[item.name] = paramsMode ? (item.defaultValue ?? '') : (item.value ?? '')
  })
  return JSON.stringify(result, null, 2)
}

const toEditorRows = (value, kind) => {
  if (Array.isArray(value)) return value
  if (!value || typeof value !== 'object') return []
  return Object.entries(value).map(([name, value]) => ({
    name,
    value: kind === 'header' ? String(value ?? '') : undefined,
    defaultValue: kind === 'param' ? value : '',
    type: typeof value,
    position: 'body',
    required: false,
    source: '',
    description: ''
  }))
}

const handleHeaderModeChange = (mode) => {
  if (mode === 'form') {
    const parsed = parseJsonEditor(headersJson.value, '请求头')
    if (parsed !== null) headers.value = toEditorRows(parsed, 'header')
  } else {
    headersJson.value = toJsonEditorValue(headers.value)
  }
}

const handleParamModeChange = (mode) => {
  if (mode === 'form') {
    const parsed = parseJsonEditor(paramsJson.value, '请求参数')
    if (parsed !== null) params.value = toEditorRows(parsed, 'param')
  } else {
    paramsJson.value = toJsonEditorValue(params.value, true)
  }
}

// 表格编辑辅助方法
const addHeader = () => headers.value.push({ name: '', value: '', description: '' })
const removeHeader = (index) => headers.value.splice(index, 1)
const addParam = () => params.value.push({ name: '', type: 'string', position: 'query', required: false, defaultValue: '', source: '', description: '' })
const removeParam = (index) => params.value.splice(index, 1)
const addResponseParam = () => responseParams.value.push({ name: '', type: 'string', path: '', description: '' })
const removeResponseParam = (index) => responseParams.value.splice(index, 1)

const handleDownloadTemplate = async () => {
  try {
    const res = await pluginConfigApi.downloadTemplate()
    const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'plugin_config_template.xlsx'
    link.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('模板下载成功')
  } catch (error) {
    console.error('模板下载失败:', error)
  }
}

const handleImport = () => {
  selectedFile.value = null
  uploadFileList.value = []
  importDialogVisible.value = true
}

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}

const executeImport = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请选择要导入的文件')
    return
  }
  importLoading.value = true
  try {
    const res = await pluginConfigApi.importExcel(selectedFile.value)
    const result = res.data
    if (result.failCount > 0) {
      ElMessage.warning(`导入完成: 成功 ${result.successCount} 条, 失败 ${result.failCount} 条`)
    } else {
      ElMessage.success(`导入成功: ${result.successCount} 条`)
    }
    importDialogVisible.value = false
    loadData()
  } catch (error) {
    console.error('导入失败:', error)
  } finally {
    importLoading.value = false
  }
}

onMounted(() => {
  loadData()
  loadAuthList()
})
</script>

<style scoped>
.plugin-config-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.filter-toolbar {
  margin-bottom: 15px;
  padding: 8px 10px;
  background-color: var(--ui-page);
  border-radius: 4px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.field-tip {
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 20px;
}

.auth-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

:deep(.el-table th.el-table__cell) {
  background-color: var(--ui-page);
}
</style>
