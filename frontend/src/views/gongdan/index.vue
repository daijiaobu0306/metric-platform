<template>
  <div class="gongdan-page workspace-theme">
    <WorkspacePageHeader title="工单管理" description="统一管理工单生成、处理与闭环，跟踪 BOMC 同步状态及操作记录。" badge="BOMC 工单对接" />
    <el-tabs v-model="activeTab" class="gongdan-workspace-tabs workspace-tabs">
      <!-- 工单列表 Tab -->
      <el-tab-pane label="工单列表" name="list"><template #label><WorkspaceTabLabel icon="Tickets">工单列表</WorkspaceTabLabel></template>
        <div class="gongdan-list-workspace">
          <WorkspaceOverviewLayout>
            <template #stats><GongdanOverviewCards :stats="gongdanStats" @time-range="filterByTimeRange" @closed-status="filterByClosedStatus" @synced="filterBySynced" /></template>
            <template #trend>

          <WorkspacePanel icon="TrendCharts" title="工单数量趋势" description="按时间查看工单数量，点击图表可筛选对应时段。">

            <div class="trend-header">
              <div class="trend-tabs">
                <el-radio-group v-model="trendDimension" size="small" @change="loadTrendData">
                  <el-radio-button value="today">今天</el-radio-button>
                  <el-radio-button value="week">近一周</el-radio-button>
                  <el-radio-button value="month">近一月</el-radio-button>
                  <el-radio-button value="year">近一年</el-radio-button>
                </el-radio-group>
              </div>
              <div class="trend-filters">
                <el-select v-model="trendType" size="small" style="width: 90px;" @change="loadTrendData">
                  <el-option label="全部" value="all" />
                  <el-option label="未关单" value="未关单" />
                  <el-option label="已关单" value="已关单" />
                </el-select>
                <el-date-picker
                  v-model="trendTimeRange"
                  type="daterange"
                  range-separator="至"
                  start-placeholder="开始"
                  end-placeholder="结束"
                  value-format="YYYY-MM-DD"
                  size="small"
                  style="width: 200px;"
                  @change="loadTrendData"
                />
              </div>
            </div>
            <div v-if="getTimeRangeHint(trendDimension, trendData)" class="trend-range-hint">
              {{ getTimeRangeHint(trendDimension, trendData) }}
            </div>
            <div v-if="chartDrillDownLabel" class="drill-down-tip">
              <el-icon><InfoFilled /></el-icon> 当前筛选：{{ chartDrillDownLabel }}
              <el-button type="danger" size="small" link @click="clearDrillDown">清除</el-button>
            </div>
            <div class="trend-chart-wrapper">
              <div ref="trendChartDom" class="trend-chart"></div>
              <div v-if="trendData.length === 0" class="trend-empty-overlay">
                <el-icon size="20" color="#909399"><WarningFilled /></el-icon>
                <span>{{ getEmptyText() }}</span>
              </div>
            </div>
          </WorkspacePanel>
            </template>
          </WorkspaceOverviewLayout>


      <!-- 多维度筛选工具栏 -->
      <GongdanFilters v-model:filters="queryParams" :alarm-level-options="alarmLevelOptions" @search="loadData" @reset="resetGongdanFilters" />

      <section ref="quickFilterResult" class="gongdan-table-panel" aria-label="工单列表">
        <div v-if="quickFilterLabel" class="quick-filter-feedback" role="status"><span>快捷筛选：<strong>{{ quickFilterLabel }}</strong></span><el-button link type="primary" @click="resetGongdanFilters">清除筛选</el-button></div>
      <!-- 表格工具栏：桌面和移动端共用 -->
        <div class="table-toolbar">
          <el-button type="primary" size="small" @click="handleAdd"><el-icon><Plus /></el-icon>新建工单</el-button>
          <el-button type="danger" size="small" :disabled="!selectedRows.length" @click="handleBatchDelete">
            <el-icon><Delete /></el-icon>删除（本地）
          </el-button>
          <el-button size="small" @click="handlePushPending">
            <el-icon><Refresh /></el-icon>补推 BOMC
          </el-button>
          <el-button size="small" :loading="statusRefreshing" @click="handleRefreshStatus">
            <el-icon><Refresh /></el-icon>刷新状态
          </el-button>
          <span v-if="selectedRows.length" class="selected-count">已选择 {{ selectedRows.length }} 项</span>
          <div class="toolbar-spacer"></div>
          <el-popover popper-class="workspace-theme workspace-popover" placement="bottom" :width="200" trigger="click">
            <template #reference>
              <el-button size="small">
                <el-icon><Setting /></el-icon>列设置 ({{ visibleColumns.length }}/{{ columnOptions.length }})
              </el-button>
            </template>
            <div style="margin-bottom: 8px; color: #909399; font-size: 12px;">已选 {{ visibleColumns.length }} / {{ columnOptions.length }} 列</div>
            <el-checkbox-group v-model="visibleColumns" class="column-toggle-group" @change="handleColumnChange">
              <el-checkbox v-for="col in columnOptions" :key="col.prop" :value="col.prop" class="column-toggle-item">
                {{ col.label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-popover>
        </div>
      <div class="desktop-table">
        <el-table
          v-sticky-header
          v-mirror-scroll
          :data="tableData"
          v-loading="tableLoading"
          :fit="true"
          style="width: 100%;"
          @selection-change="handleSelectionChange"
          @sort-change="handleSortChange"
          @header-contextmenu="handleHeaderContextMenu"
        ><template #empty><WorkspaceEmptyState title="暂无匹配的工单" description="可调整工单编号、同步状态或时间范围，查看其他工单。" /></template>
          <el-table-column type="selection" width="50" fixed="left"/>
          <el-table-column v-if="!isColumnHidden('gongdanTime')" prop="gongdanTime" label="工单时间" width="145" sortable="custom" class-name="workspace-time-cell">
            <template #default="{ row }">{{ formatDateTime(row.gongdanTime) }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('gongdanId')" prop="gongdanId" label="工单编号" width="150" class-name="wrap-cell">
            <template #default="{ row }">
              <el-button type="primary" link class="gongdan-id-link" @click="handleViewDetail(row)">
                {{ row.gongdanId || '-' }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('externalId')" prop="externalId" label="BOMC工单号" width="110" class-name="wrap-cell">
            <template #default="{ row }"><WorkspaceInlineCode :value="row.externalId" /></template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('gongdanTitle')" prop="gongdanTitle" label="工单标题" min-width="220" class-name="wrap-cell" />
          <el-table-column v-if="!isColumnHidden('alarmObject')" prop="alarmObject" label="告警对象" min-width="160" class-name="wrap-cell">
            <template #default="{ row }">{{ row.alarmObject || '-' }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('alarmLevel')" prop="alarmLevel" label="告警级别" width="95">
            <template #default="{ row }">
              <el-tag :type="alarmLevelType(row.alarmLevel)" size="small">{{ alarmLevelText(row.alarmLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('alarmPriority')" prop="alarmPriority" label="优先级" width="75">
            <template #default="{ row }">{{ row.alarmPriority || '-' }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('externalStatus')" prop="externalStatus" label="BOMC当前节点" width="125" class-name="wrap-cell">
            <template #default="{ row }">{{ row.externalStatus || '-' }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('gongdanType')" prop="gongdanType" label="类型" width="70" />
          <el-table-column v-if="!isColumnHidden('currentHandler')" prop="currentHandler" label="处理人" width="90" class-name="wrap-cell">
            <template #default="{ row }">{{ row.currentHandler || '-' }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('closedStatus')" prop="closedStatus" label="状态" width="100">
            <template #default="{ row }">
              <WorkspaceStatusBadge :tone="statusInfo(row).type">
                {{ statusInfo(row).text }}
              </WorkspaceStatusBadge>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('synced')" label="BOMC同步" width="115">
            <template #default="{ row }">
              <el-tooltip v-if="row.synced !== 1 && row.externalSyncMsg" :content="row.externalSyncMsg" placement="top">
                <WorkspaceStatusBadge tone="danger">推送失败</WorkspaceStatusBadge>
              </el-tooltip>
              <WorkspaceStatusBadge v-else-if="row.synced === 1" tone="success">已推送</WorkspaceStatusBadge>
              <WorkspaceStatusBadge v-else tone="muted">未推送</WorkspaceStatusBadge>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <div class="op-actions">
                <el-button link type="primary" size="small" @click="handleViewDetail(row)">详情</el-button>
                <el-dropdown trigger="click" @command="(cmd) => handleRowCommand(cmd, row)">
                  <el-button link type="primary" size="small">
                    更多<el-icon><ArrowDown /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">编辑</el-dropdown-item>
                      <el-dropdown-item v-if="row.synced !== 1" command="push">推送到 BOMC</el-dropdown-item>
                      <el-dropdown-item v-else command="repush">重新推送 BOMC</el-dropdown-item>
                      <!-- 仅已推送 BOMC 的工单才有撤销/归档；未推送成功的只有 编辑 / 推送 / 删除（本地） -->
                      <el-dropdown-item v-if="row.externalId && row.bomcAction == null && row.closedStatus === 0" command="quash">撤销工单</el-dropdown-item>
                      <el-dropdown-item v-if="row.externalId && row.bomcAction == null" command="archive">归档</el-dropdown-item>
                      <el-dropdown-item command="delete" divided>删除（本地）</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 移动端卡片 -->
      <div class="mobile-cards">
        <el-card v-for="row in tableData" :key="row.id" class="gongdan-card" shadow="hover">
          <div class="card-content">
            <div class="card-row">
              <WorkspaceStatusBadge :tone="statusInfo(row).type">
                {{ statusInfo(row).text }}
              </WorkspaceStatusBadge>
              <el-tag :type="alarmLevelType(row.alarmLevel)" size="small">{{ alarmLevelText(row.alarmLevel) }}</el-tag>
              <span class="time">{{ formatDateTime(row.gongdanTime) }}</span>
            </div>
            <div class="card-row">
              <span class="label">工单标题：</span>
              <span class="value">{{ row.gongdanTitle }}</span>
            </div>
            <div class="card-row">
              <span class="label">BOMC工单号：</span>
              <span class="value">{{ row.externalId || '未推送' }}</span>
            </div>
            <div class="card-row">
              <span class="label">告警对象：</span>
              <span class="value">{{ row.alarmObject || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">BOMC节点：</span>
              <span class="value">{{ row.externalStatus || '-' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <el-button type="primary" size="small" @click="handleViewDetail(row)">详情</el-button>
            <el-button size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="row.synced !== 1" size="small" @click="handlePush(row, false)">推送</el-button>
            <el-button v-else size="small" @click="handleRepush(row)">重推</el-button>
            <el-button v-if="row.externalId && row.bomcAction == null && row.closedStatus === 0" size="small" @click="openActionDialog(row, 'quash')">撤销</el-button>
            <el-button v-if="row.externalId && row.bomcAction == null" size="small" @click="openActionDialog(row, 'archive')">归档</el-button>
          </div>
        </el-card>
        <el-empty v-if="tableData.length === 0" description="暂无数据" />
      </div>

      <WorkspacePagination v-model:page="pagination.page" v-model:size="pagination.size" :total="pagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadData" />
      </section>
        </div>
      </el-tab-pane>

      <!-- 工单配置 Tab -->
      <el-tab-pane label="工单配置" name="config"><template #label><WorkspaceTabLabel icon="Setting">工单配置</WorkspaceTabLabel></template>
        <el-card class="gongdan-config-panel" shadow="never">
          <div class="config-section">
            <WorkspaceSectionHeading title="工单开关配置" icon="SwitchButton" />
            <div class="switch-item">
              <span class="switch-label">工单开关</span>
              <el-switch
                v-model="gongdanSwitchEnabled"
                active-text="启用"
                inactive-text="禁用"
                @change="toggleGongdanSwitch"
              />
              <span class="switch-hint">关闭后不再生成新的工单</span>
            </div>
          </div>
          <div class="config-section">
            <WorkspaceSectionHeading title="BOMC 工单接口对接" description="双击配置值可编辑" icon="Connection" />
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item v-for="item in bomcConfigItems" :key="item.key" :label="item.label">
                <div class="editable-value" :title="'双击修改' + item.label" @dblclick="startEditConfig(item.key)">
                  <el-input
                    v-if="editingConfigKey === item.key"
                    ref="configInputRef"
                    v-model="editingConfigValue"
                    size="small"
                    @keyup.enter="saveConfigItem(item.key)"
                    @keyup.esc="cancelEditConfig"
                    @blur="saveConfigItem(item.key)"
                  />
                  <span v-else class="editable-text">{{ bomcConfig[item.key] || '（未设置）' }}</span>
                </div>
              </el-descriptions-item>
            </el-descriptions>
            <div class="config-tip">双击数值即可修改；清空后保存将恢复平台默认值。</div>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- 操作日志 Tab -->
      <el-tab-pane label="操作日志" name="auditLog"><template #label><WorkspaceTabLabel icon="Clock">操作日志</WorkspaceTabLabel></template>
        <el-card class="gongdan-audit-panel" shadow="never">
          <GongdanAuditLog ref="operationLogRef" entity-type="GONGDAN" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 工单对话框 -->
    <div style="position: relative;">
      <GongdanFormDialog v-model="dialogVisible" v-model:form="form" :width="gongdanDialogWidth" :system-options="systemOptions" :alarm-level-options="alarmLevelOptions" @system-change="handleSystemChange" @save="saveData" />
    <DialogSizeControls :adjustWidth="adjustGongdanDialogWidth" :visible="dialogVisible" />
    </div>

    <!-- 工单详情对话框（含 BOMC 处理轨迹） -->
    <GongdanDetailDialog
      v-model="detailDialogVisible"
      :gongdan-id="currentGongdanId"
      @refresh="loadData(); loadGongdanStats()"
    />

    <!-- 归档 / 撤销工单对话框（reason → BOMC opin） -->
    <el-dialog class="workspace-theme gongdan-action-dialog"
      v-model="actionDialog.visible"
      :title="actionDialog.type === 'archive' ? '归档工单' : '撤销工单'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="BOMC工单号">
          <el-input :model-value="actionDialog.row?.externalId" disabled />
        </el-form-item>
        <el-form-item :label="actionDialog.type === 'archive' ? '归档原因' : '撤销原因'">
          <el-input
            v-model="actionDialog.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            :placeholder="actionDialog.type === 'archive' ? '请填写归档原因（必填）' : '请填写撤销原因（可留空，默认：工单撤销）'"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="actionDialog.type === 'archive'"
        type="warning"
        :closable="false"
        show-icon
        title="归档需工单已回复处理完成"
        description="若 BOMC 提示「BOMC工单还未处理完成,请等待回复后再启动归档.」，请等工单回复完成后再归档。"
      />
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        title="撤销将调用 BOMC 撤销接口"
        description="本地记录会打上「已撤销」标记并关单，不会删除数据；只有「删除（本地）」才会删数据。"
      />
      <template #footer>
        <el-button @click="actionDialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="actionDialog.submitting"
          @click="submitActionDialog"
        >
          {{ actionDialog.type === 'archive' ? '确认归档' : '确认撤销' }}
        </el-button>
      </template>
    </el-dialog>
    <!-- 表头右键菜单 -->
    <div
      v-if="showHeaderContextMenu"
      class="header-context-menu"
      :style="{ left: contextMenuPosition.x + 'px', top: contextMenuPosition.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item" @click="hideColumnFromMenu">
        隐藏此列
      </div>
    </div>
    <div
      v-if="showHeaderContextMenu"
      class="context-menu-overlay"
      @click="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    />
  </div>
</template>

<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import WorkspaceInlineCode from '@/components/workspace/WorkspaceInlineCode.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { readWorkspaceChartTheme } from '@/utils/workspaceTheme'
import GongdanFormDialog from '@/components/gongdan/GongdanFormDialog.vue'
import WorkspaceOverviewLayout from '@/components/workspace/WorkspaceOverviewLayout.vue'
import WorkspacePanel from '@/components/workspace/WorkspacePanel.vue'
import GongdanOverviewCards from '@/components/gongdan/GongdanOverviewCards.vue'
import WorkspacePagination from '@/components/workspace/WorkspacePagination.vue'
import GongdanFilters from '@/components/gongdan/GongdanFilters.vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { gongdanApi, gongdanIntegrationApi, alarmSwitchApi, systemApi } from '../../api'
import { formatDateTime, simplifyTimeLabels, getTimeRangeHint } from '../../utils'
import { useDialogSize } from '../../utils/useDialogSize'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'
import GongdanDetailDialog from '@/components/gongdan/GongdanDetailDialog.vue'
import GongdanAuditLog from '@/components/gongdan/GongdanAuditLog.vue'
import { InfoFilled, ArrowDown, Refresh } from '@element-plus/icons-vue'

// 弹窗宽度控制
const { width: gongdanDialogWidth, adjustWidth: adjustGongdanDialogWidth } = useDialogSize('gongdan-dialog', '720px')

const route = useRoute()
const router = useRouter()

// 告警级别选项（对应 BOMC mo_alarm_currentgrade）
const alarmLevelOptions = [
  { value: 1, label: '1-警告告警' },
  { value: 2, label: '2-一般告警' },
  { value: 3, label: '3-重要告警' },
  { value: 4, label: '4-严重告警' }
]

const alarmLevelText = (level) => {
  const map = { 1: '警告', 2: '一般', 3: '重要', 4: '严重' }
  return map[level] || '-'
}

const alarmLevelType = (level) => {
  const map = { 1: 'info', 2: 'primary', 3: 'warning', 4: 'danger' }
  return map[level] || 'info'
}

// 状态列展示：撤销/归档打标优先；未推送 BOMC 的工单没有「关单」概念，显示未推送；其余看关单状态
const statusInfo = (row) => {
  if (row.bomcAction === 'ARCHIVED') return { text: '已归档', type: 'success' }
  if (row.bomcAction === 'QUASHED') return { text: '已撤销', type: 'info' }
  if (!row.externalId) return { text: '未推送', type: 'info' }
  return row.closedStatus === 0 ? { text: '未关单', type: 'warning' } : { text: '已关单', type: 'success' }
}

// 归档/撤销工单对话框（reason → BOMC opin）
const actionDialog = ref({ visible: false, type: 'quash', row: null, reason: '', submitting: false })

const tableData = ref([])
const tableLoading = ref(false)
const systemOptions = ref([])
const bomcConfig = ref({})
const queryParams = ref({
  closedStatus: null,
  synced: null,
  gongdanType: null,
  alarmLevel: null,
  gongdanId: '',
  externalId: '',
  gongdanTitle: '',
  alarmObject: '',
  gongdanTimeRange: null
})
const pagination = ref({
  page: 1,
  size: 10,
  total: 0
})

// 工单开关状态
const gongdanSwitchEnabled = ref(true)
const activeTab = ref('list')
const operationLogRef = ref(null)

// 监听 Tab 切换，自动刷新操作日志 / BOMC 对接配置
watch(activeTab, (newTab) => {
  if (newTab === 'auditLog' && operationLogRef.value) {
    operationLogRef.value.refresh()
  }
  if (newTab === 'config') {
    loadBomcConfig()
  }
})

// 加载工单开关状态
const loadGongdanSwitchStatus = async () => {
  try {
    const res = await alarmSwitchApi.getStatus()
    gongdanSwitchEnabled.value = res.data.gongdanEnabled !== false
  } catch (error) {
    console.error('加载工单开关状态失败:', error)
  }
}

// 切换工单开关
const toggleGongdanSwitch = async () => {
  try {
    const newStatus = await alarmSwitchApi.toggleGongdan()
    gongdanSwitchEnabled.value = newStatus.data
    ElMessage.success(newStatus.data ? '工单已启用' : '工单已禁用')
  } catch (error) {
    console.error('切换工单开关失败:', error)
    ElMessage.error('切换失败')
  }
}

// 加载 BOMC 对接配置（地址、操作人）
const loadBomcConfig = async () => {
  try {
    const res = await gongdanIntegrationApi.getBomcConfig()
    bomcConfig.value = res.data || {}
  } catch (error) {
    console.error('加载 BOMC 对接配置失败:', error)
  }
}

// BOMC 对接参数：界面可直接双击修改的字段
const bomcConfigItems = [
  { key: 'baseUrl', label: 'BOMC 服务地址' },
  { key: 'opPerson', label: '操作人姓名' },
  { key: 'opPersonNo', label: '操作人工号' },
  { key: 'opDepart', label: '操作人部门' },
  { key: 'opContact', label: '联系方式' },
  { key: 'executeUser', label: '告警处理人（BOMC账号）' }
]

const editingConfigKey = ref('')
const editingConfigValue = ref('')
const savingConfig = ref(false)
const configInputRef = ref(null)

// 双击数值 → 切换为输入框并聚焦
const startEditConfig = (key) => {
  if (savingConfig.value) return
  editingConfigKey.value = key
  editingConfigValue.value = bomcConfig.value[key] || ''
  nextTick(() => {
    // v-for 内的 ref 在 Vue3 中会收集成数组，这里两种形态都兼容
    const target = Array.isArray(configInputRef.value) ? configInputRef.value[0] : configInputRef.value
    if (target && target.focus) target.focus()
  })
}

const cancelEditConfig = () => {
  editingConfigKey.value = ''
  editingConfigValue.value = ''
}

// 保存：留空 = 恢复平台默认值；成功后以服务端返回为准刷新
const saveConfigItem = async (key) => {
  // 回车保存后输入框会失焦再触发一次，这里用 key 是否仍处于编辑态做去重
  if (editingConfigKey.value !== key) return
  const value = (editingConfigValue.value || '').trim()
  if (key === 'baseUrl' && value && !/^https?:\/\//i.test(value)) {
    ElMessage.warning('BOMC 服务地址必须以 http:// 或 https:// 开头')
    return
  }
  if (value === (bomcConfig.value[key] || '')) {
    cancelEditConfig()
    return
  }
  savingConfig.value = true
  editingConfigKey.value = ''
  try {
    const res = await gongdanIntegrationApi.updateBomcConfig({ [key]: value })
    bomcConfig.value = res.data || bomcConfig.value
    ElMessage.success(value ? '已保存' : '已恢复平台默认值')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '保存失败')
    loadBomcConfig()
  } finally {
    savingConfig.value = false
    editingConfigValue.value = ''
  }
}

// 加载系统列表（新建/编辑工单选择所属系统）
const loadSystemOptions = async () => {
  try {
    const res = await systemApi.listAll()
    systemOptions.value = res.data || []
  } catch (error) {
    console.error('加载系统列表失败:', error)
  }
}

// 排序参数
const sortParams = ref({ sortBy: 'gongdanTime', sortOrder: 'desc' })

// 列显示设置
const STORAGE_KEY = 'gongdan_table_preferences'
const columnOptions = ref([
  { prop: 'gongdanTime', label: '工单时间' },
  { prop: 'gongdanId', label: '工单编号' },
  { prop: 'externalId', label: 'BOMC工单号' },
  { prop: 'gongdanTitle', label: '工单标题' },
  { prop: 'alarmObject', label: '告警对象' },
  { prop: 'alarmLevel', label: '告警级别' },
  { prop: 'alarmPriority', label: '优先级' },
  { prop: 'externalStatus', label: 'BOMC当前节点' },
  { prop: 'gongdanType', label: '类型' },
  { prop: 'currentHandler', label: '处理人' },
  { prop: 'closedStatus', label: '状态' },
  { prop: 'synced', label: 'BOMC同步' }
])

// 默认显示所有列
const defaultVisibleColumns = columnOptions.value.map(c => c.prop)
const visibleColumns = ref([...defaultVisibleColumns])

// 统计面板数据
const gongdanStats = ref({
  totalGongdanCount: 0,
  todayCount: 0,
  weekCount: 0,
  openCount: 0,
  closedCount: 0,
  pendingSyncCount: 0
})

// 趋势图相关
const trendChartDom = ref(null)
const trendChart = ref(null)
const trendDimension = ref('month')
const trendType = ref('all')
const trendTimeRange = ref(null)
const trendData = ref([])
const originalXAxisData = ref([])  // 保存原始x轴数据用于点击处理
const chartDrillDownLabel = ref('')

// 检查列是否隐藏
const isColumnHidden = (prop) => {
  return !visibleColumns.value.includes(prop)
}

// 从localStorage加载偏好设置
const loadPreferences = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const prefs = JSON.parse(saved)
      if (prefs.visibleColumns && prefs.visibleColumns.length > 0) {
        visibleColumns.value = prefs.visibleColumns
      }
    }
  } catch (e) {
    console.error('加载偏好设置失败:', e)
  }
}

// 保存偏好设置到localStorage
const savePreferences = () => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      visibleColumns: visibleColumns.value
    }))
  } catch (e) {
    console.error('保存偏好设置失败:', e)
  }
}

// 列显示变化
const handleColumnChange = () => {
  savePreferences()
}

// 表头右键菜单相关
const showHeaderContextMenu = ref(false)
const contextMenuPosition = ref({ x: 0, y: 0 })
const contextMenuColumn = ref(null)

const handleHeaderContextMenu = (column, event) => {
  event.preventDefault()
  if (!column.property) return
  contextMenuColumn.value = column
  contextMenuPosition.value = { x: event.clientX, y: event.clientY }
  showHeaderContextMenu.value = true
}

const hideColumnFromMenu = () => {
  if (!contextMenuColumn.value) return
  const prop = contextMenuColumn.value.property
  const idx = visibleColumns.value.indexOf(prop)
  if (idx > -1) {
    visibleColumns.value.splice(idx, 1)
    savePreferences()
  }
  showHeaderContextMenu.value = false
}

const closeContextMenu = () => {
  showHeaderContextMenu.value = false
}

// 处理排序变化
const handleSortChange = ({ prop, order }) => {
  if (order === 'descending') {
    sortParams.value.sortOrder = 'desc'
  } else if (order === 'ascending') {
    sortParams.value.sortOrder = 'asc'
  }
  sortParams.value.sortBy = prop || 'gongdanTime'
  loadData()
}

// 选中行
const selectedRows = ref([])

// 选中行变化
const handleSelectionChange = (selection) => {
  selectedRows.value = selection
}

// 批量删除
const handleBatchDelete = async () => {
  if (!selectedRows.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedRows.value.length} 条本地工单吗？仅删除本平台数据，不影响 BOMC 侧工单。`, '删除（本地）', {
      type: 'warning'
    })
    for (const row of selectedRows.value) {
      await gongdanApi.delete(row.id)
    }
    ElMessage.success('删除成功')
    selectedRows.value = []
    loadData()
    loadTrendData()      // 刷新工单趋势图
    loadGongdanStats()   // 刷新左侧统计卡
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

const dialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentGongdanId = ref(null)

const emptyForm = () => ({
  id: null,
  gongdanId: '',
  gongdanTitle: '',
  gongdanContent: '',
  gongdanType: '手动',
  currentStep: '',
  currentHandler: '',
  closedStatus: 0,
  systemId: null,
  categoryId: null,
  metricId: null,
  alarmObject: '',
  alarmLevel: 2,
  alarmPriority: '',
  alarmImpact: '',
  alarmCount: 1,
  alarmFirstTime: null,
  alarmLastTime: null,
  responsiblePerson: '',
  secondResponsible: '',
  executeUser: ''
})

const form = ref(emptyForm())

const loadData = async () => {
  tableLoading.value = true
  try {
    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      ...sortParams.value
    }
    // 处理日期范围
    if (queryParams.value.gongdanTimeRange && queryParams.value.gongdanTimeRange.length === 2) {
      params.gongdanTimeStart = queryParams.value.gongdanTimeRange[0]
      params.gongdanTimeEnd = queryParams.value.gongdanTimeRange[1]
    }
    // 添加其他查询参数
    if (queryParams.value.gongdanId) params.gongdanId = queryParams.value.gongdanId
    if (queryParams.value.externalId) params.externalId = queryParams.value.externalId
    if (queryParams.value.gongdanTitle) params.gongdanTitle = queryParams.value.gongdanTitle
    if (queryParams.value.alarmObject) params.alarmObject = queryParams.value.alarmObject
    if (queryParams.value.gongdanType) params.gongdanType = queryParams.value.gongdanType
    if (queryParams.value.alarmLevel !== null && queryParams.value.alarmLevel !== '') params.alarmLevel = queryParams.value.alarmLevel
    if (queryParams.value.synced !== null && queryParams.value.synced !== '') params.synced = queryParams.value.synced
    if (queryParams.value.closedStatus !== null && queryParams.value.closedStatus !== '') params.closedStatus = queryParams.value.closedStatus
    const res = await gongdanApi.list(params)
    tableData.value = res.data.content || []
    pagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载工单列表失败:', error)
  } finally {
    tableLoading.value = false
  }
}

// 重置工单筛选
const resetGongdanFilters = () => {
  quickFilterLabel.value = ''
  queryParams.value = {
    closedStatus: null,
    synced: null,
    gongdanType: null,
    alarmLevel: null,
    gongdanId: '',
    externalId: '',
    gongdanTitle: '',
    alarmObject: '',
    gongdanTimeRange: null
  }
  pagination.value.page = 1
  loadData()
}

// 统计卡片点击：按时间范围筛选
const quickFilterLabel = ref('')
const quickFilterResult = ref(null)
const showQuickFilterResult = async (label) => {
  quickFilterLabel.value = label
  await nextTick()
  quickFilterResult.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

const filterByTimeRange = (range) => {
  const now = new Date()
  const fmt = (d) => d.toISOString().substring(0, 10)
  if (range === 'today') {
    queryParams.value.gongdanTimeRange = [fmt(now), fmt(now)]
  } else if (range === 'week') {
    const weekAgo = new Date(now.getTime() - 7 * 86400000)
    queryParams.value.gongdanTimeRange = [fmt(weekAgo), fmt(now)]
  } else if (range === 'month') {
    const monthAgo = new Date(now.getTime() - 30 * 86400000)
    queryParams.value.gongdanTimeRange = [fmt(monthAgo), fmt(now)]
  }
  queryParams.value.closedStatus = null
  queryParams.value.synced = null
  pagination.value.page = 1
  showQuickFilterResult(({ today: '今日新增工单', week: '本周新增工单', month: '近 30 天工单' })[range])
  loadData()
}

// 统计卡片点击：按关单状态筛选
const filterByClosedStatus = (status) => {
  queryParams.value.closedStatus = status === -1 ? null : status
  queryParams.value.gongdanTimeRange = null
  queryParams.value.synced = null
  pagination.value.page = 1
  showQuickFilterResult(status === -1 ? '全部关单状态' : status === 0 ? '未关单工单' : '已关单工单')
  loadData()
}

// 统计卡片点击：筛选未成功推送 BOMC 的工单
const filterBySynced = (synced) => {
  queryParams.value.synced = synced
  queryParams.value.closedStatus = null
  queryParams.value.gongdanTimeRange = null
  pagination.value.page = 1
  showQuickFilterResult('未成功推送 BOMC 的工单')
  loadData()
}

const handleAdd = () => {
  form.value = emptyForm()
  // 手动创建时按规范约定自动带上前缀，用户可自行修改
  form.value.gongdanTitle = '【指标体系平台】'
  dialogVisible.value = true
}

const handleEdit = (row) => {
  form.value = {
    ...emptyForm(),
    ...row
  }
  dialogVisible.value = true
}

// 选择系统后自动补全告警对象前缀
const handleSystemChange = (systemId) => {
  const system = systemOptions.value.find(item => item.id === systemId)
  if (!system) return
  if (!form.value.alarmObject) {
    form.value.alarmObject = system.systemName
  }
}

const handleViewDetail = (row) => {
  currentGongdanId.value = row.id
  detailDialogVisible.value = true
}

const saveData = async () => {
  try {
    if (!form.value.gongdanTitle) {
      ElMessage.warning('请填写工单标题')
      return
    }
    // 关单状态不在弹窗里编辑，但仍显式回传，避免"不带参数"引起的歧义：
    // 新建固定为「未关单」，编辑保持列表里的原值（后端按非空字段合并，值相同即不变更）
    const payload = { ...form.value }
    if (payload.closedStatus === undefined || payload.closedStatus === null) {
      payload.closedStatus = 0
    }
    let res
    if (payload.id) {
      res = await gongdanApi.update(payload.id, payload)
    } else {
      res = await gongdanApi.create(payload)
    }
    const saved = res.data || {}
    if (payload.id) {
      ElMessage.success('保存成功')
    } else if (saved.externalId) {
      ElMessage.success(`创建成功，已推送 BOMC（工单号 ${saved.externalId}）`)
      await refreshStatusQuietly([saved.id]) // 新建后立即回填 BOMC 当前节点
    } else {
      ElMessage.warning(`工单已创建，但推送 BOMC 失败：${saved.externalSyncMsg || '请稍后重试'}`)
    }
    dialogVisible.value = false
    loadData()
    loadGongdanStats()
    loadTrendData()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

// 手动推送工单到 BOMC
const handlePush = async (row, force) => {
  try {
    if (force) {
      await ElMessageBox.confirm(
        `该工单已推送过 BOMC（工单号 ${row.externalId}），重新推送会在 BOMC 再建一条工单，确定继续吗？`,
        '提示', { type: 'warning' })
    }
    const res = await gongdanIntegrationApi.push(row.id, !!force)
    const serial = res.data && res.data.bomcSerialNo
    ElMessage.success(serial ? `推送成功，BOMC 工单号 ${serial}` : '推送成功')
    await refreshStatusQuietly([row.id]) // 推送后立即回填 BOMC 当前节点
    loadData()
    loadGongdanStats()
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || '推送失败'
    ElMessage.error(msg)
    loadData()
  }
}

// 重新推送 BOMC：先比对标题/内容与上次推送快照，再新建 BOMC 工单 + 本地新工单（原工单不自动撤回）
const handleRepush = async (row) => {
  const hasSnapshot = row.pushedTitle != null || row.pushedContent != null
  const modified = hasSnapshot
    && ((row.gongdanTitle || '') !== (row.pushedTitle || '') || (row.gongdanContent || '') !== (row.pushedContent || ''))
  let message
  if (!hasSnapshot) {
    message = '该工单缺少上次推送内容记录（历史工单），重新推送会创建一条新的工单，请确认。'
  } else if (modified) {
    message = '检测到修改了工单内容，重新推送会创建一条新的工单，推送成功后建议撤回此工单？防止工单推送冗余'
  } else {
    message = '检测到工单内容没有修改，重新推送会创建一条一样的工单，请确认。'
  }
  try {
    await ElMessageBox.confirm(message, '重新推送 BOMC', {
      type: 'warning',
      confirmButtonText: '继续推送',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    const res = await gongdanIntegrationApi.repush(row.id)
    const d = res.data || {}
    ElMessage.success(d.message || `推送成功，已新建工单 ${d.newGongdanNo || ''}（BOMC 工单号 ${d.bomcSerialNo || ''}）`)
    if (modified) {
      ElMessage.info('如需撤回原工单，请在原工单「更多 → 撤销工单」操作')
    }
    await refreshStatusQuietly(d.newLocalId ? [d.newLocalId] : []) // 新工单立即回填 BOMC 当前节点
    loadData()
    loadGongdanStats()
    loadTrendData()
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || '重新推送失败'
    ElMessage.error(msg)
    loadData()
  }
}

// 归档 / 撤销工单：弹窗输入原因（→ BOMC opin）
const openActionDialog = (row, type) => {
  actionDialog.value = {
    visible: true,
    type,
    row,
    reason: type === 'quash' ? '工单撤销' : '',
    submitting: false
  }
}

const submitActionDialog = async () => {
  const { type, row } = actionDialog.value
  const reason = (actionDialog.value.reason || '').trim()
  if (type === 'archive' && !reason) {
    ElMessage.warning('请填写归档原因')
    return
  }
  actionDialog.value.submitting = true
  try {
    const api = type === 'archive' ? gongdanIntegrationApi.archive : gongdanIntegrationApi.quash
    await api(row.id, reason)
    ElMessage.success(type === 'archive'
      ? '归档成功，本地已标记「已归档」（不删数据）'
      : '撤销成功，本地已标记「已撤销」（不删数据）')
    actionDialog.value.visible = false
    loadData()
    loadGongdanStats()
    loadTrendData()
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || (type === 'archive' ? '归档失败' : '撤销失败')
    ElMessage.error(msg)
  } finally {
    actionDialog.value.submitting = false
  }
}

// 补推所有未成功推送的工单
const handlePushPending = async () => {
  try {
    const res = await gongdanIntegrationApi.pushPending()
    ElMessage.success(`补推完成，成功 ${res.data ?? 0} 条`)
    loadData()
    loadGongdanStats()
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || '补推失败'
    ElMessage.error(msg)
  }
}

// 静默刷新指定工单的 BOMC 状态（推送/重推/新建后立即回填节点，失败不打断主流程）
const refreshStatusQuietly = async (ids) => {
  if (!ids || !ids.length) return
  try {
    await gongdanIntegrationApi.refreshStatus(ids)
  } catch (e) {
    console.warn('刷新 BOMC 状态失败:', e)
  }
}

// 列表页「刷新状态」：只刷当前页工单（后端逐个查 BOMC 轨迹并回填，避免全量请求过大）
const statusRefreshing = ref(false)
const handleRefreshStatus = async () => {
  if (statusRefreshing.value) return
  const ids = tableData.value.map((r) => r.id)
  if (!ids.length) {
    ElMessage.info('当前页没有工单')
    return
  }
  statusRefreshing.value = true
  try {
    const res = await gongdanIntegrationApi.refreshStatus(ids)
    const d = res.data || {}
    const failCount = (d.failures || []).length
    const parts = [`查询 ${d.total ?? 0} 条`, `成功 ${d.success ?? 0}`]
    if (failCount) parts.push(`失败 ${failCount}`)
    if (d.skipped) parts.push(`跳过 ${d.skipped}（未推送或已撤销/归档）`)
    ElMessage.success('刷新完成：' + parts.join('，'))
    loadData()
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || '刷新失败'
    ElMessage.error(msg)
  } finally {
    statusRefreshing.value = false
  }
}

// 行内“更多”菜单
const handleRowCommand = (command, row) => {
  switch (command) {
    case 'edit':
      handleEdit(row)
      break
    case 'push':
      handlePush(row, false)
      break
    case 'repush':
      handleRepush(row)
      break
    case 'quash':
      openActionDialog(row, 'quash')
      break
    case 'archive':
      openActionDialog(row, 'archive')
      break
    case 'delete':
      handleDelete(row)
      break
    default:
      break
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除本地工单「${row.gongdanTitle}」吗？仅删除本平台数据，不影响 BOMC 侧工单。`,
      '删除（本地）', { type: 'warning' })
    await gongdanApi.delete(row.id)
    ElMessage.success('已删除本地工单')
    loadData()
    loadGongdanStats()
    loadTrendData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

// 加载统计数据
const loadGongdanStats = async () => {
  try {
    const res = await gongdanApi.statistics()
    gongdanStats.value = res.data || {}
  } catch (error) {
    console.error('加载工单统计失败:', error)
  }
}

// 趋势图数据加载
const loadTrendData = async () => {
  try {
    const params = { dimension: trendDimension.value, type: trendType.value }
    if (trendTimeRange.value && trendTimeRange.value.length === 2) {
      params.startDate = trendTimeRange.value[0]
      params.endDate = trendTimeRange.value[1]
    }
    const res = await gongdanApi.getTrend(params)
    trendData.value = res.data || []
    nextTick(() => drawTrendChart())
  } catch (error) {
    console.error('加载趋势数据失败:', error)
  }
}

// 绘制趋势图
const drawTrendChart = () => {
  if (!trendChartDom.value) return

  // 首次挂载时工单页位于 el-tabs 的 pane 中，nextTick 不保证 pane 已完成布局。
  // ECharts 在 0 宽度容器上初始化会留下无效的 grid 坐标系，点击下钻也无法命中。
  const { clientWidth, clientHeight } = trendChartDom.value
  if (clientWidth === 0 || clientHeight === 0) {
    setupTrendChartResizeObserver()
    return
  }

  const data = trendData.value
  const isToday = trendDimension.value === 'today'
  // today维度只显示时间部分，如 "04:00"
  const xAxisData = data.map(d => isToday ? d.time.substring(11, 16) : d.time)
  // week/month 维度简化标签（去掉公共前缀），today/year 不简化
  const isNeedSimplify = trendDimension.value === 'week' || trendDimension.value === 'month'
  const simplifiedXAxisData = isNeedSimplify
    ? simplifyTimeLabels(xAxisData, trendDimension.value)
    : xAxisData
  const seriesData = data.map(d => d.count)

  // 保存原始数据用于点击处理
  originalXAxisData.value = xAxisData

  if (!trendChart.value) {
    trendChart.value = echarts.init(trendChartDom.value)
    trendChart.value.getZr().on('click', (e) => {
      const pointInPixel = [e.offsetX, e.offsetY]
      if (trendChart.value.containPixel('grid', pointInPixel)) {
        const pointInGrid = trendChart.value.convertFromPixel('grid', pointInPixel)
        const dataIndex = Math.round(pointInGrid[0])
        if (dataIndex >= 0 && dataIndex < originalXAxisData.value.length) {
          handleChartClick({ name: originalXAxisData.value[dataIndex] })
        }
      }
    })
  }

  const chartTheme = readWorkspaceChartTheme(trendChartDom.value)
  const option = {
    backgroundColor: chartTheme.surface,
    textStyle: { color: chartTheme.text },
    tooltip: {
      trigger: 'axis',
      show: true,
      showContent: true,
      confine: true,
      position: function (point, params, dom, rect, size) {
        return [point[0], '10%']
      },
      axisPointer: {
        type: 'line',
        lineStyle: { color: chartTheme.primary, width: 1, type: 'dashed' }
      },
      formatter: (params) => {
        const p = params[0]
        return `${p.name}<br/>工单数量: <b>${p.value}</b><br/><span style="font-size:10px;color:#909399">点击筛选该时段</span>`
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '8%',
      top: '20px',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: simplifiedXAxisData,
      axisLabel: {
        fontSize: 10,
        // year 维度倾斜 30° 避免 12 个月份标签互相挤压
        rotate: trendDimension.value === 'year' ? 30 : 0,
        interval: data.length > 12 ? 1 : 0,  // 数据多时每隔一个显示
        // 兜底：确保标签统一格式化为 YYYY-MM（即使后端返回了其他格式）
        formatter: (value) => {
          if (typeof value !== 'string') return value
          const yyyymm = value.match(/^\d{4}-\d{2}/)
          if (yyyymm) return yyyymm[0]
          // 处理 "2024-01-01 ~ 2024-01-31" 范围格式
          if (value.includes('~')) return value.split('~')[0].trim().substring(0, 7)
          return value
        }
      }
    },
    yAxis: {
      type: 'value',
      min: 0,
      splitNumber: 4
    },
    series: [{
      type: 'line',
      data: seriesData,
      smooth: true,
      label: {
        show: true,
        position: 'top',
        fontSize: 10,
        formatter: '{c}'
      },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: chartTheme.areaStart },
          { offset: 1, color: chartTheme.areaEnd }
        ])
      },
      lineStyle: { color: chartTheme.primary, width: 2 },
      itemStyle: { color: chartTheme.primary }
    }]
  }

  trendChart.value.setOption(option, true)  // notMerge=true 避免旧数据残留
  setupTrendChartResizeObserver()
}

