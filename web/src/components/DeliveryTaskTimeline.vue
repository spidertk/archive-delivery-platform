<template>
  <h3>Pipeline 节点</h3>
  <el-timeline>
    <el-timeline-item v-for="node in timeline.nodes" :key="node.processId"
      :timestamp="node.startedAt" :type="node.status === 'SUCCEEDED' ? 'success' : node.status === 'FAILED' ? 'danger' : 'info'">
      <strong>{{ node.nodeId }} / attempt {{ node.attempt }}</strong>
      <div>{{ node.stageCode }} · {{ node.status }} · {{ node.workerInstanceId }}</div>
      <pre v-if="node.errorDetail">{{ node.errorDetail }}</pre>
    </el-timeline-item>
  </el-timeline>
  <h3>交付事件</h3>
  <el-alert v-for="event in timeline.events" :key="event.eventId"
    :title="`${event.eventType} / ${event.reasonCode || event.severity}`"
    :description="String(event.message || '')" type="info" :closable="false" />
</template>

<script setup lang="ts">
import type { Timeline } from '../types'
defineProps<{ timeline: Timeline }>()
</script>
