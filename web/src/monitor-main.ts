import { createApp } from 'vue'
import IngestMonitorPanel from './components/IngestMonitorPanel.vue'

// 公网入口只加载只读监控及 Vue，管理台和完整组件库留在独立入口，降低手机首屏下载量。
createApp(IngestMonitorPanel).mount('#app')
