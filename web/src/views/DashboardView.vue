<template>
  <main class="dashboard">
    <h1>归档交付 MVP 看板</h1>
    <el-form inline class="filters">
      <el-form-item label="Run"><el-input-number v-model="filters.runId" :min="1" /></el-form-item>
      <el-form-item label="交付日"><el-date-picker v-model="filters.deliveryDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="归档日"><el-date-picker v-model="filters.archiveDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="生产线"><el-select v-model="filters.lineId" clearable style="width: 110px"><el-option v-for="line in [1,2,3]" :key="line" :label="`LINE-${line}`" :value="line" /></el-select></el-form-item>
      <el-form-item label="已流转"><el-checkbox v-model="rolledOnly" /></el-form-item>
      <el-form-item label="状态"><el-input v-model="filters.status" clearable placeholder="BLOCKED" /></el-form-item>
      <el-form-item label="关键字"><el-input v-model="filters.keyword" clearable placeholder="包名 / 路径" /></el-form-item>
      <el-button type="primary" @click="refresh">刷新</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="cards">
      <el-card v-for="card in cards" :key="card.label"><span>{{ card.label }}</span><strong>{{ card.value }}</strong></el-card>
    </section>
    <el-card>
      <template #header>三线 4/3/3 与六阶段</template>
      <div v-for="group in summary.groups" :key="group.lineId" class="group-row">
        <b>LINE-{{ group.lineId }}</b><span>{{ group.delivered }}/{{ group.outputTotal }} DELIVERED</span>
        <span v-if="group.blocked">BLOCKED {{ group.blocked }}</span><span v-if="group.retrying">RETRY {{ group.retrying }}</span>
        <span v-if="group.waiting">WAITING {{ group.waiting }}</span>
        <div class="stage-bar">
          <span v-for="stage in stages(group)" :key="stage.name" :style="{ width: stage.percent + '%' }" :title="stage.name + ': ' + stage.count" />
        </div>
        <small>GROUPED {{ group.grouped }} · HZ {{ group.hzUploaded }} · SG {{ group.sgVerified }} · FTP {{ group.sgDownloaded }} · PUBLISHED {{ group.published }}</small>
      </div>
    </el-card>
    <el-card>
      <template #header>输出包（{{ total }}）</template>
      <el-table :data="packages" size="small">
        <el-table-column prop="packageName" label="包名" min-width="150" />
        <el-table-column prop="lineId" label="线" width="70" />
        <el-table-column prop="status" label="业务状态" width="130" />
        <el-table-column prop="runStatus" label="运行状态" width="120" />
        <el-table-column prop="currentNode" label="节点" width="110" />
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column prop="rolloverCount" label="流转" width="70" />
        <el-table-column label="操作" width="190">
          <template #default="scope">
            <el-button link @click="open(scope.row)">复盘</el-button>
            <el-button link @click="retry(scope.row)">重试</el-button>
            <el-button link @click="resume(scope.row)">续跑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-drawer v-model="drawer" size="55%" :title="selected?.packageName">
      <el-descriptions v-if="selected" :column="1" border>
        <el-descriptions-item label="SHA-256">{{ selected.artifactSha }}</el-descriptions-item>
        <el-descriptions-item label="HZ">{{ selected.hzPath }}</el-descriptions-item>
        <el-descriptions-item label="SG">{{ selected.sgPath }}</el-descriptions-item>
        <el-descriptions-item label="FTP">{{ selected.publishedPath }}</el-descriptions-item>
        <el-descriptions-item v-for="mapping in requestMappings" :key="mapping.newRequestId" label="原 / 新 requestId">
          {{ mapping.originalRequestId }} → {{ mapping.newRequestId }}
        </el-descriptions-item>
        <el-descriptions-item label="错误">{{ selected.errorCode }}</el-descriptions-item>
      </el-descriptions>
      <PackageTimeline v-if="timeline" :timeline="timeline" />
    </el-drawer>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { getPackageDetail, getPackages, getSummary, getTimeline, resumeRun, retryPackage } from '../api'
import type { Filters, GroupProgress, PackageItem, Summary, Timeline } from '../types'

