/**
 * v-sticky-header (JS 版，已修复)
 *
 * 不用 CSS sticky（被 el-card/el-table 的 overflow:hidden 阻断），
 * 改用 position: fixed + JS 计算。
 *
 * 关键：findScrollContainer 必须正确选到 .el-main，不能选 .el-card__body
 * （之前的 bug：el-card__body 也有 overflow:auto，指令误选了它）
 */

const STATE = '_stickyHeaderState'
const CLS = 'el-table-sticky-header-active'

function findScrollContainer(el) {
  // 项目里页面级表格一定在 .el-main 里
  const elMain = document.querySelector('.el-main')
  if (elMain && elMain.contains(el)) {
    return elMain
  }
  // 弹窗里的表格：选最近的 .el-dialog__body
  const dialogBody = el.closest('.el-dialog__body')
  if (dialogBody) return dialogBody
  // 兜底
  return document.documentElement
}

export default {
  mounted(el, binding) {
    if (binding.value === false) return

    const header = el.querySelector('.el-table__header-wrapper')
    if (!header) return
    const scrollContainer = findScrollContainer(el)

    let placeholder = null
    let active = false
    let rafId = null

    function activate(tableRect, containerTop) {
      if (active) return
      active = true
      el.classList.add(CLS)
      const h = header.offsetHeight
      placeholder = document.createElement('div')
      placeholder.className = 'el-table-sticky-header-placeholder'
      placeholder.style.height = h + 'px'
      placeholder.style.flexShrink = '0'
      header.parentNode.insertBefore(placeholder, header)
      header.style.position = 'fixed'
      header.style.zIndex = '20'
      header.style.background = '#fff'
    }

    function deactivate() {
      if (!active) return
      active = false
      el.classList.remove(CLS)
      header.style.position = ''
      header.style.top = ''
      header.style.left = ''
      header.style.width = ''
      header.style.zIndex = ''
      header.style.background = ''
      if (placeholder && placeholder.parentNode) {
        placeholder.parentNode.removeChild(placeholder)
      }
      placeholder = null
    }

    function update() {
      rafId = null
      const tableRect = el.getBoundingClientRect()
      const containerRect = scrollContainer.getBoundingClientRect()
      const containerTop = containerRect.top

      if (tableRect.top < containerTop) {
        activate(tableRect, containerTop)
        if (active) {
          header.style.top = containerTop + 'px'
          header.style.left = tableRect.left + 'px'
          header.style.width = tableRect.width + 'px'
        }
      } else {
        deactivate()
      }
    }

    function onScroll() {
      if (rafId) return
      rafId = requestAnimationFrame(update)
    }

    scrollContainer.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll, { passive: true })

    update()
    setTimeout(update, 100)
    setTimeout(update, 500)

    el[STATE] = {
      cleanup() {
        scrollContainer.removeEventListener('scroll', onScroll)
        window.removeEventListener('scroll', onScroll)
        window.removeEventListener('resize', onScroll)
        if (rafId) cancelAnimationFrame(rafId)
        deactivate()
      },
      update
    }
  },
  updated(el) {
    el[STATE]?.update?.()
  },
  unmounted(el) {
    el[STATE]?.cleanup?.()
  }
}
