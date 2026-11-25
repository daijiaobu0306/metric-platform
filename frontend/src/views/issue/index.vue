<template>
  <div class="issue-page workspace-theme workspace-management">
    <WorkspacePageHeader title="问题管理" description="统一登记与跟踪问题，关联告警、核查原因及处理附件，推动问题解决与闭环。" />
    <el-tabs v-model="activeTab" class="workspace-tabs management-tabs">
      <!-- 问题列表 Tab -->
      <el-tab-pane label="问题列表" name="list"><template #label><WorkspaceTabLabel icon="List" :count="issueStats.totalIssueCount">问题列表</WorkspaceTabLabel></template>
        <el-card class="issue-shell" shadow="never">
          <template #header>
            <div class="card-header">
              <span>问题列表</span>
              <div class="header-actions">
                <el-popover popper-class="workspace-theme workspace-popover" placement="bottom" :width="300" trigger="click">
                  <template #reference>
                    <el-button :type="notificationEnabled ? 'warning' : 'info'" size="small">
                      <span class="reminder-status-dot" :class="{ enabled: notificationEnabled }"></span><el-icon><Bell /></el-icon>
                      {{ notificationEnabled ? '逾期提醒已开启' : '逾期提醒已关闭' }}
                    </el-button>
                  </template>
              <div class="notification-settings">
                <div class="setting-item">
                  <span>开启逾期提醒：</span>
                  <el-switch v-model="notificationEnabled" @change="saveNotificationSettings" />
                </div>
                <div class="setting-item" v-if="notificationEnabled">
                  <span>通知时间：</span>
                  <el-time-select
                    v-model="notificationTime"
                    start="00:00"
                    step="00:30"
                    end="23:30"
                    placeholder="选择时间"
                    style="width: 120px;"
                    @change="saveNotificationSettings"
                  />
                </div>
                <div class="setting-item" style="margin-top: 10px;">
                  <el-button type="warning" size="small" @click="handleGenerateAlerts">
                    立即生成逾期告警
                  </el-button>
                </div>
              </div>
            </el-popover>
            <el-button type="primary" @click="handleAdd">
              <el-icon><Plus /></el-icon>登记问题
            </el-button>
          </div>
        </div>
      </template>

      <!-- 统计面板和趋势图 -->

      <div ref="issueInsightsRoot" class="issue-insights-shell">
        <WorkspaceOverviewLayout with-aside>
          <template #stats><IssueOverviewCards :stats="issueStats" @time-range="filterIssueByRange" @resolve-status="filterIssueByResolve" /></template>
          <template #trend>
        <!-- 中间趋势图 -->
        <div class="issue-insights-trend">
          <div class="trend-area"><WorkspaceSectionHeading class="insight-title" title="问题发生与流转趋势" description="问题登记走势，点击采样点可下钻筛选对应时段" icon="TrendCharts" />
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
                <el-select v-model="trendType" size="small" style="width: 80px;" @change="loadTrendData">
                  <el-option label="全部" value="all" />
                  <el-option label="未解决" value="未解决" />
                  <el-option label="已解决" value="已解决" />
                </el-select>
                <el-date-picker
                  v-model="trendTimeRange"
                  type="daterange"
                  range-separator="至"
                  start-placeholder="开始"
                  end-placeholder="结束"
                  value-format="YYYY-MM-DD"
                  size="small"
                  style="width: 150px;"
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
              <div id="issue-trend-chart" ref="trendChartDom" class="trend-chart"></div>
              <div v-if="trendData.length === 0" class="trend-empty-overlay">
                <el-icon size="20" color="#909399"><WarningFilled /></el-icon>
                <span>{{ getEmptyText() }}</span>
              </div>
            </div>
          </div>
        </div>
          </template>
          <template #aside>
        <!-- 右侧发现者统计看板 -->
        <div class="issue-insights-discoverers">
          <IssueDiscovererBoard v-model:range="discovererTimeRange" :discoverer-stats="discovererStats" @change="loadDiscovererStats" @drill="drillByDiscoverer" />
        </div>
          </template>
        </WorkspaceOverviewLayout>
      </div>

      <!-- 多维度筛选工具栏 -->
      <IssueFilters v-model:filters="queryParams" :system-list="systemList" :category-list="categoryList" :metric-list="metricList" @system-change="onSystemChange" @category-change="onCategoryChange" @filter-change="handleFilterChange" @search="loadData" @reset="resetIssueFilters" />
      <!-- 表格工具栏 -->
      <div class="table-toolbar">
        <div class="toolbar-left">
          <el-button type="danger" size="small" :disabled="selectedRows.length === 0" @click="handleBatchDelete">
            <el-icon><Delete /></el-icon>删除
          </el-button>
          <el-button type="info" size="small" @click="handleImport">
            <el-icon><Upload /></el-icon>导入Excel
          </el-button>
          <el-button type="success" size="small" @click="handleExport">
            <el-icon><Download /></el-icon>导出Excel
          </el-button>
          <el-button size="small" @click="handleDownloadTemplate">
            <el-icon><Document /></el-icon>下载模板
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

      <!-- 桌面端表格 -->
      <div class="desktop-table">
        <el-table v-sticky-header v-mirror-scroll :data="tableData" v-loading="tableLoading" border :fit="true" style="width: 100%;" @sort-change="handleSortChange" @selection-change="handleSelectionChange" @header-contextmenu="handleHeaderContextMenu"><template #empty><WorkspaceEmptyState title="暂无匹配的问题" description="可调整筛选条件，或使用登记问题创建记录。" /></template>
          <el-table-column type="selection" width="40" fixed="left"/>
          <el-table-column v-if="!isColumnHidden('registerTime')" prop="registerTime" label="登记时间" width="140" sortable="custom">
            <template #default="{ row }">{{ formatDateTime(row.registerTime) }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('updateTime')" prop="updateTime" label="修改时间" width="140" sortable="custom">
            <template #default="{ row }">{{ formatDateTime(row.modifyTime) }}</template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('issueType')" prop="issueType" label="问题类型" width="60" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.issueType" @save="(val) => saveCell(row, 'issueType', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('systemName')" prop="systemName" label="系统" width="100" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.systemName" placeholder="点击编辑系统" @save="(val) => saveCell(row, 'systemName', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('categoryName')" prop="categoryName" label="指标大类" width="100" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.categoryName" placeholder="点击编辑大类" @save="(val) => saveCell(row, 'categoryName', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('subCategoryName')" prop="subCategoryName" label="指标小类" width="80" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.subCategoryName" placeholder="点击编辑小类" @save="(val) => saveCell(row, 'subCategoryName', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('metricName')" prop="metricName" label="指标" width="100" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.metricName" placeholder="点击编辑指标" @save="(val) => saveCell(row, 'metricName', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('issueDescription')" prop="issueDescription" min-width="150" class-name="wrap-cell">
            <template #header>
              <span>问题描述</span>
              <el-icon class="col-expand-toggle" @click.stop="toggleColumnExpand('issueDescription')">
                <component :is="expandedColumns['issueDescription'] ? ArrowUp : ArrowDown" />
              </el-icon>
            </template>
            <template #default="{ row }">
              <editable-cell :value="row.issueDescription" :force-expanded="expandedColumns['issueDescription']" @save="(val) => saveCell(row, 'issueDescription', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('issueAttribute')" prop="issueAttribute" label="问题属性" width="60" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.issueAttribute" @save="(val) => saveCell(row, 'issueAttribute', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('issueSource')" prop="issueSource" label="问题来源" width="70" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.issueSource" @save="(val) => saveCell(row, 'issueSource', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('discoverer')" prop="discoverer" label="发现者" width="60" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.discoverer" @save="(val) => saveCell(row, 'discoverer', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('responsiblePerson')" prop="responsiblePerson" label="责任人" width="60" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.responsiblePerson" @save="(val) => saveCell(row, 'responsiblePerson', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('checkReason')" prop="checkReason" width="80" class-name="wrap-cell">
            <template #header>
              <span>核查原因</span>
              <el-icon class="col-expand-toggle" @click.stop="toggleColumnExpand('checkReason')">
                <component :is="expandedColumns['checkReason'] ? ArrowUp : ArrowDown" />
              </el-icon>
            </template>
            <template #default="{ row }">
              <editable-cell :value="row.checkReason" :force-expanded="expandedColumns['checkReason']" @save="(val) => saveCell(row, 'checkReason', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('planResolveTime')" prop="planResolveTime" label="预计解决时间" width="140" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.planResolveTime" type="datetime" placeholder="选择时间" @save="(val) => saveCell(row, 'planResolveTime', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('resolveStatus')" prop="resolveStatus" label="解决状态" width="60" align="center">
            <template #default="{ row }">
              <el-checkbox :model-value="row.resolveStatus === '已解决'" @change="toggleResolveStatus(row)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('postEvaluation')" prop="postEvaluation" label="后评估" width="80" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.postEvaluation" @save="(val) => saveCell(row, 'postEvaluation', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('tagStatus')" prop="tagStatus" label="是否关注" width="60" class-name="wrap-cell">
            <template #default="{ row }">
              <el-tag
                :type="row.tagStatus === 1 ? 'success' : 'info'"
                size="small"
                style="cursor: pointer;"
                @click="toggleTagStatus(row)"
              >
                {{ row.tagStatus === 1 ? '关注' : '未关注' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('attachment')" prop="attachmentName" label="附件" width="50" align="center">
            <template #default="{ row }">
              <el-tooltip v-if="row.attachmentName" :content="row.attachmentName" placement="top">
                <el-icon color="#67c23a" size="18" @click="downloadAttachment(row)" style="cursor: pointer;">
                  <Paperclip />
                </el-icon>
              </el-tooltip>
              <el-icon v-else color="#909399" size="18">
                <Paperclip />
              </el-icon>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="handleViewDetail(row)">
                <el-icon><View /></el-icon>
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 移动端卡片 -->
      <div class="mobile-cards">
        <el-card v-for="row in tableData" :key="row.id" class="issue-card" shadow="hover">
          <div class="card-content">
            <div class="card-row">
              <el-tag :type="row.resolveStatus === '已解决' ? 'success' : 'danger'" size="small">
                {{ row.resolveStatus }}
              </el-tag>
              <span class="time">{{ formatDateTime(row.registerTime) }}</span>
            </div>
            <div class="card-row">
              <span class="label">问题编号：</span>
            </div>
            <div class="card-row">
              <span class="label">问题类型：</span>
              <span class="value">{{ row.issueType }}</span>
            </div>
            <div class="card-row">
              <span class="label">问题来源：</span>
              <span class="value">{{ row.issueSource }}</span>
            </div>
            <div class="card-row">
              <span class="label">责任人：</span>
              <span class="value">{{ row.responsiblePerson }}</span>
            </div>
            <div class="card-desc">
              <span class="label">问题描述：</span>
              <p>{{ row.issueDescription }}</p>
            </div>
          </div>
          <div class="card-footer">
            <el-button type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="success" size="small" @click="handleResolve(row)" v-if="row.resolveStatus === '未解决'">解决</el-button>
          </div>
        </el-card>
        <el-empty v-if="tableData.length === 0" description="暂无数据" />
      </div>

      <WorkspacePagination v-model:page="pagination.page" v-model:size="pagination.size" :total="pagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadData" />
        </el-card>
      </el-tab-pane>

      <!-- 操作日志 Tab -->
      <el-tab-pane label="操作日志" name="auditLog"><template #label><WorkspaceTabLabel icon="Clock">操作日志</WorkspaceTabLabel></template>
        <el-card>
          <IssueAuditLog ref="operationLogRef" entity-type="ISSUE" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 问题详情对话框 -->
    <div style="position: relative;">
      <IssueDetailDialog v-model="detailDialogVisible" :width="issueDetailWidth" :detail-data="detailData" @edit="handleEditFromDetail" @download="downloadAttachment" />
    <DialogSizeControls :adjustWidth="adjustIssueDetailWidth" :visible="detailDialogVisible" />
    </div>

    <!-- 问题对话框 -->
    <div style="position: relative;">
      <el-dialog v-model="dialogVisible" :width="issueDialogWidth" @opened="onIssueDialogOpened" custom-class="issue-form-dialog" :lock-scroll="false">
      <template #header>
        <div class="issue-dialog-header">
          <span class="issue-dialog-title">{{ form.id ? '编辑问题' : '登记问题' }}</span>
          <div class="issue-dialog-actions">
            <el-button size="small" @click="dialogVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveData">确定</el-button>
          </div>
        </div>
      </template>
      <el-form :model="form" :rules="formRules" ref="formRef" label-width="100px">
        <!-- 分区 1: 关联信息 -->
        <div class="form-section">
          <div class="section-header">
            <span class="section-bar"></span>
            <span class="section-title">关联信息</span>
          </div>
          <div class="section-divider"></div>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="系统" prop="systemName">
                <template #label>
                  <span>系统</span>
                  <el-tooltip content="输入后可从下拉选择历史值，或手动输入新名称" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-autocomplete
                  v-model="form.systemName"
                  :fetch-suggestions="querySystemSuggestions"
                  @select="handleSystemSelect"
                  :trigger-on-focus="true"
                  value-key="value"
                  placeholder="系统名称"
                  clearable
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="指标大类" prop="categoryName">
                <template #label>
                  <span>指标大类</span>
                  <el-tooltip content="输入后可从下拉选择历史值，或手动输入新名称" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-autocomplete
                  v-model="form.categoryName"
                  :fetch-suggestions="queryCategorySuggestions"
                  @select="handleCategorySelect"
                  :trigger-on-focus="true"
                  value-key="value"
                  placeholder="大类名称"
                  clearable
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="指标小类" prop="subCategoryName">
                <template #label>
                  <span>指标小类</span>
                  <el-tooltip content="输入后可从下拉选择历史值，或手动输入新名称" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-autocomplete
                  v-model="form.subCategoryName"
                  :fetch-suggestions="querySubCategorySuggestions"
                  @select="handleSubCategorySelect"
                  :trigger-on-focus="true"
                  value-key="value"
                  placeholder="小类名称"
                  clearable
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="指标" prop="metricName">
                <template #label>
                  <span>指标</span>
                  <el-tooltip content="输入后可从下拉选择历史值，或手动输入新名称" placement="top">
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input v-model="form.metricName" placeholder="指标名称" clearable />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <!-- 分区 2: 问题分类 -->
        <div class="form-section">
          <div class="section-header">
            <span class="section-bar"></span>
            <span class="section-title">问题分类</span>
          </div>
          <div class="section-divider"></div>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="问题类型" prop="issueType">
                <el-select v-model="form.issueType" placeholder="故障 / 缺陷 / 变更" style="width: 100%">
                  <el-option label="故障" value="故障" />
                  <el-option label="缺陷" value="缺陷" />
                  <el-option label="变更" value="变更" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="问题来源" prop="issueSource">
                <el-select v-model="form.issueSource" placeholder="告警 / 人工巡检" style="width: 100%">
                  <el-option label="告警" value="告警" />
                  <el-option label="人工巡检" value="人工巡检" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="问题属性" prop="issueAttribute">
                <el-select v-model="form.issueAttribute" placeholder="新问题 / 老问题" style="width: 100%">
                  <el-option label="新问题" value="新问题" />
                  <el-option label="老问题" value="老问题" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="解决状态" prop="resolveStatus">
                <el-select v-model="form.resolveStatus" placeholder="未解决 / 已解决" style="width: 100%">
                  <el-option label="未解决" value="未解决" />
                  <el-option label="已解决" value="已解决" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="是否关注">
                <el-switch v-model="form.tagStatus" :active-value="1" :inactive-value="0" active-text="关注" inactive-text="未关注" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <!-- 分区 3: 责任与时间 -->
        <div class="form-section">
          <div class="section-header">
            <span class="section-bar"></span>
            <span class="section-title">责任与时间</span>
          </div>
          <div class="section-divider"></div>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="发现者" prop="discoverer">
                <el-input v-model="form.discoverer" placeholder="请输入发现者" clearable />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="责任人" prop="responsiblePerson">
                <el-input v-model="form.responsiblePerson" placeholder="请输入责任人" clearable />
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="预计解决时间" prop="planResolveTime">
                <el-date-picker
                  v-model="form.planResolveTime"
                  type="datetime"
                  placeholder="选择时间"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  style="width: 100%"
                  placement="top"
                />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <!-- 分区 4: 详细描述 -->
        <div class="form-section">
          <div class="section-header">
            <span class="section-bar"></span>
            <span class="section-title">详细描述</span>
          </div>
          <div class="section-divider"></div>
          <el-form-item label="问题描述" prop="issueDescription">
            <el-input v-model="form.issueDescription" type="textarea" :rows="2" placeholder="请描述问题现象、影响范围、发生时间等" />
          </el-form-item>
          <el-form-item label="核查原因" prop="checkReason">
            <el-input v-model="form.checkReason" type="textarea" :rows="2" placeholder="分析问题产生的根本原因（可后续补充）" />
          </el-form-item>
          <el-form-item label="后评估" prop="postEvaluation">
            <el-input v-model="form.postEvaluation" type="textarea" :rows="2" placeholder="问题解决后的复盘总结（可后续补充）" />
          </el-form-item>
        </div>

        <!-- 分区 5: 附件 -->
        <div class="form-section">
          <div class="section-header">
            <span class="section-bar"></span>
            <span class="section-title">附件</span>
          </div>
          <div class="section-divider"></div>
          <el-form-item label="附件上传">
            <div class="attachment-upload">
              <el-upload
                ref="uploadRef"
                :auto-upload="false"
                :show-file-list="false"
                :on-change="handleUploadAttachment"
                accept=".pdf,.doc,.docx,.xls,.xlsx,.txt,.jpg,.jpeg,.png"
                action="#"
              >
                <el-button size="small" type="primary">选择文件</el-button>
              </el-upload>
              <div v-if="form.attachmentName" class="attachment-info">
                <el-link type="primary" @click="currentEditId && downloadAttachment({id: currentEditId, attachmentName: form.attachmentName})">
                  {{ form.attachmentName }}
                </el-link>
                <el-button type="danger" size="small" link @click="handleDeleteAttachment">删除</el-button>
              </div>
              <div v-else class="no-attachment-tip">未上传附件</div>
            </div>
          </el-form-item>
        </div>
      </el-form>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustIssueDialogWidth" :visible="dialogVisible" />
    </div>

    <!-- 导入对话框 -->
    <el-dialog class="workspace-theme" v-model="importDialogVisible" title="导入问题" width="500px">
      <div class="import-tip">
        <el-alert type="info" :closable="false">
          导入将覆盖现有所有问题数据。请先下载模板，按模板格式填写后导入。
        </el-alert>
      </div>
      <el-upload
        ref="importUploadRef"
        class="upload-demo"
        drag
        :auto-upload="false"
        :on-change="handleImportFileChange"
        :limit="1"
        accept=".xlsx"
        style="margin-top: 15px;"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">
          拖拽文件到此处或 <em>点击上传</em>
        </div>
        <template #tip>
          <div class="el-upload__tip">只能上传xlsx文件，请使用下载模板功能获取导入模板</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importLoading" @click="handleImportConfirm">确定导入</el-button>
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
import { openIssueAttachment as downloadAttachment } from '@/utils/issueAttachment'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import WorkspaceOverviewLayout from '@/components/workspace/WorkspaceOverviewLayout.vue'
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import { useChartContainerResize } from '@/utils/useChartContainerResize'
import { readWorkspaceChartTheme } from '@/utils/workspaceTheme'
import IssueDetailDialog from '@/components/issue/IssueDetailDialog.vue'
import WorkspacePagination from '@/components/workspace/WorkspacePagination.vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import IssueFilters from '@/components/issue/IssueFilters.vue'
import IssueDiscovererBoard from '@/components/issue/IssueDiscovererBoard.vue'
import IssueOverviewCards from '@/components/issue/IssueOverviewCards.vue'
import { ref, computed, onMounted, onUnmounted, h, resolveComponent, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElInput, ElInputNumber } from 'element-plus'
import * as echarts from 'echarts'
import { Plus, Search, Delete, Download, Upload, Setting, Bell, View, Edit, Close, Paperclip, InfoFilled, QuestionFilled, UploadFilled, Document, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { issueApi, systemApi, metricApi } from '../../api'
import { useDialogSize } from '../../utils/useDialogSize'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'
import IssueAuditLog from '@/components/issue/IssueAuditLog.vue'
import { formatDateTime, simplifyTimeLabels, getTimeRangeHint } from '../../utils'

const issueInsightsRoot = ref(null)
useChartContainerResize(issueInsightsRoot, element => echarts.getInstanceByDom(element))

// 弹窗宽度控制
const { width: issueDetailWidth, adjustWidth: adjustIssueDetailWidth } = useDialogSize('issue-detail', '700px')
const { width: issueDialogWidth, adjustWidth: adjustIssueDialogWidth } = useDialogSize('issue-dialog', '80vw')

const route = useRoute()
const router = useRouter()

const tableData = ref([])
const tableLoading = ref(false)
const selectedRows = ref([])

// 列级展开/收起状态（key = 列 prop，value = true 展开 / false 收起）
const expandedColumns = ref({})
const toggleColumnExpand = (col) => {
  expandedColumns.value[col] = !expandedColumns.value[col]
}

// 选中行变化
const handleSelectionChange = (selection) => {
  selectedRows.value = selection
}

// 批量删除
const handleBatchDelete = () => {
  if (selectedRows.value.length === 0) return
  ElMessageBox.confirm(`确定删除选中的 ${selectedRows.value.length} 条问题吗?`, '提示', {
    type: 'warning'
  }).then(async () => {
    try {
      for (const row of selectedRows.value) {
        await issueApi.delete(row.id)
      }
      ElMessage.success('删除成功')
      selectedRows.value = []
      loadData()
      loadTrendData()      // 刷新问题趋势图
      loadIssueStats()    // 刷新左侧统计卡
    } catch (error) {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }).catch(() => {})
}

// 导出Excel
const handleExport = async () => {
  try {
    const params = { ...queryParams.value }
    // 处理日期范围
    if (params.registerTimeRange && params.registerTimeRange.length === 2) {
      params.registerTimeStart = params.registerTimeRange[0]
      params.registerTimeEnd = params.registerTimeRange[1]
    }
    delete params.registerTimeRange
    // 移除空值
    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })

    const res = await issueApi.exportExcel(params)
    // 下载文件 - res是axios响应对象，res.data是blob数据
    const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `问题列表_${new Date().toISOString().slice(0, 10)}.xlsx`
    link.click()
    URL.revokeObjectURL(link.href)
    ElMessage.success('导出成功')
  } catch (error) {
    console.error('导出失败:', error)
    ElMessage.error('导出失败')
  }
}

// 下载导入模板
const handleDownloadTemplate = async () => {
  try {
    const res = await issueApi.downloadTemplate()
    // res是axios响应对象，res.data是blob数据
    const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = '问题导入模板.xlsx'
    link.click()
    URL.revokeObjectURL(link.href)
    ElMessage.success('模板下载成功')
  } catch (error) {
    console.error('模板下载失败:', error)
    ElMessage.error('模板下载失败')
  }
}

// 导入Excel
const importDialogVisible = ref(false)
const importLoading = ref(false)
const importUploadRef = ref(null)
const importFile = ref(null)

const handleImport = () => {
  importFile.value = null
  importDialogVisible.value = true
}

const handleImportFileChange = (file) => {
  importFile.value = file.raw
}

const handleImportConfirm = async () => {
  if (!importFile.value) {
    ElMessage.warning('请选择要导入的文件')
    return
  }
  importLoading.value = true
  try {
    const res = await issueApi.importExcel(importFile.value, true)
    if (res.code === 200) {
      const data = res.data || {}
      if (data.errorCount > 0) {
        ElMessage.warning(`导入完成：成功 ${data.successCount} 条，失败 ${data.errorCount} 条`)
        if (data.message) {
          ElMessageBox.alert(data.message, '导入部分失败', {
            type: 'warning',
            confirmButtonText: '确定'
          })
        }
      } else {
        ElMessage.success(`导入成功：共 ${data.successCount} 条`)
      }
      importDialogVisible.value = false
      loadData()
      loadIssueStats()
    } else {
      ElMessage.error(res.message || '导入失败')
    }
  } catch (error) {
    console.error('导入失败:', error)
    ElMessage.error('导入失败: ' + (error.message || '未知错误'))
  } finally {
    importLoading.value = false
  }
}

const queryParams = ref({
  resolveStatus: null,
  issueType: null,
  issueSource: null,
  issueAttribute: null,
  responsiblePerson: '',
  discoverer: '',
  keyword: '',
  systemId: null,
  categoryId: null,
  metricId: null,
  registerTimeRange: null,
  hasAttachment: null,
  hasCheckReason: null
})

// 系统、指标大类、指标列表
const activeTab = ref('list')
const operationLogRef = ref(null)

// 监听 Tab 切换，自动刷新操作日志
watch(activeTab, (newTab) => {
  if (newTab === 'auditLog' && operationLogRef.value) {
    operationLogRef.value.refresh()
  }
})

const systemList = ref([])
const categoryList = ref([])
const metricList = ref([])
const pagination = ref({
  page: 1,
  size: 10,
  total: 0
})

// 排序参数
const sortParams = ref({ sortBy: 'registerTime', sortOrder: 'desc' })

// 逾期提醒设置
const notificationEnabled = ref(false)
const notificationTime = ref('09:00')
const NOTIFICATION_KEY = 'issue_notification_settings'

// 加载通知设置
const loadNotificationSettings = () => {
  try {
    const saved = localStorage.getItem(NOTIFICATION_KEY)
    if (saved) {
      const settings = JSON.parse(saved)
      notificationEnabled.value = settings.enabled || false
      notificationTime.value = settings.time || '09:00'
    }
  } catch (e) {
    console.error('加载通知设置失败:', e)
  }
}

// 保存通知设置
const saveNotificationSettings = () => {
  try {
    localStorage.setItem(NOTIFICATION_KEY, JSON.stringify({
      enabled: notificationEnabled.value,
      time: notificationTime.value
    }))
    ElMessage.success('通知设置已保存')
  } catch (e) {
    console.error('保存通知设置失败:', e)
  }
}

// 手动生成逾期告警
const handleGenerateAlerts = async () => {
  try {
    const res = await issueApi.generateOverdueAlerts()
    // 响应拦截器已返回 {code, message, data} 结构
    if (res.code === 200) {
      ElMessage.success(`已生成 ${res.data} 条逾期告警`)
    } else {
      ElMessage.error(res.message || '生成告警失败')
    }
  } catch (e) {
    console.error('生成告警失败:', e)
    ElMessage.error('生成告警失败: ' + (e.message || e))
  }
}

// 打开附件 - 图片/PDF 用 inline 浏览器内联预览，其他类型走 attachment 直接下载


// 上传附件
const handleUploadAttachment = async (file) => {
  if (!currentEditId.value) {
    ElMessage.warning('请先保存问题')
    return false
  }
  try {
    const res = await issueApi.uploadAttachment(currentEditId.value, file.raw || file)
    if (res.code === 200) {
      // 更新当前行数据
      const idx = tableData.value.findIndex(r => r.id === currentEditId.value)
      if (idx !== -1) {
        tableData.value[idx] = res.data
      }
      form.value.attachmentName = res.data.attachmentName
      form.value.attachmentPath = res.data.attachmentPath
      ElMessage.success('上传成功')
    } else {
      ElMessage.error(res.message || '上传失败')
    }
  } catch (e) {
    console.error('上传失败:', e)
    ElMessage.error('上传失败')
  }
  return false
}

// 删除附件
const handleDeleteAttachment = async () => {
  if (!currentEditId.value) return
  try {
    const res = await issueApi.deleteAttachment(currentEditId.value)
    if (res.code === 200) {
      const idx = tableData.value.findIndex(r => r.id === currentEditId.value)
      if (idx !== -1) {
        tableData.value[idx] = res.data
      }
      form.value.attachmentName = null
      form.value.attachmentPath = null
      ElMessage.success('删除成功')
    }
  } catch (e) {
    console.error('删除失败:', e)
    ElMessage.error('删除失败')
  }
}

// 列显示设置
const STORAGE_KEY = 'issue_column_prefs'
const columnOptions = ref([
  { prop: 'registerTime', label: '登记时间' },
  { prop: 'updateTime', label: '修改时间' },
  { prop: 'issueType', label: '问题类型' },
  { prop: 'systemName', label: '系统' },
  { prop: 'categoryName', label: '指标大类' },
  { prop: 'subCategoryName', label: '指标小类' },
  { prop: 'metricName', label: '指标' },
  { prop: 'issueDescription', label: '问题描述' },
  { prop: 'issueAttribute', label: '问题属性' },
  { prop: 'issueSource', label: '问题来源' },
  { prop: 'discoverer', label: '发现者' },
  { prop: 'responsiblePerson', label: '责任人' },
  { prop: 'checkReason', label: '核查原因' },
  { prop: 'planResolveTime', label: '预计解决时间' },
  { prop: 'resolveStatus', label: '解决状态' },
  { prop: 'postEvaluation', label: '后评估' },
  { prop: 'tagStatus', label: '是否关注' },
  { prop: 'attachment', label: '附件' }
])

// 默认显示所有列
const defaultVisibleColumns = columnOptions.value.map(c => c.prop)
const visibleColumns = ref([...defaultVisibleColumns])

// 统计面板数据
const issueStats = ref({
  totalIssueCount: 0,
  todayCount: 0,
  weekCount: 0,
  unresolvedCount: 0,
  resolvedCount: 0
})

// 趋势图相关
const trendChartDom = ref(null)
const trendChart = ref(null)
const originalXAxisData = ref([])
const trendDimension = ref('month')
const trendType = ref('all')
const trendTimeRange = ref(null)
const trendData = ref([])
const chartDrillDownLabel = ref('')

// 发现者统计看板（独立时间范围，互不影响趋势图）
const discovererStats = ref([])
const discovererTimeRange = ref('month')

// 检查列是否隐藏（attachment列始终显示）
const isColumnHidden = (prop) => {
  if (prop === 'attachment') return false
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

// 可编辑单元格组件
// 支持 type: 'text'(默认) | 'number' | 'date' | 'datetime'
// 长文本自动用 CSS line-clamp:2 截断显示，点击展开/收起，双击编辑
// 溢出检测：DOM scrollHeight > clientHeight（不用字符数截断，省略号永远在第2行末尾）
const EditableCell = {
  props: ['value', 'type', 'placeholder', 'forceExpanded'],
  emits: ['save'],
  setup(props, { emit }) {
    const editing = ref(false)
    const inputValue = ref(props.value)
    const inputRef = ref(null)
    const spanRef = ref(null)
    const expanded = ref(false)
    const hasOverflow = ref(false)

    // 检测文本是否超出 2 行（scrollHeight > clientHeight）
    const checkOverflow = () => {
      nextTick(() => {
        const el = spanRef.value
        if (!el) {
          hasOverflow.value = false
          return
        }
        // 只在截断态检测（展开态 scrollHeight == clientHeight）
        if (expanded.value) {
          hasOverflow.value = true // 已展开说明确实有溢出
          return
        }
        hasOverflow.value = el.scrollHeight > el.clientHeight + 1
      })
    }

    const startEdit = () => {
      inputValue.value = props.value
      editing.value = true
      nextTick(() => {
        inputRef.value?.focus?.()
      })
    }

    const saveEdit = () => {
      if (!editing.value) return
      editing.value = false
      const newVal = inputValue.value
      const isEmpty = (v) => v === null || v === undefined || v === ''
      if (!isEmpty(newVal) && newVal !== props.value) {
        emit('save', newVal)
      } else if (isEmpty(newVal) && !isEmpty(props.value)) {
        emit('save', null)
      }
    }

    const handleKeyup = (e) => {
      if (e.key === 'Enter' && props.type !== 'date' && props.type !== 'datetime') {
        saveEdit()
      }
    }

    // 显示值（date 类型格式化展示）
    const displayValue = computed(() => {
      if (props.value == null || props.value === '') return ''
      if (props.type === 'datetime' || props.type === 'date') {
        return formatDateTime(props.value)
      }
      return props.value
    })

    const textStr = computed(() => {
      const v = displayValue.value
      return v == null ? '' : String(v)
    })

    // 估算 textarea 行数：按换行 + 字符数 / 每行宽度
    const CHARS_PER_LINE = 30
    const MIN_ROWS = 2
    const MAX_ROWS = 8
    const textRows = computed(() => {
      if (props.type && props.type !== 'text') return 1
      const s = textStr.value
      if (!s) return MIN_ROWS
      const newlines = (s.match(/\n/g) || []).length
      const maxLineLen = Math.max(...s.split('\n').map(l => l.length || 0), 0)
      const wrappedByChars = Math.ceil(maxLineLen / CHARS_PER_LINE)
      const totalLines = newlines + wrappedByChars
      return Math.max(MIN_ROWS, Math.min(MAX_ROWS, totalLines))
    })

    // 区分"单击"和"拖动选中"：mousedown 时记录起点；click 时若位移>阈值视为拖动
    // 单击延迟执行（250ms），如果在 250ms 内触发了 dblclick 就取消单击
    const downPos = ref({ x: 0, y: 0 })
    const DRAG_THRESHOLD = 5
    const DBLCLICK_DELAY = 250
    let clickTimer = null

    const onMouseDown = (e) => {
      if (editing.value) return
      if (e.button !== 0) return
      downPos.value = { x: e.clientX, y: e.clientY }
    }

    const onClick = (e) => {
      if (editing.value) return
      if (e.button !== 0) return
      const dx = Math.abs(e.clientX - downPos.value.x)
      const dy = Math.abs(e.clientY - downPos.value.y)
      if (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD) {
        e.stopPropagation()
        e.preventDefault()
        return
      }
      if (!hasOverflow.value) return
      if (clickTimer) clearTimeout(clickTimer)
      e.stopPropagation()
      clickTimer = setTimeout(() => {
        clickTimer = null
        expanded.value = !expanded.value
      }, DBLCLICK_DELAY)
    }

    const onDblClick = (e) => {
      if (clickTimer) {
        clearTimeout(clickTimer)
        clickTimer = null
      }
      startEdit(e)
    }

    // 监听 value 变化，重新检测溢出
    watch(() => props.value, () => { checkOverflow() })
    // 展开/收起后也要重新检测（收起时需要重新判断溢出）
    watch(expanded, () => { if (!expanded.value) checkOverflow() })

    return () => {
      if (editing.value) {
        if (props.type === 'number') {
          return h('div', { class: 'editable-cell-edit' }, [
            h(ElInputNumber, {
              modelValue: inputValue.value,
              'onUpdate:modelValue': (val) => { inputValue.value = val },
              size: 'small',
              controls: true,
              min: 0,
              style: 'width: 100%',
              onBlur: saveEdit,
              onKeyup: handleKeyup,
              ref: inputRef
            })
          ])
        } else if (props.type === 'datetime' || props.type === 'date') {
          return h('div', { class: 'editable-cell-edit' }, [
            h(resolveComponent('el-date-picker'), {
              modelValue: inputValue.value,
              'onUpdate:modelValue': (val) => { inputValue.value = val },
              type: props.type,
              placeholder: props.placeholder || '选择时间',
              valueFormat: props.type === 'datetime' ? 'YYYY-MM-DDTHH:mm:ss' : 'YYYY-MM-DD',
              size: 'small',
              style: 'width: 100%',
              onChange: () => { nextTick(() => saveEdit()) },
              ref: inputRef
            })
          ])
        } else {
          const rows = textRows.value
          if (rows > 1) {
            return h('div', { class: 'editable-cell-edit' }, [
              h(ElInput, {
                modelValue: inputValue.value,
                'onUpdate:modelValue': (val) => { inputValue.value = val },
                type: 'textarea',
                autosize: { minRows: rows, maxRows: Math.max(rows, MAX_ROWS) },
                size: 'small',
                placeholder: props.placeholder || '双击编辑',
                style: 'width: 100%',
                onBlur: saveEdit,
                onKeyup: handleKeyup,
                ref: inputRef
              })
            ])
          }
          return h('div', { class: 'editable-cell-edit' }, [
            h(ElInput, {
              modelValue: inputValue.value,
              'onUpdate:modelValue': (val) => { inputValue.value = val },
              size: 'small',
              placeholder: props.placeholder || '双击编辑',
              style: 'width: 100%',
              onBlur: saveEdit,
              onKeyup: handleKeyup,
              ref: inputRef
            })
          ])
        }
      }

      // 非编辑态
      // 1) 展开态：完整文字，可拖动选中，单击收起，双击编辑
      if (expanded.value || props.forceExpanded) {
        return h('span', {
          class: 'editable-cell-text editable-cell-expanded',
          title: textStr.value,
          onMousedown: onMouseDown,
          onClick: onClick,
          onDblclick: onDblClick,
        }, [textStr.value])
      }

      // 2) 截断态：CSS line-clamp:2 自动截断，省略号在第2行末尾
      //    挂 ref 检测溢出；有溢出时单击展开，双击编辑
      return h('span', {
        class: 'editable-cell-text editable-cell-truncated',
        title: hasOverflow.value ? textStr.value : undefined,
        ref: spanRef,
        onVnodeMounted: checkOverflow,
        onMousedown: onMouseDown,
        onClick: onClick,
        onDblclick: onDblClick,
      }, [textStr.value])
    }
  }
}

// 保存单元格
const saveCell = async (row, field, value) => {
  try {
    await issueApi.update(row.id, { [field]: value })
    row[field] = value
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
    loadData()
  }
}

// 切换解决状态
const toggleResolveStatus = async (row) => {
  const newStatus = row.resolveStatus === '已解决' ? '未解决' : '已解决'
  try {
    await issueApi.resolve(row.id, newStatus)
    row.resolveStatus = newStatus
    ElMessage.success('状态已更新')
  } catch (error) {
    console.error('更新状态失败:', error)
    ElMessage.error('更新状态失败')
  }
}

// 切换是否关注
const toggleTagStatus = async (row) => {
  const newStatus = row.tagStatus === 1 ? 0 : 1
  try {
    await issueApi.update(row.id, { tagStatus: newStatus })
    row.tagStatus = newStatus
    ElMessage.success(newStatus === 1 ? '已关注' : '已取消关注')
  } catch (error) {
    console.error('更新关注状态失败:', error)
    ElMessage.error('更新失败')
  }
}

// 处理排序变化
const handleSortChange = ({ prop, order }) => {
  if (order === 'descending') {
    sortParams.value.sortOrder = 'desc'
  } else if (order === 'ascending') {
    sortParams.value.sortOrder = 'asc'
  }
  sortParams.value.sortBy = prop || 'registerTime'
  loadData()
}

const dialogVisible = ref(false)
const detailDialogVisible = ref(false)
const detailData = ref(null)
const currentEditId = ref(null)
const formRef = ref(null)
// 表单校验规则
const formRules = {
  systemName: [
    { required: true, message: '请输入系统名称', trigger: 'blur' }
  ],
  categoryName: [
    { required: true, message: '请输入指标大类', trigger: 'blur' }
  ],
  issueType: [
    { required: true, message: '请选择问题类型', trigger: 'change' }
  ],
  issueSource: [
    { required: true, message: '请选择问题来源', trigger: 'change' }
  ],
  issueAttribute: [
    { required: true, message: '请选择问题属性', trigger: 'change' }
  ],
  resolveStatus: [
    { required: true, message: '请选择解决状态', trigger: 'change' }
  ],
  issueDescription: [
    { required: true, message: '请输入问题描述', trigger: 'blur' }
  ]
}
const form = ref({
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
  tagStatus: 0,
  systemName: '',
  categoryName: '',
  subCategoryName: '',
  metricName: ''
})

// === 系统名输入建议（仅用于问题管理，不与指标系统表耦合） ===
// 方案：弹窗打开时一次性预加载到 cachedSystemList，focus/敲字时本地过滤
// 避免每次 el-autocomplete 重新计算时都发网络请求导致「转圈重载」
let cachedSystemList = []  // 全量历史系统名 [{value: 'CRM'}, ...]
let isPreloading = false   // 防止并发预加载
async function preloadSystemList() {
  if (isPreloading) return
  isPreloading = true
  try {
    const res = await issueApi.suggestSystems('')
    const rawList = Array.isArray(res) ? res : (res?.data || [])
    cachedSystemList = rawList.map(name => ({ value: name }))
    console.log('[系统名建议] 预加载完成，共', cachedSystemList.length, '条')
  } catch (e) {
    // 400 = 后端版本不支持此接口，静默处理
    if (e?.response?.status === 400) {
      console.warn('[系统名建议] 接口暂不可用(400)，建议重建后端容器')
    } else {
      console.warn('[系统名建议] 预加载失败:', e?.message || e)
    }
  } finally {
    isPreloading = false
  }
}
function querySystemSuggestions(queryString, cb) {
  // 本地过滤，零延迟。如果缓存为空则同步触发一次预加载（不阻塞）
  if (cachedSystemList.length === 0) {
    preloadSystemList()
  }
  const query = (queryString || '').trim().toLowerCase()
  if (!query) {
    cb(cachedSystemList)
  } else {
    cb(cachedSystemList.filter(item => item.value.toLowerCase().includes(query)))
  }
}
function handleSystemSelect(item) {
  // el-autocomplete 选中时 v-model 会被自动设为 item.value
  // 此处仅做兜底（如果 item 是字符串或缺 value 字段）
  if (typeof item === 'string') {
    form.systemName = item
  } else if (item && typeof item === 'object' && 'value' in item) {
    form.systemName = item.value
  }
}

// === 指标大类输入建议（仅用于问题管理，不与指标大类表耦合） ===
let cachedCategoryList = []
let isPreloadingCategories = false
async function preloadCategoryList() {
  if (isPreloadingCategories) return
  isPreloadingCategories = true
  try {
    const res = await issueApi.suggestCategories('')
    const rawList = Array.isArray(res) ? res : (res?.data || [])
    cachedCategoryList = rawList.map(name => ({ value: name }))
    console.log('[指标大类建议] 预加载完成，共', cachedCategoryList.length, '条')
  } catch (e) {
    if (e?.response?.status === 400) {
      console.warn('[指标大类建议] 接口暂不可用(400)')
    } else {
      console.warn('[指标大类建议] 预加载失败:', e?.message || e)
    }
  } finally {
    isPreloadingCategories = false
  }
}
function queryCategorySuggestions(queryString, cb) {
  if (cachedCategoryList.length === 0) {
    preloadCategoryList()
  }
  const query = (queryString || '').trim().toLowerCase()
  if (!query) {
    cb(cachedCategoryList)
  } else {
    cb(cachedCategoryList.filter(item => item.value.toLowerCase().includes(query)))
  }
}
function handleCategorySelect(item) {
  if (typeof item === 'string') {
    form.categoryName = item
  } else if (item && typeof item === 'object' && 'value' in item) {
    form.categoryName = item.value
  }
}

// === 指标小类输入建议（仅用于问题管理，不与指标小类表耦合） ===
let cachedSubCategoryList = []
let isPreloadingSubCategories = false
async function preloadSubCategoryList() {
  if (isPreloadingSubCategories) return
  isPreloadingSubCategories = true
  try {
    const res = await issueApi.suggestSubCategories('')
    const rawList = Array.isArray(res) ? res : (res?.data || [])
    cachedSubCategoryList = rawList.map(name => ({ value: name }))
    console.log('[指标小类建议] 预加载完成，共', cachedSubCategoryList.length, '条')
  } catch (e) {
    if (e?.response?.status === 400) {
      console.warn('[指标小类建议] 接口暂不可用(400)')
    } else {
      console.warn('[指标小类建议] 预加载失败:', e?.message || e)
    }
  } finally {
    isPreloadingSubCategories = false
  }
}
function querySubCategorySuggestions(queryString, cb) {
  if (cachedSubCategoryList.length === 0) {
    preloadSubCategoryList()
  }
  const query = (queryString || '').trim().toLowerCase()
  if (!query) {
    cb(cachedSubCategoryList)
  } else {
    cb(cachedSubCategoryList.filter(item => item.value.toLowerCase().includes(query)))
  }
}
function handleSubCategorySelect(item) {
  if (typeof item === 'string') {
    form.subCategoryName = item
  } else if (item && typeof item === 'object' && 'value' in item) {
    form.subCategoryName = item.value
  }
}

// 弹窗打开时触发：预加载系统名、指标大类、指标小类历史
function onIssueDialogOpened() {
  preloadSystemList()
  preloadCategoryList()
  preloadSubCategoryList()
}

// 统一处理筛选变化
function handleFilterChange() {
  pagination.value.page = 1
  loadData()
}

const loadData = async () => {
  tableLoading.value = true
  try {
    // 处理日期范围参数
    const timeRange = queryParams.value.registerTimeRange
    const registerTimeStart = timeRange && timeRange[0] ? timeRange[0] : null
    const registerTimeEnd = timeRange && timeRange[1] ? timeRange[1] : null

    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      ...sortParams.value,
      systemId: queryParams.value.systemId || null,
      categoryId: queryParams.value.categoryId || null,
      metricId: queryParams.value.metricId || null,
      issueType: queryParams.value.issueType || null,
      issueSource: queryParams.value.issueSource || null,
      resolveStatus: queryParams.value.resolveStatus || null,
      issueAttribute: queryParams.value.issueAttribute || null,
      discoverer: queryParams.value.discoverer || null,
      keyword: queryParams.value.keyword || null,
      registerTimeStart: registerTimeStart,
      registerTimeEnd: registerTimeEnd,
      hasAttachment: queryParams.value.hasAttachment,
      hasCheckReason: queryParams.value.hasCheckReason
    }

    // 移除空值
    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '' || params[key] === undefined) {
        delete params[key]
      }
    })

    const res = await issueApi.list(params)
    const data = res.data.content || []
    // 调试日志
    // 补充系统、指标大类、指标名称
    data.forEach(item => {
      // 优先使用实体中存储的名称（支持自定义名称），否则从下拉列表中查找
      const system = systemList.value.find(s => s.id === item.systemId)
      item.systemName = item.systemName || (system ? system.systemName : '')
      const category = categoryList.value.find(c => c.id === item.categoryId)
      item.categoryName = item.categoryName || (category ? category.categoryName : '')
      const metric = metricList.value.find(m => m.id === item.metricId)
      item.metricName = item.metricName || (metric ? metric.metricName : '')
    })
    tableData.value = data
    pagination.value.total = res.data.totalElements || 0
  } catch (error) {
    console.error('加载问题列表失败:', error)
  } finally {
    tableLoading.value = false
  }
}

// 重置问题筛选
const resetIssueFilters = () => {
  queryParams.value = {
    resolveStatus: null,
    issueType: null,
    issueSource: null,
    issueAttribute: null,
    responsiblePerson: '',
    discoverer: '',
    keyword: '',
    systemId: null,
    categoryId: null,
    metricId: null,
    registerTimeRange: null,
    hasAttachment: null,
    hasCheckReason: null
  }
  pagination.value.page = 1
  loadData()
}

// 统计卡片点击：按时间范围筛选问题
const filterIssueByRange = (range) => {
  const now = new Date()
  const fmt = (d) => d.toISOString().substring(0, 10)
  if (range === 'today') {
    queryParams.value.registerTimeRange = [fmt(now), fmt(now)]
  } else if (range === 'week') {
    const weekAgo = new Date(now.getTime() - 7 * 86400000)
    queryParams.value.registerTimeRange = [fmt(weekAgo), fmt(now)]
  } else {
    queryParams.value.registerTimeRange = null
  }
  queryParams.value.resolveStatus = null
  pagination.value.page = 1
  loadData()
}

// 统计卡片点击：按解决状态筛选问题
const filterIssueByResolve = (status) => {
  if (status === -1) {
    queryParams.value.resolveStatus = null
  } else if (status === 0) {
    queryParams.value.resolveStatus = '未解决'
  } else {
    queryParams.value.resolveStatus = '已解决'
  }
  queryParams.value.registerTimeRange = null
  pagination.value.page = 1
  loadData()
}

const handleAdd = () => {
  form.value = {
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
    tagStatus: 0,
    systemName: '',
    categoryName: '',
    subCategoryName: '',
    metricName: '',
    attachmentName: null,
    attachmentPath: null
  }
  currentEditId.value = null
  // 清理上次校验遗留的红框
  nextTick(() => {
    formRef.value?.clearValidate()
  })
  dialogVisible.value = true
}

// 查看详情
const handleViewDetail = (row) => {
  detailData.value = { ...row }
  // 优先使用实体中存储的名称（支持自定义名称），否则从下拉列表中查找
  const system = systemList.value.find(s => s.id === row.systemId)
  const category = categoryList.value.find(c => c.id === row.categoryId)
  const metric = metricList.value.find(m => m.id === row.metricId)
  detailData.value.systemName = row.systemName || system?.systemName || '-'
  detailData.value.categoryName = row.categoryName || category?.categoryName || '-'
  detailData.value.subCategoryName = row.subCategoryName || '-'
  detailData.value.metricName = row.metricName || metric?.metricName || '-'
  detailDialogVisible.value = true
}

// 从详情页编辑
const handleEditFromDetail = () => {
  if (detailData.value) {
    form.value = { ...detailData.value }
    currentEditId.value = detailData.value.id
    detailDialogVisible.value = false
    nextTick(() => formRef.value?.clearValidate())
    dialogVisible.value = true
  }
}

const handleEdit = (row) => {
  form.value = { ...row }
  currentEditId.value = row.id
  nextTick(() => formRef.value?.clearValidate())
  dialogVisible.value = true
}

const saveData = async () => {
  // 编辑模式跳过必填校验
  if (!form.value.id) {
    if (!formRef.value) return
    try {
      await formRef.value.validate()
    } catch (validationError) {
      ElMessage.error('请填写必填项')
      return
    }
  }
  try {
    if (form.value.id) {
      // 编辑：调用 update，再用后端返回的最新数据更新当前行，最后重新加载当前页
      const res = await issueApi.update(form.value.id, form.value)
      // 用后端返回的最新对象同步当前行（包含所有字段）
      const temp = [...tableData.value]
      const idx = temp.findIndex(r => r.id === form.value.id)
      if (idx !== -1 && res?.data) {
        temp[idx] = res.data
        tableData.value = temp
      } else {
        // 后端没返回有效数据时，退回到重新查当前页
        await loadData()
      }
      ElMessage.success('保存成功')
      dialogVisible.value = false
    } else {
      // 新建
      const newName = (form.value.systemName || '').trim()
      await issueApi.create(form.value)
      ElMessage.success('保存成功')
      dialogVisible.value = false
      // 跳到第一页（按 registerTime desc 排序，新建的最靠前）
      pagination.value.page = 1
      await loadData()
      // 同步刷新建议缓存：如果新建时填了系统名，加入缓存供下次自动补全
      if (newName && !cachedSystemList.some(item => item.value === newName)) {
        cachedSystemList.push({ value: newName })
      }
    }
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败: ' + (error.message || '未知错误'))
  }
}

const handleResolve = async (row) => {
  try {
    await issueApi.resolve(row.id, { resolveStatus: '已解决' })
    ElMessage.success('标记已解决')
    loadData()
  } catch (error) {
    console.error('操作失败:', error)
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确定删除该问题吗?', '提示', {
    type: 'warning'
  }).then(async () => {
    await issueApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
    loadTrendData()      // 刷新问题趋势图
    loadIssueStats()    // 刷新左侧统计卡
  }).catch(() => {})
}

onMounted(async () => {
  loadPreferences()
  loadNotificationSettings()
  await loadSystems()
  await loadCategories()
  await loadMetrics()
  // 检查URL查询参数
  if (route.query.resolveStatus !== undefined) {
    queryParams.value.resolveStatus = route.query.resolveStatus
    router.replace({ path: '/issue' })
  }
  await loadData()
  await loadIssueStats()
  loadTrendData()
  loadDiscovererStats()
  window.addEventListener('resize', handleResize)
  // 监听路由变化
  watch(() => route.query, (newQuery) => {
    if (newQuery.resolveStatus !== undefined) {
      queryParams.value.resolveStatus = newQuery.resolveStatus
      loadData()
      router.replace({ path: '/issue' })
    }
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (trendChart.value) {
    trendChart.value.dispose()
  }
})

// 加载系统列表
const loadSystems = async () => {
  try {
    const res = await systemApi.listAll()
    systemList.value = res.data || []
  } catch (e) {
    console.error('加载系统列表失败', e)
  }
}

// 加载指标大类列表
const loadCategories = async () => {
  try {
    const res = await metricApi.categoryListAll()
    categoryList.value = res.data || []
  } catch (e) {
    console.error('加载指标大类失败', e)
  }
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

// 系统变化时加载对应的大类
const onSystemChange = async () => {
  queryParams.value.categoryId = null
  queryParams.value.metricId = null
  metricList.value = []
  await loadCategories()
  await loadMetrics()
  handleFilterChange()
}

// 切换大类后清除上一大类的指标选择，并沿用原有筛选刷新。
const onCategoryChange = () => {
  queryParams.value.metricId = null
  handleFilterChange()
}

// 加载统计数据
const loadIssueStats = async () => {
  try {
    const res = await issueApi.statistics()
    issueStats.value = res.data || {}
  } catch (error) {
    console.error('加载问题统计失败:', error)
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
    const res = await issueApi.getTrend(params)
    trendData.value = res.data || []
    nextTick(() => drawTrendChart())
  } catch (error) {
    console.error('加载趋势数据失败:', error)
  }
}

// 将发现者看板的时间范围转换为后端日期参数；'all' 时不传
const resolveDiscovererRange = () => {
  const range = discovererTimeRange.value
  if (range === 'all') return {}
  const now = new Date()
  const fmt = d => d.toISOString().substring(0, 10)
  if (range === 'today') return { registerTimeStart: fmt(now), registerTimeEnd: fmt(now) }
  const days = range === 'week' ? 7 : range === 'month' ? 30 : 365
  const ago = new Date(now.getTime() - days * 86400000)
  return { registerTimeStart: fmt(ago), registerTimeEnd: fmt(now) }
}

// 发现者统计看板数据加载
const loadDiscovererStats = async () => {
  try {
    const params = { limit: 5, ...resolveDiscovererRange() }
    const res = await issueApi.discovererStats(params)
    discovererStats.value = res.data || []
  } catch (error) {
    console.error('加载发现者统计失败:', error)
  }
}

// 发现者看板下钻：发现者 + 看板的当前时间范围 + 对应维度过滤写入 queryParams
const drillByDiscoverer = (row, dimension) => {
  queryParams.value.discoverer = (row.discoverer || '').trim()
  // 重置维度修饰符；按点击列追加对应修饰符
  queryParams.value.hasCheckReason = null
  queryParams.value.hasAttachment = null
  if (dimension === 'checkReasonCount') {
    queryParams.value.hasCheckReason = true
  } else if (dimension === 'attachmentCount') {
    queryParams.value.hasAttachment = true
  }
  // 看板的独立时间范围同步写到"登记时间"过滤，便于用户看"这段时间里"张三的这些问题
  const range = resolveDiscovererRange()
  if (range.registerTimeStart && range.registerTimeEnd) {
    queryParams.value.registerTimeRange = [range.registerTimeStart, range.registerTimeEnd]
  } else {
    queryParams.value.registerTimeRange = null
  }
  pagination.value.page = 1
  loadData()
}

// 绘制趋势图
// 智能简化时间标签，数据多时去掉公共前缀
const drawTrendChart = () => {
  if (!trendChartDom.value) return

  const data = trendData.value
  const isToday = trendDimension.value === 'today'
  // today维度只显示时间部分，如 "04:00"
  const xAxisData = data.map(d => isToday ? d.time.substring(11, 16) : d.time)
  // 保存原始数据用于点击
  originalXAxisData.value = xAxisData
  // week/month 维度简化标签（去掉公共前缀），today/year 不简化
  const isNeedSimplify = trendDimension.value === 'week' || trendDimension.value === 'month'
  const simplifiedXAxisData = isNeedSimplify
    ? simplifyTimeLabels(xAxisData, trendDimension.value)
    : xAxisData
  const seriesData = data.map(d => d.count)

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
        return `${p.name}<br/>问题数量: <b>${p.value}</b><br/><span style="font-size:10px;color:#909399">点击筛选该时段</span>`
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
        rotate: 0,
        interval: data.length > 12 ? 1 : 0  // 数据多时每隔一个显示
      }
    },
    yAxis: {
      type: 'value',
      min: 0,
      splitNumber: 4,
      minInterval: 1
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

  if (trendDimension.value === 'today') {
    // today 维度: name 现在是 "04:00" 格式，需要拼接今天日期
    const today = new Date().toISOString().substring(0, 10)
    const fullTime = `${today} ${name}:00`
    queryParams.value.registerTimeRange = [fullTime, fullTime.substring(0, 13) + ':59:59']
  } else if (trendDimension.value === 'year') {
    const [year, month] = name.split('-')
    const lastDay = new Date(parseInt(year), parseInt(month), 0).getDate()
    queryParams.value.registerTimeRange = [`${name}-01`, `${name}-${String(lastDay).padStart(2, '0')}`]
  } else {
    queryParams.value.registerTimeRange = [name, name]
  }
  chartDrillDownLabel.value = name
  loadData()
}

// 清除下钻筛选
const clearDrillDown = () => {
  chartDrillDownLabel.value = ''
  queryParams.value.registerTimeRange = null
  loadData()
}

// 获取空数据提示文本
const getEmptyText = () => {
  const dimensionMap = {
    'today': '今天没有问题',
    'week': '本周没有问题',
    'month': '本月没有问题',
    'year': '今年没有问题'
  }
  return dimensionMap[trendDimension.value] || '暂无数据'
}

// 监听窗口resize
const handleResize = () => {
  if (trendChart.value) {
    trendChart.value.resize()
  }
}
</script>

<style scoped>
.issue-page {
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
  align-items: center;
  gap: 10px;
}

.notification-settings {
  padding: 10px 0;
}

.setting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 15px;
}

.attachment-upload {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.attachment-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.no-attachment-tip {
  color: var(--ui-muted);
  font-size: 12px;
}

.no-attachment {
  color: var(--ui-muted);
  font-size: 12px;
}

.detail-container {
  padding: 10px 0;
}

.setting-item:last-child {
  margin-bottom: 0;
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

  .issue-card {
    margin-bottom: 12px;
  }

  .issue-card .card-content {
    font-size: 13px;
  }

  .issue-card .card-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 0;
  }

  .issue-card .card-row .time {
    color: var(--ui-muted);
    font-size: 12px;
  }

  .issue-card .card-row .label {
    color: var(--ui-muted);
    flex-shrink: 0;
  }

  .issue-card .card-row .value {
    color: var(--ui-heading);
    text-align: right;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 60%;
  }

  .issue-card .card-desc {
    padding: 6px 0;
  }

  .issue-card .card-desc .label {
    color: var(--ui-muted);
    display: block;
    margin-bottom: 4px;
  }

  .issue-card .card-desc p {
    margin: 0;
    color: var(--ui-heading);
    font-size: 13px;
  }

  .issue-card .card-footer {
    margin-top: 12px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
}

/* 筛选工具栏 */
.filter-toolbar {
  margin-bottom: 10px;
  padding: 8px 10px;
  background-color: var(--ui-page);
  border-radius: 4px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.filter-label {
  font-size: 14px;
  color: var(--ui-text);
  white-space: nowrap;
}

/* 表格工具栏 */
.table-toolbar {
  margin-bottom: 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.toolbar-left {
  display: flex;
  gap: 10px;
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

/* 表头背景色 */
:deep(.el-table th.el-table__cell) {
  background-color: var(--ui-page);
}

/* 统计卡片 */
.stat-cards {
  background: var(--ui-page);
  border-radius: 8px;
  padding: 10px;
  height: 100%;
  min-height: 100px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 8px;
}

.stat-card {
  background: var(--ui-surface);
  border-radius: 6px;
  padding: 6px;
  text-align: center;
  display: flex;
  flex-direction: column;
  justify-content: center;
  border: 1px solid #e8eef5;
}

.stat-card-clickable {
  cursor: pointer;
  transition: all 0.2s;
}

.stat-card-clickable:hover {
  background: var(--ui-primary-soft);
  border-color: var(--ui-primary);
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(64, 158, 255, 0.15);
}

.stat-link {
  color: var(--ui-primary);
  cursor: pointer;
  text-decoration: underline;
}

.stat-link:hover {
  color: var(--ui-primary-strong);
}

.stat-num {
  font-size: 16px;
  font-weight: bold;
  color: var(--ui-primary);
  line-height: 1.3;
}

.stat-desc {
  font-size: 12px;
  color: var(--ui-muted);
  margin-top: 4px;
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
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}

.trend-tabs {
  display: flex;
  gap: 0;
  justify-content: flex-start;
}

.trend-tabs :deep(.el-radio-button__inner) {
  padding: 4px 10px;
  font-size: 12px;
}

.trend-filters {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}

.trend-filters :deep(.el-date-editor .el-range-input) {
  font-size: 11px;
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

/* 发现者统计看板 */
.discoverer-board {
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  padding: 8px 10px;
  height: 100%;
  display: flex;
  flex-direction: column;
  font-size: 12px;
  overflow: hidden;
}
.board-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  flex: 0 0 auto;
}
.board-title {
  font-weight: 600;
  color: var(--ui-heading);
  font-size: 13px;
}
.board-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  flex: 1 1 auto;
}
.board-table thead th {
  color: var(--ui-muted);
  font-weight: 500;
  font-size: 11px;
  background: transparent;
  text-align: center;
}
.board-table th,
.board-table td {
  padding: 0;
  line-height: 20px;
  height: 24px;
  text-align: center;
  color: var(--ui-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.board-table .col-name { text-align: left; width: 38%; padding-left: 4px; font-weight: 500; color: var(--ui-heading); }
.board-table .col-num  { text-align: center; width: 20.67%; font-variant-numeric: tabular-nums; color: var(--ui-primary); }
.board-table tbody tr { transition: background 0.15s; }
.board-table tbody tr:hover { background: #f0f7ff; }
.cell-link { cursor: pointer; }
.cell-link:hover { color: #1890ff !important; }
.board-empty {
  padding: 18px 0;
  color: #c0c4cc;
  text-align: center;
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

/* 可编辑单元格样式 */
.editable-cell-text {
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
  display: block;
  min-height: 24px;
  line-height: 20px;
}

.editable-cell-text:hover {
  background-color: var(--ui-page);
}

/* 截断态：默认显示2行，第2行末尾省略号；禁止文字选中（否则单击会闪一下蓝底，且干扰拖动判断） */
.editable-cell-truncated {
  cursor: pointer;
  user-select: none;
  -webkit-user-select: none;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: break-all;
}

/* 展开态：允许文字选中（用户拖动复制） */
.editable-cell-expanded {
  user-select: text;
  -webkit-user-select: text;
  white-space: pre-wrap;
  word-break: break-all;
  cursor: text;
}

/* 展开态下整行其他列保持原高，避免抖动 */
.editable-cell-expanded:hover {
  background-color: var(--ui-warning-soft);
}

.editable-cell-edit {
  width: 100%;
}

/* 列级展开/收起图标 */
.col-expand-toggle {
  margin-left: 4px;
  cursor: pointer;
  color: var(--ui-muted);
  vertical-align: middle;
  font-size: 12px;
  transition: color 0.15s;
}
.col-expand-toggle:hover {
  color: var(--ui-primary);
}

/* 问题对话框 header：标题左对齐 + 按钮右对齐（取消/确定挪到右上角） */
.issue-dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-right: 0;
}
.issue-dialog-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--ui-heading);
}
.issue-dialog-actions {
  display: flex;
  gap: 8px;
}

/* 表单分区视觉 */
.form-section {
  margin-bottom: 8px;
}
.form-section:last-child {
  margin-bottom: 0;
}
.section-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}
.section-bar {
  display: inline-block;
  width: 3px;
  height: 12px;
  background: var(--ui-primary);
  border-radius: 2px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-heading);
}
.section-divider {
  height: 1px;
  background: var(--ui-border);
  margin: 2px 0 4px 0;
}
.tip-icon {
  margin-left: 6px;
  color: var(--ui-muted);
  cursor: help;
  font-size: 14px;
  vertical-align: middle;
}
.tip-icon:hover {
  color: var(--ui-primary);
}
:deep(.issue-form-dialog) {
  max-width: 80vw;
}
:deep(.issue-form-dialog .el-dialog__body) {
  padding: 10px 16px 16px;
}
:deep(.issue-form-dialog .el-form-item) {
  margin-bottom: 8px;
}
:deep(.issue-form-dialog .el-form-item--default) {
  margin-bottom: 8px;
}
/* datetime picker 面板紧凑化 */
:deep(.el-date-picker) {
  --el-datepicker-panel-width: 300px;
}
:deep(.el-date-picker .el-date-editor--datetime) {
  width: 100%;
}
:deep(.el-picker-panel__body) {
  font-size: 12px;
}
:deep(.el-date-picker .el-date-table td) {
  padding: 2px;
}
:deep(.el-date-picker .el-date-table th) {
  padding: 2px;
  font-size: 12px;
}
:deep(.el-date-picker .el-month-table td) {
  padding: 4px;
}
:deep(.el-date-picker .el-year-table td) {
  padding: 4px;
}
:deep(.el-date-picker .el-picker-panel__icon-btn) {
  padding: 4px;
}
:deep(.el-date-picker .el-date-picker__header) {
  margin: 4px 0;
}
:deep(.el-date-picker .el-picker-panel__content) {
  margin: 0;
}
:deep(.el-date-picker .el-time-panel) {
  width: 160px;
}
:deep(.el-date-picker .el-time-spinner) {
  width: 100%;
}
:deep(.el-date-picker .el-time-spinner__item) {
  height: 24px;
  line-height: 24px;
  font-size: 12px;
}
:deep(.el-date-picker .el-time-panel__footer) {
  padding: 4px;
}
:deep(.el-date-picker .el-time-panel__btn) {
  padding: 4px 8px;
  font-size: 12px;
}

.import-tip {
  margin-bottom: 10px;
}
</style>

<style scoped src="@/views/issue/prototype-theme.css"></style>
