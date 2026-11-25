<template>
  <div class="data-container workspace-theme workspace-management data-prototype">
    <header class="ledger-page-header"><div class="ledger-page-heading"><h1>数据汇聚</h1><p>汇聚核心业务与服务运行指标原始数据及采集状态</p></div><div class="ledger-header-actions"><el-button @click="showCollectionTasks = true"><el-icon><Tickets /></el-icon>采集任务</el-button><el-button type="primary" @click="showImportConfigDialog = true"><el-icon><Plus /></el-icon>导入配置</el-button></div></header>
    <div class="data-shell">
      <!-- 扫描健康告警面板 -->
      <el-alert
        v-if="scanHealth && !scanHealth.healthy && showScanHealthPanel"
        type="error"
        :closable="true"
        @close="showScanHealthPanel = false"
        style="margin-bottom: 12px;"
      >
        <template #title>
          <span style="font-weight: bold;">⚠ 数据扫描异常 — {{ scanHealth.issueCount }} 个数据源目录不可用</span>
        </template>
        <template #default>
          <div class="scan-health-detail">
            <div v-for="issue in scanHealth.issues" :key="`${issue.channelKey || issue.subCategoryCode}:${issue.directoryKind || '扫描'}`" class="scan-health-item">
              <div class="scan-health-item-header">
                <el-tag size="small" :type="issue.errorType === 'DOUBLE_PREFIX' ? 'warning' : 'danger'" style="margin-right: 8px;">
                  {{ issue.subCategoryName }}
                </el-tag>
                <span class="scan-health-error">{{ issue.message }}</span>
              </div>
              <div class="scan-health-path">
                <span style="color: #909399;">配置路径：</span>
                <code>{{ issue.configImportPath }}</code>
                <span style="color: #909399; margin-left: 8px;">解析为：</span>
                <code style="color: #f56c6c;">{{ issue.resolvedPath }}</code>
              </div>
              <div class="scan-health-suggestion">
                <el-icon style="color: #e6a23c; margin-right: 4px;"><Warning /></el-icon>
                <span>{{ issue.suggestion }}</span>
              </div>
            </div>
            <div class="scan-health-actions">
              <el-button size="small" type="primary" @click="showImportConfigDialog = true">
                <el-icon><Setting /></el-icon>打开导入配置
              </el-button>
              <el-button size="small" @click="loadScanHealth">
                重新检查
              </el-button>
            </div>
          </div>
        </template>
      </el-alert>

      <!-- 指标大类Tab -->
      <el-tabs v-model="activeCategory" class="category-tabs ledger-category-tabs" @tab-change="handleCategoryChange">
        <el-tab-pane
          v-for="cat in tabCategories"
          :key="cat.code"
          :label="cat.name"
          :name="cat.code"
        ><template #label><WorkspaceTabLabel :icon="getWorkspaceCategoryIcon(cat.code)" :count="cat.count">{{ cat.name }}</WorkspaceTabLabel></template>
          <div v-if="activeCategory === cat.code && !isCollectedValueView(activeCategory) && activeCategory !== '数据质量'">
            <!-- 指标小类Tab -->
            <el-tabs v-model="activeSubcategory" class="subcategory-tabs" @tab-change="handleSubcategoryChange">
              <el-tab-pane
                v-for="sub in subcategories"
                :key="sub.code"
                :label="sub.name"
                :name="sub.code"
              ><template #label><span class="data-subcategory-label"><i aria-hidden="true"></i>{{ sub.name }}</span></template>
              </el-tab-pane>
            </el-tabs>
          </div>
        </el-tab-pane>
        <el-tab-pane label="满意度及投诉服务体验" name="satisfactionReport"><template #label><WorkspaceTabLabel icon="ChatDotRound">满意度及投诉服务体验</WorkspaceTabLabel></template>
          <ComplaintReport v-if="activeCategory === 'satisfactionReport'" />
        </el-tab-pane>
        <el-tab-pane label="资源投入" name="resourceInput"><template #label><WorkspaceTabLabel icon="Coin">资源投入</WorkspaceTabLabel></template>
          <RawDataTable v-if="activeCategory === 'resourceInput'" category-name="资源投入" />
        </el-tab-pane>
        <el-tab-pane label="业务价值" name="businessValue"><template #label><WorkspaceTabLabel icon="TrendCharts">业务价值</WorkspaceTabLabel></template>
          <RawDataTable v-if="activeCategory === 'businessValue'" category-name="业务价值" />
        </el-tab-pane>

      </el-tabs>

      <!-- 第三级Tab多选 + 数据展示 -->
      <SubcategoryWorkspace layout="tabs" :enabled="false" :items="subcategories" v-model="activeSubcategory" @change="handleSubcategoryChange">
      <ExternalCustomerSimulation v-if="activeCategory === '对外部客户' && activeSubcategory" :key="activeSubcategory" :sub-category="activeSubcategory" />
      <template v-if="isCollectedValueView(activeCategory)">
        <RawDataTable :key="activeCategory" :category-name="activeCategory" />
      </template>
      <RawDataTable v-if="activeCategory === '数据质量'" category-name="数据质量" />
      <div v-if="activeSubcategory && activeCategory !== '对外部客户' && activeCategory !== '数据质量' && !isCollectedValueView(activeCategory)" class="content-area">
        <el-empty v-if="!activeTableName" description="该数据源暂未配置采集展示" />
        <!-- 左侧：指标属性多选（system_jhd 显示） -->
        <div v-if="activeTableName === 'system_jhd'" class="attr-sidebar">
          <div class="attr-header">
            <span>指标属性</span>
            <div class="attr-actions">
              <el-button size="small" link @click="selectAllAttrs">全选</el-button>
              <el-button size="small" link @click="deselectAllAttrs">反选</el-button>
            </div>
          </div>
          <div class="attr-list">
            <template v-for="group in attributes" :key="group.first_classify">
              <div class="attr-group">
                <el-checkbox
                  :model-value="isCategoryAllSelected(group)"
                  :indeterminate="isCategoryPartiallySelected(group)"
                  class="attr-category-checkbox"
                  @change="(checked) => handleCategoryToggle(group, checked)"
                >
                  {{ group.first_classify }}
                </el-checkbox>
                <div class="attr-children">
                  <el-checkbox
                    v-for="attr in group.second_classifies"
                    :key="attr"
                    v-model="selectedAttrs"
                    :label="attr"
                    class="attr-checkbox"
                  >
                    {{ attr }}
                  </el-checkbox>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- 左侧：第三级Tab（对内部人员 - 点击切换样式 + 多选） -->
        <div v-if="activeCategory === '对内部人员' && currentLevel2Tabs.length > 0" class="attr-sidebar">
          <div class="attr-header">
            <span>指标类型</span>
            <div class="attr-actions">
              <el-button size="small" link @click="selectAllLevel2Tabs">全选</el-button>
              <el-button size="small" link @click="deselectAllLevel2Tabs">反选</el-button>
            </div>
          </div>
          <div class="attr-list">
            <el-checkbox-group v-model="selectedLevel2Tabs">
              <div
                v-for="tab in currentLevel2Tabs"
                :key="tab.code"
                class="attr-item attr-item-clickable"
                :class="{ 'attr-item-active': selectedLevel2Tabs.includes(tab.code) }"
              >
                <el-checkbox :value="tab.code">
                  {{ tab.name }}
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </div>

        <!-- 左侧：系统运行-系统性能 指标分组 -->
        <div v-if="showSystemRunSidebar" class="attr-sidebar">
          <div class="attr-header">
            <span>指标</span>
            <div class="attr-actions">
              <el-button size="small" link @click="selectAllSystemRunAll">全选</el-button>
              <el-button size="small" link @click="deselectAllSystemRunAll">反选</el-button>
            </div>
          </div>
          <div class="attr-list">
            <template v-for="(groupData, groupKey) in systemRunMetricGroups" :key="groupKey">
              <div class="attr-group">
                <el-checkbox
                  :model-value="isSystemRunGroupAllSelected(groupKey)"
                  :indeterminate="isSystemRunGroupPartiallySelected(groupKey)"
                  class="attr-category-checkbox"
                  @change="(checked) => checked ? selectAllSystemRunGroup(groupKey) : deselectAllSystemRunGroup(groupKey)"
                >
                  {{ groupKey }}
                </el-checkbox>
                <div class="attr-children">
                  <el-checkbox
                    v-for="metricName in groupData.summary"
                    :key="metricName"
                    :model-value="selectedSystemRunMetrics.includes(metricName)"
                    class="attr-checkbox"
                    @change="() => toggleSystemRunMetric(metricName)"
                  >
                    {{ metricName }}
                  </el-checkbox>
                  <el-checkbox
                    v-for="metricName in groupData.detail"
                    :key="metricName"
                    :model-value="selectedSystemRunMetrics.includes(metricName)"
                    class="attr-checkbox"
                    @change="() => toggleSystemRunMetric(metricName)"
                  >
                    {{ metricName }} [明细]
                  </el-checkbox>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- 左侧：系统运行-系统异常 指标分组 -->
        <div v-if="showSystemErrorSidebar" class="attr-sidebar">
          <div class="attr-header">
            <span>指标</span>
            <div class="attr-actions">
              <el-button size="small" link @click="selectAllSystemError">全选</el-button>
              <el-button size="small" link @click="deselectAllSystemError">反选</el-button>
            </div>
          </div>
          <div class="attr-list">
            <template v-for="group in Object.keys(systemErrorMetricGroups)" :key="group">
              <div class="attr-group">
                <el-checkbox
                  :model-value="isSystemErrorGroupAllSelected(group)"
                  :indeterminate="isSystemErrorGroupPartiallySelected(group)"
                  class="attr-category-checkbox"
                  @change="(checked) => handleSystemErrorGroupToggle(group, checked)"
                >
                  {{ group }}
                </el-checkbox>
                <div class="attr-children">
                  <el-checkbox
                    v-for="metric in systemErrorAllMetrics.filter(m => m.group === group)"
                    :key="metric.name"
                    v-model="selectedSystemErrorMetrics"
                    :label="metric.name"
                    class="attr-checkbox"
                  >
                    {{ metric.name }}
                  </el-checkbox>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- 右侧数据区域 -->
        <div v-if="activeTableName" class="data-area">
          <!-- 指标属性多选（system_jhd、查询类、对外部客户-系统稽核 显示） -->
          <div v-if="activeTableName === 'system_jhd' || (activeCategory === '对内部人员' && activeSubcategory === '查询类') || showSystemRunSidebar || showSystemErrorSidebar" class="filter-row" v-balanced-query>
            <el-input
              v-if="activeTableName === 'system_jhd'"
              v-model="jhdParams.checkCode"
              placeholder="稽核点编号"
              clearable
              style="width: 100px;"
              size="small"
            />
            <el-input
              v-if="activeTableName === 'system_jhd'"
              v-model="jhdParams.checkName"
              placeholder="稽核点名称"
              clearable
              style="width: 140px;"
              size="small"
            />
            <el-input
              v-if="activeTableName === 'system_jhd'"
              v-model="jhdParams.businessOwner"
              placeholder="业务主管"
              clearable
              style="width: 80px;"
              size="small"
            />
            <el-select
              v-if="activeTableName === 'system_jhd'"
              v-model="jhdParams.checkFreq"
              placeholder="频率"
              clearable
              style="width: 70px;"
              size="small"
            >
              <el-option label="日" value="日" />
              <el-option label="月" value="月" />
            </el-select>
            <el-date-picker
              v-if="activeTableName === 'system_jhd'"
              v-model="jhdParams.abnormalDate"
              type="date"
              placeholder="异常日期"
              clearable
              style="width: 140px;"
              size="small"
              value-format="YYYY-MM-DD"
            />
            <el-date-picker
              v-if="activeCategory === '对内部人员' && activeSubcategory === '查询类'"
              v-model="innerParams.dataDate"
              type="date"
              placeholder="数据日期"
              clearable
              style="width: 120px;"
              size="small"
              value-format="YYYY-MM-DD"
            />
            <el-input
              v-if="activeCategory === '对内部人员' && activeSubcategory === '查询类'"
              v-model="innerParams.businessName"
              placeholder="业务名称"
              clearable
              style="width: 160px;"
              size="small"
            />
            <div class="query-actions"><el-button type="primary" size="small" @click="showSystemRunSidebar ? loadSystemRunData() : showSystemErrorSidebar ? loadSystemErrorData() : loadData()">
              <el-icon><Search /></el-icon>查询
            </el-button><el-button size="small" @click="resetFilters">重置</el-button></div>
            <span v-if="showSystemRunSidebar" style="font-size: 11px; color: #909399; margin-left: 4px;">已选 {{ selectedSystemRunMetrics.length }} / {{ systemRunAllMetrics.length }} 项</span>
            <span v-else-if="showSystemErrorSidebar" style="font-size: 11px; color: #909399; margin-left: 4px;">已选 {{ selectedSystemErrorMetrics.length }} / {{ systemErrorAllMetrics.length }} 项</span>
            <span v-else-if="selectedAttrs.length > 0" style="font-size: 11px; color: #909399; margin-left: 4px;">已选 {{ selectedAttrs.length }} / {{ totalAttrCount }} 项</span>
            <div class="toolbar-spacer"></div>
            <el-popover popper-class="workspace-theme workspace-popover" v-if="activeTableName !== 'system_jhd' && !showSystemRunSidebar" placement="bottom" :width="280" trigger="click">
              <template #reference>
                <el-button size="small">
                  <el-icon><Setting /></el-icon>列设置 ({{ visibleColumns.length }}/{{ currentColumns.length }})
                </el-button>
              </template>
              <div style="margin-bottom: 8px; color: #909399; font-size: 12px;">已选 {{ visibleColumns.length }} / {{ currentColumns.length }} 列</div>
              <el-checkbox-group v-model="visibleColumns" class="column-toggle-group" @change="handleColumnChange">
                <el-checkbox v-for="col in currentColumns" :key="col.prop" :value="col.prop" class="column-toggle-item">
                  {{ col.label }}
                </el-checkbox>
              </el-checkbox-group>
            </el-popover>
            <el-popover popper-class="workspace-theme workspace-popover" v-if="activeTableName === 'system_jhd' && !showSystemRunSidebar" placement="bottom" :width="280" trigger="click">
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

          <!-- inner_* 表筛选条件（单业务/融合业务） -->
          <div v-if="isInnerBusinessSelected" class="filter-row" v-balanced-query>
            <el-date-picker
              v-model="innerParams.dataDate"
              type="date"
              placeholder="数据日期"
              clearable
              style="width: 120px;"
              size="small"
              value-format="YYYY-MM-DD"
            />
            <el-input
              v-model="innerParams.businessName"
              placeholder="业务名称"
              clearable
              style="width: 160px;"
              size="small"
            />
            <div class="query-actions"><el-button type="primary" size="small" @click="loadData">
              <el-icon><Search /></el-icon>查询
            </el-button><el-button size="small" @click="resetFilters">重置</el-button></div>
            <span v-if="selectedLevel2Tabs.length > 0" style="font-size: 11px; color: #909399; margin-left: 4px;">已选 {{ selectedLevel2Tabs.length }} / {{ currentLevel2Tabs.length }} 项</span>
            <div class="toolbar-spacer"></div>
          </div>

          <!-- 工单明细筛选条件 -->
          <div v-if="activeTableName === 'system_jhd_detail'" class="filter-row" v-balanced-query>
            <el-input
              v-model="jhdDetailParams.auditId"
              placeholder="稽核点编号"
              clearable
              style="width: 100px;"
              size="small"
            />
            <el-input
              v-model="jhdDetailParams.auditName"
              placeholder="稽核点名称"
              clearable
              style="width: 140px;"
              size="small"
            />
            <el-input
              v-model="jhdDetailParams.supervisor"
              placeholder="负责主管"
              clearable
              style="width: 80px;"
              size="small"
            />
            <el-input
              v-model="jhdDetailParams.wsMonth"
              placeholder="工单月份"
              clearable
              style="width: 90px;"
              size="small"
            />
            <el-input
              v-model="jhdDetailParams.department"
              placeholder="部门"
              clearable
              style="width: 90px;"
              size="small"
            />
            <el-input
              v-model="jhdDetailParams.firstCategory"
              placeholder="一级分类"
              clearable
              style="width: 90px;"
              size="small"
            />
            <el-input
              v-model="jhdDetailParams.secondCategory"
              placeholder="二级分类"
              clearable
              style="width: 90px;"
              size="small"
            />
            <div class="query-actions"><el-button type="primary" size="small" @click="loadData">
              <el-icon><Search /></el-icon>查询
            </el-button><el-button size="small" @click="resetJhdDetailFilters">重置</el-button></div>
            <div class="toolbar-spacer"></div>
          </div>

          <!-- 桌面端表格 - system_jhd -->
          <div v-if="activeTableName === 'system_jhd'" class="desktop-table">
            <el-table v-sticky-header v-mirror-scroll :data="tableData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true" @header-contextmenu="handleHeaderContextMenu">
              <el-table-column v-if="!isColumnHidden('insert_time')" prop="insert_time" label="入库时间" width="160" :formatter="(row)=>row.insert_time ? row.insert_time.replace('T',' ') : ''" />
              <el-table-column v-if="!isColumnHidden('create_time')" prop="create_time" label="数据生成时间" width="150" />
              <el-table-column v-if="!isColumnHidden('abnormal_date')" prop="abnormal_date" label="异常数据时间" width="120" />
              <el-table-column v-if="!isColumnHidden('first_classify')" prop="first_classify" label="一级分类" width="100" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('second_classify')" prop="second_classify" label="二级分类" width="100" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('check_code')" prop="check_code" label="稽核点编号" width="100" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('check_name')" prop="check_name" label="稽核点名称" min-width="200" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('check_rule')" prop="check_rule" label="稽核口径" min-width="300" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('check_freq')" prop="check_freq" label="频率" width="80" />
              <el-table-column v-if="!isColumnHidden('abnormal_count')" prop="abnormal_count" label="异常量" width="80" />
              <el-table-column v-if="!isColumnHidden('business_owner')" prop="business_owner" label="业务主管" width="100" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('current_step')" prop="current_step" label="当前环节" width="120" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('order_2month')" prop="order_2month" label="近两月派单量" width="120" />
              <el-table-column v-if="!isColumnHidden('unclosed_2month')" prop="unclosed_2month" label="近两月未关单量" width="120" />
              <el-table-column v-if="!isColumnHidden('develop_time')" prop="develop_time" label="负责部门" width="100" />
              <el-table-column v-if="!isColumnHidden('source_file')" prop="source_file" label="来源文件名" width="180" class-name="wrap-cell" />
            </el-table>
          </div>

          <!-- 桌面端表格 - system_jhd_detail (工单明细) -->
          <div v-if="activeTableName === 'system_jhd_detail'" class="desktop-table">
            <el-table v-sticky-header v-mirror-scroll :data="tableData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true">
              <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row)=>row.insert_time ? formatTimestamp(row.insert_time) : ''" />
              <el-table-column prop="department" label="部门" width="100" class-name="wrap-cell" />
              <el-table-column prop="first_level_category" label="一级分类" width="100" class-name="wrap-cell" />
              <el-table-column prop="second_level_category" label="二级分类" width="100" class-name="wrap-cell" />
              <el-table-column prop="ws_id" label="工单编码" width="180" class-name="wrap-cell" />
              <el-table-column prop="audit_id" label="稽核点编号" width="120" class-name="wrap-cell" />
              <el-table-column prop="audit_name" label="稽核点名称" min-width="200" class-name="wrap-cell" />
              <el-table-column prop="business_supervisor_name" label="负责主管" width="100" class-name="wrap-cell" />
              <el-table-column prop="deal_person" label="当前处理人" width="100" class-name="wrap-cell" />
              <el-table-column prop="deal_person_name" label="处理人姓名" width="100" class-name="wrap-cell" />
              <el-table-column prop="status" label="工单状态" width="100" class-name="wrap-cell" />
              <el-table-column prop="if_ok" label="是否完工" width="90" class-name="wrap-cell" />
              <el-table-column prop="create_date" label="工单派发时间" width="110" :formatter="(row)=>row.create_date ? row.create_date.replace('T',' ').substring(0,16) : ''" />
              <el-table-column prop="modify_date" label="实际处理时间" width="110" :formatter="(row)=>row.modify_date ? row.modify_date.replace('T',' ').substring(0,16) : ''" />
              <el-table-column prop="limit_date" label="处理逾期时间" width="110" :formatter="(row)=>row.limit_date ? row.limit_date.replace('T',' ').substring(0,16) : ''" />
              <el-table-column prop="if_limit_ok" label="是否逾期" width="90" class-name="wrap-cell" />
              <el-table-column prop="remark" label="处理信息" min-width="200" class-name="wrap-cell" />
              <el-table-column prop="is_need_rectify" label="是否需要整改" width="110" class-name="wrap-cell" />
              <el-table-column prop="rectify_reason" label="整改原因" width="120" class-name="wrap-cell" />
              <el-table-column prop="rectify_status" label="整改状态" width="100" class-name="wrap-cell" />
              <el-table-column prop="fix_situation" label="修复情况" width="100" class-name="wrap-cell" />
              <el-table-column prop="fix_count" label="修复数量" width="90" />
              <el-table-column prop="rectify_deadline" label="预计整改完成时间" width="110" :formatter="(row)=>row.rectify_deadline ? row.rectify_deadline.replace('T',' ').substring(0,16) : ''" />
              <el-table-column prop="rectify_deadline_sj" label="实际整改完成时间" width="110" :formatter="(row)=>row.rectify_deadline_sj ? row.rectify_deadline_sj.replace('T',' ').substring(0,16) : ''" />
              <el-table-column prop="is_overdue" label="整改是否逾期" width="110" class-name="wrap-cell" />
              <el-table-column prop="rsrv_str1" label="工单月份" width="100" />
              <el-table-column prop="source_file" label="来源文件" width="180" class-name="wrap-cell" />
            </el-table>
            <el-pagination
              v-model:current-page="pagination.page"
              v-model:page-size="pagination.size"
              :total="pagination.total"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next, jumper"
              style="margin-top: 10px; justify-content: flex-end;"
              @size-change="loadData"
              @current-change="loadData"
            />
          </div>

          <!-- 桌面端表格 - inner_query -->
          <div v-if="activeTableName === 'inner_query'" class="desktop-table">
            <el-table v-sticky-header v-mirror-scroll :data="tableData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true" @header-contextmenu="handleHeaderContextMenu">
              <el-table-column v-if="!isColumnHidden('insert_time')" prop="insert_time" label="入库时间" width="160" :formatter="(row)=>row.insert_time ? row.insert_time.replace('T',' ') : ''" />
              <el-table-column v-if="!isColumnHidden('business_name')" prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('step_type')" prop="step_type" label="步骤类型" width="100" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('step_name')" prop="step_name" label="步骤名称" min-width="160" class-name="wrap-cell" />
              <el-table-column v-if="!isColumnHidden('access_count')" prop="access_count" label="访问量" width="100" />
              <el-table-column v-if="!isColumnHidden('avg_duration_sec')" prop="avg_duration_sec" label="平均时长(秒)" width="110" />
            </el-table>
          </div>

          <!-- 单业务/融合业务：两个表格区域 -->
          <div v-if="isInnerBusinessSelected" class="inner-business-area">
            <!-- 第一区域：步骤响应数据 (inner_step_response) -->
            <div v-if="showStepResponseArea" class="step-response-area">
              <div class="area-title">步骤响应数据</div>
              <el-table v-sticky-header v-mirror-scroll :data="stepResponseData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true">
                <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row)=>row.insert_time ? row.insert_time.replace('T',' ') : ''" />
                <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
                <el-table-column prop="step_type" label="步骤类型" width="100" class-name="wrap-cell" />
                <el-table-column prop="step_name" label="步骤名称" min-width="160" class-name="wrap-cell" />
                <el-table-column prop="access_count" label="访问量" width="100" />
                <el-table-column prop="avg_response_sec" label="响应时长(秒)" width="110" />
                <el-table-column prop="interval_sec" label="间隔时长(秒)" width="110" />
              </el-table>
              <el-pagination
                v-model:current-page="pagination.page"
                v-model:page-size="pagination.size"
                :total="pagination.total"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next, jumper"
                style="margin-top: 10px; justify-content: flex-end;"
                @size-change="loadData"
                @current-change="loadData"
              />
            </div>

            <!-- 第二区域：办理按钮数据 (inner_button_count) -->
            <div v-if="showButtonCountArea" class="button-count-area">
              <div class="area-title">办理按钮数据</div>
              <el-table v-sticky-header v-mirror-scroll :data="buttonCountData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true">
                <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row)=>row.insert_time ? row.insert_time.replace('T',' ') : ''" />
                <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
                <el-table-column prop="button_count" label="办理按钮总数" width="110" />
              </el-table>
              <el-pagination
                v-model:current-page="pagination2.page"
                v-model:page-size="pagination2.size"
                :total="pagination2.total"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next, jumper"
                style="margin-top: 10px; justify-content: flex-end;"
                @size-change="loadButtonCountData"
                @current-change="loadButtonCountData"
              />
            </div>

            <!-- 第三区域：全程办理时长数据 (inner_business_duration) -->
            <div v-if="showBusinessDurationArea" class="business-duration-area">
              <div class="area-title">全程办理时长</div>
              <el-table v-sticky-header v-mirror-scroll :data="businessDurationData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true">
                <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="(row)=>row.insert_time ? row.insert_time.replace('T',' ') : ''" />
                <el-table-column prop="business_name" label="业务名称" min-width="180" class-name="wrap-cell" />
                <el-table-column prop="full_duration_sec" label="全程办理时长(秒)" width="130" />
                <el-table-column prop="full_interval_sec" label="全程操作间隔时长(秒)" width="160" />
                <el-table-column prop="full_service_sec" label="全程服务耗时(秒)" width="140" />
                <el-table-column prop="access_count" label="访问量" width="100" />
                <el-table-column prop="failure_count" label="失败量" width="100" />
              </el-table>
              <el-pagination
                v-model:current-page="pagination3.page"
                v-model:page-size="pagination3.size"
                :total="pagination3.total"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next, jumper"
                style="margin-top: 10px; justify-content: flex-end;"
                @size-change="loadBusinessDurationData"
                @current-change="loadBusinessDurationData"
              />
            </div>
          </div>

          <!-- 系统运行-系统性能 数据展示区域 -->
          <div v-if="showSystemRunSidebar" class="inner-business-area">
            <!-- 汇总数据表格 -->
            <div v-if="needShowSummaryTable" class="step-response-area">
              <div class="area-title">汇总数据</div>
              <el-table v-sticky-header v-mirror-scroll :data="systemRunSummaryData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true">
                <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="formatDataTime" />
                <el-table-column prop="metric_attr" label="指标属性" width="100" class-name="wrap-cell" />
                <el-table-column prop="metric_name" label="指标名称" min-width="180" class-name="wrap-cell" />
                <el-table-column prop="object_name" label="对象名称" width="140" class-name="wrap-cell" />
                <el-table-column prop="metric_value" label="指标值" width="100" />
                <el-table-column prop="unit" label="单位" width="80" />
              </el-table>
              <el-pagination
                v-model:current-page="pagination.page"
                v-model:page-size="pagination.size"
                :total="systemRunSummaryTotal"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next, jumper"
                style="margin-top: 10px; justify-content: flex-end;"
                @size-change="loadSystemRunData"
                @current-change="loadSystemRunData"
              />
            </div>

            <!-- 明细数据表格 -->
            <div v-if="needShowDetailTable" class="button-count-area">
              <div class="area-title">明细数据</div>
              <el-table v-sticky-header v-mirror-scroll :data="systemRunDetailData" border v-loading="tableLoading" style="width: 100%;" :scroll-x="true">
                <el-table-column prop="insert_time" label="入库时间" width="160" :formatter="formatDataTime" />
                <el-table-column prop="object_name" label="对象名称" min-width="180" class-name="wrap-cell" />
                <el-table-column prop="object_url" label="对象URL" min-width="200" class-name="wrap-cell" />
                <el-table-column prop="access_count" label="访问量" width="100" />
                <el-table-column prop="metric_value" label="响应时长(ms)" width="120" />
              </el-table>
              <el-pagination
                v-model:current-page="pagination.page"
                v-model:page-size="pagination.size"
                :total="systemRunDetailTotal"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next, jumper"
                style="margin-top: 10px; justify-content: flex-end;"
                @size-change="loadSystemRunData"
                @current-change="loadSystemRunData"
              />
            </div>
          </div>

          <!-- 系统运行-系统异常 数据展示区域 -->
          <div v-if="showSystemErrorSidebar" class="inner-business-area">
            <div v-for="tableName in selectedSystemErrorTableNames" :key="tableName" class="step-response-area">
              <div class="area-title">{{ getSystemErrorTableTitle(tableName) }}</div>
              <el-table v-sticky-header v-mirror-scroll :data="getSystemErrorTableData(tableName)" border v-loading="tableLoading" stripe>
                <el-table-column
                  v-for="col in (tableColumnOptions[tableName] || [])"
                  :key="col.prop"
                  :prop="col.prop"
                  :label="col.label"
                  :formatter="col.formatter || null"
                  class-name="wrap-cell"
                />
              </el-table>
              <el-pagination
                :current-page="systemErrorPagination[tableName]?.page || 1"
                :page-size="systemErrorPageSize"
                :total="systemErrorPagination[tableName]?.total || 0"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next, jumper"
                style="margin-top: 10px; justify-content: flex-end;"
                @size-change="(size) => handleSystemErrorSizeChange(tableName, size)"
                @current-change="(page) => handleSystemErrorPageChange(tableName, page)"
              />
            </div>
            <el-empty v-if="!needShowSystemErrorTable" description="请从左侧选择指标" />
          </div>

          <!-- 移动端卡片 -->
          <div class="mobile-cards">
            <el-card v-for="row in tableData" :key="row.id" class="data-card" shadow="hover">
              <div class="card-content">
                <div class="card-row">
                  <span class="label">指标编码：</span>
                  <span class="value">{{ row.check_code }}</span>
                </div>
                <div class="card-row">
                  <span class="label">指标名称：</span>
                  <span class="value">{{ row.check_name }}</span>
                </div>
                <div class="card-row">
                  <span class="label">业务负责人：</span>
                  <span class="value">{{ row.business_owner }}</span>
                </div>
                <div class="card-row">
                  <span class="label">异常日期：</span>
                  <span class="value">{{ row.abnormal_date }}</span>
                </div>
              </div>
            </el-card>
            <el-empty v-if="tableData.length === 0" description="暂无数据" />
          </div>

          <!-- 分页（仅system_jhd和查询类使用） -->
          <el-pagination
            v-if="activeTableName === 'system_jhd' || activeTableName === 'inner_query'"
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.size"
            :total="pagination.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            style="margin-top: 10px; justify-content: flex-end;"
            @size-change="loadData"
            @current-change="loadData"
          />
        </div>
      </div>

      </SubcategoryWorkspace>

      <el-drawer class="workspace-theme" v-model="showCollectionTasks" title="采集任务管理" size="min(1500px, 96vw)" append-to-body destroy-on-close>
        <CollectionTasks v-if="showCollectionTasks" />
      </el-drawer>

      <!-- 导入配置对话框 -->
      <div style="position: relative;">
        <DataImportConfigurationDialog v-model="showImportConfigDialog" :width="importConfigDialogWidth" :base-path="basePath" :scan-health="scanHealth" :scan-health-loading="scanHealthLoading" :selected-import-configs="selectedImportConfigs" :import-config-list="importConfigList" @add="handleImportConfigAdd" @edit="handleBatchEdit" @delete="handleBatchDelete" @scan="handleBatchScan" @recheck="loadScanHealth" @selection-change="handleImportSelectionChange" />
      <DialogSizeControls :adjustWidth="adjustImportConfigDialogWidth" :visible="showImportConfigDialog" />
      </div>

      <!-- 导入配置编辑对话框 -->
      <div style="position: relative;">
        <el-dialog class="workspace-theme" v-model="showImportConfigForm" :title="importConfigForm.id ? '编辑配置' : '新增配置'" :width="importConfigFormWidth">
        <el-form :model="importConfigForm" label-width="100px">
          <el-alert v-if="importConfigForm.collectorAvailable === false" title="此通道尚未接入，不能启用；原目录中的文件会保留。" type="warning" :closable="false" />
          <el-form-item label="小类编码">
            <el-select v-model="importConfigForm.subCategoryCode" placeholder="选择小类" filterable :disabled="!!importConfigForm.id">
              <el-option label="系统异常 (error)" value="error" />
              <el-option label="系统性能 (performance)" value="performance" />
              <el-option label="系统稽核 (jihe)" value="jihe" />
            </el-select>
          </el-form-item>
          <el-form-item label="监控目录">
            <el-input v-model="importConfigForm.importPath" placeholder="如: raw_data/import/error/" />
            <div class="resolved-path-hint" v-if="importConfigForm.importPath">
              <span v-if="isAbsolutePath(importConfigForm.importPath)">
                <el-icon style="color: #409eff; margin-right: 2px;"><InfoFilled /></el-icon>
                绝对路径，直接扫描：<code>{{ importConfigForm.importPath }}</code>
              </span>
              <span v-else>
                <el-icon style="color: #e6a23c; margin-right: 2px;"><Warning /></el-icon>
                实际扫描路径：<code>{{ basePath }}/{{ importConfigForm.importPath.replace(/^\//, '') }}</code>
              </span>
            </div>
          </el-form-item>
          <el-form-item label="归档目录">
            <el-input v-model="importConfigForm.archivePath" placeholder="如: raw_data/archive/error/" />
            <div class="resolved-path-hint" v-if="importConfigForm.archivePath">
              <span v-if="isAbsolutePath(importConfigForm.archivePath)">
                <el-icon style="color: #409eff; margin-right: 2px;"><InfoFilled /></el-icon>
                绝对路径，直接归档到：<code>{{ importConfigForm.archivePath }}</code>
              </span>
              <span v-else>
                <el-icon style="color: #e6a23c; margin-right: 2px;"><Warning /></el-icon>
                实际归档路径：<code>{{ basePath }}/{{ importConfigForm.archivePath.replace(/^\//, '') }}</code>
              </span>
            </div>
          </el-form-item>
          <el-form-item label="扫描间隔(分钟)">
            <el-input-number v-model="importConfigForm.scanIntervalMinutes" :min="1" :max="60" :disabled="importConfigForm.collectorAvailable === false" />
            <span>每10秒检查到期任务，按本配置间隔扫描</span>
          </el-form-item>
          <el-form-item label="是否启用">
            <el-switch v-model="importConfigForm.enabled" :active-value="1" :inactive-value="0" :disabled="importConfigForm.collectorAvailable === false" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="showImportConfigForm = false">取消</el-button>
          <el-button type="primary" @click="saveImportConfig">确定</el-button>
        </template>
      </el-dialog>
      <DialogSizeControls :adjustWidth="adjustImportConfigFormWidth" :visible="showImportConfigForm" />
      </div>
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
    <div
      v-if="showHeaderContextMenu"
      class="context-menu-overlay"
      @click="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    />
  </div>
</template>

<script setup>
import { getWorkspaceCategoryIcon } from '@/utils/workspaceCategoryIcons'
import WorkspaceTabLabel from '@/components/workspace/WorkspaceTabLabel.vue'
import DataImportConfigurationDialog from '@/components/data/DataImportConfigurationDialog.vue'
import { ref, onMounted, watch, computed, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dataApi, errorImportApi } from '../../api'
import { Setting, Warning, InfoFilled } from '@element-plus/icons-vue'
import { useDialogSize } from '../../utils/useDialogSize'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'
import ComplaintReport from './ComplaintReport.vue'
import CollectionTasks from './CollectionTasks.vue'
import ExternalCustomerSimulation from './ExternalCustomerSimulation.vue'
import { loadRunningEfficiencySubcategories } from '../../utils/runningEfficiencyCategories'
import SubcategoryWorkspace from '../../components/common/SubcategoryWorkspace.vue'
import RawDataTable from './RawDataTable.vue'


// 弹窗宽度控制
const { width: importConfigDialogWidth, adjustWidth: adjustImportConfigDialogWidth } = useDialogSize('data-import-config', '600px')
const { width: importConfigFormWidth, adjustWidth: adjustImportConfigFormWidth } = useDialogSize('data-import-config-form', '500px')

const route = useRoute()

// 数据汇聚只回放采集到的原始数据；对内部人员、运行效率迁移到 CSV/Python 采集后，
// 原始明细表（inner_* / metric_system_run_* / metric_error_*）已停止写入，统一走原始数据视图。
const isCollectedValueView = (category) => category === '对内部人员' || category === '运行效率'

// 目录结构：指标大类 -> 指标小类
const categoryStructure = {
  '对外部客户': {
    name: '对外部客户',
    subcategories: {
      '缴费开机': { name: '缴费开机', simulation: true },
      '流量提醒': { name: '流量提醒', simulation: true }
    }
  },
  '合规管理': {
    name: '合规管理',
    subcategories: {
      '月数据清单': { name: '月数据清单', tableName: 'system_jhd' },
      '工单明细': { name: '工单明细', tableName: 'system_jhd_detail' }
    }
  },
  '对内部人员': {
    name: '对内部人员',
    // 查询类只有2个step_type选项
    level2TabsMap: {
      '查询类': ['页面加载', '查询时长'],
      '办理类-单业务': ['页面加载', '步骤时长', '办理按钮总数', '全程办理时长'],
      '办理类-融合业务': ['页面加载', '步骤时长', '办理按钮总数', '全程办理时长']
    },
    subcategories: {
      '查询类':         { name: '查询类',         tableName: 'inner_query', subCategory: '' },
      '办理类-单业务':  { name: '办理类-单业务',   tableName: 'inner_step_response', subCategory: 'crm' },
      '办理类-融合业务': { name: '办理类-融合业务', tableName: 'inner_step_response', subCategory: 'chbn' }
    }
  },
  '运行效率': {
    name: '运行效率',
    subcategories: {
      '系统性能': { name: '系统性能', hasMetricGroups: true },
      '系统异常': { name: '系统异常', dbMapping: null }
    }
  },
  '数据质量': {
    name: '数据质量',
    // 走通用实时指标表：数据质量没有原始明细表，指标值来自统一观测读取
    realtimeTable: true,
    subcategories: {
      '业务指标准确性': { name: '业务指标准确性' },
      '跨系统数据及时性': { name: '跨系统数据及时性' },
      '数据一致性': { name: '数据一致性' },
      '数据波动性': { name: '数据波动性' },
      '枚举值异常触发': { name: '枚举值异常触发' },
      '字段准确性': { name: '字段准确性' }
    }
  },
  '计划执行': {
    name: '计划执行',
    subcategories: {
      '工单执行': { name: '工单执行', dbMapping: null }
    }
  }
}

// 仪表盘数据源 -> Tab 映射
const sourceTabMapping = {
  'CRM': { categoryName: '对外部客户', subCategoryName: '流量提醒' },
  'BOSS': { categoryName: '对外部客户', subCategoryName: '缴费开机' },
  '稽核系统': { categoryName: '合规管理', subCategoryName: '月数据清单' },
  '端到端监控': { categoryName: '对内部人员' },
  'BOMC': { categoryName: '计划执行' }
}

// inner_* 表的列配置
// 格式化时间戳（去除纳秒精度）
const formatTimestamp = (val) => {
  if (!val) return ''
  return val.replace('T', ' ').split('.')[0]
}

const formatTime = (row, col, val) => {
  if (!val) return ''
  return val.replace('T', ' ')
}

// 系统运行 data_time 格式化：年-月-日 时:分:秒
const formatDataTime = (row, col, val) => {
  if (!val) return ''
  // val 格式: 2026-04-01T15:52:34.84736
  const parts = val.split('T')
  if (parts.length !== 2) return val
  const datePart = parts[0] // 2026-04-01
  const timePart = parts[1].split('.')[0] // 15:52:34
  return `${datePart} ${timePart}`
}

const tableColumnOptions = {
  'inner_query': [
    { prop: 'insert_time', label: '入库时间', formatter: formatTime },
    { prop: 'business_name', label: '业务名称' },
    { prop: 'step_type', label: '步骤类型' },
    { prop: 'step_name', label: '步骤名称' },
    { prop: 'access_count', label: '访问量' },
    { prop: 'avg_duration_sec', label: '平均时长(秒)' }
  ],
  'inner_step_response': [
    { prop: 'insert_time', label: '入库时间', formatter: formatTime },
    { prop: 'business_name', label: '业务名称' },
    { prop: 'step_type', label: '步骤类型' },
    { prop: 'step_name', label: '步骤名称' },
    { prop: 'access_count', label: '访问量' },
    { prop: 'avg_response_sec', label: '响应时长(秒)' },
    { prop: 'interval_sec', label: '间隔时长(秒)' }
  ],
  'inner_button_count': [
    { prop: 'insert_time', label: '入库时间', formatter: formatTime },
    { prop: 'business_name', label: '业务名称' },
    { prop: 'button_count', label: '办理按钮总数' }
  ],
  'system_run_summary': [
    { prop: 'insert_time', label: '入库时间', formatter: formatTime },
    { prop: 'metric_attr', label: '指标属性' },
    { prop: 'metric_name', label: '指标名称' },
    { prop: 'object_name', label: '对象名称' },
    { prop: 'metric_value', label: '指标值' },
    { prop: 'unit', label: '单位' }
  ],
  'system_run_detail': [
    { prop: 'insert_time', label: '入库时间', formatter: formatTime },
    { prop: 'metric_attr', label: '指标属性' },
    { prop: 'metric_name', label: '指标名称' },
    { prop: 'object_name', label: '对象名称' },
    { prop: 'object_url', label: '对象URL' },
    { prop: 'access_count', label: '访问量' },
    { prop: 'metric_value', label: '指标值' },
    { prop: 'unit', label: '单位' }
  ],
  // 系统异常表-接口明细
  'metric_error_interface': [
    { prop: 'insert_time', label: '入库时间', formatter: formatDataTime },
    { prop: 'interface_url', label: '接口URL' },
    { prop: 'access_count', label: '访问量' },
    { prop: 'failure_count', label: '失败量' },
    { prop: 'success_rate', label: '成功率(%)' }
  ],
  // 系统异常表-实例异常
  'metric_error_instance': [
    { prop: 'insert_time', label: '入库时间', formatter: formatDataTime },
    { prop: 'app_name', label: '应用名' },
    { prop: 'error_name', label: '异常名称' },
    { prop: 'error_count', label: '异常次数' }
  ],
  // 系统异常表-JS异常
  'metric_error_js': [
    { prop: 'insert_time', label: '入库时间', formatter: formatDataTime },
    { prop: 'error_name', label: '报错名称' },
    { prop: 'error_count', label: '报错次数' },
    { prop: 'total_error_count', label: '总报错量' }
  ],
  // 系统异常表-业务返回异常
  'metric_error_biz_return': [
    { prop: 'insert_time', label: '入库时间', formatter: formatDataTime },
    { prop: 'system_name', label: '系统名' },
    { prop: 'return_info', label: '返回信息' },
    { prop: 'count', label: '次数' }
  ],
  // 系统异常表-返回码
  'metric_error_return_code': [
    { prop: 'insert_time', label: '入库时间', formatter: formatDataTime },
    { prop: 'return_code', label: '返回码' },
    { prop: 'count', label: '返回次数' }
  ]
}

// 数据排序和列顺序处理工具函数（统一使用入库时间，缺失的用数据时间代替）
const TIME_FIELD_MAP = {
  'system_jhd': 'insert_time',
  'inner_query': 'insert_time',
  'inner_step_response': 'insert_time',
  'inner_button_count': 'insert_time',
  'inner_business_duration': 'insert_time',
  'system_run_summary': 'insert_time',
  'system_run_detail': 'insert_time',
  'metric_error_interface': 'insert_time',
  'metric_error_instance': 'insert_time',
  'metric_error_js': 'insert_time',
  'metric_error_biz_return': 'insert_time',
  'metric_error_return_code': 'insert_time'
}

// 按时间字段排序（降序）
const sortByTimeDesc = (data, timeField) => {
  if (!data || !timeField) return data
  return [...data].sort((a, b) => {
    const timeA = a[timeField] || ''
    const timeB = b[timeField] || ''
    return timeB.localeCompare(timeA)
  })
}

// 将指定字段移到数组第一位
const moveFieldToFirst = (columns, fieldName) => {
  if (!columns || !fieldName) return columns
  const idx = columns.findIndex(col => col.prop === fieldName)
  if (idx <= 0) return columns
  const col = columns.splice(idx, 1)[0]
  return [col, ...columns]
}

// 系统运行-系统性能 指标分组
const systemRunMetricGroups = {
  '前端页面': {
    summary: ['大于3s的页面访问量占比', '大于3s的请求访问量占比'],
    detail: ['加载时长大于3秒的页面数', '响应时长大于3秒的请求数']
  },
  '后端服务': {
    summary: ['应用平均响应时长', '应用FullGC时长', '接口平均响应时长', 'SQL平均响应时长', '大于1s的接口访问量占比', '大于1s的SQL访问量占比'],
    detail: []
  }
}

// 系统运行-所有指标列表（用于选择）
const systemRunAllMetrics = [
  // 前端页面 - 汇总指标
  { name: '大于3s的页面访问量占比', group: '前端页面', type: 'summary' },
  { name: '大于3s的请求访问量占比', group: '前端页面', type: 'summary' },
  // 前端页面 - 明细指标
  { name: '加载时长大于3秒的页面数', group: '前端页面', type: 'detail' },
  { name: '响应时长大于3秒的请求数', group: '前端页面', type: 'detail' },
  // 后端服务 - 汇总指标
  { name: '应用平均响应时长', group: '后端服务', type: 'summary' },
  { name: '应用FullGC时长', group: '后端服务', type: 'summary' },
  { name: '接口平均响应时长', group: '后端服务', type: 'summary' },
  { name: 'SQL平均响应时长', group: '后端服务', type: 'summary' },
  { name: '大于1s的接口访问量占比', group: '后端服务', type: 'summary' },
  { name: '大于1s的SQL访问量占比', group: '后端服务', type: 'summary' }
]

// 系统异常-系统异常 指标分组
const systemErrorMetricGroups = {
  '后端服务': {
    interface: ['metric_error_interface'],
    instance: ['metric_error_instance']
  },
  '前端页面': {
    js: ['metric_error_js'],
    bizReturn: ['metric_error_biz_return'],
    returnCode: ['metric_error_return_code']
  }
}

// 系统异常-所有指标列表（用于选择）
const systemErrorAllMetrics = [
  // 后端服务
  { name: '接口明细', group: '后端服务', tableName: 'metric_error_interface', type: 'interface' },
  { name: '实例异常', group: '后端服务', tableName: 'metric_error_instance', type: 'instance' },
  // 前端页面
  { name: 'JS异常', group: '前端页面', tableName: 'metric_error_js', type: 'js' },
  { name: '业务返回异常', group: '前端页面', tableName: 'metric_error_biz_return', type: 'bizReturn' },
  { name: '返回码', group: '前端页面', tableName: 'metric_error_return_code', type: 'returnCode' }
]

// 选中的系统异常指标
const selectedSystemErrorMetrics = ref([])

// 系统异常选中的表格名称
const selectedSystemErrorTableNames = computed(() => {
  return systemErrorAllMetrics
    .filter(m => selectedSystemErrorMetrics.value.includes(m.name))
    .map(m => m.tableName)
})

// 是否需要显示系统异常表格
const needShowSystemErrorTable = computed(() => {
  return showSystemErrorSidebar.value && selectedSystemErrorTableNames.value.length > 0
})

// 选中的系统运行指标名称列表
const selectedSystemRunMetrics = ref([])

// 对内部人员第三级Tab配置（按子类别）
const currentLevel2Tabs = computed(() => {
  const cat = categoryStructure[activeCategory.value]
  if (cat && cat.level2TabsMap && cat.level2TabsMap[activeSubcategory.value]) {
    return cat.level2TabsMap[activeSubcategory.value].map(tab => ({ code: tab, name: tab }))
  }
  return []
})

// 是否显示了单业务/融合业务（需要两个表格区域）
const isInnerBusinessSelected = computed(() => {
  return activeCategory.value === '对内部人员' &&
    (activeSubcategory.value === '办理类-单业务' || activeSubcategory.value === '办理类-融合业务')
})

// 是否显示步骤响应数据区域
const showStepResponseArea = computed(() => {
  return isInnerBusinessSelected.value &&
    (selectedLevel2Tabs.value.includes('页面加载') || selectedLevel2Tabs.value.includes('步骤时长'))
})

// 是否显示办理按钮数据区域
const showButtonCountArea = computed(() => {
  return isInnerBusinessSelected.value && selectedLevel2Tabs.value.includes('办理按钮总数')
})

// 是否显示全程办理时长数据区域
const showBusinessDurationArea = computed(() => {
  return isInnerBusinessSelected.value && selectedLevel2Tabs.value.includes('全程办理时长')
})

// 是否显示系统运行指标侧边栏
const showSystemRunSidebar = computed(() => {
  return activeCategory.value === '运行效率' && activeSubcategory.value === '系统性能'
})

// 是否显示系统异常指标侧边栏
const showSystemErrorSidebar = computed(() => {
  return activeCategory.value === '运行效率' && activeSubcategory.value === '系统异常'
})

// 系统运行选中的汇总指标名称列表
const selectedSystemRunSummaryMetrics = computed(() => {
  return systemRunAllMetrics
    .filter(m => m.type === 'summary' && selectedSystemRunMetrics.value.includes(m.name))
    .map(m => m.name)
})

// 系统运行选中的明细指标名称列表
const selectedSystemRunDetailMetrics = computed(() => {
  return systemRunAllMetrics
    .filter(m => m.type === 'detail' && selectedSystemRunMetrics.value.includes(m.name))
    .map(m => m.name)
})

// 系统运行选中的明细指标对应的metric_attr（用于查询）
const selectedSystemRunDetailGroups = computed(() => {
  const groups = new Set()
  systemRunAllMetrics
    .filter(m => m.type === 'detail' && selectedSystemRunMetrics.value.includes(m.name))
    .forEach(m => groups.add(m.group))
  return [...groups]
})

// 是否需要显示汇总表格
const needShowSummaryTable = computed(() => {
  return showSystemRunSidebar.value && selectedSystemRunSummaryMetrics.value.length > 0
})

// 是否需要显示明细表格
const needShowDetailTable = computed(() => {
  return showSystemRunSidebar.value && selectedSystemRunDetailMetrics.value.length > 0
})

const categories = ref([])
// 数据汇聚仅展示八大类，不展示计划执行。
const TRAILING_CATEGORY = '计划执行'
const tabCategories = computed(() => categories.value.filter(c => c.code !== TRAILING_CATEGORY))
const subcategories = ref([])
const attributes = ref([])
const selectedAttrs = ref([])
const activeCategory = ref('')
const activeSubcategory = ref('')
const selectedLevel2Tabs = ref([]) // 第三级Tab多选
const activeTableName = ref('system_jhd')
const isLevel2TabsInitialized = ref(false) // 标记第三级Tab是否初始化完成
let attributesRequestVersion = 0  // 请求版本号，用于处理竞态条件
const tableData = ref([])
const stepResponseData = ref([]) // 步骤响应数据
const buttonCountData = ref([]) // 办理按钮数据
const businessDurationData = ref([]) // 全程办理时长数据
const systemRunSummaryData = ref([]) // 系统运行汇总数据
const systemRunDetailData = ref([]) // 系统运行明细数据
const systemRunSummaryTotal = ref(0)
const systemRunDetailTotal = ref(0)
const tableLoading = ref(false)
const pagination = ref({ page: 1, size: 10, total: 0 })
const pagination2 = ref({ page: 1, size: 10, total: 0 })
const pagination3 = ref({ page: 1, size: 10, total: 0 }) // 全程办理时长分页

// 导入配置相关
const showCollectionTasks = ref(false)
const showImportConfigDialog = ref(false)
const showImportConfigForm = ref(false)

// 扫描健康检查相关
const scanHealth = ref(null)
const scanHealthLoading = ref(false)
const showScanHealthPanel = ref(true)
// 文件根目录：运行时从 /api/error/scan-health 取（按 BasePathProvider 当前生效值）
// 不再写死 '/app/files'（容器路径），本机跑时 fallback 由后端默认值兜底
const basePath = ref('')

const isAbsolutePath = (path) => {
  return path && path.startsWith('/')
}

const loadScanHealth = async () => {
  scanHealthLoading.value = true
  try {
    const res = await errorImportApi.scanHealth()
    scanHealth.value = res.data
    // 检查结果由页面顶部异常面板承载；检测到异常时重新展开，避免用户此前关闭后看不到
    if (res.data && !res.data.healthy) {
      showScanHealthPanel.value = true
    }
    if (res.data?.basePath) {
      basePath.value = res.data.basePath
    }
  } catch (error) {
    console.error('加载扫描健康状态失败:', error)
  } finally {
    scanHealthLoading.value = false
  }
}

// 监听导入配置对话框打开
watch(showImportConfigDialog, (val) => {
  if (val) {
    loadImportConfigs()
  }
})
const importConfigList = ref([])
const importConfigLoading = ref(false)
const selectedImportConfigs = ref([])
const importConfigForm = ref({
  id: null,
  subCategoryCode: 'error',
  importPath: 'files/raw_data/import/',
  archivePath: 'files/raw_data/archive/',
  scanIntervalMinutes: 5,
  enabled: 1
})

// 子类型切换时自动同步路径（统一指向 files/raw_data/）
watch(() => importConfigForm.value.subCategoryCode, (newVal) => {
  if (!newVal) return
  // 编辑模式下不覆盖用户已修改的值
  if (importConfigForm.value.id) return
  importConfigForm.value.importPath = 'files/raw_data/import/'
  importConfigForm.value.archivePath = 'files/raw_data/archive/'
})

// system_jhd 筛选字段
const jhdParams = ref({
  checkCode: '',
  checkName: '',
  businessOwner: '',
  checkFreq: '',
  abnormalDate: ''
})

// system_jhd_detail（工单明细）筛选字段
const jhdDetailParams = ref({
  auditId: '',
  auditName: '',
  supervisor: '',
  wsMonth: '',
  department: '',
  firstCategory: '',
  secondCategory: ''
})

// inner_* 表筛选字段
const innerParams = ref({
  dataDate: '',
  businessName: '',
  stepName: ''
})

// 下钻参数 - 用于只查询最新批次数据
const drilldownParams = ref({
  dataTime: '',
  stepTypes: ''
})

// 当前筛选字段（动态）
const queryParams = computed(() => {
  if (activeTableName.value === 'system_jhd') {
    return jhdParams.value
  }
  return innerParams.value
})

// 列显示设置
const STORAGE_KEY = 'data_table_preferences'
const columnOptions = ref([
  { prop: 'insert_time', label: '入库时间' },
  { prop: 'first_classify', label: '一级分类' },
  { prop: 'second_classify', label: '二级分类' },
  { prop: 'check_code', label: '稽核点编号' },
  { prop: 'check_name', label: '稽核点名称' },
  { prop: 'check_rule', label: '稽核口径' },
  { prop: 'check_freq', label: '频率' },
  { prop: 'abnormal_date', label: '异常数据时间' },
  { prop: 'abnormal_count', label: '异常量' },
  { prop: 'business_owner', label: '业务主管' },
  { prop: 'current_step', label: '当前环节' },
  { prop: 'order_2month', label: '近两月派单量' },
  { prop: 'unclosed_2month', label: '近两月未关单量' },
  { prop: 'develop_time', label: '负责部门' },
  { prop: 'create_time', label: '数据生成时间' },
  { prop: 'source_file', label: '来源文件名' }
])

const defaultVisibleColumns = columnOptions.value.map(c => c.prop)
const visibleColumns = ref([...defaultVisibleColumns])

const isColumnHidden = (prop) => {
  return !visibleColumns.value.includes(prop)
}

const loadPreferences = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const prefs = JSON.parse(saved)
      const key = activeTableName.value || 'system_jhd'
      if (prefs[key] && prefs[key].visibleColumns && prefs[key].visibleColumns.length > 0) {
        visibleColumns.value = prefs[key].visibleColumns
      } else {
        visibleColumns.value = currentColumns.value.map(c => c.prop)
      }
    } else {
      visibleColumns.value = currentColumns.value.map(c => c.prop)
    }
  } catch (e) {
    console.error('加载偏好设置失败:', e)
  }
}

const savePreferences = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    const prefs = saved ? JSON.parse(saved) : {}
    const key = activeTableName.value || 'system_jhd'
    prefs[key] = { visibleColumns: visibleColumns.value }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
  } catch (e) {
    console.error('保存偏好设置失败:', e)
  }
}

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

const initCategoryStructure = () => {
  categories.value = Object.keys(categoryStructure).filter(code => code !== '计划执行').map((code, idx) => ({
    code,
    name: categoryStructure[code].name
  }))
}

const handleCategoryChange = (categoryCode) => {
  activeTableName.value = ''
  ++attributesRequestVersion
  activeSubcategory.value = ''
  selectedLevel2Tabs.value = []
  subcategories.value = []
  attributes.value = []
  selectedAttrs.value = []
  tableData.value = []
  stepResponseData.value = []
  buttonCountData.value = []
  businessDurationData.value = []

  const cat = categoryStructure[categoryCode]
  if (cat && cat.subcategories) {
    subcategories.value = Object.keys(cat.subcategories).map(code => ({
      code,
      name: cat.subcategories[code].name
    }))
    if (subcategories.value.length > 0) {
      activeSubcategory.value = subcategories.value[0].code
      handleSubcategoryChange(activeSubcategory.value)
    }
  }
}

const handleSubcategoryChange = (subCode) => {
  if (activeCategory.value === '对外部客户' || isCollectedValueView(activeCategory.value) || activeCategory.value === '数据质量') {
    activeTableName.value = ''
    ++attributesRequestVersion
    tableData.value = []
    return
  }
  attributes.value = []
  selectedAttrs.value = []
  tableData.value = []
  stepResponseData.value = []
  buttonCountData.value = []
  businessDurationData.value = []
  pagination.value.page = 1
  pagination2.value.page = 1
  pagination3.value.page = 1

  const cat = categoryStructure[activeCategory.value]

  // 对内部人员：初始化第三级Tab多选
  if (activeCategory.value === '对内部人员' && cat.level2TabsMap && cat.level2TabsMap[subCode]) {
    const sub = cat.subcategories[subCode]
    if (sub) {
      activeTableName.value = sub.tableName
    }
    loadPreferences()
    // 标记初始化完成
    isLevel2TabsInitialized.value = true
    // 自动全选 + 触发查询
    selectedLevel2Tabs.value = currentLevel2Tabs.value.map(tab => tab.code)
    loadData()
    return
  }

  if (cat && cat.subcategories && cat.subcategories[subCode]) {
    const sub = cat.subcategories[subCode]
    if (sub.tableName) {
      activeTableName.value = sub.tableName
      loadPreferences()
      loadAttributes(subCode, sub.tableName)
    } else if (sub.dbMapping) {
      activeTableName.value = 'system_jhd'
      loadPreferences()
      loadAttributes(subCode)
    } else if (sub.hasMetricGroups) {
      // 系统运行-系统性能：使用指标分组侧边栏
      activeTableName.value = 'system_run'
      // 默认全选所有指标 + 自动查询
      selectedSystemRunMetrics.value = systemRunAllMetrics.map(m => m.name)
      systemRunSummaryData.value = []
      systemRunDetailData.value = []
      systemRunSummaryTotal.value = 0
      systemRunDetailTotal.value = 0
      loadSystemRunData()
    } else if (subCode === '系统异常') {
      // 系统运行-系统异常：使用系统异常指标分组侧边栏
      activeTableName.value = 'system_error'
      // 默认全选所有指标 + 自动查询
      selectedSystemErrorMetrics.value = systemErrorAllMetrics.map(m => m.name)
      systemErrorTableData.value = {}
      // 递增版本号以废弃任何 pending 的 loadAttributes 请求
      ++attributesRequestVersion
      loadSystemErrorData()
    } else {
      activeTableName.value = ''
      attributes.value = []
      selectedAttrs.value = []
      // 递增版本号以废弃任何 pending 的 loadAttributes 请求
      ++attributesRequestVersion
    }
  }
}

// 全选第三级Tab
const selectAllLevel2Tabs = () => {
  selectedLevel2Tabs.value = currentLevel2Tabs.value.map(tab => tab.code)
}

// 反选第三级Tab
const deselectAllLevel2Tabs = () => {
  const allTabs = currentLevel2Tabs.value.map(tab => tab.code)
  const unselected = allTabs.filter(tab => !selectedLevel2Tabs.value.includes(tab))
  selectedLevel2Tabs.value = unselected
}

// 切换第三级Tab选中状态
const toggleLevel2Tab = (tabCode) => {
  const index = selectedLevel2Tabs.value.indexOf(tabCode)
  if (index > -1) {
    selectedLevel2Tabs.value.splice(index, 1)
  } else {
    selectedLevel2Tabs.value.push(tabCode)
  }
}

// 系统运行-全选某个分组的所有指标
const selectAllSystemRunGroup = (groupKey) => {
  const group = systemRunMetricGroups[groupKey]
  if (!group) return
  const allMetrics = [...(group.summary || []), ...(group.detail || [])]
  allMetrics.forEach(m => {
    if (!selectedSystemRunMetrics.value.includes(m)) {
      selectedSystemRunMetrics.value.push(m)
    }
  })
}

// 系统运行-反选某个分组的所有指标
const deselectAllSystemRunGroup = (groupKey) => {
  const group = systemRunMetricGroups[groupKey]
  if (!group) return
  const allMetrics = [...(group.summary || []), ...(group.detail || [])]
  selectedSystemRunMetrics.value = selectedSystemRunMetrics.value.filter(m => !allMetrics.includes(m))
}

// 系统运行-全选所有指标
const selectAllSystemRunAll = () => {
  systemRunAllMetrics.forEach(m => {
    if (!selectedSystemRunMetrics.value.includes(m.name)) {
      selectedSystemRunMetrics.value.push(m.name)
    }
  })
}

// 系统运行-反选所有指标
const deselectAllSystemRunAll = () => {
  selectedSystemRunMetrics.value = []
}

// 系统运行-切换某个具体指标
const toggleSystemRunMetric = (metricName) => {
  const idx = selectedSystemRunMetrics.value.indexOf(metricName)
  if (idx > -1) {
    selectedSystemRunMetrics.value.splice(idx, 1)
  } else {
    selectedSystemRunMetrics.value.push(metricName)
  }
}

// 系统运行-判断分组是否全选
const isSystemRunGroupAllSelected = (groupKey) => {
  const group = systemRunMetricGroups[groupKey]
  if (!group) return false
  const allMetrics = [...(group.summary || []), ...(group.detail || [])]
  return allMetrics.every(m => selectedSystemRunMetrics.value.includes(m))
}

// 系统运行-判断分组是否部分选中
const isSystemRunGroupPartiallySelected = (groupKey) => {
  const group = systemRunMetricGroups[groupKey]
  if (!group) return false
  const allMetrics = [...(group.summary || []), ...(group.detail || [])]
  const selectedCount = allMetrics.filter(m => selectedSystemRunMetrics.value.includes(m)).length
  return selectedCount > 0 && selectedCount < allMetrics.length
}

// 全选指标属性
const selectAllAttrs = () => {
  const allAttrs = []
  attributes.value.forEach(group => {
    if (group.second_classifies) {
      allAttrs.push(...group.second_classifies)
    }
  })
  selectedAttrs.value = [...allAttrs]
}

// 反选指标属性
const deselectAllAttrs = () => {
  const allAttrs = []
  attributes.value.forEach(group => {
    if (group.second_classifies) {
      allAttrs.push(...group.second_classifies)
    }
  })
  const unselected = allAttrs.filter(attr => !selectedAttrs.value.includes(attr))
  selectedAttrs.value = unselected
}

// 处理一级分类勾选/取消勾选
const handleCategoryToggle = (group, checked) => {
  if (checked) {
    const newSelected = [...selectedAttrs.value]
    group.second_classifies.forEach(attr => {
      if (!newSelected.includes(attr)) {
        newSelected.push(attr)
      }
    })
    selectedAttrs.value = newSelected
  } else {
    selectedAttrs.value = selectedAttrs.value.filter(
      attr => !group.second_classifies.includes(attr)
    )
  }
}

// 当前表格的列配置
const currentColumns = computed(() => {
  if (activeTableName.value !== 'system_jhd' && tableColumnOptions[activeTableName.value]) {
    return tableColumnOptions[activeTableName.value]
  }
  return columnOptions.value
})

// 计算总指标属性数量
const totalAttrCount = computed(() => {
  let count = 0
  attributes.value.forEach(group => {
    if (group.second_classifies) {
      count += group.second_classifies.length
    }
  })
  return count
})

// 判断某个一级分类是否全部选中
const isCategoryAllSelected = (group) => {
  return group.second_classifies.every(attr => selectedAttrs.value.includes(attr))
}

// 判断某个一级分类是否部分选中
const isCategoryPartiallySelected = (group) => {
  const selectedCount = group.second_classifies.filter(attr => selectedAttrs.value.includes(attr)).length
  return selectedCount > 0 && selectedCount < group.second_classifies.length
}

const loadAttributes = async (subCategoryName, tableName) => {
  // 重置数据，防止竞态条件
  attributes.value = []
  selectedAttrs.value = []
  tableData.value = []
  // 增加版本号，用于处理竞态条件
  const currentVersion = ++attributesRequestVersion
  try {
    const res = await dataApi.getAttributes(subCategoryName, tableName)
    // 检查是否是最新的请求，防止竞态条件
    if (currentVersion !== attributesRequestVersion) {
      console.log('ignore stale attributes response')
      return
    }
    if (res.data && res.data.length > 0 && res.data[0].error_message) {
      ElMessage.error('加载指标属性失败: ' + res.data[0].error_message)
      attributes.value = []
      selectedAttrs.value = []
      tableData.value = []
      return
    }
    attributes.value = res.data || []
    const allAttrs = []
    attributes.value.forEach(group => {
      if (group.second_classifies) {
        allAttrs.push(...group.second_classifies)
      }
    })
    selectedAttrs.value = [...allAttrs]
    loadData()
  } catch (error) {
    console.error('加载指标属性失败:', error)
    let errMsg = '加载指标属性失败'
    if (error.response && error.response.data && error.response.data.message) {
      errMsg += ': ' + error.response.data.message
    } else if (error.message) {
      errMsg += ': ' + error.message
    }
    ElMessage.error(errMsg)
    attributes.value = []
    selectedAttrs.value = []
  }
}

const loadData = async () => {
  if (isCollectedValueView(activeCategory.value) || activeCategory.value === '数据质量') return
  if (!activeTableName.value) {
    tableData.value = []
    pagination.value.total = 0
    return
  }
  tableLoading.value = true
  try {
    // 对内部人员-查询类：使用inner_query
    if (activeCategory.value === '对内部人员' && activeSubcategory.value === '查询类') {
      // 如果没有勾选任何选项，不查询
      if (selectedLevel2Tabs.value.length === 0) {
        tableData.value = []
        pagination.value.total = 0
        tableLoading.value = false
        return
      }
      await loadInnerQueryData()
      return
    }

    // 对内部人员-单业务/融合业务：使用两个表格
    if (isInnerBusinessSelected.value) {
      // 如果没有勾选任何选项，不查询
      if (selectedLevel2Tabs.value.length === 0) {
        tableData.value = []
        stepResponseData.value = []
        buttonCountData.value = []
        businessDurationData.value = []
        pagination.value.total = 0
        pagination2.value.total = 0
        pagination3.value.total = 0
        tableLoading.value = false
        return
      }
      await loadInnerBusinessData()
      return
    }

    // 系统运行-系统性能：使用专用加载函数
    if (showSystemRunSidebar.value) {
      tableLoading.value = false
      return
    }

    // 系统运行-系统异常：使用专用加载函数
    if (showSystemErrorSidebar.value) {
      await loadSystemErrorData()
      tableLoading.value = false
      return
    }

    // system_jhd（月数据清单）逻辑
    if (activeTableName.value === 'system_jhd') {
      const params = {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        tableName: activeTableName.value,
        subCategoryName: activeSubcategory.value
      }

      if (jhdParams.value.checkCode) {
        params.checkCode = jhdParams.value.checkCode
      }
      if (jhdParams.value.checkName) {
        params.checkName = jhdParams.value.checkName
      }
      if (jhdParams.value.businessOwner) {
        params.businessOwner = jhdParams.value.businessOwner
      }
      if (jhdParams.value.checkFreq) {
        params.checkFreq = jhdParams.value.checkFreq
      }
      if (jhdParams.value.abnormalDate) {
        params.abnormalDate = jhdParams.value.abnormalDate
      }

      if (attributes.value.length > 0 && selectedAttrs.value.length === 0) {
        tableData.value = []
        pagination.value.total = 0
        tableLoading.value = false
        return
      }

      let actualTotalAttrs = 0
      attributes.value.forEach(group => {
        if (group.second_classifies) {
          actualTotalAttrs += group.second_classifies.length
        }
      })

      if (attributes.value.length > 0 && selectedAttrs.value.length > 0 && selectedAttrs.value.length < actualTotalAttrs) {
        params.secondClassify = selectedAttrs.value.join(',')
      }

      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })

      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载数据失败: ' + res.data.error)
        tableData.value = []
        pagination.value.total = 0
        tableLoading.value = false
        return
      }
      const rawData = res.data.content || []
      const timeField = TIME_FIELD_MAP[activeTableName.value] || 'data_time'
      tableData.value = sortByTimeDesc(rawData, timeField)
      pagination.value.total = res.data.total || 0
      tableLoading.value = false
      return
    }

    // system_jhd_detail（工单明细）逻辑
    if (activeTableName.value === 'system_jhd_detail') {
      const params = {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        tableName: activeTableName.value,
        subCategoryName: activeSubcategory.value
      }

      if (jhdDetailParams.value.auditId) {
        params.checkCode = jhdDetailParams.value.auditId
      }
      if (jhdDetailParams.value.auditName) {
        params.checkName = jhdDetailParams.value.auditName
      }
      if (jhdDetailParams.value.supervisor) {
        params.businessOwner = jhdDetailParams.value.supervisor
      }
      if (jhdDetailParams.value.wsMonth) {
        params.dataTime = jhdDetailParams.value.wsMonth
      }
      if (jhdDetailParams.value.department) {
        params.department = jhdDetailParams.value.department
      }
      if (jhdDetailParams.value.firstCategory) {
        params.firstCategory = jhdDetailParams.value.firstCategory
      }
      if (jhdDetailParams.value.secondCategory) {
        params.secondCategory = jhdDetailParams.value.secondCategory
      }

      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })

      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载数据失败: ' + res.data.error)
        tableData.value = []
        pagination.value.total = 0
        tableLoading.value = false
        return
      }
      const rawData = res.data.content || []
      const timeField = TIME_FIELD_MAP[activeTableName.value] || 'data_time'
      tableData.value = sortByTimeDesc(rawData, timeField)
      pagination.value.total = res.data.total || 0
      tableLoading.value = false
      return
    }

    // 其他类型（inner_* 等）共用的 attributes 过滤逻辑
    if (attributes.value.length > 0 && selectedAttrs.value.length === 0) {
      tableData.value = []
      pagination.value.total = 0
      tableLoading.value = false
      return
    }

    let actualTotalAttrs = 0
    attributes.value.forEach(group => {
      if (group.second_classifies) {
        actualTotalAttrs += group.second_classifies.length
      }
    })

    if (attributes.value.length > 0 && selectedAttrs.value.length > 0 && selectedAttrs.value.length < actualTotalAttrs) {
      params.secondClassify = selectedAttrs.value.join(',')
    }

    // inner_* 表不使用 attributes 过滤，不需要检查 attributes.value.length

    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })

    const res = await dataApi.getDetail(params)
    if (res.data && res.data.error) {
      ElMessage.error('加载数据失败: ' + res.data.error)
      tableData.value = []
      pagination.value.total = 0
      tableLoading.value = false
      return
    }
    const rawData = res.data.content || []
    const timeField = TIME_FIELD_MAP[activeTableName.value] || 'data_time'
    tableData.value = sortByTimeDesc(rawData, timeField)
    pagination.value.total = res.data.total || 0
  } catch (error) {
    console.error('加载数据失败:', error)
    let errMsg = '加载数据失败'
    if (error.response && error.response.data && error.response.data.message) {
      errMsg += ': ' + error.response.data.message
    } else if (error.response && error.response.data && error.response.data.error) {
      errMsg += ': ' + error.response.data.error
    } else if (error.message) {
      errMsg += ': ' + error.message
    }
    ElMessage.error(errMsg)
  } finally {
    tableLoading.value = false
  }
}

