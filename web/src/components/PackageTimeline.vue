<template>
  <el-timeline>
    <el-timeline-item v-for="node in timeline.nodes" :key="String(node.id)"
      :timestamp="String(node.startedAt || '')" :type="node.status === 'SUCCEEDED' ? 'success' : 'danger'">
      <strong>{{ node.nodeId }} / attempt {{ node.attempt }}</strong>
      <div>{{ node.status }} · {{ node.workerId }}</div>
      <pre v-if="node.errorDetail">{{ node.errorDetail }}</pre>
    </el-timeline-item>
  </el-timeline>
  <el-alert v-for="(event, index) in timeline.events" :key="index" :title="String(event.eventType)"
    :description="String(event.message || '')" type="info" :closable="false" />
  <el-alert v-for="(audit, index) in timeline.audits" :key="`audit-${index}`"
    :title="`${audit.action}: ${audit.reason}`" type="warning" :closable="false" />
</template>

<script setup lang="ts">
import type { Timeline } from '../types'
defineProps<{ timeline: Timeline }>()
</script>
