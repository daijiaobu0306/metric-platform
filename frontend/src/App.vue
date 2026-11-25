<template>
  <el-config-provider :locale="locale">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import { ref } from 'vue'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const locale = ref(zhCn)
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  width: 100%;
  height: 100%;
  overflow-x: hidden;
}

#app {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  width: 100%;
  height: 100%;
}

/* 全局响应式样式 */
@media screen and (max-width: 768px) {
  .el-col-xs-24 {
    width: 100%;
  }

  .el-table {
    font-size: 12px !important;
  }

  .el-table__header th,
  .el-table__body td {
    padding: 8px 0;
  }

  .el-dialog {
    width: 90% !important;
    margin: 10vh auto !important;
  }

  .el-form-item {
    margin-bottom: 12px;
  }

  .el-form-item__label {
    font-size: 12px;
  }

  .el-input__inner,
  .el-select__wrapper {
    font-size: 12px;
  }

  .el-button {
    padding: 8px 12px;
    font-size: 12px;
  }

  .el-card__header {
    padding: 12px 15px;
  }

  .el-card__body {
    padding: 15px;
  }

  .el-pagination {
    padding: 10px 0;
    justify-content: center;
  }

  .el-pagination__total {
    display: none;
  }
}

@media screen and (max-width: 480px) {
  .el-col-xs-12 {
    width: 100%;
  }

  .el-dialog {
    width: 95% !important;
    margin: 5vh auto !important;
  }

  .el-message-box {
    width: 90% !important;
  }
}

/* 开启横向滚动条，配合 v-mirror-scroll 指令在表头附近生成镜像条 */
.el-table__body-wrapper {
  overflow-x: auto;
}

/* ============================================
 * 横向滚动条加粗 + 常驻显示
 * 默认 ~8px 且仅滚动时出现，命中区域太小
 * 改为 12px 高、一直显示，hover 加深
 * ============================================ */
.el-table__body-wrapper::-webkit-scrollbar,
.el-table__header-wrapper::-webkit-scrollbar,
.el-table__footer-wrapper::-webkit-scrollbar {
  height: 12px;
  background: #f5f7fa;
}
.el-table__body-wrapper::-webkit-scrollbar-thumb,
.el-table__header-wrapper::-webkit-scrollbar-thumb,
.el-table__footer-wrapper::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 6px;
  border: 2px solid #f5f7fa;
  min-width: 30px;
}
.el-table__body-wrapper::-webkit-scrollbar-thumb:hover,
.el-table__header-wrapper::-webkit-scrollbar-thumb:hover,
.el-table__footer-wrapper::-webkit-scrollbar-thumb:hover {
  background: #909399;
}

/* ============================================
 * 顶部镜像滚动条（v-mirror-scroll 指令注入）
 * 作用：让横向滚动条不止在底部，还复制一份到表头正上方
 * 视觉上：与底部滚动条同样的外观，自身不显示滚动条（避免重复）
 * ============================================ */
.el-table-mirror-scrollbar {
  overflow-x: auto;
  overflow-y: hidden;
  height: 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
}
.el-table-mirror-scrollbar::-webkit-scrollbar {
  height: 0;
  display: none;
}

/* ============================================
 * 表头页面级 sticky（v-sticky-header 指令，JS 方式）
 *
 * 不用 CSS sticky（被 el-card/el-table 的 overflow:hidden 阻断），
 * 改用 position: fixed + JS 计算。CSS 这一段只负责"激活时"的视觉。
 * ============================================ */

/* 激活态：表头变成 position: fixed，加阴影 */
.el-table-sticky-header-active .el-table__header-wrapper {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

/* ============================================
 * 桌面端紧凑表格（提升列表信息密度）
 * 字号 14→13px，行内边距 8→4，左右 8→6
 * 移动端 @media (max-width: 768px) 已有 12px 设置，优先级更高
 * ============================================ */
.el-table {
  font-size: 13px;
  width: 100% !important;
}
.el-table__header th,
.el-table__body td {
  padding: 4px 0 !important;
}
.el-table .cell {
  padding: 0 6px !important;
}
.el-table th.el-table__cell > .cell {
  font-weight: 600;  /* 表头略加粗，区分表头与数据行 */
}

/* ============================================
 * wrap-cell：内容型单元格自动换行
 * 用途：描述/内容/备注/工单内容等长文本列
 * 用法：<el-table-column class-name="wrap-cell" prop="..." />
 * ============================================ */
.el-table td.wrap-cell .cell {
  white-space: normal !important;
  word-break: break-all;
  line-height: 1.45;
  padding-top: 6px;
  padding-bottom: 6px;
}

/* 确保表格行高自适应内容，防止多行文本重叠 */
.el-table .el-table__body-wrapper .el-table__row td {
  height: auto !important;
}
.el-table .el-table__body-wrapper .el-table__row {
  height: auto !important;
}
</style>
