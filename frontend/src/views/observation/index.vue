<template>
  <div ref="observationRoot" class="observation-container workspace-theme">
    <header class="observation-page-header">
      <div>
        <div class="observation-page-heading"><h1>运营观测</h1><span class="observation-platform-label">指标体系平台</span><WorkspaceStatusBadge :tone="healthDataState === 'ready' ? 'success' : healthDataState === 'error' ? 'danger' : 'muted'" :pulse="healthDataState === 'loading'">{{ { loading:'观测数据加载中', ready:'观测数据已更新', empty:'暂无健康度采样', error:'观测数据加载失败' }[healthDataState] }}</WorkspaceStatusBadge></div>
        <p class="observation-update-time"><el-icon><Clock /></el-icon>数据更新时间：<time>{{ latestSystemRecordTime ? formatDateTime(latestSystemRecordTime) : '暂无采样时间' }}</time></p>
      </div>
      <div class="observation-time-control" aria-label="观测时间范围">
        <el-radio-group v-model="selectedTimePreset" size="small" @change="handleTimeRangeSwitch">
          <el-radio-button value="1h">1小时</el-radio-button>
          <el-radio-button value="6h">6小时</el-radio-button>
          <el-radio-button value="1d">1天</el-radio-button>
          <el-radio-button value="7d">7天</el-radio-button>
          <el-radio-button value="30d">30天</el-radio-button>
        </el-radio-group><!-- 受控 visible：Element Plus 2.x 的 popover 在点击面板外部时自动关闭，而日期面板默认 teleport 到 body，
             点日期就会被当成“外部点击”。改成受控后只由触发按钮 / 取消 / 确定控制开关。 -->
        <!-- 宽度按日期区间的日历面板（约 646px）预留，避免日期面板横向溢出到屏幕外 -->
        <el-popover :visible="customTimeVisible" trigger="click" placement="bottom-end" :width="680" popper-class="workspace-theme observation-custom-time">
          <template #reference><el-button class="observation-custom-time-trigger" :type="customTimeRange ? 'primary' : 'default'" round @click="toggleCustomTimePicker"><el-icon><Calendar /></el-icon>自定义区间</el-button></template>
          <div class="custom-time-panel-body" :class="{ 'is-calendar-open': customDatePanelOpen }">
            <div class="custom-time-heading">选择自定义时间区间</div>
            <el-date-picker v-model="customTimeDraft" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="至" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY/MM/DD HH:mm" style="width:100%" @visible-change="handleCustomDateVisibleChange" />
            <p class="custom-time-hint">请选择开始时间和结束时间</p>
            <div class="custom-time-actions"><el-button @click="closeCustomTimePicker">取消</el-button><el-button type="primary" @click="applyCustomTimeRange">确定</el-button></div>
          </div>
        </el-popover><span v-if="customTimeRange" class="custom-time-caption">{{ customTimeRange[0] }} ～ {{ customTimeRange[1] }}</span><el-button link type="primary" :loading="healthDataState === 'loading'" @click="refreshObservation" aria-label="刷新观测数据"><el-icon><Refresh /></el-icon></el-button>
      </div>
    </header>
    <!-- 下钻面包屑 -->
    <DrilldownBreadcrumb
      :breadcrumbs="drilldownBreadcrumbs"
      :focus-range="focusRangeText"
      @navigate="handleBreadcrumbNavigate"
      @reset="handleDrilldownReset"
    />

    <div class="observation-workspace">
    <!-- 系统宏观健康度 -->
    <ObservationHealthSummary :system-health-score="systemHealthScore" :system-interval-score="systemIntervalStats.score" :system-interval-samples="systemIntervalStats.samples" :categories="categoryRowsForDisplay" :category-weights="categoryWeightsForDisplay" :sampling-state="healthDataState" :sample-delta="healthSampleDelta" :health-summary="healthSummary" :summary-loading="healthScoreLoading" :window-label="focusRangeText" :system-options="systemOptions" :selected-system-id="selectedSystemId" :get-score-class="getScoreClass" :get-score-desc="getScoreDesc" @locate="scrollToCategory" @switch-system="switchSystem" />

    <!-- 关注问题 -->
    <FollowedIssuePanel :issues="followedIssues" :total="followedIssueTotal" :calc-elapsed-time="calcElapsedTime" @more="$router.push('/issue')" />

    <!-- 指标健康度总览（默认视图） -->
    <div v-if="!selectedCategory" class="subcategory-overview-section">
      <div class="section-title">
        <span>指标健康度总览</span>
        <span v-if="summaryLoading" class="section-hint">加载中…</span>
        <span v-else class="section-hint" :title="'分数为所选时间区间内趋势采样点的平均值；尚未加载完的行暂显示实时最新分'">区间均分</span>
      </div>
      <!-- 汇总未返回前只给骨架：不能再先渲染 8 个硬编码大类再被白名单过滤掉 -->
      <div v-if="summaryLoading" class="overview-skeleton" aria-hidden="true">
        <div v-for="row in 3" :key="row" class="overview-skeleton-row">
          <span class="skeleton-card skeleton-parent"></span>
          <span class="skeleton-card"></span>
          <span class="skeleton-card"></span>
          <span class="skeleton-card"></span>
        </div>
      </div>
      <template v-else>
        <ObservationCategoryOverview v-if="overviewParentList.length" :categories="overviewParentList" :get-score-class="getScoreClass" @select="handleParentInfoClick" />
        <div v-if="!overviewParentList.length" class="no-data-tip">暂无需要展示的指标大类</div>
        <div v-else class="subcategory-overview-container">
        <div
          v-for="parent in overviewParentList"
          :key="parent.code"
          :id="'parent-row-' + parent.code"
          class="overview-parent-row"
        >
          <div
            class="overview-parent-info"
            :class="getScoreClass(parent.score)"
            @click="handleParentInfoClick(parent)"
          >
            <span class="overview-parent-name">{{ parent.name }}</span>
            <span class="overview-parent-score" :class="getScoreClass(parent.score)" :title="intervalScoreTitle(parent.intervalSamples)">{{ parent.score ?? '-' }}</span>
            <span class="overview-parent-stat"><span>指{{ parent.metricCount || 0 }}</span><span class="stat-sep">|</span><span :class="{ abnormal: parent.alarmCount > 0 }">异{{ parent.alarmCount || 0 }}</span></span>
          </div>
          <div class="overview-subcategories">
            <div
              v-for="sub in parent.subcategories"
              :key="sub.code"
              class="overview-subcategory-card"
              :class="getScoreClass(sub.score)"
              @click="handleSubcategoryOverviewClick(sub)"
            >
              <div class="overview-subcategory-header">
                <span class="overview-subcategory-name">{{ sub.name }} <span class="overview-subcategory-meta">指标{{ sub.metricCount || 0 }}|异常{{ sub.alarmCount || 0 }}<template v-if="sub.frequency">|{{ getFrequencyLabel(sub.frequency) }}</template></span></span>
                <span class="overview-subcategory-score" :class="getScoreClass(sub.score)" :title="intervalScoreTitle(sub.intervalSamples)">{{ sub.score ?? '-' }}</span>
              </div>
              <!-- data-overview-sub-key 供 IntersectionObserver 按行懒加载趋势，进入视口才请求 -->
              <div :id="'overview-sub-health-chart-' + sub.code" :data-overview-sub-key="sub.code" class="overview-subcategory-chart"></div>
            </div>
          </div>
        </div>
        </div>
      </template>
    </div>

    <!-- 指标大类健康度详情 -->
    <div v-if="selectedCategory" class="category-detail-section">
      <!-- 详情头部 -->
      <div class="detail-header">
        <el-button link @click="handleBack">
          <el-icon><ArrowLeft /></el-icon>返回
        </el-button>
        <span class="detail-title">{{ selectedCategory.name }}</span>
        <!-- 详情页大类分数与同类趋势一致：展示该大类在当前区间的趋势均分 -->
        <span v-if="detailIntervalText" class="detail-score-note">{{ detailIntervalText }}</span>
        <el-radio-group v-model="selectedTimePreset" size="small" @change="handleTimeRangeSwitch">
          <el-radio-button value="1h">1小时</el-radio-button>
          <el-radio-button value="6h">6小时</el-radio-button>
          <el-radio-button value="1d">1天</el-radio-button>
          <el-radio-button value="7d">7天</el-radio-button>
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
              <span class="subcategory-score" :class="getScoreClass(sub.score)" :title="intervalScoreTitle(sub.intervalSamples)">
                {{ sub.score ?? '-' }}
              </span>
            </div>
            <div :id="'sub-health-chart-' + sub.code" class="subcategory-chart"></div>
          </div>
        </div>
      </div>

      <!-- 3. 指标图表区域（按小类分组展示） -->
      <div class="metrics-section" v-loading="tableLoading">
        <!-- 提示信息 -->
        <div class="section-subtitle">
          <span v-if="selectedSubCategory">{{ selectedSubCategory }} - 指标详情</span>
          <span v-else>所有指标详情</span>
          <span class="hint-text">（点击指标当前值可查看实时指标，点击明细可查看原始数据）</span>
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
                    <div v-for="metric in group.metrics" :key="metric.metricId" class="metric-chart-item" :data-metric-id="metric.metricId">
                      <el-table v-sticky-header v-mirror-scroll :data="[metric]" border size="small" class="metric-info-table">
                        <el-table-column prop="metricName" label="指标名称" min-width="140" class-name="wrap-cell" />
                        <el-table-column prop="currentValue" label="当前值" width="115" align="center">
                          <template #default="{ row }">
                            <span class="clickable-value" :title="`${row.isStale ? '最近值在所选范围之外。' : ''}${row.sourceNotice || ''} 数据时间：${row.dataTime || '缺失'}`" @click="openValueDialog(row)">
                              {{ row.currentValue ?? '-' }}{{ row.currentValue != null && row.unit ? ` ${row.unit}` : '' }}
                            </span>
                          </template>
                        </el-table-column>
                        <el-table-column prop="healthScore" label="健康度" width="130" align="center">
                          <template #default="{ row }">
                            <el-tag :type="getScoreTagType(row.healthScore)" size="small">
                              {{ row.healthScore ?? '-' }}
                            </el-tag>
                            <el-tag v-if="row.healthExempt" size="small" type="info" style="margin-left:4px">不计分</el-tag>
                            <el-tag v-else-if="row.healthMode === 'manual'" size="small" type="success" style="margin-left:4px">人工</el-tag>
                            <el-tag v-else-if="row.healthMode === 'learning'" size="small" type="warning" style="margin-left:4px">学习中</el-tag>
                            <el-tag v-else-if="row.healthMode" size="small" style="margin-left:4px">动态</el-tag>
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
                      <div class="metric-charts-row">
                        <div class="mini-chart-box">
                          <div class="chart-title">{{ metric.metricName }} - 指标值</div>
                          <div :id="'metric-value-chart-' + metric.metricId" class="chart-container"></div>
                        </div>
                        <div class="mini-chart-box">
                          <div class="chart-title">{{ metric.metricName }} - 健康度</div>
                          <div :id="'metric-health-chart-' + metric.metricId" class="chart-container"></div>
                        </div>
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
                <div v-for="metric in getGroupMetrics(sub.name)" :key="metric.metricId" class="metric-chart-item" :data-metric-id="metric.metricId">
                  <el-table v-sticky-header v-mirror-scroll :data="[metric]" border size="small" class="metric-info-table">
                    <el-table-column prop="metricName" label="指标名称" min-width="140" class-name="wrap-cell" />
                    <el-table-column prop="currentValue" label="当前值" width="115" align="center">
                      <template #default="{ row }">
                        <span class="clickable-value" :title="`${row.isStale ? '最近值在所选范围之外。' : ''}${row.sourceNotice || ''} 数据时间：${row.dataTime || '缺失'}`" @click="openValueDialog(row)">
                          {{ row.currentValue ?? '-' }}{{ row.currentValue != null && row.unit ? ` ${row.unit}` : '' }}
                        </span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="healthScore" label="健康度" width="130" align="center">
                      <template #default="{ row }">
                        <el-tag :type="getScoreTagType(row.healthScore)" size="small">
                          {{ row.healthScore ?? '-' }}
                        </el-tag>
                        <el-tag v-if="row.healthExempt" size="small" type="info" style="margin-left:4px">不计分</el-tag>
                        <el-tag v-else-if="row.healthMode === 'manual'" size="small" type="success" style="margin-left:4px">人工</el-tag>
                        <el-tag v-else-if="row.healthMode === 'learning'" size="small" type="warning" style="margin-left:4px">学习中</el-tag>
                        <el-tag v-else-if="row.healthMode" size="small" style="margin-left:4px">动态</el-tag>
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
                  <div class="metric-charts-row">
                    <div class="mini-chart-box">
                      <div class="chart-title">{{ metric.metricName }} - 指标值</div>
                      <div :id="'metric-value-chart-' + metric.metricId" class="chart-container"></div>
                    </div>
                    <div class="mini-chart-box">
                      <div class="chart-title">{{ metric.metricName }} - 健康度</div>
                      <div :id="'metric-health-chart-' + metric.metricId" class="chart-container"></div>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <ObservationDataFlow :data-sources="dataSources" :dashboard-stats="dashboardStats" :import-count="importCount" :metric-count="metricCount" :health-chart-count="healthChartCount" :alarm-processed-count="alarmProcessedCount" :alarm-process-rate="alarmProcessRate" :issue-resolved-count="issueResolvedCount" :issue-resolve-rate="issueResolveRate" :gongdan-completed-count="gongdanCompletedCount" :gongdan-complete-rate="gongdanCompleteRate" :get-dashboard-rate-class="getDashboardRateClass" @navigate="$router.push($event)" @source-select="handleSourceClick" />
    </div>
  </div>

  <!-- 实时指标弹窗 -->
  <el-dialog
    v-model="realtimeDialogVisible"
    :title="realtimeDialogTitle"
    :width="realtimeDialog.width.value"
    custom-class="obs-realtime"
    destroy-on-close
    @opened="realtimeCleanup = realtimeDialog.bindResize()"
  >
    <div v-loading="realtimeDialogLoading">
      <!-- 系统异常模式：显示指标上下文 metadata -->
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

      <!-- 正常模式：统计卡 -->
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

      <!-- 系统异常模式：动态列原始记录表 -->
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

      <!-- 正常模式：内嵌 5 列 -->
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
    :width="detailDialog.width.value"
    custom-class="obs-detail"
    destroy-on-close
    @opened="detailCleanup = detailDialog.bindResize()"
  >
    <div v-loading="detailDialogLoading">
      <div class="detail-filter-row" v-balanced-query="{ fill:false }">
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

  <!-- 5分钟明细下钻弹窗 -->
  <el-dialog
    v-model="drillDetailVisible"
    title="5分钟明细数据"
    :width="drillDialog.width.value"
    custom-class="obs-drill"
    destroy-on-close
    @opened="drillCleanup = drillDialog.bindResize()"
  >
    <div v-loading="drillDetailLoading">
      <div class="drill-detail-header">
        <span class="drill-detail-time">{{ drillDetailTimeRange }}</span>
      </div>
      <el-table
        :data="drillDetailData"
        border
        size="small"
        max-height="400"
      >
        <el-table-column prop="recordTime" label="时间" width="160" :formatter="(row) => row.recordTime ? row.recordTime.replace('T', ' ') : ''" />
        <el-table-column prop="healthScore" label="健康度" width="80" align="center">
          <template #default="{ row }">
            <span :class="getScoreClass(row.healthScore)">{{ row.healthScore ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusTagType(row.status)" size="small">{{ getStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="metricValue" label="指标值" width="100" align="right" />
        <el-table-column prop="normalCount" label="正常数" width="70" align="right" />
        <el-table-column prop="warningCount" label="预警数" width="70" align="right" />
        <el-table-column prop="alarmCount" label="告警数" width="70" align="right" />
      </el-table>
    </div>
  </el-dialog>
  <MetricObservationDialog v-model="observationDialogVisible" :metric="observationDialogMetric" :time-range="currentTimeRange" :start-time="activeWindow.startTime" :end-time="activeWindow.endTime" />
</template>

<script setup>
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { useChartContainerResize } from '@/utils/useChartContainerResize'
import ObservationCategoryOverview from './components/ObservationCategoryOverview.vue'
import ObservationDataFlow from './components/ObservationDataFlow.vue'
import FollowedIssuePanel from './components/FollowedIssuePanel.vue'
import ObservationHealthSummary from './components/ObservationHealthSummary.vue'
import MetricObservationDialog from '../../components/common/MetricObservationDialog.vue'
const observationDialogVisible = ref(false)
const observationDialogMetric = ref(null)
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { observationApi, realtimeApi, dataApi, weightApi, errorDrilldownApi, alarmApi, gongdanApi, issueApi, metricApi, originalDataImportApi } from '@/api'
import { formatDateTime } from '@/utils'
import { ensureHealthThresholds, scoreLevel, scoreTone } from '@/utils/healthThresholds'
import * as echarts from 'echarts'
import DrilldownBreadcrumb from './components/DrilldownBreadcrumb.vue'
import { useDialogSize } from '@/utils/useDialogSize'

// Canvas 图表读取当前语义主题色，不把配色写入指标规则。
const observationChartColor = key => getComputedStyle(observationRoot.value || document.documentElement).getPropertyValue(`--ui-${key}`).trim()

// 原型摘要所需的只读展示数据；不修改健康度、权重或告警规则。
const healthDataState = ref('loading')     // 趋势采样的加载状态：loading / ready / empty / error
const healthScoreLoading = ref(false)      // 系统健康度汇总请求是否进行中：仪表盘据此显示骨架或刷新遮罩
const latestSystemRecordTime = ref('')
const healthSampleDelta = ref(null)
const categoryWeightsForDisplay = ref({})

// 展示层尺寸观察，不改变图表数据或下钻事件。
const observationRoot = ref(null)
useChartContainerResize(observationRoot, element => echarts.getInstanceByDom(element))

// 弹窗尺寸管理（拖拽调整 + 本地缓存）
const realtimeDialog = useDialogSize('obs-realtime', { width: '1000px' })
const detailDialog = useDialogSize('obs-detail', { width: '1100px' })
const drillDialog = useDialogSize('obs-drill', { width: '900px' })

// @opened 回调里的 cleanup 函数
let realtimeCleanup = null, detailCleanup = null, drillCleanup = null

// 统一的时间口径：预设（currentTimeRange）+ 自定义区间（customTimeRange）+ 下钻聚焦窗（drilldownStartTime/EndTime）。
// 取数优先级：聚焦窗 > 自定义区间 > 预设；预设时不下发 startTime/endTime，交给后端按 timeRange 取最近窗口。
const currentTimeRange = ref('1d')
const customTimeVisible = ref(false)
const customTimeDraft = ref(null)
const customTimeRange = ref(null)
const customDatePanelOpen = ref(false)   // 日期下拉是否展开：展开时为它预留高度，避免“确定”被日历盖住

// 下钻状态
const drilldownBreadcrumbs = ref([]) // 面包屑路径 [{ label, timeRange, startTime?, endTime?, custom? }]
const drilldownStartTime = ref(null) // 下钻后的聚焦窗口开始
const drilldownEndTime = ref(null)   // 下钻后的聚焦窗口结束

const hasFocusWindow = computed(() => !!(drilldownStartTime.value && drilldownEndTime.value))
const selectedTimePreset = computed({
  get: () => (customTimeRange.value || hasFocusWindow.value ? '' : currentTimeRange.value),
  set: (range) => {
    // 主动选预设 = 回到顶层时间口径：自定义区间和下钻聚焦窗都失效，避免旧窗口盖住新预设
    customTimeRange.value = null
    resetDrilldownState()
    currentTimeRange.value = range
  }
})
const getObservationTimeWindow = () => {
  if (hasFocusWindow.value) return { startTime: drilldownStartTime.value, endTime: drilldownEndTime.value }
  if (customTimeRange.value) return { startTime: customTimeRange.value[0], endTime: customTimeRange.value[1] }
  return {}
}
const activeWindow = computed(() => getObservationTimeWindow())
const hasExplicitWindow = () => !!(hasFocusWindow.value || customTimeRange.value)

// ===== 区间均分（外层分数 = 所选时间区间内趋势采样点平均） =====
// 口径：只取 healthScore 有效的采样点做算术平均并四舍五入；无有效点返回 null，调用方回退实时汇总分。
const averageHealthSamples = (data) => {
  const values = (data || [])
    .map(item => Number(item?.healthScore))
    .filter(value => Number.isFinite(value))
  if (!values.length) return null
  return {
    score: Math.round(values.reduce((sum, value) => sum + value, 0) / values.length),
    samples: values.length
  }
}

// 区间均分缓存 key：时间窗 + 粒度。预设档位没有显式 start/end（后端取“最近 N”），用粒度区分。
const buildObservationScopeKey = () => {
  const window = getObservationTimeWindow()
  const windowKey = window.startTime && window.endTime ? `${window.startTime}~${window.endTime}` : 'latest'
  return `${currentTimeRange.value}|${windowKey}`
}

// 顶部仪表盘：当前区间系统健康度趋势的均分；无有效点时为 null（回退实时汇总分）
const systemIntervalStats = ref({ score: null, samples: 0 })

// 区间长度 → 后端粒度档位：窗口本身已由 startTime/endTime 限定，timeRange 只决定查哪张快照表
const deriveTimeRangeFromDuration = (durationMs) => {
  if (durationMs <= 3600000) return '1h'
  if (durationMs <= 21600000) return '6h'
  if (durationMs <= 86400000) return '1d'
  if (durationMs <= 604800000) return '7d'
  return '30d'
}
const parseCustomTimeValue = (value) => new Date(String(value || '').replace(' ', 'T'))
const updateCustomTimeDraft = () => {
  customTimeDraft.value = customTimeRange.value ? [...customTimeRange.value] : null
}
const toggleCustomTimePicker = () => {
  // 用受控 visible 替代 v-model：Element Plus 2.x 的 popover 点击面板外部会自动关闭，
  // 而日期面板默认 teleport 到 body，点日期就被判定成“外部点击”，所以只能由按钮/取消/确定控制开关。
  customTimeVisible.value = !customTimeVisible.value
}
const handleCustomDateVisibleChange = (visible) => { customDatePanelOpen.value = visible }
const closeCustomTimePicker = () => {
  customDatePanelOpen.value = false
  customTimeVisible.value = false
}
const handleCustomTimeDocumentClick = (event) => {
  if (!customTimeVisible.value) return
  const target = event.target
  if (!(target instanceof Element)) return
  // 点日期面板（teleport 到 body 的 .el-picker__popper）、popover 本体或触发按钮都不算外部点击
  if (target.closest('.observation-custom-time, .el-picker__popper, .observation-custom-time-trigger')) return
  customDatePanelOpen.value = false
  customTimeVisible.value = false
}
const applyCustomTimeRange = () => {
  const range = customTimeDraft.value
  if (!range || range.length !== 2 || parseCustomTimeValue(range[0]) >= parseCustomTimeValue(range[1])) {
    ElMessage.warning('请选择有效的开始时间和结束时间，开始时间须早于结束时间')
    return
  }
  customTimeRange.value = [...range]
  // 自定义区间与聚焦窗互斥：先清聚焦窗，避免旧的聚焦窗口盖住刚选的区间
  resetDrilldownState()
  currentTimeRange.value = deriveTimeRangeFromDuration(parseCustomTimeValue(range[1]) - parseCustomTimeValue(range[0]))
  customDatePanelOpen.value = false
  customTimeVisible.value = false
  // 只走一次刷新：不再依赖 watch(currentTimeRange) + @change 双触发
  reloadForCurrentView()
}
watch(customTimeVisible, (visible) => { if (visible) updateCustomTimeDraft() })

const systemHealthScore = ref(null)
const tableLoading = ref(false)

// 5分钟明细弹窗
const drillDetailVisible = ref(false)
const drillDetailLoading = ref(false)
const drillDetailData = ref([])
const drillDetailTimeRange = ref('')

const observationCategories = [
  { code: 'external', name: '对外部客户' },
  { code: 'internal', name: '对内部人员' },
  { code: 'satisfaction', name: '满意度及投诉服务体验' },
  { code: 'system', name: '运行效率' },
  { code: 'compliance', name: '合规管理' },
  { code: 'business', name: '业务价值' },
  { code: 'resource', name: '资源投入' },
  { code: 'quality', name: '数据质量' }
]
const categoryHealthList = ref(observationCategories.map(item => ({ ...item, score: null, trend: 'stable', metricCount: 0, alarmCount: 0 })))

// 采集频率：只用于“预设档位”下避免向采集频率更粗的指标要更细的粒度；
// 自定义区间/聚焦窗口时粒度由窗口长度决定，不能再被这里放大。
const SUBCATEGORY_FREQUENCY = {
  '缴费开机': '1h',
  '流量提醒': '1d',
  '系统稽核': '1d',
  '查询类': '1h',
  '办理类-单业务': '1h',
  '办理类-融合业务': '1h',
  '系统性能': '5m',
  '系统异常': '1h'
}
const getSubcategoryFrequency = (name) => SUBCATEGORY_FREQUENCY[name] || ''

// 权重配置（从API加载，用于弹框显示）
const weightConfigForDisplay = ref(Object.fromEntries(observationCategories.map(item => [item.name, {}])))

// 加载权重配置
const loadWeightConfig = async () => {
  try {
    const res = await weightApi.getConfig(1) // systemId=1
    const data = res.data
    const weightDisplay = {}
    for (const category of data?.categories || []) {
      const weight = data?.categoryWeights?.[category.id]
      if (weight != null && Number.isFinite(Number(weight))) weightDisplay[category.categoryName] = Number(weight)
    }
    categoryWeightsForDisplay.value = weightDisplay
    console.log('权重API返回数据:', data)
    if (data && data.subcategoryWeights) {
      // 后端返回格式: { categoryId: { subCategoryName: weight } }
      // categories数组包含 {id, categoryName} 映射
      const categoryIdToName = {}
      if (data.categories) {
        data.categories.forEach(cat => {
          categoryIdToName[cat.id] = cat.categoryName
        })
      }
      console.log('categoryIdToName映射:', categoryIdToName)
      console.log('weightConfigForDisplay初始值:', weightConfigForDisplay.value)
      console.log('subcategoryWeights原始数据:', data.subcategoryWeights)

      // 转换为前端格式: { '大类名': { '小类名': weight } }
      for (const [catId, weights] of Object.entries(data.subcategoryWeights)) {
        // JSON keys are strings, so convert to number for lookup
        const catName = categoryIdToName[Number(catId)]
        console.log(`处理catId=${catId}, catName=${catName}, weights=${JSON.stringify(weights)}`)
        if (catName && weightConfigForDisplay.value[catName] !== undefined) {
          for (const [subCategoryName, weight] of Object.entries(weights)) {
            weightConfigForDisplay.value[catName][subCategoryName] = weight
          }
          console.log(`已更新weightConfigForDisplay[${catName}]`)
        } else {
          console.log(`未匹配: catName=${catName}, weightConfigForDisplay[catName]=${weightConfigForDisplay.value[catName]}`)
        }
      }
      console.log('权重配置加载完成:', weightConfigForDisplay.value)
    } else {
      console.log('API返回数据中没有subcategoryWeights:', data)
    }
  } catch (error) {
    console.error('加载权重配置失败:', error)
  }
}

// 获取指定小类的权重
const getSubcategoryWeight = (categoryName, subCategoryName) => {
  const weights = weightConfigForDisplay.value[categoryName]
  console.log(`getSubcategoryWeight called: categoryName=${categoryName}, subCategoryName=${subCategoryName}`)
  console.log(`  weights for ${categoryName}:`, weights)
  if (weights && weights[subCategoryName] !== undefined) {
    console.log(`  found weight:`, weights[subCategoryName])
    return weights[subCategoryName]
  }
  return null
}

const selectedCategory = ref(null)
const selectedSubCategory = ref(null) // 选中的小类，为null表示显示全部

// 可见大类（白名单过滤）：只隐藏“整个大类可计分指标数 expected=0”的大类；
// 部分白名单/缺数的大类继续展示，分数由后端给 null，页面显示 '-'。
// 汇总未返回时为空数组，页面走骨架，不再先渲染 8 个硬编码大类。
const visibleCategoryRows = computed(() => {
  const summary = healthSummary.value
  if (!summary) return []
  return (summary.categories || [])
    // 只认“明确为 0”的大类；缺 expected 字段（老接口/缺数）继续展示，分数显示 '-'
    .filter(category => !(category.expected != null && Number(category.expected) === 0))
    .map(category => {
      const definition = observationCategories.find(item => item.name === category.name)
      const code = definition?.code || `category-${category.categoryId ?? category.name}`
      return {
        ...category,
        code,
        name: category.name,
        score: category.score ?? null,
        status: category.status,
        metricCount: category.expected ?? 0,
        alarmCount: category.status === 'alarm' ? 1 : 0,
        subcategories: (category.subcategories || []).map(sub => ({
          ...sub,
          name: sub.name,
          // 总览图 id 用大类前缀保证唯一，避免不同大类存在同名小类时图表互相覆盖
          code: `${code}::${sub.name}`,
          parentCode: code,
          parentName: category.name,
          frequency: getSubcategoryFrequency(sub.name),
          metricCount: sub.expected ?? 0,
          alarmCount: sub.status === 'alarm' ? 1 : 0
        }))
      }
    })
})

// ===== 大/小类区间均分：外层分数 = 当前时间口径下该节点趋势采样点的平均 =====
const categoryIntervalStats = ref({})      // { 大类名: { score, samples } } 当前时间口径下的显示值
const subcategoryIntervalStats = ref({})   // { '大类名||小类名': { score, samples } }
const categoryIntervalCache = new Map()    // key: 时间窗+粒度 + 大类名 → { score, samples }
const categoryIntervalInflight = new Set() // 同一 key 正在请求时不再重复发

const categoryIntervalKey = (categoryName) => `${buildObservationScopeKey()}|category|${categoryName}`
const subcategoryIntervalKey = (parentName, subName) => `${parentName}||${subName}`

// 用缓存刷新当前可见大类的区间均分（只读缓存，不发请求）；没缓存的行继续用汇总分
const applyCategoryIntervalScores = () => {
  const next = {}
  for (const category of visibleCategoryRows.value) {
    const cached = categoryIntervalCache.get(categoryIntervalKey(category.name))
    if (cached) next[category.name] = cached
  }
  categoryIntervalStats.value = next
}

const applySubcategoryIntervalScore = (parentName, subName, data) => {
  const stats = averageHealthSamples(data)
  const key = subcategoryIntervalKey(parentName, subName)
  if (stats) {
    subcategoryIntervalStats.value = { ...subcategoryIntervalStats.value, [key]: stats }
    return
  }
  if (subcategoryIntervalStats.value[key]) {
    const next = { ...subcategoryIntervalStats.value }
    delete next[key]
    subcategoryIntervalStats.value = next
  }
}

// 可见大类的区间均分：汇总之后再并行拉（最多 8 个），按“时间窗+粒度”缓存复用，不阻塞首屏骨架
const loadCategoryIntervalAverages = async (seq = observationReloadSeq) => {
  applyCategoryIntervalScores()
  // 体系只有 8 个大类；万一后端返回更多也最多拉 8 个，避免一次性打爆趋势接口
  const categoryNames = visibleCategoryRows.value.map(category => category.name).slice(0, 8)
  const selectedName = selectedCategory.value?.name
  await Promise.all(categoryNames.map(async categoryName => {
    const key = categoryIntervalKey(categoryName)
    // 详情页当前大类的大类趋势图会在 loadCategoryHealthChart 里回填均分，这里跳过避免重复请求
    if (selectedName && categoryName === selectedName) return
    // 已缓存 / 已在飞 → 跳过；fetch 下钻等场景不会重复打同一大类
    if (categoryIntervalCache.has(key) || categoryIntervalInflight.has(key)) return
    categoryIntervalInflight.add(key)
    try {
      const res = await observationApi.getCategoryHealthTrend({
        categoryName,
        timeRange: currentTimeRange.value,
        ...getObservationTimeWindow()
      })
      // 旧时间窗口的响应直接丢弃，避免旧区间均分覆盖新区间
      if (!isCurrentReload(seq)) return
      const stats = averageHealthSamples(res.data?.data)
      if (!stats) return
      categoryIntervalCache.set(key, stats)
      categoryIntervalStats.value = { ...categoryIntervalStats.value, [categoryName]: stats }
    } catch (error) {
      console.error(`加载${categoryName}区间均分失败:`, error)
    } finally {
      categoryIntervalInflight.delete(key)
    }
  }))
}

// 切换时间口径时清掉"显示值"（缓存保留、按新 key 复用），保证不会把旧区间均分挂在新窗口上
const resetIntervalScoreViews = () => {
  subcategoryIntervalStats.value = {}
  categoryIntervalStats.value = {}
  applyCategoryIntervalScores()
}

// 外层展示用大类行：分数优先区间均分，暂无区间数据时用实时汇总分（不清零、不跳 0）；
// 小类也带上区间均分，保证“大类简要得分”里的展开明细与总览卡片口径一致
const categoryRowsForDisplay = computed(() => visibleCategoryRows.value.map(category => {
  const stats = categoryIntervalStats.value[category.name]
  return {
    ...category,
    score: stats ? stats.score : category.score,
    intervalSamples: stats ? stats.samples : null,
    subcategories: (category.subcategories || []).map(sub => {
      const subStats = subcategoryIntervalStats.value[subcategoryIntervalKey(category.name, sub.name)]
      return subStats
        ? { ...sub, score: subStats.score, intervalSamples: subStats.samples }
        : { ...sub, intervalSamples: null }
    })
  }
}))

// 区间均分说明（总览行/小类卡片/详情标题共用），未算出来时提示暂显实时分
const intervalScoreTitle = (samples) => samples
  ? `区间均分 · ${samples} 个采样点`
  : '区间趋势未加载或无有效采样，暂显示实时最新分'

// 详情页标题旁的大类区间均分（与总览/简要得分读同一份缓存，不额外请求）
const detailIntervalText = computed(() => {
  if (!selectedCategory.value) return ''
  const stats = categoryIntervalStats.value[selectedCategory.value.name]
  if (stats) return `区间均分 ${stats.score} 分 · ${stats.samples} 个采样点`
  if (healthDataState.value === 'loading' || healthScoreLoading.value) return '区间均分计算中…'
  const latestScore = selectedCategory.value.score
  return latestScore != null ? `当前区间无有效采样 · 实时最新 ${latestScore} 分` : '当前区间无有效采样'
})

// 首屏骨架判断：只有“还没有任何汇总数据”时才显示骨架；
// 已有数据后的刷新保留上次值（组件里加遮罩），不再整块闪回骨架
const summaryLoading = computed(() => !healthSummary.value && (healthScoreLoading.value || healthDataState.value === 'loading'))

// 按父类分组的小类健康度列表（默认视图用）：大类/小类分数都优先用区间均分
const overviewParentList = computed(() => [...categoryRowsForDisplay.value]
  .map(category => ({
    ...category,
    subcategories: [...(category.subcategories || [])].map(sub => {
      const stats = subcategoryIntervalStats.value[subcategoryIntervalKey(category.name, sub.name)]
      return stats
        ? { ...sub, score: stats.score, intervalSamples: stats.samples }
        : { ...sub, intervalSamples: null }
    }).sort((a, b) => (a.score ?? 100) - (b.score ?? 100))
  }))
  // 大类也按健康度从低到高排序（最差的放最上面）
  .sort((a, b) => (a.score ?? 100) - (b.score ?? 100)))

// 所有小类健康度汇总列表（详情页兜底 & 定位用），由后端汇总派生，分数优先区间均分
const allSubcategoryOverviewList = computed(() => {
  const rows = []
  for (const category of categoryRowsForDisplay.value) {
    for (const sub of category.subcategories || []) {
      const stats = subcategoryIntervalStats.value[subcategoryIntervalKey(category.name, sub.name)]
      rows.push({
        code: sub.name,
        name: sub.name,
        parentName: category.name,
        parentCode: category.code,
        frequency: getSubcategoryFrequency(sub.name) || '1d',
        score: stats?.score ?? sub.score ?? null,
        intervalSamples: stats?.samples ?? null,
        weight: sub.weight ?? null,
        participants: sub.participants ?? null,
        expected: sub.expected ?? null,
        metricCount: sub.expected ?? 0,
        alarmCount: sub.status === 'alarm' ? 1 : 0,
        trend: sub.status === 'alarm' ? 'down' : 'up'
      })
    }
  }
  return rows
})

const metricList = ref([]) // 所有指标列表

// 详情页小类列表：选中大类时从后端汇总快照同步，展开图表时再拉趋势/指标
const subcategoryList = ref([])
const syncDetailSubcategories = () => {
  const category = selectedCategory.value
  if (!category) {
    subcategoryList.value = []
    return
  }
  const summaryCategory = healthSummary.value?.categories?.find(item => item.name === category.name)
  const subs = summaryCategory?.subcategories?.length
    ? summaryCategory.subcategories
    : allSubcategoryOverviewList.value.filter(sub => sub.parentName === category.name)
  subcategoryList.value = subs.map(sub => {
    // 已经拉过该小类趋势的，沿用区间均分；否则先显示实时汇总分，等图表拉完再覆盖
    const stats = subcategoryIntervalStats.value[subcategoryIntervalKey(category.name, sub.name)]
    return {
      code: sub.name,
      name: sub.name,
      score: stats?.score ?? sub.score ?? null,
      intervalSamples: stats?.samples ?? null,
      data: [],
      participants: sub.participants ?? null,
      expected: sub.expected ?? null,
      // 权重优先用后端本次算分实际使用的值，拿不到再退回权重配置
      weight: sub.weight != null ? Number(sub.weight) : (getSubcategoryWeight(category.name, sub.name) ?? 1),
      frequency: getSubcategoryFrequency(sub.name) || '1d'
    }
  })
}

// 根据子类权重计算当前选中大类的健康度
const currentCategoryHealthScore = computed(() => {
  if (!subcategoryList.value || subcategoryList.value.length === 0) return null
  const subs = subcategoryList.value
  const totalWeight = subs.reduce((sum, sub) => sum + (sub.weight || 1), 0)
  if (totalWeight === 0) return 100
  const weightedSum = subs.reduce((sum, sub) => sum + ((sub.score || 100) * (sub.weight || 1)), 0)
  return Math.round(weightedSum / totalWeight)
})

// 根据子类健康度更新大类健康度
const updateCategoryHealthFromSubcategories = (categoryCode) => {
  const targetCat = categoryHealthList.value.find(c => c.code === categoryCode)
  if (!targetCat || !subcategoryList.value || subcategoryList.value.length === 0) return

  const subs = subcategoryList.value
  const totalWeight = subs.reduce((sum, sub) => sum + (sub.weight || 1), 0)
  if (totalWeight === 0) {
    targetCat.score = 100
  } else {
    const weightedSum = subs.reduce((sum, sub) => sum + ((sub.score || 100) * (sub.weight || 1)), 0)
    targetCat.score = Math.round(weightedSum / totalWeight)
  }
  targetCat.trend = targetCat.score >= 90 ? 'up' : (targetCat.score >= 70 ? 'down' : 'down')
}

// 展开/折叠状态
const expandedGroups = ref({
  '查询类': true,
  '办理类-单业务': true,
  '办理类-融合业务': true,
  '系统稽核': true,
  '缴费开机': true,
  '流量提醒': true,
  '系统性能': true,
  '系统异常': true,
  '工单': true,
  '问题': true
})

// 已加载图表的指标ID集合（用于懒加载）
const loadedChartMetrics = ref(new Set())

// Intersection Observer 实例
let chartObserver = null

// 初始化图表懒加载 Observer
const initChartObserver = () => {
  if (chartObserver) {
    chartObserver.disconnect()
  }
  chartObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const metricId = entry.target.dataset.metricId
        const metric = metricList.value.find(m => m.metricId == metricId)
        if (metric && !loadedChartMetrics.value.has(metric.metricId)) {
          initMetricCharts(metric)
        }
      }
    })
  }, {
    rootMargin: '100px', // 提前100px开始加载
    threshold: 0.1
  })
}

