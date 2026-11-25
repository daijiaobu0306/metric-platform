<template>
  <div class="observation-container">
    <!-- 系统宏观健康度 -->
    <div class="system-health-section">
      <div class="section-title">
        <span>系统宏观健康度</span>
        <el-select v-model="timeRange" size="small" style="width: 110px;" @change="loadCategoryHealth">
          <el-option label="24小时" value="24h" />
          <el-option label="7天" value="7d" />
          <el-option label="15天" value="15d" />
          <el-option label="1个月" value="30d" />
        </el-select>
      </div>
      <div class="system-overview">
        <div class="total-score" :class="getScoreClass(systemHealthScore)">
          <div class="score-value">{{ systemHealthScore || '-' }}</div>
          <div class="score-label">系统健康度</div>
          <div class="score-desc">{{ getScoreDesc(systemHealthScore) }}</div>
        </div>
        <div class="category-health-row">
          <div
            v-for="cat in categoryHealthList"
            :key="cat.code"
            class="category-health-card compact"
            :class="getScoreClass(cat.score)"
            @click="handleCategoryClick(cat)"
          >
            <div class="card-top">
              <span class="category-name">{{ cat.name }}</span>
              <span class="trend-icon" :class="cat.trend">
                <el-icon v-if="cat.trend === 'up'"><Top /></el-icon>
                <el-icon v-else-if="cat.trend === 'down'"><Bottom /></el-icon>
                <el-icon v-else><Minus /></el-icon>
              </span>
            </div>
            <div class="card-score">{{ cat.score || '-' }}</div>
            <div class="card-bottom">
              <span>{{ cat.metricCount || 0 }}指标</span>
              <span v-if="cat.alarmCount > 0" class="abnormal">{{ cat.alarmCount }}异常</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 指标大类健康度详情 -->
    <div v-if="selectedCategory" class="category-detail-section">
      <!-- 详情头部 -->
      <div class="detail-header">
        <el-button link @click="handleBack">
          <el-icon><ArrowLeft /></el-icon>返回
        </el-button>
        <span class="detail-title">{{ selectedCategory.name }}</span>
        <el-radio-group v-model="chartTimeRange" size="small" @change="handleTimeRangeChange">
          <el-radio-button value="24h">24小时</el-radio-button>
          <el-radio-button value="7d">7天</el-radio-button>
          <el-radio-button value="15d">15天</el-radio-button>
          <el-radio-button value="30d">30天</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 1. 大类健康度趋势图 -->
      <div class="chart-section">
        <div class="chart-title">{{ selectedCategory.name }} - 健康度趋势</div>
        <div id="category-health-chart" class="chart-container category-chart"></div>
      </div>

      <!-- 2. 指标小类健康度图表（所有小类并排，可点击聚焦） -->
      <div class="subcategory-section">
        <div class="section-subtitle">
          <span>指标小类健康度</span>
          <span class="hint-text">（点击小类卡片可聚焦查看该小类指标）</span>
          <el-button v-if="selectedSubCategory" size="small" link type="primary" @click="selectedSubCategory = null">
            显示全部小类
          </el-button>
        </div>
        <div class="subcategory-health-row">
          <div
            v-for="sub in subcategoryList"
            :key="sub.code"
            class="subcategory-health-card"
            :class="{ 'active': selectedSubCategory === sub.code }"
            @click="handleSubcategoryClick(sub)"
          >
            <div class="subcategory-header">
              <span class="subcategory-name">{{ sub.name }}</span>
              <span class="subcategory-score" :class="getScoreClass(sub.score)">
                {{ sub.score ?? '-' }}
              </span>
            </div>
            <div :id="'sub-health-chart-' + sub.code" class="subcategory-chart"></div>
          </div>
        </div>
      </div>

      <!-- 3. 指标图表区域（按小类分组展示） -->
      <div class="metrics-section" v-loading="tableLoading">
        <!-- 提示信息 + 图表控制按钮 -->
        <div class="section-subtitle">
          <span v-if="selectedSubCategory">{{ selectedSubCategory }} - 指标详情</span>
          <span v-else>所有指标详情</span>
          <span class="hint-text">（点击指标当前值可查看实时指标，点击明细可查看原始数据）</span>
          <span class="chart-controls">
            <el-button-group size="small">
              <el-button @click="expandAllCharts" :type="chartDisplayMode === 'full' ? 'primary' : ''">全部展开图表</el-button>
              <el-button @click="collapseAllCharts" :type="chartDisplayMode === 'simplified' ? 'primary' : ''">全部收起图表</el-button>
              <el-button @click="hideAllCharts" :type="chartDisplayMode === 'hidden' ? 'primary' : ''">隐藏图表</el-button>
            </el-button-group>
          </span>
        </div>

        <!-- 动态渲染指标分组 -->
        <div v-for="sub in subcategoryList" :key="sub.code" class="metric-group">
          <div v-if="!selectedSubCategory || selectedSubCategory === sub.code">
            <!-- 系统稽核：按一级分类分组展示 -->
            <template v-if="sub.code === '系统稽核' && selectedCategory?.name === '对外部客户'">
              <div class="group-title" @click="toggleGroup(sub.code)">
                <span>{{ sub.name }}</span>
                <span class="group-count">{{ systemAuditGroups.length }} 分组</span>
                <el-icon class="toggle-icon">
                  <CaretBottom v-if="expandedGroups[sub.code]" /><CaretRight v-else />
                </el-icon>
              </div>
              <div v-if="expandedGroups[sub.code]" class="group-content">
                <div v-if="systemAuditGroups.length === 0" class="no-data-tip">暂无数据</div>
                <div v-for="group in systemAuditGroups" :key="group.code" class="metric-sub-group">
                  <div class="sub-group-title" @click="toggleGroup(group.code)">
                    <span>{{ group.name }}</span>
                    <span class="group-count">{{ group.metrics.length }} 指标</span>
                    <el-icon class="toggle-icon">
                      <CaretBottom v-if="expandedGroups[group.code]" /><CaretRight v-else />
                    </el-icon>
                  </div>
                  <div v-if="expandedGroups[group.code]" class="sub-group-content">
                    <div v-for="metric in group.metrics" :key="metric.metricId" class="metric-chart-item">
                      <el-table v-sticky-header v-mirror-scroll :data="[metric]" border size="small" class="metric-info-table">
                        <el-table-column prop="metricName" label="指标名称" min-width="140" class-name="wrap-cell" />
                        <el-table-column prop="currentValue" label="当前值" width="115" align="center">
                          <template #default="{ row }">
                            <span class="clickable-value" :title="`${row.isStale ? '最近值在所选范围之外。' : ''}${row.sourceNotice || ''} 数据时间：${row.dataTime || '缺失'}`" @click="openValueDialog(row)">
                              {{ row.currentValue ?? '-' }}{{ row.currentValue != null && row.unit ? ` ${row.unit}` : '' }}
                            </span>
                          </template>
                        </el-table-column>
                        <el-table-column prop="healthScore" label="健康度" width="70" align="center">
                          <template #default="{ row }">
                            <el-tag :type="getScoreTagType(row.healthScore)" size="small">
                              {{ row.healthScore ?? '-' }}
                            </el-tag>
                          </template>
                        </el-table-column>
                        <el-table-column prop="status" label="状态" width="60" align="center">
                          <template #default="{ row }">
                            <el-tag v-if="row.status" :type="getStatusTagType(row.status)" size="small">
                              {{ getStatusText(row.status) }}
                            </el-tag>
                            <span v-else>-</span>
                          </template>
                        </el-table-column>
                        <el-table-column label="操作" width="70" align="center">
                          <template #default="{ row }">
                            <el-button size="small" link type="primary" @click="openDrilldown(row)">明细</el-button>
                          </template>
                        </el-table-column>
                      </el-table>
                      <!-- 图表展示区域 -->
                      <div v-if="chartDisplayMode !== 'hidden'" class="metric-charts-row">
                        <!-- 完整图表模式 -->
                        <template v-if="chartDisplayMode === 'full'">
                          <div class="mini-chart-box">
                            <div class="chart-title">{{ metric.metricName }} - 指标值</div>
                            <div :id="'metric-value-chart-' + metric.metricId" class="chart-container"></div>
                          </div>
                          <div class="mini-chart-box">
                            <div class="chart-title">{{ metric.metricName }} - 健康度</div>
                            <div :id="'metric-health-chart-' + metric.metricId" class="chart-container"></div>
                          </div>
                        </template>
                        <!-- 简化图表模式：显示趋势条 -->
                        <template v-else>
                          <div class="simplified-chart-area">
                            <span class="simplified-label">趋势：</span>
                            <div class="simplified-trend-bar" @click="openValueDialog(metric)">
                              <div class="trend-line-placeholder">{{ metric.metricName }}趋势</div>
                              <div class="trend-indicator" :class="getTrendClass(metric.healthScore)">
                                <span class="trend-arrow">▬</span>
                              </div>
                            </div>
                          </div>
                        </template>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>
            <!-- 其他小类：直接展示指标列表 -->
            <template v-else>
              <div class="group-title" @click="toggleGroup(sub.code)">
                <span>{{ sub.name }}</span>
                <span class="group-count">{{ getGroupMetrics(sub.name).length }} 指标</span>
                <el-icon class="toggle-icon">
                  <CaretBottom v-if="expandedGroups[sub.code]" /><CaretRight v-else />
                </el-icon>
              </div>
              <div v-if="expandedGroups[sub.code]" class="group-content">
                <div v-if="getGroupMetrics(sub.name).length === 0" class="no-data-tip">暂无数据</div>
                <div v-for="metric in getGroupMetrics(sub.name)" :key="metric.metricId" class="metric-chart-item">
                  <el-table v-sticky-header v-mirror-scroll :data="[metric]" border size="small" class="metric-info-table">
                    <el-table-column prop="metricName" label="指标名称" min-width="140" class-name="wrap-cell" />
                    <el-table-column prop="currentValue" label="当前值" width="115" align="center">
                      <template #default="{ row }">
                        <span class="clickable-value" :title="`${row.isStale ? '最近值在所选范围之外。' : ''}${row.sourceNotice || ''} 数据时间：${row.dataTime || '缺失'}`" @click="openValueDialog(row)">
                          {{ row.currentValue ?? '-' }}{{ row.currentValue != null && row.unit ? ` ${row.unit}` : '' }}
                        </span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="healthScore" label="健康度" width="70" align="center">
                      <template #default="{ row }">
                        <el-tag :type="getScoreTagType(row.healthScore)" size="small">
                          {{ row.healthScore ?? '-' }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="status" label="状态" width="60" align="center">
                      <template #default="{ row }">
                        <el-tag v-if="row.status" :type="getStatusTagType(row.status)" size="small">
                          {{ getStatusText(row.status) }}
                        </el-tag>
                        <span v-else>-</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="操作" width="70" align="center">
                      <template #default="{ row }">
                        <el-button size="small" link type="primary" @click="openDrilldown(row)">明细</el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                  <!-- 图表展示区域 -->
                  <div v-if="chartDisplayMode !== 'hidden'" class="metric-charts-row">
                    <!-- 完整图表模式 -->
                    <template v-if="chartDisplayMode === 'full'">
                      <div class="mini-chart-box">
                        <div class="chart-title">{{ metric.metricName }} - 指标值</div>
                        <div :id="'metric-value-chart-' + metric.metricId" class="chart-container"></div>
                      </div>
                      <div class="mini-chart-box">
                        <div class="chart-title">{{ metric.metricName }} - 健康度</div>
                        <div :id="'metric-health-chart-' + metric.metricId" class="chart-container"></div>
                      </div>
                    </template>
                    <!-- 简化图表模式：显示趋势条 -->
                    <template v-else>
                      <div class="simplified-chart-area">
                        <span class="simplified-label">趋势：</span>
                        <div class="simplified-trend-bar" @click="openValueDialog(metric)">
                          <div class="trend-line-placeholder">{{ metric.metricName }}趋势</div>
                          <div class="trend-indicator" :class="getTrendClass(metric.healthScore)">
                            <span class="trend-arrow">▬</span>
                          </div>
                        </div>
                      </div>
                    </template>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 实时指标弹窗 -->
  <el-dialog
    v-model="realtimeDialogVisible"
    :title="realtimeDialogTitle"
    width="1000px"
    destroy-on-close
  >
    <div v-loading="realtimeDialogLoading">
      <div v-if="isErrorDrilldown && errorDrilldownResult" class="drilldown-metadata">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="指标名">{{ errorDrilldownResult.metricName }}</el-descriptions-item>
          <el-descriptions-item label="小类">{{ errorDrilldownResult.subCategoryName }}</el-descriptions-item>
          <el-descriptions-item label="阈值">{{ errorDrilldownResult.threshold ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="关键词" v-if="errorDrilldownResult.keywords">
            <span v-for="(kw, idx) in errorDrilldownResult.keywords" :key="idx" style="margin-right:4px;">
              <el-tag size="small" type="info">{{ kw }}</el-tag>
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="数据时间">{{ errorDrilldownResult.dataTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="原始表">{{ errorDrilldownResult.tableName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="字段">{{ errorDrilldownResult.field || '-' }}</el-descriptions-item>
          <el-descriptions-item label="返回行数">
            <el-tag :type="errorDrilldownResult.rowCount > 0 ? 'success' : 'info'" size="small">
              {{ errorDrilldownResult.rowCount ?? 0 }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="errorDrilldownResult.reason" class="drilldown-reason">
          <el-alert :title="errorDrilldownResult.reason" type="warning" :closable="false" show-icon />
        </div>
      </div>

      <div v-else class="realtime-stat-cards">
        <div class="stat-card stat-normal">
          <span class="stat-value">{{ realtimeNormalCount }}</span>
          <span class="stat-label">正常</span>
        </div>
        <div class="stat-card stat-warning">
          <span class="stat-value">{{ realtimeWarningCount }}</span>
          <span class="stat-label">超限</span>
        </div>
      </div>

      <el-table
          v-sticky-header
          v-mirror-scroll
        v-if="isErrorDrilldown"
        :data="realtimeTableData"
        border
        size="small"
        style="margin-top: 10px;"
        max-height="450"
      >
        <el-table-column type="index" label="#" width="50" fixed />
        <el-table-column
          v-for="col in errorDrilldownColumns"
          :key="col"
          :prop="col"
          :label="col"
          min-width="120"
          class-name="wrap-cell"
        />
      </el-table>

      <el-table
          v-sticky-header
          v-mirror-scroll
        v-else
        :data="realtimeTableData"
        border
        size="small"
        :row-class-name="getRealtimeRowClassName"
        style="margin-top: 10px;"
        max-height="400"
      >
        <el-table-column prop="lastInsertTime" label="最新数据时间" width="150" />
        <el-table-column prop="businessName" label="业务名称" min-width="180" class-name="wrap-cell" />
        <el-table-column prop="stepName" label="步骤名称" min-width="160" class-name="wrap-cell" />
        <el-table-column prop="stepType" label="步骤类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.stepType === '页面加载' ? 'success' : 'primary'">
              {{ row.stepType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalAccessCount" label="总访问量" width="100" align="right">
          <template #default="{ row }">
            {{ formatRealtimeNumber(row.totalAccessCount) }}
          </template>
        </el-table-column>
        <el-table-column prop="avgDuration" label="平均时长(秒)" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-danger': row.isOverThreshold }">{{ row.avgDuration ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="threshold" label="阈值" width="80" align="right">
          <template #default="{ row }">
            {{ row.threshold ?? '-' }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-dialog>

  <!-- 数据明细弹窗 -->
  <el-dialog
    v-model="detailDialogVisible"
    :title="detailDialogTitle"
    width="1100px"
    destroy-on-close
  >
    <div v-loading="detailDialogLoading">
      <div class="detail-filter-row">
        <el-select
          v-if="detailDialogData.tableName === 'inner_query'"
          v-model="detailStepType"
          placeholder="选择步骤类型"
          clearable
          style="width: 140px;"
          size="small"
          @change="loadDetailData"
        >
          <el-option label="页面加载" value="页面加载" />
          <el-option label="查询时长" value="查询时长" />
        </el-select>
        <span v-if="detailDialogData.tableName === 'inner_step_response'" class="filter-tip">
          步骤类型：{{ detailDialogData.stepTypes || '全部' }}
        </span>
        <span v-if="detailDialogData.tableName === 'inner_button_count'" class="filter-tip">
          类型：办理按钮总数
        </span>
        <span v-if="detailDialogData.tableName === 'inner_business_duration'" class="filter-tip">
          类型：全程办理时长
        </span>
      </div>
      <el-table
          v-sticky-header
          v-mirror-scroll
        :data="detailTableData"
        border
        size="small"
        style="margin-top: 10px;"
        max-height="450"
      >
        <template v-if="detailDialogData.tableName === 'inner_query'">
          <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row) => row.insert_time ? row.insert_time.replace('T', ' ') : ''" />
          <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
          <el-table-column prop="step_type" label="步骤类型" width="100" />
          <el-table-column prop="step_name" label="步骤名称" min-width="160" class-name="wrap-cell" />
          <el-table-column prop="access_count" label="访问量" width="100" align="right" />
          <el-table-column prop="avg_duration_sec" label="平均时长(秒)" width="120" align="right" />
        </template>
        <template v-else-if="detailDialogData.tableName === 'inner_step_response'">
          <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row) => row.insert_time ? row.insert_time.replace('T', ' ') : ''" />
          <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
          <el-table-column prop="step_type" label="步骤类型" width="100" />
          <el-table-column prop="step_name" label="步骤名称" min-width="160" class-name="wrap-cell" />
          <el-table-column prop="access_count" label="访问量" width="100" align="right" />
          <el-table-column prop="avg_response_sec" label="响应时长(秒)" width="110" align="right" />
          <el-table-column prop="interval_sec" label="间隔时长(秒)" width="110" align="right" />
        </template>
        <template v-else-if="detailDialogData.tableName === 'inner_button_count'">
          <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row) => row.insert_time ? row.insert_time.replace('T', ' ') : ''" />
          <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
          <el-table-column prop="button_count" label="办理按钮总数" width="120" align="right" />
        </template>
        <template v-else-if="detailDialogData.tableName === 'inner_business_duration'">
          <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row) => row.insert_time ? row.insert_time.replace('T', ' ') : ''" />
          <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
          <el-table-column prop="full_duration_sec" label="全程办理时长(秒)" width="140" align="right" />
        </template>
      </el-table>
      <el-pagination
        v-model:current-page="detailPage"
        v-model:page-size="detailPageSize"
        :total="detailTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 10px; justify-content: flex-end;"
        @size-change="loadDetailData"
        @current-change="loadDetailData"
      />
    </div>
  </el-dialog>
  <MetricObservationDialog v-model="observationDialogVisible" :metric="observationDialogMetric" :time-range="chartTimeRange" />
</template>

<script setup>
import MetricObservationDialog from '../../components/common/MetricObservationDialog.vue'
const observationDialogVisible = ref(false)
const observationDialogMetric = ref(null)
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { observationApi, realtimeApi, dataApi, errorDrilldownApi } from '@/api'
import * as echarts from 'echarts'

const timeRange = ref('7d')
const chartTimeRange = ref('7d')
const systemHealthScore = ref(0)
const tableLoading = ref(false)

const categoryHealthList = ref([
  { code: 'external', name: '对外部客户', score: null, trend: 'stable', metricCount: 43, alarmCount: 0 },
  { code: 'internal', name: '对内部人员', score: null, trend: 'stable', metricCount: 12, alarmCount: 0 },
  { code: 'system', name: '运行效率', score: null, trend: 'stable', metricCount: 18, alarmCount: 0 },
  { code: 'plan', name: '计划执行', score: null, trend: 'stable', metricCount: 3, alarmCount: 0 }
])

const selectedCategory = ref(null)
const selectedSubCategory = ref(null) // 选中的小类，为null表示显示全部
const metricList = ref([]) // 所有指标列表

// 子类列表（根据选中类别动态变化）
const subcategoryList = computed(() => {
  const cat = selectedCategory.value?.name
  if (cat === '对外部客户') {
    return [{ code: '系统稽核', name: '系统稽核', score: null, data: [] }]
  }
  // 默认对内部人员
  return [
    { code: '查询类', name: '查询类', score: null, data: [] },
    { code: '办理类-单业务', name: '办理类-单业务', score: null, data: [] },
    { code: '办理类-融合业务', name: '办理类-融合业务', score: null, data: [] }
  ]
})

// 展开/折叠状态
const expandedGroups = ref({
  '查询类': true,
  '办理类-单业务': true,
  '办理类-融合业务': true,
  '系统稽核': true
})

// 图表展示模式: 'simplified'=简化(仅趋势线), 'full'=完整图表, 'hidden'=隐藏
const chartDisplayMode = ref('simplified')

// 图表展示模式控制
const expandAllCharts = () => {
  chartDisplayMode.value = 'full'
  nextTick(() => {
    initAllMetricCharts()
  })
}

const collapseAllCharts = () => {
  chartDisplayMode.value = 'simplified'
}

const hideAllCharts = () => {
  chartDisplayMode.value = 'hidden'
}

// 获取简化图表的趋势样式类
const getTrendClass = (healthScore) => {
  if (healthScore === null || healthScore === undefined) return 'trend-unknown'
  if (healthScore >= 90) return 'trend-good'
  if (healthScore >= 70) return 'trend-warning'
  return 'trend-alarm'
}

// 系统稽核指标分组（按一级分类分组）
const systemAuditGroups = computed(() => {
  if (selectedCategory.value?.name !== '对外部客户') return []
  const metrics = metricList.value.filter(m => m.subCategoryName === '系统稽核')
  const groups = {}
  metrics.forEach(m => {
    const firstClassify = m.firstClassify || '未分类'
    if (!groups[firstClassify]) {
      groups[firstClassify] = []
    }
    groups[firstClassify].push(m)
  })
  return Object.entries(groups).map(([name, items]) => ({
    code: name,
    name: name,
    metrics: items
  }))
})

const getScoreClass = (score) => {
  if (score === null || score === undefined) return ''
  if (score >= 90) return 'score-excellent'
  if (score >= 70) return 'score-good'
  return 'score-poor'
}

const getScoreDesc = (score) => {
  if (score === null || score === undefined) return '暂无数据'
  if (score >= 90) return '系统运行良好'
  if (score >= 70) return '部分指标需关注'
  return '需要尽快处理'
}

const getScoreTagType = (score) => {
  if (score === null || score === undefined) return 'info'
  if (score >= 90) return 'success'
  if (score >= 70) return 'warning'
  return 'danger'
}

const getStatusTagType = (status) => {
  if (status === 'normal' || status === '正常') return 'success'
  if (status === 'warning' || status === '预警') return 'warning'
  if (status === 'alarm' || status === '告警' || status === '工单') return 'danger'
  return 'info'
}

const getStatusText = (status) => {
  if (status === 'normal') return '正常'
  if (status === 'warning') return '预警'
  if (status === 'alarm') return '告警'
  return status
}

// 按小类分组获取指标
const getGroupMetrics = (subCategoryName) => {
  if (!metricList.value || metricList.value.length === 0) return []
  return metricList.value.filter(m => m.subCategoryName === subCategoryName)
}

// 点击大类卡片
const handleCategoryClick = (cat) => {
  selectedCategory.value = cat
  selectedSubCategory.value = null
  // 等待DOM渲染完成后再加载数据
  nextTick(() => {
    loadAllData()
  })
}

// 返回
const handleBack = () => {
  selectedCategory.value = null
  selectedSubCategory.value = null
}

// 点击小类卡片
const handleSubcategoryClick = (sub) => {
  if (selectedSubCategory.value === sub.code) {
    selectedSubCategory.value = null // 再次点击取消选中
  } else {
    selectedSubCategory.value = sub.code
  }
  nextTick(() => {
    initSubcategoryCharts()
    // 重新初始化可见的指标图表
    const visibleMetrics = metricList.value.filter(m =>
      !selectedSubCategory.value || m.subCategoryName === selectedSubCategory.value
    )
    visibleMetrics.forEach(m => initMetricCharts(m))
  })
}

// 切换时间范围
const handleTimeRangeChange = () => {
  loadAllData()
}

// 加载所有数据
const loadAllData = async () => {
  tableLoading.value = true
  try {
    // 等待DOM渲染完成
    await nextTick()
    // 等待图表容器DOM完全渲染
    await new Promise(resolve => setTimeout(resolve, 50))

    await Promise.all([
      loadCategoryHealthChart(),
      loadSubcategoryMetrics()
    ])

    await nextTick()
    initSubcategoryCharts()
    initAllMetricCharts()
  } finally {
    tableLoading.value = false
  }
}

// 加载大类健康度图表
const loadCategoryHealthChart = async () => {
  const chartDom = document.getElementById('category-health-chart')
  if (!chartDom) {
    console.warn('category-health-chart DOM not found, retrying...')
    await new Promise(resolve => setTimeout(resolve, 100))
    return loadCategoryHealthChart()
  }

  const chart = echarts.getInstanceByDom(chartDom)
  if (chart) chart.dispose()

  const res = await observationApi.getCategoryHealthTrend({
    categoryName: selectedCategory.value?.name || '对内部人员',
    timeRange: chartTimeRange.value
  })

  const data = res.data?.data || []
  // 只显示时间部分 HH:mm
  const times = data.map(d => {
    const t = d.recordTime || ''
    if (!t) return ''
    return t.slice(11, 16) // HH:mm
  })
  const scores = data.map(d => d.healthScore ?? null)
  const yRange = calculateYRange(scores, 0, 100)
  const xAxisInterval = times.length > 15 ? Math.floor(times.length / 8) : 0

  const chartInstance = echarts.init(chartDom)
  chartInstance.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '6%', right: '4%', bottom: '12%', containLabel: true },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: {
        fontSize: 10,
        interval: xAxisInterval,
        rotate: 0
      }
    },
    yAxis: { type: 'value', min: yRange.min, max: yRange.max, interval: Math.ceil((yRange.max - yRange.min) / 5), axisLabel: { fontSize: 10, formatter: (val) => Math.round(val) } },
    series: [{
      name: '健康度',
      type: 'line',
      data: scores,
      smooth: true,
      lineStyle: { width: 2 },
      areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: 'rgba(103,194,58,0.3)' },
        { offset: 1, color: 'rgba(103,194,58,0.05)' }
      ]) },
      itemStyle: { color: '#67c23a' }
    }]
  })
}

