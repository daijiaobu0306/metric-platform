<template>
  <main ref="root" class="shared-screen">
    <template v-if="screen">
      <header class="shared-header">
        <div class="screen-title">
          <h1 :title="summary?.name || screen.name">{{ summary?.name || screen.name }}</h1>
          <small v-if="!isMobile && overview.updatedAt">数据更新于 {{ time(overview.updatedAt) }} · 只读分享 · 当前范围 {{ currentRangeLabel }}</small>
        </div>
        <div class="actions">
          <el-radio-group :model-value="presetValue" size="small" @update:model-value="onPresetSelect">
            <el-radio-button :value="1">今天</el-radio-button>
            <el-radio-button :value="7">近7天</el-radio-button>
            <el-radio-button :value="30">近30天</el-radio-button>
          </el-radio-group>
          <!-- 桌面端用区间选择器；移动端改由下方原生日期时间控件底部面板承接（见 range-sheet）。
               手机 WebView 里 el-date-picker 的日历弹层会被专屏的 overflow/固定定位裁掉，导致点开看不到日历。 -->
          <el-popover v-if="!isMobile" placement="bottom-end" :width="360" trigger="click" :teleported="false" :hide-on-click="false">
            <template #reference><el-button size="small" class="range-btn" :class="{ active: customRangeActive }">{{ customRangeActive ? '自定义' : '区间' }}</el-button></template>
            <div class="range-picker-box">
              <el-date-picker v-model="rangeDraft" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" :teleported="false" size="small" class="range-picker" @change="onDesktopRangeChange" />
              <small v-if="rangeError" class="range-error">{{ rangeError }}</small>
              <small class="range-hint">最长 90 天；切换上方预设会清空自定义区间。</small>
              <el-button v-if="customRangeActive" link type="primary" size="small" @click="onPresetChange">清除自定义区间</el-button>
            </div>
          </el-popover>
          <!-- 移动端不再放大按钮：口径与 el-date-picker 的区间输入一致，一个胶囊框显示「开始 ~ 结束」，
               空间不够时省略中间，点击才打开底部面板（原生 datetime-local）。 -->
          <el-button v-else size="small" class="range-btn range-pill" :class="{ active: customRangeActive }" :title="currentRangeLabel" @click="openRangeSheet">
            <el-icon class="range-pill-icon"><Clock /></el-icon>
            <span class="range-pill-text">{{ mobileRangeLabel }}</span>
          </el-button>
          <el-button size="small" @click="refresh">刷新</el-button>
          <el-button v-if="!isMobile" size="small" @click="full">全屏</el-button>
        </div>
      </header>

      <nav v-if="!isMobile && visibleTabs.length" class="share-tabs" aria-label="专屏栏目">
        <button v-for="tab in visibleTabs" :key="tab.key" :class="{ active: activeTab === tab.key }" :aria-current="activeTab === tab.key ? 'true' : undefined" @click="selectTab(tab.key)">{{ tab.label }}</button>
      </nav>

      <div ref="content" class="screen-content" :aria-busy="loading || activeState?.loading">
        <div v-if="loading || activeState?.loading" class="panel-status" role="status">正在加载{{ activeLabel || '专屏' }}…</div>
        <div v-else-if="loadError || activeState?.error" class="panel-status" role="alert">
          <span>{{ loadError || activeState.error }}</span>
          <el-button size="small" @click="refresh">重试</el-button>
        </div>
        <div v-else-if="summary && !visibleTabs.length" class="panel-status">此专屏暂无可分享栏目</div>
        <LeaderDashboard
          v-else-if="isLeader && summary"
          :overview="overview"
          :active-section="activeTab"
          :compact="isMobile"
          :health-cards="leaderHealth?.data?.rows || []"
          :health-cards-loading="Boolean(leaderHealth?.loading)"
          :health-cards-error="Boolean(leaderHealth?.error)"
          :health-available="hasHealthPanel"
          @select-section="selectTab"
          @metric-detail="openMetric"
        />
        <template v-else-if="summary">
          <section v-if="show('situation') || show('overview') || show('events')" class="share-section">
            <div class="kpi-layout">
              <section class="panel">
                <header><div><h3>指标基线</h3><small>平台全量底数；卡片下方为本屏授权范围内的可见数量</small></div></header>
                <div class="kpi-grid four"><KpiCard v-for="item in baseline" :key="item.label" v-bind="item" /></div>
              </section>
              <section v-if="panel === 'closure' && hasModule('events')" class="panel todo-panel">
                <header><div><h3>闭环与健康态势</h3><small>待办积压及风险阻断</small></div></header>
                <div class="kpi-grid three">
                  <button type="button" v-for="item in todos" :key="item.kind" class="todo-card" :class="item.accent" @click="openEvents(item.kind)">
                    <span>{{ item.label }}</span><b>{{ item.pending }}</b><small>当前待处理 · 新增 {{ item.created }} / 完成 {{ item.completed }}<template v-if="item.platformPending != null"> · 平台 {{ item.platformPending }}</template></small>
                  </button>
                </div>
              </section>
            </div>
            <section v-if="panel === 'closure' && hasModule('events')" class="panel">
              <header><div><h3>闭环分析</h3><small>工单闭环率与问题责任分布（上方为本屏授权范围，下方为平台累计）</small></div></header>
              <div class="closure-layout">
                <div class="gauge-box">
                  <HalfGauge :value="closureRate" label="期间闭环率" unit="%" />
                  <small>{{ overview.gongdan?.closure?.closed || 0 }} / {{ overview.gongdan?.closure?.created || 0 }} 单已闭环</small>
                  <small v-if="platformClosureText" class="platform-line">{{ platformClosureText }}</small>
                </div>
                <ShareMobileList v-if="isMobile" :rows="overview.responsibility || []" :fields="mobileFields.responsibility" />
                <el-table v-else :data="overview.responsibility || []" max-height="260" size="small" empty-text="暂无问题责任记录">
                  <el-table-column prop="person" label="责任人" min-width="120" />
                  <el-table-column prop="pending" label="未解决" width="76" />
                  <el-table-column prop="overdue" label="超期" width="70" />
                </el-table>
              </div>
            </section>
          </section>

          <section v-if="show('health-observation')" class="share-section">
            <section class="panel"><header><div><h3>指标健康总览</h3><small>风险指标优先；点击卡片查看指标定义与最近记录</small></div></header><HealthGrid :rows="healthRows" :compact="isMobile" @select="openMetric" /></section>
            <!-- 人员角色健康度：与平台内专屏同一套组件（整体视角 + 按角色下钻），
                 分享页是匿名访问，不加载岗位字典，角色清单只来自授权范围内的卡片。 -->
            <AudienceHealthCard v-if="healthRows.length" :health-cards="healthRows" :days="days" :show-dict="false" class="share-role-health" @metric-detail="openMetric" />
          </section>

          <section v-if="show('catalog')" class="share-section">
            <section class="panel"><header><div><h3>指标体系</h3><small>当前分享授权范围内的指标定义与运行状态</small></div></header>
              <ShareSummaryCards v-if="isMobile" title="指标基线" note="本室管理范围" :items="catalogSummary" />
              <ShareMobileList v-if="isMobile" :rows="overview.catalogMetrics || []" :fields="mobileFields.catalog" :on-detail="canOpenMetric ? openMetric : undefined" />
              <el-table v-else :data="overview.catalogMetrics || []" stripe border max-height="560">
                <el-table-column prop="systemName" label="系统" width="110" />
                <el-table-column prop="categoryName" label="大类" width="115" />
                <el-table-column prop="subCategoryName" label="小类" width="115" />
                <el-table-column prop="metricName" label="指标名称" min-width="180" show-overflow-tooltip />
                <el-table-column prop="metricCode" label="编码" width="130" show-overflow-tooltip />
                <el-table-column prop="metricType" label="类型" width="80" />
                <el-table-column prop="metricDefinition" label="指标定义" min-width="220" show-overflow-tooltip />
                <el-table-column prop="collectFrequency" label="采集频率" width="95" />
                <el-table-column label="阈值" width="100"><template #default="{ row }">{{ threshold(row.alarmThreshold, row.unit) }}</template></el-table-column>
              </el-table>
            </section>
          </section>

          <section v-if="show('data')" class="share-section">
            <section class="panel"><header><div><h3>{{ activeLabel }}</h3><small>当前授权范围内的数据汇聚与指标健康</small></div></header>
              <ShareSummaryCards v-if="isMobile" title="数据概览" :items="dataSummaryCards" />
              <div v-else class="kpi-grid three compact">
                <KpiCard label="有数据指标" :value="(overview.dataMetrics || []).filter(x => Number(x.records) > 0).length" note="当前范围" accent="primary" />
                <KpiCard label="数据记录" :value="(overview.dataMetrics || []).reduce((n, x) => n + Number(x.records || 0), 0)" note="当前范围" accent="primary" />
                <KpiCard label="最近汇聚" :value="time(overview.dataSummary?.latest)" note="健康记录" accent="primary" />
              </div>
              <ShareMobileList v-if="isMobile" :rows="overview.dataMetrics || []" :fields="mobileFields.data" :preview-fields="['记录数', '最新数据']" :on-detail="canOpenMetric ? openMetric : undefined" />
              <el-table v-else :data="overview.dataMetrics || []" border max-height="460">
                <el-table-column prop="systemName" label="系统" width="110" />
                <el-table-column prop="categoryName" label="大类" width="120" />
                <el-table-column prop="subCategoryName" label="小类" width="120" />
                <el-table-column prop="metricName" label="指标" min-width="200" />
                <el-table-column prop="records" label="记录数" width="80" />
                <el-table-column label="最新数据" width="170"><template #default="{ row }">{{ time(row.latestDataTime) }}</template></el-table-column>
                <el-table-column v-if="hasModule('health')" label="健康度" width="100"><template #default="{ row }">{{ row.healthScore ?? '—' }}</template></el-table-column>
                <el-table-column v-if="hasModule('health')" label="查看" width="90"><template #default="{ row }"><el-button link type="primary" @click="openMetric(row)">详情</el-button></template></el-table-column>
              </el-table>
            </section>
          </section>

          <section v-if="show('sources')" class="share-section">
            <section class="panel"><header><div><h3>数据来源</h3><small>来源表/字段、采集频率与最新计算结果</small></div><RowSortToggle v-model="sourceSort" class="share-sort"/></header>
              <ShareSummaryCards v-if="isMobile" title="来源概览" :items="sourcesSummary" />
              <ShareMobileList v-if="isMobile" :rows="sortedSources" :fields="mobileFields.sources" :on-detail="canOpenMetric ? openMetric : undefined" />
              <el-table v-else :data="sortedSources" stripe border max-height="560">
                <el-table-column prop="systemName" label="系统" width="105" />
                <el-table-column prop="categoryName" label="大类" width="110" />
                <el-table-column prop="subCategoryName" label="小类" width="115" />
                <el-table-column prop="metricName" label="指标" min-width="170" show-overflow-tooltip />
                <el-table-column prop="metricCode" label="编码" width="120" show-overflow-tooltip />
                <el-table-column label="来源表 / 字段" min-width="180"><template #default="{ row }">{{ row.sourceTable || '未配置' }}<small v-if="row.sourceField"> · {{ row.sourceField }}</small></template></el-table-column>
                <el-table-column prop="collectFrequency" label="采集频率" width="95" />
                <el-table-column label="最近数据" width="165"><template #default="{ row }">{{ time(row.latestDataTime) }}</template></el-table-column>
                <el-table-column label="最新结果" width="95"><template #default="{ row }">{{ row.latestValue ?? '--' }}</template></el-table-column>
              </el-table>
            </section>
          </section>

          <section v-if="show('strategy')" class="share-section">
            <section class="panel"><header><div><h3>策略配置</h3><small>告警与工单触发规则（只读）</small></div></header>
              <ShareSummaryCards v-if="isMobile" title="策略概览" :items="strategySummary" />
              <ShareMobileList v-if="isMobile" :rows="overview.strategies || []" :fields="mobileFields.strategies" />
              <el-table v-else :data="overview.strategies || []" stripe border max-height="560">
                <el-table-column prop="metricName" label="指标" min-width="170" show-overflow-tooltip />
                <el-table-column prop="strategyName" label="策略名称" min-width="150" show-overflow-tooltip />
                <el-table-column prop="strategyType" label="类型" width="90" />
                <el-table-column label="告警规则" min-width="160"><template #default="{ row }"><span v-if="Number(row.alarmEnabled) === 1">{{ row.alarmCondition || '比较条件未配置' }} {{ threshold(row.alarmThreshold) }} · 连续 {{ row.alarmConsecutiveTimes || 1 }} 次</span><el-tag v-else size="small" type="info">未启用</el-tag></template></el-table-column>
                <el-table-column label="工单规则" min-width="160"><template #default="{ row }"><span v-if="Number(row.gongdanEnabled) === 1">{{ row.gongdanCondition || '比较条件未配置' }} {{ threshold(row.gongdanThreshold) }} · 连续 {{ row.gongdanConsecutiveTimes || 1 }} 次</span><el-tag v-else size="small" type="info">未启用</el-tag></template></el-table-column>
                <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="Number(row.status) === 1 ? 'success' : 'info'">{{ Number(row.status) === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
              </el-table>
            </section>
          </section>

          <section v-if="show('supervisors')" class="share-section">
            <section class="panel"><header><div><h3>主管与闭环</h3><small>各主管按自身专屏授权计算；范围可能重叠，数值不可相加</small></div></header>
              <ShareSummaryCards v-if="isMobile" title="主管概览" note="范围可能重叠，数值不可相加" :items="supervisorSummaryCards" />
              <ShareMobileList v-if="isMobile" :rows="overview.supervisorSummary || []" :fields="mobileFields.supervisors" />
              <el-table v-else :data="overview.supervisorSummary || []" stripe border max-height="420">
                <el-table-column label="业务主管" min-width="140"><template #default="{ row }">{{ row.displayName || row.username }}</template></el-table-column>
                <el-table-column prop="metricCount" label="可见指标" width="95" />
                <el-table-column prop="alarmPending" label="待处理告警" width="105" />
                <el-table-column prop="issuePending" label="未解决问题" width="105" />
                <el-table-column prop="issueOverdue" label="超期问题" width="95" />
                <el-table-column prop="gongdanPending" label="未闭环工单" width="105" />
              </el-table>
            </section>
          </section>

          <ShareOperationsPanel v-if="show('operations')" :operations="overview.operations" :compact="isMobile" />

          <section v-if="show('team')" class="share-section">
            <section class="panel"><header><div><h3>团队使用</h3><small>账号登录与操作记录仅作平台使用参考</small></div></header>
              <ShareSummaryCards v-if="isMobile" title="使用概览" :items="teamSummaryCards" />
              <ShareMobileList v-if="isMobile" :rows="overview.team || []" :fields="mobileFields.team" />
              <el-table v-else :data="overview.team || []" stripe border max-height="420">
                <el-table-column label="业务主管" min-width="140"><template #default="{ row }">{{ row.displayName || row.username }}</template></el-table-column>
                <el-table-column label="期间登录" width="100"><template #default="{ row }">{{ row.usage?.sessions || 0 }}</template></el-table-column>
                <el-table-column prop="operations" label="期间操作" width="100" />
                <el-table-column label="最近登录" width="170"><template #default="{ row }">{{ time(row.lastLogin) }}</template></el-table-column>
              </el-table>
            </section>
          </section>
        </template>
      </div>
      <ShareMobileNavigation v-if="isMobile && visibleTabs.length" :tabs="visibleTabs" :active="activeTab" @select="selectTab" />
    </template>
    <div v-else-if="loading" class="panel-status" role="status">正在打开专屏…</div>
    <el-result v-else-if="error" icon="warning" title="链接无效、已失效或无访问权限" sub-title="请联系专屏管理员获取新的访问链接。" />
  </main>

  <!-- 移动端自定义区间面板：用原生 datetime-local，点开就是系统日历/时间轮，
       不依赖 Element Plus 的弹层定位，因此在 App WebView 和各家手机浏览器里都能选到时间。
       挂在 body 上避免被专屏的 overflow:hidden 裁剪。 -->
  <Teleport to="body">
    <div v-if="rangeSheet" class="range-sheet-mask" @click.self="closeRangeSheet">
      <div class="range-sheet" role="dialog" aria-modal="true" aria-label="自定义时间区间">
        <header>
          <strong>自定义时间区间</strong>
          <button type="button" class="range-sheet-close" aria-label="关闭" @click="closeRangeSheet">×</button>
        </header>
        <label class="range-sheet-field">
          <span>开始时间</span>
          <input v-model="startLocal" class="range-native" type="datetime-local" step="60">
        </label>
        <label class="range-sheet-field">
          <span>结束时间</span>
          <input v-model="endLocal" class="range-native" type="datetime-local" step="60">
        </label>
        <small v-if="rangeError" class="range-error">{{ rangeError }}</small>
        <small class="range-hint">最长 90 天；应用后切换上方「今天 / 近7天 / 近30天」会清空自定义区间。</small>
        <div class="range-sheet-actions">
          <el-button size="small" @click="closeRangeSheet">取消</el-button>
          <el-button type="primary" size="small" @click="applyRangeSheet">应用区间</el-button>
        </div>
      </div>
    </div>
  </Teleport>

  <el-dialog v-model="metricVisible" :title="metricTitle" width="min(900px, 94vw)" top="5vh" class="share-metric-dialog">
    <div v-loading="metricLoading" class="metric-dialog">
      <!-- 移动端外层保持纯文字列表，点进详情再给可视化：指标值趋势优先，健康度有数据时追加一条 -->
      <ShareTrendChart
        v-if="!metricLoading && chartPoints.length"
        :points="chartPoints"
        title="指标值趋势"
        :unit="metricDetail?.definition?.[0]?.unit || ''"
      />
      <ShareTrendChart
        v-if="!metricLoading && healthChartPoints.length"
        :points="healthChartPoints"
        value-key="healthScore"
        title="健康度趋势"
        color="#0f766e"
      />
      <p v-if="!metricLoading && metricError" class="dialog-hint">{{ metricError }}</p>
      <p v-if="!metricLoading && !chartPoints.length" class="dialog-hint">当前时间范围没有可用于绘图的采集记录，可切换「近7天 / 近30天」后再看。</p>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="指标编码">{{ metricDetail?.definition?.[0]?.code || '-' }}</el-descriptions-item>
        <el-descriptions-item label="业务含义">{{ metricDetail?.definition?.[0]?.definition || '-' }}</el-descriptions-item>
        <el-descriptions-item label="单位 / 阈值">{{ metricDetail?.definition?.[0]?.unit || '-' }} / {{ threshold(metricDetail?.definition?.[0]?.threshold) }}</el-descriptions-item>
      </el-descriptions>
      <ShareMobileList v-if="isMobile" :rows="metricDetail?.values || []" :fields="mobileFields.values" />
      <el-table v-else :data="metricDetail?.values || []" max-height="320" size="small" style="margin-top:12px">
        <el-table-column label="数据时间" min-width="170"><template #default="{ row }">{{ time(row.time) }}</template></el-table-column>
        <el-table-column prop="value" label="指标值" width="120" />
        <el-table-column prop="healthScore" label="健康度" width="100" />
        <el-table-column prop="status" label="状态" width="100" />
      </el-table>
    </div>
  </el-dialog>

  <el-dialog v-model="eventVisible" :title="eventTitle" width="min(900px, 94vw)" top="8vh">
    <div v-loading="eventLoading">
      <p class="dialog-hint">共 {{ eventTotal }} 条，展示最近 20 条；分享页只读。</p>
      <ShareMobileList v-if="isMobile" :rows="eventRows" :fields="mobileFields.events" />
      <el-table v-else :data="eventRows" border size="small" max-height="420">
        <el-table-column prop="title" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column prop="owner" label="责任人" width="120" />
        <el-table-column label="发生时间" width="170"><template #default="{ row }">{{ time(row.time) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.done ? 'success' : 'warning'">{{ row.done ? '已完成' : '待处理' }}</el-tag></template></el-table-column>
      </el-table>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Clock } from '@element-plus/icons-vue'
import KpiCard from '../../components/dashboard-view/ui/KpiCard.vue'
import HalfGauge from '../../components/dashboard-view/ui/HalfGauge.vue'
import ShareOperationsPanel from '../../components/dashboard-view/ShareOperationsPanel.vue'
import ShareMobileNavigation from '../../components/dashboard-view/ShareMobileNavigation.vue'
import ShareMobileList from '../../components/dashboard-view/ShareMobileList.vue'
import ShareTrendChart from '../../components/dashboard-view/ShareTrendChart.vue'
import ShareSummaryCards from '../../components/dashboard-view/ShareSummaryCards.vue'
import { scoreLevel } from '@/utils/healthThresholds'
import LeaderDashboard from '../../components/dashboard-view/LeaderDashboard.vue'
import HealthGrid from '../../components/dashboard-view/HealthGrid.vue'
import AudienceHealthCard from '../../components/dashboard-view/AudienceHealthCard.vue'
import RowSortToggle from '../../components/dashboard-view/RowSortToggle.vue'
import { sortRowsByTime } from '../../components/dashboard-view/sortRows'
import { publicShareApi as shareApi } from '../../components/dashboard-view/share-api'
import { shareTemplateTabs } from '../../components/dashboard-view/templates'
import '../../components/dashboard-view/theme/metric-tokens.css'
import '../../components/dashboard-view/theme/metric-utilities.css'

const root = ref(), loading = ref(true), error = ref(false), token = ref(''), days = ref(7)
// 自定义时间区间：桌面用 datetimerange 一次选完；移动端用底部面板里的原生 datetime-local，
// 避免日历弹层在手机上被裁剪/点不出来。customRange 是「已生效」区间，请求参数与缓存 key 都以它为准。
const rangeDraft = ref(null), customRange = ref(null), rangeError = ref('')
const rangeSheet = ref(false), startLocal = ref(''), endLocal = ref('')
const pad2 = n => String(n).padStart(2, '0')
/** 接口口径 `YYYY-MM-DD HH:mm:ss` → 原生控件口径 `YYYY-MM-DDTHH:mm`。 */
const toLocalInput = value => value ? String(value).replace(' ', 'T').slice(0, 16) : ''
/** 原生控件不输出秒，补 `:00` 再交给 commitRange 校验与请求。 */
const fromLocalInput = value => value ? `${String(value).replace('T', ' ').slice(0, 16)}:00` : ''
/** 面板默认值按小时偏移生成，避免用户每次从空白开始选。 */
const localInputAfter = (offsetHours) => {
  const d = new Date(Date.now() + offsetHours * 3600 * 1000)
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}
const screen = ref(null), summary = ref(null), content = ref(null), loadError = ref('')
const panelStates = reactive({})
const hasHealthPanel = computed(() => Object.values(summary.value?.tabPanels || {}).includes('health'))
const isLeader = computed(() => summary.value?.template === 'leader')
const leaderHealth = computed(() => panelStates[`${rangeKey()}:health`])
const panel = computed(() => summary.value?.tabPanels?.[activeTab.value])
const activeState = computed(() => panelStates[`${rangeKey()}:${panel.value}`])
const overview = computed(() => ({ ...summary.value, ...activeState.value?.data }))
/**
 * 健康度观测页签的数据源。
 * health 面板返回 { rows: [...] }（与平台内 health-cards 接口同构），
 * summary 里没有 rows，所以这里统一兜底成空数组，避免子组件拿到 undefined。
 * 白名单（不计分）指标不进健康总览，与平台内专屏口径一致；数据来源页仍会展示它们。
 */
const healthRows = computed(() => {
  const rows = Array.isArray(activeState.value?.data) ? activeState.value.data : (overview.value.rows || [])
  return rows.filter(row => !row.exempt)
})
const activeLabel = computed(() => visibleTabs.value.find(tab => tab.key === activeTab.value)?.label)

const customRangeActive = computed(() => Array.isArray(customRange.value) && customRange.value.length === 2)
const currentRangeLabel = computed(() => customRangeActive.value
  ? `${customRange.value[0]} ~ ${customRange.value[1]}`
  : ({ 1: '今天', 7: '近7天', 30: '近30天' }[days.value] || `近${days.value}天`))
/** 移动端头部放不下完整时间：已选区间压成 MM-DD HH:mm ~ MM-DD HH:mm，未选时给同一口径的占位文案。 */
const shortTime = value => {
  const text = String(value || '')
  return text.length >= 16 ? `${text.slice(5, 10)} ${text.slice(11, 16)}` : text
}
const mobileRangeLabel = computed(() => customRangeActive.value
  ? `${shortTime(customRange.value[0])} ~ ${shortTime(customRange.value[1])}`
  : '开始时间 - 结束时间')
// 与后端 resolveRange 同口径：<=1天→1、<=7天→7、否则 30，供「N 天」文案和 health 面板使用。
function rangeDays (value) {
  const start = new Date(String(value[0]).replace(' ', 'T')).getTime()
  const end = new Date(String(value[1]).replace(' ', 'T')).getTime()
  const hours = (end - start) / 3600000
  if (!Number.isFinite(hours) || hours <= 0) return 7
  return hours <= 24 ? 1 : hours <= 7 * 24 ? 7 : 30
}
/** 缓存 key 必须带上实际区间，否则切换预设/自定义后会命中旧时间窗的数据。 */
const rangeKey = () => customRangeActive.value ? `${customRange.value[0]}~${customRange.value[1]}` : String(days.value)
/** 只有已生效自定义区间时才附加 startTime/endTime；否则请求与老分享链接一致只带 days。 */
function windowParams () {
  return customRangeActive.value ? { startTime: customRange.value[0], endTime: customRange.value[1] } : {}
}
// 已生效自定义区间时预设按钮不再高亮：否则「今天」和「11-24 ~ 11-25」会同时选中，看不出现行口径。
const presetValue = computed(() => customRangeActive.value ? null : days.value)
function onPresetSelect (value) {
  if (value == null) return
  days.value = value
  onPresetChange()
}
function onPresetChange () {
  customRange.value = null
  rangeDraft.value = null
  rangeSheet.value = false
  rangeError.value = ''
  loadActivePanel()
}
function commitRange (value) {
  if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
    rangeError.value = '请选择完整的开始时间和结束时间'
    return
  }
  const start = new Date(String(value[0]).replace(' ', 'T')).getTime()
  const end = new Date(String(value[1]).replace(' ', 'T')).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) { rangeError.value = '开始时间必须早于结束时间'; return }
  if (end - start > 90 * 24 * 3600 * 1000) { rangeError.value = '时间区间不能超过 90 天'; return }
  rangeError.value = ''
  customRange.value = [value[0], value[1]]
  rangeDraft.value = [value[0], value[1]]
  startLocal.value = toLocalInput(value[0])
  endLocal.value = toLocalInput(value[1])
  days.value = rangeDays(value)
  loadActivePanel()
}
function onDesktopRangeChange (value) { commitRange(value) }
/** 打开移动端面板：已有自定义区间就回填，否则默认近 24 小时，用户只需微调。 */
function openRangeSheet () {
  rangeError.value = ''
  if (customRangeActive.value) {
    startLocal.value = toLocalInput(customRange.value[0])
    endLocal.value = toLocalInput(customRange.value[1])
  } else {
    startLocal.value = localInputAfter(-24)
    endLocal.value = localInputAfter(0)
  }
  rangeSheet.value = true
}
function closeRangeSheet () { rangeSheet.value = false }
function applyRangeSheet () {
  commitRange([fromLocalInput(startLocal.value), fromLocalInput(endLocal.value)])
  if (!rangeError.value) closeRangeSheet()
}