// 观察指标图表容器（用于懒加载）
const observeMetricChart = (metricId) => {
  if (!chartObserver) return
  const el = document.querySelector(`[data-metric-id="${metricId}"]`)
  if (el) {
    chartObserver.observe(el)
  }
}

// 停止观察指标图表容器
const unobserveMetricChart = (metricId) => {
  if (!chartObserver) return
  const el = document.querySelector(`[data-metric-id="${metricId}"]`)
  if (el) {
    chartObserver.unobserve(el)
  }
}

// 汇总视图小类趋势懒加载：只有进入视口的行才请求，避免首屏对所有小类硬发趋势请求
let overviewChartObserver = null
const initOverviewChartObserver = () => {
  if (overviewChartObserver) overviewChartObserver.disconnect()
  overviewChartObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return
      const sub = findOverviewSubByKey(entry.target.dataset.overviewSubKey)
      if (sub) loadOverviewSubcategoryTrend(sub)
    })
  }, {
    rootMargin: '120px', // 提前120px开始加载，滚动时图表不会空着
    threshold: 0.05
  })
}
const observeOverviewCharts = () => {
  if (!overviewChartObserver) return
  document.querySelectorAll('[data-overview-sub-key]').forEach(el => overviewChartObserver.observe(el))
}
const findOverviewSubByKey = (key) => {
  if (!key) return null
  for (const parent of overviewParentList.value) {
    const found = (parent.subcategories || []).find(sub => sub.code === key)
    if (found) return found
  }
  return null
}