// 图表点击下钻
const handleChartClick = (params) => {
  const { name } = params
  if (!name) return

  if (trendDimension.value === 'today') {
    // today 维度: name 现在是 "04:00" 格式，需要拼接今天日期
    const today = new Date().toISOString().substring(0, 10)
    const fullTime = `${today} ${name}:00`
    queryParams.value.gongdanTimeRange = [fullTime, fullTime.substring(0, 13) + ':59:59']
  } else if (trendDimension.value === 'year') {
    const [year, month] = name.split('-')
    const lastDay = new Date(parseInt(year), parseInt(month), 0).getDate()
    queryParams.value.gongdanTimeRange = [`${name}-01`, `${name}-${String(lastDay).padStart(2, '0')}`]
  } else {
    queryParams.value.gongdanTimeRange = [name, name]
  }
  chartDrillDownLabel.value = name
  loadData()
}

// 清除下钻筛选
const clearDrillDown = () => {
  chartDrillDownLabel.value = ''
  queryParams.value.gongdanTimeRange = null
  loadData()
}

// 获取空数据提示文本
const getEmptyText = () => {
  const dimensionMap = {
    'today': '今天没有工单',
    'week': '本周没有工单',
    'month': '本月没有工单',
    'year': '今年没有工单'
  }
  return dimensionMap[trendDimension.value] || '暂无数据'
}

