<template>
  <el-drawer class="workspace-theme dashboard-workspace" v-model="visible" :title="isEditing ? '编辑模块' : '添加模块'" size="600px" direction="rtl">
    <div class="drawer-content" v-if="form.type">
      <!-- 基本信息 -->
      <el-divider content-position="left">基本信息</el-divider>
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="模块标题" required>
          <el-input v-model="form.title" placeholder="请输入模块标题" />
        </el-form-item>
        <el-form-item label="模块类型">
          <el-tag type="info">{{ typeLabels[form.type] }}</el-tag>
          <span class="type-hint">{{ typeHints[form.type] }}</span>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="宽度(px)">
              <el-input-number v-model="form.width" :min="100" :max="1200" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="高度(px)">
              <el-input-number v-model="form.height" :min="100" :max="800" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- 数据筛选 -->
      <el-divider content-position="left">数据筛选</el-divider>
      <div class="filter-section">
        <el-form :model="form.config.dataSource.params" label-width="80px" size="small" @submit.prevent>
          <!-- 告警/工单/指标类筛选 -->
          <template v-if="['alarm_list', 'alarm_statistics', 'alarm_trend', 'gongdan_list', 'gongdan_statistics'].includes(form.type)">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="所属系统">
                  <el-select v-model="form.config.dataSource.params.systemId" placeholder="全部" clearable filterable style="width: 100%;">
                    <el-option v-for="s in systemList" :key="s.id" :label="s.systemName" :value="s.id" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="指标大类">
                  <el-select v-model="form.config.dataSource.params.categoryId" placeholder="全部" clearable filterable style="width: 100%;" @change="handleCategoryChange">
                    <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="具体指标">
                  <el-select v-model="form.config.dataSource.params.metricId" placeholder="全部" clearable filterable style="width: 100%;">
                    <el-option v-for="m in metricList" :key="m.id" :label="m.metricName" :value="m.id" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item :label="form.type.includes('alarm') ? '告警类型' : '工单类型'">
                  <el-select v-model="form.config.dataSource.params.alarmType" placeholder="全部" clearable style="width: 100%;">
                    <el-option v-if="form.type.includes('alarm')" v-for="t in alarmTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                    <el-option v-else v-for="t in gongdanTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 告警专有筛选 -->
          <template v-if="form.type === 'alarm_list'">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="处理状态">
                  <el-select v-model="form.config.dataSource.params.processStatus" placeholder="全部" clearable style="width: 100%;">
                    <el-option label="未处理" :value="0" />
                    <el-option label="已处理" :value="1" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="屏蔽状态">
                  <el-select v-model="form.config.dataSource.params.shieldStatus" placeholder="全部" clearable style="width: 100%;">
                    <el-option label="未屏蔽" :value="0" />
                    <el-option label="已屏蔽" :value="1" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 工单专有筛选 -->
          <template v-if="form.type === 'gongdan_list'">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="处理状态">
                  <el-select v-model="form.config.dataSource.params.processStatus" placeholder="全部" clearable style="width: 100%;">
                    <el-option label="处理中" :value="0" />
                    <el-option label="已关单" :value="1" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 问题类筛选 -->
          <template v-if="form.type === 'issue_list' || form.type === 'issue_statistics'">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="问题类型">
                  <el-select v-model="form.config.dataSource.params.issueType" placeholder="全部" clearable style="width: 100%;">
                    <el-option v-for="t in issueTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="问题来源">
                  <el-select v-model="form.config.dataSource.params.source" placeholder="全部" clearable style="width: 100%;">
                    <el-option v-for="s in issueSourceOptions" :key="s.value" :label="s.label" :value="s.value" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="处理状态">
                  <el-select v-model="form.config.dataSource.params.status" placeholder="全部" clearable style="width: 100%;">
                    <el-option v-for="s in issueStatusOptions" :key="s.value" :label="s.label" :value="s.value" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 时间范围筛选 -->
          <template v-if="['alarm_list', 'gongdan_list', 'issue_list', 'data_aggregation', 'health_trend', 'metric_trend', 'alarm_trend'].includes(form.type)">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="开始时间">
                  <el-date-picker v-model="form.config.dataSource.params.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%;" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="结束时间">
                  <el-date-picker v-model="form.config.dataSource.params.endDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%;" />
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 数据汇聚类筛选 -->
          <template v-if="form.type === 'data_aggregation'">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="指标大类">
                  <el-select v-model="form.config.dataSource.params.categoryName" placeholder="请选择大类" clearable filterable style="width: 100%;" @change="handleCategoryNameChange">
                    <el-option v-for="c in categoryNameList" :key="c" :label="c" :value="c" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="指标小类">
                  <el-select v-model="form.config.dataSource.params.subCategoryName" placeholder="请先选择大类" clearable filterable style="width: 100%;">
                    <el-option v-for="s in subCategoryNameList" :key="s" :label="s" :value="s" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="原始表名">
                  <el-select v-model="form.config.dataSource.params.tableName" placeholder="请选择表名" clearable filterable style="width: 100%;">
                    <el-option v-for="t in tableNameList" :key="t" :label="t" :value="t" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 指标/健康趋势类筛选 -->
          <template v-if="form.type === 'metric_trend' || form.type === 'health_trend'">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item :label="form.type === 'health_trend' ? '指标大类' : '具体指标'">
                  <el-select v-if="form.type === 'metric_trend'" v-model="form.config.dataSource.params.metricId" placeholder="请选择指标" clearable filterable style="width: 100%;">
                    <el-option v-for="m in metricList" :key="m.id" :label="m.metricName" :value="m.id" />
                  </el-select>
                  <el-select v-else v-model="form.config.dataSource.params.categoryId" placeholder="请选择大类" clearable filterable style="width: 100%;">
                    <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="趋势维度">
                  <el-select v-model="form.config.dataSource.params.dimension" placeholder="选择维度" style="width: 100%;">
                    <el-option label="今日" value="today" />
                    <el-option label="近一周" value="week" />
                    <el-option label="近一月" value="month" />
                    <el-option label="近一年" value="year" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>
        </el-form>

        <div class="preview-action">
          <el-button type="primary" size="small" :loading="previewLoading" @click="handlePreview">
            <el-icon><Search /></el-icon>预览数据
          </el-button>
          <span class="preview-hint">点击获取真实数据，用于验证筛选条件是否正确</span>
        </div>
      </div>

      <!-- 数据预览 -->
      <template v-if="previewData.length > 0">
        <el-divider content-position="left">数据预览（仅显示前5条）</el-divider>
        <div class="preview-table-wrapper">
          <el-table :data="previewData.slice(0, 5)" size="small" border max-height="200" style="width: 100%;">
            <el-table-column v-for="col in previewColumns" :key="col.prop" :prop="col.prop" :label="col.label" :width="col.width || 150" class-name="wrap-cell" />
          </el-table>
          <div v-if="previewData.length > 5" class="preview-more">
            还有 {{ previewData.length - 5 }} 条数据未显示...
          </div>
        </div>
      </template>

      <!-- 展示配置 -->
      <el-divider content-position="left">展示配置</el-divider>
      <el-form :model="form.config.display" label-width="80px" size="small">
        <!-- 表格展示配置 -->
        <template v-if="form.config.display.mode === 'table'">
          <el-form-item label="分页大小">
            <el-select v-model="form.config.display.pageSize" style="width: 120px;">
              <el-option :label="10" :value="10" />
              <el-option :label="20" :value="20" />
              <el-option :label="50" :value="50" />
            </el-select>
          </el-form-item>
          <el-form-item label="显示列">
            <div class="column-checkboxes">
              <el-checkbox v-for="col in allColumns" :key="col.prop" v-model="col.visible" :value="col.label" @change="updateDisplayColumns" />
            </div>
          </el-form-item>
        </template>

        <!-- 指标卡展示配置 -->
        <template v-if="form.config.display.mode === 'card'">
          <el-form-item label="数值字段">
            <el-select v-model="form.config.display.valueField" placeholder="选择数值字段" style="width: 100%;">
              <el-option v-for="col in allColumns" :key="col.prop" :label="col.label + ' (' + col.prop + ')'" :value="col.prop" />
            </el-select>
          </el-form-item>
          <el-form-item label="单位后缀">
            <el-input v-model="form.config.display.suffix" placeholder="如：条、次、%" style="width: 120px;" />
          </el-form-item>
        </template>

        <!-- 图表展示配置 -->
        <template v-if="form.config.display.mode === 'line_chart' || form.config.display.mode === 'bar_chart'">
          <el-form-item label="X轴字段">
            <el-select v-model="form.config.display.xField" placeholder="选择X轴字段" style="width: 100%;">
              <el-option v-for="col in allColumns" :key="col.prop" :label="col.label + ' (' + col.prop + ')'" :value="col.prop" />
            </el-select>
          </el-form-item>
          <el-form-item label="Y轴字段">
            <el-select v-model="form.config.display.yField" placeholder="选择Y轴字段" style="width: 100%;">
              <el-option v-for="col in numericColumns" :key="col.prop" :label="col.label + ' (' + col.prop + ')'" :value="col.prop" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>
    </div>

    <template #footer>
      <div class="drawer-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { alarmApi, gongdanApi, issueApi, dataApi, metricApi, systemApi } from '../../api'

