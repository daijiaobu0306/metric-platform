<template>
  <div class="report-container workspace-theme workspace-management report-prototype" :class="{ 'weekly-report-page': activeReportType === 'weekly' }">
    <header class="ledger-page-header"><div class="ledger-page-heading"><h1>运营报表</h1><p>汇聚核心业务与服务运行指标报表数据</p></div><el-button class="ledger-header-button" @click="showKeywordDialog = true"><el-icon><Setting /></el-icon>CRM 前台自定义返回信息关键词配置</el-button></header>
    <div class="report-shell">
      <!-- 周报/月报/实时指标 Tab -->
      <el-tabs v-model="activeReportType" class="report-type-tabs workspace-tabs management-tabs">
        <!-- 实时指标 Tab -->
        <el-tab-pane label="实时指标" name="realtime"><template #label><WorkspaceTabLabel icon="TrendCharts">实时指标</WorkspaceTabLabel></template>
          <div v-if="activeReportType === 'realtime'" class="content-area">
            <!-- 左侧指标大类垂直Tab -->
            <ReportCategoryNavigation v-model:category="activeCategory" v-model:subcategory="selectedSubCategory" :categories="categories" :sub-categories="subCategories" :show-subcategories="activeCategory !== 'system' && activeCategory !== 'dataQuality'" @category-change="handleCategoryChange" @subcategory-change="handleSubCategoryChange" @refresh="() => loadRealtimeData(true)" />
            <!-- 右侧数据表格 -->
            <div class="data-area">
              <SubcategoryWorkspace layout="tabs" :enabled="activeCategory === 'system'" :items="subCategories" v-model="selectedSubCategory" @change="handleSubCategoryChange">
              <!-- 实时指标筛选区域 -->
              <WorkspaceContextTrail :items="[categories.find(item => item.code === activeCategory)?.name, subCategories.find(item => item.code === selectedSubCategory)?.name]" />
              <ReportRealtimeToolbar v-model:status="filterStatus" v-model:time="filterTimeRange" v-model:date-range="reportDateRange" v-model:name="filterMetricName" v-model:columns="visibleRealtimeColumns" :active-category="activeCategory" :normal-count="normalCount" :warning-count="warningCount" :realtime-column-options="realtimeColumnOptions" @search="searchRealtimeData" @reset="resetFilters" @columns-change="handleRealtimeColumnChange" @normal="handleStatusCardClick('正常')" @abnormal="handleAbnormalCardClick()" />
              <el-alert v-if="activeCategory === 'resourceInput'" title="按所选数据日期统计（含起止日）：需求数量、总工时分别求和；平均工时＝区间总工时÷需求数量。未投递日期不补零。" type="info" :closable="false" />

              <div v-if="activeCategory === 'satisfactionMetrics'" class="satisfaction-meta">采集数据 · 最近数据时间：{{ satisfactionDate || '—' }} · {{ satisfactionAvailable }} / {{ satisfactionTotal }} 项有值 · 按各指标原始数据时间展示；健康度未配置</div>
              <!-- 指标小类横向Tab -->
              <!-- 实时指标数据表格（统一格式） -->
              <div class="desktop-table">
                <el-table v-sticky-header v-mirror-scroll :data="paginatedTableData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true" @header-contextmenu="handleHeaderContextMenu" @sort-change="handleSortChange">
                  <el-table-column v-if="activeCategory !== 'resourceInput' && !isRealtimeColumnHidden('dataTime')" prop="dataTime" label="数据日期" width="180" sortable="custom" class-name="workspace-time-cell"><template #default="{ row }"><span class="workspace-time-value"><el-icon><Clock /></el-icon>{{ formatDataTime(row.dataTime) || '—' }}</span></template></el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('metricName')" prop="metricName" label="指标名称" min-width="200" class-name="wrap-cell"><template #default="{ row }"><span class="metric-name-link" @click="handleMetricNameClick(row)">{{ row.metricName || '—' }}</span></template></el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('subCategoryName')" prop="subCategoryName" label="指标小类" width="120" class-name="wrap-cell"><template #default="{ row }"><span class="workspace-category-chip">{{ formatResourceSubcategory(row.subCategoryName) || '—' }}</span></template></el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('metricValue')" prop="metricValue" label="指标值" width="100" sortable="custom">
                    <template #default="{ row }">
                      <span :class="row.isExceed === 1 ? 'text-danger' : row.isExceed === 0 ? 'text-normal' : ''">
                        <WorkspaceInlineCode :value="row.metricValue" empty-text="—" style="color:inherit" />
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('alarmCondition')" prop="alarmCondition" label="告警条件" width="80">
                    <template #default="{ row }">
                      {{ row.alarmCondition || '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('alarmThreshold')" prop="alarmThreshold" label="告警阈值" width="100">
                    <template #default="{ row }">
                      <span :title="row.alarmRuleSummary || row.thresholdNote || ''" :class="{ 'clickable-value': row.metricId != null }" @click="row.metricId != null && handleThresholdClick(row)">
                        {{ row.alarmThreshold != null ? row.alarmThreshold : '未配置' }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="activeCategory === 'satisfactionMetrics'" prop="alarmRuleSummary" label="完整告警规则" min-width="230" />
                  <el-table-column v-if="!isRealtimeColumnHidden('exceedCount')" prop="exceedCount" label="连续超限次数" width="110">
                    <template #default="{ row }">
                      <span :class="row.exceedCount > 0 ? 'text-danger' : ''">
                        {{ row.exceedCount == null && ['satisfactionMetrics', 'resourceInput', 'businessValue'].includes(activeCategory) ? '—' : row.exceedCount || 0 }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('status')" prop="status" label="状态" width="80">
                    <template #default="{ row }">
                      <WorkspaceStatusBadge
                        v-if="row.status"
                        size="small"
                        :tone="getStatusType(row.status)"
                        :class="{ 'clickable-tag': row.status === '工单' }"
                        @click="row.status === '工单' && handleGongdanClick(row)"
                      >
                        {{ row.status || '正常' }}
                      </WorkspaceStatusBadge>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="!isRealtimeColumnHidden('unit')" prop="unit" label="单位" width="60" />
                </el-table>
              </div>

              <div v-if="['satisfactionMetrics', 'resourceInput', 'businessValue'].includes(activeCategory)" class="mobile-cards">
                <el-card v-for="row in paginatedTableData" :key="row.metricCode" class="data-card" shadow="never"><div>{{ row.metricName }} · {{ formatResourceSubcategory(row.subCategoryName) }}</div><div>{{ row.metricValue ?? '—' }} {{ row.unit }}</div><div>{{ activeCategory === 'resourceInput' ? row.status : (row.dataTime ? row.dataTime + ' · ' : '') + row.status }}</div></el-card>
              </div>
              <!-- 分页 -->
              <WorkspacePagination v-model:page="pagination.page" v-model:size="pagination.size" :total="filteredTableData.length" :page-sizes="[10, 20, 50, 100]" />
              </SubcategoryWorkspace>

              <!-- 指标检索：按指标 + 数据时间区间查采集批次明细 -->
              <div class="metric-search-panel">
                <div class="metric-search-title">指标检索</div>
                <div class="metric-search-filters">
                  <span class="metric-search-category">指标大类：{{ searchCategoryName }}</span>
                  <el-select v-if="searchSubCategories.length > 1" v-model="searchSubCategory" placeholder="指标小类" size="small" style="width:170px" @change="onSearchSubCategoryChange">
                    <el-option v-for="s in searchSubCategories" :key="s" :label="s" :value="s" />
                  </el-select>
                  <el-select v-model="searchMetricId" placeholder="指标" size="small" style="width:200px" filterable>
                    <el-option v-for="m in searchMetrics" :key="m.metricId" :label="m.metricName" :value="m.metricId" />
                  </el-select>
                  <el-date-picker v-model="searchTimeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" size="small" style="width: 360px" />
                  <el-button type="primary" size="small" @click="doSearchMetric">查询</el-button>
                </div>
                <el-table :data="searchRows" border size="small" v-loading="searchLoading" style="width:100%">
                  <el-table-column prop="metricName" label="指标名称" min-width="170" />
                  <el-table-column prop="subCategoryName" label="指标小类" width="140" />
                  <el-table-column prop="metricValue" label="指标值" width="100" />
                  <el-table-column prop="unit" label="单位" width="80" />
                  <el-table-column label="数据日期" width="180"><template #default="{ row }">{{ formatDataTime(row.dataTime) }}</template></el-table-column>
                  <el-table-column label="入库时间" width="180"><template #default="{ row }">{{ formatDataTime(row.importedAt) }}</template></el-table-column>
                </el-table>
                <div class="metric-search-pagination">
                  <el-pagination v-model:current-page="searchPage" v-model:page-size="searchSize" :total="searchTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" size="small" @current-change="doSearchMetric" @size-change="onSearchSizeChange" />
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 环比分析 Tab -->
        <el-tab-pane label="环比分析" name="periodCompare"><template #label><WorkspaceTabLabel icon="Histogram">环比分析</WorkspaceTabLabel></template>
          <div v-if="activeReportType === 'periodCompare'" class="period-compare-area">
            <PeriodCompareAnalysis />
          </div>
        </el-tab-pane>

        <!-- 周报 Tab -->
        <el-tab-pane label="周报" name="weekly"><template #label><WorkspaceTabLabel icon="Document">周报</WorkspaceTabLabel></template>
          <template v-if="activeReportType === 'weekly'">
            <el-alert title="历史周报 · 2026 年 10 月 16 日至 23 日" type="info" :closable="false" />
            <WeeklyReportReader />
          </template>
        </el-tab-pane>

        <!-- 月报 Tab -->
        <el-tab-pane label="月报" name="monthly"><template #label><WorkspaceTabLabel icon="Calendar">月报</WorkspaceTabLabel></template>
          <div v-if="activeReportType === 'monthly'" class="coming-soon">
            <span class="coming-soon-text">Coming Soon</span>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- CRM前台自定义返回信息关键词配置对话框 -->
      <div style="position: relative;">
        <ReportKeywordDialog v-model="showKeywordDialog" :width="keywordDialogWidth" :keyword-list="keywordList" :keyword-loading="keywordLoading" :format-date-time="formatDateTime" @add="handleKeywordAdd" @edit="handleKeywordEdit" @delete="handleKeywordDelete" />
      <DialogSizeControls :adjustWidth="adjustKeywordDialogWidth" :visible="showKeywordDialog" />
      </div>

      <!-- 关键词编辑对话框 -->
      <div style="position: relative;">
        <el-dialog class="workspace-theme" v-model="keywordFormVisible" :title="keywordForm.id ? '编辑关键词' : '新增关键词'" :width="keywordFormWidth">
        <el-form :model="keywordForm" label-width="100px">
          <el-form-item label="指标">
            <el-input :model-value="keywordMetric?.name || '业务自定义返回异常'" disabled />
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="keywordForm.keyword" placeholder="请输入关键词" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="keywordFormVisible = false">取消</el-button>
          <el-button type="primary" @click="saveKeyword">确定</el-button>
        </template>
      </el-dialog>
      <DialogSizeControls :adjustWidth="adjustKeywordFormWidth" :visible="keywordFormVisible" />
      </div>
    </div>

    <StrategyEditorDialog v-model="strategyDialogVisible" :initial="strategySeed" :lock-target="true" :metric-context="strategyContext" @saved="loadRealtimeData" />
    <!-- 阈值配置对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="thresholdConfigVisible" title="阈值配置" :width="thresholdConfigWidth">
      <el-form :model="thresholdConfigData" label-width="100px">
        <el-form-item label="指标名称">
          <span>{{ thresholdConfigData.metricName }}</span>
        </el-form-item>
        <el-divider>告警配置</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="告警条件">
              <el-select v-model="thresholdConfigData.alarmCondition" style="width: 100%;">
                <el-option v-for="opt in conditionOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="告警阈值">
              <el-input-number v-model="thresholdConfigData.alarmThreshold" :min="0" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <template v-if="thresholdConfigData.structuredRule">
          <el-form-item v-if="thresholdConfigData.metricCode === 'SAT_FINAL_R003'" label="月初月末阈值"><el-input-number v-model="thresholdConfigData.edgeThreshold" :min="0" :precision="0" /></el-form-item>
          <el-form-item label="月累计阈值"><el-input-number v-model="thresholdConfigData.monthlyThreshold" :min="0" :precision="0" /></el-form-item>
          <el-form-item v-if="thresholdConfigData.metricCode !== 'SAT_FINAL_R015'" label="仅工作日"><el-switch v-model="thresholdConfigData.workdayOnly" /></el-form-item>
          <el-alert title="月初月末指1—3日及30—31日；工作日按法定节假日及调休日历判断。月累计须有完整日数据才评估。" type="info" :closable="false" />
        </template>
        <el-divider v-if="!thresholdConfigData.structuredRule">工单配置</el-divider>
        <el-row v-if="!thresholdConfigData.structuredRule" :gutter="20">
          <el-col :span="12">
            <el-form-item label="工单条件">
              <el-select v-model="thresholdConfigData.gongdanCondition" style="width: 100%;">
                <el-option v-for="opt in conditionOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="工单阈值">
              <el-input-number v-model="thresholdConfigData.gongdanThreshold" :min="0" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="thresholdConfigVisible = false">取消</el-button>
        <el-button type="primary" @click="saveThresholdConfig">保存</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustThresholdConfigWidth" :visible="thresholdConfigVisible" />
    </div>

    <!-- 工单详情对话框 -->
    <GongdanDetailDialog
      v-model="gongdanDetailVisible"
      :gongdan-id="currentGongdanId"
    />
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
import WorkspacePagination from '@/components/workspace/WorkspacePagination.vue'
import WorkspaceInlineCode from '@/components/workspace/WorkspaceInlineCode.vue'
import WorkspaceContextTrail from '@/components/workspace/WorkspaceContextTrail.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import ReportKeywordDialog from '@/components/report/ReportKeywordDialog.vue'
import ReportRealtimeToolbar from '@/components/report/ReportRealtimeToolbar.vue'
import ReportCategoryNavigation from '@/components/report/ReportCategoryNavigation.vue'
import { ref, onMounted, computed, watch } from 'vue'
import WeeklyReportReader from './components/WeeklyReportReader.vue'
import PeriodCompareAnalysis from './components/PeriodCompareAnalysis.vue'
import StrategyEditorDialog from '../../components/alarm/StrategyEditorDialog.vue'
import { compareMetricValues } from '@/utils/metricThreshold'
import { ElMessage, ElMessageBox } from 'element-plus'
import request, { realtimeApi, errorKeywordApi, gongdanApi, configApi, metricSearchApi } from '../../api'
import { resourceInputDates, formatResourceSubcategory } from '../../utils/resourceInputDates'
import { Setting } from '@element-plus/icons-vue'
import { useDialogSize } from '../../utils/useDialogSize'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'
import GongdanDetailDialog from '@/components/gongdan/GongdanDetailDialog.vue'
import { useRoute } from 'vue-router'
import { loadRunningEfficiencySubcategories } from '../../utils/runningEfficiencyCategories'
import SubcategoryWorkspace from '../../components/common/SubcategoryWorkspace.vue'

// 弹窗宽度控制
const { width: keywordDialogWidth, adjustWidth: adjustKeywordDialogWidth } = useDialogSize('op-report-keyword', '700px')
const { width: keywordFormWidth, adjustWidth: adjustKeywordFormWidth } = useDialogSize('op-report-keyword-form', '450px')
const { width: thresholdConfigWidth, adjustWidth: adjustThresholdConfigWidth } = useDialogSize('op-report-threshold', '500px')

// 指标大类配置
const categories = [
  { code: 'external', name: '对外部客户' },
  { code: 'compliance', name: '合规管理' },
  { code: 'satisfactionMetrics', name: '满意度及投诉服务体验' },
  { code: 'resourceInput', name: '资源投入' },
  { code: 'businessValue', name: '业务价值' },
  { code: 'dataQuality', name: '数据质量' },
  { code: 'internal', name: '对内部人员' },
  { code: 'system', name: '运行效率' }
]

// 实时指标 - 对内部人员的指标小类
const internalSubCategories = [
  { code: '查询类', name: '查询类' },
  { code: '办理类-单业务', name: '办理类-单业务' },
  { code: '办理类-融合业务', name: '办理类-融合业务' }
]

// 实时指标 - 对外部客户的指标小类
const externalSubCategories = [
  { code: '缴费开机', name: '缴费开机' },
  { code: '流量提醒', name: '流量提醒' }
]

const complianceSubCategories = [
  { code: '系统稽核', name: '系统稽核' }
]

const satisfactionSubCategories = ['客服投诉工单处理流程', '倒三角工单处理流程', '综调工单处理流程'].map(name => ({ code: name, name }))
const satisfactionDate = ref('')
const satisfactionAvailable = ref(0)
const satisfactionTotal = ref(0)

const activeReportType = ref('realtime')
const route = useRoute()
const satisfactionBatchId = computed(() => typeof route.query.batchId === 'string' ? route.query.batchId : '')
const activeCategory = ref('')
const selectedSubCategory = ref('')
const subCategories = ref([])
const tableData = ref([])
const tableLoading = ref(false)
const pagination = ref({ page: 1, size: 10 })
const tableSort = ref({ prop: '', order: null })

// 实时指标筛选
const filterStatus = ref('')
const filterTimeRange = ref('')
const resourceDateRange = ref(resourceInputDates())
const businessDateRange = ref(null)
const reportDateRange = computed({
  get: () => activeCategory.value === 'businessValue' ? businessDateRange.value : resourceDateRange.value,
  set: dates => {
    if (activeCategory.value === 'businessValue') businessDateRange.value = dates
    else resourceDateRange.value = dates
  }
})
const filterMetricName = ref('')

// 工单详情弹窗
const gongdanDetailVisible = ref(false)
const currentGongdanId = ref(null)

// 阈值配置弹窗
const strategyDialogVisible = ref(false)
const strategySeed = ref(null)
const strategyContext = ref(null)
let strategyRequest = 0
const thresholdConfigVisible = ref(false)
const thresholdConfigData = ref({
  metricId: null,
  metricName: '',
  alarmThreshold: null,
  alarmCondition: '>',
  gongdanThreshold: null,
  gongdanCondition: '>='
})

// 条件选项
const conditionOptions = [
  { value: '>', label: '大于 (>)' },
  { value: '>=', label: '大于等于 (>=)' },
  { value: '<', label: '小于 (<)' },
  { value: '<=', label: '小于等于 (<=)' },
  { value: '=', label: '等于 (=)' }
]

// 点击阈值打开配置弹窗
const handleThresholdClick = async (row) => {
  let policy = {}
  try { policy = JSON.parse(row.ruleExtra || '{}') } catch {}
  if (!row.structuredRule && !Object.keys(policy).some(key => ['monthlyThreshold', 'edgeThreshold', 'workdayOnly'].includes(key))) {
    const current = ++strategyRequest
    strategyContext.value = { ...row, id: row.metricId }
    strategySeed.value = null
    try {
      const response = await configApi.strategyList()
      if (current !== strategyRequest) return
      const strategies = (response.data || []).filter(item => item.strategyType === 'metric' && item.targetType === 'metric' && Number(item.targetId) === Number(row.metricId))
        .sort((a, b) => Number(b.status === 1) - Number(a.status === 1) || Number(a.id) - Number(b.id))
      strategySeed.value = strategies[0] || null
      strategyDialogVisible.value = true
    } catch { ElMessage.error('读取告警策略失败，请重试') }
    return
  }
  row = { ...row, ...policy, structuredRule: true }
  thresholdConfigData.value = {
    metricId: row.metricId,
    metricName: row.metricName,
    structuredRule: row.structuredRule, metricCode: row.metricCode,
    edgeThreshold: row.edgeThreshold, monthlyThreshold: row.monthlyThreshold, workdayOnly: row.workdayOnly,
    alarmThreshold: row.alarmThreshold,
    alarmCondition: row.alarmCondition || '>',
    gongdanThreshold: row.gongdanThreshold,
    gongdanCondition: row.gongdanCondition || '>='
  }
  thresholdConfigVisible.value = true
}

// 保存阈值配置
const saveThresholdConfig = async () => {
  try {
    if (thresholdConfigData.value.structuredRule) {
      await request.put('/collection/satisfaction-report/alarm-rule/' + thresholdConfigData.value.metricId, thresholdConfigData.value)
    } else await realtimeApi.updateMonitorConfig(thresholdConfigData.value.metricId, {
      metricId: thresholdConfigData.value.metricId,
      alarmThreshold: thresholdConfigData.value.alarmThreshold,
      alarmCondition: thresholdConfigData.value.alarmCondition,
      gongdanThreshold: thresholdConfigData.value.gongdanThreshold,
      gongdanCondition: thresholdConfigData.value.gongdanCondition
    })
    ElMessage.success('阈值配置保存成功')
    thresholdConfigVisible.value = false
    loadRealtimeData()
  } catch (error) {
    console.error('保存阈值配置失败:', error)
    ElMessage.error('保存失败')
  }
}

// 实时指标列设置
const realtimeColumnOptions = ref([
  { prop: 'dataTime', label: '数据日期' },
  { prop: 'metricName', label: '指标名称' },
  { prop: 'subCategoryName', label: '指标小类' },
  { prop: 'metricValue', label: '指标值' },
  { prop: 'alarmCondition', label: '告警条件' },
  { prop: 'alarmThreshold', label: '告警阈值' },
  { prop: 'exceedCount', label: '连续超限次数' },
  { prop: 'status', label: '状态' },
  { prop: 'unit', label: '单位' }
])
const defaultRealtimeColumns = ['dataTime', 'metricName', 'subCategoryName', 'metricValue', 'status']
const visibleRealtimeColumns = ref([...defaultRealtimeColumns])

const isRealtimeColumnHidden = (prop) => {
  return !visibleRealtimeColumns.value.includes(prop)
}

const handleRealtimeColumnChange = () => {
  savePreferences()
}

// 计算超限和正常数量
const warningCount = computed(() => tableData.value.filter(r => r.isExceed === 1).length)
const normalCount = computed(() => tableData.value.filter(r => r.isExceed === 0).length)

// 前端筛选后的数据（状态 + 指标名称）
const filteredTableData = computed(() => {
  let data = tableData.value
  if (filterStatus.value) {
    if (filterStatus.value === '__abnormal__') {
      // 非正常：筛选告警和工单
      data = data.filter(r => r.isExceed === 1)
    } else {
      data = data.filter(r => r.status === filterStatus.value)
    }
  }
  if (filterMetricName.value) {
    const keyword = filterMetricName.value.toLowerCase()
    data = data.filter(r => r.metricName && r.metricName.toLowerCase().includes(keyword))
  }
  const { prop, order } = tableSort.value
  if (!order) return data
  const direction = order === 'ascending' ? 1 : -1
  return [...data].sort((a, b) => direction * (prop === 'metricValue'
    ? compareMetricValues(a.metricValue, b.metricValue)
    : String(a.dataTime || '').localeCompare(String(b.dataTime || ''))))
})

watch([filterStatus, filterMetricName, () => pagination.value.size], () => { pagination.value.page = 1 })
watch(() => filteredTableData.value.length, total => {
  pagination.value.page = Math.min(pagination.value.page, Math.max(1, Math.ceil(total / pagination.value.size)))
})

const handleSortChange = ({ prop, order }) => {
  tableSort.value = { prop, order }
  pagination.value.page = 1
}

// 分页后的数据
const paginatedTableData = computed(() => {
  const start = (pagination.value.page - 1) * pagination.value.size
  const end = start + pagination.value.size
  return filteredTableData.value.slice(start, end)
})

// 列显示设置
const STORAGE_KEY = 'report_table_preferences'
const loadPreferences = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const prefs = JSON.parse(saved)
      if (prefs.visibleRealtimeColumns && prefs.visibleRealtimeColumns.length > 0) {
        visibleRealtimeColumns.value = prefs.visibleRealtimeColumns
      }
    }
  } catch (e) {
    console.error('加载偏好设置失败:', e)
  }
}

const savePreferences = () => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      visibleRealtimeColumns: visibleRealtimeColumns.value
    }))
  } catch (e) {
    console.error('保存偏好设置失败:', e)
  }
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
  const idx = visibleRealtimeColumns.value.indexOf(prop)
  if (idx > -1) {
    visibleRealtimeColumns.value.splice(idx, 1)
    savePreferences()
  }
  showHeaderContextMenu.value = false
}