const activeTab = ref('situation'), metricVisible = ref(false), metricLoading = ref(false), metricDetail = ref(), metricTitle = ref('指标详情'), metricError = ref('')
const eventVisible = ref(false), eventLoading = ref(false), eventRows = ref([]), eventTotal = ref(0), eventTitle = ref('')
const publicId = computed(() => String(window.location.pathname.match(/\/screen\/s\/([A-Za-z0-9_-]+)/)?.[1] || ''))
const secretKey = 'metric-dashboard-share:' + publicId.value
const time = value => value ? String(value).replace('T', ' ').slice(0, 19) : '-'
const threshold = (value, unit) => value == null ? '未配置' : `${value}${unit ? ` ${unit}` : ''}`
const labelOf = key => ({ alarm: '告警', issue: '问题', gongdan: '工单' }[key] || '事项')

const visibleTabs = computed(() => {
  const definitions = shareTemplateTabs(summary.value?.template)
  return (summary.value?.tabs || []).flatMap(key => {
    const tab = definitions.find(item => item.key === key)
    return tab && summary.value.tabPanels?.[key] ? [tab] : []
  })
})
const hasModule = key => Boolean(summary.value?.modules?.includes(key))
// 后端详情接口要求分享栏目里含 health 或 data，这里用同一口径决定入口，避免点进去 403
const canOpenMetric = computed(() => Object.values(summary.value?.tabPanels || {}).some(key => key === 'health' || key === 'data'))
const chartPoints = computed(() => metricDetail.value?.values || [])
const healthChartPoints = computed(() => metricDetail.value?.healthTrend || [])
const mobileMedia = window.matchMedia('(max-width: 680px)')
const isMobile = ref(mobileMedia.matches)
const updateMobile = event => { isMobile.value = event.matches }