// 加载各小类指标数据
const loadSubcategoryMetrics = async () => {
  const categoryName = selectedCategory.value?.name || '对内部人员'
  let subcategories = []
  if (categoryName === '对内部人员') {
    subcategories = ['查询类', '办理类-单业务', '办理类-融合业务']
  } else if (categoryName === '对外部客户') {
    subcategories = ['系统稽核']
  }

  for (const subName of subcategories) {
    try {
      const res = await observationApi.getSubcategoryMetrics({
        categoryName: categoryName,
        subCategoryName: subName
      })

      const metrics = res.data?.metrics || []
      const sub = subcategoryList.value.find(s => s.code === subName)
      if (sub) {
        sub.data = metrics
        // 计算该小类的健康度（取最新记录）
        if (metrics.length > 0) {
          const latestHealth = metrics.reduce((prev, curr) =>
            (curr.recordTime || '') > (prev.recordTime || '') ? curr : prev
          , metrics[0])
          sub.score = latestHealth.healthScore
        } else {
          sub.score = null
        }
      }

      // 合并到总指标列表
      metrics.forEach(m => {
        if (!metricList.value.find(existing => existing.metricId === m.metricId)) {
          metricList.value.push(m)
        }
      })
    } catch (error) {
      console.error(`加载${subName}指标失败:`, error)
    }
  }
}