const filters = reactive<Filters>({ runId: 1 })
const rolledOnly = ref(false)
const emptySummary: Summary = { discovered: 0, planned: 0, repaired: 0, outputTotal: 0, delivered: 0,
  blocked: 0, retrying: 0, waiting: 0, rolledOver: 0, backlog: 0, ungrouped: 0,
  activeSourceSlots: 0, activeOutputSlots: 0, groups: [] }
const summary = ref<Summary>({ ...emptySummary })
const packages = ref<PackageItem[]>([])
const total = ref(0)
const error = ref('')
const drawer = ref(false)
const selected = ref<PackageItem>()
const timeline = ref<Timeline>()
const detail = ref<Record<string, unknown>>()
const requestMappings = computed(() => {
  if (!detail.value?.manifest) return []
  const manifest = typeof detail.value.manifest === 'string'
    ? JSON.parse(detail.value.manifest) : detail.value.manifest
  return (manifest.mappings || []) as Array<{ originalRequestId?: string; newRequestId: string }>
})
let timer: ReturnType<typeof setInterval> | undefined

const cards = computed(() => [
  { label: '发现', value: summary.value.discovered }, { label: '计划', value: summary.value.planned },
  { label: '输出', value: summary.value.outputTotal }, { label: '已交付', value: summary.value.delivered },
  { label: 'BLOCKED', value: summary.value.blocked }, { label: 'RETRY', value: summary.value.retrying },
  { label: 'WAITING', value: summary.value.waiting }, { label: 'Backlog', value: summary.value.backlog },
  { label: '未分组', value: summary.value.ungrouped }, { label: '源槽位', value: summary.value.activeSourceSlots },
  { label: '输出槽位', value: summary.value.activeOutputSlots }, { label: '已流转', value: summary.value.rolledOver }
])

async function refresh() {
  try {
    const current = { ...filters, rolledOver: rolledOnly.value ? true : undefined }
    const [nextSummary, nextPackages] = await Promise.all([getSummary(current), getPackages(current)])
    summary.value = nextSummary
    packages.value = nextPackages.items
    total.value = nextPackages.total
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  }
}

function stages(group: GroupProgress) {
  const values = [group.grouped, group.hzUploaded, group.sgVerified, group.sgDownloaded, group.published, group.delivered]
  const names = ['GROUPED', 'HZ_UPLOADED', 'SG_VERIFIED', 'SG_DOWNLOADED', 'PUBLISHED', 'DELIVERED']
  return values.map((count, index) => ({ name: names[index], count, percent: group.outputTotal ? count * 100 / group.outputTotal : 0 }))
}

function open(item: PackageItem) {
  selected.value = item
  drawer.value = true
  getTimeline(item.id).then(value => { timeline.value = value }).catch(cause => { error.value = String(cause) })
  getPackageDetail(item.id).then(value => { detail.value = value }).catch(cause => { error.value = String(cause) })
}

function reason(action: string): string | undefined {
  return window.prompt(`请填写${action}原因`) || undefined
}

function retry(item: PackageItem) {
  const value = reason('重试')
  if (value) retryPackage(item.id, item.fence, value).then(refresh).catch(cause => { error.value = String(cause) })
}

function resume(item: PackageItem) {
  const value = reason('续跑')
  if (value) resumeRun(item.runId, item.currentNode, [item.id], value).then(refresh).catch(cause => { error.value = String(cause) })
}

onMounted(() => { refresh(); timer = setInterval(refresh, 15000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
defineExpose({ refresh })
</script>

<style scoped>
.dashboard { max-width: 1500px; margin: 20px auto; padding: 0 16px; }
.filters { margin-bottom: 12px; }
.cards { display: grid; grid-template-columns: repeat(6, minmax(100px, 1fr)); gap: 10px; margin-bottom: 14px; }
.cards .el-card { text-align: center; }
.cards strong { display: block; font-size: 26px; margin-top: 6px; }
.group-row { margin: 14px 0; display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.stage-bar { width: 100%; height: 14px; display: flex; background: #eee; }
.stage-bar span { display: block; height: 100%; background: #409eff; }
.stage-bar span:nth-child(2) { background: #67c23a; }
.stage-bar span:nth-child(3) { background: #9c27b0; }
.stage-bar span:nth-child(4) { background: #e6a23c; }
.stage-bar span:nth-child(5) { background: #795548; }
.stage-bar span:nth-child(6) { background: #2e7d32; }
pre { white-space: pre-wrap; }
</style>