const closeContextMenu = () => {
  showHeaderContextMenu.value = false
}

// 切换指标大类
const handleCategoryChange = async (categoryCode) => {
  pagination.value.page = 1
  tableData.value = []
  filterStatus.value = ''
  filterTimeRange.value = ''

  if (categoryCode === 'internal') {
    subCategories.value = internalSubCategories
    if (subCategories.value.length > 0) {
      selectedSubCategory.value = subCategories.value[0].code
    }
  } else if (categoryCode === 'external') {
    subCategories.value = externalSubCategories
    if (subCategories.value.length > 0) {
      selectedSubCategory.value = subCategories.value[0].code
    }
  } else if (categoryCode === 'satisfactionMetrics') {
    subCategories.value = satisfactionSubCategories
    selectedSubCategory.value = route.query.category === 'satisfactionMetrics'
      ? ({ customer: '客服投诉工单处理流程', triangle: '倒三角工单处理流程', dispatch: '综调工单处理流程' }[route.query.flow] || subCategories.value[0].code)
      : subCategories.value[0].code
  } else if (categoryCode === 'compliance') {
    subCategories.value = complianceSubCategories
    selectedSubCategory.value = subCategories.value[0].code
  } else if (categoryCode === 'businessValue') {
    businessDateRange.value = null
    subCategories.value = [{ code: '运营成效', name: '运营成效' }, { code: '报表看板', name: '报表看板' }]
    selectedSubCategory.value = subCategories.value[0].code
  } else if (categoryCode === 'resourceInput') {
    resourceDateRange.value = resourceInputDates()
    const { data } = await request.get('/collection/resource-input/catalog')
    if (activeCategory.value !== categoryCode) return
    subCategories.value = [...new Set((data || []).filter(m => /^(requirementCount|totalRequirementDuration|avgRequirementDuration)(Boss|Bass)$/.test(m.code)).map(m => m.subcategory))].map(name => ({ code: name, name: formatResourceSubcategory(name) }))
    selectedSubCategory.value = subCategories.value[0]?.code || ''
  } else if (categoryCode === 'dataQuality') {
    // 数据质量不做二级小类导航：指标直接平铺在大类下，一次取全部
    subCategories.value = []
    selectedSubCategory.value = ''
  } else if (categoryCode === 'system') {
    subCategories.value = []
    selectedSubCategory.value = ''
    try {
      const configured = await loadRunningEfficiencySubcategories()
      if (activeCategory.value !== categoryCode) return
      subCategories.value = configured
    } catch (error) {
      if (activeCategory.value !== categoryCode) return
      console.error('加载运行效率小类失败:', error)
      ElMessage.error('加载运行效率小类失败，请重新选择大类重试')
      return
    }
    if (subCategories.value.length > 0) {
      selectedSubCategory.value = subCategories.value[0].code
    }
  } else {
    subCategories.value = []
    selectedSubCategory.value = ''
  }

  loadRealtimeData()
}

