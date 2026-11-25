<template>
  <div ref="readerRoot" class="weekly-reader" :class="{ expanded: expandedReading }">
    <div ref="toolbarRoot" class="reader-toolbar">
      <div><strong>周报阅读</strong><span class="toolbar-period">{{ periodLabel }}</span></div>
      <div class="toolbar-actions">
        <button type="button" class="download-button secondary" :aria-expanded="expandedReading" @click="toggleReading">{{ expandedReading ? '收起阅读' : '展开阅读' }}</button>
        <template v-if="!report">
          <a v-if="wordUrl" :href="wordUrl" download="运营周报.docx" class="download-button">下载 Word 原文</a>
          <a v-if="issueUrl" :href="issueUrl" download="问题跟踪表.xlsx" class="download-button secondary">下载问题跟踪表</a>
        </template>
      </div>
    </div>

    <div class="reader-layout">
      <aside class="reader-sidebar" aria-label="周报导航">
        <div class="sidebar-caption">{{ report ? '本期草稿' : '历史周报' }} <span>{{ report ? '未定稿' : '1 期' }}</span></div>
        <div class="selected-report" aria-current="true">
          <span class="report-year">{{ report ? '按实际采集数据生成' : '2026年 · 10月' }}</span>
          <strong>{{ report ? `${report.startDate.slice(5)} — ${report.endDate.slice(5)}` : '10.16 — 10.23' }}</strong>
          <span>端到端监控运营周报</span>
        </div>
        <div class="sidebar-caption contents-caption">本期目录</div>
        <nav>
          <template v-for="(section, index) in sections" :key="section.id">
            <button type="button" :class="{ active: activeSection === section.id || activeSection.startsWith(section.id + '-') }"
              :aria-current="activeSection === section.id ? 'location' : undefined" @click="goTo(section.id)">
              <span class="section-number">{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}
            </button>
            <template v-for="(block, blockIndex) in section.blocks" :key="blockIndex">
              <button v-if="block.type === 'subheading'" type="button" class="subsection-link"
                :class="{ active: activeSection === `${section.id}-${blockIndex}` }"
                :aria-current="activeSection === `${section.id}-${blockIndex}` ? 'location' : undefined"
                @click="goTo(`${section.id}-${blockIndex}`)">{{ block.text.replace(/[【】]/g, '') }}</button>
            </template>
          </template>
          <button v-if="!report" type="button" :class="{ active: activeSection === 'attachments' }" @click="goTo('attachments')">
            <span class="section-number">06</span>附件
          </button>
        </nav>

      </aside>

      <main ref="documentRoot" class="document-scroll" aria-label="周报正文">
        <article class="report-paper">
          <header class="document-heading">
            <span class="document-eyebrow">运营报表 · 周报</span>
            <h1>端到端监控运营周报</h1>
            <p>{{ periodLabel }}</p>
            <div v-if="report" class="document-meta"><span>{{ report.summary.systemCount }}个系统</span><span>草稿 · 未定稿</span></div>
            <div v-else class="document-meta"><span>业务系统：CRM</span><span>报告年度：2026年</span><span>历史文档 · 以本期 Word 为准</span></div>
          </header>

          <section v-for="(section, index) in sections" :key="section.id" :data-section="section.id" class="report-section">
            <h2><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}</h2>
            <template v-for="(block, blockIndex) in section.blocks" :key="blockIndex">
              <p v-if="block.type === 'paragraph'" class="report-paragraph">{{ block.text }}</p>
              <h3 v-else-if="block.type === 'subheading'" :data-section="`${section.id}-${blockIndex}`">{{ block.text.replace(/[【】]/g, '') }}</h3>
              <p v-else-if="block.type === 'attachment'" class="inline-attachment">{{ block.text }}<button type="button" @click="goTo('attachments')">查看本期附件 →</button></p>
              <div v-else-if="block.type === 'table'" class="report-table-wrap" tabindex="0" aria-label="指标情况表，可横向滚动" @scroll.passive="scheduleUpdate">
                <table :class="tableClass(section.id)">
                  <colgroup><col v-for="(width, column) in columnWidths(section.id)" :key="column" :style="{ width }"></colgroup>
                  <thead><tr><th v-for="(header, column) in block.headers" :key="column" scope="col">{{ header }}</th></tr></thead>
                  <tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                    <td v-for="(cell, column) in row" :key="column" :class="{ 'result-cell': column === row.length - 1, 'exceeded-cell': column === row.length - 1 && /超标/.test(cell) }">{{ cell }}</td>
                  </tr></tbody>
                </table>
              </div>
              <div v-else-if="block.type === 'metrics'" class="report-table-wrap" tabindex="0" aria-label="本期实际指标，可横向滚动" @scroll.passive="scheduleUpdate">
                <table class="live-metrics-table">
                  <colgroup><col style="width: 65%"><col style="width: 35%"></colgroup>
                  <thead><tr><th>指标名称</th><th>指标值（期内最近一次）</th></tr></thead>
                  <tbody><tr v-for="metric in block.metrics" :key="metric.metricId" :class="{ 'threshold-exceeded': matchesMetricThreshold(metric) }">
                    <td><button type="button" class="metric-name-link" title="查看指标规则和来源明细" @click="emit('view-observations', metric)">{{ metric.metricName }}</button></td>
                    <td class="metric-value" :title="matchesMetricThreshold(metric) ? `符合当前告警条件：${metric.alarmCondition} ${metric.alarmThreshold} ${metric.unit || ''}` : undefined">{{ metric.latestValue ?? '—' }}<span v-if="metric.latestValue != null && metric.unit" class="value-unit"> {{ metric.unit }}</span></td>
                  </tr></tbody>
                </table>
              </div>
            </template>
          </section>

          <section v-if="!report && (wordUrl || issueUrl)" data-section="attachments" class="report-section attachments-section">
            <h2><span>06</span>附件</h2>
            <a :href="wordUrl" download="运营周报10月16日-10月23日.docx" class="attachment-item">
              <span class="file-mark word-mark">W</span><span class="attachment-name"><strong>运营周报10月16日-10月23日.docx</strong><small>本期周报原始文档</small></span><span class="attachment-download">下载 ↓</span>
            </a>
            <a :href="issueUrl" download="系统运营体系-问题跟踪表.xlsx" class="attachment-item">
              <span class="file-mark excel-mark">X</span><span class="attachment-name"><strong>系统运营体系-问题跟踪表.xlsx</strong><small>本期提供的问题跟踪附件</small></span><span class="attachment-download">下载 ↓</span>
            </a>
            <p class="source-note">正文保留原文表述与数值；“系统运行”章节按现行分类展示为“运行效率”。原文提及的其他指标明细附件尚未提供。</p>
          </section>
          <details v-if="report" class="report-method">
            <summary>数据口径与编制说明</summary>
            <p v-for="(note, index) in report.notes" :key="index">{{ note }}</p>
            <p>红色标记仅表示当前展示值符合已配置的告警数值条件，不表示整周异常或已生成告警；未配置阈值或比较条件的指标不标记。</p>
            <p>生成时间：{{ displayTime(report.generatedAt) }}。本阶段尚未生成正式附件或归档版本。</p>
          </details>
          <footer class="document-footer">端到端监控运营周报 · {{ periodLabel }}</footer>
        </article>
      </main>
    </div>
    <button v-show="showBackToTop" type="button" class="back-to-top" @click="backToTop">↑ 返回顶部</button>
    <Teleport to="body">
      <div v-if="floatingHeader" class="weekly-floating-header" :style="floatingHeader.style" aria-hidden="true">
        <table :class="floatingHeader.tableClass" :style="{ width: floatingHeader.tableWidth + 'px', transform: `translateX(-${floatingHeader.scrollLeft}px)` }">
          <colgroup><col v-for="(width, index) in floatingHeader.widths" :key="index" :style="{ width: width + 'px' }"></colgroup>
          <thead><tr><th v-for="(label, index) in floatingHeader.labels" :key="index">{{ label }}</th></tr></thead>
        </table>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
