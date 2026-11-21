<template>
  <el-card class="batch-card">
    <template #header>创建 OSS 交付批次（archive-batch）</template>
    <el-form inline @submit.prevent>
      <el-form-item label="源 OSS URI"><el-input v-model="form.ossUri" placeholder="oss://bucket/prefix/" /></el-form-item>
      <el-form-item label="交付日"><el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="交付批次"><el-input v-model="form.deliveryBatch" /></el-form-item>
      <el-button type="primary" :loading="creating" @click="create">下载并规划批次</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { createDeliveryBatch } from '../api'

const emit = defineEmits<{ created: [deliveryId: number, deliveryBatch: string] }>()
const today = new Date().toISOString().slice(0, 10)
const form = reactive({ ossUri: 'oss://bucket/prefix/', pipelineCode: 'archive-batch', deliveryDate: today, deliveryBatch: '' })
const creating = ref(false)
const error = ref('')

async function create() {
  creating.value = true
  error.value = ''
  try {
    const result = await createDeliveryBatch({ ...form })
    emit('created', result.deliveryId, form.deliveryBatch)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.batch-card { margin-bottom: 14px; }
</style>
