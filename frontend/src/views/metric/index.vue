<!--
  ============================================================================
  指标体系配置页
  ============================================================================
  【与告警策略的关系（2026-10-28 起）】
  本页只描述「指标是什么」：系统 / 大类 / 小类 / 名称 / 编码 / 单位 / 定义 /
  统计口径 / 责任信息等。**不再承担任何告警阈值、工单阈值的维护**。

  阈值统一在「告警管理 → 告警策略」维护。但为了少跳一次页面，本页表格右侧
  操作列提供「更多 → 设置策略阈值」快捷入口，它打开的是**公共组件**：

      frontend/src/components/alarm/StrategyEditorDialog.vue

  该组件同时被 views/alarm/index.vue 使用，是策略表单的唯一实现。
  ⚠️ 后续给策略增加字段（例如告警等级：重要 / 一般）请只改这个组件，
     改完两个页面同时生效，不要在本页另写一套表单。

  详细约定见：doc/告警策略表单复用说明.md
  ============================================================================
-->
<template>
  <div class="metric-page workspace-theme">
    <WorkspacePageHeader title="指标体系配置" description="统一管理系统、指标大类、小类与具体指标，维护指标定义、层级权重及策略配置。" badge="全层级纳管" tone="success" />

    <div class="metric-workspace">
      <MetricOverviewCards :stats="metricStats" :subcategory-count="subcategoryCount" @configure="key => ({ system: openSystemDialog, category: openCategoryWeightDialog, subcategory: openSubcategoryDialog, metric: openMetricWeightDialog })[key]()" />

      <section class="metric-actions" aria-label="指标操作">
        <!-- 按钮排序约定（2026-11-18）：主操作 → 白色默认按钮组 → 彩色描边按钮组。
             同类型必须相邻，避免再次出现白按钮被彩色按钮隔开。 -->
        <div class="header-buttons">
          <el-button type="primary" @click="handleImport"><el-icon><Plus /></el-icon>导入指标</el-button>
          <el-button @click="openBackupDialog"><el-icon><Clock /></el-icon>备份记录</el-button>
          <el-button @click="exportWeightConfig"><el-icon><Upload /></el-icon>导出权重</el-button>
          <el-button plain @click="audienceDictVisible = true"><el-icon><User /></el-icon>岗位字典</el-button>
          <el-button :disabled="!selectedRows.length" @click="handleBatchStatus(1)"><el-icon><SwitchButton /></el-icon>批量启用</el-button>
          <el-button v-if="canReviewWeights" type="primary" plain @click="reviewWeights = true">检查 AI 权重建议</el-button>
          <el-button type="success" plain @click="healthRuleVisible = true"><el-icon><TrendCharts /></el-icon>健康度规则配置</el-button>
          <el-button type="danger" plain :disabled="!selectedRows.length" @click="handleBatchDelete"><el-icon><Delete /></el-icon>批量删除</el-button>
          <el-button type="warning" plain :disabled="!selectedRows.length" @click="handleBatchStatus(0)"><el-icon><TurnOff /></el-icon>批量停用</el-button>
        </div>
        <el-button class="audit-sync-button" @click="handleSyncAudit"><el-icon><Refresh /></el-icon>系统稽核指标同步</el-button>
      </section>

      <MetricWeightReview v-if="reviewWeights" v-model="reviewWeights" :categories="categoryOptions" />
      <HealthRuleDrawer v-model="healthRuleVisible" @saved="loadData" />
      <AudienceDictDrawer v-model="audienceDictVisible" @saved="onAudienceDictSaved" />

      <!-- 多维度筛选工具栏 -->
      <MetricFilters v-model:filters="queryParams" :system-options="systemOptions" :category-options="categoryOptions" :sub-category-options="subCategoryOptions" :metric-type-options="metricTypeOptions" @filter-change="handleFilterChange" @search="loadData" @reset="resetFilters" />

      <!-- 操作栏 -->
      <div class="table-toolbar">
        <!-- 配置概览（2026-10-28 新增）
             目的：不点进告警页也能知道「多少指标配了告警、多少配了工单」。
             口径：只统计已启用指标；「已配置」= 存在启用策略且对应阈值有值（与告警页一致）。
             交互：每个数字都可点，点一下按该条件筛选表格，再点一下取消。 -->
        <MetricConfigurationSummary :config-summary="configSummary" :config-filter="configFilter" @toggle="toggleConfigFilter" @clear="clearConfigFilter" />
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

      <section class="metric-list-panel" aria-label="指标列表">
      <!-- 响应式表格 - 桌面端 -->
      <div id="metricTableSection" class="table-container desktop-table">
        <el-table
          class="metric-config-table" size="small" :tooltip-options="{ popperClass: 'metric-config-overflow' }"
          v-sticky-header
          v-mirror-scroll
          :data="tableData"
          v-loading="tableLoading"
          :fit="true"
          style="width: 100%;"
          @selection-change="handleSelectionChange"
          @header-contextmenu="handleHeaderContextMenu"
        ><template #empty><WorkspaceEmptyState title="暂无匹配的指标" description="可调整系统、大类、小类或指标名称筛选。" /></template>
          <el-table-column type="selection" width="50" fixed="left"/>
          <el-table-column v-if="!isColumnHidden('systemName')" prop="systemName" label="系统" width="100" class-name="wrap-cell">
            <template #default="{ row }">
              <el-link type="primary" @click="showSystemInfo(row)">{{ row.systemName || '-' }}</el-link>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('categoryName')" prop="categoryName" label="指标大类" width="120" class-name="wrap-cell">
            <template #default="{ row }">
              <el-link class="metric-category-link" type="primary" @click="showCategoryInfo(row)">{{ row.categoryName || '-' }}</el-link>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('subCategoryName')" prop="subCategoryName" label="指标小类" width="100" class-name="wrap-cell">
            <template #default="{ row }">
              <span @click="editCell(row, 'subCategoryName')">{{ row.subCategoryName || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('metricName')" prop="metricName" label="指标名称" min-width="180" class-name="metric-name-cell">
            <template #default="{ row }">
              <editable-cell :value="row.metricName" @save="(val) => saveCell(row, 'metricName', val)" />
            </template>
          </el-table-column>
          <!-- 指标编码只读（2026-11-18）：编码用于关联采集数据与规则，
               改动会让历史数据、采集任务、告警记录全部断链，因此不提供行内编辑。 -->
          <el-table-column v-if="!isColumnHidden('metricCode')" prop="metricCode" label="指标编码" width="150" class-name="metric-code-cell">
            <template #default="{ row }">
              <span class="metric-code-text" :title="row.metricCode ? '指标编码不可修改' : '未设置编码'">{{ row.metricCode || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('unit')" prop="unit" label="单位" width="96" align="center">
            <template #default="{ row }">
              <el-dropdown trigger="click" @command="(v) => saveCell(row, 'unit', v)">
                <span :class="['unit-chip', { 'unit-chip-missing': !row.unit }]" :title="row.unit ? '点击修改单位' : '点击设置单位'">
                  {{ row.unit || '未配置' }}
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="u in unitOptions" :key="u" :command="u">{{ u }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
          <!-- 2026-11-17：面向人员改为岗位下拉（一个指标只挂一个岗位）。
               写入 audience_code，responsible_person 由后端按字典名称回填。 -->
          <el-table-column v-if="!isColumnHidden('responsiblePerson')" prop="audienceCode" label="面向人员" width="130">
            <template #default="{ row }">
              <el-select :model-value="row.audienceCode" size="small" placeholder="未设置"
                class="audience-select-cell" :title="row.audienceCode ? '点击修改岗位' : '点击设置岗位'"
                @change="(value) => saveAudience(row, value)">
                <el-option v-for="item in audienceOptions" :key="item.audienceCode"
                  :value="item.audienceCode" :label="item.audienceName" />
                <!-- 岗位停用后指标仍保留编码：下拉里补一个只读项显示原名，避免只看到一个裸编码 -->
                <el-option
                  v-if="row.audienceCode && !audienceOptions.some(item => item.audienceCode === row.audienceCode)"
                  :key="'inactive-' + row.audienceCode"
                  :value="row.audienceCode"
                  :label="`${row.responsiblePerson || row.audienceCode}（已停用）`"
                  disabled
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('metricDefinition')" prop="metricDefinition" label="指标定义" min-width="200" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.metricDefinition" @save="(val) => saveCell(row, 'metricDefinition', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('statisticsCaliber')" prop="statisticsCaliber" label="统计口径" min-width="180" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.statisticsCaliber" @save="(val) => saveCell(row, 'statisticsCaliber', val)" />
            </template>
          </el-table-column>
          <!-- 导入模板对应列：取数来源 / 数据对接模式 / 采集频率 / 采集状态（默认隐藏） -->
          <el-table-column v-if="!isColumnHidden('dataSource')" prop="dataSource" label="取数来源" width="130" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.dataSource" @save="(val) => saveCell(row, 'dataSource', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('dataSyncMode')" prop="dataSyncMode" label="数据对接模式" width="130" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.dataSyncMode" @save="(val) => saveCell(row, 'dataSyncMode', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('collectFrequency')" prop="collectFrequency" label="采集频率" width="110" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.collectFrequency" @save="(val) => saveCell(row, 'collectFrequency', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('collectStatus')" prop="collectStatus" label="采集状态" width="110" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.collectStatus" @save="(val) => saveCell(row, 'collectStatus', val)" />
            </template>
          </el-table-column>
          <!-- 2026-10-28 指标与告警解耦：告警条件/阈值/连续次数、工单条件/阈值/连续次数 6 列已移除，
               阈值请在「告警管理 → 告警策略」中配置 -->
          <el-table-column v-if="!isColumnHidden('department')" prop="department" label="部室" width="120" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.department" @save="(val) => saveCell(row, 'department', val)" />
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('reporter')" prop="reporter" label="填报人" width="100" class-name="wrap-cell">
            <template #default="{ row }">
              <editable-cell :value="row.reporter" @save="(val) => saveCell(row, 'reporter', val)" />
            </template>
          </el-table-column>
          <!-- 告警概览（2026-10-28 新增）
               把「告警 / 工单」两个配置情况压在一列里，各占一行，避免横向铺一排文字：
                 已配置 → 展示条件 + 阈值 + 单位（如 > 5 秒），并高亮
                 未配置 → 灰色「未配置」
               数据由后端随列表返回（读 alarm_strategy，只是展示字段，不落库）。 -->
          <el-table-column v-if="!isColumnHidden('alarmOverview')" label="告警概览" width="136" class-name="alarm-overview-cell">
            <template #default="{ row }">
              <div class="ao-row" :class="{ on: row.alarmThreshold != null }">
                <span class="ao-tag alarm">告警</span>
                <span class="ao-val" :title="thresholdText(row, 'alarm')">{{ thresholdText(row, 'alarm') }}</span>
              </div>
              <div class="ao-row" :class="{ on: row.gongdanThreshold != null }">
                <span class="ao-tag gongdan">工单</span>
                <span class="ao-val" :title="thresholdText(row, 'gongdan')">{{ thresholdText(row, 'gongdan') }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column v-if="!isColumnHidden('status')" prop="status" label="状态" width="70" align="center">
            <template #default="{ row }">
              <el-switch
                v-model="row.status"
                :active-value="1"
                :inactive-value="0"
                @change="saveStatus(row)"
                size="small"
              />
            </template>
          </el-table-column>
          <!-- 操作列：固定在右侧，横向滚动时始终可见 -->
          <el-table-column label="操作" width="132" fixed="right" align="center" class-name="op-cell">
            <template #default="{ row }">
              <div class="op-bar">
                <el-button link type="primary" size="small" @click="openMetricDetail(row)">详情</el-button>
                <el-dropdown trigger="click" @command="(cmd) => handleMetricMore(cmd, row)">
                  <el-button link type="primary" size="small" class="op-more">
                    更多<el-icon class="op-more__arrow"><ArrowDown /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="strategy">
                        <el-icon><Setting /></el-icon>设置策略阈值
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 响应式卡片 - 移动端 -->
      <div class="mobile-cards">
        <el-card v-for="row in tableData" :key="row.id" class="metric-card" shadow="hover">
          <template #header>
            <div class="card-title">
              <el-link type="primary" @click="showSystemInfo(row)">{{ row.systemName || '-' }}</el-link>
              <el-switch
                v-model="row.status"
                :active-value="1"
                :inactive-value="0"
                @change="saveStatus(row)"
                size="small"
              />
            </div>
          </template>
          <div class="card-content">
            <div class="card-row">
              <span class="label">指标大类：</span>
              <el-link class="metric-category-link" type="primary" @click="showCategoryInfo(row)">{{ row.categoryName || '-' }}</el-link>
            </div>
            <div class="card-row">
              <span class="label">指标小类：</span>
              <span class="value">{{ row.subCategoryName || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">指标名称：</span>
              <span class="value">{{ row.metricName || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">指标编码：</span>
              <span class="value">{{ row.metricCode || '-' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <el-button type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </div>
        </el-card>
        <el-empty v-if="tableData.length === 0" description="暂无数据" />
      </div>

      <!-- 分页 -->
      <WorkspacePagination v-model:page="pagination.page" v-model:size="pagination.size" :total="pagination.total" :page-sizes="[10, 20, 50, 100]" @change="loadData" />
      </section>
    </div>

    <!-- 导入对话框 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="importDialogVisible" title="导入指标" :width="importDialogWidth" destroy-on-close @closed="resetImportState">
      <!-- ============ 第一步：选模式 + 上传文件 ============ -->
      <template v-if="!importPreview">
        <div class="import-mode-grid">
          <div :class="['import-mode', { 'is-active': importMode === 'incremental' }]" @click="importMode = 'incremental'">
            <div class="import-mode__head">
              <span class="import-mode__name">增量导入</span>
              <el-tag size="small" type="success" effect="light" round>日常维护用这个</el-tag>
            </div>
            <div class="import-mode__desc">
              Excel 里<b>有</b>的：新增或更新，已有指标保留原 ID，<b>不影响已有告警策略与历史</b>。<br>
              Excel 里<b>没有</b>的：一律不动。
            </div>
          </div>
          <div :class="['import-mode', 'import-mode--danger', { 'is-active': importMode === 'full' }]" @click="importMode = 'full'">
            <div class="import-mode__head">
              <span class="import-mode__name">全量导入</span>
              <el-tag size="small" type="danger" effect="light" round>会删除数据</el-tag>
            </div>
            <div class="import-mode__desc">
              以 Excel 为全集：库里存在、但 Excel 里<b>没有</b>的指标会被<b>彻底删除</b>，
              连同其历史值/健康度/告警触发记录（告警、工单、问题三类业务记录保留）。
            </div>
          </div>
        </div>

        <div class="import-tools">
          <span class="import-tools__label">下载模板</span>
          <el-button link type="primary" @click="downloadTemplate">空白模板</el-button>
          <span class="import-tools__sep">|</span>
          <el-button link type="primary" @click="downloadFullTemplate">全量模板（含现有 {{ importDbTotal }} 条指标）</el-button>
        </div>

        <el-checkbox v-if="importMode === 'full'" v-model="allowDeleteCategory" class="import-guard">
          允许删除整个指标大类（Excel 里缺少的大类会被整体移除）
        </el-checkbox>

        <el-upload
          ref="uploadRef"
          class="upload-demo"
          drag
          :auto-upload="false"
          :on-change="handleFileChange"
          :limit="1"
          accept=".xlsx"
          style="margin-top: 14px;"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">
            拖拽文件到此处或 <em>点击上传</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">只支持 xlsx，需包含「系统清单」「指标大类清单」「指标清单」三个 Sheet</div>
          </template>
        </el-upload>
      </template>

      <!-- ============ 第二步：预演结果 ============ -->
      <template v-else>
        <div class="pv-summary">
          <div class="pv-card">
            <div class="pv-num pv-num--add">{{ importPreview.summary.metricAdd }}</div>
            <div class="pv-label">新增</div>
          </div>
          <div class="pv-card">
            <div class="pv-num pv-num--upd">{{ importPreview.summary.metricUpdate }}</div>
            <div class="pv-label">更新</div>
          </div>
          <div class="pv-card">
            <div class="pv-num pv-num--del">{{ importPreview.summary.metricDelete }}</div>
            <div class="pv-label">删除</div>
          </div>
          <div class="pv-card">
            <div class="pv-num pv-num--same">{{ importPreview.summary.metricUnchanged }}</div>
            <div class="pv-label">无变化</div>
          </div>
        </div>
        <div class="pv-meta">
          当前库中 <b>{{ importPreview.summary.totalInDb }}</b> 条 · Excel 中 <b>{{ importPreview.summary.totalInExcel }}</b> 条 ·
          导入后 <b>{{ importPreview.summary.totalInExcel }}</b> 条
          <el-tag size="small" :type="importMode === 'full' ? 'danger' : 'success'" effect="light" style="margin-left: 8px;">
            {{ importMode === 'full' ? '全量导入' : '增量导入' }}
          </el-tag>
        </div>

        <!-- 冲突（阻塞） -->
        <el-alert v-if="importPreview.conflicts && importPreview.conflicts.length" type="error" :closable="false" style="margin-top: 12px;">
          <template #title>发现 {{ importPreview.conflicts.length }} 处问题，修正后才能导入</template>
          <ul class="pv-list">
            <li v-for="(c, i) in importPreview.conflicts" :key="'c' + i">{{ c }}</li>
          </ul>
        </el-alert>

        <!-- 警告（不阻塞） -->
        <el-alert v-if="importPreview.warnings && importPreview.warnings.length" type="warning" :closable="false" style="margin-top: 10px;">
          <template #title>提示</template>
          <ul class="pv-list">
            <li v-for="(w, i) in importPreview.warnings" :key="'w' + i">{{ w }}</li>
          </ul>
        </el-alert>

        <!-- 删除明细 -->
        <el-collapse v-if="importPreview.summary.metricDelete > 0" class="pv-collapse">
          <el-collapse-item :title="`将删除 ${importPreview.summary.metricDelete} 个指标（点击查看明细）`" name="del">
            <div v-for="g in importPreview.deletions.metricsByCategory" :key="g.categoryCode" class="pv-group">
              <div class="pv-group__head">
                <span class="pv-group__name">{{ g.categoryName }}（{{ g.categoryCode }}）</span>
                <span class="pv-group__count">{{ g.count }} 条</span>
              </div>
              <div class="pv-group__items">
                <span v-for="it in g.items" :key="it.id" class="pv-item" :title="'ID ' + it.id">
                  {{ it.metricName }}<span class="pv-item__code">{{ it.metricCode }}</span>
                </span>
              </div>
            </div>
            <div v-if="importPreview.deletions.categories.length" class="pv-group">
              <div class="pv-group__head">
                <span class="pv-group__name">整个大类一起删除</span>
                <span class="pv-group__count">{{ importPreview.deletions.categories.length }} 个大类</span>
              </div>
              <div class="pv-group__items">
                <span v-for="c in importPreview.deletions.categories" :key="c.categoryCode" class="pv-item is-danger">
                  {{ c.categoryName }}（{{ c.categoryCode }}）
                </span>
              </div>
            </div>
            <div class="pv-ref-tip">
              将一并清理历史数据约 <b>{{ importPreview.deletions.totalRefRows }}</b> 行；
              告警、工单、问题记录<b>不受影响</b>。
            </div>
          </el-collapse-item>
        </el-collapse>

        <!-- 大变更确认词 -->
        <div v-if="importPreview.needConfirmWord" class="pv-confirm">
          <div class="pv-confirm__tip">
            本次删除量较大，请输入「<b>{{ importPreview.confirmWord }}</b>」以确认
          </div>
          <el-input v-model="confirmWordInput" :placeholder="importPreview.confirmWord" style="width: 220px;" />
        </div>
      </template>

      <template #footer>
        <template v-if="!importPreview">
          <el-button @click="importDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="importPreviewLoading" @click="handlePreview">下一步：校验文件</el-button>
        </template>
        <template v-else>
          <el-button @click="importPreview = null">返回修改</el-button>
          <el-button
            :type="importMode === 'full' ? 'danger' : 'primary'"
            :loading="importExecuting"
            :disabled="!importPreview.canExecute"
            @click="handleExecute"
          >确认导入</el-button>
        </template>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustImportDialogWidth" :visible="importDialogVisible" />
    </div>

    <!-- 系统列表弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="systemDialogVisible" title="系统列表" :width="systemDialogWidth">
      <el-tabs v-model="systemDialogActiveTab" style="margin-top: 10px;">
        <el-tab-pane label="系统列表" name="list">
          <div class="dialog-toolbar">
            <el-button type="primary" size="small" @click="addSystem">
              <el-icon><Plus /></el-icon>新增系统
            </el-button>
            <el-button type="danger" size="small" :disabled="!selectedSystems.length" @click="batchDeleteSystems">
              <el-icon><Delete /></el-icon>删除选中 ({{ selectedSystems.length }})
            </el-button>
          </div>
          <el-table v-sticky-header v-mirror-scroll :data="systemList" border max-height="350" @selection-change="handleSystemSelection">
            <el-table-column type="selection" width="40" fixed="left"/>
            <el-table-column prop="systemName" label="系统名称" min-width="120">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.systemName" size="small" @blur="saveSystem(row)" />
                <span v-else @dblclick="startEditSystem(row)" style="cursor: pointer;">{{ row.systemName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="systemCode" label="系统编码" min-width="100">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.systemCode" size="small" :disabled="!!row.id" @blur="saveSystem(row)" />
                <span v-else @dblclick="startEditSystem(row)" style="cursor: pointer;">{{ row.systemCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="150">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.description" size="small" @blur="saveSystem(row)" />
                <span v-else @dblclick="startEditSystem(row)" style="cursor: pointer;">{{ row.description || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-switch
                  v-model="row.status"
                  :active-value="1"
                  :inactive-value="0"
                  size="small"
                  @change="saveSystem(row)"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="告警策略" name="strategy">
          <el-table :data="systemStrategyList" border size="small" max-height="400">
            <el-table-column prop="systemName" label="对象" width="150" />
            <el-table-column prop="strategyName" label="策略名称" min-width="180" />
            <el-table-column label="告警规则" min-width="260"><template #default="{ row }">{{ row.alarmEnabled === 1 ? ruleSummary(row, 'alarm') : '未启用' }}</template></el-table-column>
            <el-table-column label="工单规则" min-width="260"><template #default="{ row }">{{ row.gongdanEnabled === 1 ? ruleSummary(row, 'gongdan') : '未启用' }}</template></el-table-column>
            <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }">
              <el-button link type="primary" @click="openHealthStrategy('system', row.systemId, row.systemName, row.categoryId)">设置策略</el-button>
            </template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <template #footer v-if="systemDialogActiveTab === 'list'">
        <el-button @click="systemDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustSystemDialogWidth" :visible="systemDialogVisible" />
    </div>

    <!-- 指标大类列表弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="categoryDialogVisible" title="指标大类列表" :width="categoryDialogWidth">
      <div class="dialog-toolbar">
        <el-button type="primary" size="small" @click="addCategory">
          <el-icon><Plus /></el-icon>新增指标大类
        </el-button>
        <el-button type="danger" size="small" :disabled="!selectedCategories.length" @click="batchDeleteCategories">
          <el-icon><Delete /></el-icon>删除选中 ({{ selectedCategories.length }})
        </el-button>
      </div>
      <el-table v-sticky-header v-mirror-scroll :data="categoryList" border max-height="400" @selection-change="handleCategorySelection2">
        <el-table-column type="selection" width="40" fixed="left"/>
        <el-table-column prop="categoryName" label="指标大类名称" min-width="140">
          <template #default="{ row }">
            <el-input v-if="row._editing" v-model="row.categoryName" size="small" @blur="saveCategory(row)" />
            <span v-else @dblclick="startEditCategory(row)" style="cursor: pointer;">{{ row.categoryName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="categoryCode" label="指标大类编码" min-width="120">
          <template #default="{ row }">
            <el-input v-if="row._editing" v-model="row.categoryCode" size="small" :disabled="!!row.id" @blur="saveCategory(row)" />
            <span v-else @dblclick="startEditCategory(row)" style="cursor: pointer;">{{ row.categoryCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="150">
          <template #default="{ row }">
            <el-input v-if="row._editing" v-model="row.description" size="small" @blur="saveCategory(row)" />
            <span v-else @dblclick="startEditCategory(row)" style="cursor: pointer;">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="subcategoryCount" label="小类数量" width="90" align="center" />
        <el-table-column prop="status" label="状态" width="70">
          <template #default="{ row }">
            <el-switch
              v-model="row.status"
              :active-value="1"
              :inactive-value="0"
              size="small"
              @change="saveCategory(row)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustCategoryDialogWidth" :visible="categoryDialogVisible" />
    </div>

    <!-- 备份记录弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="backupDialogVisible" title="备份记录" :width="backupDialogWidth">
      <el-table v-sticky-header v-mirror-scroll :data="backupList" border max-height="400">
        <el-table-column prop="backupTime" label="备份时间" width="180" />
        <el-table-column prop="systemCount" label="系统数" width="80" />
        <el-table-column prop="categoryCount" label="大类数" width="80" />
        <el-table-column prop="itemCount" label="指标数" width="80" />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="downloadBackup(row)">下载</el-button>
            <el-button link type="danger" size="small" @click="restoreBackup(row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="backup-tip">
        <el-icon><InfoFilled /></el-icon>
        备份文件保存在应用目录下的 <code>metric_backups/</code> 文件夹中
      </div>
      <template #footer>
        <el-button @click="backupDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustBackupDialogWidth" :visible="backupDialogVisible" />
    </div>

    <!-- 指标大类权重配置弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="categoryWeightDialogVisible" title="指标大类配置" :width="categoryWeightDialogWidth">
      <div class="dialog-info-bar">
        <span class="info-label">系统：</span>
        <div class="category-tags">
          <el-tag
            v-for="sys in systemList"
            :key="sys.id"
            :type="categoryWeightSystemId === sys.id ? 'primary' : 'info'"
            class="category-tag"
            @click="selectCategoryWeightSystem(sys.id)"
            style="cursor: pointer;"
          >
            {{ sys.systemName }}
          </el-tag>
        </div>
      </div>
      <el-tabs v-model="categoryDialogActiveTab" style="margin-top: 15px;">
        <el-tab-pane label="大类列表" name="list">
          <div class="dialog-toolbar">
            <el-button type="primary" size="small" @click="addCategory">
              <el-icon><Plus /></el-icon>新增指标大类
            </el-button>
            <el-button type="danger" size="small" :disabled="!selectedCategories.length" @click="batchDeleteCategories">
              <el-icon><Delete /></el-icon>删除选中 ({{ selectedCategories.length }})
            </el-button>
          </div>
          <el-table v-sticky-header v-mirror-scroll :data="categoryList" border max-height="350" @selection-change="handleCategorySelection">
            <el-table-column type="selection" width="40" fixed="left"/>
            <el-table-column prop="categoryName" label="指标大类名称" min-width="140">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.categoryName" size="small" @blur="saveCategory(row)" />
                <span v-else @dblclick="startEditCategory(row)" style="cursor: pointer;">{{ row.categoryName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="categoryCode" label="编码" width="120">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.categoryCode" size="small" :disabled="!!row.id" @blur="saveCategory(row)" />
                <span v-else @dblclick="startEditCategory(row)" style="cursor: pointer;">{{ row.categoryCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="150">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.description" size="small" @blur="saveCategory(row)" />
                <span v-else @dblclick="startEditCategory(row)" style="cursor: pointer;">{{ row.description || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="subcategoryCount" label="小类数量" width="90" align="center" />
            <el-table-column prop="status" label="状态" width="70">
              <template #default="{ row }">
                <el-switch
                  v-model="row.status"
                  :active-value="1"
                  :inactive-value="0"
                  size="small"
                  @change="saveCategory(row)"
                />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="权重配置" name="weight">
          <div class="weight-config-section" v-if="categoryWeightSystemId">
            <div class="weight-sum-row">
              <span>权重总和：</span>
              <span :class="{ 'sum-error': Math.abs(sumCategoryWeights - 1) > 0.01 }">{{ sumCategoryWeights.toFixed(2) }}</span>
              <span v-if="categoryLastSaveTime" class="save-time">上次保存: {{ categoryLastSaveTime }}</span>
            </div>
            <div v-for="(item, index) in categoryWeightList" :key="item.categoryId" class="weight-row">
              <span class="weight-label">{{ item.categoryName }}</span>
              <el-slider
                v-model="categoryWeightList[index].weight"
                :min="0"
                :max="1"
                :step="0.01"
                @change="(val) => onCategoryWeightChange(index, val)"
              />
              <span class="weight-value">{{ categoryWeightList[index].weight.toFixed(2) }}</span>
            </div>
          </div>
          <el-empty v-else description="请先选择系统" />
        </el-tab-pane>
        <el-tab-pane label="告警策略" name="strategy">
          <el-table :data="categoryStrategyList" border size="small" max-height="400">
            <el-table-column prop="categoryName" label="对象" width="150" />
            <el-table-column prop="strategyName" label="策略名称" min-width="180" />
            <el-table-column label="告警规则" min-width="260"><template #default="{ row }">{{ row.alarmEnabled === 1 ? ruleSummary(row, 'alarm') : '未启用' }}</template></el-table-column>
            <el-table-column label="工单规则" min-width="260"><template #default="{ row }">{{ row.gongdanEnabled === 1 ? ruleSummary(row, 'gongdan') : '未启用' }}</template></el-table-column>
            <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }">
              <el-button link type="primary" @click="openHealthStrategy('category', row.categoryId, row.categoryName, row.categoryId)">设置策略</el-button>
            </template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <template #footer v-if="categoryDialogActiveTab === 'info'">
        <el-button @click="categoryDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 指标小类权重配置弹窗 -->
    <el-dialog class="workspace-theme metric-config-dialog" v-model="subcategoryDialogVisible" title="指标小类配置" width="900px">
      <div class="dialog-info-bar">
        <span class="info-label">系统：</span>
        <el-select v-model="subcategorySystemId" placeholder="请选择系统" style="width: 180px;" @change="onSubcategorySystemChange">
          <el-option v-for="sys in systemList" :key="sys.id" :label="sys.systemName" :value="sys.id" />
        </el-select>
        <span class="info-label" style="margin-left: 20px;">大类：</span>
        <div class="category-tags">
          <el-tag
            v-for="cat in categoryOptions"
            :key="cat.id"
            :type="subcategoryCategoryId === cat.id ? 'primary' : 'info'"
            class="category-tag"
            @click="selectCategory(cat.id)"
            style="cursor: pointer;"
          >
            {{ cat.categoryName }}
          </el-tag>
        </div>
      </div>
      <el-tabs v-model="subcategoryActiveTab" style="margin-top: 15px;">
        <el-tab-pane label="小类列表" name="list">
          <div class="subcategory-toolbar" v-if="subcategoryCategoryId">
            <el-button type="primary" size="small" @click="openSubcategoryForm">
              <el-icon><Plus /></el-icon>新增小类
            </el-button>
            <el-button type="danger" size="small" :disabled="!selectedSubcategories.length" @click="batchDeleteSubcategories">
              <el-icon><Delete /></el-icon>删除选中 ({{ selectedSubcategories.length }})
            </el-button>
          </div>
          <el-table v-sticky-header v-mirror-scroll :data="subcategoryList" border max-height="300" @selection-change="handleSubcategorySelection">
            <el-table-column type="selection" width="40" fixed="left"/>
            <el-table-column prop="subCategoryCode" label="小类编码" width="120">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.subCategoryCode" size="small" :disabled="!!row.id" @blur="saveSubcategoryRow(row)" />
                <span v-else @dblclick="startEditSubcategory(row)" style="cursor: pointer;">{{ row.subCategoryCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="subCategoryName" label="指标小类名称" min-width="150">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.subCategoryName" size="small" @blur="saveSubcategoryRow(row)" />
                <span v-else @dblclick="startEditSubcategory(row)" style="cursor: pointer;">{{ row.subCategoryName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="itemCount" label="指标数量" width="100" />
            <el-table-column prop="description" label="描述" min-width="150">
              <template #default="{ row }">
                <el-input v-if="row._editing" v-model="row.description" size="small" @blur="saveSubcategoryRow(row)" />
                <span v-else @dblclick="startEditSubcategory(row)" style="cursor: pointer;">{{ row.description || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-switch
                  v-model="row.status"
                  :active-value="'enabled'"
                  :inactive-value="'disabled'"
                  size="small"
                  @change="saveSubcategoryRow(row)"
                />
              </template>
            </el-table-column>
          </el-table>
          <!-- 新增/编辑小类表单 -->
          <div style="position: relative;">
            <el-dialog class="workspace-theme metric-config-dialog" v-model="subcategoryFormVisible" :title="editingSubcategory.id ? '编辑小类' : '新增小类'" :width="subcategoryFormWidth" append-to-body>
            <el-form :model="editingSubcategory" label-width="100px">
              <el-form-item label="小类编码">
                <el-input v-model="editingSubcategory.subCategoryCode" placeholder="请输入小类编码" :disabled="!!editingSubcategory.id" />
              </el-form-item>
              <el-form-item label="小类名称">
                <el-input v-model="editingSubcategory.subCategoryName" placeholder="请输入小类名称" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input v-model="editingSubcategory.description" type="textarea" :rows="2" placeholder="请输入描述" />
              </el-form-item>
              <el-form-item label="状态">
                <el-radio-group v-model="editingSubcategory.status">
                  <el-radio value="enabled">启用</el-radio>
                  <el-radio value="disabled">禁用</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="subcategoryFormVisible = false">取消</el-button>
              <el-button type="primary" @click="saveSubcategory">保存</el-button>
            </template>
          </el-dialog>
          <DialogSizeControls :adjustWidth="adjustSubcategoryFormWidth" :visible="subcategoryFormVisible" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="权重配置" name="weight">
          <div class="weight-config-section" v-if="subcategoryCategoryId">
            <div class="weight-sum-row">
              <span>权重总和：</span>
              <span :class="{ 'sum-error': Math.abs(sumSubcategoryWeights - 1) > 0.01 }">{{ sumSubcategoryWeights.toFixed(2) }}</span>
              <span v-if="subcategoryLastSaveTime" class="save-time">上次保存: {{ subcategoryLastSaveTime }}</span>
            </div>
            <div v-for="(item, index) in subcategoryWeightList" :key="item.subCategoryName" class="weight-row">
              <span class="weight-label">{{ item.subCategoryName }}</span>
              <el-slider
                v-model="subcategoryWeightList[index].weight"
                :min="0"
                :max="1"
                :step="0.01"
                @change="(val) => onSubcategoryWeightChange(index, val)"
              />
              <span class="weight-value">{{ subcategoryWeightList[index].weight.toFixed(2) }}</span>
            </div>
          </div>
          <el-empty v-else description="请先选择系统和大类" />
        </el-tab-pane>
        <el-tab-pane label="告警策略" name="strategy">
          <el-table :data="subCategoryStrategyList" border size="small" max-height="400">
            <el-table-column prop="subCategoryName" label="对象" width="150" />
            <el-table-column prop="strategyName" label="策略名称" min-width="180" />
            <el-table-column label="告警规则" min-width="260"><template #default="{ row }">{{ row.alarmEnabled === 1 ? ruleSummary(row, 'alarm') : '未启用' }}</template></el-table-column>
            <el-table-column label="工单规则" min-width="260"><template #default="{ row }">{{ row.gongdanEnabled === 1 ? ruleSummary(row, 'gongdan') : '未启用' }}</template></el-table-column>
            <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }">
              <el-button link type="primary" @click="openHealthStrategy('subCategory', row.subCategoryId, row.subCategoryName, row.categoryId)">设置策略</el-button>
            </template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="subcategoryDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustSubcategoryDialogWidth" :visible="subcategoryDialogVisible" />
    </div>

    <!-- 具体指标权重配置弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="metricWeightDialogVisible" title="具体指标权重配置" :width="metricWeightDialogWidth">
      <div class="dialog-info-bar">
        <span class="info-label">系统：</span>
        <div class="category-tags">
          <el-tag
            v-for="sys in systemList"
            :key="sys.id"
            :type="metricWeightSystemId === sys.id ? 'primary' : 'info'"
            class="category-tag"
            @click="selectMetricWeightSystem(sys.id)"
            style="cursor: pointer;"
          >
            {{ sys.systemName }}
          </el-tag>
        </div>
        <span class="info-label" style="margin-left: 15px;" v-if="categoryOptions.length > 0">大类：</span>
        <div class="category-tags" v-if="categoryOptions.length > 0">
          <el-tag
            v-for="cat in categoryOptions"
            :key="cat.id"
            :type="metricWeightCategoryId === cat.id ? 'primary' : 'info'"
            class="category-tag"
            @click="selectMetricWeightCategory(cat.id)"
            style="cursor: pointer;"
          >
            {{ cat.categoryName }}
          </el-tag>
        </div>
      </div>
      <div class="dialog-info-bar" style="margin-top: 10px;" v-if="subCategoryOptions.length > 0">
        <span class="info-label">小类：</span>
        <div class="category-tags">
          <el-tag
            v-for="sub in subCategoryOptions"
            :key="sub"
            :type="metricWeightSubCategoryName === sub ? 'primary' : 'info'"
            class="category-tag"
            @click="selectMetricWeightSubcategory(sub)"
            style="cursor: pointer;"
          >
            {{ sub }}
          </el-tag>
        </div>
      </div>
      <el-alert v-if="categoryOptions.find(c => c.id === metricWeightCategoryId)?.categoryCode === 'satisfaction'" title="满意度健康度权重尚未确认。下方未保存的默认比例仅供编辑，不代表已完成健康度计算配置。" type="info" :closable="false" />
      <div class="weight-config-section" style="margin-top: 15px;" v-if="metricWeightList.length > 0">
        <div class="weight-sum-row">
          <span>权重总和：</span>
          <span :class="{ 'sum-error': Math.abs(sumMetricWeights - 1) > 0.01 }">{{ sumMetricWeights.toFixed(2) }}</span>
          <span v-if="metricLastSaveTime" class="save-time">上次保存: {{ metricLastSaveTime }}</span>
        </div>
        <div v-for="(item, index) in metricWeightList" :key="item.metricId" class="weight-row">
          <span class="weight-label" :title="item.metricName">{{ item.metricName }}</span>
          <el-slider
            v-model="metricWeightList[index].weight"
            :min="0"
            :max="1"
            :step="0.01"
            @change="(val) => onMetricWeightChange(index, val)"
          />
          <span class="weight-value">{{ metricWeightList[index].weight.toFixed(2) }}</span>
        </div>
      </div>
      <el-empty v-else description="请先选择系统、大类和小类" style="margin-top: 20px;" />
      <template #footer>
        <el-button @click="metricWeightDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustMetricWeightDialogWidth" :visible="metricWeightDialogVisible" />
    </div>

    <!-- 系统信息弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="systemInfoDialogVisible" title="系统信息" :width="systemInfoDialogWidth">
      <el-tabs v-model="systemInfoActiveTab">
        <el-tab-pane label="基本信息" name="info">
          <el-descriptions :column="2" border v-if="systemInfoData.id">
            <el-descriptions-item label="系统编码">{{ systemInfoData.systemCode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="系统名称">{{ systemInfoData.systemName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="描述" :span="2">{{ systemInfoData.description || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="systemInfoData.status === 1 ? 'success' : 'info'">
                {{ systemInfoData.status === 1 ? '启用' : '禁用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="权重">{{ systemInfoData.weight || '-' }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="暂无数据" />
        </el-tab-pane>
        <el-tab-pane label="告警策略" name="strategy">
          <el-button type="primary" @click="openHealthStrategy('system', systemInfoData.id, systemInfoData.systemName)">设置分级告警与工单策略</el-button>

        </el-tab-pane>
      </el-tabs>
      <template #footer v-if="systemInfoActiveTab === 'info'">
        <el-button @click="systemInfoDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustSystemInfoDialogWidth" :visible="systemInfoDialogVisible" />
    </div>

    <!-- 指标大类信息弹窗 -->
    <div style="position: relative;">
      <el-dialog class="workspace-theme metric-config-dialog" v-model="categoryInfoDialogVisible" title="指标大类信息" :width="categoryInfoDialogWidth">
      <el-tabs v-model="categoryInfoActiveTab">
        <el-tab-pane label="基本信息" name="info">
          <el-descriptions :column="2" border v-if="categoryInfoData.id">
            <el-descriptions-item label="大类编码">{{ categoryInfoData.categoryCode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="大类名称">{{ categoryInfoData.categoryName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="描述" :span="2">{{ categoryInfoData.description || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="categoryInfoData.status === 1 ? 'success' : 'info'">
                {{ categoryInfoData.status === 1 ? '启用' : '禁用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="权重">{{ categoryInfoData.weight || '-' }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="暂无数据" />
        </el-tab-pane>
        <el-tab-pane label="告警策略" name="strategy">
          <el-button type="primary" @click="openHealthStrategy('category', categoryInfoData.id, categoryInfoData.categoryName)">设置分级告警与工单策略</el-button>

        </el-tab-pane>
      </el-tabs>
      <template #footer v-if="categoryInfoActiveTab === 'info'">
        <el-button @click="categoryInfoDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <DialogSizeControls :adjustWidth="adjustCategoryInfoDialogWidth" :visible="categoryInfoDialogVisible" />
    <!-- ===== 指标详情弹窗 =====
         设计说明：不用「字段名-值」逐行罗列，改为分区卡片（身份 / 定义 / 统计口径 /
         责任与属性），一屏看完，长文本单独成块便于阅读。只读展示，不在此处编辑。 -->
    <MetricDetailDialog v-model="metricDetailVisible" :detail-row="detailRow" @strategy="openStrategyFromDetail" />

    <!-- ===== 设置策略阈值弹窗 =====
         直接复用「告警管理 → 告警策略」的表单组件，不另写一套：
         后续给策略加字段（如告警等级）只改 components/alarm/StrategyEditorDialog.vue，
         两个页面同时生效。详见 doc/告警策略表单复用说明.md -->
    <StrategyEditorDialog
      v-model="strategyDialogVisible"
      :initial="strategySeed"
      :lock-target="true"
      :metric-context="strategyMetricContext"
      @saved="onStrategySaved"
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
  </div>
</template>

<script setup>
import MetricWeightReview from '@/components/metric/MetricWeightReview.vue'
import HealthRuleDrawer from '@/components/metric/HealthRuleDrawer.vue'
import AudienceDictDrawer from '@/components/metric/AudienceDictDrawer.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import MetricDetailDialog from '@/components/metric/MetricDetailDialog.vue'
import { EditableCell, EditableCellSelect } from '@/components/common/EditableCell'
import MetricOverviewCards from '@/components/metric/MetricOverviewCards.vue'
import MetricConfigurationSummary from '@/components/metric/MetricConfigurationSummary.vue'
import WorkspacePagination from '@/components/workspace/WorkspacePagination.vue'
import MetricFilters from '@/components/metric/MetricFilters.vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { metricApi, systemApi, weightApi, subcategoryApi, realtimeApi, configApi } from '../../api'
import { Document, Setting, TurnOff, ArrowDown, Grid, TrendCharts } from '@element-plus/icons-vue'
import { useDialogSize } from '../../utils/useDialogSize'
import { formatDateTime } from '../../utils'
import DialogSizeControls from '../../components/common/DialogSizeControls.vue'
// 策略表单公共组件：与「告警管理 → 告警策略」共用同一份实现，改一处两页同时生效
import { ruleSummary } from '../../utils/strategyRules'
import StrategyEditorDialog from '../../components/alarm/StrategyEditorDialog.vue'

const canReviewWeights = sessionStorage.getItem('isAdmin') === 'true'
const reviewWeights = ref(false)
// 健康度规则配置抽屉（白名单 + 人工/动态基线 + 试算 + 审计）
const healthRuleVisible = ref(false)
// 岗位字典维护抽屉（2026-11-18）：新增/改名/启停/删除，改完刷新同页「面向人员」下拉
const audienceDictVisible = ref(false)
/** 字典变更后：下拉选项和列表数据都要重取（改名会同步指标上的面向人员文本） */
const onAudienceDictSaved = () => {
  loadAudiences()
  loadData()
}

// 弹窗宽度控制
const { width: importDialogWidth, adjustWidth: adjustImportDialogWidth } = useDialogSize('metric-import', '600px')
const { width: systemDialogWidth, adjustWidth: adjustSystemDialogWidth } = useDialogSize('metric-system', '900px')
const { width: categoryDialogWidth, adjustWidth: adjustCategoryDialogWidth } = useDialogSize('metric-category', '900px')
const { width: backupDialogWidth, adjustWidth: adjustBackupDialogWidth } = useDialogSize('metric-backup', '700px')
const { width: categoryWeightDialogWidth, adjustWidth: adjustCategoryWeightDialogWidth } = useDialogSize('metric-category-weight', '900px')
const { width: subcategoryDialogWidth, adjustWidth: adjustSubcategoryDialogWidth } = useDialogSize('metric-subcategory', '900px')
const { width: subcategoryFormWidth, adjustWidth: adjustSubcategoryFormWidth } = useDialogSize('metric-subcategory-form', '400px')
const { width: metricWeightDialogWidth, adjustWidth: adjustMetricWeightDialogWidth } = useDialogSize('metric-metric-weight', '900px')
const { width: systemInfoDialogWidth, adjustWidth: adjustSystemInfoDialogWidth } = useDialogSize('metric-system-info', '800px')
const { width: categoryInfoDialogWidth, adjustWidth: adjustCategoryInfoDialogWidth } = useDialogSize('metric-category-info', '800px')

// 可编辑单元格组件
// 条件选项
// 指标单位受控词典（与后端 MetricImportService.UNIT_ALIAS 保持一致）
// 规范值用中文；导入时后端会兼容 ms/s/min/h 等写法并归一化
const unitOptions = ['毫秒', '秒', '分钟', '小时', '%', '个', '次', '种', '条']

// 岗位（面向人员）字典：2026-11-17 起「面向人员」改成岗位下拉，
// 一个指标只挂一个岗位；运营观测/专屏的人员维度按这个编码聚合。
const audienceOptions = ref([])
const loadAudiences = async () => {
  try {
    // 注意：请求拦截器返回的是 { code, message, data } 整体，字典在 data 里。
    // 2026-11-18 修正：原来直接把整个响应体当数组用，导致下拉选项恒为空。
    const res = await metricApi.audienceList()
    audienceOptions.value = res?.data || []
  } catch (error) {
    console.error('加载岗位字典失败:', error)
  }
}
/** 保存岗位：只提交 audienceCode，后端会用字典名称回填「面向人员」文本 */
const saveAudience = async (row, code) => {
  if (!code || code === row.audienceCode) return
  try {
    await metricApi.itemUpdate(row.id, { audienceCode: code })
    row.audienceCode = code
    row.responsiblePerson = audienceOptions.value.find(item => item.audienceCode === code)?.audienceName || row.responsiblePerson
    ElMessage.success('面向人员已更新')
  } catch (error) {
    console.error('保存面向人员失败:', error)
    ElMessage.error('保存失败')
    loadData()
  }
}

const tableData = ref([])
// 全量指标（不受分页/筛选影响），用于顶部「指标总数 / 告警 / 工单」概览统计
const allMetricItems = ref([])
// 告警/工单配置筛选：'' | 'alarm-yes' | 'alarm-no' | 'gongdan-yes' | 'gongdan-no'
const configFilter = ref('')
const importDialogVisible = ref(false)
const importLoading = ref(false)
const tableLoading = ref(false)
const uploadFile = ref(null)

// ===== 导入：模式 + 预演 + 执行（2026-10-28 两阶段改造）=====
const importMode = ref('incremental')        // incremental | full
const allowDeleteCategory = ref(false)       // 全量模式下允许删除整个大类的护栏开关
const importPreview = ref(null)              // 预演结果，null 表示还在第一步
const importPreviewLoading = ref(false)
const importExecuting = ref(false)
const confirmWordInput = ref('')
const importDbTotal = computed(() => pagination.value.total || 0)
const metricStats = ref({
  systemCount: 0,
  categoryCount: 0,
  itemCount: 0,
  enabledCount: 0
})

// 筛选参数
const queryParams = ref({
  systemId: null,
  categoryId: null,
  subCategoryName: null,
  metricType: null,
  status: null,
  metricName: '',
  metricCode: '',
  metricDefinition: ''
})

// 筛选选项
const systemOptions = ref([])
const categoryOptions = ref([])
const subCategoryOptions = ref([])
const metricTypeOptions = ref([])
const totalSubcategoryCount = ref(0)

// 选中行
const selectedRows = ref([])

// ============================================================================
// 操作列：详情 / 更多 → 设置策略阈值
// ============================================================================
const metricDetailVisible = ref(false)
const detailRow = ref({})

const openMetricDetail = (row) => {
  detailRow.value = { ...row }
  metricDetailVisible.value = true
}

// 更多菜单分发
const handleMetricMore = (cmd, row) => {
  if (cmd === 'strategy') openStrategyForMetric(row)
}

// 详情弹窗底部按钮 → 直接进策略设置
const openStrategyFromDetail = () => {
  metricDetailVisible.value = false
  openStrategyForMetric(detailRow.value)
}

// ---- 策略阈值弹窗（复用 components/alarm/StrategyEditorDialog.vue）----
// 注意：这里不实现任何策略表单逻辑，只负责查已有策略 + 预填 + 传单位/路径上下文。
const strategyDialogVisible = ref(false)
const strategySeed = ref(null)
const strategyMetricContext = ref(null)

const buildMetricContext = (row) => ({
  id: row.id,
  metricName: row.metricName,
  metricCode: row.metricCode,
  unit: row.unit,
  systemName: row.systemName,
  categoryName: row.categoryName,
  subCategoryName: row.subCategoryName
})

/**
 * 打开「设置策略阈值」：
 * 1) 用当前行指标预填，用户不用再去下拉列表里找指标
 * 2) 若该指标已有策略 → 带出已有阈值（编辑模式，保存即更新）
 * 3) 若有多条策略 → 提醒检查是否重复，默认编辑第一条（业务约定：一个指标只有一条指标值阈值）
 */
const openStrategyForMetric = async (row) => {
  if (!row || !row.id) return
  strategyMetricContext.value = buildMetricContext(row)
  strategySeed.value = null

  let list = []
  try {
    const res = await configApi.strategyByTarget({ targetType: 'metric', targetId: row.id })
    list = (res.data || [])
      // 只取指标值类策略；健康度策略作用在系统/大类/小类上，不在本弹窗处理
      .filter(x => !x.strategyType || x.strategyType === 'metric')
      .sort((a, b) => (a.id || 0) - (b.id || 0))
  } catch (e) {
    console.error('查询指标已有策略失败:', e)
    ElMessage.error('查询该指标已有策略失败，请稍后重试')
    return
  }

  if (list.length > 1) {
    const names = list.map(x => x.strategyName || `策略#${x.id}`).join('、')
    try {
      await ElMessageBox.alert(
        `该指标下存在 ${list.length} 条策略：${names}。请检查是否重复配置。\n\n当前显示第 1 条，保存时只会更新这一条，其余维持原样。`,
        '该指标存在多个策略',
        { type: 'warning', confirmButtonText: '继续编辑第一条' }
      )
    } catch (e) { /* 关闭弹窗也继续 */ }
  }

  // 优先带出「已启用且有阈值」的那条，与后端读取口径保持一致
  const picked = list.find(x => x.status !== 0 && x.alarmThreshold != null) || list[0]
  if (picked) {
    strategySeed.value = {
      ...picked,
      id: picked.id,
      strategyName: picked.strategyName,
      strategyType: picked.strategyType || 'metric',
      targetType: 'metric',
      targetId: row.id,
      targetName: row.metricName,
      status: picked.status ?? 1,
      alarmEnabled: picked.alarmEnabled ?? 1,
      alarmCondition: picked.alarmCondition || '>',
      alarmThreshold: picked.alarmThreshold ?? null,
      alarmConsecutiveTimes: picked.alarmConsecutiveTimes ?? 1,
      gongdanEnabled: picked.gongdanEnabled ?? 0,
      gongdanCondition: picked.gongdanCondition || '>',
      gongdanThreshold: picked.gongdanThreshold ?? null,
      gongdanConsecutiveTimes: picked.gongdanConsecutiveTimes ?? 1
    }
  }
  strategyDialogVisible.value = true
  // 保存动作交给公共组件，保存成功后组件 emit('saved') → onStrategySaved 刷新列表
}

const openHealthStrategy = async (targetType, targetId, targetName, categoryId = null) => {
  if (!targetId) { ElMessage.warning('缺少告警对象信息'); return }
  try {
    const res = await configApi.strategyByTarget({ targetType, targetId })
    const list = (res.data || []).filter(item => item.strategyType === 'health').sort((a, b) => a.id - b.id)
    const picked = list.find(item => item.status === 1) || list[0]
    strategySeed.value = picked ? { ...picked, categoryId } : {
      targetType, targetId, targetName, categoryId, strategyType: 'health',
      strategyName: `${targetName}-健康度-告警策略`, alarmEnabled: 0, gongdanEnabled: 0
    }
    strategyMetricContext.value = { metricName: targetName, unit: '分' }
    strategyDialogVisible.value = true
  } catch (error) { ElMessage.error('读取策略失败，请稍后重试') }
}

const onStrategySaved = () => {
  if (systemDialogVisible.value) loadSystemStrategyList(systemList.value)
  if (categoryDialogVisible.value) loadCategoryStrategyList(categoryList.value)
  if (subcategoryDialogVisible.value) loadSubCategoryStrategyList(subcategoryList.value)
  // 2026-10-28：策略保存后刷新列表。
  // 「告警概览」列和顶部「告警/工单」概览统计都是读 alarm_strategy 的，
  // 不刷新会继续显示旧数字（表现就是"配好了但页面数字没变"）。
  // loadData 会保留当前筛选条件与分页，不会把用户选好的条件重置。
  loadData()
}

// 列显示设置
const STORAGE_KEY = 'metric_table_preferences'
const columnOptions = ref([
  { prop: 'systemName', label: '系统' },
  { prop: 'categoryName', label: '指标大类' },
  { prop: 'subCategoryName', label: '指标小类' },
  { prop: 'metricName', label: '指标名称' },
  { prop: 'metricCode', label: '指标编码' },
  { prop: 'unit', label: '单位' },
  { prop: 'responsiblePerson', label: '面向人员' },
  { prop: 'metricDefinition', label: '指标定义' },
  { prop: 'statisticsCaliber', label: '统计口径' },
  // 以下 4 列来自导入模板（取数来源 / 数据对接模式 / 采集频率 / 采集状态），
  // 与 ITEM_HEADERS 一一对应，默认隐藏，需要时在「列设置」中勾选
  { prop: 'dataSource', label: '取数来源' },
  { prop: 'dataSyncMode', label: '数据对接模式' },
  { prop: 'collectFrequency', label: '采集频率' },
  { prop: 'collectStatus', label: '采集状态' },
  { prop: 'department', label: '部室' },
  { prop: 'reporter', label: '填报人' },
  // 告警概览：把告警/工单是否配置、阈值多少压成一列两行（默认显示）
  { prop: 'alarmOverview', label: '告警概览' },
  { prop: 'status', label: '状态' }
])

// 可在「列设置」中勾选展示，但默认隐藏的列（辅助/管理类信息，不影响核心指标浏览）
// 2026-11-17：面向人员（岗位）改为默认显示 —— 人员维度验收要看这一列的配置结果
const DEFAULT_HIDDEN_COLUMNS = [
  'statisticsCaliber', 'department', 'reporter',
  'dataSource', 'dataSyncMode', 'collectFrequency', 'collectStatus'
]

// 全部列的 prop（用于校验本地偏好里保存的列是否合法）
const allColumnProps = columnOptions.value.map(c => c.prop)

// 默认显示的列 = 全部列 - 默认隐藏列
const defaultVisibleColumns = allColumnProps.filter(prop => !DEFAULT_HIDDEN_COLUMNS.includes(prop))
const visibleColumns = ref([...defaultVisibleColumns])

// 检查列是否隐藏
const isColumnHidden = (prop) => {
  return !visibleColumns.value.includes(prop)
}

// 从localStorage加载偏好设置
// PREF_VERSION 变更时丢弃旧偏好，让新的默认列配置立即生效（避免老用户的本地缓存口径覆盖升级后的默认值）
// v4：面向人员（岗位）改为默认显示，列设置偏好重置一次
const PREF_VERSION = 4
const loadPreferences = () => {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const prefs = JSON.parse(saved)
      if (prefs.version !== PREF_VERSION) {
        // 旧版本偏好：不做迁移，直接采用当前默认列，并写入新版本号
        visibleColumns.value = [...defaultVisibleColumns]
        savePreferences()
        return
      }
      if (Array.isArray(prefs.visibleColumns)) {
        // 白名单用全部列，保证「列设置」里勾选的、默认隐藏的列也能被保存和恢复
        const savedColumns = prefs.visibleColumns.filter(prop => allColumnProps.includes(prop))
        visibleColumns.value = savedColumns.length > 0 ? savedColumns : [...defaultVisibleColumns]
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
      version: PREF_VERSION,
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

// 筛选处理
const handleFilterChange = () => {
  pagination.value.page = 1
  loadData()
}

const resetFilters = () => {
  queryParams.value = {
    systemId: null,
    categoryId: null,
    subCategoryName: null,
    metricType: null,
    status: null,
    metricName: '',
    metricCode: '',
    metricDefinition: ''
  }
  pagination.value.page = 1
  loadData()
}

// 点击启用指标 - 筛选启用状态的指标
const filterByEnabled = () => {
  queryParams.value.status = 1
  pagination.value.page = 1
  loadData()
  const el = document.getElementById('metricTableSection')
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

// 滚动到指标表格（同时清除状态筛选，查询全部）
const scrollToMetricTable = () => {
  queryParams.value.status = null
  pagination.value.page = 1
  loadData()
  const el = document.getElementById('metricTableSection')
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

// 选中行变化
const handleSelectionChange = (selection) => {
  selectedRows.value = selection
}

// 批量删除
const handleBatchDelete = () => {
  if (!selectedRows.value.length) return
  ElMessageBox.confirm(`确定删除选中的 ${selectedRows.value.length} 项指标吗?`, '提示', {
    type: 'warning'
  }).then(async () => {
    try {
      for (const row of selectedRows.value) {
        await metricApi.itemDelete(row.id)
      }
      ElMessage.success('删除成功')
      selectedRows.value = []
      loadData()
    } catch (error) {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }).catch(() => {})
}

/**
 * 批量启用 / 停用（2026-10-28 新增）。
 * 停用 = 总开关：所选指标一起退出算分、告警/工单、实时指标与运营报表，历史数据保留。
 * 后端一次事务处理，返回实际发生变化的条数（已是目标状态的会被跳过）。
 */
const handleBatchStatus = async (status) => {
  const rows = selectedRows.value
  if (!rows.length) return

  const turningOff = status === 0
  // 只统计真正需要变更的，避免提示"将停用 5 项"但其中 3 项本来就停着
  const pending = rows.filter(r => ((r.status == null ? 1 : r.status) !== status))
  if (!pending.length) {
    ElMessage.info(turningOff ? '选中的指标都已是停用状态' : '选中的指标都已是启用状态')
    return
  }

  const action = turningOff ? '停用' : '启用'
  const tip = turningOff
    ? `将停用 ${pending.length} 项指标。\n\n停用后这些指标会：\n· 停止算分（不再产生新的健康度）\n· 停止告警与工单（相关告警策略一并失效）\n· 从实时指标、运营报表中隐藏\n\n历史数据不会删除，重新启用即可恢复。`
    : `将启用 ${pending.length} 项指标。\n\n启用后这些指标会恢复参与算分、告警与实时报表。`

  try {
    await ElMessageBox.confirm(tip, `确认批量${action}？`, {
      type: turningOff ? 'warning' : 'info',
      confirmButtonText: `确认${action}`,
      cancelButtonText: '取消',
      customStyle: { whiteSpace: 'pre-line', lineHeight: '1.8' }
    })
  } catch (e) {
    return
  }

  try {
    const res = await metricApi.itemBatchStatus({
      ids: pending.map(r => r.id),
      status
    })
    const updated = res.data?.updated ?? pending.length
    ElMessage.success(`已${action} ${updated} 项指标`)
    selectedRows.value = []
    loadData()
  } catch (error) {
    console.error(`批量${action}失败:`, error)
    ElMessage.error(`批量${action}失败`)
  }
}

// 系统稽核指标同步
const handleSyncAudit = async () => {
  try {
    const res = await metricApi.syncAuditMetrics()
    const data = res.data || res
    const error = data.error
    if (error) {
      ElMessage.error('同步失败: ' + error)
      return
    }
    const addedCount = data.addedCount || 0
    if (addedCount > 0) {
      ElMessage.success(`同步成功！新增 ${addedCount} 个指标`)
      loadData()
    } else {
      ElMessage.info('同步完成，暂无新增指标')
    }
  } catch (error) {
    console.error('同步失败:', error)
    ElMessage.error('同步失败: ' + (error.message || '未知错误'))
  }
}

// 分页
const pagination = ref({
  page: 1,
  size: 10,
  total: 0
})

// 系统信息弹窗
const systemInfoDialogVisible = ref(false)
const systemInfoData = ref({})
const systemInfoActiveTab = ref('info')
const systemStrategyLoading = ref(false)
const systemStrategySaving = ref(false)
const systemStrategyForm = ref({
  strategyName: '',
  alarmEnabled: 0,
  alarmCondition: '<',
  alarmThreshold: null,
  alarmConsecutiveTimes: 1,
  gongdanEnabled: 0,
  gongdanCondition: '<',
  gongdanThreshold: null,
  gongdanConsecutiveTimes: 1
})

// 指标大类信息弹窗
const categoryInfoDialogVisible = ref(false)
const categoryInfoData = ref({})
const categoryInfoActiveTab = ref('info')
const categoryStrategyLoading = ref(false)
const categoryStrategySaving = ref(false)
const categoryStrategyForm = ref({
  strategyName: '',
  alarmEnabled: 0,
  alarmCondition: '<',
  alarmThreshold: null,
  alarmConsecutiveTimes: 1,
  gongdanEnabled: 0,
  gongdanCondition: '<',
  gongdanThreshold: null,
  gongdanConsecutiveTimes: 1
})

// 系统列表相关
const systemDialogVisible = ref(false)
const systemDialogActiveTab = ref('list')
const systemList = ref([])
const systemOriginalData = ref({})
const selectedSystems = ref([])
const systemStrategyList = ref([])

// 指标大类列表相关
const categoryDialogVisible = ref(false)
const categoryList = ref([])
const categoryOriginalData = ref({})
const selectedCategories = ref([])
const selectedCategories2 = ref([])

// 备份记录相关
const backupDialogVisible = ref(false)
const backupList = ref([])

// 指标大类权重配置相关
const categoryDialogActiveTab = ref('list')
const categoryWeightList = ref([])
const categoryWeightDialogVisible = ref(false)
const categoryWeightSystemId = ref(null)
const categoryLastSaveTime = ref('')

// 大类配置弹窗中的策略表单
const dialogStrategyForm = ref({
  strategyName: '',
  strategyType: 'health',
  status: 1,
  configs: [
    { enabled: 1, condition: '>', threshold: null, consecutiveTimes: 1, generateType: 'alarm' },
    { enabled: 0, condition: '>', threshold: null, consecutiveTimes: 1, generateType: 'gongdan' }
  ]
})

// 大类策略列表（表格形式）
const categoryStrategyList = ref([])

// 指标小类权重配置相关
const subcategoryDialogVisible = ref(false)
const subcategoryActiveTab = ref('list')
const subcategoryList = ref([])
const subcategoryWeightList = ref([])
const subCategoryStrategyList = ref([])
const subcategorySystemId = ref(null)
const subcategoryCategoryId = ref(null)
const subcategoryFormVisible = ref(false)
const subcategoryLastSaveTime = ref('')
const selectedSubcategories = ref([])
const editingSubcategory = ref({
  id: null,
  categoryId: null,
  subCategoryCode: '',
  subCategoryName: '',
  description: '',
  status: 'enabled'
})

// 具体指标权重配置相关
const metricWeightDialogVisible = ref(false)
const metricWeightSystemId = ref(null)
const metricWeightCategoryId = ref(null)
const metricWeightSubCategoryName = ref('')
const metricLastSaveTime = ref('')
const metricWeightList = ref([])

const subcategoryCount = computed(() => {
  return totalSubcategoryCount.value
})

const tableHeight = computed(() => {
  return window.innerHeight - 200
})

// 加载统计数据
const loadMetricStats = async () => {
  try {
    const [systemRes, metricRes, categoryRes] = await Promise.all([
      systemApi.listAll(),
      metricApi.statistics(),
      metricApi.categoryListAll()
    ])
    const systems = systemRes.data || []
    const categories = metricRes.data?.categoryCount || 0
    const items = metricRes.data?.itemCount || 0
    const enabledCount = metricRes.data?.enabledCount || 0

    metricStats.value = {
      systemCount: systems.length,
      categoryCount: categories,
      itemCount: items,
      enabledCount: enabledCount
    }

    // 加载小类总数
    let totalSub = 0
    const allCategories = categoryRes.data || []
    for (const cat of allCategories) {
      try {
        const subRes = await subcategoryApi.list(cat.id)
        totalSub += (subRes.data || []).length
      } catch (e) {
        // ignore
      }
    }
    totalSubcategoryCount.value = totalSub
  } catch (error) {
    console.error('加载统计数据失败:', error)
  }
}

// 加载数据（分页）
const loadData = async () => {
  tableLoading.value = true
  try {
    // 加载所有系统（用于下拉选项）
    const systemRes = await systemApi.listAll()
    const systems = systemRes.data || []
    systemOptions.value = systems

    // 加载所有指标大类（用于下拉选项）
    const categoryRes = await metricApi.categoryListAll()
    const categories = categoryRes.data || []
    categoryOptions.value = categories

    // 加载所有指标（用于提取小类和类型选项，同时作为概览统计的数据源）
    const allItemRes = await metricApi.itemListAll()
    const allItems = allItemRes.data || []
    allMetricItems.value = allItems
    // 提取唯一的小类
    const subCategories = [...new Set(allItems.map(i => i.subCategoryName).filter(Boolean))]
    subCategoryOptions.value = subCategories
    // 提取唯一的类型
    const metricTypes = [...new Set(allItems.map(i => i.metricType).filter(Boolean))]
    metricTypeOptions.value = metricTypes

    // 加载分页指标（带筛选参数）
    const params = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
      ...queryParams.value
    }
    // 「告警 / 工单」概览卡片点击后的筛选条件（后端按策略是否存在且阈值有值过滤）
    if (configFilter.value) {
      const [kind, mode] = configFilter.value.split('-')
      params[kind === 'alarm' ? 'alarmConfigured' : 'gongdanConfigured'] = mode
    }
    // 移除空值
    Object.keys(params).forEach(key => {
      if (params[key] === null || params[key] === '') delete params[key]
    })
    const itemRes = await metricApi.itemList(params)
    const pageData = itemRes.data || { content: [], totalElements: 0 }
    const items = pageData.content || []

    pagination.value.total = pageData.totalElements || 0

    // 指标只管自身属性；告警/工单阈值不在本页展示，统一在「告警管理 → 告警策略」维护
    tableData.value = items.map(item => {
      const category = categories.find(c => c.id === item.categoryId) || {}
      const system = systems.find(s => s.id === category.systemId) || {}

      return {
        ...item,
        systemId: system.id,
        systemName: system.systemName,
        systemCode: system.systemCode,
        categoryId: category.id,
        categoryName: category.categoryName,
        categoryCode: category.categoryCode
      }
    })
  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    tableLoading.value = false
  }
}

// ===== 告警 / 工单配置概览（2026-10-28 新增）=====
// 统计口径：只算「已启用」指标（status 为 0 视为停用，其余按启用处理，与后端 countEnabledItems 一致）；
// 「已配置」= 该指标存在启用中的策略且对应阈值有值，由后端随列表返回 alarmThreshold / gongdanThreshold。
const configSummary = computed(() => {
  const enabled = allMetricItems.value.filter(i => Number(i.status) !== 0)
  const alarmYes = enabled.filter(i => i.alarmThreshold != null).length
  const gongdanYes = enabled.filter(i => i.gongdanThreshold != null).length
  return {
    enabledTotal: enabled.length,
    alarmYes,
    alarmNo: enabled.length - alarmYes,
    gongdanYes,
    gongdanNo: enabled.length - gongdanYes
  }
})

// 点同一个条件再次点击 = 取消筛选
const toggleConfigFilter = (key) => {
  configFilter.value = configFilter.value === key ? '' : key
  pagination.value.page = 1
  loadData()
}

const clearConfigFilter = () => {
  if (!configFilter.value) return
  configFilter.value = ''
  pagination.value.page = 1
  loadData()
}

// 告警概览列的文案：已配置显示「> 5 秒」，未配置显示「未配置」
const thresholdText = (row, kind) => ruleSummary(row, kind)

// 编辑单元格
const editCell = (row, field) => {
  // 可在这里添加编辑逻辑，目前通过EditableCell组件处理
}

// 保存单元格
const saveCell = async (row, field, value) => {
  try {
    // 指标属性统一保存到 metric_item（阈值不在此处，阈值只在告警策略中维护）
    await metricApi.itemUpdate(row.id, { [field]: value })
    row[field] = value
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
    loadData()
  }
}

// 保存状态
/**
 * 切换指标启用/停用状态。
 *
 * 2026-10-28 起「停用」是真正的总开关：
 * 停用后该指标退出算分、告警/工单、实时指标刷新与运营报表，历史数据保留。
 * 所以这里必须二次确认——开关是直接改 row.status 的，用户很容易误触。
 */
const saveStatus = async (row) => {
  const targetStatus = row.status
  const turningOff = targetStatus === 0
  const tip = turningOff
    ? `停用后，指标「${row.metricName}」将：\n· 停止算分（不再产生新的健康度）\n· 停止告警与工单（该指标的告警策略一并失效）\n· 从实时指标、运营报表中隐藏\n\n历史数据不会删除，重新启用即可恢复。`
    : `启用后，指标「${row.metricName}」将恢复参与算分、告警与实时报表。`

  try {
    await ElMessageBox.confirm(tip, turningOff ? '确认停用该指标？' : '确认启用该指标？', {
      type: turningOff ? 'warning' : 'info',
      confirmButtonText: turningOff ? '确认停用' : '确认启用',
      cancelButtonText: '取消',
      customClass: 'status-confirm-box',
      // 纯文本 + white-space:pre-line，既能换行又不解析 HTML（指标名里可能有 < >）
      customStyle: { whiteSpace: 'pre-line', lineHeight: '1.8' }
    })
  } catch (e) {
    // 用户取消：把开关拨回去，不提交
    row.status = turningOff ? 1 : 0
    return
  }

  try {
    await metricApi.itemUpdate(row.id, { status: targetStatus })
    ElMessage.success(turningOff ? '已停用，该指标不再参与算分与告警' : '已启用')
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
    // 提交失败也要把开关恢复，避免界面与库不一致
    row.status = turningOff ? 1 : 0
    loadData()
  }
}

// 新增
const handleAdd = () => {
  ElMessage.info('请使用导入功能批量添加指标，或在系统中新增')
}

// 删除
const handleDelete = (row) => {
  ElMessageBox.confirm('确定删除该指标吗?', '提示', {
    type: 'warning'
  }).then(async () => {
    try {
      await metricApi.itemDelete(row.id)
      ElMessage.success('删除成功')
      loadData()
    } catch (error) {
      console.error('删除失败:', error)
    }
  }).catch(() => {})
}

// 显示系统信息弹窗
const showSystemInfo = async (row) => {
  try {
    const res = await systemApi.get(row.systemId)
    systemInfoData.value = res.data || {}
    systemInfoActiveTab.value = 'info'
    // 加载系统策略
    systemStrategyLoading.value = true
    try {
      const strategyRes = await configApi.strategyByTarget({ targetType: 'system', targetId: row.systemId })
      const strategies = strategyRes.data || []
      if (strategies.length > 0) {
        const s = strategies[0]
        systemStrategyForm.value = {
          strategyName: s.strategyName || '',
          alarmEnabled: s.alarmEnabled || 0,
          alarmCondition: s.alarmCondition || '>',
          alarmThreshold: s.alarmThreshold ?? null,
          alarmConsecutiveTimes: s.alarmConsecutiveTimes || 1,
          gongdanEnabled: s.gongdanEnabled || 0,
          gongdanCondition: s.gongdanCondition || '>',
          gongdanThreshold: s.gongdanThreshold ?? null,
          gongdanConsecutiveTimes: s.gongdanConsecutiveTimes || 1
        }
      } else {
        systemStrategyForm.value = {
          strategyName: row.systemName + ' 健康度策略',
          alarmEnabled: 0,
          alarmCondition: '<',
          alarmThreshold: null,
          alarmConsecutiveTimes: 1,
          gongdanEnabled: 0,
          gongdanCondition: '<',
          gongdanThreshold: null,
          gongdanConsecutiveTimes: 1
        }
      }
    } catch (e) {
      console.error('加载系统策略失败:', e)
    } finally {
      systemStrategyLoading.value = false
    }
    systemInfoDialogVisible.value = true
  } catch (error) {
    console.error('获取系统信息失败:', error)
  }
}

// 显示指标大类信息弹窗
const showCategoryInfo = async (row) => {
  try {
    const res = await metricApi.categoryGet(row.categoryId)
    const categoryData = res.data || {}

    // 从权重配置获取该大类在当前系统下的权重
    if (categoryData.systemId) {
      const weightRes = await weightApi.getConfig(categoryData.systemId)
      const catWeights = weightRes.data?.categoryWeights || {}
      categoryData.weight = catWeights[categoryData.id] ? parseFloat(catWeights[categoryData.id]) : '-'
    }

    categoryInfoData.value = categoryData
    categoryInfoActiveTab.value = 'info'
    // 加载大类策略
    categoryStrategyLoading.value = true
    try {
      const strategyRes = await configApi.strategyByTarget({ targetType: 'category', targetId: row.categoryId })
      const strategies = strategyRes.data || []
      if (strategies.length > 0) {
        const s = strategies[0]
        categoryStrategyForm.value = {
          strategyName: s.strategyName || '',
          alarmEnabled: s.alarmEnabled || 0,
          alarmCondition: s.alarmCondition || '>',
          alarmThreshold: s.alarmThreshold ?? null,
          alarmConsecutiveTimes: s.alarmConsecutiveTimes || 1,
          gongdanEnabled: s.gongdanEnabled || 0,
          gongdanCondition: s.gongdanCondition || '>',
          gongdanThreshold: s.gongdanThreshold ?? null,
          gongdanConsecutiveTimes: s.gongdanConsecutiveTimes || 1
        }
      } else {
        categoryStrategyForm.value = {
          strategyName: categoryData.categoryName + ' 健康度策略',
          alarmEnabled: 0,
          alarmCondition: '<',
          alarmThreshold: null,
          alarmConsecutiveTimes: 1,
          gongdanEnabled: 0,
          gongdanCondition: '<',
          gongdanThreshold: null,
          gongdanConsecutiveTimes: 1
        }
      }
    } catch (e) {
      console.error('加载大类策略失败:', e)
    } finally {
      categoryStrategyLoading.value = false
    }
    categoryInfoDialogVisible.value = true
  } catch (error) {
    console.error('获取指标大类信息失败:', error)
  }
}

// 保存系统健康度策略
const saveSystemStrategy = async () => {
  if (!systemInfoData.value.id) {
    ElMessage.warning('系统信息不存在')
    return
  }
  systemStrategySaving.value = true
  try {
    // 查询现有策略
    const strategyRes = await configApi.strategyByTarget({ targetType: 'system', targetId: systemInfoData.value.id })
    const existingStrategies = strategyRes.data || []
    const existingId = existingStrategies.length > 0 ? existingStrategies[0].id : null

    const submitData = {
      strategyName: systemStrategyForm.value.strategyName,
      strategyType: 'health',
      targetType: 'system',
      targetId: systemInfoData.value.id,
      targetName: systemInfoData.value.systemName,
      alarmEnabled: systemStrategyForm.value.alarmEnabled,
      alarmCondition: systemStrategyForm.value.alarmCondition,
      alarmThreshold: systemStrategyForm.value.alarmThreshold,
      alarmConsecutiveTimes: systemStrategyForm.value.alarmConsecutiveTimes,
      gongdanEnabled: systemStrategyForm.value.gongdanEnabled,
      gongdanCondition: systemStrategyForm.value.gongdanCondition,
      gongdanThreshold: systemStrategyForm.value.gongdanThreshold,
      gongdanConsecutiveTimes: systemStrategyForm.value.gongdanConsecutiveTimes,
      status: 1
    }

    if (existingId) {
      await configApi.strategyUpdate(existingId, submitData)
    } else {
      await configApi.strategyCreate(submitData)
    }
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存系统策略失败:', error)
    ElMessage.error('保存失败')
  } finally {
    systemStrategySaving.value = false
  }
}

// 保存大类健康度策略
const saveCategoryStrategy = async () => {
  if (!categoryInfoData.value.id) {
    ElMessage.warning('大类信息不存在')
    return
  }
  categoryStrategySaving.value = true
  try {
    // 查询现有策略
    const strategyRes = await configApi.strategyByTarget({ targetType: 'category', targetId: categoryInfoData.value.id })
    const existingStrategies = strategyRes.data || []
    const existingId = existingStrategies.length > 0 ? existingStrategies[0].id : null

    const submitData = {
      strategyName: categoryStrategyForm.value.strategyName,
      strategyType: 'health',
      targetType: 'category',
      targetId: categoryInfoData.value.id,
      targetName: categoryInfoData.value.categoryName,
      alarmEnabled: categoryStrategyForm.value.alarmEnabled,
      alarmCondition: categoryStrategyForm.value.alarmCondition,
      alarmThreshold: categoryStrategyForm.value.alarmThreshold,
      alarmConsecutiveTimes: categoryStrategyForm.value.alarmConsecutiveTimes,
      gongdanEnabled: categoryStrategyForm.value.gongdanEnabled,
      gongdanCondition: categoryStrategyForm.value.gongdanCondition,
      gongdanThreshold: categoryStrategyForm.value.gongdanThreshold,
      gongdanConsecutiveTimes: categoryStrategyForm.value.gongdanConsecutiveTimes,
      status: 1
    }

    if (existingId) {
      await configApi.strategyUpdate(existingId, submitData)
    } else {
      await configApi.strategyCreate(submitData)
    }
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存大类策略失败:', error)
    ElMessage.error('保存失败')
  } finally {
    categoryStrategySaving.value = false
  }
}

// 保存大类配置弹窗中的策略
const saveDialogStrategy = async () => {
  try {
    // 获取当前选中的大类ID
    const selectedCategory = categoryList.value.find(c => c._selected)
    if (!selectedCategory || !selectedCategory.id) {
      ElMessage.warning('请先在列表中选择一个大类')
      return
    }
    // 从 configs 提取告警和工单配置
    const alarmConfig = dialogStrategyForm.value.configs.find(c => c.generateType === 'alarm')
    const gongdanConfig = dialogStrategyForm.value.configs.find(c => c.generateType === 'gongdan')
    const submitData = {
      strategyName: dialogStrategyForm.value.strategyName || selectedCategory.categoryName + ' 健康度策略',
      strategyType: dialogStrategyForm.value.strategyType,
      targetType: 'category',
      targetId: selectedCategory.id,
      targetName: selectedCategory.categoryName,
      status: dialogStrategyForm.value.status,
      alarmEnabled: alarmConfig ? alarmConfig.enabled : 0,
      alarmCondition: alarmConfig ? alarmConfig.condition : '>',
      alarmThreshold: alarmConfig ? alarmConfig.threshold : null,
      alarmConsecutiveTimes: alarmConfig ? alarmConfig.consecutiveTimes : 1,
      gongdanEnabled: gongdanConfig ? gongdanConfig.enabled : 0,
      gongdanCondition: gongdanConfig ? gongdanConfig.condition : '>',
      gongdanThreshold: gongdanConfig ? gongdanConfig.threshold : null,
      gongdanConsecutiveTimes: gongdanConfig ? gongdanConfig.consecutiveTimes : 1
    }
    // 查询现有策略
    const strategyRes = await configApi.strategyByTarget({ targetType: 'category', targetId: selectedCategory.id })
    const existingStrategies = strategyRes.data || []
    if (existingStrategies.length > 0) {
      await configApi.strategyUpdate(existingStrategies[0].id, submitData)
    } else {
      await configApi.strategyCreate(submitData)
    }
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存策略失败:', error)
    ElMessage.error('保存失败')
  }
}

// 新增弹窗中的策略配置
const addDialogStrategyConfig = () => {
  dialogStrategyForm.value.configs.push({
    enabled: 0,
    condition: '>',
    threshold: null,
    consecutiveTimes: 1,
    generateType: 'alarm'
  })
}

// 配置导出 - 导出多sheet Excel
const exportConfig = () => {
  window.open('/metric-code/api/metric/export', '_blank')
}

// 下载空白模板（3 个 sheet，表头与导出完全一致）
const downloadTemplate = () => {
  window.open('/metric-code/api/metric/template', '_blank')
}

// 打开导入弹窗
const handleImport = () => {
  resetImportState()
  importDialogVisible.value = true
}

const handleFileChange = (file) => {
  uploadFile.value = file.raw
  // 换了文件，之前算出的预演结果作废
  importPreview.value = null
}

const resetImportState = () => {
  uploadFile.value = null
  importPreview.value = null
  confirmWordInput.value = ''
  allowDeleteCategory.value = false
  importMode.value = 'incremental'
}

// 全量模板 = 导出当前库全部数据（在它基础上改，再全量导入）
const downloadFullTemplate = () => {
  window.open('/metric-code/api/metric/export', '_blank')
}

/**
 * 第一步：校验 + 预演。只让后端算差异，不落库。
 * 返回新增/更新/删除/无变化各多少、要删的指标按大类分组、冲突清单。
 */
const handlePreview = async () => {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择要导入的文件')
    return
  }
  importPreviewLoading.value = true
  try {
    const formData = new FormData()
    formData.append('file', uploadFile.value)
    const res = await metricApi.importPreview(formData, {
      mode: importMode.value,
      allowDeleteCategory: allowDeleteCategory.value
    })
    if (res.code === 200) {
      importPreview.value = res.data
      confirmWordInput.value = ''
      if (!res.data.canExecute) {
        ElMessage.warning(`校验发现 ${res.data.conflicts.length} 处问题，修正文件后才能导入`)
      } else if (res.data.summary.metricDelete > 0) {
        ElMessage.warning(`本次将删除 ${res.data.summary.metricDelete} 个指标，请确认明细无误`)
      }
    } else {
      ElMessage.error(res.message || '校验失败')
    }
  } catch (e) {
    console.error('预演失败:', e)
    ElMessage.error('校验失败: ' + (e.message || '未知错误'))
  } finally {
    importPreviewLoading.value = false
  }
}

/**
 * 第二步：按预演结果执行。
 * 执行期间后端会重新校验 token 与确认词，前端这里的校验只是提前拦一道。
 */
const handleExecute = async () => {
  const pv = importPreview.value
  if (!pv) return
  if (!pv.canExecute) {
    ElMessage.warning('存在未处理的冲突，不能导入')
    return
  }
  if (pv.needConfirmWord && (confirmWordInput.value || '').trim() !== pv.confirmWord) {
    ElMessage.warning(`请在输入框中填写「${pv.confirmWord}」以确认`)
    return
  }
  importExecuting.value = true
  try {
    const res = await metricApi.importExecute({
      token: pv.token,
      confirmWord: confirmWordInput.value
    })
    if (res.code === 200) {
      const d = res.data || {}
      const parts = []
      if (d.metricChanged) parts.push(`写入/更新 ${d.metricChanged} 条`)
      if (d.metricDeleted) parts.push(`删除 ${d.metricDeleted} 条`)
      if (d.categoryChanged) parts.push(`大类 ${d.categoryChanged} 个`)
      if (d.systemChanged) parts.push(`系统 ${d.systemChanged} 个`)
      ElMessage.success('导入完成：' + (parts.length ? parts.join('，') : '无数据变化'))
      importDialogVisible.value = false
      loadMetricStats()
      loadData()
    } else {
      ElMessage.error(res.message || '导入失败')
    }
  } catch (e) {
    console.error('导入失败:', e)
    ElMessage.error('导入失败: ' + (e.message || '未知错误'))
  } finally {
    importExecuting.value = false
  }
}

// 打开系统列表弹窗
const openSystemDialog = async () => {
  try {
    const res = await systemApi.listAll()
    systemList.value = res.data || []
    systemDialogVisible.value = true
    // 切换到策略tab时加载系统策略列表
    if (systemDialogActiveTab.value === 'strategy') {
      loadSystemStrategyList(systemList.value)
    }
  } catch (error) {
    console.error('加载系统列表失败:', error)
  }
}

// 监听系统弹窗tab切换
watch(systemDialogActiveTab, (newTab) => {
  if (newTab === 'strategy' && systemList.value.length > 0) {
    loadSystemStrategyList(systemList.value)
  }
})

// 开始编辑系统（双击触发）
const startEditSystem = (row) => {
  row._editing = true
}

// 保存系统
const saveSystem = async (row) => {
  try {
    if (row.id) {
      await systemApi.update(row.id, row)
    } else {
      await systemApi.create(row)
    }
    ElMessage.success('保存成功')
    openSystemDialog()
    loadMetricStats()
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
  }
}

// 删除系统
const deleteSystem = (row) => {
  ElMessageBox.confirm('确定删除该系统吗?', '提示', { type: 'warning' }).then(async () => {
    try {
      await systemApi.delete(row.id)
      ElMessage.success('删除成功')
      openSystemDialog()
      loadMetricStats()
    } catch (error) {
      console.error('删除失败:', error)
    }
  })
}

// 系统列表选择
const handleSystemSelection = (selection) => {
  selectedSystems.value = selection
}

// 批量删除系统
const batchDeleteSystems = async () => {
  if (!selectedSystems.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedSystems.value.length} 个系统吗?`, '提示', { type: 'warning' })
    for (const sys of selectedSystems.value) {
      await systemApi.delete(sys.id)
    }
    ElMessage.success('删除成功')
    selectedSystems.value = []
    openSystemDialog()
    loadMetricStats()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }
}

// 新增系统
const addSystem = () => {
  systemList.value.push({
    id: null,
    systemName: '',
    systemCode: '',
    description: '',
    status: 1,
    _editing: true
  })
}

// 打开指标大类列表弹窗
const openCategoryDialog = async () => {
  try {
    // 加载系统列表用于显示
    const systemRes = await systemApi.listAll()
    const systems = systemRes.data || []

    // 加载指标大类
    const categoryRes = await metricApi.categoryListAll()
    const categories = categoryRes.data || []

    // 加载所有小类用于统计
    const allSubcategories = []
    for (const cat of categories) {
      try {
        const subRes = await subcategoryApi.list(cat.id)
        allSubcategories.push(...(subRes.data || []).map(s => ({ ...s, categoryId: cat.id })))
      } catch (e) {
        // ignore
      }
    }

    // 统计每个大类的小类数量
    const subCountMap = {}
    allSubcategories.forEach(s => {
      if (!s.categoryId) return
      subCountMap[s.categoryId] = (subCountMap[s.categoryId] || 0) + 1
    })

    // 合并系统名称和小类数量
    categoryList.value = categories.map(c => ({
      ...c,
      systemName: systems.find(s => s.id === c.systemId)?.systemName || '',
      subcategoryCount: subCountMap[c.id] || 0
    }))

    categoryDialogVisible.value = true
  } catch (error) {
    console.error('加载指标大类列表失败:', error)
  }
}

// 开始编辑指标大类（双击触发）
const startEditCategory = (row) => {
  row._editing = true
}

// 保存指标大类
const saveCategory = async (row) => {
  try {
    if (row.id) {
      await metricApi.categoryUpdate(row.id, row)
    } else {
      await metricApi.categoryCreate(row)
    }
    ElMessage.success('保存成功')
    // 重新加载大类列表和权重
    await loadCategoryWeightData()
    const categoryRes = await metricApi.categoryListAll()
    const categories = categoryRes.data || []
    categoryList.value = categories.map(c => ({
      ...c,
      systemName: systemList.value.find(s => s.id === c.systemId)?.systemName || ''
    }))
    loadMetricStats()
  } catch (error) {
    console.error('保存失败:', error)
    ElMessage.error('保存失败')
  }
}

// 删除指标大类
const deleteCategory = (row) => {
  ElMessageBox.confirm('确定删除该指标大类吗?', '提示', { type: 'warning' }).then(async () => {
    try {
      await metricApi.categoryDelete(row.id)
      ElMessage.success('删除成功')
      // 重新加载大类列表和权重
      await loadCategoryWeightData()
      const categoryRes = await metricApi.categoryListAll()
      const categories = categoryRes.data || []
      categoryList.value = categories.map(c => ({
        ...c,
        systemName: systemList.value.find(s => s.id === c.systemId)?.systemName || ''
      }))
      loadMetricStats()
    } catch (error) {
      console.error('删除失败:', error)
    }
  }).catch(() => {})
}

// 大类列表选择（权重配置弹窗用）
const handleCategorySelection = (selection) => {
  selectedCategories.value = selection
}

// 大类列表选择（基础大类弹窗用）
const handleCategorySelection2 = (selection) => {
  selectedCategories2.value = selection
}

// 批量删除指标大类
const batchDeleteCategories = async () => {
  const toDelete = selectedCategories.value.length > 0 ? selectedCategories.value : selectedCategories2.value
  if (!toDelete.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${toDelete.length} 个指标大类吗?`, '提示', { type: 'warning' })
    for (const cat of toDelete) {
      await metricApi.categoryDelete(cat.id)
    }
    ElMessage.success('删除成功')
    selectedCategories.value = []
    selectedCategories2.value = []
    // 重新加载大类列表和权重
    await loadCategoryWeightData()
    const categoryRes = await metricApi.categoryListAll()
    const categories = categoryRes.data || []
    categoryList.value = categories.map(c => ({
      ...c,
      systemName: systemList.value.find(s => s.id === c.systemId)?.systemName || ''
    }))
    loadMetricStats()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }
}

// 新增指标大类
const addCategory = () => {
  categoryList.value.push({
    id: null,
    systemId: null,
    systemName: '',
    categoryName: '',
    categoryCode: '',
    description: '',
    status: 1,
    _editing: true
  })
}

// 打开备份记录弹窗
const openBackupDialog = async () => {
  try {
    const response = await fetch('/metric-code/api/metric/backup-list')
    const res = await response.json()
    backupList.value = res.data || []
    backupDialogVisible.value = true
  } catch (error) {
    console.error('加载备份记录失败:', error)
  }
}

// 下载备份
const downloadBackup = (row) => {
  window.open(`/metric-code/api/metric/backup-download/${row.id}`, '_blank')
}

// 恢复备份
const restoreBackup = (row) => {
  ElMessageBox.confirm('确定恢复此备份吗? 当前数据将被覆盖。', '提示', { type: 'warning' }).then(async () => {
    try {
      const response = await fetch(`/metric-code/api/metric/backup-restore/${row.id}`, { method: 'POST' })
      const res = await response.json()
      if (res.code === 200) {
        ElMessage.success('恢复成功')
        backupDialogVisible.value = false
        loadMetricStats()
        loadData()
      } else {
        ElMessage.error(res.message || '恢复失败')
      }
    } catch (error) {
      console.error('恢复失败:', error)
      ElMessage.error('恢复失败')
    }
  }).catch(() => {})
}

// ==================== 权重配置相关函数 ====================

// 计算权重总和
const sumCategoryWeights = computed(() => {
  return categoryWeightList.value.reduce((sum, w) => sum + w.weight, 0)
})

const sumSubcategoryWeights = computed(() => {
  return subcategoryWeightList.value.reduce((sum, w) => sum + w.weight, 0)
})

const sumMetricWeights = computed(() => {
  return metricWeightList.value.reduce((sum, w) => sum + w.weight, 0)
})

// 打开指标大类配置弹窗（带权重Tab）
const openCategoryWeightDialog = async () => {
  try {
    categoryDialogActiveTab.value = 'list'
    categoryLastSaveTime.value = ''
    const systemRes = await systemApi.listAll()
    systemList.value = systemRes.data || []
    // 默认选择第一个系统
    if (systemList.value.length > 0) {
      categoryWeightSystemId.value = systemList.value[0].id
      loadCategoryWeightData()
    } else {
      categoryWeightSystemId.value = null
      categoryWeightList.value = []
    }
    // 加载大类列表
    const categoryRes = await metricApi.categoryListAll()
    const categories = categoryRes.data || []

    // 加载所有小类用于统计
    const allSubcategories = []
    for (const cat of categories) {
      try {
        const subRes = await subcategoryApi.list(cat.id)
        allSubcategories.push(...(subRes.data || []).map(s => ({ ...s, categoryId: cat.id })))
      } catch (e) {
        // ignore
      }
    }

    // 统计每个大类的小类数量
    const subCountMap = {}
    allSubcategories.forEach(s => {
      if (!s.categoryId) return
      subCountMap[s.categoryId] = (subCountMap[s.categoryId] || 0) + 1
    })

    categoryList.value = categories.map(c => ({
      ...c,
      systemName: systemList.value.find(s => s.id === c.systemId)?.systemName || '',
      subcategoryCount: subCountMap[c.id] || 0
    }))

    // 加载所有大类的策略
    await loadCategoryStrategyList(categories)

    categoryWeightDialogVisible.value = true
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

// 加载所有大类的策略列表
const loadCategoryStrategyList = async (categories) => {
  try {
    const res = await configApi.strategyList()
    const allStrategies = res.data || []
    // 筛选出大类的策略
    const categoryStrategies = allStrategies.filter(s => s.targetType === 'category')

    // 构建大类策略列表
    categoryStrategyList.value = categories.map(cat => {
      const strategy = categoryStrategies.find(s => s.targetId === cat.id)
      if (strategy) {
        return {
          categoryId: cat.id,
          categoryName: cat.categoryName,
          ...strategy,
          strategyId: strategy.id,
          strategyName: strategy.strategyName || cat.categoryName + ' 健康度策略',
          alarmEnabled: strategy.alarmEnabled || 0,
          alarmCondition: strategy.alarmCondition || '<',
          alarmThreshold: strategy.alarmThreshold ?? null,
          alarmConsecutiveTimes: strategy.alarmConsecutiveTimes || 1,
          gongdanEnabled: strategy.gongdanEnabled || 0,
          gongdanCondition: strategy.gongdanCondition || '<',
          gongdanThreshold: strategy.gongdanThreshold ?? null,
          gongdanConsecutiveTimes: strategy.gongdanConsecutiveTimes || 1,
          saving: false
        }
      } else {
        return {
          categoryId: cat.id,
          categoryName: cat.categoryName,
          strategyId: null,
          strategyName: cat.categoryName + ' 健康度策略',
          alarmEnabled: 0,
          alarmCondition: '<',
          alarmThreshold: null,
          alarmConsecutiveTimes: 1,
          gongdanEnabled: 0,
          gongdanCondition: '<',
          gongdanThreshold: null,
          gongdanConsecutiveTimes: 1,
          saving: false
        }
      }
    })
  } catch (error) {
    console.error('加载大类策略列表失败:', error)
    // 即使加载失败，也创建空列表
    categoryStrategyList.value = categories.map(cat => ({
      categoryId: cat.id,
      categoryName: cat.categoryName,
      strategyId: null,
      strategyName: cat.categoryName + ' 健康度策略',
      alarmEnabled: 0,
      alarmCondition: '<',
      alarmThreshold: null,
      alarmConsecutiveTimes: 1,
      gongdanEnabled: 0,
      gongdanCondition: '<',
      gongdanThreshold: null,
      gongdanConsecutiveTimes: 1,
      saving: false
    }))
  }
}

// 保存单行大类策略
const saveCategoryStrategyRow = async (row, index) => {
  categoryStrategyList.value[index].saving = true
  try {
    const submitData = {
      strategyName: row.strategyName,
      strategyType: 'health',
      targetType: 'category',
      targetId: row.categoryId,
      targetName: row.categoryName,
      alarmEnabled: row.alarmEnabled,
      alarmCondition: row.alarmCondition,
      alarmThreshold: row.alarmThreshold,
      alarmConsecutiveTimes: row.alarmConsecutiveTimes,
      gongdanEnabled: row.gongdanEnabled,
      gongdanCondition: row.gongdanCondition,
      gongdanThreshold: row.gongdanThreshold,
      gongdanConsecutiveTimes: row.gongdanConsecutiveTimes
    }
    if (row.strategyId) {
      await configApi.strategyUpdate(row.strategyId, submitData)
    } else {
      const res = await configApi.strategyCreate(submitData)
      if (res.data?.id) {
        categoryStrategyList.value[index].strategyId = res.data.id
      }
    }
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存策略失败:', error)
    ElMessage.error('保存失败')
  } finally {
    categoryStrategyList.value[index].saving = false
  }
}

// 加载系统策略列表
const loadSystemStrategyList = async (systems) => {
  try {
    const res = await configApi.strategyList()
    const allStrategies = res.data || []
    const systemStrategies = allStrategies.filter(s => s.targetType === 'system')

    systemStrategyList.value = systems.map(sys => {
      const strategy = systemStrategies.find(s => s.targetId === sys.id)
      if (strategy) {
        return {
          systemId: sys.id,
          systemName: sys.systemName,
          ...strategy,
          strategyId: strategy.id,
          strategyName: strategy.strategyName || sys.systemName + ' 健康度策略',
          alarmEnabled: strategy.alarmEnabled || 0,
          alarmCondition: strategy.alarmCondition || '<',
          alarmThreshold: strategy.alarmThreshold ?? null,
          alarmConsecutiveTimes: strategy.alarmConsecutiveTimes || 1,
          gongdanEnabled: strategy.gongdanEnabled || 0,
          gongdanCondition: strategy.gongdanCondition || '<',
          gongdanThreshold: strategy.gongdanThreshold ?? null,
          gongdanConsecutiveTimes: strategy.gongdanConsecutiveTimes || 1,
          saving: false
        }
      } else {
        return {
          systemId: sys.id,
          systemName: sys.systemName,
          strategyId: null,
          strategyName: sys.systemName + ' 健康度策略',
          alarmEnabled: 0,
          alarmCondition: '<',
          alarmThreshold: null,
          alarmConsecutiveTimes: 1,
          gongdanEnabled: 0,
          gongdanCondition: '<',
          gongdanThreshold: null,
          gongdanConsecutiveTimes: 1,
          saving: false
        }
      }
    })
  } catch (error) {
    console.error('加载系统策略列表失败:', error)
    systemStrategyList.value = systems.map(sys => ({
      systemId: sys.id,
      systemName: sys.systemName,
      strategyId: null,
      strategyName: sys.systemName + ' 健康度策略',
      alarmEnabled: 0,
      alarmCondition: '<',
      alarmThreshold: null,
      alarmConsecutiveTimes: 1,
      gongdanEnabled: 0,
      gongdanCondition: '<',
      gongdanThreshold: null,
      gongdanConsecutiveTimes: 1,
      saving: false
    }))
  }
}

// 保存单行系统策略
const saveSystemStrategyRow = async (row, index) => {
  systemStrategyList.value[index].saving = true
  try {
    const submitData = {
      strategyName: row.strategyName,
      strategyType: 'health',
      targetType: 'system',
      targetId: row.systemId,
      targetName: row.systemName,
      alarmEnabled: row.alarmEnabled,
      alarmCondition: row.alarmCondition,
      alarmThreshold: row.alarmThreshold,
      alarmConsecutiveTimes: row.alarmConsecutiveTimes,
      gongdanEnabled: row.gongdanEnabled,
      gongdanCondition: row.gongdanCondition,
      gongdanThreshold: row.gongdanThreshold,
      gongdanConsecutiveTimes: row.gongdanConsecutiveTimes
    }
    if (row.strategyId) {
      await configApi.strategyUpdate(row.strategyId, submitData)
    } else {
      const res = await configApi.strategyCreate(submitData)
      if (res.data?.id) {
        systemStrategyList.value[index].strategyId = res.data.id
      }
    }
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存策略失败:', error)
    ElMessage.error('保存失败')
  } finally {
    systemStrategyList.value[index].saving = false
  }
}

// 加载小类策略列表
const loadSubCategoryStrategyList = async (subcategories) => {
  try {
    const res = await configApi.strategyList()
    const allStrategies = res.data || []
    const subCatStrategies = allStrategies.filter(s => s.targetType === 'subCategory')

    subCategoryStrategyList.value = subcategories.map(sub => {
      const strategy = subCatStrategies.find(s => s.targetId === sub.id)
      if (strategy) {
        return {
          categoryId: sub.categoryId,
          subCategoryId: sub.id,
          subCategoryName: sub.subCategoryName,
          ...strategy,
          strategyId: strategy.id,
          strategyName: strategy.strategyName || sub.subCategoryName + ' 健康度策略',
          alarmEnabled: strategy.alarmEnabled || 0,
          alarmCondition: strategy.alarmCondition || '<',
          alarmThreshold: strategy.alarmThreshold ?? null,
          alarmConsecutiveTimes: strategy.alarmConsecutiveTimes || 1,
          gongdanEnabled: strategy.gongdanEnabled || 0,
          gongdanCondition: strategy.gongdanCondition || '<',
          gongdanThreshold: strategy.gongdanThreshold ?? null,
          gongdanConsecutiveTimes: strategy.gongdanConsecutiveTimes || 1,
          saving: false
        }
      } else {
        return {
          categoryId: sub.categoryId,
          subCategoryId: sub.id,
          subCategoryName: sub.subCategoryName,
          strategyId: null,
          strategyName: sub.subCategoryName + ' 健康度策略',
          alarmEnabled: 0,
          alarmCondition: '<',
          alarmThreshold: null,
          alarmConsecutiveTimes: 1,
          gongdanEnabled: 0,
          gongdanCondition: '<',
          gongdanThreshold: null,
          gongdanConsecutiveTimes: 1,
          saving: false
        }
      }
    })
  } catch (error) {
    console.error('加载小类策略列表失败:', error)
    subCategoryStrategyList.value = subcategories.map(sub => ({
      subCategoryId: sub.id,
      subCategoryName: sub.subCategoryName,
      strategyId: null,
      strategyName: sub.subCategoryName + ' 健康度策略',
      alarmEnabled: 0,
      alarmCondition: '<',
      alarmThreshold: null,
      alarmConsecutiveTimes: 1,
      gongdanEnabled: 0,
      gongdanCondition: '<',
      gongdanThreshold: null,
      gongdanConsecutiveTimes: 1,
      saving: false
    }))
  }
}

// 保存单行小类策略
const saveSubCategoryStrategyRow = async (row, index) => {
  subCategoryStrategyList.value[index].saving = true
  try {
    const submitData = {
      strategyName: row.strategyName,
      strategyType: 'health',
      targetType: 'subCategory',
      targetId: row.subCategoryId,
      targetName: row.subCategoryName,
      alarmEnabled: row.alarmEnabled,
      alarmCondition: row.alarmCondition,
      alarmThreshold: row.alarmThreshold,
      alarmConsecutiveTimes: row.alarmConsecutiveTimes,
      gongdanEnabled: row.gongdanEnabled,
      gongdanCondition: row.gongdanCondition,
      gongdanThreshold: row.gongdanThreshold,
      gongdanConsecutiveTimes: row.gongdanConsecutiveTimes
    }
    if (row.strategyId) {
      await configApi.strategyUpdate(row.strategyId, submitData)
    } else {
      const res = await configApi.strategyCreate(submitData)
      if (res.data?.id) {
        subCategoryStrategyList.value[index].strategyId = res.data.id
      }
    }
    ElMessage.success('保存成功')
  } catch (error) {
    console.error('保存策略失败:', error)
    ElMessage.error('保存失败')
  } finally {
    subCategoryStrategyList.value[index].saving = false
  }
}

// 选择大类权重的系统
const selectCategoryWeightSystem = (systemId) => {
  categoryWeightSystemId.value = systemId
  loadCategoryWeightData()
}

// 加载大类权重数据
const loadCategoryWeightData = async () => {
  if (!categoryWeightSystemId.value) return
  try {
    const res = await weightApi.getConfig(categoryWeightSystemId.value)
    const data = res.data || {}
    const catWeights = data.categoryWeights || {}
    const categories = data.categories || []
    categoryWeightList.value = categories.map(cat => ({
      categoryId: cat.id,
      categoryName: cat.categoryName,
      weight: catWeights[cat.id] ? parseFloat(catWeights[cat.id]) : (1 / categories.length)
    }))
  } catch (error) {
    console.error('加载大类权重失败:', error)
  }
}

// 大类权重变化
const onCategoryWeightChange = async (index, newValue) => {
  const oldValue = categoryWeightList.value[index].weight
  const delta = newValue - oldValue
  const otherCount = categoryWeightList.value.length - 1
  if (otherCount > 0) {
    const adjustment = delta / otherCount
    categoryWeightList.value = categoryWeightList.value.map((w, i) => {
      if (i === index) return { ...w, weight: newValue }
      return { ...w, weight: Math.max(0, Math.min(1, Math.round((w.weight - adjustment) * 100) / 100)) }
    })
    normalizeCategoryWeights()
  }
  await saveCategoryWeights()
}

const normalizeCategoryWeights = () => {
  const sum = sumCategoryWeights.value
  if (sum === 0) return
  categoryWeightList.value = categoryWeightList.value.map(w => ({
    ...w,
    weight: Math.round(w.weight / sum * 100) / 100
  }))
}

const saveCategoryWeights = async () => {
  try {
    await weightApi.updateCategoryWeights(
      categoryWeightSystemId.value,
      categoryWeightList.value.map(w => ({
        categoryId: w.categoryId,
        weight: w.weight.toString()
      }))
    )
    categoryLastSaveTime.value = new Date().toLocaleTimeString()
    ElMessage.success('权重已保存')
  } catch (error) {
    console.error('保存大类权重失败:', error)
    ElMessage.error('保存失败')
  }
}

// 导出权重配置
const exportWeightConfig = async () => {
  // 如果没有选择系统，使用第一个系统
  let systemId = categoryWeightSystemId.value
  if (!systemId) {
    if (systemOptions.value.length === 0) {
      ElMessage.warning('请先选择系统')
      return
    }
    systemId = systemOptions.value[0].id
  }
  try {
    const response = await weightApi.exportConfig(systemId)
    const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    const timestamp = new Date().toISOString().replace(/[:.]/g, '').slice(0, 15)
    link.download = `weight_config_${timestamp}.xlsx`
    link.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (error) {
    console.error('导出权重配置失败:', error)
    ElMessage.error('导出失败')
  }
}

// 打开指标小类配置弹窗
const openSubcategoryDialog = async () => {
  try {
    subcategoryActiveTab.value = 'list'
    const systemRes = await systemApi.listAll()
    systemList.value = systemRes.data || []
    if (systemList.value.length > 0) {
      subcategorySystemId.value = systemList.value[0].id
      loadSubcategoryData()
    }
    subcategoryDialogVisible.value = true
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

// 监听小类弹窗tab切换
watch(subcategoryActiveTab, (newTab) => {
  if (newTab === 'strategy' && subcategoryList.value.length > 0) {
    loadSubCategoryStrategyList(subcategoryList.value)
  }
})

// 加载小类列表数据
const loadSubcategoryData = async () => {
  if (!subcategorySystemId.value) return
  try {
    // 加载该系统下的大类
    const categoryRes = await metricApi.categoryBySystem(subcategorySystemId.value)
    categoryOptions.value = categoryRes.data || []
    // 默认选择第一个大类
    if (categoryOptions.value.length > 0) {
      subcategoryCategoryId.value = categoryOptions.value[0].id
      loadSubcategoryWeightData()
    } else {
      subcategoryCategoryId.value = null
      subcategoryList.value = []
      subcategoryWeightList.value = []
    }
  } catch (error) {
    console.error('加载小类列表失败:', error)
  }
}

// 系统变化时加载大类
const onSubcategorySystemChange = async () => {
  subcategoryCategoryId.value = null
  subcategoryList.value = []
  subcategoryWeightList.value = []
  await loadSubcategoryData()
}

// 选择大类
const selectCategory = (categoryId) => {
  subcategoryCategoryId.value = categoryId
  loadSubcategoryWeightData()
}

// 加载小类权重数据
const loadSubcategoryWeightData = async () => {
  if (!subcategoryCategoryId.value) return
  try {
    // 加载该大类下的所有小类（从metric_sub_category表）
    const res = await subcategoryApi.list(subcategoryCategoryId.value)
    subcategoryList.value = res.data || []

    // 加载该大类下的指标数量（用subCategoryCode关联）
    const itemRes = await metricApi.itemList({ categoryId: subcategoryCategoryId.value, size: 1000 })
    const items = itemRes.data?.content || []
    const itemCountMap = {}
    items.forEach(item => {
      if (item.subCategoryCode) {
        itemCountMap[item.subCategoryCode] = (itemCountMap[item.subCategoryCode] || 0) + 1
      }
    })
    subcategoryList.value = subcategoryList.value.map(sub => ({
      ...sub,
      itemCount: itemCountMap[sub.subCategoryCode] || 0
    }))

    // 加载权重（用subCategoryCode关联）
    const weightRes = await weightApi.getConfig(subcategorySystemId.value)
    const subWeights = weightRes.data?.subcategoryWeights?.[subcategoryCategoryId.value] || {}
    const count = subcategoryList.value.length
    subcategoryWeightList.value = subcategoryList.value.map(sub => ({
      subCategoryCode: sub.subCategoryCode,
      subCategoryName: sub.subCategoryName,
      weight: subWeights[sub.subCategoryCode] ? parseFloat(subWeights[sub.subCategoryCode]) : (count > 0 ? 1 / count : 0)
    }))

    // 如果当前在告警策略tab，同步加载策略列表
    if (subcategoryActiveTab.value === 'strategy') {
      loadSubCategoryStrategyList(subcategoryList.value)
    }
  } catch (error) {
    console.error('加载小类权重失败:', error)
  }
}

// 打开新增小类表单
const openSubcategoryForm = () => {
  editingSubcategory.value = {
    id: null,
    categoryId: subcategoryCategoryId.value,
    subCategoryCode: '',
    subCategoryName: '',
    description: '',
    status: 'enabled'
  }
  subcategoryFormVisible.value = true
}

// 编辑小类（弹窗形式）
const editSubcategory = (row) => {
  editingSubcategory.value = {
    id: row.id,
    categoryId: row.categoryId,
    subCategoryCode: row.subCategoryCode,
    subCategoryName: row.subCategoryName,
    description: row.description || '',
    status: row.status || 'enabled'
  }
  subcategoryFormVisible.value = true
}

// 开始编辑小类（双击触发）
const startEditSubcategory = (row) => {
  row._editing = true
}

// 保存小类（弹窗形式）
const saveSubcategory = async () => {
  if (!editingSubcategory.value.subCategoryCode || !editingSubcategory.value.subCategoryName) {
    ElMessage.warning('请填写小类编码和名称')
    return
  }
  try {
    if (editingSubcategory.value.id) {
      await subcategoryApi.update(editingSubcategory.value.id, editingSubcategory.value)
      ElMessage.success('更新成功')
    } else {
      await subcategoryApi.create(editingSubcategory.value)
      ElMessage.success('创建成功')
    }
    subcategoryFormVisible.value = false
    loadSubcategoryWeightData()
  } catch (error) {
    console.error('保存小类失败:', error)
    ElMessage.error('保存失败')
  }
}

// 保存小类（行内编辑）
const saveSubcategoryRow = async (row) => {
  try {
    const updateData = {
      subCategoryName: row.subCategoryName,
      description: row.description,
      status: row.status
    }
    if (row.id) {
      await subcategoryApi.update(row.id, updateData)
    } else {
      await subcategoryApi.create({ ...updateData, categoryId: subcategoryCategoryId.value })
    }
    ElMessage.success('保存成功')
    loadSubcategoryWeightData()
  } catch (error) {
    console.error('保存小类失败:', error)
    ElMessage.error('保存失败')
  }
}

// 删除小类
const deleteSubcategory = async (row) => {
  try {
    await ElMessageBox.confirm(`确定要删除小类"${row.subCategoryName}"吗？`, '提示', {
      type: 'warning'
    })
    await subcategoryApi.delete(row.id)
    ElMessage.success('删除成功')
    loadSubcategoryWeightData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除小类失败:', error)
    }
  }
}

// 小类列表选择
const handleSubcategorySelection = (selection) => {
  selectedSubcategories.value = selection
}

// 批量删除小类
const batchDeleteSubcategories = async () => {
  if (!selectedSubcategories.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedSubcategories.value.length} 个小类吗?`, '提示', { type: 'warning' })
    for (const sub of selectedSubcategories.value) {
      await subcategoryApi.delete(sub.id)
    }
    ElMessage.success('删除成功')
    selectedSubcategories.value = []
    loadSubcategoryWeightData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除小类失败:', error)
      ElMessage.error('删除失败')
    }
  }
}

// 小类权重变化
const onSubcategoryWeightChange = async (index, newValue) => {
  const oldValue = subcategoryWeightList.value[index].weight
  const delta = newValue - oldValue
  const otherCount = subcategoryWeightList.value.length - 1
  if (otherCount > 0) {
    const adjustment = delta / otherCount
    subcategoryWeightList.value = subcategoryWeightList.value.map((w, i) => {
      if (i === index) return { ...w, weight: newValue }
      return { ...w, weight: Math.max(0, Math.min(1, Math.round((w.weight - adjustment) * 100) / 100)) }
    })
    normalizeSubcategoryWeights()
  }
  await saveSubcategoryWeights()
}

const normalizeSubcategoryWeights = () => {
  const sum = sumSubcategoryWeights.value
  if (sum === 0) return
  subcategoryWeightList.value = subcategoryWeightList.value.map(w => ({
    ...w,
    weight: Math.round(w.weight / sum * 100) / 100
  }))
}

const saveSubcategoryWeights = async () => {
  try {
    await weightApi.updateSubcategoryWeights(
      subcategoryCategoryId.value,
      subcategoryWeightList.value.map(w => ({
        subCategoryName: w.subCategoryCode,  // 用编码关联
        weight: w.weight.toString()
      }))
    )
    subcategoryLastSaveTime.value = new Date().toLocaleTimeString()
    ElMessage.success('权重已保存')
  } catch (error) {
    console.error('保存小类权重失败:', error)
    ElMessage.error('保存失败')
  }
}

// 打开具体指标权重配置弹窗
const openMetricWeightDialog = async () => {
  try {
    metricLastSaveTime.value = ''
    const systemRes = await systemApi.listAll()
    systemList.value = systemRes.data || []

    if (systemList.value.length > 0) {
      // 自动选择第一个系统
      metricWeightSystemId.value = systemList.value[0].id
      const categoryRes = await metricApi.categoryBySystem(metricWeightSystemId.value)
      categoryOptions.value = categoryRes.data || []

      if (categoryOptions.value.length > 0) {
        // 自动选择第一个大类
        metricWeightCategoryId.value = categoryOptions.value[0].id
        const itemRes = await metricApi.itemList({ categoryId: metricWeightCategoryId.value, size: 1000 })
        const items = itemRes.data?.content || []
        const subs = [...new Set(items.map(i => i.subCategoryName).filter(s => s))]
        subCategoryOptions.value = subs

        if (subs.length > 0) {
          // 自动选择第一个小类
          metricWeightSubCategoryName.value = subs[0]
          loadMetricWeightData()
        }
      }
    } else {
      metricWeightSystemId.value = null
      metricWeightCategoryId.value = null
      metricWeightSubCategoryName.value = ''
      categoryOptions.value = []
      subCategoryOptions.value = []
      metricWeightList.value = []
    }
    metricWeightDialogVisible.value = true
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

// 选择具体指标权重的系统
const selectMetricWeightSystem = async (systemId) => {
  metricWeightSystemId.value = systemId
  metricWeightCategoryId.value = null
  metricWeightSubCategoryName.value = ''
  subCategoryOptions.value = []
  metricWeightList.value = []
  // 加载该系统下的大类
  const categoryRes = await metricApi.categoryBySystem(systemId)
  categoryOptions.value = categoryRes.data || []
}

// 选择具体指标权重的大类
const selectMetricWeightCategory = async (categoryId) => {
  metricWeightCategoryId.value = categoryId
  metricWeightSubCategoryName.value = ''
  metricWeightList.value = []
  // 加载小类选项
  const itemRes = await metricApi.itemList({ categoryId: categoryId, size: 1000 })
  const items = itemRes.data?.content || []
  const subs = [...new Set(items.map(i => i.subCategoryName).filter(s => s))]
  subCategoryOptions.value = subs
  // 自动选择第一个小类
  if (subs.length > 0) {
    metricWeightSubCategoryName.value = subs[0]
    loadMetricWeightData()
  }
}

// 选择具体指标权重的小类
const selectMetricWeightSubcategory = (subName) => {
  metricWeightSubCategoryName.value = subName
  loadMetricWeightData()
}

const onMetricWeightSystemChange = async () => {
  metricWeightCategoryId.value = null
  metricWeightSubCategoryName.value = ''
  metricWeightList.value = []
  if (metricWeightSystemId.value) {
    const categoryRes = await metricApi.categoryBySystem(metricWeightSystemId.value)
    categoryOptions.value = categoryRes.data || []
  }
}

const onMetricWeightCategoryChange = async () => {
  metricWeightSubCategoryName.value = ''
  metricWeightList.value = []
  if (metricWeightCategoryId.value) {
    const itemRes = await metricApi.itemList({ categoryId: metricWeightCategoryId.value, size: 1000 })
    const items = itemRes.data?.content || []
    const subs = [...new Set(items.map(i => i.subCategoryName).filter(s => s))]
    subCategoryOptions.value = subs
  }
}

const loadMetricWeightData = async () => {
  if (!metricWeightCategoryId.value || !metricWeightSubCategoryName.value) return
  try {
    const res = await metricApi.itemList({
      categoryId: metricWeightCategoryId.value,
      subCategoryName: metricWeightSubCategoryName.value,
      size: 1000
    })
    const items = res.data?.content || []
    metricWeightList.value = items.map(item => ({
      metricId: item.id,
      metricName: item.metricName,
      metricCode: item.metricCode
    }))

    // 加载权重
    const weightRes = await weightApi.getConfig(metricWeightSystemId.value)
    const metricWeightsMap = weightRes.data?.metricWeights?.[metricWeightCategoryId.value] || {}
    const count = metricWeightList.value.length
    metricWeightList.value = metricWeightList.value.map(m => ({
      ...m,
      weight: metricWeightsMap[m.metricId] != null ? parseFloat(metricWeightsMap[m.metricId]) : (count > 0 ? 1 / count : 0)
    }))

    // 归一化权重
    normalizeMetricWeights()
  } catch (error) {
    console.error('加载指标权重失败:', error)
  }
}

const onMetricWeightChange = async (index, newValue) => {
  const oldValue = metricWeightList.value[index].weight
  const delta = newValue - oldValue
  const otherCount = metricWeightList.value.length - 1
  if (otherCount > 0) {
    const adjustment = delta / otherCount
    metricWeightList.value = metricWeightList.value.map((w, i) => {
      if (i === index) return { ...w, weight: newValue }
      return { ...w, weight: Math.max(0, Math.min(1, Math.round((w.weight - adjustment) * 100) / 100)) }
    })
    normalizeMetricWeights()
  }
  await saveMetricWeights()
}

const normalizeMetricWeights = () => {
  const sum = sumMetricWeights.value
  if (sum === 0) return
  metricWeightList.value = metricWeightList.value.map(w => ({
    ...w,
    weight: Math.round(w.weight / sum * 100) / 100
  }))
}

const saveMetricWeights = async () => {
  try {
    await weightApi.updateMetricWeights(
      metricWeightCategoryId.value,
      metricWeightList.value.map(w => ({
        metricId: w.metricId,
        weight: w.weight.toString()
      }))
    )
    metricLastSaveTime.value = new Date().toLocaleTimeString()
    ElMessage.success('权重已保存')
  } catch (error) {
    console.error('保存指标权重失败:', error)
    ElMessage.error('保存失败')
  }
}

onMounted(() => {
  loadPreferences()
  loadMetricStats()
  loadAudiences()
  loadData()
})
</script>

<style scoped>
.metric-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.header-buttons {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.el-tag {
  cursor: pointer;
}

.el-tag:hover {
  opacity: 0.8;
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
  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-buttons {
    width: 100%;
  }

  .header-buttons .el-button {
    flex: 1;
  }

  /* 移动端切换为卡片布局 */
  .desktop-table {
    display: none;
  }

  .mobile-cards {
    display: block;
  }

  .metric-card {
    margin-bottom: 12px;
  }

  .card-title {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .card-content {
    font-size: 13px;
  }

  .card-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 0;
    border-bottom: 1px solid #eee;
  }

  .card-row:last-child {
    border-bottom: none;
  }

  .card-row .label {
    color: var(--ui-muted);
    flex-shrink: 0;
  }

  .card-row .value {
    color: var(--ui-heading);
    text-align: right;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 60%;
  }

  .card-footer {
    margin-top: 12px;
    text-align: right;
  }
}

.dialog-toolbar {
  margin-bottom: 15px;
}

.import-tip {
  margin-bottom: 10px;
}

.editable-cell-text:hover {
  background-color: var(--ui-page);
}

.editable-cell-edit {
  width: 100%;
}

/* 面向人员（岗位）下拉：单元格内铺满，点击即改 */
.audience-select-cell {
  width: 100%;
}

/* ===== 单位与阈值口径 ===== */
.unit-chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  background: #eef2ff;
  color: #3730a3;
  cursor: pointer;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.unit-chip:hover {
  background: #e0e7ff;
}

/* 未配置单位：红色提示，避免"单位为空被误读成某个默认单位" */
.unit-chip-missing {
  background: #fef2f2;
  color: #dc2626;
}

.threshold-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.unit-suffix {
  font-size: 12px;
  color: #6b7280;
  white-space: nowrap;
  flex-shrink: 0;
}

/* 筛选工具栏 */

/* 表格工具栏 */
.table-toolbar {
  margin-bottom: 15px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;      /* 概览条加入后按钮变多，窄屏允许换行 */
  row-gap: 8px;
  gap: 15px;
}

.table-toolbar .selected-count {
  color: var(--ui-muted);
  font-size: 14px;
}

.table-toolbar .toolbar-spacer {
  flex: 1;
}

/* 告警/工单配置概览条（2026-10-28 新增）
   一眼看到总量与覆盖率，每个数字可点筛选；整体做成浅底圆角条，与按钮区分开 */

/* 已配置：有值的用绿色，未配置用灰色，和告警概览列保持同一套语义 */

/* 告警概览列：两行，各自「标签 + 值」，未配置为灰色 */
.alarm-overview-cell .cell {
  padding-top: 4px;
  padding-bottom: 4px;
}

.ao-row {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  line-height: 17px;
  color: #b1b3b8;
  white-space: nowrap;
}

.ao-row + .ao-row {
  margin-top: 2px;
}

.ao-tag {
  flex: none;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 15px;
  background: #f2f3f5;
  color: #a8abb2;
}

.ao-row.on .ao-tag.alarm {
  background: var(--ui-danger-soft);
  color: var(--ui-danger);
}

.ao-row.on .ao-tag.gongdan {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
}

.ao-row.on .ao-val {
  color: var(--ui-heading);
  font-weight: 600;
}

.ao-val {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 表格容器 */
.table-container {
  overflow-x: auto;
}

/* 表格表头背景色 */
.table-container .el-table__header th {
  background-color: var(--ui-page) !important;
}

/* 列设置弹出框 */
.column-toggle-group {
  display: flex;
  flex-direction: column;
}

.column-toggle-group .el-checkbox {
  margin-right: 0;
  padding: 8px 4px;
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

.column-toggle-group .el-checkbox:hover {
  background-color: var(--ui-page);
}

/* 备份提示 */
.backup-tip {
  margin-top: 15px;
  padding: 10px;
  background-color: var(--ui-primary-soft);
  border-radius: 4px;
  font-size: 13px;
  color: var(--ui-text);
}

.backup-tip code {
  background-color: #e6e8eb;
  padding: 2px 6px;
  border-radius: 3px;
  font-family: monospace;
}

/* 权重配置样式 */
.dialog-info-bar {
  padding: 10px 15px;
  background-color: var(--ui-page);
  border-radius: 4px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.info-label {
  font-weight: bold;
  color: var(--ui-heading);
}

.weight-config-section {
  padding: 10px 0;
}

.weight-sum-row {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 20px;
  padding: 10px 15px;
  background-color: var(--ui-primary-soft);
  border-radius: 4px;
  font-size: 14px;
}

.weight-sum-row .sum-error {
  color: var(--ui-danger);
  font-weight: bold;
}

.weight-sum-row span:first-child {
  color: var(--ui-text);
}

.weight-sum-row span:last-child {
  font-weight: bold;
  color: var(--ui-success);
}

.weight-row {
  display: flex;
  align-items: center;
  margin-bottom: 15px;
  gap: 10px;
}

.weight-label {
  width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.weight-value {
  width: 50px;
  text-align: right;
  font-size: 14px;
  color: var(--ui-primary);
}

.weight-row .el-slider {
  flex: 1;
}

.subcategory-toolbar {
  margin-bottom: 10px;
}

.save-time {
  margin-left: 20px;
  color: var(--ui-success);
  font-size: 12px;
}

.category-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.category-tag {
  margin-right: 0;
}

/* ===== 操作列 ===== */
.op-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}
.op-more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.op-more__arrow {
  font-size: 12px;
  transition: transform 0.2s;
}
.op-more:focus .op-more__arrow,
.op-more:hover .op-more__arrow {
  transform: translateY(1px);
}

/* ===== 导入弹窗：模式选择 ===== */
.import-mode-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
@media (max-width: 720px) {
  .import-mode-grid { grid-template-columns: 1fr; }
}
.import-mode {
  border: 1.5px solid #e4e8ef;
  border-radius: 10px;
  padding: 13px 14px;
  cursor: pointer;
  transition: all 0.18s;
  background: var(--ui-surface);
}
.import-mode:hover { border-color: #b9d4ff; }
.import-mode.is-active {
  border-color: var(--ui-primary);
  background: #f5f9ff;
  box-shadow: 0 2px 10px rgba(64, 158, 255, 0.12);
}
.import-mode--danger.is-active {
  border-color: var(--ui-danger);
  background: #fff8f8;
  box-shadow: 0 2px 10px rgba(245, 108, 108, 0.12);
}
.import-mode__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.import-mode__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ui-heading);
}
.import-mode__desc {
  font-size: 12.5px;
  line-height: 1.7;
  color: #7a8290;
}
.import-mode__desc b { color: var(--ui-heading); }

.import-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  font-size: 12.5px;
}
.import-tools__label { color: var(--ui-muted); }
.import-tools__sep { color: #dcdfe6; }
.import-guard {
  margin-top: 10px;
  --el-checkbox-text-color: var(--ui-warning);
}

/* ===== 导入弹窗：预演 ===== */
.pv-summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.pv-card {
  border-radius: 10px;
  padding: 12px 10px;
  text-align: center;
  background: var(--ui-page);
  border: 1px solid #eef1f6;
}
.pv-num {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.pv-num--add { color: var(--ui-success); }
.pv-num--upd { color: var(--ui-primary); }
.pv-num--del { color: var(--ui-danger); }
.pv-num--same { color: #b0b6bf; }
.pv-label {
  margin-top: 2px;
  font-size: 12px;
  color: var(--ui-muted);
}

.pv-meta {
  margin-top: 10px;
  font-size: 12.5px;
  color: #7a8290;
}
.pv-meta b { color: var(--ui-heading); }

.pv-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.85;
  max-height: 150px;
  overflow-y: auto;
}

.pv-collapse {
  margin-top: 12px;
  border-top: none;
}
.pv-group { margin-bottom: 12px; }
.pv-group__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 9px;
  background: #f7f8fa;
  border-radius: 6px;
  font-size: 12.5px;
}
.pv-group__name { color: var(--ui-heading); font-weight: 600; }
.pv-group__count { color: var(--ui-danger); font-weight: 600; }
.pv-group__items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 2px 0;
}
.pv-item {
  font-size: 12px;
  color: #5a626e;
  background: #f4f6f9;
  border-radius: 4px;
  padding: 2px 7px;
  line-height: 1.6;
}
.pv-item.is-danger { background: var(--ui-danger-soft); color: var(--ui-danger); }
.pv-item__code {
  margin-left: 5px;
  color: #b0b6bf;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
}
.pv-ref-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ui-muted);
}
.pv-ref-tip b { color: var(--ui-warning); }

.pv-confirm {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
}
.pv-confirm__tip { font-size: 12.5px; color: #b88230; }
.pv-confirm__tip b { color: var(--ui-warning); }
/* Keep long definitions and JSON parameters from expanding the main configuration rows. */
:deep(.metric-config-table .el-table__cell) {
  padding: 4px 0;
}

:deep(.metric-config-table .cell) {
  line-height: 24px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.metric-config-table .editable-cell-text) {
  box-sizing: border-box;
  max-width: 100%;
  line-height: 20px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.metric-config-table .el-link),
:deep(.metric-config-table .el-link__inner) {
  display: block;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

:global(.metric-config-overflow) {
  max-width: min(480px, 90vw);
  max-height: 50vh;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.6;
}


</style>

<style scoped src="@/views/metric/prototype-theme.css"></style>
