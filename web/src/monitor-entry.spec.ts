import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { getIngestMonitor, getIngestTrend, getIngestChanges } from './api'

vi.mock('./api', () => ({ getIngestMonitor: vi.fn(), getIngestTrend: vi.fn(), getIngestChanges: vi.fn() }))

describe('public monitor entry', () => {
  it('boots without the management app or component library', async () => {
    // 验证真正的独立入口能够替换 HTML 加载占位，防止组件测试有全局插件而线上首屏白屏。
    vi.mocked(getIngestMonitor).mockResolvedValue({ enabled: true, pollIntervalSeconds: 60, continuousPeriodSeconds: 1800, reportIntervalSeconds: 3600, dailyTimezone: 'Asia/Shanghai', statisticsDate: '2026-11-22', channels: [], bucketCosts: [], storageSummary: { currency: 'CNY', storedBytes: 25288822233946, monthlyStorage: 2673.650029, periodMonths: 1 }, pricing: { currency: 'CNY', storagePerGbMonth: 0.1135209, sourceCurrency: 'USD', sourceStoragePerGbMonth: 0.017, usdCnyRate: 6.6777, exchangeRateDate: '2026-11-20', exchangeRateSource: 'https://api.frankfurter.dev/v1/2026-11-20?base=USD&symbols=CNY', bytesPerGb: 1073741824, basis: '预估', region: 'ap-southeast-1', storageClass: 'Standard LRS', sourceUrl: 'https://www.alibabacloud.com/en/product/oss/pricing', verifiedOn: '2026-11-22' } })
    vi.mocked(getIngestTrend).mockResolvedValue([])
    vi.mocked(getIngestChanges).mockResolvedValue([])
    document.body.innerHTML = '<div id="app"><p id="boot-message">正在加载</p></div>'
    await import('./monitor-main')
    await flushPromises()
    expect(document.querySelector('h1')?.textContent).toBe('渠道数据看板')
    expect(document.querySelector('#boot-message')).toBeNull()
    expect(document.querySelector('.storage-summary')?.textContent).toContain('¥2,673.65')
    expect(document.querySelectorAll('.totals article')).toHaveLength(4)
    expect(document.body.textContent).not.toContain('交付看板')
  })
})