// 系统稽核指标分组（按二级分类分组）
const systemAuditGroups = computed(() => {
  if (selectedCategory.value?.name !== '对外部客户') return []
  const metrics = metricList.value.filter(m => m.subCategoryName === '系统稽核')
  const groups = {}
  metrics.forEach(m => {
    const secondClassify = m.secondClassify || m.firstClassify || '未分类'
    if (!groups[secondClassify]) {
      groups[secondClassify] = []
    }
    groups[secondClassify].push(m)
  })
  return Object.entries(groups).map(([name, items]) => ({
    code: name,
    name: name,
    metrics: items
  }))
})

const getScoreClass = (score) => {
  // 分档阈值统一来自 system_config（60/80），前后端同一口径
  if (score === null || score === undefined || score === '') return ''
  return scoreTone(score)
}

const getScoreDesc = (score) => {
  if (score === null || score === undefined) return '暂无数据'
  const level = scoreLevel(score)
  if (level === 'normal') return '系统运行良好'
  if (level === 'warning') return '部分指标需关注'
  return '需要尽快处理'
}

// 采集频率显示文本
const frequencyLabels = { '5m': '5分钟', '1h': '1小时', '1d': '1天' }
const getFrequencyLabel = (freq) => frequencyLabels[freq] || freq || ''

