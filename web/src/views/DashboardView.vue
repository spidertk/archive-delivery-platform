<template>
  <main class="dashboard">
    <h1>归档交付看板</h1>
    <el-form inline class="filters" @submit.prevent>
      <el-form-item label="Delivery ID"><el-input-number v-model="deliveryId" :min="1" /></el-form-item>
      <el-button type="primary" @click="refresh">刷新</el-button>
      <el-button v-if="!['DELIVERED','CANCELLED'].includes(summary.deliveryStatus)" @click="pause">节点边界暂停</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card class="submit-card">
      <template #header>按业务编码提交；相同批次原样再提交可继续暂停流程</template>
      <el-form inline @submit.prevent>
        <el-form-item label="源 OSS URI"><el-input v-model="submission.ossUri" placeholder="oss://bucket/prefix/" /></el-form-item>
        <el-form-item label="Pipeline Code"><el-input v-model="submission.pipelineCode" /></el-form-item>
        <el-form-item label="交付日"><el-date-picker v-model="submission.deliveryDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="交付批次"><el-input v-model="submission.deliveryBatch" /></el-form-item>
        <el-form-item v-for="(target, index) in submission.targets" :key="index" :label="`目标 ${index + 1} vendorCode / entityCode`">
          <el-input v-model="target.vendorCode" placeholder="alibaba" />
          <el-input v-model="target.entityCode" placeholder="jun" />
        </el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">提交 / 继续</el-button>
      </el-form>
    </el-card>
    <section class="cards">
      <el-card v-for="card in cards" :key="card.label"><span>{{ card.label }}</span><strong>{{ card.value }}</strong></el-card>
    </section>
    <el-card>
      <template #header>交付目标与厂商实体</template>
      <div v-for="target in summary.targets" :key="`${target.vendorCode}/${target.entityCode}`" class="target-row">
        <b>{{ target.entityCode }}</b><span>厂商 {{ target.vendorCode }}</span><span>{{ target.entityName }}</span>
        <span>{{ target.accountName }}</span><span>执行组 {{ target.workerGroup }}</span><strong>{{ target.completedCount }}/{{ target.targetCount }}</strong><span>{{ target.status }}</span>
      </div>
    </el-card>
    <el-card>
      <template #header>最新有效任务（{{ total }}）</template>
      <el-table :data="tasks" size="small">
        <el-table-column prop="taskId" label="Task ID" width="90" />
        <el-table-column prop="entityCode" label="实体" width="120" />
        <el-table-column prop="vendorCode" label="厂商" width="140" />
        <el-table-column prop="workerGroup" label="执行组" width="100" />
        <el-table-column prop="newFileName" label="产物" min-width="150" />
        <el-table-column prop="deliveryDate" label="交付日" width="110" />
        <el-table-column prop="stageCode" label="Pipeline 阶段" width="130" />
        <el-table-column prop="executionStatus" label="执行状态" width="120" />
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button link @click="open(scope.row)">时间轴</el-button>
            <el-button v-if="['BLOCKED','RETRY_WAIT'].includes(scope.row.executionStatus)" link @click="retry(scope.row)">重试</el-button>
            <el-button v-if="!['SUCCEEDED','EXPIRED','RUNNING'].includes(scope.row.executionStatus)" link @click="resume(scope.row)">续跑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-drawer v-model="drawer" size="58%" :title="selected ? `Task ${selected.taskId} · ${selected.newFileName}` : '任务时间轴'">
      <el-descriptions v-if="selected" :column="1" border>
        <el-descriptions-item label="来源 Data IDs">{{ selected.sourceDataIds?.join(', ') }}</el-descriptions-item>
        <el-descriptions-item label="来源 metadata dataIds">{{ selected.metadataIds?.join(', ') }}</el-descriptions-item>
        <el-descriptions-item label="SHA-256">{{ selected.sha256 }}</el-descriptions-item>
        <el-descriptions-item label="Size / Token">{{ selected.sizeBytes }} / {{ selected.tokenCount }}</el-descriptions-item>
        <el-descriptions-item label="错误">{{ selected.lastErrorCode }}</el-descriptions-item>
      </el-descriptions>
      <DeliveryTaskTimeline v-if="timeline" :timeline="timeline" />
    </el-drawer>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { getSummary, getTasks, getTimeline, pauseDelivery, resumeDelivery, retryTask, submitDelivery } from '../api'