function rule (row, kind) {
  if (Number(row[kind + 'Enabled']) !== 1) return '未启用'
  const value = row[kind + 'Threshold']
  const condition = row[kind + 'Condition']
  if (value == null || !condition) return '未配置'
  return `${condition} ${value} · 连续 ${row[kind + 'ConsecutiveTimes'] || 1} 次`
}

const mobileFields = {
  responsibility: [
    { label: '责任人', value: 'person' },
    { label: '未解决', value: 'pending' },
    { label: '超期', value: 'overdue' }
  ],
  catalog: [
    { label: '指标名称', value: 'metricName' },
    { label: '系统', value: 'systemName' },
    { label: '大类', value: 'categoryName' },
    { label: '小类', value: 'subCategoryName' },
    { label: '编码', value: 'metricCode' },
    { label: '类型', value: 'metricType' },
    { label: '指标定义', value: 'metricDefinition' },
    { label: '采集频率', value: 'collectFrequency' },
    { label: '阈值', value: (row) => threshold(row.alarmThreshold, row.unit) }
  ],
  data: [
    { label: '指标', value: 'metricName' },
    { label: '系统', value: 'systemName' },
    { label: '大类', value: 'categoryName' },
    { label: '小类', value: 'subCategoryName' },
    { label: '记录数', value: 'records' },
    { label: '最新值', value: row => row.latestValue == null ? '—' : `${row.latestValue} ${row.unit || ''}`.trim() },
    { label: '健康度', value: row => hasModule('health') ? row.healthScore : '未开放' },
    { label: '最新数据', value: (row) => time(row.latestDataTime) }
  ],
  sources: [
    { label: '指标', value: 'metricName' },
    { label: '系统', value: 'systemName' },
    { label: '大类', value: 'categoryName' },
    { label: '小类', value: 'subCategoryName' },
    { label: '编码', value: 'metricCode' },
    { label: '来源表 / 字段', value: (row) => row.sourceField ? `${row.sourceTable || '未配置'} · ${row.sourceField}` : row.sourceTable || '未配置' },
    { label: '采集频率', value: 'collectFrequency' },
    { label: '最近数据', value: (row) => time(row.latestDataTime) },
    { label: '最新结果', value: (row) => row.latestValue }
  ],
  strategies: [
    { label: '指标', value: 'metricName' },
    { label: '策略名称', value: 'strategyName' },
    { label: '类型', value: 'strategyType' },
    { label: '告警规则', value: (row) => rule(row, 'alarm') },
    { label: '工单规则', value: (row) => rule(row, 'gongdan') },
    { label: '状态', value: (row) => Number(row.status) === 1 ? '启用' : '停用' }
  ],
  supervisors: [
    { label: '业务主管', value: (row) => row.displayName || row.username },
    { label: '可见指标', value: 'metricCount' },
    { label: '待处理告警', value: 'alarmPending' },
    { label: '未解决问题', value: 'issuePending' },
    { label: '超期问题', value: 'issueOverdue' },
    { label: '未闭环工单', value: 'gongdanPending' }
  ],
  team: [
    { label: '业务主管', value: (row) => row.displayName || row.username },
    { label: '期间登录', value: (row) => row.usage?.sessions ?? 0 },
    { label: '期间操作', value: 'operations' },
    { label: '最近登录', value: (row) => time(row.lastLogin) }
  ],
  values: [
    { label: '数据时间', value: (row) => time(row.time) },
    { label: '指标值', value: 'value' },
    { label: '健康度', value: 'healthScore' },
    { label: '状态', value: 'status' }
  ],
  events: [
    { label: '内容', value: 'title' },
    { label: '责任人', value: 'owner' },
    { label: '发生时间', value: (row) => time(row.time) },
    { label: '状态', value: (row) => row.done ? '已完成' : '待处理' }
  ]
}

