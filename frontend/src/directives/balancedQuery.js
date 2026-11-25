// 仅对显式标记的查询行生效；不改已验收的固定网格，也不移动 Vue 管理的节点。
// 说明：本指令会按容器宽度重新分配子控件宽度，因此必须避免"改子元素 → 容器宽度变化 →
// 再次重排"的自激循环。容器宽度每轮只变化零点几像素时（自身排版造成），一律忽略。
const states = new WeakMap()
const gap = 10
const SETTLE = 1.5
function metrics (element) {
  const buttons = element.matches('.filter-actions,.query-actions')
    ? [...element.querySelectorAll('.el-button')] : element.matches('.el-button') ? [element] : []
  if (buttons.length) return { element, min: buttons.reduce((n, b) => n + Math.max(68, b.textContent.trim().length * 13 + 26), 0) + (buttons.length - 1) * 8, weight: 0 }
  if (element.matches('.el-range-editor')) return { element, min: 260, weight: 1.8 }
  if (element.matches('.el-date-editor')) return { element, min: 170, weight: 1.2 }
  if (element.matches('.el-form-item')) return { element, min: element.querySelector('.el-range-editor') ? 340 : 220, weight: element.querySelector('.el-range-editor') ? 2 : 1.3 }
  if (element.matches('.query-field')) {
    const range = element.querySelector('.el-range-editor')
    return { element, min: range ? 340 : 200, weight: range ? 1.8 : 1.2 }
  }
  if (element.matches('.el-radio-group')) return { element, min: Math.min(380, Math.max(200, element.scrollWidth)), weight: 0 }
  if (element.matches('.el-select,.el-input') || element.querySelector('input')) {
    const caption = element.querySelector('input')?.placeholder || ''
    const wide = /名称|描述|关键词|内容|编号|批次/.test(caption)
    return { element, min: wide ? 175 : 140, weight: wide ? 1.4 : 1 }
  }
  return { element, min: Math.min(320, Math.max(100, element.scrollWidth)), weight: 0 }
}
const minimum = items => items.reduce((n, item) => n + item.min, 0) + Math.max(0, items.length - 1) * gap
function partition (items, width) {
  if (minimum(items) <= width) return [items]
  // 优先寻找两行都能完整放下且长度均衡的切分，避免第二行只剩查询按钮。
  let best = null, score = Infinity
  for (let i = 1; i < items.length; i++) {
    const first = items.slice(0, i), second = items.slice(i)
    if (minimum(first) > width || minimum(second) > width) continue
    const cost = Math.abs(minimum(first) - minimum(second)) + (second.length === 1 ? width : 0)
    if (cost < score) { best = [first, second]; score = cost }
  }
  if (best) return best
  const rows = []; let row = []
  for (const item of items) {
    if (row.length && minimum([...row, item]) > width) { rows.push(row); row = [] }
    row.push(item)
  }
  if (row.length) rows.push(row)
  return rows
}
function arrange (element, state) {
  const style = getComputedStyle(element)
  const boxWidth = element.clientWidth
  const width = boxWidth - parseFloat(style.paddingLeft || 0) - parseFloat(style.paddingRight || 0)
  if (width < 1) return
  state.boxWidth = boxWidth
  const children = [...element.children].filter(child => {
    if (child.matches('.toolbar-spacer')) { child.style.display = 'none'; return false }
    return getComputedStyle(child).display !== 'none' && child.getClientRects().length
  })
  const items = children.map(metrics)
  for (const row of partition(items, width)) {
    const extra = state.fill ? Math.max(0, width - minimum(row)) : 0
    const weights = row.reduce((n, item) => n + item.weight, 0)
    for (const item of row) {
      if (!state.original.has(item.element)) state.original.set(item.element, item.element.getAttribute('style'))
      const size = Math.max(0, Math.min(width, item.min + (weights ? extra * item.weight / weights : 0)) - 0.15)
      for (const [key, value] of Object.entries({ flex:`0 0 ${size}px`, width:`${size}px`, 'max-width':`${size}px`, 'min-width':'0', margin:'0', 'box-sizing':'border-box' })) {
        if (item.element.style.getPropertyValue(key) !== value) item.element.style.setProperty(key, value, 'important')
      }
    }
  }
}
export default {
  mounted (element, binding) {
    if (binding.value === false) return
    const state = { fill:binding.value?.fill !== false, frame:0, original:new Map(), boxWidth:null }
    state.schedule = () => {
      cancelAnimationFrame(state.frame)
      state.frame = requestAnimationFrame(() => arrange(element, state))
    }
    element.classList.add('balanced-query-row')
    // 只响应"外部"（窗口/父级）引起的尺寸变化：排版自身写回的宽度差通常只有零点几像素，
    // 且一定表现为容器略微变窄，直接忽略；否则容器宽度会跟着子控件一起收缩，
    // 表现为按钮持续漂移、忽大忽小。
    state.resize = new ResizeObserver(() => {
      const boxWidth = element.clientWidth
      if (state.boxWidth != null) {
        const delta = boxWidth - state.boxWidth
        if (delta <= 0 && delta > -6) return
        if (Math.abs(delta) < SETTLE) return
      }
      state.schedule()
    })
    state.resize.observe(element)
    state.mutation = new MutationObserver(state.schedule); state.mutation.observe(element, { childList:true })
    states.set(element, state); state.schedule()
  },
  updated (element, binding) {
    const state = states.get(element)
    if (state) { state.fill = binding.value?.fill !== false; state.schedule() }
  },
  beforeUnmount (element) {
    const state = states.get(element)
    if (!state) return
    state.resize.disconnect(); state.mutation.disconnect(); cancelAnimationFrame(state.frame)
    for (const [child, style] of state.original) {
      if (style == null) child.removeAttribute('style'); else child.setAttribute('style', style)
    }
    states.delete(element)
  }
}