// 监听窗口resize
const handleResize = () => {
  if (trendChart.value) {
    trendChart.value.resize()
  }
}

// 监听图表容器尺寸变化（捕获 el-col 宽度变化，避免初次渲染宽度异常）
let trendChartResizeObserver = null
const setupTrendChartResizeObserver = () => {
  if (trendChartResizeObserver || !trendChartDom.value) return
  trendChartResizeObserver = new ResizeObserver(() => {
    if (trendChart.value) {
      trendChart.value.resize()
    } else if (trendChartDom.value?.clientWidth > 0 && trendChartDom.value?.clientHeight > 0) {
      drawTrendChart()
    }
  })
  trendChartResizeObserver.observe(trendChartDom.value)
}

onMounted(() => {
  loadPreferences()
  loadGongdanSwitchStatus()
  loadSystemOptions()
  loadBomcConfig()
  // 检查URL查询参数
  if (route.query.closedStatus !== undefined) {
    queryParams.value.closedStatus = Number(route.query.closedStatus)
    router.replace({ path: '/gongdan' })
  }
  loadData()
  loadGongdanStats()
  loadTrendData()
  window.addEventListener('resize', handleResize)
  // 监听路由变化
  watch(() => route.query, (newQuery) => {
    if (newQuery.closedStatus !== undefined) {
      queryParams.value.closedStatus = Number(newQuery.closedStatus)
      loadData()
      router.replace({ path: '/gongdan' })
    }
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (trendChartResizeObserver) {
    trendChartResizeObserver.disconnect()
    trendChartResizeObserver = null
  }
  if (trendChart.value) {
    trendChart.value.dispose()
  }
})
</script>

<style scoped>
.gongdan-page {
  padding: 0;
}

/* 操作列：详情 / 更多 同一水平线对齐（el-dropdown 与 el-button 混排会错位） */
.op-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.op-actions .el-dropdown {
  vertical-align: middle;
}

.op-actions :deep(.el-button) {
  vertical-align: middle;
  margin-left: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.card-header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.toolbar {
  margin-bottom: 15px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.toolbar .el-select {
  width: 100%;
  max-width: 150px;
}

/* 统计卡片 */

/* 配置区域 */
.config-section {
  padding: 10px 0;
}
.config-section h4 {
  margin: 0 0 20px 0;
  font-size: 16px;
  font-weight: 500;
  color: var(--ui-heading);
}
.switch-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--ui-page);
  border-radius: 8px;
  margin-bottom: 16px;
}
.switch-hint {
  font-size: 12px;
  color: var(--ui-muted);
}

.config-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--ui-muted);
  line-height: 1.8;
}