// 根据采集频率计算有效查询粒度（不能比采集频率更细）
const getEffectiveTimeRange = (subFrequency, userTimeRange) => {
  const granularityOrder = { '5m': 0, '1h': 1, '6h': 2, '1d': 3, '7d': 4, '30d': 5 }
  const freqToRange = { '5m': '1h', '1h': '1d', '1d': '7d' }
  const minRange = freqToRange[subFrequency] || '1d'
  if ((granularityOrder[userTimeRange] || 3) < (granularityOrder[minRange] || 3)) {
    return minRange
  }
  return userTimeRange
}

// 趋势接口最终使用的粒度档位：自定义区间/聚焦窗口下由窗口长度决定，
// 不能再被“采集频率下限”强行放大，否则聚焦 1 小时却去查 7 天快照会一个点都没有。
const resolveTrendTimeRange = (subFrequency) => {
  if (hasExplicitWindow()) return currentTimeRange.value
  return getEffectiveTimeRange(subFrequency, currentTimeRange.value)
}

// 按条件统计大类数量
const getCategoryCountByScore = (op1, val1, op2, val2) => {
  let count = 0
  for (const cat of categoryHealthList.value) {
    const score = cat.score
    if (score === null || score === undefined) continue
    let match = false
    if (op1 === '>=' && op2 === undefined) {
      match = score >= val1
    } else if (op1 === '<' && op2 === '>=') {
      match = score < val1 && score >= val2
    } else if (op1 === '<' && op2 === undefined) {
      match = score < val1
    }
    if (match) count++
  }
  return count
}

// 获取总指标数
const getTotalMetricCount = () => {
  return overviewParentList.value.reduce((sum, p) => sum + (p.metricCount || 0), 0)
}

// 获取总异常数
const getTotalAlarmCount = () => {
  return overviewParentList.value.reduce((sum, p) => sum + (p.alarmCount || 0), 0)
}

// 按健康度条件统计指标数量
const getMetricCountByHealth = (op1, val1, op2, val2) => {
  let count = 0
  for (const parent of overviewParentList.value) {
    for (const sub of parent.subcategories) {
      if (sub.score === null || sub.score === undefined) continue
      let match = false
      if (op1 === '>=' && op2 === undefined) {
        match = sub.score >= val1
      } else if (op1 === '<' && op2 === '>=') {
        match = sub.score < val1 && sub.score >= val2
      } else if (op1 === '<' && op2 === undefined) {
        match = sub.score < val1
      }
      if (match) count++
    }
  }
  return count
}