// 初始化小类健康度图表
const initSubcategoryCharts = async () => {
  for (const sub of subcategoryList.value) {
    const chartDom = document.getElementById('sub-health-chart-' + sub.code)
    if (!chartDom) continue

    const chart = echarts.getInstanceByDom(chartDom)
    if (chart) chart.dispose()

    try {
      const res = await observationApi.getSubcategoryHealthTrend({
        categoryName: selectedCategory.value?.name || '对内部人员',
        subCategoryName: sub.code,
        timeRange: chartTimeRange.value
      })

      const data = res.data?.data || []
      // 只显示时间部分 HH:mm
      const times = data.map(d => {
        const t = d.recordTime || ''
        if (!t) return ''
        return t.slice(11, 16) // HH:mm
      })
      const scores = data.map(d => d.healthScore ?? null)
      const yRange = calculateYRange(scores, 0, 100)

      // 根据数据点数量计算interval，避免标签拥挤
      const labelInterval = times.length > 20 ? Math.floor(times.length / 10) : 0

      const chartInstance = echarts.init(chartDom)
      chartInstance.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: '8%', right: '4%', bottom: '15%', containLabel: true },
        xAxis: {
          type: 'category',
          data: times,
          axisLabel: {
            fontSize: 9,
            interval: labelInterval,
            rotate: 0
          },
          axisLine: { onZero: false }
        },
        yAxis: {
          type: 'value',
          min: yRange.min,
          max: yRange.max,
          interval: Math.ceil((yRange.max - yRange.min) / 5),
          axisLabel: {
            fontSize: 10,
            formatter: (val) => Math.round(val)
          }
        },
        series: [{
          name: '健康度',
          type: 'line',
          data: scores,
          smooth: true,
          lineStyle: { width: 1.5 },
          itemStyle: { color: selectedSubCategory.value === sub.code ? '#409eff' : '#67c23a' },
          areaStyle: { opacity: 0.2 }
        }]
      })
    } catch (error) {
      console.error(`加载${sub.code}健康度图表失败:`, error)
    }
  }
}

