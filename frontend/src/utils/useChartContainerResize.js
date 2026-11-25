import { onMounted, onBeforeUnmount } from 'vue'

/** 跟随真实图表容器尺寸变化；懒加载与下钻新增容器也会被观察。 */
export function useChartContainerResize(root, getInstance) {
  let resizeObserver
  let mutationObserver
  let frame
  const observed = new Set()
  const scan = () => {
    frame = undefined
    const container = root.value
    if (!container || !resizeObserver) return
    for (const node of observed) {
      if (!container.contains(node)) {
        resizeObserver.unobserve(node)
        observed.delete(node)
      }
    }
    container.querySelectorAll('[id*="chart"]').forEach(node => {
      if (!observed.has(node)) {
        observed.add(node)
        resizeObserver.observe(node)
      }
    })
  }
  onMounted(() => {
    if (!root.value) return
    resizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        if (entry.contentRect.width > 0 && entry.contentRect.height > 0) {
          getInstance(entry.target)?.resize()
        }
      }
    })
    mutationObserver = new MutationObserver(() => {
      if (frame === undefined) frame = requestAnimationFrame(scan)
    })
    mutationObserver.observe(root.value, { childList: true, subtree: true })
    scan()
  })
  onBeforeUnmount(() => {
    resizeObserver?.disconnect()
    mutationObserver?.disconnect()
    if (frame !== undefined) cancelAnimationFrame(frame)
    observed.clear()
  })
}
