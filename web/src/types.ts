export interface Summary {
  deliveryStatus: string
  taskTotal: number
  targetTotal: number
  succeeded: number
  blocked: number
  retrying: number
  waiting: number
  paused: number
  expired: number
  batchPackageTotal: number
  deliveredPackageCount: number
  originalFileCount: number
  targets: DeliveryTarget[]
}

export interface DeliveryTarget {
  targetCount: number
  assignedCount: number
  completedCount: number
  status: string
  deliveryEntityId: number
  workerGroup: string
  vendorCode: string
  entityCode: string
  entityName: string
  protocol: string
  accountName: string
}

export interface Artifact {
  artifactSeq: number
  originalFileName: string
  newFileName: string
  sizeBytes: number
  sha256: string
  token: number
  file_mapping: Record<string, string>
}

export interface ArtifactPair {
  dataId: number
  sourceArtifact: Artifact
}

export interface DeliveryTask {
  taskId: number
  deliveryId: number
  sourceDataIds: number[]
  deliveryEntityId: number
  workerGroup: string
  vendorCode: string
  entityCode: string
  entityName: string
  accountName: string
  deliveryDate: string
  cutoffAt: string
  newFileName: string
  ftpFileName: string
  ftpSequence: number | null
  sizeBytes: number
  tokenCount: number
  sha256?: string
  currentNodeId: string
  executionStatus: string
  stageCode: string
  displayName: string
  fence: number
  retryCount: number
  lastErrorCode?: string
  artifacts: ArtifactPair[]
}

export interface NodeItem {
  processId: number
  taskId: number
  nodeId: string
  nodeType: string
  stageCode: string
  attempt: number
  status: string
  workerInstanceId?: string
  outputLocator: Record<string, unknown>
  errorCode?: string
  errorDetail?: string
  startedAt: string
  finishedAt?: string
}

export interface DeliveryEvent {
  eventId: number
  taskId?: number
  processId?: number
  deliveryEntityId?: number
  eventType: string
  severity: string
  reasonCode?: string
  message?: string
  payload: Record<string, unknown>
  occurredAt: string
}

export interface Timeline {
  taskId: number
  nodes: NodeItem[]
  events: DeliveryEvent[]
}

export interface SubmitTarget {
  targetCount: number
  vendorCode: string
  entityCode: string
}

export interface SubmitDelivery {
  ossUri: string
  pipelineCode: string
  deliveryDate: string
  deliveryBatch: string
  targets: SubmitTarget[]
}

export interface CreateDeliveryBatch {
  ossUri: string
  pipelineCode: string
  deliveryDate: string
  deliveryBatch: string
}

export interface IngestChannel {
  sourceId: number
  sourceIds: number[]
  channelKey: string
  bucket: string
  channelCode: string
  ossUri: string
  availability: string
  phase: string
  observedAt?: string
  continuousSince?: string
  lastGrowthAt?: string
  objectCount?: number
  sizeBytes?: number
  lastErrorCode?: string
  pendingNotifications: number
  monitoringSince?: string
  incomingTotalBytes: number
  incomingTodayBytes: number
  deliveryTotalBytes: number
  deliveryTodayBytes: number
  destinations: IngestDestination[]
  costEstimate: { currency: string; monthlyStorage: number }
}

export interface IngestDestination {
  bucket: string
  files: number
  totalBytes: number
  todayFiles: number
  todayBytes: number
  lastTransferredAt?: string
}

export interface MonitorPricing {
  currency: 'CNY'
  storagePerGbMonth: number
  sourceCurrency: 'USD'
  sourceStoragePerGbMonth: number
  usdCnyRate: number
  exchangeRateDate: string
  exchangeRateSource: string
  bytesPerGb: number
  basis: string
  region: string
  storageClass: string
  sourceUrl: string
  verifiedOn: string
}

export interface BucketCost {
  bucket: string
  storedBytes: number
  deliveredBytes: number
  monthlyStorage: number
  currency: string
}

export interface IngestMonitorStatus {
  enabled: boolean
  pollIntervalSeconds: number
  continuousPeriodSeconds: number
  reportIntervalSeconds: number
  dailyTimezone: string
  channels: IngestChannel[]
  statisticsDate: string
  pricing: MonitorPricing
  bucketCosts: BucketCost[]
  storageSummary: { currency: 'CNY'; storedBytes: number; monthlyStorage: number; periodMonths: number }
}

export interface IngestTrend {
  hour: string
  objectCount: number
  sizeBytes: number
  addedObjects: number
  addedBytes: number
  changedObjects: number
  changedBytes: number
  removedObjects: number
  removedBytes: number
}

export interface IngestChange {
  directory: string
  addedObjects: number
  addedBytes: number
  changedObjects: number
  changedBytes: number
  observedAt: string
}