// 初始化所有指标图表
const initAllMetricCharts = async () => {
  for (const metric of metricList.value) {
    await initMetricCharts(metric)
  }
}

const initMetricCharts = async (metric) => {
  let valueChartDom = document.getElementById('metric-value-chart-' + metric.metricId)
  let healthChartDom = document.getElementById('metric-health-chart-' + metric.metricId)

  // 如果DOM不存在，等待重试
  if (!valueChartDom || !healthChartDom) {
    await new Promise(resolve => setTimeout(resolve, 100))
    valueChartDom = document.getElementById('metric-value-chart-' + metric.metricId)
    healthChartDom = document.getElementById('metric-health-chart-' + metric.metricId)
  }

  if (!valueChartDom || !healthChartDom) return

  const valueChart = echarts.getInstanceByDom(valueChartDom)
  if (valueChart) valueChart.dispose()

  const healthChart = echarts.getInstanceByDom(healthChartDom)
  if (healthChart) healthChart.dispose()

  try {
    const [valueRes, healthRes] = await Promise.all([
      observationApi.getMetricValueTrend({
        metricId: metric.metricId,
        timeRange: chartTimeRange.value
      }),
      observationApi.getMetricHealthTrend({
        metricId: metric.metricId,
        timeRange: chartTimeRange.value
      })
    ])

    // 指标值图表 - 处理时间格式
    const valueData = valueRes.data?.data || []
    const valueTimes = valueData.map(d => {
      const t = d.recordTime || ''
      if (!t) return ''
      const timePart = t.slice(11, 16) // HH:mm
      return timePart
    })
    const valueValues = valueData.map(d => d.metricValue ?? null)
    const valueYRange = calculateYRange(valueValues)
    const valueInterval = valueTimes.length > 20 ? Math.floor(valueTimes.length / 8) : 0

    const valueChartInstance = echarts.init(valueChartDom)
    valueChartInstance.setOption({
      title: { subtext: valueData.length ? valueRes.data?.notice || '' : '当前范围暂无指标值历史', left: 'center', bottom: 24, subtextStyle: { fontSize: 10, color: '#909399' } },
      tooltip: { trigger: 'axis' },
      grid: { left: '6%', right: '4%', bottom: '12%', containLabel: true },
      xAxis: {
        type: 'category',
        data: valueTimes,
        axisLabel: {
          fontSize: 9,
          interval: valueInterval,
          rotate: 0
        }
      },
      yAxis: { type: 'value', min: valueYRange.min, max: valueYRange.max, interval: Math.ceil((valueYRange.max - valueYRange.min) / 5), axisLabel: { fontSize: 10 } },
      series: [{
        name: metric.metricName,
        type: 'line',
        data: valueValues,
        connectNulls: false,
        smooth: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#409eff' }
      }]
    })

    // 健康度图表
    const healthData = healthRes.data?.data || []
    const healthTimes = healthData.map(d => {
      const t = d.recordTime || ''
      if (!t) return ''
      const timePart = t.slice(11, 16) // HH:mm
      return timePart
    })
    const healthValues = healthData.map(d => d.healthScore ?? null)
    const healthYRange = calculateYRange(healthValues, 0, 100)
    const healthInterval = healthTimes.length > 20 ? Math.floor(healthTimes.length / 8) : 0

    const healthChartInstance = echarts.init(healthChartDom)
    healthChartInstance.setOption({
      title: { subtext: healthData.length ? '' : healthRes.data?.notice || '当前范围暂无健康度历史', left: 'center', bottom: 24, subtextStyle: { fontSize: 10, color: '#909399' } },
      tooltip: { trigger: 'axis' },
      grid: { left: '6%', right: '4%', bottom: '12%', containLabel: true },
      xAxis: {
        type: 'category',
        data: healthTimes,
        axisLabel: {
          fontSize: 9,
          interval: healthInterval,
          rotate: 0
        }
      },
      yAxis: { type: 'value', min: healthYRange.min, max: healthYRange.max, interval: Math.ceil((healthYRange.max - healthYRange.min) / 5), axisLabel: { fontSize: 10 } },
      series: [{
        name: metric.metricName,
        type: 'line',
        data: healthValues,
        connectNulls: false,
        smooth: false,
        lineStyle: { width: 2 },
        itemStyle: { color: '#e6a23c' }
      }]
    })
  } catch (error) {
    console.error('加载指标图表失败:', metric.metricName, error)
  }
}

