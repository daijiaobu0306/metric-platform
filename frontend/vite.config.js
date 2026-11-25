import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  base: '/metric-code/',
  // 保留旧 hash 资源，使发布前已打开的页面仍能加载后续懒加载模块。
  // 但打部署包时必须清空（VITE_CLEAN_DIST=1），否则历次构建产物会全进包。
  build: { emptyOutDir: process.env.VITE_CLEAN_DIST === '1' },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 28080,
    host: '0.0.0.0',
    proxy: {
      '/metric-code/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/metric-code/, ''),
        // fix(sso): 让后端看到原始 Host（localhost:28080）+ 正确端口，否则 Spring
        // 拼出的 OAuth2 redirect_uri 是 :18080，IdP 白名单不匹配 → silent SSO 失败。
        // 配合 application.yml 的 server.forward-headers-strategy: framework 生效。
        preserveHostHeader: true,
        headers: {
          'X-Forwarded-Proto': 'http',
          'X-Forwarded-Port': '28080'
        }
      },
      // SSO 跳转：浏览器拿到后端 /oauth2/authorization/sso 相对路径后
      // 会落到当前 host (28080)，必须转发到后端 18080，否则 404
      '/oauth2': {
        target: 'http://localhost:18080',
        changeOrigin: true,
        preserveHostHeader: true,
        headers: {
          'X-Forwarded-Proto': 'http',
          'X-Forwarded-Port': '28080'
        }
      },
      // SSO 回调：IdP 登录完会跳到 {baseUrl}/login/oauth2/code/sso
      // （baseUrl 由 Spring 从 X-Forwarded-* 头推算 → 28080）
      '/login/oauth2': {
        target: 'http://localhost:18080',
        changeOrigin: true,
        preserveHostHeader: true,
        headers: {
          'X-Forwarded-Proto': 'http',
          'X-Forwarded-Port': '28080'
        }
      }
    }
  }
})