// 切换指标小类
const handleSubCategoryChange = () => {
  pagination.value.page = 1
  loadRealtimeData()
}

// 获取状态标签类型
const getStatusType = (status) => {
  if (status === '工单') return 'danger'
  if (['告警', '超限'].includes(status)) return 'warning'
  return status === '正常' ? 'success' : 'info'
}

// 点击工单标签打开工单详情
const handleGongdanClick = (row) => {
  if (row.status === '工单' && row.gongdanId) {
    currentGongdanId.value = row.gongdanId
    gongdanDetailVisible.value = true
  } else if (row.status === '工单') {
    ElMessage.warning('该工单ID暂不可用')
  }
}

// 加载实时指标数据
let realtimeRequest = 0
const searchRealtimeData = () => {
  pagination.value.page = 1
  return loadRealtimeData()
}
const loadRealtimeData = async (forceRefresh = false) => {
  const version = ++realtimeRequest
  if (!activeCategory.value) {
    tableData.value = []

    tableLoading.value = false
    return
  }

  tableLoading.value = true
  const requestedCategory = activeCategory.value
  const requestedSubCategory = selectedSubCategory.value
  const isCurrent = () => version === realtimeRequest &&
    activeCategory.value === requestedCategory && selectedSubCategory.value === requestedSubCategory
  try {
    if (requestedCategory === 'businessValue') {
      const [startDate, endDate] = businessDateRange.value || []
      const { data } = await request.get('/collection/business-value/report', {
        params: { startDate, endDate, subCategory: requestedSubCategory || undefined }
      })
      if (!isCurrent()) return
      tableData.value = data || []
      return
    }
    if (requestedCategory === 'resourceInput') {
      const dates = resourceDateRange.value || resourceInputDates()
      const res = await request.get('/collection/resource-input/report', { params: { startDate: dates[0], endDate: dates[1], subCategory: requestedSubCategory || undefined } })
      const currentDates = resourceDateRange.value || resourceInputDates()
      if (!isCurrent() || currentDates.join() !== dates.join()) return
      tableData.value = res.data || []
      return
    }
    if (requestedCategory === 'satisfactionMetrics' && satisfactionBatchId.value) {
      const res = await request.get('/collection/satisfaction-report', { params: { batchId: satisfactionBatchId.value || undefined } })
      if (!isCurrent()) return
      const batch = res.data.batches.find(item => item.id === res.data.batchId)
      satisfactionDate.value = batch?.businessDate || ''
      satisfactionTotal.value = res.data.rows.length
      satisfactionAvailable.value = res.data.rows.filter(row => row.metricValue != null).length
      let rows = res.data.rows.filter(row => row.groupName === requestedSubCategory).map(row => ({
        ...row, dataTime: batch?.businessDate || '',
        subCategoryName: row.groupName, exceedCount: null
      }))
      if (filterTimeRange.value) rows = filterDataByTimeRange(rows, filterTimeRange.value)
      tableData.value = rows

      return
    }
    // 运行效率按正式小类名称筛选，包含已配置的采集指标。
    if (activeCategory.value === 'system') {
      const res = await realtimeApi.getMonitorMetrics({
        category: '运行效率',
        subCategory: requestedSubCategory || undefined,
        refresh: forceRefresh || undefined
      })
      if (!isCurrent()) return
      // 根据选中的小类过滤
      let filteredData = (res.data || []).filter(row => row.categoryCode === 'system_run' || row.categoryName === '运行效率').map(row => ({ ...row, isExceed: row.alarmThreshold == null ? null : row.isExceed }))
      if (selectedSubCategory.value) {
        filteredData = filteredData.filter(m => m.subCategoryName === selectedSubCategory.value)
      }
      // 前端时间范围筛选
      if (filterTimeRange.value) {
        filteredData = filterDataByTimeRange(filteredData, filterTimeRange.value)
      }
      tableData.value = filteredData

    } else {
      // 其他类别（对外部客户、对内部人员、计划执行）使用业务指标API，从 metric_realtime 取数据
      const catName = categories.find(c => c.code === activeCategory.value)?.name || ''
      const params = {
        category: catName,
        subCategory: selectedSubCategory.value || undefined,
        timeRange: filterTimeRange.value || undefined
      }
      const res = await realtimeApi.getBusinessMetrics(params)
      if (!isCurrent()) return
      tableData.value = res.data || []

      if (requestedCategory === 'satisfactionMetrics') {
        satisfactionTotal.value = tableData.value.length
        satisfactionAvailable.value = tableData.value.filter(row => row.metricValue != null).length
        satisfactionDate.value = tableData.value.map(row => row.dataTime).filter(Boolean).sort().at(-1) || ''
      }
    }
  } catch (error) {
    if (!isCurrent()) return
    console.error('加载实时指标数据失败:', error)
    ElMessage.error('加载实时指标数据失败')
    tableData.value = []

  } finally {
    if (version === realtimeRequest) tableLoading.value = false
  }
}

