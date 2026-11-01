import type { Filters, PackageItem, Summary, Timeline } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers }
  })
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`)
  return response.json()
}

function query(filters: Filters): string {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  return params.toString()
}

export const getSummary = (filters: Filters) => request<Summary>(`/dashboard/summary?${query(filters)}`)
export const getPackages = (filters: Filters) =>
  request<{ items: PackageItem[]; total: number }>(`/packages?${query({ ...filters, size: 100 })}`)
export const getPackageDetail = (id: number) => request<Record<string, unknown>>(`/packages/${id}`)
export const getTimeline = (id: number) => request<Timeline>(`/packages/${id}/timeline`)
export const retryPackage = (id: number, expectedFence: number, reason: string) =>
  request(`/packages/${id}/retry`, { method: 'POST', body: JSON.stringify({ expectedFence, reason }) })
export const resumeRun = (runId: number, fromNode: string, packageIds: number[], reason: string) =>
  request(`/runs/${runId}/resume`, { method: 'POST', body: JSON.stringify({ fromNode, packageIds, reason }) })