// 加载inner_query数据（查询类）
const loadInnerQueryData = async () => {
  console.log('=== loadInnerQueryData 开始 ===')
  console.log('selectedLevel2Tabs:', selectedLevel2Tabs.value)
  console.log('selectedLevel2Tabs.length:', selectedLevel2Tabs.value.length)
  try {
    // 查询类有页面加载、查询时长共2种step_type
    const totalStepTypes = 2
    console.log('totalStepTypes:', totalStepTypes)
    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      tableName: 'inner_query',
      subCategoryName: activeSubcategory.value
    }

    // 如果是下钻查询，添加dataTime过滤
    if (drilldownParams.value.dataTime) {
      params.dataTime = drilldownParams.value.dataTime
    }

    // 如果选择了部分（不是全部）step_type，才传stepType过滤
    // 如果全选了，就不传stepType，返回所有数据
    if (selectedLevel2Tabs.value.length > 0 && selectedLevel2Tabs.value.length < totalStepTypes) {
      params.stepType = selectedLevel2Tabs.value.join(',')
      console.log('已设置stepType过滤:', params.stepType)
    } else {
      console.log('未设置stepType过滤，selectedLevel2Tabs.length =', selectedLevel2Tabs.value.length)
    }

    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })

    console.log('查询inner_query，params:', params)

    const res = await dataApi.getDetail(params)
    console.log('inner_query response.data:', res.data)
    console.log('inner_query response.data.content:', res.data?.content)
    console.log('inner_query response.data.total:', res.data?.total)
    if (res.data && res.data.content && res.data.content.length > 0) {
      console.log('第一条数据的step_type:', res.data.content[0].step_type)
      const uniqueStepTypes = [...new Set(res.data.content.map(item => item.step_type))]
      console.log('数据中的step_type种类:', uniqueStepTypes)
    }
    if (res.data && res.data.error) {
      ElMessage.error('加载数据失败: ' + res.data.error)
      tableData.value = []
      pagination.value.total = 0
      return
    }
    const rawData = res.data.content || []
    tableData.value = sortByTimeDesc(rawData, 'insert_time')
    pagination.value.total = res.data.total || 0
  } catch (error) {
    console.error('加载数据失败:', error)
    ElMessage.error('加载数据失败')
    tableData.value = []
    pagination.value.total = 0
  } finally {
    tableLoading.value = false
  }
}