/* BOMC 对接参数：双击可改 */
.editable-value {
  min-height: 24px;
  cursor: text;
  border-radius: 3px;
  padding: 0 2px;
}

.editable-value:hover .editable-text {
  background: var(--ui-primary-soft);
  box-shadow: 0 0 0 2px var(--ui-primary-soft);
}

.editable-text {
  display: inline-block;
  border-radius: 3px;
  word-break: break-all;
}

/* 开关卡片 */
.switch-card {
  margin-top: 8px;
  padding: 10px;
  background: var(--ui-surface);
  border-radius: 6px;
  border: 1px solid #e8eef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.switch-label {
  font-size: 12px;
  color: var(--ui-text);
  font-weight: 500;
}

/* 趋势图区域 */
.trend-area {
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  padding: 12px;
}

.trend-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.trend-tabs {
  display: flex;
  gap: 8px;
}

.trend-filters {
  display: flex;
  gap: 8px;
  align-items: center;
}

.trend-chart {
  height: 120px;
  width: 100%;
}

.trend-chart-wrapper {
  position: relative;
  min-height: 120px;
}

.trend-empty-overlay {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ui-muted);
  font-size: 13px;
  pointer-events: none;
  background: rgba(255, 255, 255, 0.92);
  padding: 8px 16px;
  border-radius: 4px;
  z-index: 1;
}

