<template>
  <main class="monitor">
    <header class="heading">
      <div><span class="eyebrow">OSS · 数据流转</span><h1>渠道数据看板</h1><p>{{ status.statisticsDate || '今日' }} · 北京时间 · {{ status.channels.length }} 个渠道</p></div>
      <button class="refresh" :disabled="loading" @click="refresh">{{ loading ? '正在刷新…' : '刷新数据' }}</button>
    </header>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="!status.enabled && !loading" class="notice" role="status">定时监控尚未启用，以下为已保存的数据</div>
    <section class="totals" aria-label="传输总览">
      <article v-for="item in totals" :key="item.label"><span>{{ item.label }}</span><strong>{{ bytes(item.value) }}</strong><small>{{ item.note }}</small></article>
    </section>

    <section class="storage-summary" aria-label="总存储一个月费用">
      <div><span>总存储一个月预估费用</span><strong>{{ status.statisticsDate ? money(status.storageSummary.monthlyStorage) : '计算中…' }}<small>人民币 / 月</small></strong></div>
      <p>按当前总存储量 <b>{{ bytes(status.storageSummary.storedBytes) }}</b> 持续存放一个月<br />全部已监控渠道 · 仅估算 OSS 存储费</p>
    </section>

    <section class="section">
      <div class="section-title"><h2>桶与预估费用</h2><span>按官方参考价估算，仅计存储费</span></div>
      <p class="pricing">新加坡 · 标准存储（本地冗余） · <b>约 ¥{{ Number(status.pricing.storagePerGbMonth).toFixed(6) }} / GB / 月</b> · <a :href="status.pricing.sourceUrl" target="_blank" rel="noopener noreferrer">阿里云官方价格 ↗</a><br />官方美元单价 US$ {{ status.pricing.sourceStoragePerGbMonth }}；参考汇率 1 美元 = {{ status.pricing.usdCnyRate }} 元人民币（{{ status.pricing.exchangeRateDate }}，<a :href="status.pricing.exchangeRateSource" target="_blank" rel="noopener noreferrer">汇率来源</a>）。<br />按当前监控存量持续一个月估算，未扣免费额度及套餐折扣。仅估算存储费用。</p>
      <div class="bucket-grid">
        <article v-for="bucket in status.bucketCosts" :key="bucket.bucket" class="bucket-card">
          <span class="eyebrow">OSS BUCKET</span><h3>{{ bucket.bucket }}</h3>
          <div class="pair"><span>已监控存量</span><b>{{ bytes(bucket.storedBytes) }}</b></div>
          <div class="pair"><span>累计交付到此桶</span><b>{{ bytes(bucket.deliveredBytes) }}</b></div>
          <div class="estimates"><div><span>预估月存储费 · 监控范围</span><strong>{{ money(bucket.monthlyStorage) }}</strong></div></div>
        </article>
      </div>
    </section>

    <section class="section">
      <div class="section-title"><h2>渠道与去向</h2><span>连续进数 {{ status.continuousPeriodSeconds / 60 }} 分钟后主动播报，之后每 {{ status.reportIntervalSeconds / 3600 }} 小时汇总</span></div>
      <div class="channel-grid">
        <article v-for="channel in status.channels" :key="channel.channelKey" class="channel-card" :class="{ selected: selectedId === channel.sourceId }">
          <div class="channel-top"><button @click="selectChannel(channel)">{{ channel.channelCode }}</button><span class="phase" :class="{ active: channel.phase !== 'IDLE', failed: !!channel.lastErrorCode }">{{ phase(channel) }}</span></div>
          <p class="bucket-name">来源桶 · {{ channel.bucket }}</p>
          <div class="stock"><strong>{{ bytes(channel.sizeBytes) }}</strong><span>当前存量 · {{ number(channel.objectCount) }} 文件 · 预估 {{ money(channel.costEstimate.monthlyStorage) }} / 月</span></div>
          <div class="channel-metrics"><div><small>今日进数</small><b>{{ bytes(channel.incomingTodayBytes) }}</b></div><div><small>监控以来累计进数</small><b>{{ bytes(channel.incomingTotalBytes) }}</b></div><div><small>今日已交付</small><b>{{ bytes(channel.deliveryTodayBytes) }}</b></div></div>
          <div class="destinations"><small>交付去向 · 累计 {{ bytes(channel.deliveryTotalBytes) }}</small><p v-if="!channel.destinations?.length">暂无已确认的目标桶</p><div v-for="target in channel.destinations" :key="target.bucket"><b>→ {{ target.bucket }}</b><span>今日 {{ bytes(target.todayBytes) }} · 累计 {{ bytes(target.totalBytes) }}</span></div></div>
          <footer><span>扫描 {{ time(channel.observedAt) }}</span><button @click="selectChannel(channel)">查看趋势与新增目录 ↗</button></footer>
        </article>
      </div>
      <p class="footnote">累计进数从首次监控基线之后统计；进数是扫描发现的新增和更新，交付量只统计已确认上传到目标桶的对象。</p>
    </section>

    <section v-if="selectedId" class="section detail">
      <div class="section-title"><h2>{{ selected?.channelCode }} · 小时趋势</h2><select v-model.number="hours" aria-label="趋势时间范围" @change="refreshTrend"><option :value="24">最近 24 小时</option><option :value="168">最近 7 天</option><option :value="720">最近 30 天</option></select></div>
      <p>新增 {{ addedObjects }} 个文件 / {{ bytes(addedBytes) }}；覆盖和移除单独统计。</p>
      <p v-if="!trend.length">暂无成功扫描记录</p>
      <svg v-else viewBox="0 0 800 150" role="img" aria-label="每小时新增文件数柱状图"><line x1="0" y1="130" x2="800" y2="130" stroke="#cddbd8" /><g v-for="(point,index) in trend" :key="point.hour"><rect :x="index * 800 / trend.length + 2" :y="130 - point.addedObjects / peak * 115" :width="Math.max(1, 800 / trend.length - 4)" :height="Math.max(1, point.addedObjects / peak * 115)" fill="#168577"><title>{{ time(point.hour) }}：新增 {{ point.addedObjects }} 个，{{ bytes(point.addedBytes) }}</title></rect></g></svg>
      <div class="table-scroll"><table class="data-table"><thead><tr><th>小时（北京时间）</th><th>累计文件</th><th>累计容量</th><th>新增文件</th><th>新增容量</th><th>更新文件</th></tr></thead><tbody><tr v-for="point in trend" :key="point.hour"><td>{{ time(point.hour) }}</td><td>{{ number(point.objectCount) }}</td><td>{{ bytes(point.sizeBytes) }}</td><td>{{ number(point.addedObjects) }}</td><td>{{ bytes(point.addedBytes) }}</td><td>{{ number(point.changedObjects) }}</td></tr></tbody></table></div>
      <h3>所选时段有新增数据的目录</h3><p v-if="!changes.length" class="empty">所选时段没有新增数据目录</p>
      <div v-else class="table-scroll"><table class="data-table"><thead><tr><th>渠道下的相对目录</th><th>新增文件</th><th>更新文件</th><th>进数容量</th></tr></thead><tbody><tr v-for="folder in changes" :key="folder.directory"><td>{{ folder.directory }}</td><td>{{ number(folder.addedObjects) }}</td><td>{{ number(folder.changedObjects) }}</td><td>{{ bytes(Number(folder.addedBytes) + Number(folder.changedBytes)) }}</td></tr></tbody></table></div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getIngestMonitor, getIngestTrend, getIngestChanges } from '../api'