// 示例周报正文与附件已移除（原为真实业务数据）；接入真实数据时替换此处的常量即可
const referenceSections: any[] = []
const wordUrl = ''
const issueUrl = ''
import { matchesMetricThreshold } from '@/utils/metricThreshold'

const props = defineProps({ report: { type: Object, default: null }, reportSections: { type: Array, default: null } })
const emit = defineEmits(['view-observations'])
const sections = computed(() => props.reportSections || referenceSections)
const periodLabel = computed(() => props.report ? `${props.report.startDate} — ${props.report.endDate}` : '2026年10月16日 — 10月23日')
const displayTime = (value) => value ? String(value).replace('T', ' ') : '—'
const readerRoot = ref(null)
const toolbarRoot = ref(null)
const documentRoot = ref(null)
const activeSection = ref('overview')
const expandedReading = ref(false)
const showBackToTop = ref(false)
const floatingHeader = ref(null)
let scrollRoot
let resizeObserver
let animationFrame
const tableClass = (id) => id === 'overview' ? 'summary-table' : id === 'internal' ? 'internal-table' : 'efficiency-table'
const columnWidths = (id) => id === 'overview' ? ['6%', '14%', '30%', '50%']
  : id === 'internal' ? ['16%', '16%', '30%', '8%', '14%', '16%'] : ['13%', '28%', '23%', '23%', '13%']