// 滚动到大类行（总览区）
const scrollToParent = (parentCode) => {
  const element = document.getElementById('parent-row-' + parentCode)
  if (element) {
    element.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

// 点击宏观区域的大类名称 → 滚动到总览区对应行
const scrollToCategory = (code) => {
  // 先确保在总览视图
  if (selectedCategory.value) return
  nextTick(() => {
    const el = document.getElementById('parent-row-' + code)
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

// 点击大类信息卡片，进入详情（拿不到回填行时直接用汇总行）
const handleParentInfoClick = (parent) => {
  handleCategoryClick(categoryHealthList.value.find(c => c.code === parent.code) || parent)
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

// 请求序号：时间口径/聚焦窗一变就自增，旧窗口的响应回来直接丢弃，避免旧数据覆盖新数据
let observationReloadSeq = 0
const beginReload = () => ++observationReloadSeq
const isCurrentReload = (seq) => seq === observationReloadSeq

// 汇总视图小类趋势缓存：已拉到的趋势在进入详情/返回时复用，initOverviewSubcategoryCharts 不再重复请求
const overviewTrendCache = new Map()
const overviewLoadedKeys = new Set()
const clearOverviewTrendCache = () => overviewTrendCache.clear()
const overviewSubKey = (sub) => `${sub.parentName}||${sub.name}`

// 统一的图表销毁工具：ECharts 实例必须手动 dispose，否则 v-if 切换后会残留旧实例
const disposeChartDom = (domId) => {
  const element = document.getElementById(domId)
  if (!element) return
  const instance = echarts.getInstanceByDom(element)
  if (instance) instance.dispose()
}
const disposeOverviewCharts = () => {
  for (const parent of overviewParentList.value) {
    for (const sub of parent.subcategories || []) disposeChartDom('overview-sub-health-chart-' + sub.code)
  }
}
const disposeDetailCharts = () => {
  disposeChartDom('category-health-chart')
  for (const sub of subcategoryList.value) disposeChartDom('sub-health-chart-' + sub.code)
  for (const metric of metricList.value) {
    disposeChartDom('metric-value-chart-' + metric.metricId)
    disposeChartDom('metric-health-chart-' + metric.metricId)
  }
}

// 点击大类（总览卡片 / 指标大类简要得分）
const handleCategoryClick = (cat, preSelectedSub = null) => {
  selectedCategory.value = cat
  selectedSubCategory.value = preSelectedSub
  loadedChartMetrics.value = new Set()
  syncDetailSubcategories()
  disposeOverviewCharts()
  nextTick(() => { reloadCategoryDetail() })
}

// 从详情返回总览
const handleBack = () => {
  // 让详情页还在飞的请求作废，避免返回后旧响应把指标列表/图表状态写回来
  beginReload()
  loadedChartMetrics.value = new Set()
  disposeDetailCharts()
  selectedCategory.value = null
  selectedSubCategory.value = null
  metricList.value = []
  subcategoryList.value = []
  nextTick(() => {
    // 总览 DOM 重新挂载：先用缓存重绘，再交给 IntersectionObserver 补齐未加载的行
    initOverviewSubcategoryCharts()
  })
}

// 点击小类卡片：切换该小类的指标筛选
const handleSubcategoryClick = (sub) => {
  if (selectedSubCategory.value === sub.code) {
    selectedSubCategory.value = null // 再次点击取消选中
  } else {
    selectedSubCategory.value = sub.code
  }
  // 被过滤掉的指标 DOM 会被移除：先销毁实例并清掉“已加载”标记，切回来时才会重新画图
  for (const metric of metricList.value) {
    const visible = !selectedSubCategory.value || metric.subCategoryName === selectedSubCategory.value
    if (visible || !loadedChartMetrics.value.has(metric.metricId)) continue
    disposeChartDom('metric-value-chart-' + metric.metricId)
    disposeChartDom('metric-health-chart-' + metric.metricId)
    loadedChartMetrics.value.delete(metric.metricId)
    unobserveMetricChart(metric.metricId)
  }
  nextTick(() => {
    initSubcategoryCharts()
    // 懒加载：观察可见的指标图表
    setTimeout(() => {
      const visibleMetrics = metricList.value.filter(m =>
        !selectedSubCategory.value || m.subCategoryName === selectedSubCategory.value
      )
      visibleMetrics.forEach(m => {
        if (!loadedChartMetrics.value.has(m.metricId)) {
          const el = document.querySelector(`[data-metric-id="${m.metricId}"]`)
          if (el) chartObserver?.observe(el)
        }
      })
    }, 100)
  })
}

// 顶部刷新按钮：保持当前时间口径（预设/自定义/聚焦窗）重取数；
// 同时重新拉一次“最新总分”，避免分数停留在进页面那一刻
const refreshObservation = async () => {
  // 主动刷新时区间均分也重新算一遍，不吃旧缓存
  categoryIntervalCache.clear()
  await Promise.all([loadHealthSummary(), reloadForCurrentView()])
}

// 预设切换（单选组）：v-model setter 已更新粒度并清空自定义/聚焦窗，这里只走一次重载
const handleTimeRangeSwitch = () => {
  customDatePanelOpen.value = false
  customTimeVisible.value = false
  reloadForCurrentView()
}

// 统一重载入口：汇总视图与详情视图共用同一路径 + 同一个请求序号
const reloadForCurrentView = async () => {
  const seq = beginReload()
  clearOverviewTrendCache()
  // 新时间口径：先清掉旧窗口的“区间均分显示值”，缓存按新 key 复用；未算出来的先回退实时汇总分
  resetIntervalScoreViews()
  const tasks = [
    loadSystemHealthTrendChart(seq),
    // 外层大类/简要得分也换成新区间均分（最多 8 个，并行、不阻塞其他刷新）
    loadCategoryIntervalAverages(seq)
  ]
  if (selectedCategory.value) tasks.push(reloadCategoryDetail(seq))
  else tasks.push(reloadOverviewTrends(seq))
  await Promise.all(tasks)
}

// 汇总视图重载：只刷新已经进入过视口的行，其余等 IntersectionObserver 触达
const reloadOverviewTrends = async (seq) => {
  await nextTick()
  const subs = []
  for (const parent of overviewParentList.value) {
    for (const sub of parent.subcategories || []) {
      if (overviewLoadedKeys.has(overviewSubKey(sub))) subs.push(sub)
    }
  }
  await Promise.all(subs.map(sub => loadOverviewSubcategoryTrend(sub, seq)))
  observeOverviewCharts()
}

// 详情视图重载：大类趋势 + 小类趋势 + 指标列表一起按新时间窗刷新
const reloadCategoryDetail = async (seq = beginReload()) => {
  tableLoading.value = true
  try {
    syncDetailSubcategories()
    disposeDetailCharts()
    metricList.value = []
    loadedChartMetrics.value = new Set()
    await nextTick()
    await Promise.all([
      loadCategoryHealthChart(seq),
      loadSubcategoryMetrics(seq)
    ])
    if (!isCurrentReload(seq)) return
    await nextTick()
    await initSubcategoryCharts(seq)
    observeVisibleMetricCharts()
  } finally {
    if (isCurrentReload(seq)) tableLoading.value = false
  }
}

// 时间窗变化后指标图表必须重拉：重新观察当前渲染出来的指标卡片，交给懒加载重建
const observeVisibleMetricCharts = () => {
  nextTick(() => {
    document.querySelectorAll('.metric-chart-item[data-metric-id]').forEach(el => {
      chartObserver?.unobserve(el)
      chartObserver?.observe(el)
    })
  })
}

// 汇总视图：只重绘已缓存的小类趋势（这里不再对同一小类重复请求）
const initOverviewSubcategoryCharts = () => {
  for (const parent of overviewParentList.value) {
    for (const sub of parent.subcategories || []) {
      const data = overviewTrendCache.get(overviewSubKey(sub))
      if (data) renderOverviewSubcategoryChart(sub, data)
    }
  }
  observeOverviewCharts()
}

// 懒加载单个小类趋势：命中缓存直接复用，未命中才请求；拉到后小类卡片分数改为区间均分
const loadOverviewSubcategoryTrend = async (sub, seq = observationReloadSeq) => {
  const key = overviewSubKey(sub)
  overviewLoadedKeys.add(key)
  const cached = overviewTrendCache.get(key)
  if (cached) {
    applySubcategoryIntervalScore(sub.parentName, sub.name, cached)
    renderOverviewSubcategoryChart(sub, cached)
    return
  }
  try {
    const res = await observationApi.getSubcategoryHealthTrend({
      categoryName: sub.parentName,
      subCategoryName: sub.name,
      timeRange: currentTimeRange.value,
      ...getObservationTimeWindow()
    })
    if (!isCurrentReload(seq)) return
    const data = res.data?.data || []
    overviewTrendCache.set(key, data)
    applySubcategoryIntervalScore(sub.parentName, sub.name, data)
    renderOverviewSubcategoryChart(sub, data)
  } catch (error) {
    console.error(`加载${sub.parentName}-${sub.name}趋势失败:`, error)
  }
}

// 渲染汇总视图小类趋势（只显示最近 6 个点）
const renderOverviewSubcategoryChart = (sub, rawData) => {
  const chartDom = document.getElementById('overview-sub-health-chart-' + sub.code)
  if (!chartDom) return
  const existing = echarts.getInstanceByDom(chartDom)
  if (existing) existing.dispose()

  const data = (rawData || []).slice(-6)
  const times = data.map(d => formatTimeLabel(d.recordTime, currentTimeRange.value))
  const scores = data.map(d => d.healthScore ?? null)
  const yRange = calculateYRange(scores, 0, 100)

  const chartInstance = echarts.init(chartDom)
  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        const idx = p.dataIndex
        const fullTime = data[idx]?.recordTime || times[idx]
        return `${formatFullTimestamp(fullTime)}<br/>${p.marker} ${p.value}`
      }
    },
    grid: { left: 40, right: 8, top: 4, bottom: 20, containLabel: false },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: { fontSize: 9, interval: 'auto', rotate: 0 },
      axisLine: { onZero: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      min: yRange.min,
      max: yRange.max,
      splitNumber: 3,
      axisLabel: { fontSize: 9, formatter: (val) => Math.round(val) },
      axisLine: { show: false },
      splitLine: { lineStyle: { type: 'dashed', color: observationChartColor('border-soft') } }
    },
    series: [{
      type: 'line',
      data: scores,
      smooth: true,
      symbolSize: 4,
      lineStyle: { width: 1.5 },
      itemStyle: { color: observationChartColor('primary') },
      areaStyle: { opacity: 0.15 }
    }]
  })

  // 绑定点击下钻
  chartInstance.on('click', (params) => {
    const raw = data[params.dataIndex]
    if (!raw) return
    const drillInfo = getChartDrillInfo(currentTimeRange.value, raw.recordTime, data)
    if (drillInfo) handleChartDrilldown(drillInfo)
  })
}

// 系统健康度汇总（2026-11-17 P3 起由后端聚合服务统一口径返回：
// 该系统名下全部大类加权 + 覆盖率 + 最差指标，前端不再自己平均，也不再逐个大类拉趋势算分）
const healthSummary = ref(null)
const systemOptions = ref([])
const selectedSystemId = ref(1)
let healthSummaryRequestId = 0

const loadSystemOptions = async () => {
  try {
    const res = await observationApi.getSystems()
    systemOptions.value = res.data || []
    if (systemOptions.value.length && !systemOptions.value.some(item => item.systemId === selectedSystemId.value)) {
      selectedSystemId.value = systemOptions.value[0].systemId
    }
  } catch (error) {
    console.error('获取系统列表失败:', error)
  }
}

// 把汇总大类回填到 categoryHealthList，兼容旧的分类统计/定位代码路径
const syncCategoryHealthFromSummary = () => {
  const summary = healthSummary.value
  if (!summary) {
    categoryHealthList.value = observationCategories.map(item => ({ ...item, score: null, trend: 'stable', metricCount: 0, alarmCount: 0 }))
    return
  }
  categoryHealthList.value = (summary.categories || []).map(category => {
    const definition = observationCategories.find(item => item.name === category.name)
    return {
      code: definition?.code || category.categoryId?.toString() || category.name,
      name: category.name,
      score: category.score ?? null,
      trend: category.status === 'alarm' ? 'down' : 'up',
      metricCount: category.expected ?? 0,
      alarmCount: category.status === 'alarm' ? 1 : 0,
      participants: category.participants,
      expected: category.expected,
      weightShare: category.weightShare,
      subcategories: category.subcategories || []
    }
  })
  // 详情页打开时，大类分数变化要同步到当前选中对象
  if (selectedCategory.value) {
    const refreshed = categoryHealthList.value.find(row => row.name === selectedCategory.value.name)
    if (refreshed) {
      selectedCategory.value = refreshed
      syncDetailSubcategories()
    }
  }
}

// 取系统健康度汇总（实时最新口径，不跟时间窗走）；加载中保留旧分数，由仪表盘加遮罩
const loadHealthSummary = async () => {
  const requestId = ++healthSummaryRequestId
  healthScoreLoading.value = true
  try {
    const res = await observationApi.getSystemHealthSummary({ systemId: selectedSystemId.value })
    if (requestId !== healthSummaryRequestId) return
    const summary = res.data || null
    healthSummary.value = summary
    systemHealthScore.value = summary?.score ?? null
    syncCategoryHealthFromSummary()
    // 汇总变化后把缓存里已有的区间均分贴回来（换系统时缓存已清空，这里自然为空）
    applyCategoryIntervalScores()
    await nextTick()
    // 汇总回来才知道哪些大类要展示：重新观察总览图表行（未进入视口的仍不会请求）
    observeOverviewCharts()
  } catch (error) {
    if (requestId !== healthSummaryRequestId) return
    console.error('获取系统健康度汇总失败:', error)
    // 不清空旧分数：刷新失败时保留上次值，避免页面闪成空
    if (systemHealthScore.value == null) healthDataState.value = 'error'
  } finally {
    if (requestId === healthSummaryRequestId) healthScoreLoading.value = false
  }
}

const switchSystem = async (systemId) => {
  if (systemId === selectedSystemId.value) return
  selectedSystemId.value = systemId
  // 换系统后旧系统的分数/大类/小类都不能继续展示，先清空让骨架接住，避免张冠李戴
  healthSummary.value = null
  categoryHealthList.value = []
  // 只清趋势缓存、保留“已进入过视口”的 key：换系统后同名大类会复用 DOM，
  // 不保留 key 就不会重新取数，图表会停留在旧系统的数据上
  overviewTrendCache.clear()
  // 区间均分是按“系统的大类名”缓存的，换系统必须整批失效，避免旧系统均分挂到新系统
  categoryIntervalCache.clear()
  categoryIntervalInflight.clear()
  resetIntervalScoreViews()
  await loadHealthSummary()
  if (selectedCategory.value && !categoryHealthList.value.some(row => row.name === selectedCategory.value.name)) {
    selectedCategory.value = null
    selectedSubCategory.value = null
  }
  await reloadForCurrentView()
}

// 点击汇总视图中的小类卡片：进入该大类详情并预选小类
const handleSubcategoryOverviewClick = (sub) => {
  const parent = overviewParentList.value.find(row => row.code === sub.parentCode)
  if (parent) handleCategoryClick(parent, sub.name)
}

// 加载大类健康度图表
const loadCategoryHealthChart = async (seq = observationReloadSeq) => {
  const chartDom = document.getElementById('category-health-chart')
  if (!chartDom) return

  const chart = echarts.getInstanceByDom(chartDom)
  if (chart) chart.dispose()

  const categoryName = selectedCategory.value?.name
  if (!categoryName) return

  let res
  try {
    res = await observationApi.getCategoryHealthTrend({
      categoryName,
      timeRange: currentTimeRange.value,
      ...getObservationTimeWindow()
    })
  } catch (error) {
    console.error('加载大类健康度趋势失败:', error)
    return
  }
  if (!isCurrentReload(seq) || selectedCategory.value?.name !== categoryName) return

  const data = res.data?.data || []
  // 详情页大类趋势和总览大类行同一口径：顺手回填区间均分缓存，总览/简要得分/详情标题直接复用
  const intervalStats = averageHealthSamples(data)
  if (intervalStats) {
    categoryIntervalCache.set(categoryIntervalKey(categoryName), intervalStats)
    categoryIntervalStats.value = { ...categoryIntervalStats.value, [categoryName]: intervalStats }
  }
  // 根据时段自适应 label 格式
  const times = data.map(d => formatTimeLabel(d.recordTime, currentTimeRange.value))
  const scores = data.map(d => d.healthScore ?? null)
  const yRange = calculateYRange(scores, 0, 100)
  // 15d/30d 数据点较多，每隔 1 个显示避免重叠
  const xAxisInterval = ['7d', '30d'].includes(currentTimeRange.value)
    ? Math.max(1, Math.floor(times.length / 10))
    : (times.length > 15 ? Math.floor(times.length / 8) : 0)
  const catRotate = ['7d', '30d'].includes(currentTimeRange.value) ? 30 : 0
  const catBottom = ['7d', '30d'].includes(currentTimeRange.value) ? '15%' : '10%'

  const chartInstance = echarts.init(chartDom)
  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        const idx = p.dataIndex
        const fullTime = data[idx]?.recordTime || times[idx]
        return `${formatFullTimestamp(fullTime)}<br/>${p.marker} 健康度: ${p.value}`
      }
    },
    grid: { left: '6%', right: '4%', top: '8%', bottom: catBottom, containLabel: true },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: {
        fontSize: 10,
        interval: xAxisInterval,
        rotate: catRotate
      }
    },
    yAxis: { type: 'value', min: yRange.min, max: yRange.max, splitNumber: 3, axisLabel: { fontSize: 10, formatter: (val) => Math.round(val), hideOverlap: true } },
    series: [{
      name: '健康度',
      type: 'line',
      data: scores,
      smooth: true,
      symbolSize: 4,
      lineStyle: { width: 2 },
      areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: 'rgba(103,194,58,0.3)' },
        { offset: 1, color: 'rgba(103,194,58,0.05)' }
      ]) },
      itemStyle: { color: observationChartColor('success') }
    }]
  })

  // 绑定点击下钻
  chartInstance.on('click', (params) => {
    const drillInfo = getChartDrillInfo(currentTimeRange.value, data[params.dataIndex]?.recordTime, data)
    if (drillInfo) handleChartDrilldown(drillInfo)
  })
}