// 加载单业务/融合业务数据（两个表格）
const loadInnerBusinessData = async () => {
  try {
    const cat = categoryStructure[activeCategory.value]
    const sub = cat?.subcategories?.[activeSubcategory.value]
    if (!sub) {
      tableLoading.value = false
      return
    }

    // 如果需要显示步骤响应数据区域
    if (showStepResponseArea.value) {
      // 确定需要查询的stepType
      const selectedSteps = selectedLevel2Tabs.value.filter(t => t === '页面加载' || t === '步骤时长')
      // 步骤响应数据只有页面加载和步骤时长两种，共2种
      const totalStepTypes = 2
      const params = {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        tableName: 'inner_step_response',
        subCategoryName: activeSubcategory.value
      }
      // 如果是下钻查询，添加dataTime过滤
      if (drilldownParams.value.dataTime) {
        params.dataTime = drilldownParams.value.dataTime
      }
      // 如果选择了部分（不是全部）step_type，才传stepType过滤
      // 如果全选了（选择了全部2种），就不传stepType，返回所有数据
      if (selectedSteps.length > 0 && selectedSteps.length < totalStepTypes) {
        params.stepType = selectedSteps.join(',')
      }

      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })

      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载数据失败: ' + res.data.error)
        stepResponseData.value = []
        pagination.value.total = 0
      } else {
        const rawData = res.data.content || []
        stepResponseData.value = sortByTimeDesc(rawData, 'insert_time')
        pagination.value.total = res.data.total || 0
      }
    } else {
      stepResponseData.value = []
      pagination.value.total = 0
    }

    // 如果需要显示办理按钮数据区域
    if (showButtonCountArea.value) {
      const params = {
        page: pagination2.value.page - 1,
        size: pagination2.value.size,
        tableName: 'inner_button_count',
        subCategoryName: activeSubcategory.value
      }
      // 如果是下钻查询，添加dataTime过滤
      if (drilldownParams.value.dataTime) {
        params.dataTime = drilldownParams.value.dataTime
      }

      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })

      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载按钮数据失败: ' + res.data.error)
        buttonCountData.value = []
        pagination2.value.total = 0
      } else {
        const rawData = res.data.content || []
        buttonCountData.value = sortByTimeDesc(rawData, 'insert_time')
        pagination2.value.total = res.data.total || 0
      }
    } else {
      buttonCountData.value = []
      pagination2.value.total = 0
    }

    // 如果需要显示全程办理时长数据区域
    if (showBusinessDurationArea.value) {
      const params = {
        page: pagination3.value.page - 1,
        size: pagination3.value.size,
        tableName: 'inner_business_duration',
        subCategoryName: activeSubcategory.value
      }
      // 如果是下钻查询，添加dataTime过滤
      if (drilldownParams.value.dataTime) {
        params.dataTime = drilldownParams.value.dataTime
      }

      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })

      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载全程办理时长数据失败: ' + res.data.error)
        businessDurationData.value = []
        pagination3.value.total = 0
      } else {
        const rawData = res.data.content || []
        businessDurationData.value = sortByTimeDesc(rawData, 'insert_time')
        pagination3.value.total = res.data.total || 0
      }
    } else {
      businessDurationData.value = []
      pagination3.value.total = 0
    }
  } catch (error) {
    console.error('加载数据失败:', error)
    ElMessage.error('加载数据失败')
    stepResponseData.value = []
    buttonCountData.value = []
    businessDurationData.value = []
  } finally {
    tableLoading.value = false
  }
}