// 按时间范围筛选数据
const filterDataByTimeRange = (data, timeRange) => {
  if (!timeRange) return data
  const now = Date.now()
  let startTime = null
  if (timeRange === '1h') {
    startTime = now - 60 * 60 * 1000
  } else if (timeRange === '6h') {
    startTime = now - 6 * 60 * 60 * 1000
  } else if (timeRange === 'today') {
    const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(now)
    startTime = Date.parse(`${today}T00:00:00+08:00`)
  } else if (timeRange === '24h') {
    startTime = now - 24 * 60 * 60 * 1000
  }
  if (!startTime) return data
  return data.filter(row => {
    if (!row.dataTime) return false
    const time = row.dataTime.length === 10 ? `${row.dataTime}T00:00:00` : row.dataTime.replace(' ', 'T')
    const dataTime = Date.parse(/(?:Z|[+-]\d\d:\d\d)$/i.test(time) ? time : time + '+08:00')
    return dataTime >= startTime && dataTime <= now
  })
}

const resetFilters = () => {
  resourceDateRange.value = resourceInputDates()
  businessDateRange.value = null
  filterStatus.value = ''
  filterTimeRange.value = ''
  filterMetricName.value = ''
  pagination.value.page = 1
  loadRealtimeData()
}

// 点击状态卡片筛选
const handleStatusCardClick = (status) => {
  filterStatus.value = status
}