// 加载各小类指标数据（小类列表来自后端汇总，不再维护静态分类）
const loadSubcategoryMetrics = async (seq = observationReloadSeq) => {
  const categoryName = selectedCategory.value?.name
  if (!categoryName) return
  const subcategories = subcategoryList.value.map(sub => sub.name)

  for (const subName of subcategories) {
    try {
      const res = await observationApi.getSubcategoryMetrics({
        categoryName,
        subCategoryName: subName,
        timeRange: currentTimeRange.value,
        ...getObservationTimeWindow()
      })
      if (!isCurrentReload(seq)) return

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
          // 无数据时默认健康度为100
          sub.score = 100
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

  // 注意：不再根据子类健康度更新大类健康度，保持loadCategoryHealth的结果
  // 这样可以避免因子类数据为空导致大类分数被错误计算为100的问题
}

// 初始化小类健康度图表
const initSubcategoryCharts = async (seq = observationReloadSeq) => {
  for (const sub of subcategoryList.value) {
    const chartDom = document.getElementById('sub-health-chart-' + sub.code)
    if (!chartDom) continue

    const chart = echarts.getInstanceByDom(chartDom)
    if (chart) chart.dispose()

    try {
      // 自定义区间/聚焦窗口下按窗口长度选粒度，不被采集频率下限强行放大
      const effectiveTimeRange = resolveTrendTimeRange(sub.frequency)
      const categoryName = selectedCategory.value?.name
      if (!categoryName) return
      const res = await observationApi.getSubcategoryHealthTrend({
        categoryName,
        subCategoryName: sub.name,
        timeRange: effectiveTimeRange,
        ...getObservationTimeWindow()
      })
      if (!isCurrentReload(seq)) return

      const data = res.data?.data || []
      // 小类卡片分数同步改为该区间的趋势均分（与外层仪表盘/大类行同一口径）
      const intervalStats = averageHealthSamples(data)
      if (intervalStats) {
        sub.score = intervalStats.score
        sub.intervalSamples = intervalStats.samples
      }
      // 根据时段自适应 label 格式
      const times = data.map(d => formatTimeLabel(d.recordTime, effectiveTimeRange))
      const scores = data.map(d => d.healthScore ?? null)
      const yRange = calculateYRange(scores, 0, 100)

      const labelInterval = ['7d', '30d'].includes(effectiveTimeRange)
        ? Math.max(1, Math.floor(times.length / 10))
        : (times.length > 20 ? Math.floor(times.length / 10) : 0)
      const subRotate = ['7d', '30d'].includes(effectiveTimeRange) ? 30 : 0
      const subBottom = ['7d', '30d'].includes(effectiveTimeRange) ? '15%' : '12%'

      const chartInstance = echarts.init(chartDom)
      chartInstance.setOption({
        tooltip: {
          trigger: 'axis',
          formatter: (params) => {
            const p = params[0]
            const idx = p.dataIndex
            const fullTime = data[idx]?.recordTime || times[idx]
            return `${formatFullTimestamp(fullTime)}<br/>${p.marker} ${p.value}`
          }
        },
        grid: { left: 40, right: 8, top: 4, bottom: 20, containLabel: false },
        xAxis: {
          type: 'category',
          data: times,
          axisLabel: { fontSize: 9, interval: 'auto', rotate: 0 },
          axisLine: { onZero: false },
          axisTick: { show: false }
        },
        yAxis: {
          type: 'value',
          min: yRange.min,
          max: yRange.max,
          splitNumber: 3,
          axisLabel: { fontSize: 9, formatter: (val) => Math.round(val) },
          axisLine: { show: false },
          splitLine: { lineStyle: { type: 'dashed', color: observationChartColor('border-soft') } }
        },
        series: [{
          type: 'line',
          data: scores,
          smooth: true,
          symbolSize: 4,
          lineStyle: { width: 1.5 },
          itemStyle: { color: selectedSubCategory.value === sub.code ? observationChartColor('primary') : observationChartColor('success') },
          areaStyle: { opacity: 0.2 }
        }]
      })

      // 绑定点击下钻：下钻档位按图表实际粒度（effectiveTimeRange）计算
      chartInstance.on('click', (params) => {
        const raw = data[params.dataIndex]
        if (!raw) return
        const drillInfo = getChartDrillInfo(effectiveTimeRange, raw.recordTime, data)
        if (drillInfo) handleChartDrilldown(drillInfo)
      })
    } catch (error) {
      console.error(`加载${sub.code}健康度图表失败:`, error)
    }
  }
}

const initMetricCharts = async (metric, force = false, seq = observationReloadSeq) => {
  // 如果已经加载过且非强制刷新，则跳过
  if (!force && loadedChartMetrics.value.has(metric.metricId)) {
    return
  }

  let valueChartDom = document.getElementById('metric-value-chart-' + metric.metricId)
  let healthChartDom = document.getElementById('metric-health-chart-' + metric.metricId)

  // 如果DOM不存在，等待重试
  if (!valueChartDom || !healthChartDom) {
    await new Promise(resolve => setTimeout(resolve, 100))
    valueChartDom = document.getElementById('metric-value-chart-' + metric.metricId)
    healthChartDom = document.getElementById('metric-health-chart-' + metric.metricId)
  }

  if (!valueChartDom || !healthChartDom) return

  // 标记为已加载
  loadedChartMetrics.value.add(metric.metricId)
  unobserveMetricChart(metric.metricId)

  const valueChart = echarts.getInstanceByDom(valueChartDom)
  if (valueChart) valueChart.dispose()

  const healthChart = echarts.getInstanceByDom(healthChartDom)
  if (healthChart) healthChart.dispose()

  try {
    const [valueRes, healthRes, breakRes] = await Promise.all([
      observationApi.getMetricValueTrend({
        metricId: metric.metricId,
        timeRange: currentTimeRange.value,
        ...getObservationTimeWindow()
      }),
      observationApi.getMetricHealthTrend({
        metricId: metric.metricId,
        timeRange: currentTimeRange.value,
        ...getObservationTimeWindow()
      }),
      // 规则版本变化点：趋势图上标注「基线调整」，避免“昨天 90 今天 60”无法解释
      observationApi.getMetricRuleBreakpoints({ metricId: metric.metricId }).catch(() => ({ data: [] }))
    ])
    // 时间窗已切换：旧响应直接丢弃，避免旧窗口数据画到新图表上
    if (!isCurrentReload(seq)) return

    // 指标值图表 - 处理时间格式
    const valueData = valueRes.data?.data || []
    const valueTimes = valueData.map(d => formatTimeLabel(d.recordTime, currentTimeRange.value))
    const valueValues = valueData.map(d => d.metricValue ?? null)
    const valueYRange = calculateYRange(valueValues)
    // 15d/30d 数据点较多，每隔 1 个显示避免重叠
    const valueInterval = ['7d', '30d'].includes(currentTimeRange.value)
      ? Math.max(1, Math.floor(valueTimes.length / 10))
      : 0
    // 15d/30d 倾斜 30°
    const valueRotate = ['7d', '30d'].includes(currentTimeRange.value) ? 30 : 0

    const valueChartInstance = echarts.init(valueChartDom)
    valueChartInstance.setOption({
      title: { subtext: valueData.length ? valueRes.data?.notice || '' : '当前范围暂无指标值历史', left: 'center', bottom: 24, subtextStyle: { fontSize: 10, color: observationChartColor('muted') } },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0]
          const idx = p.dataIndex
          const fullTime = valueData[idx]?.recordTime || valueTimes[idx]
          return `${formatFullTimestamp(fullTime)}<br/>${p.marker} ${metric.metricName}: ${valueData[idx]?.metricValue ?? '缺数'} ${metric.unit || ''}`
        }
      },
      grid: { left: '6%', right: '4%', bottom: '15%', containLabel: true },
      dataZoom: [{ type: 'slider', start: 0, end: 100, height: 18, bottom: 2 }],
      xAxis: {
        type: 'category',
        data: valueTimes,
        axisLabel: {
          fontSize: 9,
          interval: valueInterval,
          rotate: valueRotate
        }
      },
      yAxis: { type: 'value', min: valueYRange.min, max: valueYRange.max, splitNumber: 3, axisLabel: { fontSize: 10, hideOverlap: true } },
      series: [{
        name: metric.metricName,
        type: 'line',
        data: valueValues,
        connectNulls: false,
        smooth: false,
        symbolSize: 4,
        lineStyle: { width: 2 },
        itemStyle: { color: observationChartColor('primary') }
      }]
    })

    // 绑定点击下钻（指标值图）
    valueChartInstance.on('click', (params) => {
      const raw = valueData[params.dataIndex]
      if (!raw) return
      const drillInfo = getChartDrillInfo(currentTimeRange.value, raw.recordTime, valueData)
      if (drillInfo) handleChartDrilldown(drillInfo)
    })

    // 健康度图表
    const healthData = healthRes.data?.data || []
    const healthTimes = healthData.map(d => formatTimeLabel(d.recordTime, currentTimeRange.value))
    const healthValues = healthData.map(d => d.healthScore ?? null)
    const healthYRange = calculateYRange(healthValues, 0, 100)
    // 15d/30d 数据点较多，每隔 1 个显示避免重叠
    const healthInterval = ['7d', '30d'].includes(currentTimeRange.value)
      ? Math.max(1, Math.floor(healthTimes.length / 10))
      : 0
    const healthRotate = ['7d', '30d'].includes(currentTimeRange.value) ? 30 : 0

    const ruleBreakpoints = (breakRes.data || [])
      .map(row => {
        const label = formatTimeLabel(row.time, currentTimeRange.value)
        return healthTimes.includes(label)
          ? { xAxis: label, label: { formatter: '基线调整', fontSize: 9, color: observationChartColor('warning') }, lineStyle: { color: observationChartColor('warning'), type: 'dashed' } }
          : null
      })
      .filter(Boolean)
      .slice(0, 6)

    const healthChartInstance = echarts.init(healthChartDom)
    healthChartInstance.setOption({
      title: { subtext: healthData.length ? '' : healthRes.data?.notice || '当前范围暂无健康度历史', left: 'center', bottom: 24, subtextStyle: { fontSize: 10, color: observationChartColor('muted') } },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0]
          const idx = p.dataIndex
          const fullTime = healthData[idx]?.recordTime || healthTimes[idx]
          return `${formatFullTimestamp(fullTime)}<br/>${p.marker} ${metric.metricName} 健康度: ${healthData[idx]?.healthScore ?? '未配置'}`
        }
      },
      grid: { left: '6%', right: '4%', bottom: '15%', containLabel: true },
      dataZoom: [{ type: 'slider', start: 0, end: 100, height: 18, bottom: 2 }],
      xAxis: {
        type: 'category',
        data: healthTimes,
        axisLabel: {
          fontSize: 9,
          interval: healthInterval,
          rotate: healthRotate
        }
      },
      yAxis: { type: 'value', min: healthYRange.min, max: healthYRange.max, splitNumber: 3, axisLabel: { fontSize: 10, hideOverlap: true } },
      series: [{
        name: metric.metricName,
        type: 'line',
        data: healthValues,
        connectNulls: false,
        smooth: false,
        symbolSize: 4,
        lineStyle: { width: 2 },
        itemStyle: { color: observationChartColor('warning') },
        markLine: ruleBreakpoints.length ? { silent: true, symbol: 'none', data: ruleBreakpoints } : undefined
      }]
    })

    // 绑定点击下钻（健康度图）
    healthChartInstance.on('click', (params) => {
      const raw = healthData[params.dataIndex]
      if (!raw) return
      const drillInfo = getChartDrillInfo(currentTimeRange.value, raw.recordTime, healthData)
      if (drillInfo) handleChartDrilldown(drillInfo)
    })
  } catch (error) {
    console.error('加载指标图表失败:', metric.metricName, error)
  }
}

const formatTimeLabel = (timeStr, range) => {
  if (!timeStr) return ''
  const t = String(timeStr)
  switch (range) {
    case '1h':  return t.slice(11, 16)                          // HH:mm
    case '6h':  return t.slice(11, 16)                          // HH:mm
    case '1d':  return t.slice(11, 16)                          // HH:mm
    case '7d':  return t.slice(5, 16).replace('T', ' ')         // MM-dd HH:mm
    case '30d': return t.slice(5, 10)                           // MM-dd
    default:    return t.slice(11, 16)
  }
}

const formatFullTimestamp = (timeStr) => {
  if (!timeStr) return ''
  const t = String(timeStr)
  // 兼容 '2026-06-16 14:00:00' 和 '2026-06-16T14:00:00'
  return t.replace('T', ' ').slice(0, 16)  // YYYY-MM-DD HH:mm
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
const toggleGroup = async (groupKey) => {
  const newState = !expandedGroups.value[groupKey]
  expandedGroups.value = { ...expandedGroups.value, [groupKey]: newState }

  // 如果展开分组，延迟后观察可见的指标元素进行懒加载
  if (newState) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))
    // 观察该分组下的所有未加载图表的指标
    const metricEls = document.querySelectorAll('.metric-chart-item[data-metric-id]')
    metricEls.forEach(el => {
      const metricId = el.dataset.metricId
      if (!loadedChartMetrics.value.has(parseInt(metricId))) {
        chartObserver?.observe(el)
      }
    })
  }
}

// 实时指标弹窗
const realtimeDialogVisible = ref(false)
const realtimeDialogTitle = ref('')
const realtimeDialogLoading = ref(false)
const realtimeTableData = ref([])
const realtimeNormalCount = computed(() => realtimeTableData.value.filter(r => !r.isOverThreshold).length)
const realtimeWarningCount = computed(() => realtimeTableData.value.filter(r => r.isOverThreshold).length)
// 系统异常-下钻响应（与 inner 的 realtimeTableData 结构不同，单独存）
const errorDrilldownResult = ref(null)  // { metricName, threshold, tableName, dataTime, rowCount, content, reason }
const errorDrilldownColumns = ref([])   // content 的字段名数组（动态列）
const isErrorDrilldown = ref(false)     // 当前弹窗是 error drilldown 模式吗

// 系统异常 / 前端异常 小类 → 走 errorDrilldownApi
const ERROR_SUBCATEGORY_NAMES = ['系统异常', '前端异常', '系统异常-接口', '系统异常-实例', '系统异常-JS', '系统异常-业务返回', '系统异常-返回码']
const ERROR_SUBCATEGORY_CODES = ['error', 'error_frontend']
const isErrorSubcategory = (subCategoryCode, subCategoryName) => {
  if (subCategoryCode && ERROR_SUBCATEGORY_CODES.includes(subCategoryCode)) return true
  if (subCategoryName && ERROR_SUBCATEGORY_NAMES.includes(subCategoryName)) return true
  if (subCategoryName && subCategoryName.includes('异常')) return true
  return false
}

/**
 * 共用的「加载 error 下钻数据」逻辑
 * 写入 errorDrilldownResult / errorDrilldownColumns / realtimeTableData，返回是否成功
 * 调用方负责切换 isErrorDrilldown 标志、设置标题
 */
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
  // 优先用后端派发：把 metricId 透传给 /api/data/detail，后端按 metric_id 派发到正确的表
  // 兜底保留原 tableName/stepTypes 字符串推断（兼容老调用）
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

// 初始化系统健康度趋势图（始终跟随当前时间口径：预设 / 自定义区间 / 下钻聚焦窗）
const loadSystemHealthTrendChart = async (seq = beginReload()) => {
  const chartDom = document.getElementById('system-health-trend-chart')
  if (!chartDom) return

  healthDataState.value = 'loading'
  const chart = echarts.getInstanceByDom(chartDom)
  if (chart) chart.dispose()

  try {
    // 获取系统健康度历史趋势（按大类权重加权计算）
    const res = await observationApi.getSystemHealthTrend({
      timeRange: currentTimeRange.value,
      systemId: selectedSystemId.value,
      ...getObservationTimeWindow()
    })
    // 旧请求晚到：直接丢弃，避免旧时间窗的数据覆盖新窗口
    if (!isCurrentReload(seq)) return

    const data = res.data?.data || []
    latestSystemRecordTime.value = data[data.length - 1]?.recordTime || ''
    healthDataState.value = data.some(item => item.healthScore != null && Number.isFinite(Number(item.healthScore))) ? 'ready' : 'empty'
    // 顶部仪表盘分数就是这份趋势的区间均分（复用本请求，不额外打接口）；
    // 无有效采样点时置空，由外层回退到实时汇总分
    systemIntervalStats.value = averageHealthSamples(data) || { score: null, samples: 0 }
    const samples = data.filter(item => item.healthScore != null && Number.isFinite(Number(item.healthScore))).slice(-2)
    healthSampleDelta.value = samples.length === 2 && samples[0].recordTime !== samples[1].recordTime
      ? Number((Number(samples[1].healthScore) - Number(samples[0].healthScore)).toFixed(2)) : null
    const times = data.map(d => formatTimeLabel(d.recordTime, currentTimeRange.value))
    const scores = data.map(d => d.healthScore ?? null)
    const yRange = calculateYRange(scores, 0, 100)
    // 15d/30d 数据点较多，每隔 1 个显示避免重叠
    const sysInterval = ['7d', '30d'].includes(currentTimeRange.value)
      ? Math.max(1, Math.floor(times.length / 10))
      : 0
    const sysRotate = ['7d', '30d'].includes(currentTimeRange.value) ? 30 : 0

    const chartInstance = echarts.init(chartDom)
    chartInstance.setOption({
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0]
          const idx = p.dataIndex
          const fullTime = data[idx]?.recordTime || times[idx]
          return `${formatFullTimestamp(fullTime)}<br/>${p.marker} 系统健康度: ${p.value}`
        }
      },
      grid: { left: 40, right: 8, top: 6, bottom: 22, containLabel: false },
      xAxis: {
        type: 'category',
        data: times,
        axisLabel: { fontSize: 9, interval: 'auto', rotate: 0 },
        axisLine: { onZero: false },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        min: yRange.min,
        max: yRange.max,
        splitNumber: 3,
        axisLabel: { fontSize: 9, formatter: (val) => Math.round(val) },
        axisLine: { show: false },
        splitLine: { lineStyle: { type: 'dashed', color: observationChartColor('border-soft') } }
      },
      series: [{
        type: 'line',
        data: scores,
        smooth: true,
        symbolSize: 4,
        lineStyle: { width: 2 },
        itemStyle: { color: observationChartColor('primary') },
        areaStyle: { opacity: 0.15 }
      }]
    })

    // 绑定点击下钻
    chartInstance.on('click', (params) => {
      const raw = data[params.dataIndex]
      if (!raw) return
      const drillInfo = getChartDrillInfo(currentTimeRange.value, raw.recordTime, data)
      if (drillInfo) handleChartDrilldown(drillInfo)
    })
  } catch (error) {
    if (isCurrentReload(seq)) {
      // 趋势不可用：清掉区间均分，外层回退实时汇总分
      systemIntervalStats.value = { score: null, samples: 0 }
      // 已有汇总分数时不要因为趋势接口失败就把整页标成失败
      healthDataState.value = systemHealthScore.value == null ? 'error' : 'ready'
      healthSampleDelta.value = null
    }
    console.error('加载系统健康度趋势图失败:', error)
  }
}