.trend-range-hint {
  font-size: 11px;
  color: var(--ui-muted);
  text-align: center;
  margin-bottom: 4px;
}

.trend-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 120px;
  color: var(--ui-muted);
  gap: 8px;
}

.drill-down-tip {
  font-size: 12px;
  color: var(--ui-muted);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 桌面端表格默认显示 */
.desktop-table {
  display: block;
}

/* 表格工具栏 */
.table-toolbar {
  margin-bottom: 15px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.selected-count {
  color: var(--ui-muted);
  font-size: 13px;
}

.toolbar-spacer {
  flex: 1;
}

/* 移动端卡片默认隐藏 */
.mobile-cards {
  display: none;
}

@media screen and (max-width: 768px) {
  .toolbar {
    flex-direction: column;
  }

  .toolbar .el-select {
    width: 100% !important;
    max-width: none;
  }

  /* 移动端切换为卡片布局 */
  .desktop-table {
    display: none;
  }

  .mobile-cards {
    display: block;
  }

  .gongdan-card {
    margin-bottom: 12px;
  }

  .gongdan-card .card-content {
    font-size: 13px;
  }

  .gongdan-card .card-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 0;
    gap: 6px;
  }

  .gongdan-card .card-row .time {
    color: var(--ui-muted);
    font-size: 12px;
  }

  .gongdan-card .card-row .label {
    color: var(--ui-muted);
    flex-shrink: 0;
  }

  .gongdan-card .card-row .value {
    color: var(--ui-heading);
    text-align: right;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 60%;
  }

  .gongdan-card .card-footer {
    margin-top: 12px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
}

/* 筛选工具栏 */

/* 表头背景色 */
:deep(.el-table th.el-table__cell) {
  background-color: var(--ui-page);
}

/* 列设置样式 */
.column-toggle-group {
  display: flex;
  flex-direction: column;
}

.column-toggle-item {
  margin-right: 0;
  padding: 4px 0;
}

/* 弹窗底部提示 */
.dialog-tip {
  font-size: 12px;
  color: var(--ui-muted);
  padding-left: 120px;
  line-height: 1.6;
}

/* 表头右键菜单 */
.header-context-menu {
  position: fixed;
  z-index: 9999;
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 4px 0;
  min-width: 120px;
}

.context-menu-item {
  padding: 8px 16px;
  font-size: 13px;
  color: var(--ui-text);
  cursor: pointer;
  white-space: nowrap;
}

.context-menu-item:hover {
  background-color: var(--ui-page);
  color: var(--ui-primary);
}

.context-menu-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 9998;
}

/* 工单ID列：el-button(link) 默认 inline-flex + white-space:nowrap，
   会阻断 wrap-cell 的换行效果。这里强制按钮内容随单元格宽度自动换行 */
.gongdan-page :deep(.el-table td.wrap-cell .cell .el-button.gongdan-id-link) {
  white-space: normal;
  word-break: break-all;
  text-align: left;
  padding: 0;
  display: inline;
}
</style>

<style scoped src="@/views/gongdan/prototype-theme.css"></style>
