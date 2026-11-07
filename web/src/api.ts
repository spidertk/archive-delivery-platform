import type { DeliveryTask, SubmitDelivery, Summary, Timeline } from './types'

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
export const submitDelivery = (delivery: SubmitDelivery) =>
  request<{ deliveryId: number }>('/deliveries', { method: 'POST', body: JSON.stringify(delivery) })
