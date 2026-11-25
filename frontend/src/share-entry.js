import { createApp } from 'vue'
import {
  ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElEmpty, ElIcon, ElInput, ElLoading, ElProgress,
  ElRadioButton, ElRadioGroup, ElResult, ElTable, ElTableColumn, ElTag
} from 'element-plus'
import 'element-plus/dist/index.css'
import './assets/styles/dialog.css'
import './assets/styles/share-entry.css'
import SharedScreen from './views/dashboard-share/index.vue'

const app = createApp(SharedScreen)
for (const component of [
  ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElEmpty, ElIcon, ElInput, ElProgress, ElRadioButton,
  ElRadioGroup, ElResult, ElTable, ElTableColumn, ElTag
]) {
  app.component(component.name, component)
}
app.use(ElLoading)
app.mount('#app')