// 注意：currentTimeRange 的变化不再用 watch 触发加载。
// 预设切换、自定义区间、面包屑回退、下钻全部走 reloadForCurrentView() 单一入口，
// 用请求序号丢弃过期响应，避免以前 “watch + @change” 双触发拉两遍数据。

// ============ 数据流总览 + 关注问题（原 dashboard 页面） ============
const router = useRouter()

// 数据流总览统计（用 dashboardStats 避免与 observation 自身变量冲突）
const dashboardStats = ref({
  totalAlarmCount: 0,
  unprocessedCount: 0,
  gongdanCount: 0,
  issueCount: 0,
  fileCount: 0,
  tableCount: 0
})
const followedIssues = ref([])
const followedIssueTotal = ref(null)
const importCount = ref(0)
const metricCount = ref(0)
const categoryCount = ref(0)
const subCategoryCount = ref(0)
const issueUnresolvedCount = ref(0)
const gongdanOpenCount = ref(0)

const healthChartCount = computed(() => metricCount.value + 1 + categoryCount.value + subCategoryCount.value)

// 数据源配置
const dataSources = [
  { name: 'CRM', icon: 'User' },
  { name: 'BOSS', icon: 'Box' },
  { name: '稽核系统', icon: 'Monitor' },
  { name: '端到端监控', icon: 'DataLine' },
  { name: 'BOMC', icon: 'Operation' }
]

const handleSourceClick = (sourceName) => {
  router.push({ path: '/data', query: { source: sourceName } })
}

// 告警统计
const alarmProcessedCount = computed(() => {
  const total = dashboardStats.value.totalAlarmCount || 0
  const unprocessed = dashboardStats.value.unprocessedCount || 0
  return Math.max(0, total - unprocessed)
})
const alarmProcessRate = computed(() => {
  const total = dashboardStats.value.totalAlarmCount || 0
  if (total === 0) return 0
  return Math.max(0, Math.min(100, Math.round((alarmProcessedCount.value / total) * 100)))
})

// 问题统计
const issueResolvedCount = computed(() => {
  const total = dashboardStats.value.issueCount || 0
  const unresolved = issueUnresolvedCount.value || 0
  return Math.max(0, total - unresolved)
})
const issueResolveRate = computed(() => {
  const total = dashboardStats.value.issueCount || 0
  if (total === 0) return 0
  return Math.max(0, Math.min(100, Math.round((issueResolvedCount.value / total) * 100)))
})

// 工单统计
const gongdanCompletedCount = computed(() => {
  const total = dashboardStats.value.gongdanCount || 0
  const open = gongdanOpenCount.value || 0
  return Math.max(0, total - open)
})
const gongdanCompleteRate = computed(() => {
  const total = dashboardStats.value.gongdanCount || 0
  if (total === 0) return 0
  return Math.max(0, Math.min(100, Math.round((gongdanCompletedCount.value / total) * 100)))
})

const getDashboardRateClass = (rate) => {
  if (rate >= 80) return 'rate-excellent'
  if (rate >= 60) return 'rate-good'
  return 'rate-poor'
}

// 计算已登记时长（统一按天展示，不换算为月）
const calcElapsedTime = (registerTime) => {
  if (!registerTime) return '-'
  const now = new Date()
  const reg = new Date(registerTime)
  const diffMs = now - reg
  const diffHours = Math.floor(diffMs / 3600000)
  if (diffHours < 1) return Math.floor(diffMs / 60000) + '分钟'
  if (diffHours < 24) return diffHours + '小时'
  return Math.floor(diffHours / 24) + '天'
}

const loadDashboardStatistics = async () => {
  Object.assign(dashboardStats.value, {
    totalAlarmCount: 0, unprocessedCount: 0, gongdanCount: 0, issueCount: 0
  })
  issueUnresolvedCount.value = 0
  gongdanOpenCount.value = 0
  try {
    const [alarmRes, gongdanRes, issueRes] = await Promise.all([
      alarmApi.statistics(),
      gongdanApi.statistics(),
      issueApi.statistics()
    ])
    Object.assign(dashboardStats.value, {
      totalAlarmCount: alarmRes.data.totalAlarmCount ?? 0,
      unprocessedCount: alarmRes.data.unprocessedCount ?? 0,
      gongdanCount: gongdanRes.data.totalGongdanCount ?? 0,
      issueCount: issueRes.data.totalIssueCount ?? 0
    })
    issueUnresolvedCount.value = issueRes.data.unresolvedIssueCount || issueRes.data.totalIssueCount || 0
    gongdanOpenCount.value = gongdanRes.data.openGongdanCount || 0

    const issueRes2 = await issueApi.list({ page: 0, size: 10, resolveStatus: '未解决', tagStatus: 1 })
    followedIssues.value = issueRes2.data.content || []
    followedIssueTotal.value = issueRes2.data.totalElements ?? followedIssues.value.length
  } catch (error) {
    console.error('加载统计数据失败:', error)
  }
}

const loadDashboardFlowStatistics = async () => {
  dashboardStats.value.fileCount = 0
  dashboardStats.value.tableCount = 0
  importCount.value = 0
  metricCount.value = 0
  categoryCount.value = 0
  subCategoryCount.value = 0
  try {
    const importStatsRes = await originalDataImportApi.getStatistics()
    dashboardStats.value.fileCount = importStatsRes.data?.totalFileCount ?? importStatsRes.data?.todayFileCount ?? 0
    dashboardStats.value.tableCount = importStatsRes.data?.tableCount ?? 0
    importCount.value = importStatsRes.data?.todayRecordCount ?? 0

    const metricRes = await metricApi.statistics()
    metricCount.value = metricRes.data?.itemCount || 0
    categoryCount.value = metricRes.data?.categoryCount || 0
    subCategoryCount.value = metricRes.data?.subCategoryCount || 0
  } catch (error) {
    console.error('加载流程统计数据失败:', error)
  }
}

// 首屏之后再做：统计/关注问题不影响观测主视图，延后加载避免和健康度抢请求
const scheduleDeferred = (task, delay = 300) => {
  const run = () => {
    try { task() } catch (error) { console.error(error) }
  }
  if (typeof window !== 'undefined' && typeof window.requestIdleCallback === 'function') {
    window.requestIdleCallback(run, { timeout: 2000 })
  } else {
    setTimeout(run, delay)
  }
}

onMounted(async () => {
  ensureHealthThresholds()
  initChartObserver()
  initOverviewChartObserver()
  document.addEventListener('click', handleCustomTimeDocumentClick)
  // 先定系统（默认系统 id 可能不是 1），再并行拉汇总/权重/趋势，避免汇总按旧系统 id 白跑一次
  await loadSystemOptions()
  // 首屏只发三件事：系统健康度汇总（含白名单）、权重配置、系统趋势图；
  // 小类趋势全部交给 IntersectionObserver 进入视口再拉，统计/关注问题延后。
  await Promise.all([
    loadHealthSummary(),
    loadWeightConfig(),
    loadSystemHealthTrendChart(beginReload())
  ])
  await nextTick()
  observeOverviewCharts()
  // 汇总之后异步补“大类区间均分”：最多 8 个并行请求，不阻塞首屏骨架
  loadCategoryIntervalAverages(observationReloadSeq)
  scheduleDeferred(() => {
    loadDashboardStatistics()
    loadDashboardFlowStatistics()
  })
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleCustomTimeDocumentClick)
  chartObserver?.disconnect()
  overviewChartObserver?.disconnect()
})

// ============ 下钻功能 ============

