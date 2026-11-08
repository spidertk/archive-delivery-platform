export interface Summary {
  deliveryStatus: string
  taskTotal: number
  targetTotal: number
  succeeded: number
  blocked: number
  retrying: number
  waiting: number
  expired: number
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

export interface DeliveryTask {
  taskId: number
  deliveryId: number
  dataId: number
  deliveryEntityId: number
  workerGroup: string
  targetSeq: number
  vendorCode: string
  entityName: string
  accountName: string
  artifactSeq: number
  deliveryDate: string
  cutoffAt: string
  originalFileName: string
  newFileName: string
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
  supersededByTaskId?: number
  artifacts: Artifact[]
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
  deliveryEntityId: number
}

export interface SubmitDelivery {
  sourceId: number
  pipelineVersionId: number
  deliveryDate: string
  cutoffAt: string
  deliveryBatch: string
  idempotencyKey: string
  targets: SubmitTarget[]
}