const visible = ref(false)
const editingIndex = ref(-1)
const previewLoading = ref(false)
const previewData = ref([])
const previewColumns = ref([])

const systemList = ref([])
const categoryList = ref([])
const metricList = ref([])
const categoryNameList = ref([])
const subCategoryNameList = ref([])
const tableNameList = ref([])

const form = ref({
  type: 'alarm_list',
  title: '',
  width: 600,
  height: 400,
  config: {
    dataSource: {
      api: '/metric-code/api/alarm',
      params: {}
    },
    display: {
      mode: 'table',
      pagination: true,
      pageSize: 10,
      columns: []
    }
  }
})

const typeLabels = {
  alarm_list: '告警列表',
  alarm_statistics: '告警统计',
  alarm_trend: '告警趋势',
  gongdan_list: '工单列表',
  gongdan_statistics: '工单统计',
  issue_list: '问题列表',
  issue_statistics: '问题统计',
  data_aggregation: '数据汇聚',
  realtime_metric: '实时指标',
  health_trend: '健康度趋势',
  metric_trend: '指标值趋势'
}

const typeHints = {
  alarm_list: '展示告警记录列表，支持筛选和分页',
  alarm_statistics: '展示告警统计数据（总数/未处理/已处理）',
  alarm_trend: '展示告警数量随时间变化趋势',
  gongdan_list: '展示工单记录列表',
  gongdan_statistics: '展示工单统计数据',
  issue_list: '展示问题记录列表',
  issue_statistics: '展示问题统计数据',
  data_aggregation: '从原始数据表获取数据并展示',
  realtime_metric: '展示单个指标的当前值',
  health_trend: '展示健康度随时间变化趋势',
  metric_trend: '展示指标数值随时间变化趋势'
}

