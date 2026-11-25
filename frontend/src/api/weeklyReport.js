import request from './index'

export const weeklyReportApi = {
  preview: (params) => request.get('/report/weekly', { params, timeout: 60000 }),
  observations: (params) => request.get('/report/weekly/observations', { params, timeout: 60000 })
}
