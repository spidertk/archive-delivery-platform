import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'
import { getPackages, getSummary } from '../api'

vi.mock('../api', () => ({
  getSummary: vi.fn(),
  getPackages: vi.fn(),
  getTimeline: vi.fn(),
  retryPackage: vi.fn(),
  resumeRun: vi.fn()
}))

const summary = {
  discovered: 12, planned: 10, repaired: 10, outputTotal: 10, delivered: 8,
  blocked: 1, retrying: 1, waiting: 0, rolledOver: 0, backlog: 2, ungrouped: 0,
  activeSourceSlots: 1, activeOutputSlots: 2,
  groups: [{ lineId: 1, outputTotal: 4, grouped: 0, hzUploaded: 0, sgVerified: 0,
    sgDownloaded: 0, published: 0, delivered: 4, blocked: 0, retrying: 0, waiting: 0 }]
}

describe('DashboardView', () => {
  beforeEach(() => {
    vi.mocked(getSummary).mockResolvedValue(summary)
    vi.mocked(getPackages).mockResolvedValue({ items: [], total: 10 })
  })

  it('renders real API data and keeps last success on refresh failure', async () => {
    const wrapper = mount(DashboardView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('12')
    expect(wrapper.text()).toContain('LINE-1')
    expect(wrapper.text()).toContain('4/4 DELIVERED')
    vi.mocked(getSummary).mockRejectedValueOnce(new Error('offline'))
    await wrapper.vm.refresh()
    await flushPromises()
    expect(wrapper.text()).toContain('offline')
    expect(wrapper.text()).toContain('12')
    expect(wrapper.text()).toContain('4/4 DELIVERED')
  })
})