const calculateYRange = (data, defaultMin, defaultMax) => {
  if (!data || data.length === 0) {
    return { min: defaultMin ?? 0, max: defaultMax ?? 100 }
  }

  const validData = data.filter(v => v !== null && v !== undefined && !isNaN(v))
  if (validData.length === 0) {
    return { min: defaultMin ?? 0, max: defaultMax ?? 100 }
  }

  const minVal = Math.min(...validData)
  const maxVal = Math.max(...validData)

  // 计算合适的范围，确保有足够间隔
  let yMin = Math.floor(minVal)
  let yMax = Math.ceil(maxVal)

  // 确保最小间隔为10
  if (yMax - yMin < 10) {
    const center = Math.round((yMin + yMax) / 2)
    yMin = center - 5
    yMax = center + 5
  }

  // 确保不超出默认范围
  if (defaultMin !== undefined) yMin = Math.max(defaultMin, yMin)
  if (defaultMax !== undefined) yMax = Math.min(defaultMax, yMax)

  return { min: yMin, max: yMax }
}

// 切换分组展开/折叠
const toggleGroup = (groupKey) => {
  const newState = !expandedGroups.value[groupKey]
  expandedGroups.value = { ...expandedGroups.value, [groupKey]: newState }
}

// 实时指标弹窗
const realtimeDialogVisible = ref(false)
const realtimeDialogTitle = ref('')
const realtimeDialogLoading = ref(false)
const realtimeTableData = ref([])
const realtimeNormalCount = computed(() => realtimeTableData.value.filter(r => !r.isOverThreshold).length)
const realtimeWarningCount = computed(() => realtimeTableData.value.filter(r => r.isOverThreshold).length)
const errorDrilldownResult = ref(null)
const errorDrilldownColumns = ref([])
const isErrorDrilldown = ref(false)