// 加载办理按钮数据（独立分页）
const loadButtonCountData = async () => {
  if (!showButtonCountArea.value) return
  tableLoading.value = true
  try {
    const params = {
      page: pagination2.value.page - 1,
      size: pagination2.value.size,
      tableName: 'inner_button_count',
      subCategoryName: activeSubcategory.value
    }

    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })

    const res = await dataApi.getDetail(params)
    if (res.data && res.data.error) {
      ElMessage.error('加载按钮数据失败: ' + res.data.error)
      buttonCountData.value = []
      pagination2.value.total = 0
    } else {
      buttonCountData.value = sortByTimeDesc(res.data.content || [], 'insert_time')
      pagination2.value.total = res.data.total || 0
    }
  } catch (error) {
    console.error('加载按钮数据失败:', error)
    ElMessage.error('加载按钮数据失败')
    buttonCountData.value = []
    pagination2.value.total = 0
  } finally {
    tableLoading.value = false
  }
}

// 加载全程办理时长数据（独立分页）
const loadBusinessDurationData = async () => {
  if (!showBusinessDurationArea.value) return
  tableLoading.value = true
  try {
    const params = {
      page: pagination3.value.page - 1,
      size: pagination3.value.size,
      tableName: 'inner_business_duration',
      subCategoryName: activeSubcategory.value
    }

    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })

    const res = await dataApi.getDetail(params)
    if (res.data && res.data.error) {
      ElMessage.error('加载全程办理时长数据失败: ' + res.data.error)
      businessDurationData.value = []
      pagination3.value.total = 0
    } else {
      businessDurationData.value = sortByTimeDesc(res.data.content || [], 'insert_time')
      pagination3.value.total = res.data.total || 0
    }
  } catch (error) {
    console.error('加载全程办理时长数据失败:', error)
    ElMessage.error('加载全程办理时长数据失败')
    businessDurationData.value = []
    pagination3.value.total = 0
  } finally {
    tableLoading.value = false
  }
}