// 点击异常卡片：筛选非正常（告警+工单），再次点击取消筛选
const handleAbnormalCardClick = () => {
  if (filterStatus.value === '__abnormal__') {
    filterStatus.value = ''
  } else {
    filterStatus.value = '__abnormal__'
  }
}

// 关键词配置相关
const showKeywordDialog = ref(false)
const keywordFormVisible = ref(false)
const keywordList = ref([])
const keywordLoading = ref(false)
const keywordMetric = ref(null)
const keywordForm = ref({
  id: null,
  metricId: null,
  keyword: ''
})

const loadKeywords = async () => {
  keywordLoading.value = true
  try {
    const { data } = await request.get('/collection/raw-data/catalog', { params: { category: '运行效率' } })
    const matches = (data || []).filter(metric => metric.code === 'error_biz_return_count')
    if (matches.length !== 1) {
      keywordMetric.value = null
      keywordList.value = []
      ElMessage.warning('找不到唯一的业务自定义返回异常指标，请核对指标配置')
      return
    }
    keywordMetric.value = matches[0]
    const res = await errorKeywordApi.list(keywordMetric.value.id)
    keywordList.value = res.data || []
  } catch (error) {
    keywordMetric.value = null
    keywordList.value = []
    console.error('加载关键词失败:', error)
  } finally {
    keywordLoading.value = false
  }
}