const ERROR_SUBCATEGORY_NAMES = ['系统异常', '前端异常', '系统异常-接口', '系统异常-实例', '系统异常-JS', '系统异常-业务返回', '系统异常-返回码']
const ERROR_SUBCATEGORY_CODES = ['error', 'error_frontend']
const isErrorSubcategory = (subCategoryCode, subCategoryName) => {
  if (subCategoryCode && ERROR_SUBCATEGORY_CODES.includes(subCategoryCode)) return true
  if (subCategoryName && ERROR_SUBCATEGORY_NAMES.includes(subCategoryName)) return true
  if (subCategoryName && subCategoryName.includes('异常')) return true
  return false
}

const loadErrorDrilldownData = async (metric) => {
  const res = await errorDrilldownApi.getDrilldown(metric.metricId)
  const data = res.data || {}
  errorDrilldownResult.value = data
  const content = data.content || []
  realtimeTableData.value = content
  if (content.length > 0) {
    errorDrilldownColumns.value = Object.keys(content[0])
  } else {
    errorDrilldownColumns.value = ['__info__']
  }
  return data
}

const openValueDialog = (metric) => {
  observationDialogMetric.value = metric
  observationDialogVisible.value = true
}

const getRealtimeRowClassName = ({ row }) => {
  return row.isOverThreshold ? 'row-danger' : ''
}

