import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import IngestMonitorPanel from './IngestMonitorPanel.vue'
import { getIngestMonitor, getIngestTrend, getIngestChanges } from '../api'

vi.mock('../api', () => ({ getIngestMonitor: vi.fn(), getIngestTrend: vi.fn(), getIngestChanges: vi.fn() }))

describe('IngestMonitorPanel', () => {
  it('shows channel growth and preserves successful facts when the API fails', async () => {
    vi.mocked(getIngestMonitor).mockResolvedValue({ enabled: true, pollIntervalSeconds: 60, continuousPeriodSeconds: 1800, reportIntervalSeconds: 3600, dailyTimezone: 'Asia/Shanghai', statisticsDate: '2026-11-22', bucketCosts: [{ bucket: 'bucket', storedBytes: 1048576, deliveredBytes: 1024, monthlyStorage: 0.017, currency: 'CNY' }], storageSummary: { currency: 'CNY', storedBytes: 25288822233946, monthlyStorage: 2673.650029, periodMonths: 1 }, pricing: { currency: 'CNY', storagePerGbMonth: 0.1135209, sourceCurrency: 'USD', sourceStoragePerGbMonth: 0.017, usdCnyRate: 6.6777, exchangeRateDate: '2026-11-20', exchangeRateSource: 'https://api.frankfurter.dev/v1/2026-11-20?base=USD&symbols=CNY', bytesPerGb: 1073741824, basis: '预估', region: 'ap-southeast-1', storageClass: 'Standard LRS', sourceUrl: 'https://www.alibabacloud.com/en/product/oss/pricing', verifiedOn: '2026-11-22' },
      channels: [{ sourceId: 3, sourceIds: [3], channelKey: 'oss://bucket/incoming/', bucket: 'bucket', channelCode: 'incoming', ossUri: 'oss://bucket/incoming/temp/',
        availability: 'AVAILABLE', phase: 'OBSERVING', objectCount: 10, sizeBytes: 1048576,
        observedAt: '2026-11-22T00:00:00Z', pendingNotifications: 1, incomingTotalBytes: 1048576, incomingTodayBytes: 1048576, deliveryTotalBytes: 1024, deliveryTodayBytes: 0, destinations: [{ bucket: 'target-bucket', files: 1, totalBytes: 1024, todayFiles: 0, todayBytes: 0 }], costEstimate: { currency: 'CNY', monthlyStorage: 0.017 } }] })
    vi.mocked(getIngestTrend).mockResolvedValue([{ hour: '2026-11-22T00:00:00Z', objectCount: 10, sizeBytes: 1048576,
      addedObjects: 3, addedBytes: 1048576, changedObjects: 1, changedBytes: 0, removedObjects: 1, removedBytes: 0 }])
    vi.mocked(getIngestChanges).mockResolvedValue([{ directory: 'temp/new/folder/', addedObjects: 3, addedBytes: 1048576, changedObjects: 0, changedBytes: 0, observedAt: '2026-11-22T00:00:00Z' }])
    const wrapper = mount(IngestMonitorPanel, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('incoming')
    expect(wrapper.text()).toContain('target-bucket')
    expect(wrapper.text()).toContain('预估月存储费')
    expect(wrapper.text()).toContain('¥0.02')
    expect(wrapper.text()).toContain('总存储一个月预估费用')
    expect(wrapper.text()).toContain('¥2,673.65')
    expect(wrapper.text()).toContain('6.6777')
    expect(wrapper.text()).toContain('temp/new/folder/')
    expect(wrapper.text()).toContain('连续进数 30 分钟')
    expect(wrapper.text()).toContain('观察进数')
    expect(wrapper.text()).toContain('新增 3 个文件 / <0.01 GB')
    expect(wrapper.find('svg[role="img"]').attributes('aria-label')).toContain('新增文件数')
    vi.mocked(getIngestMonitor).mockRejectedValueOnce(new Error('offline'))
    await wrapper.vm.refresh()
    expect(wrapper.text()).toContain('offline')
    expect(wrapper.text()).toContain('新增 3 个文件 / <0.01 GB')
    wrapper.unmount()
  })
})
