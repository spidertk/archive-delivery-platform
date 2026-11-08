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
  deliveryArtifact?: Artifact
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
  metadataIds: string[]
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
  cutoffAt: string
  targets: SubmitTarget[]
}