// 后端时间字符串统一成 'YYYY-MM-DD HH:mm:ss'（兼容 T 分隔、带毫秒）
const normalizeTimeText = (value) => {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 19 ? text.slice(0, 19) : text
}
const pad2 = (value) => String(value).padStart(2, '0')
const formatLocalDate = (date) => `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
// 用本地零点解析日期：new Date('YYYY-MM-DD') 会按 UTC 解析，跨时区会偏一天
const parseLocalDate = (dateText) => {
  const [year, month, day] = dateText.slice(0, 10).split('-').map(Number)
  return new Date(year, (month || 1) - 1, day || 1)
}
const shiftLocalDate = (dateText, days) => {
  const date = parseLocalDate(dateText)
  date.setDate(date.getDate() + days)
  return formatLocalDate(date)
}

// 下钻路径：30d → 7d → 1d → 6h → 1h → 5m（5m 打开明细弹窗，不再切换图表粒度）
const DRILLDOWN_PATH = { '30d': '7d', '7d': '1d', '1d': '6h', '6h': '1h', '1h': '5m' }

const resetDrilldownState = () => {
  drilldownBreadcrumbs.value = []
  drilldownStartTime.value = null
  drilldownEndTime.value = null
}

// 面包屑聚焦窗文案：明确展示当前真正取数的绝对时间窗，避免只看“1天/1小时”误以为是最近 N 小时
const focusRangeText = computed(() => {
  if (hasFocusWindow.value) {
    return `${formatFullTimestamp(drilldownStartTime.value)} ~ ${formatFullTimestamp(drilldownEndTime.value)}`
  }
  if (customTimeRange.value) return `${customTimeRange.value[0]} ~ ${customTimeRange.value[1]}`
  return ''
})

// 根据当前粒度和点击的数据点，计算该点覆盖的时段
const getChartDrillInfo = (currentRange, pointTime, rawData) => {
  const toTimeRange = DRILLDOWN_PATH[currentRange]
  if (!toTimeRange) return null

  // 图表 x 轴是格式化后的短标签，必须回到 rawData 找原始 recordTime 再算时间窗
  const clicked = normalizeTimeText(pointTime)
  const rawItem = rawData?.find(item => normalizeTimeText(item.recordTime) === clicked)
    || rawData?.find(item => normalizeTimeText(item.recordTime).slice(0, 16) === clicked.slice(0, 16))
  const fullTime = normalizeTimeText(rawItem?.recordTime || clicked)
  if (fullTime.length < 16) return null

  const datePart = fullTime.slice(0, 10)
  const hour = Number(fullTime.slice(11, 13)) || 0
  let startTime = null
  let endTime = null
  switch (toTimeRange) {
    case '7d': {
      // 30d 粒度点的是某天：聚焦该天所在的自然周（周一~周日）
      const day = parseLocalDate(datePart).getDay() || 7 // 周日=7
      const monday = shiftLocalDate(datePart, -day + 1)
      const sunday = shiftLocalDate(monday, 6)
      startTime = `${monday} 00:00:00`
      endTime = `${sunday} 23:59:59`
      break
    }
    case '1d':
      // 7d 粒度点的是某天：聚焦该天
      startTime = `${datePart} 00:00:00`
      endTime = `${datePart} 23:59:59`
      break
    case '6h': {
      // 1d 粒度点的是某小时：聚焦该小时所在的 6 小时块
      const block = Math.floor(hour / 6) * 6
      startTime = `${datePart} ${pad2(block)}:00:00`
      endTime = `${datePart} ${pad2(Math.min(block + 5, 23))}:59:59`
      break
    }
    case '1h':
    case '5m':
      // 6h 粒度点的是某小时：聚焦该小时；1h 粒度点的是 5 分钟桶 → 打开明细弹窗
      startTime = `${datePart} ${pad2(hour)}:00:00`
      endTime = `${datePart} ${pad2(hour)}:59:59`
      break
  }

  return { fromTimeRange: currentRange, toTimeRange, pointTime: fullTime, startTime, endTime }
}

// 面包屑标签：直接写聚焦窗，比“1天/6小时”更能说明当前看的是哪段时间
const getBreadcrumbLabel = (timeRange, startTime, endTime) => {
  if (timeRange === '7d' || timeRange === '30d') return `${startTime.slice(5, 10)} ~ ${endTime.slice(5, 10)}`
  if (timeRange === '1d' || timeRange === '6h') return `${startTime.slice(5, 10)} ${startTime.slice(11, 16)} ~ ${endTime.slice(11, 16)}`
  return `${startTime.slice(11, 16)} ~ ${endTime.slice(11, 16)}`
}

// 进入下钻前先记下顶层时间口径（预设 / 自定义区间），面包屑第一级用它逐级回退
const buildRootBreadcrumb = (fromTimeRange) => {
  if (customTimeRange.value) {
    return {
      label: '自定义区间',
      timeRange: fromTimeRange,
      startTime: customTimeRange.value[0],
      endTime: customTimeRange.value[1],
      custom: true
    }
  }
  const labels = { '30d': '30天', '7d': '7天', '1d': '1天', '6h': '6小时', '1h': '1小时' }
  return { label: labels[fromTimeRange] || fromTimeRange, timeRange: fromTimeRange }
}

// 处理图表点击下钻：算出聚焦窗 → 清空自定义区间 → 用聚焦窗真正重新取数
const handleChartDrilldown = (drillInfo) => {
  const { fromTimeRange, toTimeRange, pointTime, startTime, endTime } = drillInfo
  if (!startTime || !endTime) return

  // 末级 1 小时点 → 打开现有 5 分钟明细弹窗（不切换图表粒度）
  if (toTimeRange === '5m') {
    openDrillDetail(pointTime, startTime, endTime)
    return
  }

  if (drilldownBreadcrumbs.value.length === 0) {
    drilldownBreadcrumbs.value = [buildRootBreadcrumb(fromTimeRange)]
  }
  drilldownBreadcrumbs.value.push({
    label: getBreadcrumbLabel(toTimeRange, startTime, endTime),
    timeRange: toTimeRange,
    startTime,
    endTime,
    focusLabel: `${formatFullTimestamp(startTime)} ~ ${formatFullTimestamp(endTime)}`
  })

  // 聚焦窗优先于自定义区间：旧自定义窗口必须清掉，否则会继续盖住聚焦窗
  customTimeRange.value = null
  drilldownStartTime.value = startTime
  drilldownEndTime.value = endTime
  currentTimeRange.value = toTimeRange
  reloadForCurrentView()
}

// 面包屑导航：把时间口径恢复到该级别并重新取数
const applyBreadcrumbContext = (item) => {
  if (item.custom && item.startTime && item.endTime) {
    // 第一级是自定义区间：恢复它，并把聚焦窗清掉
    customTimeRange.value = [item.startTime, item.endTime]
    drilldownStartTime.value = null
    drilldownEndTime.value = null
  } else if (item.startTime && item.endTime) {
    // 中间级是聚焦窗：恢复聚焦窗
    customTimeRange.value = null
    drilldownStartTime.value = item.startTime
    drilldownEndTime.value = item.endTime
  } else {
    // 第一级是预设：回到顶层口径
    customTimeRange.value = null
    drilldownStartTime.value = null
    drilldownEndTime.value = null
  }
  currentTimeRange.value = item.timeRange
  reloadForCurrentView()
}

const handleBreadcrumbNavigate = (item) => {
  // 截断面包屑到点击的位置，之后的层级作废
  drilldownBreadcrumbs.value = drilldownBreadcrumbs.value.slice(0, item.index + 1)
  applyBreadcrumbContext(item)
}

// 重置下钻：回到第一级（顶层时间口径）
const handleDrilldownReset = () => {
  const root = drilldownBreadcrumbs.value[0]
  if (root && drilldownBreadcrumbs.value.length > 1) {
    drilldownBreadcrumbs.value = [root]
    applyBreadcrumbContext(root)
    return
  }
  resetDrilldownState()
  reloadForCurrentView()
}

// 打开5分钟明细弹窗
const openDrillDetail = async (pointTime, startTime, endTime) => {
  drillDetailVisible.value = true
  drillDetailLoading.value = true
  drillDetailData.value = []
  drillDetailTimeRange.value = `${startTime || ''} - ${endTime || ''}`

  try {
    // 调用5分钟明细接口
    const res = await observationApi.getMetricHealthDetail({
      startTime: startTime,
      endTime: endTime
    })
    drillDetailData.value = res.data?.data || []
  } catch (error) {
    console.error('加载5分钟明细失败:', error)
  } finally {
    drillDetailLoading.value = false
  }
}
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
  color: var(--ui-heading);
  margin-bottom: 8px;
}

.section-hint {
  margin-left: auto;
  font-size: 11px;
  font-weight: normal;
  color: var(--ui-muted);
}

/* 汇总未返回时的骨架：避免先渲染硬编码大类再被白名单过滤，页面不会闪一下 */
.overview-skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.overview-skeleton-row {
  display: flex;
  gap: 10px;
}

.skeleton-card {
  flex: 1;
  height: 110px;
  border-radius: 6px;
  background: linear-gradient(90deg, #f2f4f7 25%, #e9edf3 37%, #f2f4f7 63%);
  background-size: 400% 100%;
  animation: skeleton-shimmer 1.4s ease infinite;
}

.skeleton-parent {
  flex: 0 0 85px;
}

@keyframes skeleton-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* 自定义区间弹层：日期面板是绝对定位、不占文档流，展开时给面板预留高度，
   让“取消/确定”落在日历下方，不会被日历盖住点不到 */
.custom-time-panel-body.is-calendar-open .custom-time-actions {
  margin-top: 340px;
}

.custom-time-panel-body.is-calendar-open .custom-time-hint {
  visibility: hidden;
}

.total-score {
  flex-shrink: 0;
  width: 90px;
  padding: 8px;
  border-radius: 6px;
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 4px;
  position: relative;
}

.score-excellent {
  background: linear-gradient(135deg, #e1f3d8 0%, #f0f9eb 100%);
  color: var(--ui-success);
}

.score-good {
  background: linear-gradient(135deg, #fdf6ec 0%, #fef0e6 100%);
  color: var(--ui-warning);
}

.score-poor {
  background: linear-gradient(135deg, #fef0f0 0%, #ffe1e1 100%);
  color: var(--ui-danger);
}

.total-score:not(.score-excellent):not(.score-good):not(.score-poor) {
  background: #f5f5f5;
  color: var(--ui-muted);
}

.score-value {
  font-size: 28px;
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

.overview-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.overview-right-top {
  margin-bottom: 8px;
}

.overview-right-bottom {
  flex: 1;
  min-height: 0;
}

/* 详情页面 */
.category-detail-section {
  background: var(--ui-surface);
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
  color: var(--ui-heading);
}

/* 详情页大类分数：与总览/仪表盘统一的区间均分口径 */
.detail-score-note {
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--ui-primary-soft, #ecf5ff);
  color: var(--ui-primary, #3b82f6);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.chart-section {
  margin-bottom: 16px;
}

.chart-title {
  font-size: 12px;
  color: var(--ui-text);
  margin-bottom: 2px;
  font-weight: 500;
}

.chart-container {
  height: 180px;
  background: #fafafa;
  border: 1px solid var(--ui-border-soft);
  border-radius: 4px;
}

.chart-container.category-chart {
  height: 200px;
}

/* 小类健康度区域 */
.subcategory-section {
  margin-bottom: 16px;
}

/* 汇总视图（默认展示） */
.subcategory-overview-section {
  background: var(--ui-surface);
  border-radius: 6px;
  padding: 10px;
  margin-top: 10px;
}

.subcategory-overview-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.overview-parent-row {
  display: flex;
  gap: 10px;
  align-items: stretch;
}

.overview-parent-info {
  flex-shrink: 0;
  width: 85px;
  background: var(--ui-page);
  border-radius: 6px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  border-left: 3px solid var(--ui-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.overview-parent-info:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.overview-parent-info.score-excellent { border-left-color: var(--ui-success); }
.overview-parent-info.score-good { border-left-color: var(--ui-warning); }
.overview-parent-info.score-poor { border-left-color: var(--ui-danger); }

.overview-parent-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-heading);
  text-align: center;
}

.overview-parent-score {
  font-size: 18px;
  font-weight: bold;
  color: var(--ui-heading);
  text-align: center;
}

.overview-parent-score.score-excellent { color: var(--ui-success); }
.overview-parent-score.score-good { color: var(--ui-warning); }
.overview-parent-score.score-poor { color: var(--ui-danger); }

.overview-parent-stat {
  font-size: 11px;
  color: var(--ui-muted);
  text-align: center;
  white-space: nowrap;
}

.stat-sep {
  margin: 0 2px;
  color: var(--ui-border);
}

.overview-parent-stat .abnormal {
  color: var(--ui-danger);
}

.overview-parent-stats .abnormal {
  color: var(--ui-danger);
}

.overview-subcategories {
  display: flex;
  flex: 1;
  gap: 10px;
}

.overview-subcategory-card {
  flex: 1;
  background: #fafafa;
  border: 1px solid var(--ui-border-soft);
  border-radius: 6px;
  padding: 8px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
}

.overview-subcategory-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  border-color: var(--ui-primary);
}

.overview-subcategory-card.score-excellent {
  border-left: 3px solid var(--ui-success);
}

.overview-subcategory-card.score-good {
  border-left: 3px solid var(--ui-warning);
}

.overview-subcategory-card.score-poor {
  border-left: 3px solid var(--ui-danger);
}

.overview-subcategory-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 4px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--ui-border-soft);
}

.overview-subcategory-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-heading);
}

.overview-subcategory-score {
  font-size: 16px;
  font-weight: bold;
  color: var(--ui-heading);
  line-height: 1;
}

.overview-subcategory-score.score-excellent { color: var(--ui-success); }
.overview-subcategory-score.score-good { color: var(--ui-warning); }
.overview-subcategory-score.score-poor { color: var(--ui-danger); }

.overview-subcategory-meta {
  font-size: 11px;
  color: var(--ui-muted);
  font-weight: normal;
}

.overview-subcategory-chart {
  height: 90px;
  min-height: 0;
}

/* 5分钟明细弹窗 */
.drill-detail-header {
  margin-bottom: 12px;
}

.drill-detail-time {
  font-size: 13px;
  color: var(--ui-text);
  font-weight: 500;
}

@media (max-width: 1200px) {
  .overview-subcategories {
    flex-wrap: wrap;
  }
  .overview-subcategory-card {
    min-width: calc(50% - 5px);
  }
}

@media (max-width: 768px) {
  .overview-parent-info {
    width: 70px;
  }
  .overview-subcategory-card {
    min-width: 100%;
  }
}

.section-subtitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ui-text);
  margin-bottom: 8px;
}

.hint-text {
  color: var(--ui-muted);
  font-size: 11px;
}

.subcategory-health-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.subcategory-health-card {
  background: #fafafa;
  border: 2px solid var(--ui-border-soft);
  border-radius: 6px;
  padding: 10px;
  cursor: pointer;
  transition: all 0.2s;
}

.subcategory-health-card:hover {
  border-color: var(--ui-subtle);
}

.subcategory-health-card.active {
  border-color: var(--ui-primary);
  background: var(--ui-surface);
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
  color: var(--ui-heading);
}

.subcategory-score {
  font-size: 20px;
  font-weight: bold;
}

.subcategory-score.score-excellent { color: var(--ui-success); }
.subcategory-score.score-good { color: var(--ui-warning); }
.subcategory-score.score-poor { color: var(--ui-danger); }

.subcategory-chart {
  height: 130px;
}

/* 指标分组 */
.metrics-section {
  margin-top: 16px;
}

.metric-group {
  margin-bottom: 16px;
  border: 1px solid var(--ui-border-soft);
  border-radius: 6px;
  overflow: hidden;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--ui-page);
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  color: var(--ui-heading);
}

.group-title:hover {
  background: var(--ui-border-soft);
}

.group-count {
  color: var(--ui-muted);
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
  border: 1px solid var(--ui-border);
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
  color: var(--ui-text);
}

.sub-group-title:hover {
  background: #f0f0f0;
}

.sub-group-content {
  padding: 8px;
}

.no-data-tip {
  text-align: center;
  color: var(--ui-muted);
  padding: 20px;
}

.metric-chart-item {
  margin-bottom: 12px;
  border: 1px solid var(--ui-border-soft);
  border-radius: 4px;
  padding: 8px;
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
  color: var(--ui-primary);
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
  color: var(--ui-success);
}
.stat-warning {
  background: linear-gradient(135deg, #fdf6ec 0%, #fef0e6 100%);
  color: var(--ui-warning);
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
  color: var(--ui-danger);
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
  color: var(--ui-text);
}

/* 健康度计算依据弹窗样式 */

/* ============ 数据流总览 + 关注问题（原 dashboard 样式） ============ */

/* 数据流示意图 */

/* 流程容器 - 横向等高布局 */

/* 响应式布局 - 小屏幕 */

/* 流程面板 */

/* 入库统计卡片 */

/* 箭头单元格 */

/* 数据流动画箭头 */

@keyframes flowDot {
  0% { left: 5px; opacity: 0; box-shadow: 0 0 4px var(--ui-primary); }
  20% { opacity: 1; box-shadow: 0 0 12px var(--ui-primary); }
  80% { opacity: 1; box-shadow: 0 0 12px var(--ui-primary); }
  100% { left: calc(100% - 13px); opacity: 0; box-shadow: 0 0 4px var(--ui-primary); }
}

/* 箭头脉冲动画 - 交错效果 */

/* 流程卡片 */

/* 垂直连接线 */

/* 垂直数据流动画箭头 */

@keyframes flowDotV {
  0% { top: 5px; opacity: 0; box-shadow: 0 0 4px var(--ui-primary); }
  20% { opacity: 1; box-shadow: 0 0 12px var(--ui-primary); }
  80% { opacity: 1; box-shadow: 0 0 12px var(--ui-primary); }
  100% { top: calc(100% - 13px); opacity: 0; box-shadow: 0 0 4px var(--ui-primary); }
}

/* 计算卡片 */

/* 处理结果统计卡片 */

/* 健康度卡片 */

/* 处理率颜色 */

/* 告警/问题/工单卡片左边框 */
</style>

<!-- 弹窗拖拽手柄样式（全局，JS 动态创建的元素无法用 scoped） -->
<style>
.dialog-resize-handle {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 24px;
  height: 24px;
  cursor: nwse-resize;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  font-size: 16px;
  color: var(--ui-subtle);
  background: linear-gradient(135deg, transparent 50%, var(--ui-border) 50%);
  border-radius: 0 0 6px 0;
  transition: color 0.2s;
}
.dialog-resize-handle:hover {
  color: var(--ui-primary);
  background: linear-gradient(135deg, transparent 50%, var(--ui-primary-border) 50%);
}
</style>

<style scoped src="@/views/observation/prototype-theme.css"></style>
