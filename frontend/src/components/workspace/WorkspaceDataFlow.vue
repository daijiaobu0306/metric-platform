<template>
    <!-- 平台数据流总览 -->
    <div class="dashboard-merged-section">
      <div class="dataflow-section">
        <div class="section-title">平台数据流总览 <small>数据采集、计算至流转闭环全生命周期拓扑链路</small><div v-if="dedicated" class="flow-overall-status"><WorkspaceStatusBadge>流水线状态：{{ pipelineStatus }}</WorkspaceStatusBadge><WorkspaceStatusBadge :tone="coverageRate >= 100 ? 'success' : 'muted'">计算覆盖：{{ coverageRate }}%</WorkspaceStatusBadge></div><div v-else class="flow-overall-status"><WorkspaceStatusBadge>流水线状态：采集 → 计算 → 处理</WorkspaceStatusBadge><WorkspaceStatusBadge :tone="scanHealth ? (scanHealth.healthy ? 'success' : 'danger') : 'muted'" :pulse="!!scanHealth && !scanHealth.healthy">{{ scanHealth ? (scanHealth.healthy ? '扫描目录检查正常' : `扫描目录异常 ${scanHealth.issueCount} 项`) : '管道健康度：待检测' }}</WorkspaceStatusBadge></div></div>

        <!-- 流程容器 - 4列等高布局 -->
        <div class="flow-container">
          <!-- 第1列：数据源 -->
          <div class="flow-col flow-col-1">
            <div class="step-label">数据源<span class="step-badge">{{ dataSources.length }} 个来源入口</span></div>
            <div class="flow-panel source-panel">
              <div class="flow-card source-card" :class="{ active: activeSource === source.name }" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" v-for="source in dataSources" :key="source.name" @click="emit('source-select', source.name)">
                <el-icon :size="18" class="source-icon">
                  <component :is="source.icon" />
                </el-icon>
                <span class="source-info"><span class="card-name">{{ source.name }}</span><small>{{ source.subtitle || '查看采集数据' }}</small></span><span class="source-connection" title="当前接口未提供此来源的实时连通检测结果">未检测<i></i></span>
              </div>
            <el-empty v-if="!dataSources.length" description="当前授权范围暂无系统" :image-size="48" /></div>
          </div>

          <!-- 箭头1 -->
          <div class="flow-arrow-cell"><button class="flow-link-label" type="button" @click="emit('navigate', '/data')">实时采集传输 <span>→</span></button>
            <div class="flow-arrow-pulse">
              <div class="arrow-head"></div>
            </div>
          </div>

          <!-- 第2列：数据入库 -->
          <div class="flow-col flow-col-2">
            <div class="step-label">数据入库<WorkspaceStatusBadge v-if="dedicated" tone="muted">授权范围统计</WorkspaceStatusBadge><WorkspaceStatusBadge v-else :tone="scanHealth?.healthy ? 'success' : 'muted'">{{ scanHealth ? (scanHealth.healthy ? '目录正常' : '目录异常') : '待检测' }}</WorkspaceStatusBadge></div>
            <div class="flow-panel import-panel">
              <template v-if="dedicated"><div v-for="card in importCards" :key="card.label" class="import-stat-card" role="button" tabindex="0" @click="emit('navigate', '/data')" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()"><div class="import-stat-icon"><el-icon :size="20"><component :is="card.icon" /></el-icon></div><div class="import-stat-info"><div class="import-stat-label">{{ card.label }}</div><div class="import-stat-row"><span class="import-stat-value">{{ card.value == null ? '--' : Number(card.value).toLocaleString() }}</span><span class="import-stat-unit">{{ card.unit }}</span></div></div></div><div class="import-panel-footer"><span>当前专屏授权范围</span><button type="button" @click="emit('navigate', '/data')">查看数据来源 &gt;</button></div></template><template v-else>              <div class="import-stat-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', '/data')">
                <div class="import-stat-icon">
                  <el-icon :size="20"><Coin /></el-icon>
                </div>
                <div class="import-stat-info">
                  <div class="import-stat-label">今日数据量</div>
                  <div class="import-stat-row">
                    <span class="import-stat-value">{{ importCount != null ? importCount.toLocaleString() : '--' }}</span>
                    <span class="import-stat-unit">条</span>
                  </div>
                </div>
              </div>
              <div class="import-stat-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', '/data')">
                <div class="import-stat-icon">
                  <el-icon :size="20"><Document /></el-icon>
                </div>
                <div class="import-stat-info">
                  <div class="import-stat-label">采集文件</div>
                  <div class="import-stat-row">
                    <span class="import-stat-value">{{ dashboardStats.fileCount ?? '--' }}</span>
                    <span class="import-stat-unit">个</span>
                  </div>
                </div>
              </div>
              <div class="import-stat-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', '/data')">
                <div class="import-stat-icon">
                  <el-icon :size="20"><Grid /></el-icon>
                </div>
                <div class="import-stat-info">
                  <div class="import-stat-label">数据表</div>
                  <div class="import-stat-row">
                    <span class="import-stat-value">{{ dashboardStats.tableCount ?? '--' }}</span>
                    <span class="import-stat-unit">张</span>
                  </div>
                </div>
              </div><div class="import-panel-footer"><span>{{ scanHealth ? (scanHealth.healthy ? '扫描目录：检查正常' : '扫描目录：存在异常') : '监听通道：状态待检测' }}</span><button type="button" @click="emit('navigate', '/data')">跳转数据汇聚 &gt;</button></div>