const show = key => activeTab.value === key
const counts = computed(() => overview.value?.counts || {})
// 数据来源表：与其他专屏视图保持一致，可按「最近数据」倒序/正序；只调整展示顺序，不重新请求
const sourceSort = ref('desc')
const sortedSources = computed(() => sortRowsByTime(overview.value.dataSources || [], sourceSort.value))

// —— 移动端各页签顶部的汇总卡 ——
// PC 端四列大卡片（图标 + 状态徽标 + 说明）单张近 100px 高，照搬到手机上会占掉半屏，
// 这里统一改成 2×2 紧凑格，只保留「标签 + 数值 + 单位」，用左侧色条表达语义。
const catalogSummary = computed(() => [
  { label: '接入系统', value: counts.value.system || 0, unit: '个' },
  { label: '指标大类', value: counts.value.category || 0, unit: '个' },
  { label: '指标小类', value: counts.value.subcategory || 0, unit: '个' },
  { label: '指标数量', value: counts.value.metric || 0, unit: '项', tone: 'success' }
])
const dataSummaryCards = computed(() => {
  const rows = overview.value.dataMetrics || []
  const active = rows.filter(row => Number(row.records) > 0).length
  const records = rows.reduce((total, row) => total + Number(row.records || 0), 0)
  const danger = healthRows.value.length
    ? healthRows.value.filter(row => scoreLevel(row.healthScore) === 'danger').length
    : '—'
  return [
    { label: '有数据指标', value: active, unit: '个' },
    { label: '数据记录', value: records.toLocaleString('zh-CN'), unit: '条' },
    { label: '异常指标', value: danger, unit: danger === '—' ? '' : '个', tone: 'danger' },
    { label: '最近汇聚', value: time(overview.value.dataSummary?.latest), tone: 'info' }
  ]
})
const sourcesSummary = computed(() => {
  const rows = overview.value.dataSources || []
  const configured = rows.filter(row => row.sourceTable).length
  const withData = rows.filter(row => row.latestDataTime).length
  const latest = rows.map(row => row.latestDataTime).filter(Boolean).sort().at(-1)
  return [
    { label: '指标来源', value: rows.length, unit: '条' },
    { label: '已配来源表', value: configured, unit: '个', tone: 'success' },
    { label: '有数据来源', value: withData, unit: '个' },
    { label: '最近数据', value: time(latest), tone: 'info' }
  ]
})
const strategySummary = computed(() => {
  const rows = overview.value.strategies || []
  const enabled = rows.filter(row => Number(row.status) === 1)
  return [
    { label: '策略总数', value: rows.length, unit: '条' },
    { label: '启用告警', value: enabled.filter(row => Number(row.alarmEnabled) === 1).length, unit: '条', tone: 'warning' },
    { label: '启用工单', value: enabled.filter(row => Number(row.gongdanEnabled) === 1).length, unit: '条', tone: 'success' },
    { label: '停用策略', value: rows.length - enabled.length, unit: '条' }
  ]
})
const supervisorSummaryCards = computed(() => {
  const rows = overview.value.supervisorSummary || []
  const total = key => rows.reduce((sum, row) => sum + Number(row[key] || 0), 0)
  return [
    { label: '关联主管', value: rows.length, unit: '人' },
    { label: '可见指标', value: total('metricCount'), unit: '项' },
    { label: '待处理告警', value: total('alarmPending'), unit: '条', tone: 'warning' },
    { label: '未闭环工单', value: total('gongdanPending'), unit: '单', tone: 'danger' }
  ]
})
const teamSummaryCards = computed(() => {
  const rows = overview.value.team || []
  const sessions = rows.reduce((sum, row) => sum + Number(row.usage?.sessions || 0), 0)
  const operations = rows.reduce((sum, row) => sum + Number(row.operations || 0), 0)
  const latest = rows.map(row => row.lastLogin).filter(Boolean).sort().at(-1)
  return [
    { label: '团队成员', value: rows.length, unit: '人' },
    { label: '期间登录', value: sessions, unit: '次' },
    { label: '期间操作', value: operations, unit: '次', tone: 'success' },
    { label: '最近登录', value: time(latest), tone: 'info' }
  ]
})