const handleKeywordAdd = () => {
  if (!keywordMetric.value || keywordLoading.value) return
  keywordForm.value = { id: null, metricId: keywordMetric.value.id, keyword: '' }
  keywordFormVisible.value = true
}

const handleKeywordEdit = (row) => {
  keywordForm.value = { ...row }
  keywordFormVisible.value = true
}

const handleKeywordDelete = (row) => {
  ElMessageBox.confirm('确定删除该关键词吗?', '提示', { type: 'warning' })
    .then(async () => {
      await errorKeywordApi.delete(row.id)
      ElMessage.success('删除成功')
      loadKeywords()
    }).catch(() => {})
}

const saveKeyword = async () => {
  try {
    if (keywordForm.value.id) {
      await errorKeywordApi.update(keywordForm.value.id, keywordForm.value)
    } else {
      await errorKeywordApi.create(keywordForm.value)
    }
    ElMessage.success('保存成功')
    keywordFormVisible.value = false
    loadKeywords()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

const formatDateTime = (datetime) => {
  if (!datetime) return ''
  const date = new Date(datetime)
  return date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

// 数据日期展示：统一 yyyy-MM-dd HH:mm:ss（纯日期补零点时分秒）
const formatDataTime = (v) => {
  if (!v) return ''
  const s = String(v).replace('T', ' ').trim()
  if (s.length === 10) return s + ' 00:00:00'
  return s.length >= 19 ? s.slice(0, 19) : s
}

// ===== 指标检索 =====
const DIRECT_MODES = ['collected_value', 'resource_value', 'external_value', 'satisfaction_report']
const searchCatalog = ref([])
const searchSubCategory = ref('')
const searchMetricId = ref(null)
const searchTimeRange = ref(null)
const searchRows = ref([])
const searchTotal = ref(0)
const searchPage = ref(1)
const searchSize = ref(20)
const searchLoading = ref(false)

const pad2 = n => String(n).padStart(2, '0')
const nowDateTimeStr = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`

const searchCatalogItems = computed(() => searchCatalog.value.filter(m => DIRECT_MODES.includes(m.collectionMode)))
// 检索大类锁定当前指标区选中的大类，不跨大类
const searchCategoryName = computed(() => categories.find(c => c.code === activeCategory.value)?.name || '')
const searchSubCategories = computed(() => {
  if (!searchCategoryName.value) return []
  return [...new Set(searchCatalogItems.value.filter(m => m.categoryName === searchCategoryName.value && m.subCategoryName).map(m => m.subCategoryName))].sort()
})
const searchMetrics = computed(() => {
  if (!searchCategoryName.value) return []
  const byCategory = searchCatalogItems.value.filter(m => m.categoryName === searchCategoryName.value)
  return searchSubCategory.value ? byCategory.filter(m => m.subCategoryName === searchSubCategory.value) : byCategory
})

const onSearchSubCategoryChange = () => { searchMetricId.value = null; clearSearchResult() }
const clearSearchResult = () => { searchRows.value = []; searchTotal.value = 0; searchPage.value = 1 }
// 切换大类：检索区跟随当前大类并清空，避免串到其他大类的数据
watch(activeCategory, () => {
  const subs = searchSubCategories.value
  searchSubCategory.value = subs.length ? subs[0] : ''
  searchMetricId.value = null
  clearSearchResult()
})

const doSearchMetric = async () => {
  if (!searchMetricId.value) { ElMessage.warning('请选择指标'); return }
  if (!searchTimeRange.value || searchTimeRange.value.length !== 2) { ElMessage.warning('请选择时间范围'); return }
  searchLoading.value = true
  try {
    const res = await metricSearchApi.search({
      metricId: searchMetricId.value,
      startTime: searchTimeRange.value[0],
      endTime: searchTimeRange.value[1],
      page: searchPage.value - 1,
      size: searchSize.value
    })
    const name = res.data?.metric?.metricName
    const sub = res.data?.metric?.subCategoryName
    const unit = res.data?.metric?.unit
    searchRows.value = (res.data?.rows || []).map(r => ({ ...r, metricName: name, subCategoryName: sub, unit }))
    searchTotal.value = res.data?.total || 0
  } catch (e) {
    // 拦截器已统一提示
  } finally {
    searchLoading.value = false
  }
}
const onSearchSizeChange = () => { searchPage.value = 1; doSearchMetric() }
// 点击当前指标表格的指标名称：检索区自动选中该指标并查询
const handleMetricNameClick = (row) => {
  const metricId = row.metricId ?? row.id
  if (metricId == null) return
  searchSubCategory.value = row.subCategoryName || searchSubCategories.value[0] || ''
  searchMetricId.value = metricId
  searchPage.value = 1
  doSearchMetric()
}

// 监听关键词配置对话框打开
watch(showKeywordDialog, (val) => {
  if (val) {
    loadKeywords()
  }
})

onMounted(() => {
  loadPreferences()
  // 指标检索：加载指标清单 + 默认今天时间范围
  metricSearchApi.catalog().then(res => { searchCatalog.value = res.data || [] })
  const now = new Date()
  const today = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`
  searchTimeRange.value = [`${today} 00:00:00`, nowDateTimeStr(now)]
  if (route.query.category === 'satisfactionMetrics') openSatisfactionMetrics()
  else { activeCategory.value = 'external'; handleCategoryChange('external') }
})

function openSatisfactionMetrics() {
  activeReportType.value = 'realtime'
  activeCategory.value = 'satisfactionMetrics'
  subCategories.value = satisfactionSubCategories
  selectedSubCategory.value = { customer: '客服投诉工单处理流程', triangle: '倒三角工单处理流程', dispatch: '综调工单处理流程' }[route.query.flow] || satisfactionSubCategories[0].code
  filterStatus.value = ''
  filterTimeRange.value = ''
  filterMetricName.value = ''
  pagination.value.page = 1
  loadRealtimeData()
}
watch(() => [route.query.category, route.query.flow, route.query.batchId], () => {
  if (route.query.category === 'satisfactionMetrics') openSatisfactionMetrics()
})
</script>

<style scoped>
.report-container {
  padding: 8px;
}

.weekly-report-page { padding: 0; }
.weekly-report-page > :deep(.el-card) { border: none; box-shadow: none; overflow: visible; }
.weekly-report-page :deep(.el-card__body),
.weekly-report-page :deep(.report-type-tabs > .el-tabs__content) { overflow: visible; }
.weekly-report-page :deep(.el-card__body) { padding: 12px 20px 20px; }

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
}