// 加载系统运行数据（可能有汇总和明细两个表格）
const loadSystemRunData = async () => {
  tableLoading.value = true
  try {
    // 如果有选中的汇总指标，从汇总表查询
    if (selectedSystemRunSummaryMetrics.value.length > 0) {
      const params = {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        tableName: 'system_run_summary',
        secondClassify: selectedSystemRunSummaryMetrics.value.join(',')
      }
      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })
      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载系统运行汇总数据失败: ' + res.data.error)
        systemRunSummaryData.value = []
        systemRunSummaryTotal.value = 0
      } else {
        const rawData = res.data.content || []
        systemRunSummaryData.value = sortByTimeDesc(rawData, 'insert_time')
        systemRunSummaryTotal.value = res.data.total || 0
      }
    } else {
      systemRunSummaryData.value = []
      systemRunSummaryTotal.value = 0
    }

    // 如果有选中的明细指标（metric_name），从明细表查询
    if (selectedSystemRunDetailMetrics.value.length > 0) {
      const params = {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        tableName: 'system_run_detail',
        secondClassify: selectedSystemRunDetailMetrics.value.join(',')  // 具体的明细指标名称
      }
      Object.keys(params).forEach(key => {
        if (params[key] === null || params[key] === '') delete params[key]
      })
      const res = await dataApi.getDetail(params)
      if (res.data && res.data.error) {
        ElMessage.error('加载系统运行明细数据失败: ' + res.data.error)
        systemRunDetailData.value = []
        systemRunDetailTotal.value = 0
      } else {
        const rawData = res.data.content || []
        systemRunDetailData.value = sortByTimeDesc(rawData, 'insert_time')
        systemRunDetailTotal.value = res.data.total || 0
      }
    } else {
      systemRunDetailData.value = []
      systemRunDetailTotal.value = 0
    }
  } catch (error) {
    console.error('加载系统运行数据失败:', error)
    ElMessage.error('加载系统运行数据失败')
    systemRunSummaryData.value = []
    systemRunDetailData.value = []
  } finally {
    tableLoading.value = false
  }
}

