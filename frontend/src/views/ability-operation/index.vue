<template>
  <div class="ability-operation-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>能力运营</span>
          <div class="header-actions">
            <el-button type="primary" size="small" @click="handleExport">
              <el-icon><Download /></el-icon>导出
            </el-button>
            <el-button size="small" @click="handleScan">
              <el-icon><Refresh /></el-icon>触发扫描
            </el-button>
          </div>
        </div>
      </template>

      <!-- 标签页切换 -->
      <el-tabs v-model="activeTab" class="main-tabs">
        <el-tab-pane label="数据" name="data">
          <!-- 数据Tab内容 -->
          <!-- 筛选条件 -->
          <div class="filter-area">
            <el-date-picker
              v-model="filters.dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYYMMDD"
              size="small"
              style="width: 220px;"
            />
            <el-select v-model="filters.regionCode" placeholder="区域" clearable filterable size="small" style="width: 100px;">
              <el-option v-for="r in regionOptions" :key="r" :label="r" :value="r" />
            </el-select>
            <el-select v-model="filters.functionCode" placeholder="功能码" clearable filterable size="small" style="width: 100px;">
              <el-option v-for="f in functionOptions" :key="f" :label="f" :value="f" />
            </el-select>
            <el-select v-model="filters.capabilityCode" placeholder="能力编码" clearable filterable size="small" style="width: 140px;" @change="loadYoYMoM">
              <el-option v-for="cap in capabilityOptions" :key="cap" :label="cap" :value="cap" />
            </el-select>
            <el-select v-model="filters.serviceOrgCode" placeholder="服务机构" clearable filterable size="small" style="width: 120px;">
              <el-option v-for="s in serviceOrgOptions" :key="s" :label="s" :value="s" />
            </el-select>
            <el-select v-model="filters.sceneCode" placeholder="场景编码" clearable filterable size="small" style="width: 120px;">
              <el-option v-for="s in sceneOptions" :key="s" :label="s" :value="s" />
            </el-select>
            <el-select v-model="filters.userCode" placeholder="使用方" clearable filterable size="small" style="width: 150px;">
              <el-option v-for="user in userOptions" :key="user.code" :label="user.name + '(' + user.code + ')'" :value="user.code" />
            </el-select>
            <el-button type="primary" size="small" @click="loadData">
              <el-icon><Search /></el-icon>查询
            </el-button>
            <el-button size="small" @click="resetFilters">重置</el-button>
          </div>

          <!-- 工具栏：列设置 + 统计概览 -->
          <div class="table-toolbar">
            <div class="toolbar-left">
              <!-- 列设置 -->
              <el-dropdown trigger="click" @command="handleColumnCommand">
                <el-button size="small">
                  <el-icon><Setting /></el-icon>列设置
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <div class="column-settings">
                      <div class="column-settings-header">
                        <el-checkbox v-model="allColumnsSelected" @change="handleSelectAll">全选</el-checkbox>
                        <el-button size="small" link @click="handleInverseSelect">反选</el-button>
                        <el-button size="small" link @click="handleResetSelect">重置</el-button>
                      </div>
                      <el-scrollbar height="300px">
                        <el-checkbox-group v-model="selectedColumns" @change="handleColumnsChange">
                          <el-dropdown-item v-for="col in allColumns" :key="col.prop" :command="col.prop">
                            <el-checkbox :value="col.prop" style="width: 100%;">{{ col.label }}</el-checkbox>
                          </el-dropdown-item>
                        </el-checkbox-group>
                      </el-scrollbar>
                    </div>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <div class="toolbar-right">
              <span class="summary-tag">总记录: {{ pagination.total }}</span>
              <span class="summary-tag">调用量: {{ summaryData.totalInvoke | formatNumber }}</span>
              <span class="summary-tag">失败量: {{ summaryData.totalFailure | formatNumber }}</span>
            </div>
          </div>

          <!-- 同比环比卡片 -->
          <div v-if="yoyMomData.currentValue !== undefined" class="analysis-cards">
            <el-row :gutter="12">
              <el-col :span="6">
                <el-card shadow="hover">
                  <div class="stat-card">
                    <div class="stat-label">当前周期调用量</div>
                    <div class="stat-value">{{ yoyMomData.currentValue || 0 | formatNumber }}</div>
                    <div class="stat-date">{{ yoyMomData.currentDate }}</div>
                  </div>
                </el-card>
              </el-col>
              <el-col :span="6">
                <el-card shadow="hover">
                  <div class="stat-card">
                    <div class="stat-label">环比</div>
                    <div class="stat-value" :class="getTrendClass(yoyMomData.momTrend)">
                      {{ formatPercent(yoyMomData.momPercent) }}
                    </div>
                    <div class="stat-date">较{{ yoyMomData.compareDate }}</div>
                  </div>
                </el-card>
              </el-col>
              <el-col :span="6">
                <el-card shadow="hover">
                  <div class="stat-card">
                    <div class="stat-label">同比</div>
                    <div class="stat-value" :class="getTrendClass(yoyMomData.yoyTrend)">
                      {{ formatPercent(yoyMomData.yoyPercent) }}
                    </div>
                    <div class="stat-date">较{{ yoyMomData.lastYearDate }}</div>
                  </div>
                </el-card>
              </el-col>
              <el-col :span="6">
                <el-card shadow="hover">
                  <div class="stat-card">
                    <div class="stat-label">失败量</div>
                    <div class="stat-value failure">{{ yoyMomData.currentFailure || 0 | formatNumber }}</div>
                    <div class="stat-date">当前周期</div>
                  </div>
                </el-card>
              </el-col>
            </el-row>
          </div>

          <!-- 数据表格 -->
          <div class="table-area">
            <el-table v-sticky-header v-mirror-scroll :data="tableData" border v-loading="tableLoading" stripe size="small" height="500" @header-contextmenu="handleHeaderContextMenu">
              <el-table-column v-if="selectedColumns.includes('dataDate')" prop="dataDate" label="日期" width="100" fixed />
              <el-table-column v-if="selectedColumns.includes('regionCode')" prop="regionCode" label="区域" width="80" />
              <el-table-column v-if="selectedColumns.includes('functionCode')" prop="functionCode" label="功能码" width="100" />
              <el-table-column v-if="selectedColumns.includes('unifiedCapabilityCode')" prop="unifiedCapabilityCode" label="能力编码" width="130" />
              <el-table-column v-if="selectedColumns.includes('unifiedServiceCode')" prop="unifiedServiceCode" label="服务编码" width="130" />
              <el-table-column v-if="selectedColumns.includes('unifiedSceneCode')" prop="unifiedSceneCode" label="场景编码" width="100" />
              <el-table-column v-if="selectedColumns.includes('abilityUserName')" prop="abilityUserName" label="使用方" min-width="150" class-name="wrap-cell" />
              <el-table-column v-if="selectedColumns.includes('abilityUserCode')" prop="abilityUserCode" label="使用方编码" width="100" />
              <el-table-column v-if="selectedColumns.includes('abilityUserAppName')" prop="abilityUserAppName" label="应用名称" min-width="150" class-name="wrap-cell" />
              <el-table-column v-if="selectedColumns.includes('abilityUserAppCode')" prop="abilityUserAppCode" label="应用编码" width="100" />
              <el-table-column v-if="selectedColumns.includes('abilityUserOrgName')" prop="abilityUserOrgName" label="服务机构" min-width="150" class-name="wrap-cell" />
              <el-table-column v-if="selectedColumns.includes('abilityUserOrgCode')" prop="abilityUserOrgCode" label="服务机构编码" width="100" />
              <el-table-column v-if="selectedColumns.includes('portalOrderNo')" prop="portalOrderNo" label="订单号" width="140" class-name="wrap-cell" />
              <el-table-column v-if="selectedColumns.includes('portalSubOrderNo')" prop="portalSubOrderNo" label="子订单号" width="140" class-name="wrap-cell" />
              <el-table-column v-if="selectedColumns.includes('invokeVolume')" label="调用量(JSON)" width="200">
                <template #default="{ row }">
                  <span class="json-text">{{ row.invokeVolume }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="selectedColumns.includes('invokeFailure')" label="失败量(JSON)" width="200">
                <template #default="{ row }">
                  <span class="failure-text">{{ row.invokeFailure }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="selectedColumns.includes('fileName')" prop="fileName" label="源文件" min-width="200" class-name="wrap-cell" />
              <el-table-column v-if="selectedColumns.includes('lineNumber')" prop="lineNumber" label="行号" width="80" />
              <el-table-column v-if="selectedColumns.includes('insertTime')" prop="insertTime" label="入库时间" width="160">
                <template #default="{ row }">
                  <span class="time-text">{{ formatTime(row.insertTime) }}</span>
                </template>
              </el-table-column>
            </el-table>

            <el-pagination
              v-model:current-page="pagination.page"
              v-model:page-size="pagination.size"
              :total="pagination.total"
              :page-sizes="[20, 50, 100, 200]"
              layout="total, sizes, prev, pager, next, jumper"
              style="margin-top: 10px; justify-content: flex-end;"
              @size-change="loadData"
              @current-change="loadData"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="统计" name="statistics">
          <el-row :gutter="12">
            <!-- 使用方统计卡片 -->
            <el-col :span="8">
              <el-card shadow="hover">
                <template #header>
                  <span>使用方统计 Top10</span>
                </template>
                <div class="stat-list">
                  <div v-for="(item, idx) in userStats" :key="idx" class="stat-item" @click="drilldown('user', item.userCode)">
                    <span class="rank">{{ idx + 1 }}</span>
                    <span class="name" :title="item.userName">{{ item.userName || item.userCode }}</span>
                    <span class="value">{{ item.totalInvoke | formatNumber }}</span>
                    <span class="rate">{{ item.rate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <!-- 服务编码统计卡片 -->
            <el-col :span="8">
              <el-card shadow="hover">
                <template #header>
                  <span>服务编码统计 Top10</span>
                </template>
                <div class="stat-list">
                  <div v-for="(item, idx) in serviceStats" :key="idx" class="stat-item" @click="drilldown('service', item.serviceCode)">
                    <span class="rank">{{ idx + 1 }}</span>
                    <span class="name">{{ item.serviceCode }}</span>
                    <span class="value">{{ item.totalInvoke | formatNumber }}</span>
                    <span class="rate">{{ item.rate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <!-- 日期统计卡片 -->
            <el-col :span="8">
              <el-card shadow="hover">
                <template #header>
                  <span>日期统计</span>
                </template>
                <div class="stat-list">
                  <div v-for="(item, idx) in dateStats" :key="idx" class="stat-item" @click="drilldown('date', item.dataDate)">
                    <span class="rank">{{ idx + 1 }}</span>
                    <span class="name">{{ item.dataDate }}</span>
                    <span class="value">{{ item.totalInvoke | formatNumber }}</span>
                    <span class="rate">{{ item.rate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <!-- 能力编码统计卡片 -->
            <el-col :span="8">
              <el-card shadow="hover">
                <template #header>
                  <span>能力编码统计</span>
                </template>
                <div class="stat-list">
                  <div v-for="(item, idx) in capabilityStats" :key="idx" class="stat-item" @click="drilldown('capability', item.capabilityCode)">
                    <span class="rank">{{ idx + 1 }}</span>
                    <span class="name">{{ item.capabilityCode }}</span>
                    <span class="value">{{ item.totalInvoke | formatNumber }}</span>
                    <span class="rate">{{ item.rate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <!-- 区域统计卡片 -->
            <el-col :span="8">
              <el-card shadow="hover">
                <template #header>
                  <span>区域统计</span>
                </template>
                <div class="stat-list">
                  <div v-for="(item, idx) in regionStats" :key="idx" class="stat-item" @click="drilldown('region', item.regionCode)">
                    <span class="rank">{{ idx + 1 }}</span>
                    <span class="name">{{ item.regionCode }}</span>
                    <span class="value">{{ item.totalInvoke | formatNumber }}</span>
                    <span class="rate">{{ item.rate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>

            <!-- 服务机构统计卡片 -->
            <el-col :span="8">
              <el-card shadow="hover">
                <template #header>
                  <span>服务机构统计</span>
                </template>
                <div class="stat-list">
                  <div v-for="(item, idx) in orgStats" :key="idx" class="stat-item" @click="drilldown('org', item.orgCode)">
                    <span class="rank">{{ idx + 1 }}</span>
                    <span class="name" :title="item.orgName">{{ item.orgName || item.orgCode }}</span>
                    <span class="value">{{ item.totalInvoke | formatNumber }}</span>
                    <span class="rate">{{ item.rate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </el-tab-pane>
      </el-tabs>
    </el-card>
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
import { ref, onMounted, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { abilityMetricApi } from '../../api'

const activeTab = ref('data')

const filters = ref({
  dateRange: [],
  regionCode: '',
  functionCode: '',
  capabilityCode: '',
  serviceOrgCode: '',
  sceneCode: '',
  userCode: ''
})

const pagination = ref({ page: 1, size: 50, total: 0 })
const tableData = ref([])
const tableLoading = ref(false)
const yoyMomData = ref({})
const capabilityOptions = ref([])
const userOptions = ref([])
const regionOptions = ref([])
const functionOptions = ref([])
const serviceOrgOptions = ref([])
const sceneOptions = ref([])
const summaryData = ref({})

// 统计卡片数据
const userStats = ref([])
const serviceStats = ref([])
const dateStats = ref([])
const capabilityStats = ref([])
const regionStats = ref([])
const orgStats = ref([])

// 列设置相关
const allColumns = [
  { prop: 'dataDate', label: '日期' },
  { prop: 'regionCode', label: '区域' },
  { prop: 'functionCode', label: '功能码' },
  { prop: 'unifiedCapabilityCode', label: '能力编码' },
  { prop: 'unifiedServiceCode', label: '服务编码' },
  { prop: 'unifiedSceneCode', label: '场景编码' },
  { prop: 'abilityUserName', label: '使用方' },
  { prop: 'abilityUserCode', label: '使用方编码' },
  { prop: 'abilityUserAppName', label: '应用名称' },
  { prop: 'abilityUserAppCode', label: '应用编码' },
  { prop: 'abilityUserOrgName', label: '服务机构' },
  { prop: 'abilityUserOrgCode', label: '服务机构编码' },
  { prop: 'portalOrderNo', label: '订单号' },
  { prop: 'portalSubOrderNo', label: '子订单号' },
  { prop: 'invokeVolume', label: '调用量(JSON)' },
  { prop: 'invokeFailure', label: '失败量(JSON)' },
  { prop: 'fileName', label: '源文件' },
  { prop: 'lineNumber', label: '行号' },
  { prop: 'insertTime', label: '入库时间' }
]

const selectedColumns = ref([])
const allColumnsSelected = computed({
  get: () => selectedColumns.value.length === allColumns.length,
  set: (val) => { if (val) selectedColumns.value = allColumns.map(c => c.prop) }
})

// 加载数据
const loadData = async () => {
  tableLoading.value = true
  try {
    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      dataDateStart: filters.value.dateRange?.[0] || '',
      dataDateEnd: filters.value.dateRange?.[1] || '',
      regionCode: filters.value.regionCode || undefined,
      functionCode: filters.value.functionCode || undefined,
      capabilityCode: filters.value.capabilityCode || undefined,
      serviceOrgCode: filters.value.serviceOrgCode || undefined,
      sceneCode: filters.value.sceneCode || undefined,
      userCode: filters.value.userCode || undefined
    }
    const res = await abilityMetricApi.list(params)
    tableData.value = res.data?.content || []
    pagination.value.total = res.data?.totalElements || 0

    await loadStatistics()
    await loadDimensionStats()
    if (filters.value.capabilityCode) {
      await loadYoYMoM()
    } else {
      yoyMomData.value = {}
    }
  } catch (error) {
    console.error('加载数据失败:', error)
    ElMessage.error('加载数据失败')
  } finally {
    tableLoading.value = false
  }
}

// 加载统计数据
const loadStatistics = async () => {
  try {
    const params = {
      dimension: 'capability',
      startDate: filters.value.dateRange?.[0] || '',
      endDate: filters.value.dateRange?.[1] || '',
      capabilityCode: filters.value.capabilityCode || undefined,
      userCode: filters.value.userCode || undefined,
      regionCode: filters.value.regionCode || undefined,
      functionCode: filters.value.functionCode || undefined,
      serviceOrgCode: filters.value.serviceOrgCode || undefined,
      sceneCode: filters.value.sceneCode || undefined
    }
    const res = await abilityMetricApi.statistics(params)
    const stats = res.data || []

    let totalInvoke = 0
    let totalFailure = 0

    stats.forEach(s => {
      totalInvoke += (s.totalInvoke || 0)
      totalFailure += (s.totalFailure || 0)
    })

    summaryData.value = {
      totalCount: pagination.value.total,
      totalInvoke,
      totalFailure
    }
  } catch (error) {
    console.error('加载统计失败:', error)
  }
}

// 加载各维度Top统计
const loadDimensionStats = async () => {
  const commonParams = {
    startDate: filters.value.dateRange?.[0] || '',
    endDate: filters.value.dateRange?.[1] || '',
    capabilityCode: filters.value.capabilityCode || undefined,
    userCode: filters.value.userCode || undefined,
    regionCode: filters.value.regionCode || undefined,
    functionCode: filters.value.functionCode || undefined,
    serviceOrgCode: filters.value.serviceOrgCode || undefined,
    sceneCode: filters.value.sceneCode || undefined
  }

  // 使用方统计
  try {
    const res = await abilityMetricApi.statistics({ ...commonParams, dimension: 'user' })
    const stats = res.data || []
    const total = stats.reduce((sum, s) => sum + (s.totalInvoke || 0), 0)
    userStats.value = stats.slice(0, 10).map(s => ({
      ...s,
      rate: total > 0 ? ((s.totalInvoke / total) * 100).toFixed(1) : '0'
    }))
  } catch (e) { console.error(e) }

  // 服务编码统计
  try {
    const res = await abilityMetricApi.statistics({ ...commonParams, dimension: 'service' })
    const stats = res.data || []
    const total = stats.reduce((sum, s) => sum + (s.totalInvoke || 0), 0)
    serviceStats.value = stats.slice(0, 10).map(s => ({
      ...s,
      rate: total > 0 ? ((s.totalInvoke / total) * 100).toFixed(1) : '0'
    }))
  } catch (e) { console.error(e) }

  // 日期统计
  try {
    const res = await abilityMetricApi.statistics({ ...commonParams, dimension: 'date' })
    const stats = res.data || []
    const total = stats.reduce((sum, s) => sum + (s.totalInvoke || 0), 0)
    dateStats.value = stats.slice(0, 10).map(s => ({
      ...s,
      rate: total > 0 ? ((s.totalInvoke / total) * 100).toFixed(1) : '0'
    }))
  } catch (e) { console.error(e) }

  // 能力编码统计
  try {
    const res = await abilityMetricApi.statistics({ ...commonParams, dimension: 'capability' })
    const stats = res.data || []
    const total = stats.reduce((sum, s) => sum + (s.totalInvoke || 0), 0)
    capabilityStats.value = stats.slice(0, 10).map(s => ({
      ...s,
      rate: total > 0 ? ((s.totalInvoke / total) * 100).toFixed(1) : '0'
    }))
  } catch (e) { console.error(e) }

  // 区域统计
  try {
    const res = await abilityMetricApi.statistics({ ...commonParams, dimension: 'region' })
    const stats = res.data || []
    const total = stats.reduce((sum, s) => sum + (s.totalInvoke || 0), 0)
    regionStats.value = stats.slice(0, 10).map(s => ({
      ...s,
      rate: total > 0 ? ((s.totalInvoke / total) * 100).toFixed(1) : '0'
    }))
  } catch (e) { console.error(e) }

  // 服务机构统计
  try {
    const res = await abilityMetricApi.statistics({ ...commonParams, dimension: 'org' })
    const stats = res.data || []
    const total = stats.reduce((sum, s) => sum + (s.totalInvoke || 0), 0)
    orgStats.value = stats.slice(0, 10).map(s => ({
      ...s,
      rate: total > 0 ? ((s.totalInvoke / total) * 100).toFixed(1) : '0'
    }))
  } catch (e) { console.error(e) }
}

// 加载同比环比
const loadYoYMoM = async () => {
  if (!filters.value.capabilityCode) {
    yoyMomData.value = {}
    return
  }
  try {
    const today = new Date()
    const currentDate = today.toISOString().slice(0, 10).replace(/-/g, '')
    const res = await abilityMetricApi.getYoYMoM({
      capabilityCode: filters.value.capabilityCode,
      userCode: filters.value.userCode || undefined,
      currentDate,
      period: 'day'
    })
    yoyMomData.value = res.data || {}
  } catch (error) {
    console.error('加载同比环比失败:', error)
  }
}

// 列设置操作
const handleColumnCommand = (command) => {
  // 触发checkbox变化
}

const handleSelectAll = (checked) => {
  if (checked) {
    selectedColumns.value = allColumns.map(c => c.prop)
  } else {
    selectedColumns.value = []
  }
  saveColumnPrefs()
}

const handleInverseSelect = () => {
  const allProps = allColumns.map(c => c.prop)
  selectedColumns.value = allProps.filter(p => !selectedColumns.value.includes(p))
  saveColumnPrefs()
}

const handleResetSelect = () => {
  selectedColumns.value = allColumns.map(c => c.prop)
  saveColumnPrefs()
}

const handleColumnsChange = () => {
  saveColumnPrefs()
}

const saveColumnPrefs = () => {
  localStorage.setItem('ability_metric_column_prefs', JSON.stringify(selectedColumns.value))
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
  const idx = selectedColumns.value.indexOf(prop)
  if (idx > -1) {
    selectedColumns.value.splice(idx, 1)
    saveColumnPrefs()
  }
  showHeaderContextMenu.value = false
}

const closeContextMenu = () => {
  showHeaderContextMenu.value = false
}

const loadColumnPrefs = () => {
  const saved = localStorage.getItem('ability_metric_column_prefs')
  if (saved) {
    try {
      selectedColumns.value = JSON.parse(saved)
    } catch {
      selectedColumns.value = allColumns.map(c => c.prop)
    }
  } else {
    selectedColumns.value = allColumns.map(c => c.prop)
  }
}

// 下钻
const drilldown = (dimension, value) => {
  activeTab.value = 'data'
  switch (dimension) {
    case 'user':
      filters.value.userCode = value
      break
    case 'service':
      filters.value.serviceOrgCode = value
      break
    case 'date':
      filters.value.dateRange = [value, value]
      break
    case 'capability':
      filters.value.capabilityCode = value
      break
    case 'region':
      filters.value.regionCode = value
      break
    case 'org':
      filters.value.serviceOrgCode = value
      break
  }
  loadData()
}

// 导出
const handleExport = async () => {
  try {
    const res = await abilityMetricApi.export({
      dataDateStart: filters.value.dateRange?.[0] || '',
      dataDateEnd: filters.value.dateRange?.[1] || '',
      capabilityCode: filters.value.capabilityCode || undefined,
      userCode: filters.value.userCode || undefined
    })

    const data = res.data?.data || []
    if (data.length === 0) {
      ElMessage.warning('没有数据可导出')
      return
    }

    let csv = '﻿'
    csv += '日期,区域,功能码,能力编码,服务编码,场景编码,使用方,使用方编码,应用名称,应用编码,服务机构,服务机构编码,订单号,子订单号,调用量(JSON),失败量(JSON),源文件,行号,入库时间\n'
    data.forEach(item => {
      csv += `${item.dataDate || ''},`
      csv += `${item.regionCode || ''},`
      csv += `${item.functionCode || ''},`
      csv += `${item.unifiedCapabilityCode || ''},`
      csv += `${item.unifiedServiceCode || ''},`
      csv += `${item.unifiedSceneCode || ''},`
      csv += `${item.abilityUserName || ''},`
      csv += `${item.abilityUserCode || ''},`
      csv += `${item.abilityUserAppName || ''},`
      csv += `${item.abilityUserAppCode || ''},`
      csv += `${item.abilityUserOrgName || ''},`
      csv += `${item.abilityUserOrgCode || ''},`
      csv += `${item.portalOrderNo || ''},`
      csv += `${item.portalSubOrderNo || ''},`
      csv += `"${(item.invokeVolume || '').replace(/"/g, '""')}",`
      csv += `"${(item.invokeFailure || '').replace(/"/g, '""')}",`
      csv += `${item.fileName || ''},`
      csv += `${item.lineNumber || ''},`
      csv += `${formatTime(item.insertTime)}\n`
    })

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `ability_metric_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.csv`
    link.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功，共' + data.length + '条')
  } catch (error) {
    console.error('导出失败:', error)
    ElMessage.error('导出失败')
  }
}

// 触发扫描
const handleScan = async () => {
  try {
    await abilityMetricApi.triggerScan()
    ElMessage.success('扫描任务已触发')
    setTimeout(loadData, 1000)
  } catch (error) {
    ElMessage.error('触发扫描失败')
  }
}

// 格式化时间
const formatTime = (val) => {
  if (!val) return ''
  return val.replace('T', ' ').slice(0, 19)
}

// 格式化百分比
const formatPercent = (val) => {
  if (val == null || val === 'N/A') return 'N/A'
  return (val > 0 ? '+' : '') + val + '%'
}

// 获取趋势样式
const getTrendClass = (trend) => {
  if (trend === 'up') return 'trend-up'
  if (trend === 'down') return 'trend-down'
  return ''
}

// 格式化数字
const formatNumber = (val) => {
  if (val == null) return '0'
  return val.toLocaleString()
}

// 重置筛选
const resetFilters = () => {
  filters.value = {
    dateRange: [],
    regionCode: '',
    functionCode: '',
    capabilityCode: '',
    serviceOrgCode: '',
    sceneCode: '',
    userCode: ''
  }
  pagination.value.page = 1
  loadData()
}

// 加载能力编码选项
const loadCapabilityOptions = async () => {
  try {
    const res = await abilityMetricApi.getCapabilities()
    capabilityOptions.value = res.data || []
  } catch (error) {
    console.error('加载能力编码失败:', error)
  }
}

// 加载使用方选项
const loadUserOptions = async () => {
  try {
    const res = await abilityMetricApi.getUsers()
    userOptions.value = res.data || []
  } catch (error) {
    console.error('加载使用方失败:', error)
  }
}

// 加载其他筛选选项
const loadFilterOptions = async () => {
  try {
    const res = await abilityMetricApi.statistics({ dimension: 'capability' })
    const data = res.data || []
    const regions = new Set()
    const functions = new Set()
    const serviceOrgs = new Set()
    const scenes = new Set()

    // 从已导入数据中获取
    const listRes = await abilityMetricApi.list({ page: 0, size: 1000 })
    const items = listRes.data?.content || []
    items.forEach(item => {
      if (item.regionCode) regions.add(item.regionCode)
      if (item.functionCode) functions.add(item.functionCode)
      if (item.serviceOrgCode) serviceOrgs.add(item.serviceOrgCode)
      if (item.unifiedSceneCode) scenes.add(item.unifiedSceneCode)
    })

    regionOptions.value = [...regions].sort()
    functionOptions.value = [...functions].sort()
    serviceOrgOptions.value = [...serviceOrgs].sort()
    sceneOptions.value = [...scenes].sort()
  } catch (error) {
    console.error('加载筛选选项失败:', error)
  }
}

onMounted(() => {
  loadColumnPrefs()
  loadCapabilityOptions()
  loadUserOptions()
  loadFilterOptions()
  loadData()
})

watch(() => filters.value.capabilityCode, () => {
  loadStatistics()
  if (filters.value.capabilityCode) {
    loadYoYMoM()
  } else {
    yoyMomData.value = {}
  }
})

watch(() => filters.value.userCode, () => {
  loadStatistics()
})

watch(() => filters.value.dateRange, () => {
  loadStatistics()
})

watch(activeTab, (newTab) => {
  if (newTab === 'statistics') {
    loadDimensionStats()
  }
})
</script>

<style scoped>
.ability-operation-container { padding: 8px; }

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.main-tabs {
  margin-top: 0;
}

.filter-area {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  align-items: center;
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
}

.toolbar-right {
  display: flex;
  gap: 12px;
}

.summary-tag {
  font-size: 12px;
  color: #606266;
  background: #f0f2f5;
  padding: 4px 8px;
  border-radius: 4px;
}

.column-settings {
  padding: 8px 12px;
  min-width: 200px;
}

.column-settings-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eee;
  margin-bottom: 8px;
}

.analysis-cards {
  margin-bottom: 12px;
}

.stat-card {
  text-align: center;
  padding: 10px 0;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.stat-date {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 4px;
}

.trend-up { color: #67c23a; }
.trend-down { color: #f56c6c; }
.failure { color: #f56c6c; }

.table-area { margin-top: 10px; }

.json-text {
  font-size: 11px;
  word-break: break-all;
  max-width: 180px;
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.failure-text { color: #f56c6c; font-size: 11px; }
.time-text { color: #909399; font-size: 12px; }

.stat-list {
  max-height: 300px;
  overflow-y: auto;
}

.stat-item {
  display: flex;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}

.stat-item:hover {
  background: #f5f7fa;
}

.stat-item .rank {
  width: 24px;
  text-align: center;
  color: #909399;
  font-size: 12px;
}

.stat-item .name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.stat-item .value {
  width: 80px;
  text-align: right;
  font-weight: bold;
  font-size: 13px;
}

.stat-item .rate {
  width: 60px;
  text-align: right;
  color: #909399;
  font-size: 12px;
}

:deep(.el-table .el-table__header th) {
  background-color: #f5f7fa;
}

:deep(.el-dropdown-menu__item) {
  padding: 0 8px;
}

/* 表头右键菜单 */
.header-context-menu {
  position: fixed;
  z-index: 9999;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 4px 0;
  min-width: 120px;
}

.context-menu-item {
  padding: 8px 16px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
  white-space: nowrap;
}

.context-menu-item:hover {
  background-color: #f5f7fa;
  color: #409eff;
}

.context-menu-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 9998;
}
</style>