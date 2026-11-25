import { ref } from 'vue'

const STORAGE_KEY = 'metric_dialog_sizes'
let _cache = null

function loadCache() {
  if (_cache) return _cache
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    _cache = saved ? JSON.parse(saved) : {}
  } catch (e) { _cache = {} }
  return _cache
}

function saveToStorage(sizes) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(sizes)) } catch (e) {}
}

export function useDialogSize(dialogKey, defaults = {}) {
  const width = ref(defaults.width || '1000px')

  const cache = loadCache()
  if (cache[dialogKey]?.width) {
    // 如果默认值变了，清除旧缓存（比如 900px → 1100px）
    const cachedWidth = parseInt(cache[dialogKey].width)
    const defaultWidth = parseInt(defaults.width || '1000px')
    if (Math.abs(cachedWidth - defaultWidth) > 100) {
      delete cache[dialogKey]
      saveToStorage(cache)
    } else {
      width.value = cache[dialogKey].width
    }
  }

  const save = () => {
    const c = loadCache()
    c[dialogKey] = { width: width.value, updatedAt: Date.now() }
    saveToStorage(c)
  }

  /**
   * 在弹窗右下角添加拖拽手柄
   * 在 @opened 事件回调中调用
   */
  const bindResize = () => {
    // 方式1: custom-class 加到 .el-dialog 上
    let dialogEl = document.querySelector(`.el-dialog.${dialogKey}`)

    // 方式2: 兜底 — 找当前可见 overlay 里的 .el-dialog
    if (!dialogEl) {
      const overlays = document.querySelectorAll('.el-overlay')
      for (const ov of overlays) {
        if (ov.style.display !== 'none') {
          dialogEl = ov.querySelector('.el-dialog')
          if (dialogEl) break
        }
      }
    }

    if (!dialogEl) {
      console.warn(`[useDialogSize] dialog not found: ${dialogKey}`)
      return null
    }

    // 移除旧手柄
    dialogEl.querySelector('.dialog-resize-handle')?.remove()

    // 确保弹窗 position: relative
    if (getComputedStyle(dialogEl).position === 'static') {
      dialogEl.style.position = 'relative'
    }

    // 创建拖拽手柄
    const handle = document.createElement('div')
    handle.className = 'dialog-resize-handle'
    handle.innerHTML = '⤡'
    dialogEl.appendChild(handle)

    // 拖拽逻辑
    let startX, startW, dragging = false

    const onMove = (e) => {
      if (!dragging) return
      const newW = Math.max(400, startW + (e.clientX - startX))
      dialogEl.style.width = newW + 'px'
      dialogEl.style.maxWidth = 'none'
    }

    const onUp = () => {
      if (!dragging) return
      dragging = false
      width.value = dialogEl.style.width
      save()
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }

    handle.addEventListener('mousedown', (e) => {
      dragging = true
      startX = e.clientX
      startW = dialogEl.offsetWidth
      e.preventDefault()
      document.addEventListener('mousemove', onMove)
      document.addEventListener('mouseup', onUp)
      document.body.style.cursor = 'nwse-resize'
      document.body.style.userSelect = 'none'
    })

    return () => {
      handle.remove()
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
    }
  }

  return { width, bindResize, save }
}
