// ── Client → Server ──────────────────────────────────────────────────────────

export interface JoinMessage {
  type: 'join'
  deckerName: string
}

export interface ActionParams {
  newContent?: string | null
  precision?: 'NORMAL' | 'HIGH'
  hasValidPasscode?: boolean
  scannerDeviceRating?: number
  inactivitySeconds?: number
}

export interface ActionCommand {
  type: 'action'
  actionIndex: number
  params?: ActionParams
}

// ── Server → Client ──────────────────────────────────────────────────────────

export type Role = 'observer' | 'registered_decker' | 'active_controller'

export interface ControlMessage {
  type: 'control'
  role: Role
  deckerName?: string
}

export interface ActiveUtility {
  type: string
  rating: number
}

export interface DeckerStateDto {
  name: string
  location: string
  isPinnedByBlackIc: boolean
  physicalDamage: number
  physicalMaxBoxes: number
  mentalDamage: number
  mentalMaxBoxes: number
  hackingPool: number
  mcpRating: number
  activeUtilities: ActiveUtility[]
}

export type AlertStatus = 'NO_ALERT' | 'PASSIVE_ALERT' | 'ACTIVE_ALERT'
export type SecurityCode = 'BLUE' | 'GREEN' | 'ORANGE' | 'RED'
export type TopologyType = 'OPEN_ACCESS' | 'TIERED' | 'HOST_HOST' | 'PRIVATE_GRID'
export type SubsystemType = 'ACCESS' | 'CONTROL' | 'INDEX' | 'FILES' | 'SLAVE'

export type MatrixObjectDto =
  | { kind: 'GridNode'; index: number; name: string; region: string; alertStatus: AlertStatus; securityTally: number; ltgCount: number; connectedRtgCount: number }
  | { kind: 'LocalGrid'; index: number; name: string; parentRtgName: string; alertStatus: AlertStatus; securityTally: number; hostCount: number; pltgCount: number }
  | { kind: 'PrivateGrid'; index: number; name: string; owner: string; parentLtgName: string; alertStatus: AlertStatus; hostCount: number }
  | { kind: 'HostNode'; index: number; name: string; topologyType: TopologyType; offline: boolean; alertStatus: AlertStatus; securityCode: SecurityCode; securityTally: number }
  | { kind: 'HostSubsystem'; index: number; subsystemType: SubsystemType; description: string }
  | { kind: 'IcProgram'; index: number; name: string; rating: number; behavior: 'PROACTIVE' | 'REACTIVE'; guardedNodeType: string | null }
  | { kind: 'File'; index: number; name: string; isScrambleProtected: boolean; isPointer: boolean; sizeMp: number }
  | { kind: 'Device'; index: number; name: string; systemAddress: string }

export type ActionType = 'FREE' | 'SIMPLE' | 'COMPLEX'

export type AvailableActionDto =
  | { kind: 'LogonToRtg'; index: number; actionType: ActionType; rtgName: string }
  | { kind: 'LogonToLtg'; index: number; actionType: ActionType; ltgName: string }
  | { kind: 'LogonToPltg'; index: number; actionType: ActionType; pltgName: string }
  | { kind: 'LogonToHost'; index: number; actionType: ActionType; hostName: string }
  | { kind: 'GracefulLogoff'; index: number; actionType: ActionType }
  | { kind: 'JackOut'; index: number; actionType: ActionType }
  | { kind: 'Operation'; index: number; actionType: ActionType; operation: string; targetKind: string | null; targetName: string | null }

export interface StateMessage {
  type: 'state'
  role: Role
  decker: DeckerStateDto
  visibleObjects: MatrixObjectDto[]
  availableActions: AvailableActionDto[]
}

export interface ResultMessage {
  type: 'result'
  success: boolean
  deckerSuccesses?: number
  hostSuccesses?: number
  details: string
}

export interface ErrorMessage {
  type: 'error'
  message: string
}

export type ServerMessage = ControlMessage | StateMessage | ResultMessage | ErrorMessage

export type GameEvent =
  | { kind: 'result'; msg: ResultMessage }
  | { kind: 'error'; msg: ErrorMessage }