// 指标基线：主数值取平台全量（platformCounts），备注写本屏授权范围（counts）。
// 只给范围数时，主管专屏会显示「1 个大类 / 12 个指标」，容易被误读成平台底数（用户 2026-11-25 反馈）。
const platformCounts = computed(() => overview.value?.platformCounts || {})
const baselineValue = key => Number(platformCounts.value?.[key] ?? counts.value[key] ?? 0)
const baseline = computed(() => [
  { label: '接入系统', value: baselineValue('system'), note: `本屏可见 ${counts.value.system || 0}`, accent: 'primary' },
  { label: '指标大类', value: baselineValue('category'), note: `本屏可见 ${counts.value.category || 0}`, accent: 'primary' },
  { label: '指标小类', value: baselineValue('subcategory'), note: `本屏可见 ${counts.value.subcategory || 0}`, accent: 'primary' },
  { label: '指标数量', value: baselineValue('metric'), note: `本屏可见 ${counts.value.metric || 0}`, accent: 'primary' }
])
// 平台全量闭环口径（后端 platformClosure）：本屏授权范围常常不含工单/问题所在大类，
// 只给范围数会显示成「0 / 1 单已闭环」，容易被当成平台没有闭环（用户 2026-11-25 反馈）。
const platformClosure = computed(() => overview.value?.platformClosure || {})
const platformPendingOf = kind => {
  const source = platformClosure.value
  const value = kind === 'alarm' ? source.alarmPending : kind === 'issue' ? source.issuePending : source.gongdanPending
  return value == null ? null : Number(value)
}
const platformClosureText = computed(() => {
  const source = platformClosure.value
  if (source.gongdanCreated == null) return ''
  const created = Number(source.gongdanCreated || 0)
  const closed = Number(source.gongdanClosed || 0)
  const rate = created > 0 ? Math.round(closed * 100 / created) : 0
  const latest = source.gongdanLatest ? ` · 最近工单 ${time(source.gongdanLatest)}` : ''
  return `平台累计：工单 ${created} 单，已闭环 ${closed}（${rate}%）；待处理告警 ${source.alarmPending ?? 0} · 未解决问题 ${source.issuePending ?? 0}${latest}`
})
const todos = computed(() => ['alarm', 'issue', 'gongdan'].map((kind, index) => {
  const data = overview.value?.[kind] || {}
  return {
    kind,
    label: kind === 'alarm' ? '待处理告警' : kind === 'issue' ? '未解决问题' : '未闭环工单',
    pending: data.pending ?? 0,
    created: data.created ?? 0,
    completed: data.completed ?? 0,
    platformPending: platformPendingOf(kind),
    accent: ['danger', 'warning', 'success'][index]
  }
}))
const closureRate = computed(() => {
  const data = overview.value?.gongdan?.closure || {}
  return Number(data.created) > 0 && data.closed != null ? Math.min(100, Math.max(0, Math.round(Number(data.closed) * 100 / Number(data.created)))) : null
})