const alarmTypeOptions = [
  { label: '指标值告警', value: 'metric' },
  { label: '健康度告警', value: 'health' }
]

const gongdanTypeOptions = [
  { label: '普通工单', value: 'normal' },
  { label: '紧急工单', value: 'urgent' }
]

const issueTypeOptions = [
  { label: '逾期问题', value: 'overdue' },
  { label: '其他问题', value: 'other' }
]

const issueSourceOptions = [
  { label: '手工登记', value: 'manual' },
  { label: '系统产生', value: 'system' }
]

const issueStatusOptions = [
  { label: '处理中', value: 0 },
  { label: '已解决', value: 1 }
]

const isEditing = computed(() => editingIndex.value >= 0)

const allColumns = computed(() => {
  if (!previewColumns.value.length) return []
  return previewColumns.value.map(c => ({ ...c, visible: true }))
})

const numericColumns = computed(() => {
  return allColumns.value.filter(c => {
    const sample = previewData.value[0]?.[c.prop]
    return typeof sample === 'number'
  })
})

const emit = defineEmits(['save'])

watch(visible, async (val) => {
  if (!val) {
    editingIndex.value = -1
    previewData.value = []
    previewColumns.value = []
  } else {
    await loadSystemData()
  }
})

const loadSystemData = async () => {
  try {
    const res = await systemApi.listAll()
    systemList.value = res.data || []
  } catch (e) {
    console.error('加载系统列表失败', e)
  }
}

