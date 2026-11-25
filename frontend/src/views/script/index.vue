<!--
  ============================================================================
  技能管理
  ============================================================================
  2026-11-18 重构：原来的「文件管理 / 脚本管理 / 操作日志」三个页签已下线。
  它们是最早的脚手架（通用文件网盘、cron 脚本执行、操作流水），实际数据为空，
  和现在的用法（内网智能体通过 MCP 直接操作平台）也不是一条链路。

  现在只保留四块：
    1. 技能仓库   —— 平台内置 + 团队上传的技能包，统一下载、上传；
    2. 提示词管理 —— 纯文本提示词，随用随复制；
    3. staragent  —— 第三方智能体页面（平台签票免登录），作为技能的执行端；
    4. MCP 管理   —— 告警/工单/问题三个只读 MCP 服务的开关、访问令牌与调用日志。

  约定：
    - 技能包由内网智能体自己运行，平台只负责分发，不在平台里执行脚本；
    - MCP 服务内嵌在 backend 进程，随平台启动，Docker 部署不需要额外容器；
    - 内置技能的清单与打包规则在 backend 的 com.metric.skill（BuiltinSkillCatalog），
      与「指标体系配置 → 检查 AI 权重建议 → 下载 Skills」共用同一份来源。
  ============================================================================
-->
<template>
  <div class="skill-page workspace-theme">
    <WorkspacePageHeader
      title="技能管理"
      description="平台技能与常用提示词的统一入口：技能包下载后导入内网智能体即可使用，提示词随时复制。"
      badge="技能化运维"
      tone="success"
    />

    <div class="skill-workspace">
      <el-tabs v-model="activeTab" class="workspace-tabs">
        <el-tab-pane label="技能仓库" name="repository" lazy>
          <SkillRepository v-if="activeTab === 'repository'" />
        </el-tab-pane>
        <el-tab-pane label="提示词管理" name="prompts" lazy>
          <PromptManagement v-if="activeTab === 'prompts'" />
        </el-tab-pane>
        <el-tab-pane label="staragent" name="staragent" lazy>
          <StarAgentPanel v-if="activeTab === 'staragent'" />
        </el-tab-pane>
        <el-tab-pane label="MCP 管理" name="mcp" lazy>
          <McpManagement v-if="activeTab === 'mcp'" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import WorkspacePageHeader from '@/components/workspace/WorkspacePageHeader.vue'
import StarAgentPanel from '@/components/script/StarAgentPanel.vue'
import SkillRepository from './components/SkillRepository.vue'
import PromptManagement from './components/PromptManagement.vue'
import McpManagement from './components/McpManagement.vue'

// 默认落在技能仓库：页面一进来就能看到可用的技能，staragent 是 iframe，
// 放到有人点开时再加载，避免进页面就去连第三方内网服务。
const activeTab = ref('repository')
</script>

<style scoped>
.skill-page {
  margin: -15px;
  min-height: calc(100% + 30px);
  background: var(--ui-page);
  color: var(--ui-text);
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.skill-workspace {
  padding: 24px;
}
.skill-workspace :deep(.workspace-tabs > .el-tabs__header) {
  margin: 0 0 18px;
}

@media (max-width: 768px) {
  .skill-page {
    margin: -10px;
    min-height: calc(100% + 20px);
  }
  .skill-workspace {
    padding: 16px;
  }
}
</style>
