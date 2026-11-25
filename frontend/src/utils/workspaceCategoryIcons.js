// 仅提供分类的展示图标；不参与指标分类、编码匹配或统计。
export function getWorkspaceCategoryIcon(category) {
  return ({
    external:'User', '对外部客户':'User', internal:'UserFilled', '对内部人员':'UserFilled',
    compliance:'CircleCheck', '合规管理':'CircleCheck', system:'Setting', '运行效率':'Setting',
    satisfactionMetrics:'ChatDotRound', satisfactionReport:'ChatDotRound', '满意度及投诉服务体验':'ChatDotRound',
    resourceInput:'Coin', '资源投入':'Coin', businessValue:'TrendCharts', '业务价值':'TrendCharts',
    plan:'Clock', '计划执行':'Clock', '数据质量':'DataAnalysis'
  })[category] || 'Grid'
}
