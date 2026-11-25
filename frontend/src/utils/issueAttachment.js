// 问题页及告警关联问题弹窗共用附件入口，沿用现有预览/下载约定。
export function openIssueAttachment(row) {
  if (!row?.attachmentName || row.id == null) return
  const canPreview = /\.(png|jpg|jpeg|gif|bmp|webp|svg|pdf)$/i.test(row.attachmentName)
  const url = `/metric-code/api/issue/${row.id}/attachment${canPreview ? '?inline=true' : ''}`
  window.open(url, '_blank')
}
