<template>
  <el-drawer class="workspace-theme dashboard-workspace" v-model="visible" title="选择模块类型" size="500px" direction="rtl">
    <div class="template-sections">
      <!-- 数据汇聚 -->
      <div class="template-section">
        <div class="section-title">
          <span class="section-icon">📊</span>
          <span>数据汇聚</span>
        </div>
        <div class="template-grid">
          <div v-for="t in dataTemplates" :key="t.type" class="template-card" :class="{ selected: selectedType === t.type }" @click="handleSelect(t)">
            <div class="template-icon">{{ t.icon }}</div>
            <div class="template-info">
              <div class="template-name">{{ t.label }}</div>
              <div class="template-desc">{{ t.description }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 运营观测 -->
      <div class="template-section">
        <div class="section-title">
          <span class="section-icon">📈</span>
          <span>运营观测</span>
        </div>
        <div class="template-grid">
          <div v-for="t in observationTemplates" :key="t.type" class="template-card" :class="{ selected: selectedType === t.type }" @click="handleSelect(t)">
            <div class="template-icon">{{ t.icon }}</div>
            <div class="template-info">
              <div class="template-name">{{ t.label }}</div>
              <div class="template-desc">{{ t.description }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 告警管理 -->
      <div class="template-section">
        <div class="section-title">
          <span class="section-icon">🔔</span>
          <span>告警管理</span>
        </div>
        <div class="template-grid">
          <div v-for="t in alarmTemplates" :key="t.type" class="template-card" :class="{ selected: selectedType === t.type }" @click="handleSelect(t)">
            <div class="template-icon">{{ t.icon }}</div>
            <div class="template-info">
              <div class="template-name">{{ t.label }}</div>
              <div class="template-desc">{{ t.description }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 工单管理 -->
      <div class="template-section">
        <div class="section-title">
          <span class="section-icon">📝</span>
          <span>工单管理</span>
        </div>
        <div class="template-grid">
          <div v-for="t in gongdanTemplates" :key="t.type" class="template-card" :class="{ selected: selectedType === t.type }" @click="handleSelect(t)">
            <div class="template-icon">{{ t.icon }}</div>
            <div class="template-info">
              <div class="template-name">{{ t.label }}</div>
              <div class="template-desc">{{ t.description }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 问题管理 -->
      <div class="template-section">
        <div class="section-title">
          <span class="section-icon">❓</span>
          <span>问题管理</span>
        </div>
        <div class="template-grid">
          <div v-for="t in issueTemplates" :key="t.type" class="template-card" :class="{ selected: selectedType === t.type }" @click="handleSelect(t)">
            <div class="template-icon">{{ t.icon }}</div>
            <div class="template-info">
              <div class="template-name">{{ t.label }}</div>
              <div class="template-desc">{{ t.description }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref } from 'vue'

const visible = ref(false)
const selectedType = ref('')

const emit = defineEmits(['select'])

// ===== 模板定义 =====
// 每个模板的 defaultConfig 包含:
// - dataSource.api: 对应后端API路径
// - dataSource.params: 默认筛选参数（可编辑）
// - display: 展示配置（列、卡片字段等）

const dataTemplates = [
  {
    type: 'data_aggregation',
    label: '数据汇聚',
    icon: '📊',
    description: '展示原始数据明细（表格）',
    defaultConfig: {
      type: 'data_aggregation',
      title: '数据汇聚',
      width: 700,
      height: 400,
      config: {
        dataSource: {
          api: '/metric-code/api/data/detail',
          params: {
            tableName: null,
            categoryName: null,
            subCategoryName: null,
            startDate: null,
            endDate: null
          }
        },
        display: {
          mode: 'table',
          pagination: true,
          pageSize: 20,
          columns: []
        }
      }
    }
  },
  {
    type: 'realtime_metric',
    label: '实时指标',
    icon: '⚡',
    description: '展示单个指标的当前值',
    defaultConfig: {
      type: 'realtime_metric',
      title: '实时指标',
      width: 300,
      height: 180,
      config: {
        dataSource: {
          api: '/metric-code/api/observation/realtime',
          params: {
            metricId: null
          }
        },
        display: {
          mode: 'card',
          valueField: 'value',
          suffix: ''
        }
      }
    }
  },
  {
    type: 'health_trend',
    label: '健康度趋势',
    icon: '📉',
    description: '展示健康度随时间变化趋势',
    defaultConfig: {
      type: 'health_trend',
      title: '健康度趋势',
      width: 500,
      height: 300,
      config: {
        dataSource: {
          api: '/metric-code/api/observation/category-health-trend',
          params: {
            categoryId: null,
            startDate: null,
            endDate: null
          }
        },
        display: {
          mode: 'line_chart',
          xField: 'date',
          yField: 'healthScore'
        }
      }
    }
  }
]

const observationTemplates = [
  {
    type: 'metric_trend',
    label: '指标值趋势',
    icon: '📈',
    description: '展示指标数值随时间变化趋势',
    defaultConfig: {
      type: 'metric_trend',
      title: '指标值趋势',
      width: 500,
      height: 300,
      config: {
        dataSource: {
          api: '/metric-code/api/observation/metric-value-trend',
          params: {
            metricId: null,
            startDate: null,
            endDate: null
          }
        },
        display: {
          mode: 'line_chart',
          xField: 'date',
          yField: 'value'
        }
      }
    }
  }
]

const alarmTemplates = [
  {
    type: 'alarm_list',
    label: '告警列表',
    icon: '🔔',
    description: '展示告警记录列表',
    defaultConfig: {
      type: 'alarm_list',
      title: '告警列表',
      width: 700,
      height: 400,
      config: {
        dataSource: {
          api: '/metric-code/api/alarm',
          params: {
            systemId: null,
            categoryId: null,
            metricId: null,
            alarmType: null,
            processStatus: null,
            shieldStatus: null,
            alarmTimeStart: null,
            alarmTimeEnd: null
          }
        },
        display: {
          mode: 'table',
          pagination: true,
          pageSize: 10,
          columns: [
            { prop: 'alarmTime', label: '告警时间', width: 160 },
            { prop: 'alarmContent', label: '告警内容' },
            { prop: 'processStatusText', label: '处理状态', width: 80 },
            { prop: 'systemName', label: '所属系统', width: 100 }
          ]
        }
      }
    }
  },
  {
    type: 'alarm_statistics',
    label: '告警统计',
    icon: '📊',
    description: '展示告警统计数据（总数/未处理/已处理）',
    defaultConfig: {
      type: 'alarm_statistics',
      title: '告警统计',
      width: 300,
      height: 180,
      config: {
        dataSource: {
          api: '/metric-code/api/alarm/statistics',
          params: {}
        },
        display: {
          mode: 'card',
          valueField: 'totalAlarmCount',
          suffix: '条'
        }
      }
    }
  },
  {
    type: 'alarm_trend',
    label: '告警趋势',
    icon: '📈',
    description: '展示告警数量随时间变化趋势',
    defaultConfig: {
      type: 'alarm_trend',
      title: '告警趋势',
      width: 500,
      height: 300,
      config: {
        dataSource: {
          api: '/metric-code/api/alarm/trend',
          params: {
            dimension: 'week',
            type: 'all'
          }
        },
        display: {
          mode: 'line_chart',
          xField: 'time',
          yField: 'count'
        }
      }
    }
  }
]

const gongdanTemplates = [
  {
    type: 'gongdan_list',
    label: '工单列表',
    icon: '📝',
    description: '展示工单记录列表',
    defaultConfig: {
      type: 'gongdan_list',
      title: '工单列表',
      width: 700,
      height: 400,
      config: {
        dataSource: {
          api: '/metric-code/api/gongdan',
          params: {
            systemId: null,
            categoryId: null,
            metricId: null,
            processStatus: null,
            startDate: null,
            endDate: null
          }
        },
        display: {
          mode: 'table',
          pagination: true,
          pageSize: 10,
          columns: [
            { prop: 'createTime', label: '创建时间', width: 160 },
            { prop: 'gongdanContent', label: '工单内容' },
            { prop: 'processStatusText', label: '处理状态', width: 80 }
          ]
        }
      }
    }
  },
  {
    type: 'gongdan_statistics',
    label: '工单统计',
    icon: '📊',
    description: '展示工单统计数据',
    defaultConfig: {
      type: 'gongdan_statistics',
      title: '工单统计',
      width: 300,
      height: 180,
      config: {
        dataSource: {
          api: '/metric-code/api/gongdan/statistics',
          params: {}
        },
        display: {
          mode: 'card',
          valueField: 'totalCount',
          suffix: '条'
        }
      }
    }
  }
]

const issueTemplates = [
  {
    type: 'issue_list',
    label: '问题列表',
    icon: '❓',
    description: '展示问题记录列表',
    defaultConfig: {
      type: 'issue_list',
      title: '问题列表',
      width: 700,
      height: 400,
      config: {
        dataSource: {
          api: '/metric-code/api/issue',
          params: {
            issueType: null,
            source: null,
            status: null,
            startDate: null,
            endDate: null
          }
        },
        display: {
          mode: 'table',
          pagination: true,
          pageSize: 10,
          columns: [
            { prop: 'createTime', label: '创建时间', width: 160 },
            { prop: 'description', label: '问题描述' },
            { prop: 'statusText', label: '状态', width: 80 }
          ]
        }
      }
    }
  },
  {
    type: 'issue_statistics',
    label: '问题统计',
    icon: '📊',
    description: '展示问题统计数据',
    defaultConfig: {
      type: 'issue_statistics',
      title: '问题统计',
      width: 300,
      height: 180,
      config: {
        dataSource: {
          api: '/metric-code/api/issue/statistics',
          params: {}
        },
        display: {
          mode: 'card',
          valueField: 'totalCount',
          suffix: '条'
        }
      }
    }
  }
]

const handleSelect = (template) => {
  selectedType.value = template.type
  emit('select', JSON.parse(JSON.stringify(template.defaultConfig)))
  visible.value = false
  selectedType.value = ''
}

const open = () => {
  visible.value = true
}

defineExpose({ open })
</script>

<style scoped>
.template-sections {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 0 8px;
}

.template-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: bold;
  color: #303133;
  padding-bottom: 4px;
  border-bottom: 1px solid #ebeef5;
}

.section-icon {
  font-size: 16px;
}

.template-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.template-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 2px solid transparent;
}

.template-card:hover {
  background: #e4e7ed;
  border-color: #c0c4cc;
}

.template-card.selected {
  background: #ecf5ff;
  border-color: #409eff;
}

.template-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  background: var(--ui-surface);
  border-radius: 8px;
  font-size: 20px;
  flex-shrink: 0;
}

.template-info {
  flex: 1;
  min-width: 0;
}

.template-name {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
  margin-bottom: 2px;
}

.template-desc {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