.report-type-tabs {
  margin-top: 2px;
}

:deep(.el-tabs__header) {
  margin-bottom: 0;
}

:deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
}

:deep(.el-tabs__item) {
  padding: 0 16px;
  height: 32px;
  line-height: 32px;
  font-size: 14px;
}

.content-area {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

.period-compare-area {
  margin-top: 8px;
}

.category-sidebar {
  width: 140px;
  flex-shrink: 0;
  background: var(--ui-page);
  border-radius: 4px;
  padding: 8px 0;
}

.subcategory-tabs {
  margin-bottom: 8px;
}

.subcategory-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.subcategory-tabs :deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
}

.subcategory-tabs :deep(.el-tabs__item) {
  padding: 0 16px;
  height: 32px;
  line-height: 32px;
  font-size: 13px;
}

.vertical-tabs {
  height: 100%;
}

:deep(.vertical-tabs .el-tabs__header) {
  margin-bottom: 0;
}

:deep(.vertical-tabs .el-tabs__nav-wrap) {
  margin-bottom: 0;
}

:deep(.vertical-tabs .el-tabs__item) {
  padding: 8px 16px;
  height: auto;
  line-height: 1.4;
  font-size: 13px;
  text-align: center;
  white-space: normal;
  justify-content: center;
}

:deep(.vertical-tabs .el-tabs__nav-scroll) {
  display: flex;
  align-items: flex-start;
}

