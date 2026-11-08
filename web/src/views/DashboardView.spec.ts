import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'
import { getSummary, getTasks } from '../api'

vi.mock('../api', () => ({
  getSummary: vi.fn(), getTasks: vi.fn(), getTimeline: vi.fn(), retryTask: vi.fn(), resumeDelivery: vi.fn(),
  pauseDelivery: vi.fn(), submitDelivery: vi.fn()
}))

const summary = {
  deliveryStatus: 'BLOCKED', taskTotal: 3, targetTotal: 3, succeeded: 2, blocked: 1,
  retrying: 0, waiting: 0, paused: 0, expired: 1,
  targets: [
    { targetCount: 1, assignedCount: 1, completedCount: 1, status: 'DELIVERED', deliveryEntityId: 1, workerGroup: 'group1', vendorCode: 'LOCAL-FTP-1', entityCode: 'ENTITY-1', entityName: '实体 1', protocol: 'FTP', accountName: 'entity1' },
    { targetCount: 1, assignedCount: 1, completedCount: 1, status: 'DELIVERED', deliveryEntityId: 2, workerGroup: 'group1', vendorCode: 'LOCAL-FTP-2', entityCode: 'ENTITY-2', entityName: '实体 2', protocol: 'FTP', accountName: 'entity2' },
    { targetCount: 1, assignedCount: 1, completedCount: 0, status: 'BLOCKED', deliveryEntityId: 3, workerGroup: 'group2', vendorCode: 'LOCAL-FTP-3', entityCode: 'ENTITY-3', entityName: '实体 3', protocol: 'FTP', accountName: 'entity3' }
  ]
}

describe('DashboardView', () => {
  beforeEach(() => {
    vi.mocked(getSummary).mockResolvedValue(summary)
    vi.mocked(getTasks).mockResolvedValue({ items: [], total: 3 })
  })

  it('renders delivery targets and retains the last successful facts on refresh failure', async () => {
    const wrapper = mount(DashboardView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('实体 1')
    expect(wrapper.text()).toContain('LOCAL-FTP-1')
    expect(wrapper.text()).toContain('执行组 group1')
    expect(wrapper.text()).toContain('执行组 group2')
    expect(wrapper.text()).toContain('超期历史')
    expect(wrapper.text()).not.toContain('来源 ID')
    expect(wrapper.text()).not.toContain('PipelineVersion')
    vi.mocked(getSummary).mockRejectedValueOnce(new Error('offline'))
    await wrapper.vm.refresh()
    await flushPromises()
    expect(wrapper.text()).toContain('offline')
    expect(wrapper.text()).toContain('实体 1')
  })
})