</template>            </div>
          </div>

          <!-- 箭头2 -->
          <div class="flow-arrow-cell"><button class="flow-link-label" type="button" @click="emit('navigate', '/report')">计算引擎载入 <span>→</span></button>
            <div class="flow-arrow-pulse">
              <div class="arrow-head"></div>
            </div>
          </div>

          <!-- 第3列：指标计算 + 健康度分析 -->
          <div class="flow-col flow-col-3">
            <div class="step-label">指标计算<WorkspaceStatusBadge>{{ metricCount > 0 ? '指标已纳管' : '待加载' }}</WorkspaceStatusBadge></div>
            <div class="flow-panel process-panel">
              <div class="flow-card process-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', '/report')">
                <el-icon :size="18"><Cpu /></el-icon>
                <span class="card-name">指标计算</span>
                <span class="card-stat">{{ metricCount }} 个</span><small class="process-caption">{{ dedicated ? '当前授权指标与计算结果' : '查看实时指标与计算结果' }}</small>
              </div>
              <div class="flow-connector-v">
                <div class="flow-arrow-pulse-v">
                  <div class="arrow-head-v"></div>
                </div>
              </div>
              <div class="flow-card process-card health-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', '/observation')">
                <el-icon :size="18"><CircleCheck /></el-icon>
                <span class="card-name">健康度分析</span>
                <span class="card-stat health-score">{{ healthChartCount }} 个</span><small class="process-caption">{{ dedicated ? '当前授权范围已产生健康度' : '系统、大类、小类及指标评估' }}</small>
              </div>
            </div>
          </div>

          <!-- 箭头3 -->
          <div class="flow-arrow-cell"><button class="flow-link-label" type="button" @click="emit('navigate', '/alarm')">告警工单流转 <span>→</span></button>
            <div class="flow-arrow-pulse">
              <div class="arrow-head"></div>
            </div>
          </div>

          <!-- 第4列：处理结果 -->
          <div class="flow-col flow-col-4">
            <div class="step-label">处理结果<span class="step-badge">闭环跟踪</span></div>
            <div class="flow-panel result-panel">
              <template v-if="dedicated"><div v-for="card in pendingCards" :key="card.path" class="result-stat-card" :class="card.className" role="button" tabindex="0" @click="emit('navigate', card.path)" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()"><div class="result-stat-left"><div class="result-stat-header"><el-icon :size="16"><component :is="card.icon" /></el-icon><span class="result-stat-title">{{ card.label }}</span><span class="result-state">{{ card.status }}</span></div><div class="result-stat-body"><div class="result-stat-sub">{{ card.caption }}</div><div class="result-stat-main">{{ card.value }}</div></div></div></div></template><template v-else>              <!-- 告警统计 -->
              <div class="result-stat-card alarm-stat-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', { path: '/alarm', query: { processStatus: 0 } })">
                <div class="result-stat-left">
                  <div class="result-stat-header">
                    <el-icon :size="16"><Bell /></el-icon>
                    <span class="result-stat-title">告警</span><span class="result-state">{{ !dashboardStats.totalAlarmCount ? '暂无告警' : dashboardStats.unprocessedCount > 0 ? '待处理' : '已处理' }}</span>
                  </div>
                  <div class="result-stat-body">
                    <div class="result-stat-sub">本月生成</div>
                    <div class="result-stat-main danger">{{ dashboardStats.totalAlarmCount || 0 }}</div>
                  </div>
                </div>
                <div class="result-stat-right">
                  <div class="result-footer-item">
                    <span class="footer-label">已处理</span>
                    <span class="footer-value">{{ alarmProcessedCount }}</span>
                  </div>
                  <div class="result-footer-item">
                    <span class="footer-label">处理率</span>
                    <span class="footer-value" :class="getDashboardRateClass(alarmProcessRate)">{{ alarmProcessRate }}%</span>
                  </div>
                </div>
              </div>
              <!-- 问题统计 -->
              <div class="result-stat-card issue-stat-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', { path: '/issue', query: { resolveStatus: '未解决' } })">
                <div class="result-stat-left">
                  <div class="result-stat-header">
                    <el-icon :size="16"><Warning /></el-icon>
                    <span class="result-stat-title">问题</span><span class="result-state">{{ !dashboardStats.issueCount ? '暂无问题' : issueResolveRate < 100 ? '待解决' : '已解决' }}</span>
                  </div>
                  <div class="result-stat-body">
                    <div class="result-stat-sub">本月登记</div>
                    <div class="result-stat-main warning">{{ dashboardStats.issueCount || 0 }}</div>
                  </div>
                </div>
                <div class="result-stat-right">
                  <div class="result-footer-item">
                    <span class="footer-label">已解决</span>
                    <span class="footer-value">{{ issueResolvedCount }}</span>
                  </div>
                  <div class="result-footer-item">
                    <span class="footer-label">解决率</span>
                    <span class="footer-value" :class="getDashboardRateClass(issueResolveRate)">{{ issueResolveRate }}%</span>
                  </div>
                </div>
              </div>
              <!-- 工单统计 -->
              <div class="result-stat-card gongdan-stat-card" role="button" tabindex="0" @keydown.enter="$event.currentTarget.click()" @keydown.space.self.prevent="$event.currentTarget.click()" @click="emit('navigate', { path: '/gongdan', query: { closedStatus: 0 } })">
                <div class="result-stat-left">
                  <div class="result-stat-header">
                    <el-icon :size="16"><Document /></el-icon>
                    <span class="result-stat-title">工单</span><span class="result-state">{{ !dashboardStats.gongdanCount ? '暂无工单' : gongdanCompleteRate < 100 ? '待闭环' : '全部闭环' }}</span>
                  </div>
                  <div class="result-stat-body">
                    <div class="result-stat-sub">本月创建</div>
                    <div class="result-stat-main primary">{{ dashboardStats.gongdanCount || 0 }}</div>
                  </div>
                </div>
                <div class="result-stat-right">
                  <div class="result-footer-item">
                    <span class="footer-label">已完成</span>
                    <span class="footer-value">{{ gongdanCompletedCount }}</span>
                  </div>
                  <div class="result-footer-item">
                    <span class="footer-label">完成率</span>
                    <span class="footer-value" :class="getDashboardRateClass(gongdanCompleteRate)">{{ gongdanCompleteRate }}%</span>
                  </div>
                </div>
              </div>
</template>            </div>
          </div>
        </div>
      </div>
    </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { errorImportApi } from '@/api'
import WorkspaceStatusBadge from '@/components/workspace/WorkspaceStatusBadge.vue'
const scanHealth = ref(null)
onMounted(async () => {
  if (props.dedicated) return
  try {
    const response = await errorImportApi.scanHealth()
    scanHealth.value = response.data
  } catch {
    scanHealth.value = null
  }
})

const props = defineProps({dedicated:Boolean,activeSource:String,pipelineStatus:String,coverageRate:Number,importCards:{type:Array,default:()=>[]},pendingCards:{type:Array,default:()=>[]},dataSources:Array,dashboardStats:Object,importCount:Number,metricCount:Number,healthChartCount:Number,alarmProcessedCount:Number,alarmProcessRate:Number,issueResolvedCount:Number,issueResolveRate:Number,gongdanCompletedCount:Number,gongdanCompleteRate:Number,getDashboardRateClass:Function})
const emit=defineEmits(['navigate','source-select'])
</script>
<style scoped src="./workspace-data-flow.css"></style>

<style scoped>.source-card.active { border-color:var(--ui-primary); box-shadow:0 0 0 2px var(--ui-primary-soft); }</style>