function selectTab (key) {
  if (key === activeTab.value || !visibleTabs.value.some(tab => tab.key === key)) return
  activeTab.value = key
  content.value?.scrollTo(0, 0)
  loadActivePanel()
  track('tab_changed', 'tab', key)
}
async function track (name, targetType, targetId) {
  if (!token.value) return
  try { await shareApi.event(publicId.value, token.value, { name, targetType, targetId, page: 'screen' }) } catch {}
}
function loadActivePanel () {
  loadPanel(panel.value)
  if (isLeader.value && activeTab.value === 'overview' && hasHealthPanel.value) {
    loadPanel('health')
  }
}
async function loadPanel (requestedPanel) {
  if (!summary.value || !token.value || !requestedPanel) return
  const range = days.value
  const key = `${rangeKey()}:${requestedPanel}`
  if (panelStates[key]?.loading || panelStates[key]?.data) return
  const state = reactive({ loading: true, data: null, error: '' })
  panelStates[key] = state
  try {
    state.data = await shareApi.panel(publicId.value, token.value, range, requestedPanel, windowParams())
  } catch {
    state.error = '栏目加载失败'
  } finally {
    state.loading = false
  }
}
async function loadSummary () {
  loading.value = true
  loadError.value = ''
  try {
    summary.value = await shareApi.panel(publicId.value, token.value, days.value, 'summary', windowParams())
    if (!visibleTabs.value.some(tab => tab.key === activeTab.value)) activeTab.value = visibleTabs.value[0]?.key || ''
    loadActivePanel()
  } catch {
    loadError.value = '专屏加载失败，请重试'
  } finally {
    loading.value = false
  }
}
function refresh () {
  if (loading.value || activeState.value?.loading) return
  for (const key of Object.keys(panelStates)) delete panelStates[key]
  loadSummary()
  track('refresh_triggered')
}
async function openMetric (item) {
  metricVisible.value = true
  metricLoading.value = true
  metricDetail.value = null
  metricError.value = ''
  metricTitle.value = item.metricName || '指标详情'
  const metricId = item.metricId ?? item.id
  track('metric_card_clicked', 'metric', String(metricId))
  try { metricDetail.value = await shareApi.metricDetail(publicId.value, token.value, metricId, days.value, windowParams()) } catch (e) { console.error(e); metricError.value = '指标详情加载失败，可能不在当前分享授权范围内' } finally { metricLoading.value = false }
}
async function openEvents (kind) {
  eventTitle.value = `${labelOf(kind)}明细`
  eventVisible.value = true
  eventLoading.value = true
  track('detail_opened', kind, kind)
  try {
    const data = await shareApi.eventRows(publicId.value, token.value, kind, 0, days.value, windowParams())
    eventRows.value = data.rows || []
    eventTotal.value = data.total || 0
  } catch (e) { eventRows.value = []; eventTotal.value = 0; console.error(e) } finally { eventLoading.value = false }
}
async function full () {
  try { await root.value?.requestFullscreen(); track('fullscreen_entered') } catch {}
}
onUnmounted(() => mobileMedia.removeEventListener?.('change', updateMobile))
onMounted(async () => {
  mobileMedia.addEventListener?.('change', updateMobile)
  const urlSecret = new URLSearchParams(location.hash.slice(1)).get('k')
  const secret = urlSecret || sessionStorage.getItem(secretKey)
  try {
    if (!publicId.value || !secret) throw new Error('missing share secret')
    const session = await shareApi.open(publicId.value, secret)
    token.value = session.token
    screen.value = { name: session.viewName }
    sessionStorage.setItem(secretKey, secret)
    await loadSummary()
    track('page_view')
  } catch (e) { error.value = true; loading.value = false; console.error(e) }
})
</script>

