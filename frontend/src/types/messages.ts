// ── Client → Server ──────────────────────────────────────────────────────────

export interface JoinMessage {
  type: 'join'
  deckerName: string
  reconnectToken?: string
}

export interface ActionParams {
  newContent?: string | null
  precision?: 'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'
  query?: string
  inactivitySeconds?: number
  hasValidPasscode?: boolean
  scannerDeviceRating?: number
  dataSize?: number
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
  reconnectToken?: string
}

export interface ActiveUtility {
  type: string
  rating: number
}

export interface DeckerStateDto {
  name: string
  location: string
  locationIndex: number | null
  isPinnedByBlackIc: boolean
  physicalDamage: number
  physicalMaxBoxes: number
  mentalDamage: number
  mentalMaxBoxes: number
  hackingPool: number
  mcpRating: number
  activeUtilities: ActiveUtility[]
}

// These union types mirror Kotlin enums serialised with .name (not @SerialName).
// If a Kotlin enum adds, removes, or renames a variant, update the matching type here.
//   AlertStatus  ↔ com.shadowrun.matrix.network.AlertStatus
//   SecurityCode ↔ com.shadowrun.matrix.common.SecurityCode
//   TopologyType ↔ com.shadowrun.matrix.network.TopologyType
//   SubsystemType ↔ com.shadowrun.matrix.common.SubsystemType
//   IcProgram.behavior ↔ com.shadowrun.matrix.ic.IcBehavior
export type AlertStatus = 'NO_ALERT' | 'PASSIVE_ALERT' | 'ACTIVE_ALERT'
export type SecurityCode = 'BLUE' | 'GREEN' | 'ORANGE' | 'RED'
export type TopologyType = 'OPEN_ACCESS' | 'TIERED' | 'HOST_HOST' | 'PRIVATE_GRID'
export type SubsystemType = 'ACCESS' | 'CONTROL' | 'INDEX' | 'FILES' | 'SLAVE'

export type MatrixObjectDto =
  | { kind: 'GridNode'; index: number; name: string; region: string; alertStatus: AlertStatus; securityCode: SecurityCode; securityTally: number; ltgCount: number; connectedRtgCount: number }
  | { kind: 'LocalGrid'; index: number; name: string; parentRtgName: string; alertStatus: AlertStatus; securityTally: number; hostCount: number; pltgCount: number }
  | { kind: 'PrivateGrid'; index: number; name: string; owner: string; parentLtgName: string; alertStatus: AlertStatus; securityCode: SecurityCode; hostCount: number }
  | { kind: 'HostNode'; index: number; name: string; topologyType: TopologyType; offline: boolean; alertStatus: AlertStatus; securityCode: SecurityCode; securityTally: number }
  | { kind: 'HostSubsystem'; index: number; subsystemType: SubsystemType; description: string }
  | { kind: 'IcProgram'; index: number; name: string; analyzed: boolean; rating: number | null; behavior: 'PROACTIVE' | 'REACTIVE' | null; guardedNodeType: string | null }
  | { kind: 'File'; index: number; name: string; isScrambleProtected: boolean; isPointer: boolean; sizeMp: number }
  | { kind: 'Device'; index: number; name: string; systemAddress: string }

export type ActionType = 'FREE' | 'SIMPLE' | 'COMPLEX'

export type SystemOperation =
  | 'ANALYZE_HOST' | 'ANALYZE_IC' | 'ANALYZE_ICON' | 'ANALYZE_SECURITY' | 'ANALYZE_SUBSYSTEM'
  | 'CONTROL_SLAVE' | 'DECRYPT_ACCESS' | 'DECRYPT_FILE' | 'DECRYPT_SLAVE' | 'DOWNLOAD_DATA'
  | 'EDIT_FILE' | 'EDIT_SLAVE' | 'INVOKE_MEDIC' | 'LOCATE_ACCESS_NODE'
  | 'LOCATE_FILE' | 'LOCATE_IC' | 'LOCATE_SLAVE'
  | 'MAKE_COMCALL' | 'MONITOR_SLAVE' | 'NULL_OPERATION'
  | 'RELOCATE_ICON' | 'TAP_COMCALL' | 'UPLOAD_DATA'

export type AvailableActionDto =
  | { kind: 'LogonToRtg'; index: number; actionType: ActionType; rtgName: string }
  | { kind: 'LogonToLtg'; index: number; actionType: ActionType; ltgName: string }
  | { kind: 'LogonToPltg'; index: number; actionType: ActionType; pltgName: string }
  | { kind: 'LogonToHost'; index: number; actionType: ActionType; hostName: string }
  | { kind: 'GracefulLogoff'; index: number; actionType: ActionType }
  | { kind: 'JackOut'; index: number; actionType: ActionType }
  | { kind: 'Operation'; index: number; actionType: ActionType; operation: SystemOperation; paramKind: 'precision' | 'hasValidPasscode' | 'scannerDeviceRating' | 'newContent' | 'dataSize' | null; targetKind: string | null; targetName: string | null }

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
  deckerSuccesses: number
  hostSuccesses: number
  details: string
}

export interface ErrorMessage {
  type: 'error'
  message: ErrorCode
  details?: string
}

export type ErrorCode =
  | 'not_your_turn'
  | 'no_action_pending'
  | 'already_registered'
  | 'name_already_taken'
  | 'name_too_long'
  | 'unknown_message_type'
  | 'bad_request'
  | 'server_full'

export type ServerMessage = ControlMessage | StateMessage | ResultMessage | ErrorMessage

export type GameEvent =
  | { kind: 'result'; msg: ResultMessage }
  | { kind: 'error'; msg: ErrorMessage }