import type { IngestChannel, IngestMonitorStatus, IngestTrend, IngestChange } from '../types'

const status = ref<IngestMonitorStatus>({ enabled: false, pollIntervalSeconds: 60, continuousPeriodSeconds: 1800, reportIntervalSeconds: 3600, dailyTimezone: 'Asia/Shanghai', channels: [], statisticsDate: '', bucketCosts: [], storageSummary: { currency: 'CNY', storedBytes: 0, monthlyStorage: 0, periodMonths: 1 }, pricing: { currency: 'CNY', storagePerGbMonth: 0.1135209, sourceCurrency: 'USD', sourceStoragePerGbMonth: 0.017, usdCnyRate: 6.6777, exchangeRateDate: '2026-11-20', exchangeRateSource: '', bytesPerGb: 1073741824, basis: '', region: 'ap-southeast-1', storageClass: 'Standard LRS', sourceUrl: '', verifiedOn: '' } })
const selectedId = ref<number>()
const selected = computed(() => status.value.channels.find(channel => channel.sourceId === selectedId.value))
const hours = ref(24), trend = ref<IngestTrend[]>([]), changes = ref<IngestChange[]>([])
const error = ref(''), loading = ref(false)
const peak = computed(() => Math.max(1, ...trend.value.map(point => point.addedObjects)))
const addedObjects = computed(() => trend.value.reduce((sum,point) => sum + Number(point.addedObjects),0))
const addedBytes = computed(() => trend.value.reduce((sum,point) => sum + Number(point.addedBytes),0))
const totals = computed(() => [
  { label: '当前存量', value: sum('sizeBytes'), note: '所有监控渠道' },
  { label: '累计进数', value: sum('incomingTotalBytes'), note: '首次基线之后' },
  { label: '今日进数', value: sum('incomingTodayBytes'), note: '北京时间 00:00 起' },
  { label: '今日已交付', value: sum('deliveryTodayBytes'), note: '已确认到达目标 OSS' }
])
let timer: ReturnType<typeof setInterval> | undefined
function sum(key: 'sizeBytes' | 'incomingTotalBytes' | 'incomingTodayBytes' | 'deliveryTodayBytes') { return status.value.channels.reduce((total,row) => total + Number(row[key] || 0),0) }
function bytes(value?: number) { if (value == null) return '—'; if (value > 0 && value < 1e7) return '<0.01 GB'; return value >= 1e12 ? `${(value / 1e12).toFixed(2)} TB` : `${(value / 1e9).toFixed(2)} GB` }
function number(value?: number) { return value == null ? '—' : Number(value).toLocaleString() }
function time(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '等待首次扫描' }
function phase(channel: IngestChannel) { return channel.lastErrorCode ? '扫描失败' : !channel.observedAt ? '等待扫描' : channel.phase === 'ACTIVE' ? '小时汇总中' : channel.phase === 'OBSERVING' ? '观察进数' : '静默' }
function money(value: number) { return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
async function refreshTrend() {
  // 切换渠道或时间范围后只接受对应响应，避免慢请求覆盖新选择。
  if (!selectedId.value) return
  const sourceId = selectedId.value, range = hours.value
  try { const [result,folders] = await Promise.all([getIngestTrend(sourceId,range),getIngestChanges(sourceId,range)]); if (sourceId === selectedId.value && range === hours.value) { trend.value = result; changes.value = folders; error.value = '' } }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
}
function selectChannel(channel: IngestChannel) { if (selectedId.value !== channel.sourceId) { trend.value = []; changes.value = [] }; selectedId.value = channel.sourceId; refreshTrend() }
async function refresh() {
  // 网络失败保留最后成功事实；预估费用不会用空响应覆盖渠道总量。
  if (loading.value) return
  loading.value = true
  try { status.value = await getIngestMonitor(); if (!selectedId.value) selectedId.value = status.value.channels[0]?.sourceId; error.value = ''; await refreshTrend() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  finally { loading.value = false }
}
onMounted(() => { refresh(); timer = setInterval(refresh,60000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
defineExpose({ refresh })
</script>

<style scoped>
.monitor { max-width: 1320px; margin: 0 auto; padding: 30px 24px 60px; color: #203b36; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.heading,.section-title,.channel-top,.pair,footer { display:flex; justify-content:space-between; align-items:center; gap:16px; }
.heading h1 { font-size:30px; letter-spacing:-1px; margin:8px 0; }.eyebrow { font-size:11px; letter-spacing:1.5px; color:#64857d; }.heading p,.footnote { color:#7b8e88; font-size:12px; }
.totals { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; margin:24px 0 34px; }.totals article { background:#eaf4f0; border:1px solid #d9e8e2; border-radius:14px; padding:20px; }.totals span,.totals small { display:block; color:#668077; font-size:12px; }.totals strong { display:block; font-size:28px; margin:12px 0 9px; font-weight:650; }
.storage-summary { display:flex; justify-content:space-between; align-items:center; gap:24px; padding:24px; border-radius:14px; background:#174f40; color:#fff; }.storage-summary span { font-size:13px; color:#c7e1d6; }.storage-summary strong { display:block; font-size:34px; font-weight:650; margin-top:10px; }.storage-summary small { font-size:12px; font-weight:400; color:#c7e1d6; margin-left:12px; }.storage-summary p { font-size:12px; color:#d6e9e1; line-height:1.9; margin:0; }
h2 { font-size:18px; margin:0; }.section { margin-top:30px; }.section-title { margin-bottom:16px; }.section-title > span { color:#81918c; font-size:12px; }.section-title select { width:150px; padding:8px; border:1px solid #d7e4df; border-radius:7px; color:#365b4c; background:white; }
.pricing { border:1px solid #e2eae6; border-radius:10px; padding:12px 16px; margin-bottom:16px; color:#668077; font-size:12px; }.pricing summary { cursor:pointer; color:#36786a; }.price-fields { display:flex; gap:16px; margin-top:16px; flex-wrap:wrap; }.price-fields label { display:flex; flex-direction:column; gap:6px; }.price-fields input,.price-fields select { padding:9px; border:1px solid #d7e4df; border-radius:6px; background:white; color:#203b36; width:180px; }.pricing p { line-height:1.7; }
.bucket-grid,.channel-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; }.bucket-card,.channel-card,.detail { background:white; border:1px solid #e0e9e4; border-radius:14px; padding:20px; }.bucket-card h3 { font-size:15px; margin:10px 0 18px; overflow-wrap:anywhere; }.pair { margin:9px 0; font-size:12px; }.pair span { color:#7e928a; }.estimates { display:grid; grid-template-columns:1fr 1fr; gap:14px; border-top:1px solid #edf2ef; margin-top:16px; padding-top:15px; }.estimates span { display:block; font-size:11px; color:#84938d; }.estimates strong { display:block; margin-top:8px; font-size:20px; font-weight:600; }
.channel-card.selected { border-color:#86b8a8; }.channel-top button { font-size:17px; font-weight:650; color:#204f43; overflow-wrap:anywhere; text-align:left; }.channel-card button { border:0; background:none; padding:0; cursor:pointer; }.phase { flex-shrink:0; font-size:11px; padding:4px 8px; border-radius:20px; background:#eff3f1; color:#7a8a83; }.phase.active { color:#13866c; background:#e6f6ef; }.phase.failed { color:#a34545; background:#fbecec; }.bucket-name { font-size:11px; color:#8a9a94; overflow-wrap:anywhere; }.stock { display:flex; flex-direction:column; gap:6px; margin:22px 0; }.stock strong { font-size:29px; font-weight:650; }.stock span { color:#83958d; font-size:11px; }.channel-metrics { display:grid; grid-template-columns:repeat(3,1fr); gap:10px; }.channel-metrics small { display:block; font-size:10px; color:#8a9a94; }.channel-metrics b { display:block; font-size:14px; margin-top:8px; }.destinations { border-top:1px solid #eef3ef; margin-top:20px; padding-top:14px; font-size:11px; }.destinations small { color:#87998f; }.destinations p { color:#9aaba2; }.destinations div { display:flex; flex-direction:column; gap:5px; margin-top:12px; overflow-wrap:anywhere; }.destinations span { color:#7a8e83; }footer { border-top:1px solid #eef3ef; padding-top:14px; margin-top:18px; font-size:10px; color:#9baaa2; }footer button { font-size:11px; color:#39816b; }.footnote { line-height:1.8; }.detail > p { font-size:12px; color:#7a8e83; }.detail h3 { font-size:15px; margin-top:30px; }svg { width:100%; max-height:180px; margin:12px 0; }
.refresh { border:1px solid #c5dcd2; border-radius:8px; padding:10px 15px; background:#fff; color:#24715a; cursor:pointer; }.refresh:disabled { opacity:.5; }.notice { padding:12px 16px; border-radius:8px; background:#f0f6f3; font-size:13px; }.notice.error { color:#a23c3c; background:#fff0ee; }.table-scroll { overflow:auto; max-height:320px; }.data-table { width:100%; border-collapse:collapse; font-size:12px; text-align:left; white-space:nowrap; }.data-table th { color:#718b7e; background:#f3f7f5; font-weight:500; }.data-table td,.data-table th { padding:12px 10px; border-bottom:1px solid #edf2ef; }.empty { text-align:center; padding:25px 0; color:#84988b; }
@media(max-width:700px) { .monitor { padding:20px 14px 40px; }.heading h1 { font-size:25px; }.totals { grid-template-columns:repeat(2,1fr); gap:10px; }.totals article { padding:16px; }.totals strong { font-size:23px; }.bucket-grid,.channel-grid { grid-template-columns:1fr; }.section-title { align-items:flex-start; }.section-title > span { max-width:52%; text-align:right; line-height:1.6; }.channel-card,.bucket-card,.detail { padding:17px; }.channel-metrics b { font-size:13px; }.channel-top button { font-size:16px; } }
@media(max-width:700px) { .storage-summary { flex-direction:column; align-items:flex-start; padding:20px; gap:16px; }.storage-summary strong { font-size:30px; } }
</style>
