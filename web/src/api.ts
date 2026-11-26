import type { CreateDeliveryBatch, DeliveryTask, SubmitDelivery, Summary, Timeline, IngestMonitorStatus, IngestTrend, IngestChange } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers }
  })
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`)
  return response.json()
}

export const getSummary = (deliveryId: number) => request<Summary>(`/dashboard/summary?deliveryId=${deliveryId}`)
export const getTasks = (deliveryId: number) => request<{ items: DeliveryTask[]; total: number }>(`/deliveries/${deliveryId}/tasks`)
export const getTimeline = (taskId: number) => request<Timeline>(`/tasks/${taskId}/timeline`)
export const retryTask = (taskId: number, expectedFence: number, reason: string) =>
  request(`/tasks/${taskId}/retry`, { method: 'POST', body: JSON.stringify({ expectedFence, reason }) })
export const resumeDelivery = (deliveryId: number, fromNode: string, taskIds: number[], reason: string) =>
  request(`/deliveries/${deliveryId}/resume`, { method: 'POST', body: JSON.stringify({ fromNode, taskIds, reason }) })
export const pauseDelivery = (deliveryId: number, reason: string) =>
  request(`/deliveries/${deliveryId}/pause`, { method: 'POST', body: JSON.stringify({ reason }) })
export const submitDelivery = (delivery: SubmitDelivery) =>
  request<{ deliveryId: number }>('/deliveries', { method: 'POST', body: JSON.stringify(delivery) })
export const createDeliveryBatch = (batch: CreateDeliveryBatch) =>
  request<{ deliveryId: number }>('/delivery-batches', { method: 'POST', body: JSON.stringify(batch) })
export const getIngestMonitor = () => request<IngestMonitorStatus>('/ingest-sources/monitor')
export const getIngestTrend = (sourceId: number, hours: number) =>
  request<IngestTrend[]>(`/ingest-sources/${sourceId}/trend?hours=${hours}`)

export const getIngestChanges = (sourceId: number, hours: number) => request<IngestChange[]>(`/ingest-sources/${sourceId}/changes?hours=${hours}&limit=200`)

type EntityEndpoint = { schemaVersion: 1; host: string; port: number; rootPath: string }
// 地址修复提交旧值作并发条件；账户和机器组由服务端保护，不从此入口修改。
export const updateEntityEndpoint = (vendorCode: string, entityCode: string, expectedEndpointLocator: EntityEndpoint, endpointLocator: EntityEndpoint) =>
  request(`/delivery-entities/${encodeURIComponent(vendorCode)}/${encodeURIComponent(entityCode)}/endpoint`, {
    method: 'PUT', body: JSON.stringify({ expectedEndpointLocator, endpointLocator })
  })

// 重分配保持完整包和源顺序，实际执行组由后端发布的 Pipeline 解析。
export const redistributeBatch = (deliveryId: number, deliveryDate: string, expectedRemainingPackages: number,
  targets: { vendorCode: string; entityCode: string; targetCount: number }[], reason: string) =>
  request(`/delivery-batches/${deliveryId}/redistribute`, { method: 'POST',
    body: JSON.stringify({ deliveryDate, expectedRemainingPackages, targets, reason }) })
