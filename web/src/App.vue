<template>
  <IngestMonitorPanel v-if="monitorOnly" />
  <el-tabs v-else class="app-tabs">
    <el-tab-pane label="交付看板"><DashboardView /></el-tab-pane>
    <el-tab-pane label="渠道监控与趋势" lazy><IngestMonitorPanel /></el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import DashboardView from './views/DashboardView.vue'
import IngestMonitorPanel from './components/IngestMonitorPanel.vue'
const monitorOnly = window.location.pathname === '/monitor'
</script>

<style scoped>
.app-tabs { padding: 0 16px; }
</style>
