import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './assets/styles/dialog.css'
import './assets/styles/workspace-theme.css'
import './assets/styles/management-theme.css'
import './assets/styles/ledger-theme.css'
import './assets/styles/dashboard-theme.css'
import './assets/styles/query-layout.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import mirrorScroll from './directives/mirrorScroll'
import stickyHeader from './directives/stickyHeader'
import balancedQuery from './directives/balancedQuery'

const app = createApp(App)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 全局自定义指令
app.directive('mirror-scroll', mirrorScroll)
app.directive('sticky-header', stickyHeader)
app.directive('balanced-query', balancedQuery)

app.mount('#app')