// 系统异常-全选
const selectAllSystemError = () => {
  systemErrorAllMetrics.forEach(m => {
    if (!selectedSystemErrorMetrics.value.includes(m.name)) {
      selectedSystemErrorMetrics.value.push(m.name)
    }
  })
}

// 系统异常-反选
const deselectAllSystemError = () => {
  selectedSystemErrorMetrics.value = []
}

// 系统异常-分组是否全选
const isSystemErrorGroupAllSelected = (groupKey) => {
  const allMetrics = systemErrorAllMetrics.filter(m => m.group === groupKey)
  if (allMetrics.length === 0) return false
  return allMetrics.every(m => selectedSystemErrorMetrics.value.includes(m.name))
}

// 系统异常-分组是否部分选中
const isSystemErrorGroupPartiallySelected = (groupKey) => {
  const allMetrics = systemErrorAllMetrics.filter(m => m.group === groupKey)
  if (allMetrics.length === 0) return false
  const selectedCount = allMetrics.filter(m => selectedSystemErrorMetrics.value.includes(m.name)).length
  return selectedCount > 0 && selectedCount < allMetrics.length
}

// 系统异常-切换分组
const handleSystemErrorGroupToggle = (groupKey, checked) => {
  const allMetrics = systemErrorAllMetrics.filter(m => m.group === groupKey)
  if (checked) {
    allMetrics.forEach(m => {
      if (!selectedSystemErrorMetrics.value.includes(m.name)) {
        selectedSystemErrorMetrics.value.push(m.name)
      }
    })
  } else {
    selectedSystemErrorMetrics.value = selectedSystemErrorMetrics.value.filter(
      name => !allMetrics.some(m => m.name === name)
    )
  }
}