const handleCategoryChange = async (categoryId) => {
  form.value.config.dataSource.params.metricId = null
  metricList.value = []
  if (!categoryId) return
  try {
    const res = await metricApi.itemByCategory(categoryId)
    metricList.value = res.data || []
  } catch (e) {
    console.error('加载指标列表失败', e)
  }
}

const handleCategoryNameChange = async (categoryName) => {
  form.value.config.dataSource.params.subCategoryName = null
  subCategoryNameList.value = []
  if (!categoryName) return
  try {
    const res = await dataApi.getSubcategories({ categoryName })
    subCategoryNameList.value = res.data || []
  } catch (e) {
    console.error('加载小类列表失败', e)
  }
}

const open = (module, index = -1) => {
  form.value = JSON.parse(JSON.stringify(module))
  editingIndex.value = index
  previewData.value = []
  previewColumns.value = []
  visible.value = true
}

const handleSave = () => {
  if (!form.value.title) {
    return
  }
  emit('save', JSON.parse(JSON.stringify(form.value)), editingIndex.value)
  visible.value = false
}

const updateDisplayColumns = () => {
  form.value.config.display.columns = allColumns.value.filter(c => c.visible).map(c => ({
    prop: c.prop,
    label: c.label,
    width: c.width || null
  }))
}

const handlePreview = async () => {
  previewLoading.value = true
  previewData.value = []
  previewColumns.value = []

  try {
    const { type, config } = form.value
    const { params } = config.dataSource
    let res = null

    switch (type) {
      case 'alarm_list':
        res = await alarmApi.list({ ...params, page: 0, size: 5 })
        previewData.value = res.data?.content || []
        break
      case 'alarm_statistics':
        res = await alarmApi.statistics()
        previewData.value = [res.data || {}]
        break
      case 'alarm_trend':
        res = await alarmApi.getTrend(params)
        previewData.value = res.data || []
        break
      case 'gongdan_list':
        res = await gongdanApi.list({ ...params, page: 0, size: 5 })
        previewData.value = res.data?.content || []
        break
      case 'gongdan_statistics':
        res = await gongdanApi.statistics()
        previewData.value = [res.data || {}]
        break
      case 'issue_list':
        res = await issueApi.list({ ...params, page: 0, size: 5 })
        previewData.value = res.data?.content || []
        break
      case 'issue_statistics':
        res = await issueApi.statistics()
        previewData.value = [res.data || {}]
        break
      case 'data_aggregation':
        res = await dataApi.getDetail({ ...params, page: 0, size: 5 })
        previewData.value = res.data?.content || []
        break
      default:
        previewData.value = []
    }

    // 从数据中提取列信息
    if (previewData.value.length > 0) {
      const firstRow = previewData.value[0]
      previewColumns.value = Object.keys(firstRow).map(key => ({
        prop: key,
        label: key,
        width: 150
      }))

      // 初始化 display.columns
      if (!form.value.config.display.columns?.length) {
        form.value.config.display.columns = previewColumns.value.slice(0, 5).map(c => ({
          prop: c.prop,
          label: c.label,
          width: c.width
        }))
      }
    }
  } catch (e) {
    console.error('预览数据失败', e)
  } finally {
    previewLoading.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.drawer-content {
  padding: 0 16px;
}

.type-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.filter-section {
  margin-bottom: 12px;
}

.preview-action {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.preview-hint {
  font-size: 12px;
  color: #909399;
}

.preview-table-wrapper {
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.preview-more {
  padding: 8px 12px;
  font-size: 12px;
  color: #909399;
  background: #f5f7fa;
  text-align: center;
}

.column-checkboxes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
