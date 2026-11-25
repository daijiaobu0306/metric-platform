<!--
  ============================================================================
  告警管理页
  ============================================================================
  【策略表单已抽为公共组件（2026-10-28）】
  「告警策略」子页的策略新增/编辑/复制弹窗，不再在本文件内联实现，而是引用：

      frontend/src/components/alarm/StrategyEditorDialog.vue

  该组件同时被「指标体系配置」页的「更多 → 设置策略阈值」使用，是策略表单的
  唯一实现。⚠️ 后续给策略增加字段（例如告警等级：重要 / 一般）请只改该组件，
  本页与指标页同时生效。

  本文件只负责：
    ① 打开弹窗时准备 initial 预填数据（handleStrategyAdd / Edit / Copy）
    ② 保存成功后刷新策略列表（onStrategySaved）
    ③ 策略**列表**的列展示（这部分不在组件内，加列需在此处改）

  详细约定见：doc/告警策略表单复用说明.md
  ============================================================================
-->
<template>
  <div class="alarm-page workspace-theme">
    <WorkspacePageHeader title="告警管理" description="统一查看告警态势、处理异常事件，管理告警策略、屏蔽规则与通知通道。" badge="告警与策略管理" />
    <el-tabs v-model="activeTab" class="workspace-tabs alarm-workspace-tabs">
      <!-- 告警列表 -->
      <el-tab-pane label="告警列表" name="alarm"><template #label><WorkspaceTabLabel icon="Bell">告警列表</WorkspaceTabLabel></template>
        <!-- 扫描异常提示 -->
        <el-alert
          v-if="scanErrors.length > 0 && showScanErrorPanel"
          type="error"
          :closable="true"
          @close="showScanErrorPanel = false"
          style="margin-bottom: 12px;"
        >
          <template #title>
            <span style="font-weight: bold;">⚠ 告警扫描异常 — {{ scanErrors.length }} 个策略执行出错</span>
          </template>
          <template #default>
            <div v-for="(err, idx) in scanErrors" :key="idx" style="margin-bottom: 4px; font-size: 13px;">
              <el-tag size="small" type="danger" style="margin-right: 8px;">{{ err.phase }}</el-tag>
              <span>{{ err.message }}</span>
            </div>
          </template>
        </el-alert>
        <div class="alarm-list-workspace">
          <WorkspaceOverviewLayout>
            <template #stats><AlarmOverviewCards :stats="channelStats" @time-range="filterAlarmByTimeRange" @process-status="filterAlarmByProcessStatus" /></template>
            <template #trend>
          <WorkspacePanel icon="TrendCharts" title="告警触发趋势" description="按时间查看告警触发数量，点击图表可筛选对应时段。">

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
                      <el-option label="未处理" value="unprocessed" />
                      <el-option label="已处理" value="processed" />
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
          <AlarmFilters v-model:filters="queryParams" :system-list="systemList" :category-list="categoryList" :alarm-type-options="alarmTypeOptions" @system-change="onSystemChange" @category-change="onAlarmCategoryChange" @search="loadAlarmData" @reset="resetAlarmFilters" />

          <section ref="quickFilterResult" class="alarm-table-panel">
        <div v-if="quickFilterLabel" class="quick-filter-feedback" role="status"><span>快捷筛选：<strong>{{ quickFilterLabel }}</strong></span><el-button link type="primary" @click="resetAlarmFilters">清除筛选</el-button></div>
          <div class="summary-caption">
            <span>按告警对象与类型汇总</span><strong>{{ pagination.total }} 组</strong><span>·</span><span>当前筛选匹配 {{ summaryTotalEvents }} 次触发</span>
            <small>点击次数查看每一次发生记录；勾选后的处理、删除作用于整组匹配记录。</small>
          </div>
          <!-- 桌面端表格 -->
          <div class="desktop-table">
            <!-- 表格工具栏 -->
            <div class="table-toolbar">
              <div class="toolbar-left">
                <el-button type="danger" size="small" :disabled="selectedRows.length === 0" @click="handleBatchDelete">
                  <el-icon><Delete /></el-icon>删除
                </el-button>
                <el-button type="warning" size="small" :disabled="selectedRows.length === 0" @click="handleBatchProcess">
                  {{ batchProcessBtnText }}
                </el-button>
              </div>
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
            <el-table
          v-sticky-header
          v-mirror-scroll
              :data="alarmList"
              row-key="groupKey"
              v-loading="tableLoading"
              border
              :fit="true"
              style="width: 100%;"
              :row-class-name="getRowClassName"
              @sort-change="handleSortChange"
              @selection-change="handleSelectionChange"
              @header-contextmenu="handleHeaderContextMenu"
            ><template #empty><WorkspaceEmptyState title="暂无匹配的告警" description="可调整告警对象、处理状态或时间范围。" /></template>
              <el-table-column type="selection" width="40" fixed="left"/>
              <el-table-column v-if="!isColumnHidden('alarmTime')" prop="alarmTime" label="最近发生" width="158" sortable="custom" class-name="workspace-time-cell">
                <template #default="{ row }"><el-tooltip :content="`首次发生：${formatDateTime(row.firstAlarmTime)}`"><span>{{ formatDateTime(row.lastAlarmTime) }}</span></el-tooltip></template>
              </el-table-column>
              <el-table-column v-if="!isColumnHidden('alarmObject')" label="告警对象" min-width="190" class-name="wrap-cell">
                <template #default="{ row }">
                  <el-tooltip placement="top" :show-after="300">
                    <template #content>
                      <div style="max-width: 400px; line-height: 1.8;">
                        <div>系统: {{ row.systemName || '-' }}</div>
                        <div>指标大类: {{ row.categoryName || '-' }}</div>
                        <div>指标小类: {{ row.subCategoryName || '-' }}</div>
                        <div>指标: {{ row.metricName || '-' }}</div>
                      </div>
                    </template>
                    <span>{{ getAlarmObjectDisplay(row) }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column v-if="!isColumnHidden('systemName')" prop="systemName" label="告警系统" min-width="130" class-name="wrap-cell"><template #default="{ row }">{{ row.systemName || '未记录' }}</template></el-table-column>
              <el-table-column v-if="!isColumnHidden('categoryName')" prop="categoryName" label="指标大类" min-width="140" class-name="wrap-cell"><template #default="{ row }">{{ row.categoryName || '未记录' }}</template></el-table-column>
              <el-table-column v-if="!isColumnHidden('alarmType')" prop="alarmType" label="类型" width="120" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('alarmCount')" prop="alarmCount" label="告警次数" width="100" sortable="custom">
                <template #default="{ row }"><el-button link type="primary" class="occurrence-count" @click="openOccurrences(row)">{{ row.alarmCount }}<span>次 ›</span></el-button></template>
              </el-table-column>
              <el-table-column v-if="!isColumnHidden('alarmLevel')" label="最近级别" width="85">
                <template #default="{ row }">
                  <el-tag v-if="row.alarmLevel" size="small" :type="ruleLevels.find(level => level.value === row.alarmLevel)?.type">
                    {{ ruleLevels.find(level => level.value === row.alarmLevel)?.label || '未定级' }}
                  </el-tag><span v-else>未定级</span>
                </template>
              </el-table-column>
              <el-table-column v-if="!isColumnHidden('currentValue')" label="最近数值" min-width="110" class-name="wrap-cell">
                <template #default="{ row }">
                  {{ alarmValueText(row) }}
                </template>
              </el-table-column>
              <el-table-column v-if="!isColumnHidden('thresholdValue')" label="告警阈值" min-width="190">
                <template #header><el-tooltip content="当前策略配置；每次触发时的阈值请查看告警次数明细"><span>告警阈值</span></el-tooltip></template>
                <template #default="{ row }"><el-tooltip v-if="row.strategyType === 'raw' && !row.targetId" content="原始数据记录的发生时阈值；规则请在原始数据告警配置中修改"><span>{{ row.thresholdValue ?? '未记录' }} {{ row.unit || '' }} <small style="color: #909399;">历史值</small></span></el-tooltip><StrategyThresholdCell :single-record="row.strategyType === 'raw'" v-else :rules="row.alarmRules" :unit="row.thresholdUnit || ''" :editable="!!row.targetId" @edit="id => handleThreshold(row, id)" /></template>
              </el-table-column>
              <el-table-column v-if="!isColumnHidden('gongdanThreshold')" label="工单阈值" min-width="190">
                <template #default="{ row }"><StrategyThresholdCell :single-record="row.strategyType === 'raw'" :rules="row.gongdanRules" :unit="row.thresholdUnit || ''" :editable="!!row.targetId" @edit="id => handleThreshold(row, id)" /></template>
              </el-table-column>
                            <el-table-column v-if="!isColumnHidden('alarmContent')" prop="alarmContent" label="最近告警内容" min-width="210" class-name="wrap-cell">
                              <template #default="{ row }">
                                <el-tooltip :content="stripNumberTrailingZeros(row.alarmContent)" placement="top" :show-after="400"><span class="latest-content">{{ stripNumberTrailingZeros(row.alarmContent) }}</span></el-tooltip>
                              </template>
                            </el-table-column>
              <el-table-column v-if="!isColumnHidden('processStatus')" prop="processStatus" label="处理概况" min-width="120" class-name="wrap-cell">
                <template #default="{ row }">
                  <WorkspaceStatusBadge :tone="row.processStatus === 0 ? 'danger' : 'success'" :pulse="row.processStatus === 0">
                    {{ processLabel(row) }}
                  </WorkspaceStatusBadge>
                </template>
              </el-table-column>
              <el-table-column label="组操作" width="135" fixed="right">
                <template #default="{ row }">
                  <div class="group-actions">
                    <el-button link :type="row.shieldStatus === 1 ? 'warning' : 'info'" size="small" @click="handleShield(row)">{{ shieldLabel(row) }}</el-button>
                    <el-dropdown trigger="click" @command="command => handleGroupCommand(command, row)">
                      <el-button link type="info" size="small">更多 ▾</el-button>
                      <template #dropdown><el-dropdown-menu>
                        <el-dropdown-item command="details">查看明细</el-dropdown-item>
                        <el-dropdown-item command="issue">转问题</el-dropdown-item>
                        <el-dropdown-item command="gongdan">转工单</el-dropdown-item>
                        <el-dropdown-item v-if="row.unprocessedCount > 0" command="process">处理本组未处理告警</el-dropdown-item>
                      </el-dropdown-menu></template>
                    </el-dropdown>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 移动端卡片 -->
          <div class="mobile-cards">
            <el-card v-for="row in alarmList" :key="row.groupKey" class="alarm-card" shadow="hover">
              <div class="card-content">
                <div class="card-row">
                  <WorkspaceStatusBadge :tone="row.processStatus === 0 ? 'danger' : 'success'" :pulse="row.processStatus === 0">
                    {{ processLabel(row) }}
                  </WorkspaceStatusBadge>
                  <span class="time">{{ formatDateTime(row.alarmTime) }}</span>
                </div>
                <div class="card-row">
                  <span class="label">告警类型：</span>
                  <span class="value">{{ row.alarmType }} · {{ ruleLevels.find(level => level.value === row.alarmLevel)?.label || '未定级' }}</span>
                </div>
                <div class="card-row">
                  <span class="label">告警系统：</span><span class="value">{{ row.systemName || '未记录' }}</span>
                </div>
                <div class="card-row"><span class="label">指标大类：</span><span class="value">{{ row.categoryName || '未记录' }}</span></div>
                <div class="card-row">
                  <span class="label">最近数值：</span><span class="value">{{ alarmValueText(row) }}</span>
                  <el-button link type="primary" @click="openOccurrences(row)">{{ row.alarmCount }} 次触发 ›</el-button>
                </div>
                <div class="card-row">
                  <span class="label">告警阈值：</span><span v-if="row.strategyType === 'raw' && !row.targetId">{{ row.thresholdValue ?? '未记录' }} {{ row.unit || '' }}（历史值）</span><StrategyThresholdCell :single-record="row.strategyType === 'raw'" v-else :rules="row.alarmRules" :unit="row.thresholdUnit || ''" :editable="!!row.targetId" @edit="id => handleThreshold(row, id)" />
                </div>
                <div class="card-row"><span class="label">工单阈值：</span><StrategyThresholdCell :single-record="row.strategyType === 'raw'" :rules="row.gongdanRules" :unit="row.thresholdUnit || ''" :editable="!!row.targetId" @edit="id => handleThreshold(row, id)" />
                </div>
                <div class="card-desc">
                  <span class="label">告警内容：</span>
                  <p>{{ stripNumberTrailingZeros(row.alarmContent) }}</p>
                </div>
              </div>
              <div class="card-footer">
                <el-button type="primary" size="small" @click="handleProcess(row)" v-if="row.processStatus === 0">处理</el-button>
                <el-button type="warning" size="small" @click="handleShield(row)">屏蔽</el-button>
                <el-button type="primary" size="small" @click="openOccurrences(row)">查看明细</el-button>
                <el-button size="small" @click="openConversion(row, 'issue')">转问题</el-button>
                <el-button size="small" @click="openConversion(row, 'gongdan')">转工单</el-button>
              </div>
            </el-card>
            <el-empty v-if="alarmList.length === 0" description="暂无数据" />
          </div>

          <WorkspacePagination v-model:page="pagination.page" v-model:size="pagination.size" :total="pagination.total" :page-sizes="[10, 20, 50, 100, 200, 500]" @change="loadAlarmData" />
                  </section>
</div>
      </el-tab-pane>

      <!-- 告警策略 -->
      <el-tab-pane label="告警策略" name="strategy"><template #label><WorkspaceTabLabel icon="SetUp">告警策略</WorkspaceTabLabel></template>
        <el-card>
          <template #header>
            <div class="card-header" style="display: flex; justify-content: space-between; align-items: center;">
              <div style="display: flex; align-items: center; gap: 16px;">
                <span>告警策略配置</span>
                <div class="switch-inline">
                  <el-switch
                    v-model="alarmSwitchEnabled"
                    active-text="启用"
                    inactive-text="禁用"
                    @change="toggleAlarmSwitch"
                  />
                </div>
              </div>
              <el-button v-if="strategySubTab === 'strategy'" @click="strategyExcelRef?.downloadTemplate()">导出全量模板</el-button>
              <el-button v-if="strategySubTab === 'strategy'" @click="strategyExcelVisible = true">Excel 批量设置</el-button>
              <el-button type="primary" @click="handleStrategyAdd" v-if="strategySubTab === 'strategy'">
                <el-icon><Plus /></el-icon>新增策略
              </el-button>
            </div>
          </template>

          <el-tabs v-model="strategySubTab" class="strategy-subtabs">
            <el-tab-pane label="策略配置" name="strategy">
              <!-- 策略筛选工具栏 -->
              <AlarmStrategyFilters v-model:filters="strategyFilters" :selected-strategy-rows="selectedStrategyRows" :all-selected-enabled="allSelectedEnabled" :has-scope-filter="hasScopeFilter" :filtered-total="strategyPagination.total" :filter-batch-loading="filterBatchLoading" @search="loadStrategyData" @reset="resetStrategyFilters" @delete="batchDeleteStrategies" @toggle-status="batchToggleStatus" @batch-filter-status="batchFilterStatus" />
              <AlarmStrategyTable :rows="strategyList" @selection-change="handleStrategySelectionChange" @edit="handleStrategyEdit" @status-change="saveStrategyStatus" />
              <WorkspacePagination v-model:page="strategyPagination.page" v-model:size="strategyPagination.size" :total="strategyPagination.total" :page-sizes="[10, 20, 50, 100, 200, 500]" @change="loadStrategyData" />
            </el-tab-pane>
            <el-tab-pane label="屏蔽管理" name="shield">
              <div class="shield-toolbar">
                <el-radio-group v-model="shieldFilter" size="small">
                  <el-radio-button value="effective">有效屏蔽</el-radio-button>
                  <el-radio-button value="expired">已到期</el-radio-button>
                  <el-radio-button value="all">全部</el-radio-button>
                </el-radio-group>
              </div>
              <el-table v-sticky-header v-mirror-scroll :data="shieldedAlarmList" v-loading="shieldLoading" border>
                <el-table-column prop="id" label="ID" width="60" />
                <el-table-column prop="metricName" label="指标名称" min-width="120" class-name="wrap-cell" />
                <el-table-column prop="alarmContent" label="告警内容" min-width="150" class-name="wrap-cell">
                  <template #default="{ row }">
                    <span style="white-space: pre-line;">{{ stripNumberTrailingZeros(row.alarmContent) }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="shieldTime" label="屏蔽开始时间" width="160">
                  <template #default="{ row }">{{ row.shieldTime ? formatDateTime(row.shieldTime) : '-' }}</template>
                </el-table-column>
                <el-table-column prop="shieldEndTime" label="屏蔽到期时间" width="160">
                  <template #default="{ row }">{{ row.shieldEndTime ? formatDateTime(row.shieldEndTime) : '永久' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="80">
                  <template #default="{ row }">
                    <el-tag v-if="isShieldExpired(row)" type="info" size="small">已到期</el-tag>
                    <el-tag v-else type="warning" size="small">有效</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" @click="handleRemoveShield(row)">解除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!shieldLoading && shieldedAlarmList.length === 0" description="暂无屏蔽记录" style="padding: 40px 0;" />
            </el-tab-pane>

          </el-tabs>
        </el-card>
      </el-tab-pane>

      <!-- 告警通道 -->
      <el-tab-pane label="告警通道" name="channel"><template #label><WorkspaceTabLabel icon="Connection">告警通道</WorkspaceTabLabel></template>
        <el-card>
          <template #header>
            <div class="card-header">
              <WorkspaceSectionHeading title="告警通道配置" icon="Connection"><span>{{ channelList.length }} 个通道</span></WorkspaceSectionHeading>
              <el-button type="primary" @click="handleChannelAdd">
                <el-icon><Plus /></el-icon>新增通道
              </el-button>
            </div>
          </template>

          <!-- 统计信息 -->
          <el-table v-sticky-header v-mirror-scroll :data="channelList" border>
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="channelName" label="通道名称" width="150" />
            <el-table-column prop="channelType" label="类型" width="100">
              <template #default="{ row }">
                <el-tag :type="row.channelType === 'FILE' ? 'success' : 'primary'">
                  {{ row.channelType === 'FILE' ? '文件' : 'HTTP接口' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="configKey" label="配置标识" width="150" />
            <el-table-column prop="configValue" label="文件路径／插件" min-width="200" class-name="wrap-cell">
              <template #default="{ row }">
                <div v-if="row.channelType === 'FILE'" class="channel-path">
                  <span>{{ row.configValue }}</span>
                  <small v-if="row.fullPath" :title="row.fullPath">{{ row.fullPath }}</small>
                </div>
                <span v-else>
                  {{ getPluginName(row.configValue) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="150" class-name="wrap-cell" />
            <el-table-column prop="enabled" label="状态" width="80">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :active-value="1"
                  :inactive-value="0"
                  @change="handleChannelToggle(row)"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="handleChannelEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="handleChannelDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 告警规律分析 -->
      <el-tab-pane label="告警分析" name="analysis"><template #label><WorkspaceTabLabel icon="DataAnalysis">告警分析</WorkspaceTabLabel></template>
        <el-card>
          <template #header>
            <div class="card-header" style="display: flex; justify-content: space-between; align-items: center;">
              <span>告警规律分析</span>
              <el-button type="primary" size="small" @click="handleTriggerAnalysis">
                <el-icon><Refresh /></el-icon>生成报告
              </el-button>
            </div>
          </template>
          <!-- 配置区域 -->
          <el-form v-balanced-query :inline="true" size="small" style="margin-bottom: 12px; padding: 12px; background: #f5f7fa; border-radius: 4px;">
            <el-form-item label="定时任务">
              <el-switch v-model="analysisConfig.analysisEnabled" @change="updateAnalysisConfig" />
            </el-form-item>
            <el-form-item label="Cron">
              <el-input v-model="analysisConfig.cronExpression" style="width: 160px;" placeholder="0 0 2 * * ?" @blur="updateAnalysisConfig" />
            </el-form-item>
            <el-form-item label="回溯天数">
              <el-input-number v-model="analysisConfig.lookbackDays" :min="1" :max="30" @change="updateAnalysisConfig" />
            </el-form-item>
            <el-form-item label="日期筛选">
              <el-date-picker
                v-model="analysisDateRange"
                type="daterange"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                value-format="YYYY-MM-DD"
                style="width: 220px;"
                @change="loadAnalysisReports"
                clearable
              />
            </el-form-item>
          </el-form>
          <el-table v-sticky-header v-mirror-scroll v-loading="analysisLoading" :data="analysisReportList" border stripe @selection-change="handleAnalysisSelectionChange">
            <el-table-column type="selection" width="40" fixed="left"/>
            <el-table-column prop="reportDate" label="报告日期" width="120">
              <template #default="{ row }">{{ row.reportDate }}</template>
            </el-table-column>
            <el-table-column prop="metricName" label="指标名称" min-width="120" class-name="wrap-cell" />
            <el-table-column prop="alarmCount" label="告警次数" width="80" />
            <el-table-column prop="avgValue" label="平均值" width="100">
              <template #default="{ row }">{{ row.avgValue ? Number(row.avgValue).toFixed(2) : '-' }}</template>
            </el-table-column>
            <el-table-column prop="maxValue" label="最大值" width="100">
              <template #default="{ row }">{{ row.maxValue ? Number(row.maxValue).toFixed(2) : '-' }}</template>
            </el-table-column>
            <el-table-column prop="ruleType" label="规则类型" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.ruleType" :type="getRuleTypeTagType(row.ruleType)" size="small">
                  {{ row.ruleType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="ruleDescription" label="规则描述" min-width="150" class-name="wrap-cell" />
            <el-table-column prop="suggestion" label="建议" min-width="200" class-name="wrap-cell" />
          </el-table>
          <div style="display: flex; justify-content: space-between; margin-top: 12px;">
            <el-button type="danger" size="small" :disabled="analysisSelectedRows.length === 0" @click="handleBatchDeleteAnalysis">
              批量删除
            </el-button>
            <WorkspacePagination v-model:page="analysisPagination.page" v-model:size="analysisPagination.size" :total="analysisPagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadAnalysisReports" />
          </div>
          <el-empty v-if="!analysisLoading && analysisReportList.length === 0" description="暂无分析报告" style="padding: 40px 0;" />
        </el-card>
      </el-tab-pane>

      <!-- 操作日志 -->
      <el-tab-pane label="操作日志" name="auditLog"><template #label><WorkspaceTabLabel icon="Clock">操作日志</WorkspaceTabLabel></template>
        <el-card>
          <template #header>
            <div class="card-header" style="display: flex; justify-content: space-between; align-items: center;">
              <span>告警操作日志</span>
              <el-button type="primary" size="small" @click="loadAuditLogs">
                刷新
              </el-button>
            </div>
          </template>
          <!-- 筛选区域 -->
          <div class="filter-toolbar" v-balanced-query style="margin-bottom: 12px;">
            <el-select v-model="auditQuery.entityType" placeholder="实体类型" clearable @change="loadAuditLogs" style="width: 130px;">
              <el-option label="告警" value="alarm" />
              <el-option label="告警策略" value="alarm_strategy" />
              <el-option label="告警通道" value="alarm_channel" />
              <el-option label="屏蔽规则" value="shield_rule" />
              <el-option label="原始数据告警" value="raw_data_alarm" />
            </el-select>
            <el-select v-model="auditQuery.operationType" placeholder="操作类型" clearable @change="loadAuditLogs" style="width: 130px;">
              <el-option label="屏蔽" value="SHIELD" />
              <el-option label="解除屏蔽" value="UNSHIELD" />
              <el-option label="处理" value="PROCESS" />
              <el-option label="取消处理" value="CANCEL_PROCESS" />
              <el-option label="转问题" value="TAG_TO_ISSUE" />
              <el-option label="转工单" value="TAG_TO_GONGDAN" />
              <el-option label="新增" value="CREATE" />
              <el-option label="修改" value="UPDATE" />
              <el-option label="删除" value="DELETE" />
            </el-select>
            <el-date-picker
              v-model="auditQuery.timeRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 340px;"
              @change="loadAuditLogs"
              clearable
            />
          </div>
          <!-- 批量删除按钮 -->
          <div style="margin-bottom: 12px;">
            <el-button type="danger" size="small" :disabled="selectedAuditLogs.length === 0" @click="handleBatchDeleteAuditLogs">
              删除 ({{ selectedAuditLogs.length }})
            </el-button>
          </div>
          <!-- 日志列表 -->
          <el-table v-sticky-header v-mirror-scroll v-loading="auditLoading" :data="auditLogList" border stripe @selection-change="(val) => selectedAuditLogs = val">
            <el-table-column type="selection" width="40" fixed="left" />
            <el-table-column prop="createTime" label="操作时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column prop="entityType" label="实体类型" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.entityType === 'alarm'" type="danger" size="small">告警</el-tag>
                <el-tag v-else-if="row.entityType === 'alarm_strategy'" type="warning" size="small">告警策略</el-tag>
                <el-tag v-else-if="row.entityType === 'alarm_channel'" type="success" size="small">告警通道</el-tag>
                <el-tag v-else-if="row.entityType === 'shield_rule'" type="info" size="small">屏蔽规则</el-tag>
                <el-tag v-else-if="row.entityType === 'raw_data_alarm'" type="" size="small">原始数据告警</el-tag>
                <span v-else>{{ row.entityType }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="operationType" label="操作类型" width="100">
              <template #default="{ row }">
                <el-tag :type="getOperationTypeTagType(row.operationType)" size="small">
                  {{ getOperationTypeLabel(row.operationType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="entityId" label="实体ID" width="80" />
            <el-table-column prop="operator" label="操作人" width="100" />
            <el-table-column prop="changeContent" label="变更内容" min-width="200" class-name="wrap-cell" />
            <el-table-column prop="extraInfo" label="详细信息" min-width="200" class-name="wrap-cell" />
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showAuditLogDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <WorkspacePagination v-model:page="auditPagination.page" v-model:size="auditPagination.size" :total="auditPagination.total" :page-sizes="[10, 20, 50, 100, 200, 500]" @change="loadAuditLogs" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 屏蔽对话框 -->
    <div style="position: relative; overflow: visible;">
      <el-dialog class="workspace-theme" v-model="shieldDialogVisible" title="屏蔽设置" :width="shieldWidth">
        <el-form label-width="100px">
          <el-form-item label="屏蔽状态">
            <el-switch v-model="shieldForm.shieldStatus" :active-value="1" :inactive-value="0" />
          </el-form-item>
          <el-form-item label="屏蔽结束时间" v-if="shieldForm.shieldStatus === 1">
            <el-date-picker v-model="shieldForm.shieldEndTime" type="datetime" placeholder="选择结束时间" value-format="YYYY-MM-DDTHH:mm:ss" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="shieldDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveShield">确定</el-button>
        </template>
      </el-dialog>
      <DialogSizeControls :adjustWidth="adjustShieldWidth" :visible="shieldDialogVisible" />
    </div>





    <!-- 问题详情对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="issueDetailDialogVisible" title="问题详情" :width="issueDetailWidth">
      <div class="detail-container" v-if="issueDetailData">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="问题类型">{{ issueDetailData.issueType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="问题属性">{{ issueDetailData.issueAttribute || '-' }}</el-descriptions-item>
          <el-descriptions-item label="问题来源">{{ issueDetailData.issueSource || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发现者">{{ issueDetailData.discoverer || '-' }}</el-descriptions-item>
          <el-descriptions-item label="责任人">{{ issueDetailData.responsiblePerson || '-' }}</el-descriptions-item>
          <el-descriptions-item label="解决状态">
            <el-tag :type="issueDetailData.resolveStatus === '已解决' ? 'success' : 'danger'">
              {{ issueDetailData.resolveStatus || '-' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="系统">{{ issueDetailData.systemName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="指标大类">{{ issueDetailData.categoryName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="指标">{{ issueDetailData.metricName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="预计解决时间">{{ formatDateTime(issueDetailData.planResolveTime) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="登记时间">{{ formatDateTime(issueDetailData.registerTime) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="修改时间">{{ formatDateTime(issueDetailData.updateTime) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="问题描述" :span="2">{{ issueDetailData.issueDescription || '-' }}</el-descriptions-item>
          <el-descriptions-item label="核查原因" :span="2">{{ issueDetailData.checkReason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="后评估" :span="2">{{ issueDetailData.postEvaluation || '-' }}</el-descriptions-item>
          <el-descriptions-item label="附件" :span="2">
            <el-link v-if="issueDetailData.attachmentName" type="primary" @click="downloadIssueAttachment(issueDetailData)">
              {{ issueDetailData.attachmentName }}
            </el-link>
            <span v-else>无</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button type="primary" @click="handleEditIssueFromDetail">
          <el-icon><Edit /></el-icon>编辑
        </el-button>
        <el-button @click="issueDetailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustIssueDetailWidth" :visible="issueDetailDialogVisible" />
    </div>

    <!-- 问题编辑对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="issueDialogVisible" :title="issueForm.id ? '编辑问题' : '登记问题'" :width="issueWidth">
      <el-form :model="issueForm" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="系统">
              <el-input v-model="issueForm.systemName" placeholder="输入系统名称" clearable />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="指标大类">
              <el-input v-model="issueForm.categoryName" placeholder="输入大类名称" clearable />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="指标">
              <el-input v-model="issueForm.metricName" placeholder="输入指标名称" clearable />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="问题类型">
              <el-input v-model="issueForm.issueType" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="问题来源">
              <el-select v-model="issueForm.issueSource">
                <el-option label="告警" value="告警" />
                <el-option label="人工巡检" value="人工巡检" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="问题描述">
          <el-input v-model="issueForm.issueDescription" type="textarea" :rows="3" />
        </el-form-item>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="发现者">
              <el-input v-model="issueForm.discoverer" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="责任人">
              <el-input v-model="issueForm.responsiblePerson" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="问题属性">
              <el-select v-model="issueForm.issueAttribute">
                <el-option label="新问题" value="新问题" />
                <el-option label="老问题" value="老问题" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="预计解决时间">
              <el-date-picker
                v-model="issueForm.planResolveTime"
                type="datetime"
                placeholder="选择时间"
                value-format="YYYY-MM-DDTHH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="核查原因">
          <el-input v-model="issueForm.checkReason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="后评估">
          <el-input v-model="issueForm.postEvaluation" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="解决状态">
          <el-select v-model="issueForm.resolveStatus">
            <el-option label="未解决" value="未解决" />
            <el-option label="已解决" value="已解决" />
          </el-select>
        </el-form-item>
        <el-form-item label="附件上传">
          <div class="attachment-upload">
            <el-upload
              ref="issueUploadRef"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleIssueUploadAttachment"
              accept=".pdf,.doc,.docx,.xls,.xlsx,.txt,.jpg,.jpeg,.png"
              action="#"
            >
              <el-button size="small" type="primary">选择文件</el-button>
            </el-upload>
            <div v-if="issueForm.attachmentName" class="attachment-info">
              <el-link type="primary" @click="issueCurrentEditId && downloadIssueAttachment({id: issueCurrentEditId, attachmentName: issueForm.attachmentName})">
                {{ issueForm.attachmentName }}
              </el-link>
              <el-button type="danger" size="small" link @click="handleIssueDeleteAttachment">删除</el-button>
            </div>
            <div v-else class="no-attachment-tip">未上传附件</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveIssueData">确定</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustIssueWidth" :visible="issueDialogVisible" />
    </div>

    <!-- 告警策略对话框（公共组件）
         与「指标体系配置」页共用同一份表单实现：components/alarm/StrategyEditorDialog.vue
         后续给策略增加字段（例如告警等级：重要/一般）改该组件即可，两页同时生效。
         详见 doc/告警策略表单复用说明.md -->
    <AlarmOccurrenceDialog ref="occurrenceDialogRef" v-model="occurrenceVisible" :group="occurrenceGroup" :scope="occurrenceScope"
                           @process="handleProcess" @shield="handleShield" @issue="handleOccurrenceIssue" @gongdan="handleOccurrenceGongdan" />
    <AlarmConversionDialog ref="conversionDialogRef" @converted="handleConverted"
                           @open-issue="id => handleViewRelatedIssue({ relatedIssueId: id })" @open-gongdan="openRelatedGongdan" />
    <GongdanDetailDialog v-model="gongdanDetailVisible" :gongdan-id="relatedGongdanId" @refresh="loadAlarmData" />
    <el-dialog class="workspace-theme" v-model="strategyPickerVisible" title="选择要修改的策略" width="480px" append-to-body>
      <p style="color: #909399; line-height: 1.7;">此对象有多条策略，请选择需要修改的一条。保存只更新所选策略。</p>
      <el-select v-model="pickedStrategyId" style="width: 100%" placeholder="请选择策略">
        <el-option v-for="choice in strategyPickerRow?.strategyChoices || []" :key="choice.id" :value="choice.id"
                   :label="`${choice.name}（#${choice.id}，${choice.status === 1 ? '启用' : '停用'}）`" />
      </el-select>
      <template #footer><el-button @click="strategyPickerVisible = false">取消</el-button><el-button type="primary" :disabled="!pickedStrategyId" @click="confirmStrategyChoice">编辑策略</el-button></template>
    </el-dialog>
    <StrategyExcelDialog ref="strategyExcelRef" v-model="strategyExcelVisible" @saved="onStrategySaved" />
    <StrategyEditorDialog
      v-model="strategyDialogVisible"
      :initial="strategySeed"
      @saved="onStrategySaved"
    />

    <!-- 告警通道对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme" v-model="channelDialogVisible" :title="channelForm.id ? '编辑通道' : '新增通道'" :width="channelWidth">
      <el-form :model="channelForm" label-width="100px">
        <el-form-item label="通道名称" required>
          <el-input v-model="channelForm.channelName" placeholder="请输入通道名称" />
        </el-form-item>
        <el-form-item label="通道类型" required>
          <el-select v-model="channelForm.channelType" style="width: 100%" @change="onChannelTypeChange">
            <el-option label="文件" value="FILE" />
            <el-option label="HTTP接口" value="HTTP_API" />
          </el-select>
        </el-form-item>
        <el-form-item label="配置标识" required>
          <el-input v-model="channelForm.configKey" placeholder="唯一标识，如 file_output_1" />
        </el-form-item>
<el-form-item v-if="channelForm.channelType === 'FILE'" label="文件路径" required>
          <el-input v-model="channelForm.configValue" placeholder="如 alarm/alarm.txt" />
          <div class="form-help">填到最后的文件名，例如 alarm/alarm.txt。相对路径写到平台运行环境的 files/ 目录下，也可以直接填绝对路径；目录不存在会自动创建。</div>
        </el-form-item>
        <el-form-item v-if="channelForm.channelType === 'FILE'" label="适用场景">
          <el-select v-model="channelForm.scene" style="width: 100%" placeholder="请选择场景" @change="onSceneChange">
            <el-option v-for="s in alarmSceneOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="channelForm.channelType === 'FILE'" label="内容模板">
          <div class="template-editor">
            <div class="template-fields">
              <span v-for="f in currentSceneFields" :key="f.key" class="template-field-tag" @click="insertField(f)">{{ f.label }}</span>
            </div>
            <el-input v-model="channelForm.template" type="textarea" :rows="3" placeholder="选择场景后自动填入模板，也可自定义修改" />
            <div class="template-preview" v-if="channelForm.template">
              <span class="preview-label">预览：</span>
              <span class="preview-text">{{ previewTemplate }}</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="channelForm.channelType === 'HTTP_API'" label="选择插件" required>
          <el-select v-model="channelForm.configValue" style="width: 100%" filterable placeholder="请选择插件">
            <el-option v-for="p in pluginList" :key="p.id" :label="p.name" :value="String(p.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="channelForm.description" type="textarea" :rows="2" placeholder="请输入描述" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="channelForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveChannel">确定</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustChannelWidth" :visible="channelDialogVisible" />
    </div>

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
    <!-- 点击空白关闭菜单的遮罩 -->
    <div
      v-if="showHeaderContextMenu"
      class="context-menu-overlay"
      @click="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    />
  </div>
</template>

<script setup>
import { openIssueAttachment as downloadIssueAttachment } from '@/utils/issueAttachment'
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
import { readWorkspaceChartTheme } from '@/utils/workspaceTheme'
import AlarmStrategyFilters from '@/components/alarm/AlarmStrategyFilters.vue'
import AlarmStrategyTable from '@/components/alarm/AlarmStrategyTable.vue'
import AlarmOverviewCards from '@/components/alarm/AlarmOverviewCards.vue'
import WorkspaceOverviewLayout from '@/components/workspace/WorkspaceOverviewLayout.vue'
import WorkspacePanel from '@/components/workspace/WorkspacePanel.vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import WorkspacePagination from '@/components/workspace/WorkspacePagination.vue'
import AlarmFilters from '@/components/alarm/AlarmFilters.vue'
import { ref, onMounted, onUnmounted, watch, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { alarmApi, configApi, systemApi, metricApi, issueApi, alarmChannelApi, pluginConfigApi, alarmSwitchApi, alarmAnalysisApi, realtimeApi, subcategoryApi, shieldRuleApi } from '../../api'
import { formatDateTime, simplifyTimeLabels, getTimeRangeHint, stripNumberTrailingZeros } from '../../utils'
import { useDialogSize } from '../../utils/useDialogSize'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'
// 策略表单公共组件（与「指标体系配置」页共用，改一处两页同时生效）
import { ruleSummary, ruleLevels } from '../../utils/strategyRules'
import AlarmOccurrenceDialog from '../../components/alarm/AlarmOccurrenceDialog.vue'
import AlarmConversionDialog from '../../components/alarm/AlarmConversionDialog.vue'
import GongdanDetailDialog from '../../components/gongdan/GongdanDetailDialog.vue'
import StrategyThresholdCell from '../../components/alarm/StrategyThresholdCell.vue'
import StrategyExcelDialog from '../../components/alarm/StrategyExcelDialog.vue'
import StrategyEditorDialog from '../../components/alarm/StrategyEditorDialog.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref('alarm')
const summaryScope = ref(null)
const summaryTotalEvents = ref(0)
const defaultAlarmTypes = ['指标值告警', '健康度告警', '原始数据告警', '原始数据工单', '问题逾期告警', '指标值工单', '健康度工单']
const alarmTypeOptions = ref([...defaultAlarmTypes])
const occurrenceVisible = ref(false)
const occurrenceGroup = ref(null)
const occurrenceScope = ref(null)
const occurrenceDialogRef = ref(null)
const strategyPickerVisible = ref(false)
const strategyPickerRow = ref(null)
const pickedStrategyId = ref(null)
let summaryRequestVersion = 0
const openOccurrences = row => {
  occurrenceGroup.value = { ...row }
  occurrenceScope.value = { ...summaryScope.value }
  occurrenceVisible.value = true
}
const conversionDialogRef = ref(null)
const gongdanDetailVisible = ref(false), relatedGongdanId = ref(null)
const openRelatedGongdan = id => { relatedGongdanId.value = id; gongdanDetailVisible.value = true }
const openConversion = (row, kind) => conversionDialogRef.value?.open(row.groupKey
  ? { ...summaryScope.value, groups: groupSelections([row]) }
  : { alarmId: row.id }, kind)
const handleOccurrenceIssue = row => row.relatedIssueId ? handleViewRelatedIssue(row) : openConversion(row, 'issue')
const handleOccurrenceGongdan = row => row.relatedGongdanId ? openRelatedGongdan(row.relatedGongdanId) : openConversion(row, 'gongdan')
const handleConverted = () => { refreshOccurrences(); loadAlarmData() }
const handleGroupCommand = (command, row) => {
  if (command === 'details') return openOccurrences(row)
  if (command === 'issue' || command === 'gongdan') return openConversion(row, command)
  if (command === 'process') return handleProcess(row)
}
const confirmStrategyChoice = () => {
  strategyPickerVisible.value = false
  handleThreshold(strategyPickerRow.value, pickedStrategyId.value)
}
const processLabel = row => row.unprocessedCount > 0 ? `未处理 ${row.unprocessedCount} 次` : row.processStatus === 3 ? '工单记录' : row.processStatus === 1 ? '已处理' : row.processStatus === 2 ? '已关闭' : '混合状态'
const shieldLabel = row => row.shieldStatus === 1 ? '已屏蔽' : row.shieldStatus === -1 ? '部分屏蔽' : '屏蔽'
const alarmValueText = row => {
  const value = row.alarmType?.includes('健康度') ? row.healthScore : row.metricValue
  const unit = row.alarmType === '问题逾期告警' ? '天' : row.alarmType?.includes('健康度') ? '分' : row.currentValueUnit || row.unit?.trim() || row.thresholdUnit?.trim() || ''
  return value == null ? '未记录' : `${stripNumberTrailingZeros(String(value))}${unit ? ` ${unit}` : '（单位未配置）'}`
}
const groupSelections = rows => rows.map(row => ({ groupKey: row.groupKey, expectedCount: row.alarmCount }))
const refreshOccurrences = () => { if (occurrenceVisible.value) occurrenceDialogRef.value?.reload() }

const strategyExcelVisible = ref(false)
const strategyExcelRef = ref(null)

// 弹窗宽度控制
const { width: shieldWidth, adjustWidth: adjustShieldWidth } = useDialogSize('alarm-shield', '400px')
const { width: thresholdWidth, adjustWidth: adjustThresholdWidth } = useDialogSize('alarm-threshold', '600px')
const { width: issueDetailWidth, adjustWidth: adjustIssueDetailWidth } = useDialogSize('alarm-issue-detail', '700px')
const { width: issueWidth, adjustWidth: adjustIssueWidth } = useDialogSize('alarm-issue', '800px')
const { width: channelWidth, adjustWidth: adjustChannelWidth } = useDialogSize('alarm-channel', '500px')

// 监听Tab切换，加载数据
watch(activeTab, (newTab) => {
  if (newTab === 'analysis') {
    loadAnalysisConfig()
    loadAnalysisReports()
  } else if (newTab === 'auditLog') {
    loadAuditLogs()
  }
})

const alarmList = ref([])
const tableLoading = ref(false)
const selectedRows = ref([])
const strategyList = ref([])
const selectedStrategyRows = ref([])

const handleStrategySelectionChange = (selection) => {
  selectedStrategyRows.value = selection
}

const allSelectedEnabled = computed(() => {
  return selectedStrategyRows.value.length > 0 && selectedStrategyRows.value.every(r => r.status === 1)
})

// 批量删除策略
const batchDeleteStrategies = async () => {
  if (selectedStrategyRows.value.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedStrategyRows.value.length} 条策略吗？`, '提示', { type: 'warning' })
    await Promise.all(selectedStrategyRows.value.map(row => configApi.strategyDelete(row.id)))
    ElMessage.success('删除成功')
    selectedStrategyRows.value = []
    loadStrategyData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('批量删除失败:', error)
    }
  }
}

// 批量切换状态
const batchToggleStatus = async () => {
  if (selectedStrategyRows.value.length === 0) return
  const newStatus = allSelectedEnabled.value ? 0 : 1
  const action = newStatus === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定${action}选中的 ${selectedStrategyRows.value.length} 条策略吗？`, '提示', { type: 'warning' })
    await Promise.all(selectedStrategyRows.value.map(row => configApi.strategyUpdate(row.id, { status: newStatus })))
    ElMessage.success(`${action}成功`)
    selectedStrategyRows.value = []
    loadStrategyData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('批量更新状态失败:', error)
    }
  }
}

// 按当前「指标大类/小类」筛选批量启停：覆盖筛选结果全部分页，不只是当前页勾选
const batchFilterStatus = async (targetStatus) => {
  if (!hasScopeFilter.value || filterBatchLoading.value) return
  const action = targetStatus === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(`将当前筛选的 ${strategyPagination.value.total} 条策略全部${action}（含未显示的分页记录）？`, `按筛选批量${action}`, { type: 'warning' })
    filterBatchLoading.value = true
    const response = await configApi.strategyBatchStatus({ ...strategyFilterParams(), targetStatus })
    ElMessage.success(`已${action} ${response.data} 条策略`)
    selectedStrategyRows.value = []
    loadStrategyData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('按筛选批量更新状态失败:', error)
      ElMessage.error(error.message || `${action}失败`)
    }
  } finally {
    filterBatchLoading.value = false
  }
}

// ========== 操作日志相关 ==========
const auditLoading = ref(false)
const auditLogList = ref([])
const selectedAuditLogs = ref([])
const auditQuery = ref({
  entityType: '',
  operationType: '',
  timeRange: []
})
const auditPagination = ref({
  page: 1,
  size: 20,
  total: 0
})

// 加载操作日志
const loadAuditLogs = async () => {
  auditLoading.value = true
  try {
    const params = {
      page: auditPagination.value.page - 1,
      size: auditPagination.value.size
    }
    if (auditQuery.value.entityType) {
      params.entityType = auditQuery.value.entityType
    }
    if (auditQuery.value.operationType) {
      params.operationType = auditQuery.value.operationType
    }
    if (auditQuery.value.timeRange && auditQuery.value.timeRange.length === 2) {
      params.startTime = auditQuery.value.timeRange[0]
      params.endTime = auditQuery.value.timeRange[1]
    }
    const res = await alarmApi.getAuditLogs(params)
    auditLogList.value = res.data.content || []
    auditPagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载操作日志失败:', error)
  } finally {
    auditLoading.value = false
  }
}

// 操作类型标签颜色
const getOperationTypeTagType = (opType) => {
  const typeMap = {
    'SHIELD': 'warning',
    'UNSHIELD': 'info',
    'PROCESS': 'success',
    'CANCEL_PROCESS': 'info',
    'TAG_TO_ISSUE': 'primary',
    'TAG_TO_GONGDAN': 'primary',
    'CREATE': 'success',
    'UPDATE': 'warning',
    'DELETE': 'danger',
    'ENABLE': 'success',
    'DISABLE': 'info'
  }
  return typeMap[opType] || 'info'
}

// 操作类型标签文字
const getOperationTypeLabel = (opType) => {
  const labelMap = {
    'SHIELD': '屏蔽',
    'UNSHIELD': '解除屏蔽',
    'PROCESS': '处理',
    'CANCEL_PROCESS': '取消处理',
    'TAG_TO_ISSUE': '转问题',
    'TAG_TO_GONGDAN': '转工单',
    'STRATEGY_CREATE': '新增策略',
    'STRATEGY_UPDATE': '修改策略',
    'STRATEGY_DELETE': '删除策略',
    'STRATEGY_ENABLE': '启用策略',
    'STRATEGY_DISABLE': '禁用策略',
    'CHANNEL_CREATE': '新增通道',
    'CHANNEL_UPDATE': '修改通道',
    'CHANNEL_DELETE': '删除通道',
    'CHANNEL_ENABLE': '启用通道',
    'CHANNEL_DISABLE': '禁用通道',
    'SHIELD_RULE_CREATE': '新增屏蔽规则',
    'SHIELD_RULE_UPDATE': '修改屏蔽规则',
    'SHIELD_RULE_DELETE': '删除屏蔽规则',
    'RAW_DATA_ALARM_CREATE': '新增原始数据告警',
    'RAW_DATA_ALARM_UPDATE': '修改原始数据告警',
    'RAW_DATA_ALARM_DELETE': '删除原始数据告警',
    'RAW_DATA_ALARM_TOGGLE': '切换原始数据告警',
    'CREATE': '新增',
    'UPDATE': '修改',
    'DELETE': '删除'
  }
  return labelMap[opType] || opType
}

// 显示日志详情
const showAuditLogDetail = (row) => {
  ElMessageBox.alert(
    `<div>
      <p><strong>实体类型：</strong>${row.entityType}</p>
      <p><strong>实体ID：</strong>${row.entityId}</p>
      <p><strong>操作类型：</strong>${row.operationType}</p>
      <p><strong>操作人：</strong>${row.operator}</p>
      <p><strong>操作时间：</strong>${formatDateTime(row.createTime)}</p>
      <p><strong>变更内容：</strong>${row.changeContent || '-'}</p>
      <p><strong>旧值：</strong>${row.oldValue || '-'}</p>
      <p><strong>新值：</strong>${row.newValue || '-'}</p>
      <p><strong>额外信息：</strong>${row.extraInfo || '-'}</p>
    </div>`,
    '操作日志详情',
    { dangerouslyUseHTMLString: true }
  )
}

// 批量删除操作日志
const handleBatchDeleteAuditLogs = async () => {
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedAuditLogs.value.length} 条操作日志？`, '确认删除', { type: 'warning' })
    const ids = selectedAuditLogs.value.map(r => r.id)
    await alarmApi.batchDeleteAuditLogs(ids)
    ElMessage.success('删除成功')
    loadAuditLogs()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

const systemList = ref([])
const categoryList = ref([])
const metricList = ref([])
const subCategoryList = ref([])
const queryParams = ref({
  processStatus: null,
  alarmType: null,
  shieldStatus: null,
  alarmTimeRange: null,
  alarmContent: '',
  systemId: null,
  categoryId: null,
  metricId: null
})

// 告警开关状态
const alarmSwitchEnabled = ref(true)

// 加载告警开关状态
const loadAlarmSwitchStatus = async () => {
  try {
    const res = await alarmSwitchApi.getStatus()
    alarmSwitchEnabled.value = res.data.alarmEnabled !== false
  } catch (error) {
    console.error('加载告警开关状态失败:', error)
  }
}

// 切换告警开关
const toggleAlarmSwitch = async () => {
  try {
    const newStatus = await alarmSwitchApi.toggleAlarm()
    alarmSwitchEnabled.value = newStatus.data
    ElMessage.success(newStatus.data ? '告警已启用' : '告警已禁用')
  } catch (error) {
    console.error('切换告警开关失败:', error)
    ElMessage.error('切换失败')
  }
}

// 告警分析相关
const analysisLoading = ref(false)
const analysisReportList = ref([])
const analysisConfig = ref({
  cronExpression: '0 0 2 * * ?',
  analysisEnabled: true,
  lookbackDays: 7
})
const analysisDateRange = ref([])
const analysisSelectedRows = ref([])
const analysisPagination = ref({
  page: 1,
  size: 10,
  total: 0
})

// 加载分析配置
const loadAnalysisConfig = async () => {
  try {
    const res = await alarmAnalysisApi.getConfig()
    if (res.data) {
      analysisConfig.value = {
        cronExpression: res.data.cronExpression || '0 0 2 * * ?',
        analysisEnabled: res.data.analysisEnabled === 1,
        lookbackDays: res.data.lookbackDays || 7
      }
    }
  } catch (error) {
    console.error('加载分析配置失败:', error)
  }
}

// 更新分析配置
const updateAnalysisConfig = async () => {
  try {
    await alarmAnalysisApi.updateConfig({
      cronExpression: analysisConfig.value.cronExpression,
      analysisEnabled: analysisConfig.value.analysisEnabled ? 1 : 0,
      lookbackDays: analysisConfig.value.lookbackDays
    })
    ElMessage.success('配置已保存')
  } catch (error) {
    console.error('保存分析配置失败:', error)
    ElMessage.error('保存失败')
  }
}

// 加载分析报告
const loadAnalysisReports = async () => {
  analysisLoading.value = true
  try {
    const params = {
      page: analysisPagination.value.page - 1,
      size: analysisPagination.value.size
    }
    if (analysisDateRange.value && analysisDateRange.value.length === 2) {
      params.startDate = analysisDateRange.value[0]
      params.endDate = analysisDateRange.value[1]
    }
    const res = await alarmAnalysisApi.getReports(params)
    analysisReportList.value = res.data.content || []
    analysisPagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载分析报告失败:', error)
  } finally {
    analysisLoading.value = false
  }
}

// 分析报告选择处理
const handleAnalysisSelectionChange = (rows) => {
  analysisSelectedRows.value = rows
}

// 批量删除分析报告
const handleBatchDeleteAnalysis = async () => {
  if (analysisSelectedRows.value.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${analysisSelectedRows.value.length} 条报告吗？`, '提示', { type: 'warning' })
    const ids = analysisSelectedRows.value.map(row => row.id)
    await alarmAnalysisApi.batchDelete(ids)
    ElMessage.success('删除成功')
    analysisSelectedRows.value = []
    loadAnalysisReports()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }
}

// 手动触发分析
const handleTriggerAnalysis = async () => {
  try {
    await alarmAnalysisApi.trigger({ lookbackDays: analysisConfig.value.lookbackDays })
    ElMessage.success('报告生成中，请稍后刷新查看')
    setTimeout(loadAnalysisReports, 2000)
  } catch (error) {
    console.error('触发分析失败:', error)
    ElMessage.error('触发失败')
  }
}

// 获取规则类型标签颜色
const getRuleTypeTagType = (ruleType) => {
  const typeMap = {
    'high_frequency': 'warning',
    'time_based': 'info',
    'value_spike': 'danger',
    'consecutive': 'warning',
    'correlated': 'success'
  }
  return typeMap[ruleType] || 'info'
}

// 排序参数
const sortParams = ref({ sortBy: 'alarmTime', sortOrder: 'desc' })

// 列显示设置
const STORAGE_KEY = 'alarm_table_preferences'
const columnOptions = ref([
  { prop: 'alarmTime', label: '最近发生' },
  { prop: 'alarmObject', label: '告警对象' },
  { prop: 'systemName', label: '告警系统' },
  { prop: 'categoryName', label: '指标大类' },
  { prop: 'alarmType', label: '类型' },
  { prop: 'currentValue', label: '最近数值' },
  { prop: 'alarmCount', label: '告警次数' },
  { prop: 'alarmLevel', label: '最近级别' },
  { prop: 'thresholdValue', label: '告警阈值' },
  { prop: 'gongdanThreshold', label: '工单阈值' },
  { prop: 'alarmContent', label: '告警内容' },
  { prop: 'processStatus', label: '状态' }
])

// 默认显示所有列
const defaultVisibleColumns = columnOptions.value.map(c => c.prop)
const visibleColumns = ref([...defaultVisibleColumns])

// 检查列是否隐藏
const isColumnHidden = (prop) => {
  return !visibleColumns.value.includes(prop)
}

// 告警对象列精简显示：
//   健康度告警: 系统-维度名（categoryName 在健康度场景下是维度名）
//   指标值告警/工单: 系统-指标小类-指标名（跳过被错误填充的 categoryName）
const getAlarmObjectDisplay = (row) => {
  const parts = [row.systemName || '']
  if (row.alarmType?.includes('健康度')) {
    // 健康度告警：categoryName 是维度名（对内部人员/系统运行/对外部客户等），直接显示
    if (row.categoryName) parts.push(row.categoryName)
    if (row.subCategoryName) parts.push(row.subCategoryName)
    if (row.metricName) parts.push(row.metricName)
  } else {
    // 指标值告警/工单：跳过 categoryName（可能与 metricName 重复），显示小类+指标
    if (row.subCategoryName) parts.push(row.subCategoryName)
    if (row.metricName) parts.push(row.metricName)
  }
  return parts.filter(part => part && part !== '/').join(' / ')
}

// 行样式：区分已屏蔽的告警
const getRowClassName = ({ row }) => {
  if (row.shieldStatus === 1) {
    return 'row-shielded'
  }
  return ''
}

// 从localStorage加载偏好设置
const loadPreferences = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const prefs = JSON.parse(saved)
      if (prefs.visibleColumns && prefs.visibleColumns.length > 0) {
        visibleColumns.value = prefs.visibleColumns.filter(prop => defaultVisibleColumns.includes(prop))
        if (![2, 3].includes(prefs.version)) {
          visibleColumns.value = [...new Set([...visibleColumns.value, 'alarmCount', 'alarmLevel', 'gongdanThreshold'])]
        }
        if (prefs.version !== 3) visibleColumns.value = [...new Set([...visibleColumns.value, 'systemName', 'categoryName'])]
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
      visibleColumns: visibleColumns.value, version: 3
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
  // selection 列不支持隐藏
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

// 选中行变化
const handleSelectionChange = (selection) => {
  selectedRows.value = selection
}

// 批量删除

// 批量处理按钮文字
const batchProcessBtnText = computed(() => {
  const pending = selectedRows.value.reduce((sum, row) => sum + row.unprocessedCount, 0)
  const processed = selectedRows.value.reduce((sum, row) => sum + row.processedCount, 0)
  return pending === 0 && processed > 0 ? '取消处理' : '处理'
})

const handleBatchDelete = () => {
  if (selectedRows.value.length === 0) return
  ElMessageBox.confirm(`确定删除选中的 ${selectedRows.value.length} 组、共 ${selectedRows.value.reduce((sum, row) => sum + row.alarmCount, 0)} 次告警记录吗？仅删除本次筛选匹配记录，此操作不可撤销。`, '提示', {
    type: 'warning'
  }).then(async () => {
    try {
      await alarmApi.groupAction({ ...summaryScope.value, groups: groupSelections(selectedRows.value), action: 'delete' })
      refreshOccurrences()
      ElMessage.success('删除成功')
      selectedRows.value = []
      loadAlarmData()
      loadChannelStats()
      loadTrendData()  // 刷新告警趋势图
      // 触发更新顶部铃铛数字
      window.dispatchEvent(new Event('alarm-count-update'))
    } catch (error) {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }).catch(() => {})
}

// 批量处理
const handleBatchProcess = () => {
  if (selectedRows.value.length === 0) return
  // 判断当前选中是已处理还是未处理居多
  const hasProcessed = selectedRows.value.some(row => row.processedCount > 0)
  const hasUnprocessed = selectedRows.value.some(row => row.unprocessedCount > 0)
  if (!hasProcessed && !hasUnprocessed) { ElMessage.warning('所选组没有可处理的普通告警，工单请在工单管理中处理'); return }
  const isUnprocess = hasProcessed && !hasUnprocessed  // 全是已处理时才是取消处理
  const actionText = isUnprocess ? '取消处理' : '处理'

  ElMessageBox.confirm(`确定${actionText}选中的 ${selectedRows.value.length} 组、共 ${selectedRows.value.reduce((sum, row) => sum + (isUnprocess ? row.processedCount : row.unprocessedCount), 0)} 次匹配记录吗？工单记录不受影响。`, '提示', {
    type: 'warning'
  }).then(async () => {
    try {
      const targetStatus = isUnprocess ? 0 : 1
      await alarmApi.groupAction({ ...summaryScope.value, groups: groupSelections(selectedRows.value), action: 'process', newProcessStatus: targetStatus })
      refreshOccurrences()
      ElMessage.success(`${actionText}成功`)
      selectedRows.value = []
      loadAlarmData()
      loadChannelStats()
      loadTrendData()  // 刷新告警趋势图
      // 触发更新顶部铃铛数字
      window.dispatchEvent(new Event('alarm-count-update'))
    } catch (error) {
      console.error('操作失败:', error)
      ElMessage.error('操作失败')
    }
  }).catch(() => {})
}

// 处理排序变化
const handleSortChange = ({ prop, order }) => {
  if (order === 'descending') {
    sortParams.value.sortOrder = 'desc'
  } else if (order === 'ascending') {
    sortParams.value.sortOrder = 'asc'
  }
  sortParams.value.sortBy = prop || 'alarmTime'
  loadAlarmData()
}
const pagination = ref({ page: 1, size: 10, total: 0 })

// 屏蔽相关
const shieldDialogVisible = ref(false)
const shieldForm = ref({ id: null, shieldStatus: 0, shieldEndTime: null })

// 阈值设置相关
const thresholdDialogVisible = ref(false)
const thresholdForm = ref({
  metricId: null,
  metricName: '',
  alarmThreshold: null,
  alarmCondition: '>',
  alarmStatus: 1,
  alarmWindowMinutes: 30,
  consecutiveCount: 3
})
const thresholdLoading = ref(false)
const thresholdActiveTab = ref('config')

// 模拟相关
const simulateForm = ref({
  condition: '>',
  threshold: null,
  timeRange: null
})
const simulateLoading = ref(false)
const simulateResult = ref({})
const simulateChartDom = ref(null)
const simulateChart = ref(null)

// 问题详情弹窗相关
const issueDetailDialogVisible = ref(false)
const issueDetailData = ref(null)

// 问题编辑弹窗相关
const issueDialogVisible = ref(false)
const issueCurrentEditId = ref(null)
const issueForm = ref({
  id: null,
  issueType: '',
  issueSource: '告警',
  issueDescription: '',
  discoverer: '',
  responsiblePerson: '',
  issueAttribute: '新问题',
  planResolveTime: null,
  checkReason: '',
  postEvaluation: '',
  resolveStatus: '未解决',
  systemName: '',
  categoryName: '',
  metricName: ''
})
const issueUploadRef = ref(null)

// 策略相关
const strategyDialogVisible = ref(false)
const strategySeed = ref(null)  // 传给公共 StrategyEditorDialog 的预填数据，null = 新建

// 通道相关
const channelDialogVisible = ref(false)
const channelList = ref([])
const pluginList = ref([])
const channelStats = ref({ totalCount: 0, todayCount: 0, weekCount: 0, unprocessedCount: 0, processedCount: 0 })

// 扫描异常提示
const scanErrors = ref([])
const showScanErrorPanel = ref(true)
const loadScanErrors = async () => {
  try {
    const res = await alarmApi.getScanErrors()
    scanErrors.value = res.data?.errors || []
  } catch (e) {
    // 静默失败
  }
}
const channelForm = ref({
  id: null, channelName: '', channelType: 'FILE', configKey: '', configValue: '', scene: '', template: '', description: '', enabled: 1
})

// 告警模板字段定义
const alarmTemplateFields = [
  { key: 'alarmTime', label: '告警时间', template: '告警时间:{alarmTime}' },
  { key: 'alarmType', label: '告警类型', template: '告警类型:{alarmType}' },
  { key: 'alarmContent', label: '告警内容', template: '告警内容:{alarmContent}' },
  { key: 'metricValue', label: '指标值', template: '指标值:{metricValue}' },
  { key: 'thresholdValue', label: '阈值', template: '阈值:{thresholdValue}' },
  { key: 'healthScore', label: '健康度', template: '健康度:{healthScore}' },
  { key: 'categoryName', label: '大类', template: '大类:{categoryName}' },
  { key: 'metricName', label: '指标', template: '指标:{metricName}' },
  { key: 'processStatus', label: '处理状态', template: '处理状态:{processStatus}' },
  { key: 'systemName', label: '系统', template: '系统:{systemName}' },
  { key: 'subCategoryName', label: '小类', template: '小类:{subCategoryName}' },
  { key: 'sourceTable', label: '来源表', template: '来源表:{sourceTable}' },
  { key: 'sourceId', label: '来源ID', template: '来源ID:{sourceId}' },
]

// 模板预览（使用示例数据渲染）
const previewTemplate = computed(() => {
  if (!channelForm.value.template) return ''
  const sampleAlarm = {
    alarmTime: '2026-04-23 18:30:00',
    alarmType: '健康度告警',
    alarmContent: '[对外部客户-健康度] 大类=对外部客户 健康度=73，< 阈值=80',
    metricValue: '85.5',
    thresholdValue: '80',
    healthScore: '73',
    categoryName: '对外部客户',
    metricName: '/',
    processStatus: '未处理',
    systemName: 'CRM系统',
    subCategoryName: '/',
    sourceTable: '/',
    sourceId: '/',
  }
  let result = channelForm.value.template
  alarmTemplateFields.forEach(f => {
    const regex = new RegExp('\\{' + f.key + '}', 'g')
    result = result.replace(regex, sampleAlarm[f.key] || '/')
  })
  return result
})

// 插入字段到模板
const insertField = (field) => {
  channelForm.value.template = (channelForm.value.template || '') + field.template
}

// 场景选项
const alarmSceneOptions = [
  { value: 'system_health', label: '系统健康度', template: '[{alarmTime}] {systemName} 健康度:{healthScore}', fields: ['alarmTime', 'systemName', 'healthScore'] },
  { value: 'category_health', label: '大类健康度', template: '[{alarmTime}] {systemName}/{categoryName} 健康度:{healthScore}', fields: ['alarmTime', 'systemName', 'categoryName', 'healthScore'] },
  { value: 'sub_category_health', label: '小类健康度', template: '[{alarmTime}] {systemName}/{categoryName}/{subCategoryName} 健康度:{healthScore}', fields: ['alarmTime', 'systemName', 'categoryName', 'subCategoryName', 'healthScore'] },
  { value: 'metric_health', label: '指标健康度', template: '[{alarmTime}] {systemName}/{categoryName}/{subCategoryName}/{metricName} 健康度:{healthScore}', fields: ['alarmTime', 'systemName', 'categoryName', 'subCategoryName', 'metricName', 'healthScore'] },
  { value: 'metric_value', label: '指标值告警', template: '[{alarmTime}] {systemName}/{categoryName}/{metricName} 指标值:{metricValue}，阈值:{thresholdValue}', fields: ['alarmTime', 'systemName', 'categoryName', 'metricName', 'metricValue', 'thresholdValue'] },
  { value: 'raw_data', label: '原始数据告警', template: '[{alarmTime}] [{sourceTable}] {businessName} 指标值:{metricValue}', fields: ['alarmTime', 'sourceTable', 'businessName', 'metricValue'] },
  { value: 'issue_overdue', label: '问题逾期', template: '[{alarmTime}] {alarmContent}', fields: ['alarmTime', 'alarmContent'] },
  { value: 'custom', label: '自定义', template: '', fields: ['alarmTime', 'alarmType', 'alarmContent', 'metricValue', 'thresholdValue', 'healthScore', 'categoryName', 'metricName', 'processStatus', 'systemName', 'subCategoryName', 'sourceTable', 'sourceId'] },
]

// 当前场景可用的字段
const currentSceneFields = computed(() => {
  const scene = alarmSceneOptions.find(s => s.value === channelForm.value.scene)
  if (!scene) return alarmTemplateFields
  return alarmTemplateFields.filter(f => scene.fields.includes(f.key))
})

// 场景切换
const onSceneChange = (sceneValue) => {
  const scene = alarmSceneOptions.find(s => s.value === sceneValue)
  if (scene && sceneValue !== 'custom') {
    channelForm.value.template = scene.template
  }
}

// 策略子Tab
const strategySubTab = ref('strategy')

// 屏蔽管理相关
const shieldedAlarmList = ref([])
const shieldLoading = ref(false)
const shieldFilter = ref('effective')

// 监听策略子Tab切换
watch(strategySubTab, (newTab) => {
  if (newTab === 'shield') {
    loadShieldedAlarms()

  }
})

// 监听屏蔽筛选条件变化
watch(shieldFilter, () => {
  if (strategySubTab.value === 'shield') {
    loadShieldedAlarms()
  }
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

// 加载告警数据
const loadAlarmData = async () => {
  const version = ++summaryRequestVersion
  tableLoading.value = true
  try {
    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      ...sortParams.value
    }
    // 处理日期范围
    if (queryParams.value.alarmTimeRange && queryParams.value.alarmTimeRange.length === 2) {
      params.alarmTimeStart = queryParams.value.alarmTimeRange[0]
      params.alarmTimeEnd = queryParams.value.alarmTimeRange[1]
    }
    // 添加其他查询参数
    if (queryParams.value.systemId) params.systemId = queryParams.value.systemId
    if (queryParams.value.categoryId) params.categoryId = queryParams.value.categoryId
    if (queryParams.value.metricId) params.metricId = queryParams.value.metricId
    if (queryParams.value.alarmType) params.alarmType = queryParams.value.alarmType
    if (queryParams.value.processStatus !== null && queryParams.value.processStatus !== '') params.processStatus = queryParams.value.processStatus
    if (queryParams.value.shieldStatus !== null && queryParams.value.shieldStatus !== '') params.shieldStatus = queryParams.value.shieldStatus
    if (queryParams.value.alarmContent) params.alarmContent = queryParams.value.alarmContent
    const res = await alarmApi.summary(params)
    if (version !== summaryRequestVersion) return
    summaryScope.value = { ...params, snapshotId: res.data.snapshotId }
    alarmTypeOptions.value = [...new Set([...defaultAlarmTypes, ...(res.data.alarmTypes || []), queryParams.value.alarmType].filter(Boolean))]
    summaryTotalEvents.value = res.data.totalEvents || 0
    alarmList.value = (res.data.content || []).map(group => ({
      ...group.latest, ...group,
      processStatus: group.unprocessedCount > 0 ? 0 : group.processedCount === group.alarmCount ? 1 : group.ticketCount === group.alarmCount ? 3 : group.unprocessedCount + group.processedCount + group.ticketCount === 0 ? group.latest.processStatus : -1,
      shieldStatus: group.shieldedCount === group.alarmCount ? 1 : group.shieldedCount > 0 ? -1 : 0
    }))
    pagination.value.total = res.data.totalElements || 0
    selectedRows.value = []
    if (!alarmList.value.length && pagination.value.total && pagination.value.page > 1) { pagination.value.page = 1; await loadAlarmData() }
  } catch (error) {
    if (version === summaryRequestVersion) { alarmList.value = []; selectedRows.value = []; summaryScope.value = null; pagination.value.total = 0; summaryTotalEvents.value = 0 }
    console.error('加载告警列表失败:', error)
  } finally {
    if (version === summaryRequestVersion) tableLoading.value = false
  }
}

// 策略筛选条件
const strategyFilters = ref({
  id: '',
  name: '',
  type: '',
  targetType: '',
  categoryId: null,
  subCategoryId: null,
  alarmEnabled: null,
  gongdanEnabled: null
})

// 重置策略筛选条件
const resetStrategyFilters = () => {
  strategyFilters.value = {
    id: '',
    name: '',
    type: '',
    targetType: '',
    categoryId: null,
    subCategoryId: null,
    alarmEnabled: null,
    gongdanEnabled: null
  }
  loadStrategyData()
}

// 选了指标大类/小类才允许「按筛选全部启停」，避免误伤全量策略
const hasScopeFilter = computed(() => !!(strategyFilters.value.categoryId || strategyFilters.value.subCategoryId))
const filterBatchLoading = ref(false)

const strategyFilterParams = () => {
  const params = {}
  if (strategyFilters.value.id) params.id = strategyFilters.value.id
  if (strategyFilters.value.name) params.name = strategyFilters.value.name
  if (strategyFilters.value.type) params.strategyType = strategyFilters.value.type
  if (strategyFilters.value.targetType) params.targetType = strategyFilters.value.targetType
  if (strategyFilters.value.categoryId) params.categoryId = strategyFilters.value.categoryId
  if (strategyFilters.value.subCategoryId) params.subCategoryId = strategyFilters.value.subCategoryId
  if (strategyFilters.value.alarmEnabled !== null) params.alarmEnabled = strategyFilters.value.alarmEnabled
  if (strategyFilters.value.gongdanEnabled !== null) params.gongdanEnabled = strategyFilters.value.gongdanEnabled
  return params
}

// 策略分页
const strategyPagination = ref({
  page: 1,
  size: 20,
  total: 0
})

// 加载策略数据
const loadStrategyData = async () => {
  try {
    const params = {
      ...strategyFilterParams(),
      pageNum: strategyPagination.value.page,
      pageSize: strategyPagination.value.size
    }

    const res = await configApi.strategyFilter(params)
    strategyList.value = res.data.content || []
    strategyPagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载告警策略失败:', error)
  }
}

// 保存内联告警配置
const saveInlineAlarmConfig = async (row) => {
  try {
    await configApi.strategyUpdate(row.id, {
      alarmEnabled: row.alarmEnabled,
      alarmCondition: row.alarmCondition,
      alarmThreshold: row.alarmThreshold
    })
    ElMessage.success('告警配置已更新')
    loadStrategyData()
  } catch (error) {
    console.error('保存告警配置失败:', error)
    ElMessage.error('保存失败')
  }
}

// 保存内联工单配置
const saveInlineGongdanConfig = async (row) => {
  try {
    await configApi.strategyUpdate(row.id, {
      gongdanEnabled: row.gongdanEnabled,
      gongdanCondition: row.gongdanCondition,
      gongdanThreshold: row.gongdanThreshold
    })
    ElMessage.success('工单配置已更新')
    loadStrategyData()
  } catch (error) {
    console.error('保存工单配置失败:', error)
    ElMessage.error('保存失败')
  }
}

// 通道相关
const loadChannelData = async () => {
  try {
    const res = await alarmChannelApi.list()
    channelList.value = res.data || []
  } catch (error) {
    console.error('加载告警通道失败:', error)
  }
}

const loadChannelStats = async () => {
  try {
    const res = await alarmChannelApi.statistics()
    channelStats.value = res.data || {}
  } catch (error) {
    console.error('加载告警统计失败:', error)
  }
}

const loadPlugins = async () => {
  try {
    const res = await pluginConfigApi.list({ size: 1000 })
    pluginList.value = res.data?.content || []
  } catch (error) {
    console.error('加载插件列表失败:', error)
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
    const res = await alarmApi.getTrend(params)
    trendData.value = res.data || []
    nextTick(() => drawTrendChart())
  } catch (error) {
    console.error('加载趋势数据失败:', error)
  }
}

// 绘制趋势图
const drawTrendChart = () => {
  if (!trendChartDom.value) return

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
    // 使用 axisPointer 点击整个轴区域，而不仅是数据点
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
      trigger: 'axis'
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
        rotate: 0,
        interval: data.length > 12 ? 1 : 0
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
}

// 图表点击下钻
const handleChartClick = (params) => {
  const { name } = params
  if (!name) return

  // 后端返回完整日期格式
  // today: "2026-04-09 16:00", week/month: "2026-04-09", year: "2026-04"
  if (trendDimension.value === 'today') {
    // today 维度: name 现在是 "04:00" 格式，需要拼接今天日期
    const today = new Date().toISOString().substring(0, 10)  // "2026-04-13"
    const fullTime = `${today} ${name}:00`  // "2026-04-13 04:00:00"
    queryParams.value.alarmTimeRange = [fullTime, fullTime.substring(0, 13) + ':59:59']
  } else if (trendDimension.value === 'year') {
    // year 维度扩展为月份范围 "2026-04" -> "2026-04-01" 到 "2026-04-30/31"
    const [year, month] = name.split('-')
    const lastDay = new Date(parseInt(year), parseInt(month), 0).getDate()
    queryParams.value.alarmTimeRange = [`${name}-01`, `${name}-${String(lastDay).padStart(2, '0')}`]
  } else {
    queryParams.value.alarmTimeRange = [name, name]
  }
  chartDrillDownLabel.value = name
  loadAlarmData()
}

// 清除下钻筛选
const clearDrillDown = () => {
  chartDrillDownLabel.value = ''
  queryParams.value.alarmTimeRange = null
  loadAlarmData()
}

// 获取空数据提示文本
const getEmptyText = () => {
  const dimensionMap = {
    'today': '今天没有告警',
    'week': '本周没有告警',
    'month': '本月没有告警',
    'year': '今年没有告警'
  }
  return dimensionMap[trendDimension.value] || '暂无数据'
}

// 监听窗口resize
const handleResize = () => {
  if (trendChart.value) {
    trendChart.value.resize()
  }
}

const handleChannelAdd = () => {
  channelForm.value = {
    id: null, channelName: '', channelType: 'FILE', configKey: '', configValue: '', scene: '', template: '', description: '', enabled: 1
  }
  channelDialogVisible.value = true
}

const handleChannelEdit = (row) => {
  channelForm.value = { ...row }
  channelDialogVisible.value = true
}

const handleChannelDelete = (row) => {
  ElMessageBox.confirm('确定删除该通道吗?', '提示', { type: 'warning' })
    .then(async () => {
      await alarmChannelApi.delete(row.id)
      ElMessage.success('删除成功')
      loadChannelData()
    }).catch(() => {})
}

const handleChannelToggle = async (row) => {
  try {
    await alarmChannelApi.toggle(row.id, row.enabled)
    ElMessage.success(row.enabled === 1 ? '已启用' : '已禁用')
  } catch (error) {
    console.error('切换状态失败:', error)
    loadChannelData()
  }
}

const saveChannel = async () => {
  try {
    if (channelForm.value.id) {
      await alarmChannelApi.update(channelForm.value.id, channelForm.value)
    } else {
      await alarmChannelApi.create(channelForm.value)
    }
    ElMessage.success('保存成功')
    channelDialogVisible.value = false
    loadChannelData()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

const onChannelTypeChange = () => {
  channelForm.value.configValue = ''
}

const getPluginName = (pluginId) => {
  if (!pluginId) return ''
  const plugin = pluginList.value.find(p => String(p.id) === String(pluginId))
  return plugin ? plugin.name : `插件 #${pluginId}`
}

// 处理告警
const handleProcess = async (row) => {
  try {
    if (row.groupKey) {
      await ElMessageBox.confirm(`处理本组当前筛选范围内的 ${row.unprocessedCount} 次未处理记录？`, '处理告警组', { type: 'warning' })
      await alarmApi.groupAction({ ...summaryScope.value, groups: groupSelections([row]), action: 'process', newProcessStatus: 1 })
    } else {
      await alarmApi.process(row.id, { processStatus: row.processStatus === 1 ? 0 : 1 })
    }
    refreshOccurrences()
    ElMessage.success('处理成功')
    loadAlarmData()
    loadChannelStats()
    // 触发更新顶部铃铛数字
    window.dispatchEvent(new Event('alarm-count-update'))
  } catch (error) {
    console.error('处理失败:', error)
  }
}

// 屏蔽
const handleShield = (row) => {
  shieldForm.value = { id: row.id, shieldStatus: row.shieldStatus === 1 ? 0 : 1, shieldEndTime: null,
    groups: row.groupKey ? groupSelections([row]) : null, scope: row.groupKey ? { ...summaryScope.value } : null, alarmCount: row.alarmCount || 1 }
  shieldDialogVisible.value = true
}

// 阈值设置
const handleThreshold = async (row, selectedId = null) => {
  if (row.groupKey) {
    if (!row.targetId) { ElMessage.warning('缺少指标策略定位，请到对应配置页修改'); return }
    if (!selectedId && row.strategyChoices?.length > 1) {
      strategyPickerRow.value = row; pickedStrategyId.value = null; strategyPickerVisible.value = true; return
    }
    const id = selectedId || row.strategyChoices?.[0]?.id
    try {
      if (id) {
        const res = await configApi.strategyGet(id)
        if (!res.data) throw new Error('策略已不存在，请刷新列表')
        strategySeed.value = { ...res.data }
      } else {
        strategySeed.value = { strategyType: row.strategyType, targetType: row.targetType, targetId: row.targetId,
          targetName: row.targetName, strategyName: `${row.targetName || '告警对象'}-${row.strategyType === 'health' ? '健康度' : '指标值'}-告警策略` }
      }
      strategyDialogVisible.value = true
    } catch (error) { ElMessage.error(error.message || '读取策略失败，请刷新列表') }
    return
  }
  if (!row.strategyId) { ElMessage.warning('此历史记录未关联策略，请从汇总列表设置'); return }
  try { strategySeed.value = { ...(await configApi.strategyGet(row.strategyId)).data }; strategyDialogVisible.value = true }
  catch (error) { ElMessage.error('读取策略失败，请刷新列表') }
}

// 运行阈值模拟
const runSimulation = async () => {
  if (!thresholdForm.value.metricId) {
    ElMessage.warning('请先选择指标')
    return
  }
  if (!simulateForm.value.threshold) {
    ElMessage.warning('请输入阈值')
    return
  }

  simulateLoading.value = true
  try {
    const params = {
      metricId: thresholdForm.value.metricId,
      threshold: simulateForm.value.threshold,
      condition: simulateForm.value.condition
    }
    if (simulateForm.value.timeRange && simulateForm.value.timeRange.length === 2) {
      params.startTime = simulateForm.value.timeRange[0] + ' 00:00:00'
      params.endTime = simulateForm.value.timeRange[1] + ' 23:59:59'
    }
    const res = await realtimeApi.simulateThreshold(params)
    if (res.data.error) {
      ElMessage.error(res.data.error)
      return
    }
    simulateResult.value = res.data || {}
    nextTick(() => drawSimulateChart())
  } catch (error) {
    console.error('模拟失败:', error)
    ElMessage.error('模拟失败')
  } finally {
    simulateLoading.value = false
  }
}

// 绘制模拟图表
const drawSimulateChart = () => {
  if (!simulateChartDom.value || !simulateResult.value.data) return

  const data = simulateResult.value.data || []
  if (data.length === 0) return

  if (!simulateChart.value) {
    simulateChart.value = echarts.init(simulateChartDom.value)
  }

  const chartTheme = readWorkspaceChartTheme(simulateChartDom.value)
  const option = {
    backgroundColor: chartTheme.surface,
    textStyle: { color: chartTheme.text },
    tooltip: {
      trigger: 'axis'
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '10%',
      top: '30px',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: data.map(d => d.time.substring(11, 16)),  // 只显示小时部分
      axisLabel: {
        fontSize: 10,
        rotate: 0,
        interval: data.length > 12 ? 1 : 0
      }
    },
    yAxis: {
      type: 'value',
      min: 0,
      splitNumber: 4,
      name: '告警数'
    },
    series: [{
      type: 'bar',
      data: data.map(d => d.count),
      itemStyle: {
        color: chartTheme.danger
      },
      label: {
        show: true,
        position: 'top',
        fontSize: 10,
        formatter: '{c}'
      }
    }]
  }

  simulateChart.value.setOption(option)
}

const saveShield = async () => {
  try {
    if (shieldForm.value.groups) {
      await alarmApi.groupAction({ ...shieldForm.value.scope, groups: shieldForm.value.groups, action: 'shield',
        newShieldStatus: shieldForm.value.shieldStatus, shieldEndTime: shieldForm.value.shieldEndTime || null })
    } else {
      await alarmApi.shield(shieldForm.value.id, { shieldStatus: shieldForm.value.shieldStatus, shieldEndTime: shieldForm.value.shieldEndTime })
    }
    refreshOccurrences()
    ElMessage.success('保存成功')
    shieldDialogVisible.value = false
    loadAlarmData()
    loadChannelStats()   // 刷新统计卡片
    loadTrendData()      // 刷新趋势图
  } catch (error) {
    console.error('保存失败:', error)
  }
}

// 屏蔽管理
// 加载已屏蔽的告警列表
const loadShieldedAlarms = async () => {
  shieldLoading.value = true
  try {
    const res = await alarmApi.list({
      shieldStatus: 1,
      page: 0,
      size: 1000
    })
    let list = res.data.content || []
    // 根据筛选条件过滤
    const now = new Date()
    if (shieldFilter.value === 'effective') {
      list = list.filter(item => !item.shieldEndTime || new Date(item.shieldEndTime) > now)
    } else if (shieldFilter.value === 'expired') {
      list = list.filter(item => item.shieldEndTime && new Date(item.shieldEndTime) <= now)
    }
    shieldedAlarmList.value = list
  } catch (error) {
    console.error('加载屏蔽列表失败:', error)
  } finally {
    shieldLoading.value = false
  }
}

// 判断屏蔽是否已到期
const isShieldExpired = (row) => {
  if (!row.shieldEndTime) return false
  return new Date(row.shieldEndTime) <= new Date()
}

// 解除屏蔽
const handleRemoveShield = (row) => {
  ElMessageBox.confirm('确定解除该告警的屏蔽状态吗？解除后将恢复正常告警。', '解除屏蔽确认', {
    type: 'warning'
  }).then(async () => {
    try {
      await alarmApi.shield(row.id, { shieldStatus: 0, shieldEndTime: null })
      ElMessage.success('解除屏蔽成功')
      loadShieldedAlarms()
      loadAlarmData()
    } catch (error) {
      console.error('解除屏蔽失败:', error)
      ElMessage.error('解除屏蔽失败')
    }
  }).catch(() => {})
}

// 打标
// 重置告警筛选
const resetAlarmFilters = () => {
  quickFilterLabel.value = ''
  queryParams.value = {
    processStatus: null,
    alarmType: null,
    shieldStatus: null,
    alarmTimeRange: null,
    alarmContent: '',
    systemId: null,
    categoryId: null,
    metricId: null
  }
  pagination.value.page = 1
  loadAlarmData()
}

// 统计卡片点击：按时间范围筛选告警
const quickFilterLabel = ref('')
const quickFilterResult = ref(null)
const showQuickFilterResult = async (label) => {
  quickFilterLabel.value = label
  await nextTick()
  quickFilterResult.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

const filterAlarmByTimeRange = (range) => {
  const now = new Date()
  const fmt = (d) => d.toISOString().substring(0, 10)
  if (range === 'today') {
    queryParams.value.alarmTimeRange = [fmt(now), fmt(now)]
  } else if (range === 'week') {
    const weekAgo = new Date(now.getTime() - 7 * 86400000)
    queryParams.value.alarmTimeRange = [fmt(weekAgo), fmt(now)]
  } else {
    queryParams.value.alarmTimeRange = null
  }
  queryParams.value.processStatus = null
  queryParams.value.shieldStatus = null
  pagination.value.page = 1
  showQuickFilterResult(({ all: '全部告警', today: '今日告警', week: '本周告警' })[range])
  loadAlarmData()
}

// 统计卡片的处理状态筛选，与列表相同的 processStatus 参数。
const filterAlarmByProcessStatus = (status) => {
  queryParams.value.processStatus = status === -1 ? null : status
  queryParams.value.alarmTimeRange = null
  queryParams.value.shieldStatus = null
  pagination.value.page = 1
  showQuickFilterResult(status === -1 ? '全部处理状态' : status === 0 ? '未处理告警' : '已处理告警')
  loadAlarmData()
}

// 查看已转问题详情
const handleViewRelatedIssue = async (row) => {
  if (!row.relatedIssueId) {
    ElMessage.warning('该告警未关联问题')
    return
  }
  try {
    const res = await issueApi.get(row.relatedIssueId)
    issueDetailData.value = res.data
    issueDetailDialogVisible.value = true
  } catch (error) {
    console.error('获取问题详情失败:', error)
    ElMessage.error('获取问题详情失败')
  }
}

// 从详情弹窗编辑问题
const handleEditIssueFromDetail = () => {
  if (issueDetailData.value) {
    issueForm.value = { ...issueDetailData.value }
    issueCurrentEditId.value = issueDetailData.value.id
    issueDetailDialogVisible.value = false
    issueDialogVisible.value = true
  }
}

// 保存问题编辑
const saveIssueData = async () => {
  try {
    if (issueForm.value.id) {
      await issueApi.update(issueForm.value.id, issueForm.value)
      ElMessage.success('保存成功')
      issueDialogVisible.value = false
    } else {
      await issueApi.create(issueForm.value)
      ElMessage.success('保存成功')
      issueDialogVisible.value = false
    }
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
  }
}

// 上传问题附件
const handleIssueUploadAttachment = async (file) => {
  if (!issueCurrentEditId.value) {
    ElMessage.warning('请先保存问题后再上传附件')
    return
  }
  try {
    const res = await issueApi.uploadAttachment(issueCurrentEditId.value, file.raw || file)
    issueForm.value.attachmentName = res.data.attachmentName
    issueForm.value.attachmentPath = res.data.attachmentPath
    ElMessage.success('附件上传成功')
  } catch (error) {
    console.error('附件上传失败:', error)
    ElMessage.error('附件上传失败')
  }
}

// 删除问题附件
const handleIssueDeleteAttachment = async () => {
  if (!issueCurrentEditId.value) return
  try {
    await issueApi.deleteAttachment(issueCurrentEditId.value)
    issueForm.value.attachmentName = null
    issueForm.value.attachmentPath = null
    ElMessage.success('附件删除成功')
  } catch (error) {
    console.error('附件删除失败:', error)
    ElMessage.error('附件删除失败')
  }
}

// 策略操作
// 策略编辑：表单本身由公共组件 components/alarm/StrategyEditorDialog.vue 渲染。
// 本页只负责两件事：① 打开弹窗时准备 initial 预填数据；② 保存成功后刷新列表。
// 这样后续给策略加字段（如告警等级）只改组件一处，本页与「指标体系配置」页同时生效。

// 新增策略：不给 initial，组件按空表单处理
const handleStrategyAdd = () => {
  strategySeed.value = null
  strategyDialogVisible.value = true
}

// 编辑策略：row 本身已是扁平字段（alarmEnabled/alarmThreshold/...），直接交给组件
const handleStrategyEdit = (row) => {
  strategySeed.value = { ...row }
  strategyDialogVisible.value = true
}

// 复制策略：去掉 id、改名称，其余配置照搬
const handleStrategyCopy = (row) => {
  strategySeed.value = {
    ...row,
    id: null,
    strategyName: `${row.strategyName} (副本)`,
    status: 1
  }
  strategyDialogVisible.value = true
}

const onStrategySaved = () => {
  refreshOccurrences()
  loadAlarmData()
  loadStrategyData()
}

const saveStrategyStatus = async (row) => {
  try {
    await configApi.strategyUpdate(row.id, { status: row.status })
    ElMessage.success('状态更新成功')
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('状态更新失败')
    loadStrategyData()
  }
}

// 加载系统列表
const loadSystems = async () => {
  try {
    const res = await systemApi.listAll()
    systemList.value = res.data || []
  } catch (error) {
    console.error('加载系统列表失败:', error)
  }
}

// 加载指标大类列表
const loadCategories = async () => {
  try {
    const res = await metricApi.categoryListAll()
    categoryList.value = res.data || []
  } catch (error) {
    console.error('加载指标大类列表失败:', error)
  }
}

// 系统变化时加载对应的大类
const onSystemChange = () => {
  queryParams.value.categoryId = null
  queryParams.value.metricId = null
  pagination.value.page = 1
  loadCategories()
  loadAlarmData()
}

const onAlarmCategoryChange = (categoryId) => {
  const category = categoryList.value.find(item => item.id === categoryId)
  if (category) queryParams.value.systemId = category.systemId
  queryParams.value.metricId = null
  pagination.value.page = 1
  loadAlarmData()
}


// 加载指标列表
const loadMetrics = async () => {
  try {
    const res = await metricApi.itemListAll()
    metricList.value = res.data || []
  } catch (e) {
    console.error('加载指标列表失败', e)
  }
}

// 加载小类列表
const loadSubCategories = async (categoryId) => {
  try {
    const res = await subcategoryApi.list(categoryId)
    subCategoryList.value = res.data || []
  } catch (e) {
    console.error('加载小类列表失败', e)
  }
}


onMounted(() => {
  loadPreferences()
  loadSystems()
  loadCategories()
  loadAlarmSwitchStatus()
  // 检查URL查询参数，如果有processStatus则设置筛选条件并加载数据
  if (route.query.processStatus !== undefined) {
    queryParams.value.processStatus = Number(route.query.processStatus)
    loadAlarmData()
    // 清除查询参数，避免刷新时重复加载
    router.replace({ path: '/alarm' })
  } else {
    loadAlarmData()
  }
  loadStrategyData()
  loadChannelData()
  loadChannelStats()
  loadPlugins()
  loadTrendData()
  loadScanErrors()
  window.addEventListener('resize', handleResize)
  watch(() => route.query, (newQuery) => {
    if (newQuery.processStatus !== undefined) {
      queryParams.value.processStatus = Number(newQuery.processStatus)
      loadAlarmData()
      router.replace({ path: '/alarm' })
    }
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (trendChart.value) {
    trendChart.value.dispose()
    trendChart.value = null
  }
})
</script>

<style scoped>
.group-actions { display: flex; gap: 14px; align-items: center; white-space: nowrap; }
.group-actions .el-button { margin: 0; justify-content: flex-start; }
.group-actions .el-dropdown { justify-self: start; }
.summary-caption { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 4px 0 14px; font-size: 12px; color: var(--ui-muted); }
.summary-caption strong { color: var(--ui-text); font-weight: 500; }.summary-caption small { margin-left: auto; font-size: 12px; }
.occurrence-count { font-size: 18px; font-weight: 600; }.occurrence-count span { margin-left: 5px; font-size: 11px; font-weight: 400; }
.latest-content { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.7; }

.alarm-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
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

/* 桌面端表格默认显示 */
.desktop-table {
  display: block;
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

  .alarm-card {
    margin-bottom: 12px;
  }

  .alarm-card .card-content {
    font-size: 13px;
  }

  .alarm-card .card-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 0;
  }

  .alarm-card .card-row .time {
    color: var(--ui-muted);
    font-size: 12px;
  }

  .alarm-card .card-row .label {
    color: var(--ui-muted);
    flex-shrink: 0;
  }

  .alarm-card .card-row .value {
    color: var(--ui-heading);
  }

  .alarm-card .card-desc {
    padding: 6px 0;
  }

  .alarm-card .card-desc .label {
    color: var(--ui-muted);
    display: block;
    margin-bottom: 4px;
  }

  .alarm-card .card-desc p {
    margin: 0;
    color: var(--ui-heading);
    font-size: 13px;
  }

  .alarm-card .card-footer {
    margin-top: 12px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
}

/* 筛选工具栏 */

/* 表格工具栏 */
.table-toolbar {
  margin-bottom: 15px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.toolbar-left {
  display: flex;
  gap: 10px;
}

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

/* 统计卡片 */

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
  display: flex;
  flex-direction: column;
}

.trend-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
  gap: 8px;
}

.trend-tabs :deep(.el-radio-button__inner) {
  padding: 5px 10px;
  font-size: 12px;
}

.trend-filters {
  display: flex;
  gap: 8px;
  align-items: center;
}

.drill-down-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ui-warning);
  margin-bottom: 6px;
}

.drill-down-tip .el-button {
  margin-left: 4px;
}

.trend-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 160px;
  color: var(--ui-muted);
  gap: 8px;
}

.trend-chart-wrapper {
  position: relative;
  min-height: 160px;
}

.trend-chart {
  width: 100%;
  height: 160px;
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

/* 告警开关内联样式 */
.switch-inline {
  display: flex;
  align-items: center;
}

/* 已屏蔽行样式 */
:deep(.el-table .row-shielded) {
  background-color: #f5f5f5 !important;
}

:deep(.el-table .row-shielded td) {
  color: #999 !important;
}

:deep(.el-table .row-shielded:hover > td) {
  background-color: #ebebeb !important;
}

/* 屏蔽管理工具栏 */
.shield-toolbar {
  margin-bottom: 12px;
}

/* 模拟结果 */
.simulate-result {
  margin-top: 16px;
}

.simulate-stat {
  background: var(--ui-page);
  border-radius: 6px;
  padding: 12px;
  text-align: center;
}

.simulate-stat-num {
  font-size: 18px;
  font-weight: bold;
  color: var(--ui-danger);
}

.simulate-stat-label {
  font-size: 12px;
  color: var(--ui-muted);
  margin-top: 4px;
}

.simulate-chart {
  width: 100%;
  height: 200px;
  margin-top: 16px;
}

/* 策略筛选工具栏 */

/* 内联编辑表单 */
.config-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.config-cell:hover {
  color: var(--el-color-primary);
}

.inline-edit-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.inline-edit-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inline-edit-label {
  width: 40px;
  font-size: 12px;
  color: #666;
}

.condition-buttons {
  display: flex;
  gap: 4px;
}

/* 告警模板编辑 */
.template-editor {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 12px;
  background: var(--ui-page);
}

.template-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.template-field-tag {
  display: inline-block;
  padding: 4px 10px;
  background: var(--ui-surface);
  border: 1px solid var(--ui-primary);
  border-radius: 4px;
  color: var(--ui-primary);
  font-size: 12px;
  cursor: pointer;
}

.template-field-tag:hover {
  background: var(--ui-primary);
  color: var(--ui-surface);
}

.template-preview {
  margin-top: 8px;
  padding: 8px;
  background: var(--ui-surface);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.preview-label {
  color: var(--ui-muted);
  font-weight: bold;
}

/* 通道路径：上行是配置值，下行是后端解析出的实际写入位置 */
.channel-path { display: flex; flex-direction: column; gap: 2px; }
.channel-path small { color: var(--ui-muted); font-size: 12px; word-break: break-all; }
</style>

<style scoped src="@/views/alarm/prototype-theme.css"></style>