const formatRealtimeNumber = (num) => {
  if (num == null) return '-'
  if (num >= 10000) {
    return (num / 10000).toFixed(1) + '万'
  }
  return num.toLocaleString()
}

// 数据明细弹窗
const detailDialogVisible = ref(false)
const detailDialogTitle = ref('')
const detailDialogLoading = ref(false)
const detailTableData = ref([])
const detailDialogData = ref({ tableName: '', stepTypes: '', subCategoryName: '' })
const detailStepType = ref('')
const detailPage = ref(1)
const detailPageSize = ref(20)
const detailTotal = ref(0)

const openDrilldown = async (metric) => {
  const metricName = metric.metricName || ''
  const subCategoryName = metric.subCategoryName || ''
  const subCategoryCode = metric.subCategoryCode
  const isError = isErrorSubcategory(subCategoryCode, subCategoryName)

  // error / error_frontend 小类：复用 realtimeDialog 的下钻模式（不再用 detailDialog）
  if (isError) {
    realtimeDialogTitle.value = `数据明细 - ${metricName} (${metric.metricId || '无 ID'})`
    realtimeDialogVisible.value = true
    realtimeDialogLoading.value = true
    realtimeTableData.value = []
    errorDrilldownResult.value = null
    errorDrilldownColumns.value = []
    isErrorDrilldown.value = true
    try {
      await loadErrorDrilldownData(metric)
    } catch (error) {
      console.error('加载 error 明细失败:', error)
    } finally {
      realtimeDialogLoading.value = false
    }
    return
  }

  // inner_* / system_jhd 小类：原 detailDialog 行为
  let tableName = 'inner_step_response'
  let stepTypes = ''

  if (subCategoryName === '查询类') {
    tableName = 'inner_query'
    const lowerName = metricName.toLowerCase()
    if (lowerName.includes('页面加载') || lowerName.includes('page')) {
      stepTypes = '页面加载'
    } else if (lowerName.includes('查询时长') || lowerName.includes('query')) {
      stepTypes = '查询时长'
    } else {
      stepTypes = '页面加载,查询时长'
    }
  } else {
    const lowerName = metricName.toLowerCase()
    if (lowerName.includes('页面加载')) {
      tableName = 'inner_step_response'
      stepTypes = '页面加载'
    } else if (lowerName.includes('步骤响应')) {
      tableName = 'inner_step_response'
      stepTypes = '步骤时长'
    } else if (lowerName.includes('步骤间隔')) {
      tableName = 'inner_step_response'
      stepTypes = '步骤时长'
    } else if (lowerName.includes('按钮')) {
      tableName = 'inner_button_count'
      stepTypes = '办理按钮总数'
    } else if (lowerName.includes('全程')) {
      tableName = 'inner_business_duration'
      stepTypes = '全程办理时长'
    } else {
      stepTypes = '页面加载,步骤时长,办理按钮总数'
    }
  }

  detailDialogTitle.value = `数据明细 - ${metricName} (${metric.metricId || '无 metricId'})`
  detailDialogData.value = {
    metricId: metric.metricId,
    tableName: tableName,
    stepTypes: stepTypes,
    subCategoryName: subCategoryName
  }
  detailStepType.value = stepTypes.split(',')[0] || ''
  detailPage.value = 1
  detailPageSize.value = 20
  detailDialogVisible.value = true
  loadDetailData()
}

const loadDetailData = async () => {
  detailDialogLoading.value = true
  detailTableData.value = []

  try {
    const params = {
      metricId: detailDialogData.value.metricId,
      tableName: detailDialogData.value.tableName,
      subCategoryName: detailDialogData.value.subCategoryName,
      page: detailPage.value - 1,
      size: detailPageSize.value
    }

    if (detailDialogData.value.tableName === 'inner_query' && detailStepType.value) {
      params.stepType = detailStepType.value
    }

    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })

    const res = await dataApi.getDetail(params)
    detailTableData.value = res.data?.content || []
    detailTotal.value = res.data?.total || 0
  } catch (error) {
    console.error('加载数据明细失败:', error)
  } finally {
    detailDialogLoading.value = false
  }
}

const loadCategoryHealth = async () => {
  // 为所有类别加载健康度
  const categories = ['对内部人员', '对外部客户']
  try {
    for (const catName of categories) {
      const res = await observationApi.getCategoryHealthTrend({
        categoryName: catName,
        timeRange: timeRange.value
      })
      if (res.data && res.data.data && res.data.data.length > 0) {
        const latest = res.data.data[res.data.data.length - 1]
        const code = catName === '对内部人员' ? 'internal' : 'external'
        const targetCat = categoryHealthList.value.find(c => c.code === code)
        if (targetCat) {
          targetCat.score = latest.healthScore
          targetCat.alarmCount = latest.alarmCount || 0
          targetCat.trend = latest.status === 'alarm' ? 'down' : (latest.status === 'warning' ? 'down' : 'up')
        }
        // 更新系统总体健康度（取对内部人员的值）
        if (catName === '对内部人员') {
          systemHealthScore.value = latest.healthScore || 0
        }
      }
    }
  } catch (error) {
    console.error('加载大类健康度失败:', error)
  }
}

watch(chartTimeRange, () => {
  if (selectedCategory.value) {
    loadAllData()
  }
})

onMounted(() => {
  loadCategoryHealth()
})
</script>

<style scoped>
.observation-container {
  padding: 6px;
}

.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: bold;
  color: #303133;
  margin-bottom: 8px;
}

.system-health-section {
  margin-bottom: 12px;
}