// 系统异常-获取表格标题
const getSystemErrorTableTitle = (tableName) => {
  const titles = {
    'metric_error_interface': '接口明细',
    'metric_error_instance': '实例异常',
    'metric_error_js': 'JS异常',
    'metric_error_biz_return': '业务返回异常',
    'metric_error_return_code': '返回码'
  }
  return titles[tableName] || tableName
}

// 系统异常表格数据
const systemErrorTableData = ref({})

// 获取系统异常表格数据
const getSystemErrorTableData = (tableName) => {
  return systemErrorTableData.value[tableName] || []
}

// 系统异常-表格分页状态
const systemErrorPagination = ref({})
const systemErrorPageSize = ref(10)

// 获取系统异常表格分页信息
const getSystemErrorPagination = (tableName) => {
  return systemErrorPagination.value[tableName] || { page: 1, total: 0 }
}

// 设置系统异常表格分页信息
const setSystemErrorPagination = (tableName, page, total) => {
  systemErrorPagination.value[tableName] = { page, total }
}

// 加载系统异常数据
const loadSystemErrorData = async () => {
  if (!needShowSystemErrorTable.value) return
  tableLoading.value = true
  try {
    for (const tableName of selectedSystemErrorTableNames.value) {
      const currentPage = getSystemErrorPagination(tableName).page
      const page = currentPage - 1
      const size = systemErrorPageSize.value
      const res = await dataApi.getDetail({
        tableName: tableName,
        page: page,
        size: size
      })
      const rawData = res.data?.content || []
      const timeField = TIME_FIELD_MAP[tableName] || 'data_time'
      systemErrorTableData.value[tableName] = sortByTimeDesc(rawData, timeField)
      setSystemErrorPagination(tableName, currentPage, res.data?.total || 0)
    }
  } catch (error) {
    console.error('加载系统异常数据失败:', error)
  } finally {
    tableLoading.value = false
  }
}

// 系统异常分页变化处理
const handleSystemErrorPageChange = (tableName, page) => {
  setSystemErrorPagination(tableName, page, getSystemErrorPagination(tableName).total)
  loadSystemErrorData()
}

// 系统异常每页条数变化处理
const handleSystemErrorSizeChange = (tableName, size) => {
  systemErrorPageSize.value = size
  setSystemErrorPagination(tableName, 1, getSystemErrorPagination(tableName).total)
  loadSystemErrorData()
}

