export interface Summary {
  discovered: number
  planned: number
  repaired: number
  outputTotal: number
  delivered: number
  blocked: number
  retrying: number
  waiting: number
  rolledOver: number
  backlog: number
  ungrouped: number
  activeSourceSlots: number
  activeOutputSlots: number
  nextCutoff?: string
  groups: GroupProgress[]
}

export interface GroupProgress {
  lineId: number
  outputTotal: number
  grouped: number
  hzUploaded: number
  sgVerified: number
  sgDownloaded: number
  published: number
  delivered: number
  blocked: number
  retrying: number
  waiting: number
}

export interface PackageItem {
  id: number
  runId: number
  status: string
  runStatus: string
  currentNode: string
  packageName: string
  lineId: number
  retryCount: number
  fence: number
  rolloverCount: number
  artifactSha: string
  hzPath?: string
  sgPath?: string
  stagingPath?: string
  publishedPath?: string
  errorCode?: string
}

export interface Timeline {
  nodes: Array<Record<string, unknown>>
  events: Array<Record<string, unknown>>
  audits: Array<Record<string, unknown>>
}

export interface Filters {
  runId: number
  deliveryDate?: string
  archiveDate?: string
  lineId?: number
  rolledOver?: boolean
  status?: string
  keyword?: string
  page?: number
  size?: number
}
