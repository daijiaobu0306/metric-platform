import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../layouts/Layout.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/session-expired',
    name: 'SessionExpired',
    component: () => import('../views/session-expired/index.vue'),
    meta: { title: '会话已过期' }
  },
  {
    path: '/screen/s/:publicId',
    name: 'SharedScreen',
    component: () => import('../views/dashboard-share/index.vue'),
    meta: { title: '专屏' }
  },
  {
    // PC 分享页：复用平台内专屏页面组件，只把取数换成匿名只读分享接口，
    // 所以分享出去的 PC 页面和平台内看到的专屏一致（页签、卡片、下钻都有）。
    path: '/screen/pc/s/:publicId',
    name: 'SharedScreenPc',
    component: () => import('../views/dashboard-view/index.vue'),
    meta: { title: '专屏' }
  },
  {
    // SSO 回调：后端 /api/sso/access-link 与 OAuth2 成功 handler 都 302 跳过来
    // 进入本路由即表示需要以登录态进入 target；本路由页会自动写入 sessionStorage 并跳转
    path: '/sso/callback',
    name: 'SsoCallback',
    component: () => import('../views/sso-callback/index.vue'),
    meta: { title: 'SSO 登录中' }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/observation',
    children: [
      { path: 'external/:id', name: 'ExternalPage', component: () => import('../views/ExternalPage.vue'), meta: { title: '外部页面' } },
      {
        path: 'observation',
        name: 'Observation',
        component: () => import('../views/observation/index.vue'),
        meta: { title: '运营观测', icon: 'DataLine' }
      },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/dashboard/index.vue'),
        meta: { title: '总览', icon: 'DataAnalysis' }
      },
      {
        path: 'observation-new',
        name: 'ObservationNew',
        component: () => import('../views/observation/index-new.vue'),
        meta: { title: '运营观测(新)', icon: 'DataLine' }
      },
      {
        path: 'metric',
        name: 'Metric',
        component: () => import('../views/metric/index.vue'),
        meta: { title: '指标体系配置', icon: 'TrendCharts' }
      },
      {
        path: 'alarm',
        name: 'Alarm',
        component: () => import('../views/alarm/index.vue'),
        meta: { title: '告警管理', icon: 'Bell' }
      },
      {
        path: 'gongdan',
        name: 'Gongdan',
        component: () => import('../views/gongdan/index.vue'),
        meta: { title: '工单管理', icon: 'Document' }
      },
      {
        path: 'issue',
        name: 'Issue',
        component: () => import('../views/issue/index.vue'),
        meta: { title: '问题管理', icon: 'Warning' }
      },
      {
        path: 'audit',
        name: 'Audit',
        component: () => import('../views/audit/index.vue'),
        meta: { title: '变更审计', icon: 'Monitor' }
      },
      {
        path: 'data',
        name: 'Data',
        component: () => import('../views/data/index.vue'),
        meta: { title: '数据汇聚', icon: 'Grid' }
      },
      {
        path: 'report',
        name: 'Report',
        component: () => import('../views/operation-report/index.vue'),
        meta: { title: '运营报表', icon: 'DataBoard' }
      },
      // 能力运营路由已临时禁用（2026-09-15 暂不需要）。恢复时取消下面这一块的注释 + Layout.vue 中对应的菜单项。
      // {
      //   path: 'ability-operation',
      //   name: 'AbilityOperation',
      //   component: () => import('../views/ability-operation/index.vue'),
      //   meta: { title: '能力运营', icon: 'Connection' }
      // },
      {
        path: 'config',
        name: 'Config',
        component: () => import('../views/config/index.vue'),
        meta: { title: '配置管理', icon: 'Setting' }
      },
      {
        path: 'dashboard-view/:viewCode?',
        name: 'DashboardView',
        component: () => import('../views/dashboard-view/index.vue'),
        meta: { title: '专屏视图', icon: 'Monitor' }
      },
      {
        path: 'script',
        name: 'Script',
        component: () => import('../views/script/index.vue'),
        meta: { title: '技能管理', icon: 'Code' }
      },

    ]
  }
]

const router = createRouter({
  history: createWebHistory('/metric-code/'),
  routes
})

// 路由守卫：未登录时跳转登录页（带 redirect 回原页面），已登录时检查菜单权限
router.beforeEach((to, from, next) => {
  const sessionKey = sessionStorage.getItem('sessionKey')
  const publicPaths = ['/login', '/session-expired', '/sso/callback']
  // createWebHistory 在不同代理/开发基路径下可能返回 /screen/... 或
  // /metric-code/screen/...；分享页（移动端 /screen/s/、PC 端 /screen/pc/s/）
  // 必须在两种形式下都绕过平台登录守卫。
  const isSharedScreen = /(^|\/)screen\/(pc\/)?s\/[A-Za-z0-9_-]+/.test(to.path)
    || /(^|\/)screen\/(pc\/)?s\/[A-Za-z0-9_-]+/.test(to.fullPath)

  if (publicPaths.includes(to.path) || isSharedScreen) {
    // 公开页面（登录、会话过期、SSO 回调）直接放行
    next()
  } else if (!sessionKey) {
    // 未登录：携带原目标路径的 redirect 参数；sso/callback 这种特殊路径不需带
    if (to.fullPath && to.fullPath !== '/' && !to.path.startsWith('/login')) {
      next({ path: '/login', query: { redirect: to.fullPath } })
    } else {
      next('/login')
    }
  } else if (to.path === '/') {
    next()
  } else {
    // 检查菜单权限（管理员跳过检查）
    const permissions = JSON.parse(sessionStorage.getItem('permissions') || '[]')
    const isAdmin = sessionStorage.getItem('isAdmin') === 'true'
    if (to.path.startsWith('/external/') || isAdmin || permissions.includes(to.path)
        || (to.path.startsWith('/dashboard-view/') && permissions.includes('/dashboard-view'))) {
      next()
    } else if (to.path === '/observation') {
      // 兜底页自身必须放行：否则「无权限 → next('/observation') → 再次进入守卫 → 仍无权限
      // → 再 next('/observation')」会无限重定向，直接卡死浏览器主线程。
      next()
    } else {
      next('/observation')
    }
  }
})

/**
 * 安全校验：判断一个路径是否可以作为安全 redirect 目标。
 * 必须在模块顶层（不依赖任何运行时状态），供其他页面调用。
 */
export const isSafeRedirect = (p) => {
  const safeRedirectPrefixes = [
    '/observation', '/dashboard', '/metric', '/alarm', '/gongdan',
    '/issue', '/audit', '/data', '/report',
    '/config', '/dashboard-view', '/script', '/'
  ]
  if (!p || typeof p !== 'string' || !p.startsWith('/')) return false
  if (p.startsWith('//')) return false
  if (p.startsWith('/sso/') || p === '/login' || p === '/session-expired') return false
  return safeRedirectPrefixes.some(prefix => p === prefix || p.startsWith(prefix + '/') || p.startsWith(prefix + '?'))
}

export default router
