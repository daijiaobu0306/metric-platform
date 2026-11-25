import axios from 'axios'

/**
 * PC 分享只读模式。
 *
 * <p>/screen/pc/s/:publicId 复用平台内专屏页面（同一个页面组件、同一套卡片和页签），
 * 只是把取数换成本文件的匿名分享接口：页面组件不用改，screenApi 按这里的状态自动切换。
 *
 * <p>分享访问不携带平台 X-Session-Key（匿名访客没有平台登录态），
 * 也单独建 axios 实例，这样不会触发全局 401 跳登录、也不会弹全局错误提示。
 */
const visitorRequest = axios.create({ baseURL: '/metric-code/api', timeout: 45000 })
const unwrap = promise => promise.then(response => {
  if (response.data?.code !== 200) return Promise.reject(new Error(response.data?.message || '请求失败'))
  return response.data.data
})

const state = { active: false, publicId: '', token: '' }

export const shareMode = {
  get active () { return state.active },
  get publicId () { return state.publicId },
  get token () { return state.token },

  /** 用 URL 里的密钥换一个 30 分钟访客会话；成功后页面所有取数都走分享接口。 */
  async open (publicId, secret) {
    const data = await unwrap(visitorRequest.post(
      `/dashboard-share/s/${encodeURIComponent(publicId)}/session`,
      null,
      { headers: { 'X-Share-Secret': secret } }
    ))
    if (!data?.token) throw new Error('分享会话创建失败')
    state.active = true
    state.publicId = publicId
    state.token = data.token
    return data
  },

  reset () {
    state.active = false
    state.publicId = ''
    state.token = ''
  },

  /** 分享模式下的只读 GET，统一带访客会话头；返回后端 data 载荷。 */
  get (path, params) {
    return unwrap(visitorRequest.get(
      `/dashboard-share/s/${encodeURIComponent(state.publicId)}${path}`,
      { params, headers: { 'X-Share-Session': state.token } }
    ))
  },

  /** 分享页行为埋点（与移动端分享页同一套事件名）。 */
  event (payload) {
    return unwrap(visitorRequest.post(
      `/dashboard-share/s/${encodeURIComponent(state.publicId)}/events`,
      payload,
      { headers: { 'X-Share-Session': state.token } }
    ))
  }
}