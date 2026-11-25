/** 在图表重绘时读取 CSS 主题变量；图表不直接持有固定配色。 */
export function readWorkspaceChartTheme(element) {
  const style = getComputedStyle(element || document.documentElement)
  const read = (name) => style.getPropertyValue(`--ui-${name}`).trim()
  const primary = read('primary')
  const rgb = /^#([0-9a-f]{6})$/i.exec(primary)
  const channels = rgb ? [0, 2, 4].map(offset => parseInt(rgb[1].slice(offset, offset + 2), 16)).join(', ') : null
  return {
    primary,
    danger: read('danger'),
    text: read('text'),
    muted: read('muted'),
    border: read('border'),
    surface: read('surface'),
    areaStart: channels ? `rgba(${channels}, 0.3)` : primary,
    areaEnd: channels ? `rgba(${channels}, 0.03)` : 'transparent'
  }
}