:deep(.vertical-tabs .el-tabs__active-bar) {
  height: 100%;
}

:deep(.vertical-tabs .el-tabs__header .el-tabs__item.is-active) {
  background-color: var(--ui-primary-soft);
}

.data-area {
  flex: 1;
  min-width: 0;
}
.metric-search-panel { margin-top: 16px; padding: 14px 16px; border: 1px solid var(--ui-border); border-radius: 14px; background: var(--ui-surface); box-shadow: var(--ui-shadow-sm); }
.metric-search-title { font-size: 13px; font-weight: 600; color: var(--ui-heading); margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
.metric-search-title::before { content: ''; height: 16px; width: 4px; border-radius: 3px; background: var(--ui-primary); }
.metric-search-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 10px; }
.metric-search-category { font-size: 12px; color: var(--ui-heading); font-weight: 600; }
.metric-name-link { color: var(--ui-primary); cursor: pointer; }
.metric-name-link:hover { text-decoration: underline; }
.metric-search-pagination { display: flex; justify-content: flex-end; margin-top: 10px; }
.satisfaction-meta { margin: 4px 0 8px; color: var(--ui-muted); font-size: 12px; }

.filter-row {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
  align-items: center;
}

.filter-label {
  font-size: 13px;
  color: var(--ui-text);
}

.toolbar-spacer {
  flex: 1;
}

.stat-cards {
  display: flex;
  gap: 12px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 16px;
  border-radius: 4px;
  min-width: 60px;
}

.stat-card.clickable {
  cursor: pointer;
  transition: opacity 0.2s;
}

.stat-card.clickable:hover {
  opacity: 0.8;
}

.stat-normal {
  background-color: #f0f9eb;
  border: 1px solid #e1f3d8;
}

.stat-warning {
  background-color: #fef0f0;
  border: 1px solid #fde2e2;
}

.stat-value {
  font-size: 18px;
  font-weight: bold;
}

.stat-label {
  font-size: 11px;
  color: var(--ui-muted);
}

.text-danger {
  color: var(--ui-danger);
  font-weight: bold;
}

.text-normal {
  color: var(--ui-success);
}

:deep(.row-danger) {
  background-color: #fef0f0 !important;
}

:deep(.row-danger:hover > td) {
  background-color: #fee !important;
}

.column-toggle-group {
  display: flex;
  flex-direction: column;
}

.column-toggle-item {
  margin-left: 0;
  padding: 4px 0;
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

@media (max-width: 768px) {
  .desktop-table {
    display: none;
  }
  .mobile-cards {
    display: block;
  }
  .content-area {
    flex-direction: column;
  }
  .category-sidebar {
    width: 100%;
  }
}

@media (min-width: 769px) {
  .desktop-table {
    display: block;
  }
  .mobile-cards {
    display: none;
  }
}

.data-card {
  margin-bottom: 10px;
}

.card-content .card-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 14px;
}

.card-content .card-row .label {
  color: var(--ui-muted);
}

.card-content .card-row .value {
  color: var(--ui-heading);
  text-align: right;
  flex: 1;
  margin-left: 10px;
  word-break: break-all;
}

/* 可点击的标签样式 */
.clickable-tag {
  cursor: pointer;
}

/* 可点击的数值样式 */
.clickable-value {
  cursor: pointer;
  color: var(--ui-primary);
  padding: 2px 4px;
  border-radius: 4px;
}

.clickable-value:hover {
  background-color: var(--ui-primary-soft);
}

.coming-soon {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

.coming-soon-text {
  font-size: 24px;
  color: var(--ui-muted);
  font-weight: 500;
}
</style>

<style src="@/views/operation-report/prototype-theme.css"></style>