const readingTop = () => {
  const containerTop = scrollRoot?.getBoundingClientRect().top || 0
  if (window.matchMedia('(max-width: 768px)').matches) return containerTop
  const inset = scrollRoot ? parseFloat(getComputedStyle(scrollRoot).paddingTop) || 0 : 0
  return containerTop + inset + (toolbarRoot.value?.offsetHeight || 0)
}
const scrollToElement = (element) => {
  if (!element || !scrollRoot) return
  const top = element.getBoundingClientRect().top - readingTop() + scrollRoot.scrollTop
  scrollRoot.scrollTo({ top, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
}
const goTo = (id) => {
  scrollToElement(documentRoot.value?.querySelector(`[data-section="${id}"]`))
  activeSection.value = id
}
const backToTop = () => scrollToElement(readerRoot.value)
const toggleReading = async () => {
  expandedReading.value = !expandedReading.value
  await nextTick()
  scheduleUpdate()
}
const updateReading = () => {
  animationFrame = undefined
  const root = documentRoot.value
  if (!root || !scrollRoot) return
  readerRoot.value.style.setProperty('--toolbar-height', `${toolbarRoot.value.offsetHeight}px`)
  const containerTop = scrollRoot.getBoundingClientRect().top
  const toolbarTop = toolbarRoot.value.getBoundingClientRect().top
  const inset = parseFloat(getComputedStyle(scrollRoot).paddingTop) || 0
  // The application's scroll area has top padding. Cover that strip only once
  // the toolbar sticks, otherwise document rows remain visible above it.
  const isSticky = !window.matchMedia('(max-width: 768px)').matches && toolbarTop <= containerTop + inset + 1
  readerRoot.value.style.setProperty('--toolbar-top-gap', `${isSticky ? Math.max(0, toolbarTop - containerTop) + 1 : 0}px`)
  const top = readingTop()
  showBackToTop.value = readerRoot.value.getBoundingClientRect().top < top - 200
  let current = 'overview'
  for (const section of root.querySelectorAll('[data-section]')) {
    if (section.getBoundingClientRect().top <= top + 48) current = section.dataset.section
  }
  activeSection.value = current
  let header = null
  for (const wrapper of root.querySelectorAll('.report-table-wrap')) {
    const table = wrapper.querySelector('table')
    const rect = table.getBoundingClientRect()
    const head = table.querySelector('thead')
    const height = head.getBoundingClientRect().height
    if (rect.top < top && rect.bottom > top + height) {
      const bounds = wrapper.getBoundingClientRect()
      header = {
        style: { top: `${top}px`, left: `${bounds.left + 1}px`, width: `${wrapper.clientWidth}px` },
        widths: [...head.querySelectorAll('th')].map(cell => cell.getBoundingClientRect().width),
        labels: [...head.querySelectorAll('th')].map(cell => cell.textContent),
        tableWidth: rect.width, scrollLeft: wrapper.scrollLeft, tableClass: table.className
      }
      break
    }
  }
  floatingHeader.value = header
}
const scheduleUpdate = () => {
  if (!animationFrame) animationFrame = requestAnimationFrame(updateReading)
}
onMounted(() => {
  // Use the application's existing scroll area so the report adds no vertical scrollbar.
  scrollRoot = readerRoot.value.closest('.el-main') || document.scrollingElement
  scrollRoot.addEventListener('scroll', scheduleUpdate, { passive: true })
  window.addEventListener('resize', scheduleUpdate, { passive: true })
  resizeObserver = new ResizeObserver(scheduleUpdate)
  resizeObserver.observe(readerRoot.value)
  resizeObserver.observe(toolbarRoot.value)
  scheduleUpdate()
})
onBeforeUnmount(() => {
  scrollRoot?.removeEventListener('scroll', scheduleUpdate)
  window.removeEventListener('resize', scheduleUpdate)
  resizeObserver?.disconnect()
  if (animationFrame) cancelAnimationFrame(animationFrame)
})
</script>

<style scoped>
.weekly-reader { color: #303844; --toolbar-height: 68px; }
.reader-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; flex-wrap: wrap; position: sticky; top: 0; z-index: 25; background: #fff; border-bottom: 1px solid #e8ecf2; min-height: 44px; }
.reader-toolbar::before { content: ''; position: absolute; bottom: 100%; left: 0; right: 0; height: var(--toolbar-top-gap, 0px); background: #fff; pointer-events: none; }
.reader-toolbar strong { font-size: 16px; }
.toolbar-period { color: #7c8694; font-size: 13px; margin-left: 18px; }
.toolbar-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.download-button { text-decoration: none; background: #386bea; color: white; border: 1px solid #386bea; border-radius: 6px; padding: 8px 14px; font-size: 13px; cursor: pointer; font-family: inherit; line-height: 20px; }
.download-button:hover { background: #295bd6; }
.download-button.secondary { color: #526174; background: white; border-color: #dce2ea; }
.reader-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: 24px; align-items: start; padding-top: 24px; }
.reader-sidebar { padding: 8px 0; background: #fff; position: sticky; top: calc(var(--toolbar-height) + 16px); }
.sidebar-caption { display: flex; justify-content: space-between; color: #717d8d; font-size: 12px; letter-spacing: 1px; padding: 0 8px; }
.sidebar-caption span { color: #a0a8b4; letter-spacing: 0; }
.selected-report { margin-top: 14px; padding: 16px 12px; border: 1px solid #dce6fc; background: #f1f5ff; border-radius: 6px; display: flex; flex-direction: column; gap: 8px; color: #6b7a93; font-size: 12px; }
.selected-report strong { font-size: 17px; color: #315fba; letter-spacing: .5px; }
.report-year { font-size: 11px; }
.contents-caption { margin: 32px 0 12px; }
nav { display: flex; flex-direction: column; gap: 4px; }
nav button { border: none; background: transparent; text-align: left; color: #627084; padding: 11px 10px; border-radius: 5px; cursor: pointer; font: inherit; font-size: 13px; }
nav button:hover, nav button.active { background: #edf3ff; color: #3465ca; }
.section-number { font-size: 11px; color: #9aa7bb; margin-right: 12px; }
.sidebar-note { border-top: 1px solid #edf0f5; padding: 20px 8px 0; margin-top: 32px; color: #98a1ae; font-size: 12px; line-height: 1.9; }
.document-scroll { min-width: 0; }
.report-paper { max-width: 1320px; margin: auto; background: white; padding: 24px 16px; }
.document-heading { text-align: center; padding-bottom: 28px; border-bottom: 2px solid #34465d; margin-bottom: 34px; }
.document-eyebrow { font-size: 11px; color: #8c97a7; letter-spacing: 3px; }
h1 { font-size: 28px; font-weight: 600; letter-spacing: 2px; margin: 15px 0 12px; color: #243348; line-height: 1.5; }
.document-heading > p { font-size: 15px; color: #6c7786; margin: 0 0 22px; }
.document-meta { display: flex; justify-content: center; gap: 24px; font-size: 12px; color: #8a94a2; flex-wrap: wrap; }
.report-section { margin-bottom: 34px; }
h2 { font-size: 18px; color: #27364a; margin: 0 0 18px; display: flex; gap: 12px; align-items: center; }
h2 > span { color: #5f7fae; font-size: 13px; font-weight: 500; }
h3 { font-size: 15px; color: #35465d; margin: 24px 0 12px; border-left: 3px solid #8097b8; padding-left: 10px; }
.report-paragraph { font-size: 14px; line-height: 2; margin: 10px 0 16px; white-space: pre-wrap; overflow-wrap: anywhere; }
.report-table-wrap { overflow-x: auto; border: 1px solid #dfe5ed; border-radius: 4px; margin: 16px 0; }
table { border-collapse: separate; border-spacing: 0; width: 100%; font-size: 13px; line-height: 1.8; text-align: left; table-layout: fixed; }
table.summary-table { min-width: 660px; }
table.internal-table { min-width: 860px; }
table.efficiency-table { min-width: 760px; }
table.live-metrics-table { min-width: 440px; }
.threshold-exceeded .metric-name-link, .threshold-exceeded .metric-value { color: #c44d41; font-weight: 600; }
.threshold-exceeded .value-unit { color: inherit; }
.metric-name-link { border: none; padding: 0; background: none; color: inherit; font: inherit; text-align: left; cursor: pointer; line-height: inherit; }
.metric-name-link:hover { color: #386bea; text-decoration: underline; }
.value-unit { color: #7b8797; font-size: 12px; }
.report-method { color: #8a94a2; font-size: 12px; line-height: 1.9; margin: 24px 0; }
.report-method summary { cursor: pointer; }
.metric-code { font-family: monospace; font-size: 12px; }
.cell-note { display: block; color: #8a94a2; font-size: 11px; margin-top: 5px; line-height: 1.7; }
.detail-link { border: 0; background: transparent; color: #386bea; font: inherit; cursor: pointer; padding: 0; }
th { background: #f2f5fa; color: #465773; font-weight: 600; padding: 11px 12px; border-bottom: 1px solid #dfe5ed; white-space: pre-line; overflow-wrap: anywhere; }
td { padding: 10px 12px; border-bottom: 1px solid #e8ecf2; white-space: pre-wrap; overflow-wrap: anywhere; vertical-align: top; }
th + th, td + td { border-left: 1px solid #e8ecf2; }
tr:last-child td { border-bottom: none; }
tbody tr:nth-child(even) { background: #fbfcfe; }
th:first-child { min-width: 54px; }
.detail-table th:nth-child(2) { min-width: 105px; }
.detail-table th:nth-child(3) { min-width: 130px; }
.detail-table th:last-child { min-width: 88px; }
.exceeded-cell { color: #c44d41; }
.inline-attachment { color: #909baa; font-size: 12px; margin-top: 12px; }
.inline-attachment button { background: transparent; border: none; font: inherit; color: #486fba; cursor: pointer; padding: 4px; }
.attachment-item { display: flex; gap: 14px; align-items: center; padding: 15px; border: 1px solid #e3e8f0; border-radius: 6px; margin-bottom: 10px; text-decoration: none; color: inherit; }
.attachment-item:hover { background: #f8faff; border-color: #b9cbed; }
.file-mark { display: grid; place-items: center; width: 34px; height: 40px; border-radius: 4px; font-size: 19px; font-weight: bold; flex-shrink: 0; }
.word-mark { color: #3664ae; background: #eaf1fc; }
.excel-mark { color: #3b8762; background: #eaf6ef; }
.attachment-name { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.attachment-name strong { font-size: 13px; font-weight: 500; overflow-wrap: anywhere; }
.attachment-name small { font-size: 11px; color: #929cab; }
.attachment-download { margin-left: auto; color: #5576b0; white-space: nowrap; font-size: 12px; }
.source-note { color: #949eac; font-size: 11px; line-height: 1.9; margin-top: 16px; }
.document-footer { border-top: 1px solid #e8ecf2; color: #a0a8b4; text-align: center; font-size: 11px; padding-top: 20px; }
button:focus-visible, a:focus-visible, [tabindex]:focus-visible { outline: 2px solid #6089df; outline-offset: 2px; }
@media (max-width: 1100px) {
  .reader-layout { grid-template-columns: 180px minmax(0, 1fr); }
  .reader-layout { gap: 16px; }
  .report-paper { padding: 24px 0; }
}
@media (max-width: 768px) {
  .reader-layout { grid-template-columns: minmax(0, 1fr); }
  .reader-sidebar { position: static; padding: 0; border-bottom: 1px solid #e4e8ef; }
  .sidebar-caption, .sidebar-note, .selected-report { display: none; }
  nav { flex-direction: row; overflow-x: auto; }
  nav button { flex-shrink: 0; padding: 8px; }
  .section-number { display: none; }
  .document-scroll { padding: 0; }
  .report-paper { padding: 26px 16px; }
  h1 { font-size: 22px; letter-spacing: 1px; }
  .document-meta { gap: 10px; }
  .toolbar-period { display: block; margin: 8px 0 0; }

  .attachment-item { padding: 12px; gap: 10px; }
}
.expanded .reader-layout { grid-template-columns: minmax(0, 1fr); }
.expanded .reader-sidebar { display: none; }
.expanded .report-paper { max-width: 1320px; }
nav .subsection-link { padding: 7px 10px 7px 35px; font-size: 12px; }
.back-to-top { position: fixed; bottom: 28px; right: 30px; z-index: 30; background: #fff; color: #486fba; border: 1px solid #dce2ea; border-radius: 6px; padding: 10px 16px; box-shadow: 0 3px 12px #26375112; cursor: pointer; }
.weekly-floating-header { position: fixed; overflow: hidden; pointer-events: none; z-index: 24; box-shadow: 0 2px 4px #26375112; background: white; }
.weekly-floating-header table { min-width: 0; margin: 0; }
@media (max-width: 768px) {
  .reader-toolbar { position: static; }
  nav .subsection-link { padding: 8px; }
  .reader-layout { padding-top: 12px; }
  .back-to-top { bottom: 16px; right: 16px; }
}
</style>