.system-overview {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.total-score {
  flex-shrink: 0;
  width: 120px;
  padding: 12px;
  border-radius: 6px;
  text-align: center;
}

.score-excellent {
  background: linear-gradient(135deg, #e1f3d8 0%, #f0f9eb 100%);
  color: #67c23a;
}

.score-good {
  background: linear-gradient(135deg, #fdf6ec 0%, #fef0e6 100%);
  color: #e6a23c;
}

.score-poor {
  background: linear-gradient(135deg, #fef0f0 0%, #ffe1e1 100%);
  color: #f56c6c;
}

.total-score:not(.score-excellent):not(.score-good):not(.score-poor) {
  background: #f5f5f5;
  color: #909399;
}

.score-value {
  font-size: 36px;
  font-weight: bold;
  line-height: 1;
}

.score-label {
  font-size: 12px;
  margin-top: 4px;
}

.score-desc {
  font-size: 11px;
  margin-top: 4px;
  opacity: 0.8;
}

.category-health-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  flex: 1;
}

.category-health-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
}

.category-health-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.category-health-card.score-excellent {
  border-left: 3px solid #67c23a;
}

.category-health-card.score-good {
  border-left: 3px solid #e6a23c;
}

.category-health-card.score-poor {
  border-left: 3px solid #f56c6c;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.category-name {
  font-size: 12px;
  font-weight: 500;
  color: #303133;
}

.trend-icon {
  font-size: 14px;
}

.trend-icon.up { color: #67c23a; }
.trend-icon.down { color: #f56c6c; }
.trend-icon.stable { color: #909399; }

.card-score {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
  text-align: center;
  margin: 4px 0;
}

.card-bottom {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #909399;
}

.abnormal {
  color: #f56c6c;
}

/* 详情页面 */
.category-detail-section {
  background: #fff;
  border-radius: 6px;
  padding: 12px;
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.detail-title {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
}

.chart-section {
  margin-bottom: 16px;
}

.chart-title {
  font-size: 12px;
  color: #606266;
  margin-bottom: 6px;
  font-weight: 500;
}

.chart-container {
  height: 200px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.chart-container.category-chart {
  height: 220px;
}

/* 小类健康度区域 */
.subcategory-section {
  margin-bottom: 16px;
}

.section-subtitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #606266;
  margin-bottom: 8px;
}

.hint-text {
  color: #909399;
  font-size: 11px;
}

.subcategory-health-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.subcategory-health-card {
  background: #fafafa;
  border: 2px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  cursor: pointer;
  transition: all 0.2s;
}

.subcategory-health-card:hover {
  border-color: #c0c4cc;
}

.subcategory-health-card.active {
  border-color: #409eff;
  background: #fff;
}

.subcategory-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.subcategory-name {
  font-size: 12px;
  font-weight: 500;
  color: #303133;
}

.subcategory-score {
  font-size: 20px;
  font-weight: bold;
}

.subcategory-score.score-excellent { color: #67c23a; }
.subcategory-score.score-good { color: #e6a23c; }
.subcategory-score.score-poor { color: #f56c6c; }

.subcategory-chart {
  height: 150px;
}

/* 指标分组 */
.metrics-section {
  margin-top: 16px;
}

.metric-group {
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #f5f7fa;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  color: #303133;
}

.group-title:hover {
  background: #ebeef5;
}

.group-count {
  color: #909399;
  font-weight: normal;
}

.toggle-icon {
  margin-left: auto;
}

.group-content {
  padding: 10px;
}

.metric-sub-group {
  margin-bottom: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.sub-group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fafafa;
  cursor: pointer;
  font-size: 12px;
  color: #606266;
}

.sub-group-title:hover {
  background: #f0f0f0;
}

.sub-group-content {
  padding: 8px;
}

.no-data-tip {
  text-align: center;
  color: #909399;
  padding: 20px;
}

.metric-chart-item {
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px;
}

.metric-info-table {
  margin-bottom: 10px;
}

.metric-charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.mini-chart-box {
  min-width: 0;
}

@media (max-width: 900px) {
  .metric-charts-row {
    grid-template-columns: 1fr;
  }
  .subcategory-health-row {
    grid-template-columns: 1fr;
  }
}

/* 可点击的数值样式 */
.clickable-value {
  color: #409eff;
  cursor: pointer;
  text-decoration: underline dotted;
}
.clickable-value:hover {
  color: #66b1ff;
}

/* 实时指标弹窗统计卡片 */
.realtime-stat-cards {
  display: flex;
  gap: 20px;
  margin-bottom: 10px;
}
.stat-card {
  padding: 8px 16px;
  border-radius: 4px;
  text-align: center;
}
.stat-normal {
  background: linear-gradient(135deg, #e1f3d8 0%, #f0f9eb 100%);
  color: #67c23a;
}
.stat-warning {
  background: linear-gradient(135deg, #fdf6ec 0%, #fef0e6 100%);
  color: #e6a23c;
}
.stat-value {
  font-size: 24px;
  font-weight: bold;
  display: block;
}
.stat-label {
  font-size: 12px;
}

/* 表格中超限数值样式 */
.text-danger {
  color: #f56c6c;
  font-weight: bold;
}

/* 实时指标表格超限行样式 */
:deep(.row-danger) {
  background-color: #fff5f5 !important;
}

/* 数据明细弹窗筛选区域 */
.detail-filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.filter-tip {
  font-size: 12px;
  color: #606266;
}

/* 图表控制按钮 */
.chart-controls {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 简化图表样式 */
.simplified-chart-area {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: #fafafa;
  border-radius: 4px;
  min-height: 60px;
}

.simplified-label {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}

.simplified-trend-bar {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.2s;
}

.simplified-trend-bar:hover {
  background: #f0f0f0;
}

.trend-line-placeholder {
  font-size: 11px;
  color: #606266;
  flex: 1;
}

.trend-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 50px;
  height: 24px;
  border-radius: 4px;
}

.trend-indicator.trend-good {
  background: #e1f3d8;
  color: #67c23a;
}

.trend-indicator.trend-warning {
  background: #fdf6ec;
  color: #e6a23c;
}

.trend-indicator.trend-alarm {
  background: #fff0f0;
  color: #f56c6c;
}

.trend-indicator.trend-unknown {
  background: #f4f4f5;
  color: #909399;
}

.trend-arrow {
  font-size: 16px;
}
</style>
