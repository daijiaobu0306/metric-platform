import { metricApi, subcategoryApi } from '../api'

// 小类名称来自指标体系配置；颜色只决定采集范围，不参与分类。
export async function loadRunningEfficiencySubcategories() {
  const categories = await metricApi.categoryListAll()
  const category = categories.data.find(item => item.categoryCode === 'system_run')
  if (!category) throw new Error('未找到运行效率大类配置')
  const response = await subcategoryApi.list(category.id)
  return [...new Set(response.data.filter(item => item.status === 'enabled')
    .map(item => item.subCategoryName))].map(name => ({ code: name, name }))
}
