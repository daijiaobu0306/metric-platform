/**
 * v-mirror-scroll
 *
 * 给 <el-table> 顶部加一条与底部同步的横向滚动条，
 * 让用户不必去拖表格底部那条又细又靠下的滚动条。
 *
 * 行为：
 * 1. 在 el-table 头部正上方插入一个镜像 div
 * 2. 镜像 div 内部用 inner 撑出和 body 一样的 scrollWidth
 * 3. 镜像 ↔ body 双向联动 scrollLeft
 * 4. scrollWidth <= clientWidth 时镜像条自动隐藏（窄表不受影响）
 *
 * 用法：<el-table v-mirror-scroll :data="..."> ... </el-table>
 */

const STATE = '_mirrorScrollState'

export default {
  mounted(el) {
    // 等待 el-table 内部 DOM 渲染完成（v-if / keep-alive 场景）
    requestAnimationFrame(() => init(el))
  },
  updated(el) {
    // 数据更新导致列宽/行数变化时，重新同步 inner 宽度
    const state = el[STATE]
    if (state) state.syncWidth()
  },
  unmounted(el) {
    const state = el[STATE]
    if (state) state.cleanup()
  }
}

function init(el) {
  // 已初始化过则先清理
  const prev = el[STATE]
  if (prev) prev.cleanup()

  const body = el.querySelector('.el-table__body-wrapper')
  const header = el.querySelector('.el-table__header-wrapper')
  if (!body || !header) return

  // 顶部插入镜像条
  const mirror = document.createElement('div')
  mirror.className = 'el-table-mirror-scrollbar'
  const inner = document.createElement('div')
  inner.style.height = '1px'
  mirror.appendChild(inner)
  // 插在 .el-table__inner-wrapper 内的最前面（即表头上方）
  const insertTarget = header.parentNode
  insertTarget.insertBefore(mirror, header)

  let pending = false
  const syncWidth = () => {
    if (pending) return
    pending = true
    requestAnimationFrame(() => {
      pending = false
      const w = body.scrollWidth
      inner.style.width = w + 'px'
      const overflow = w > body.clientWidth + 1
      mirror.style.display = overflow ? 'block' : 'none'
      // 同步初始 scrollLeft
      mirror.scrollLeft = body.scrollLeft
    })
  }
  const onMirrorScroll = () => {
    if (body.scrollLeft !== mirror.scrollLeft) {
      body.scrollLeft = mirror.scrollLeft
    }
  }
  const onBodyScroll = () => {
    if (mirror.scrollLeft !== body.scrollLeft) {
      mirror.scrollLeft = body.scrollLeft
    }
  }

  mirror.addEventListener('scroll', onMirrorScroll, { passive: true })
  body.addEventListener('scroll', onBodyScroll, { passive: true })

  let ro1, ro2, mo
  if (typeof ResizeObserver !== 'undefined') {
    ro1 = new ResizeObserver(syncWidth)
    ro1.observe(body)
    const tbody = body.querySelector('.el-table__body')
    if (tbody) {
      ro2 = new ResizeObserver(syncWidth)
      ro2.observe(tbody)
    }
  } else {
    // Fallback: 监听 body 内部尺寸变化
    mo = new MutationObserver(syncWidth)
    mo.observe(body, { childList: true, subtree: true, attributes: true })
  }
  // 窗口大小变化时也同步
  window.addEventListener('resize', syncWidth)
  // 首次同步
  syncWidth()
  // 兜底：表格内容异步渲染时再同步几次
  setTimeout(syncWidth, 100)
  setTimeout(syncWidth, 500)

  el[STATE] = {
    cleanup() {
      mirror.remove()
      mirror.removeEventListener('scroll', onMirrorScroll)
      body.removeEventListener('scroll', onBodyScroll)
      if (ro1) ro1.disconnect()
      if (ro2) ro2.disconnect()
      if (mo) mo.disconnect()
      window.removeEventListener('resize', syncWidth)
    },
    syncWidth
  }
}