const resetFilters = () => {
  jhdParams.value = {
    checkCode: '',
    checkName: '',
    businessOwner: '',
    checkFreq: '',
    abnormalDate: ''
  }
  jhdDetailParams.value = {
    auditId: '',
    auditName: '',
    supervisor: '',
    wsMonth: ''
  }
  innerParams.value = {
    dataDate: '',
    businessName: '',
    stepName: ''
  }
  pagination.value.page = 1
  pagination2.value.page = 1
  // 如果是系统运行，重置指标选择并重新加载
  if (showSystemRunSidebar.value) {
    selectedSystemRunMetrics.value = systemRunAllMetrics.map(m => m.name)
    loadSystemRunData()
  } else if (showSystemErrorSidebar.value) {
    // 如果是系统异常，重置指标选择
    selectedSystemErrorMetrics.value = []
    systemErrorTableData.value = {}
    loadData()
  } else {
    loadData()
  }
}

const resetJhdDetailFilters = () => {
  jhdDetailParams.value = {
    auditId: '',
    auditName: '',
    supervisor: '',
    wsMonth: '',
    department: '',
    firstCategory: '',
    secondCategory: ''
  }
  pagination.value.page = 1
  loadData()
}

// 导入配置方法
const loadImportConfigs = async () => {
  importConfigLoading.value = true
  try {
    const res = await errorImportApi.list()
    importConfigList.value = res.data || []
  } catch (error) {
    console.error('加载导入配置失败:', error)
  } finally {
    importConfigLoading.value = false
  }
}

const handleImportConfigAdd = () => {
  importConfigForm.value = {
    id: null,
    subCategoryCode: 'error',
    importPath: 'files/raw_data/import/',
    archivePath: 'files/raw_data/archive/',
    scanIntervalMinutes: 5,
    enabled: 1
  }
  showImportConfigForm.value = true
}

const handleEditImportConfig = (row) => {
  importConfigForm.value = { ...row, enabled: row.collectorAvailable === false ? 0 : row.enabled }
  showImportConfigForm.value = true
}

const handleDeleteImportConfig = (row) => {
  ElMessageBox.confirm('确定删除该配置吗?', '提示', { type: 'warning' })
    .then(async () => {
      await errorImportApi.delete(row.id)
      ElMessage.success('删除成功')
      loadImportConfigs()
    }).catch(() => {})
}

const handleImportSelectionChange = (selection) => {
  selectedImportConfigs.value = selection
}

const handleBatchEdit = () => {
  if (selectedImportConfigs.value.length === 1) {
    handleEditImportConfig(selectedImportConfigs.value[0])
  } else if (selectedImportConfigs.value.length > 1) {
    ElMessage.warning('请选择一条记录进行编辑')
  }
}

const handleBatchDelete = () => {
  if (selectedImportConfigs.value.length === 0) return
  ElMessageBox.confirm('确定删除选中的配置吗?', '提示', { type: 'warning' })
    .then(async () => {
      for (const row of selectedImportConfigs.value) {
        await errorImportApi.delete(row.id)
      }
      ElMessage.success('删除成功')
      selectedImportConfigs.value = []
      loadImportConfigs()
    }).catch(() => {})
}

const handleBatchScan = () => {
  if (selectedImportConfigs.value.length === 0) return
  ElMessageBox.confirm('确定触发选中的配置扫描吗?', '提示', { type: 'warning' })
    .then(async () => {
      for (const row of selectedImportConfigs.value) {
        await errorImportApi.triggerScan(row.subCategoryCode)
      }
      ElMessage.success('扫描任务已触发')
    }).catch(() => {})
}

const saveImportConfig = async () => {
  try {
    if (importConfigForm.value.id) {
      await errorImportApi.update(importConfigForm.value.id, importConfigForm.value)
    } else {
      await errorImportApi.create(importConfigForm.value)
    }
    ElMessage.success('保存成功')
    showImportConfigForm.value = false
    loadImportConfigs()
    loadScanHealth()
    window.dispatchEvent(new Event('scan-health-update'))
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || String(error)
    console.error('保存失败:', error)
    ElMessage.error('保存失败: ' + msg)
  }
}

const handleTriggerScan = async (row) => {
  try {
    await errorImportApi.triggerScan(row.subCategoryCode)
    ElMessage.success('扫描任务已触发')
  } catch (error) {
    console.error('触发扫描失败:', error)
  }
}

onMounted(async () => {
  loadPreferences()
  try {
    const configured = await loadRunningEfficiencySubcategories()
    categoryStructure['运行效率'].subcategories = Object.fromEntries(configured.map(({ name }) => [name,
      name === '系统性能' ? { name, hasMetricGroups: true }
        : name === '系统异常' ? { name, dbMapping: null } : { name, collectedValues: true }
    ]))
  } catch (error) {
    console.error('加载运行效率小类失败:', error)
    ElMessage.error('加载运行效率小类失败，请刷新重试')
  }
  initCategoryStructure()
  loadScanHealth()

  // 处理下钻参数
  const { categoryName, subCategoryName, tableName, stepTypes, dataTime, source } = route.query

  // 处理 source 参数（来自仪表盘数据源卡片跳转）
  if (source && sourceTabMapping[source]) {
    const mapping = sourceTabMapping[source]
    activeCategory.value = mapping.categoryName
    handleCategoryChange(mapping.categoryName)

    nextTick(() => {
      if (mapping.subCategoryName) {
        activeSubcategory.value = mapping.subCategoryName
        handleSubcategoryChange(mapping.subCategoryName)
      } else {
        // 自动选中第一个二级 Tab
        if (subcategories.value.length > 0) {
          activeSubcategory.value = subcategories.value[0].code
          handleSubcategoryChange(activeSubcategory.value)
        }
      }
    })
    return
  }

  if (categoryName || subCategoryName || tableName) {
    // 如果有下钻参数，优先使用这些参数
    if (categoryName) {
      activeCategory.value = categoryName
    }
    if (tableName) {
      activeTableName.value = tableName
    }
    // 保存下钻参数
    if (dataTime) {
      drilldownParams.value.dataTime = dataTime
    }
    if (stepTypes) {
      drilldownParams.value.stepTypes = stepTypes
    }
    if (subCategoryName) {
      // 需要先确保category结构加载完成，再设置subcategory
      nextTick(() => {
        activeSubcategory.value = subCategoryName
        // 初始化第三级Tab（对内部人员）
        if (activeCategory.value === '对内部人员') {
          if (stepTypes) {
            // 下钻时使用传入的stepTypes进行过滤
            selectedLevel2Tabs.value = stepTypes.split(',').map(t => t.trim())
          } else {
            selectedLevel2Tabs.value = [...currentLevel2Tabs.value.map(t => t.code)]
          }
          isLevel2TabsInitialized.value = true
        }
        loadData()
      })
    }
    // 执行category change以加载subcategories
    handleCategoryChange(activeCategory.value)
    return
  }

  if (categories.value.length > 0) {
    activeCategory.value = categories.value[0].code
    handleCategoryChange(activeCategory.value)
    loadData()
  }
})
</script>

<style scoped>
.data-container {
  padding: 8px;
}

.scan-health-detail {
  margin-top: 8px;
}

.scan-health-item {
  padding: 8px 12px;
  margin-bottom: 8px;
  background: #fef0f0;
  border-radius: 4px;
  border-left: 3px solid var(--ui-danger);
}

.scan-health-item-header {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}

.scan-health-error {
  font-weight: 500;
  color: var(--ui-danger);
  font-size: 13px;
}

.scan-health-path {
  font-size: 12px;
  margin: 4px 0;
  line-height: 1.6;
}

.scan-health-path code {
  background: #f5f5f5;
  padding: 1px 4px;
  border-radius: 2px;
  font-size: 11px;
  font-family: monospace;
}

.scan-health-suggestion {
  font-size: 12px;
  color: var(--ui-warning);
  display: flex;
  align-items: flex-start;
  margin-top: 4px;
  line-height: 1.5;
}

.scan-health-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

.resolved-path-hint {
  font-size: 12px;
  color: var(--ui-muted);
  margin-top: 4px;
  line-height: 1.5;
  display: flex;
  align-items: center;
}

.resolved-path-hint code {
  background: #f5f5f5;
  padding: 1px 4px;
  border-radius: 2px;
  font-size: 11px;
  font-family: monospace;
  color: var(--ui-warning);
  word-break: break-all;
}

.config-basepath-hint {
  font-size: 12px;
  color: var(--ui-text);
  background: #f0f9ff;
  border: 1px solid var(--ui-primary-border);
  border-radius: 4px;
  padding: 6px 10px;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
}

.config-basepath-hint code {
  background: #e8f4fd;
  padding: 1px 4px;
  border-radius: 2px;
  font-size: 11px;
  font-family: monospace;
  color: var(--ui-primary);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0;
  font-size: 14px;
}

.data-header-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.data-header-actions .el-button + .el-button {
  margin-left: 0;
}

.category-tabs {
  margin-top: 2px;
}

.subcategory-tabs {
  margin-top: 2px;
}

:deep(.el-tabs__header) {
  margin-bottom: 0;
}

:deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
}

:deep(.el-tabs__item) {
  padding: 0 12px;
  height: 28px;
  line-height: 28px;
  font-size: 13px;
}

:deep(.el-tabs__content) {
  padding: 0;
}

.content-area {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

.attr-sidebar {
  width: 180px;
  flex-shrink: 0;
  background: var(--ui-page);
  border-radius: 4px;
  padding: 8px;
  border: 1px solid var(--ui-border-soft);
}

.attr-header {
  font-size: 12px;
  color: var(--ui-muted);
  margin-bottom: 6px;
  padding-left: 4px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.attr-actions {
  display: flex;
  gap: 4px;
}

.attr-actions .el-button {
  padding: 0;
  font-size: 11px;
}

.attr-list {
  display: flex;
  flex-direction: column;
}

.attr-group {
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--ui-border-soft);
}

.attr-group:last-child {
  border-bottom: none;
  margin-bottom: 0;
}

.attr-category-checkbox {
  margin-left: 0;
  margin-bottom: 2px;
  font-weight: bold;
}

:deep(.attr-category-checkbox .el-checkbox__label) {
  font-size: 13px;
  padding-left: 6px;
  color: var(--ui-heading);
  font-weight: bold;
}

:deep(.attr-category-checkbox .el-checkbox__input) {
  position: relative;
  top: 1px;
}

.attr-children {
  padding-left: 20px;
  margin-top: 2px;
  display: flex;
  flex-wrap: wrap;
}

.attr-children .el-checkbox {
  display: inline-flex;
  align-items: flex-start;
  flex-basis: auto;
  width: auto;
  max-width: 100%;
  margin-right: 6px;
  margin-bottom: 2px;
  white-space: normal;
  word-break: break-word;
}

/* 覆盖 Element Plus 默认 el-checkbox 高度，让左侧紧凑 */
:deep(.attr-list .el-checkbox) {
  height: auto;
  line-height: 22px;
}
:deep(.attr-list .el-checkbox__label) {
  line-height: 22px;
}

:deep(.attr-children .el-checkbox__label) {
  white-space: normal;
  word-break: break-word;
  line-height: 1.4;
}

.attr-checkbox {
  margin-left: 0;
  margin-bottom: 4px;
}

:deep(.attr-checkbox .el-checkbox__label) {
  font-size: 12px;
  padding-left: 4px;
  color: var(--ui-text);
}

:deep(.attr-checkbox:hover .el-checkbox__label) {
  color: var(--ui-primary);
}

.attr-item {
  padding: 4px 10px;
  margin-bottom: 2px;
  border-radius: 4px;
  font-size: 13px;
  color: var(--ui-text);
  transition: all 0.2s;
  border: 1px solid transparent;
}

.attr-item-clickable {
  cursor: pointer;
}

.attr-item-clickable:hover {
  color: var(--ui-primary);
}

.attr-item-active {
  color: var(--ui-primary);
}

:deep(.attr-item .el-checkbox__label) {
  font-size: 13px;
  padding-left: 6px;
  color: inherit;
}

:deep(.attr-item .el-checkbox) {
  margin-bottom: 0;
}

.data-area {
  flex: 1;
  min-width: 0;
}

.filter-row {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
  align-items: center;
}

.toolbar-spacer {
  flex: 1;
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

.inner-business-area {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.step-response-area,
.button-count-area {
  border: 1px solid var(--ui-border-soft);
  border-radius: 4px;
  padding: 10px;
  background: #fafafa;
}

.area-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ui-heading);
  margin-bottom: 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--ui-border-soft);
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
  .attr-sidebar {
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
</style>

<style src="@/views/data/prototype-theme.css"></style>