import DeliveryTaskTimeline from '../components/DeliveryTaskTimeline.vue'
import type { DeliveryTask, SubmitDelivery, Summary, Timeline } from '../types'

const deliveryId = ref(1)
const today = new Date().toISOString().slice(0, 10)
const submission = reactive<SubmitDelivery>({ ossUri: 'oss://bucket/prefix/', pipelineCode: 'archive-delivery', deliveryDate: today,
  deliveryBatch: `BATCH-${Date.now()}`,
  targets: ['jun', 'jun2'].map(entityCode => ({ targetCount: 1, vendorCode: 'alibaba', entityCode })) })
const submitting = ref(false)
const emptySummary: Summary = { deliveryStatus: 'SUBMITTED', taskTotal: 0, targetTotal: 0, succeeded: 0,
  blocked: 0, retrying: 0, waiting: 0, paused: 0, expired: 0, targets: [] }
const summary = ref<Summary>({ ...emptySummary })
const tasks = ref<DeliveryTask[]>([])
const total = ref(0)
const error = ref('')
const drawer = ref(false)
const selected = ref<DeliveryTask>()
const timeline = ref<Timeline>()
let timer: ReturnType<typeof setInterval> | undefined

const cards = computed(() => [
  { label: '聚合状态', value: summary.value.deliveryStatus }, { label: '有效任务', value: summary.value.taskTotal },
  { label: '已交付', value: summary.value.succeeded }, { label: 'BLOCKED', value: summary.value.blocked },
  { label: 'RETRY', value: summary.value.retrying }, { label: 'WAITING', value: summary.value.waiting },
  { label: 'PAUSED', value: summary.value.paused }, { label: '超期历史', value: summary.value.expired }
])

async function refresh() {
  try {
    const [nextSummary, nextTasks] = await Promise.all([getSummary(deliveryId.value), getTasks(deliveryId.value)])
    summary.value = nextSummary
    tasks.value = nextTasks.items
    total.value = nextTasks.total
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  }
}

async function submit() {
  submitting.value = true
  try {
    const result = await submitDelivery({ ...submission })
    deliveryId.value = result.deliveryId
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    submitting.value = false
  }
}

async function open(item: DeliveryTask) {
  selected.value = item
  timeline.value = undefined
  drawer.value = true
  try { timeline.value = await getTimeline(item.taskId) } catch (cause) { error.value = String(cause) }
}

function reason(action: string): string | undefined { return window.prompt(`请填写${action}原因`) || undefined }
function retry(item: DeliveryTask) {
  const value = reason('重试')
  if (value) retryTask(item.taskId, item.fence, value).then(refresh).catch(cause => { error.value = String(cause) })
}
function resume(item: DeliveryTask) {
  const value = reason('续跑')
  if (value) resumeDelivery(item.deliveryId, item.currentNodeId, [item.taskId], value).then(refresh).catch(cause => { error.value = String(cause) })
}
function pause() {
  const value = reason('暂停')
  if (value) pauseDelivery(deliveryId.value, value).then(refresh).catch(cause => { error.value = String(cause) })
}

onMounted(() => { refresh(); timer = setInterval(refresh, 15000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
defineExpose({ refresh })
</script>

<style scoped>
.dashboard { max-width: 1500px; margin: 20px auto; padding: 0 16px; }
.filters { margin-bottom: 12px; }
.cards { display: grid; grid-template-columns: repeat(8, minmax(100px, 1fr)); gap: 10px; margin-bottom: 14px; }
.cards .el-card { text-align: center; }
.cards strong { display: block; font-size: 22px; margin-top: 6px; }
.target-row { margin: 10px 0; display: flex; gap: 20px; align-items: center; }
pre { white-space: pre-wrap; }
</style>