<style scoped>
.shared-screen { box-sizing: border-box; min-height: 100dvh; padding: 20px; background: var(--metric-canvas); color: var(--metric-text); }
.shared-screen:fullscreen { padding: 20px; overflow: auto; background: var(--metric-canvas); }
.shared-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.shared-header h1 { margin: 0; color: var(--metric-text); font-size: 22px; }
.shared-header small { display: block; margin-top: 5px; color: var(--metric-text-muted); font-size: 11px; }
.actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.share-tabs { display: flex; gap: 4px; overflow-x: auto; padding: 0 4px; margin-bottom: 12px; background: #fff; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); }
.share-tabs button { position: relative; padding: 10px 12px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--metric-text-muted); font-size: 12px; white-space: nowrap; cursor: pointer; }
.share-tabs button.active { border-bottom-color: var(--metric-primary); color: var(--metric-primary-hover); font-weight: 650; }
.share-section { display: grid; gap: 12px; margin-bottom: 14px; }
.share-sort { flex: none; }
.panel { padding: 16px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius-lg); background: #fff; box-shadow: var(--metric-shadow-xs); }
.panel header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.panel h3 { margin: 0; color: var(--metric-text); font-size: 15px; }
.panel header small { display: block; margin-top: 4px; color: var(--metric-text-muted); font-size: 10px; }
.kpi-layout { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr); gap: 12px; }
.kpi-grid { display: grid; gap: 8px; }
.kpi-grid.four { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.kpi-grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.kpi-grid.compact { margin-bottom: 12px; }
.todo-panel { background: linear-gradient(135deg, #fff 0%, var(--metric-warning-soft) 100%); }
.todo-card { text-align: left; font: inherit; display: grid; gap: 4px; padding: 11px; border: 1px solid var(--metric-border); border-radius: var(--metric-radius); background: #fff; cursor: pointer; }
.todo-card:hover { border-color: var(--metric-primary-border); box-shadow: var(--metric-shadow-sm); }
.todo-card span { color: var(--metric-text-secondary); font-size: 11px; }
.todo-card b { color: var(--metric-text); font-family: var(--metric-font-mono); font-size: 24px; line-height: 1; }
.todo-card small { color: var(--metric-text-muted); font-size: 9px; }
.todo-card.danger b { color: var(--metric-danger); }
.todo-card.warning b { color: var(--metric-warning); }
.todo-card.success b { color: var(--metric-success); }
.closure-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); align-items: center; gap: 16px; }
.gauge-box { display: grid; justify-items: center; gap: 4px; }
.gauge-box small { color: var(--metric-text-muted); font-size: 10px; }
.gauge-box .platform-line { max-width: 320px; text-align: center; line-height: 1.5; }
.dialog-hint { margin: 0 0 10px; color: var(--metric-text-muted); font-size: 11px; }
.metric-dialog { min-height: 100px; }
@media (max-width: 900px) { .shared-header { flex-direction: column; } .kpi-layout, .closure-layout { grid-template-columns: 1fr; } .kpi-grid.four, .kpi-grid.three { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.panel-status { min-height: 92px; display: flex; align-items: center; justify-content: center; gap: 8px; color: #52667f; font-size: 13px; }
@media (max-width: 680px) {
  .shared-screen {
    box-sizing: border-box;
    height: 100vh;
    height: 100dvh;
    min-height: 0;
    display: flex;
    flex-direction: column;
    padding: 0 env(safe-area-inset-right) 0 env(safe-area-inset-left);
    overflow: hidden;
    background: #f4f6f9;
    color: #172c43;
    overflow-wrap: anywhere;
  }
  /* 手机头部改两行：第一行专屏名，第二行预设 + 区间胶囊 + 刷新，避免标题被挤成 0 宽、按钮溢出屏外。 */
  .shared-header {
    flex: none; flex-direction: row; flex-wrap: wrap; align-items: center; column-gap: 8px; row-gap: 5px;
    margin: 0; padding: calc(3px + env(safe-area-inset-top)) 8px 5px;
    background: #fff; border-bottom: 1px solid #dce5ef;
  }
  .screen-title { flex: 1 1 100%; min-width: 0; }
  .actions { flex: 1 1 100%; gap: 6px; flex-wrap: nowrap; white-space: nowrap; }
  /* 区间入口与 el-date-picker 的区间框同口径：一个胶囊显示「开始 - 结束」，宽度不够就省略中间。 */
  .range-pill { min-width: 0; max-width: 40vw; justify-content: flex-start; gap: 3px; border-radius: 16px; }
  .range-pill-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .range-pill-icon { flex: none; font-size: 12px; color: #8fa3b8; }
  .shared-header h1 {
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    font-size: 14px; line-height: 20px;
  }
  .actions :deep(.el-radio-group) { flex-wrap: nowrap; }
  .actions :deep(.el-radio-button__inner), .actions :deep(.el-button) {
    min-height: 32px; display: inline-flex; align-items: center;
    padding: 0 7px; font-size: 11px;
  }
  .screen-content { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; padding: 6px 8px 0; }
  .share-section, .kpi-layout { gap: 6px; margin-bottom: 6px; }
  .panel { min-width: 0; padding: 8px 10px; border-radius: 7px; box-shadow: none; }
  .panel header { margin-bottom: 6px; }
  .panel h3 { font-size: 13px; }
  .panel header small { display: none; }
  .kpi-grid { gap: 3px; }
  .kpi-grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .kpi-grid.compact { margin-bottom: 5px; }
  .kpi-grid.four { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .kpi-grid :deep(.metric-kpi-card) { gap: 2px; min-height: 0; padding: 6px; box-shadow: none; }
  /* 指标基线卡片要显示「平台 N / 本屏可见 M」，手机端不能把 note 隐藏掉 */
  .kpi-grid :deep(.metric-bar), .kpi-grid :deep(.metric-kpi-card .note) { display: none; }
  .kpi-grid :deep(.metric-kpi-card .note) { display: block; font-size: 9px; line-height: 1.3; }
  .kpi-grid :deep(.metric-kpi-card .label) { font-size: 11px; }
  .kpi-grid :deep(.metric-kpi-card .value) { font-size: 16px; }
  .kpi-grid :deep(.metric-kpi-card .value-long) { font-size: 11px; }
  .todo-card { padding: 5px; gap: 2px; }
  .todo-card b { font-size: 17px; }
  .todo-card small { font-size: 10px; }
}
/* 自定义时间区间弹层：桌面用区间选择器，移动端用两个 datetime，避免双月日历超出屏宽 */
.range-picker-box { display: grid; gap: 6px; }
.range-picker { width: 100% !important; }
.range-picker-box :deep(.el-date-editor) { width: 100%; }
.range-hint { color: var(--metric-text-muted); font-size: 10px; line-height: 1.5; }
.range-error { color: var(--metric-danger); font-size: 10px; }
.range-btn.active { color: var(--metric-primary); border-color: var(--metric-primary-border); background: var(--metric-primary-soft); }
/* 移动端自定义区间底部面板：固定定位 + 挂 body，保证不被专屏容器裁剪；控件用原生 input。 */
.range-sheet-mask { position: fixed; inset: 0; z-index: 3000; display: flex; align-items: flex-end; background: #0b1c2e66; }
.range-sheet { display: grid; gap: 10px; width: 100%; padding: 14px 14px calc(14px + env(safe-area-inset-bottom)); border-radius: 14px 14px 0 0; background: #fff; box-shadow: 0 -8px 28px #172e4724; }
.range-sheet header { display: flex; align-items: center; justify-content: space-between; }
.range-sheet header strong { color: #172c43; font-size: 15px; }
.range-sheet-close { width: 30px; height: 30px; border: 0; border-radius: 50%; background: #f1f5f9; color: #64748b; font-size: 18px; line-height: 1; cursor: pointer; }
.range-sheet-field { display: grid; gap: 4px; color: #54657a; font-size: 12px; }
.range-native { width: 100%; min-height: 38px; padding: 0 10px; border: 1px solid #d7e0ec; border-radius: 8px; background: #fff; color: #172c43; font: inherit; font-size: 14px; }
.range-native:focus { border-color: var(--metric-primary); outline: none; }
.range-sheet-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
