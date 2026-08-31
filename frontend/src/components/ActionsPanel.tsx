import { useEffect, useState } from 'react'
import type { ActionParams, AvailableActionDto } from '../types/messages'

interface Props {
  actions: AvailableActionDto[]
  isActiveTurn: boolean
  onAction: (index: number, params?: ActionParams) => void
}

function actionLabel(action: AvailableActionDto): string {
  switch (action.kind) {
    case 'LogonToRtg':    return `LOGON RTG: ${action.rtgName}`
    case 'LogonToLtg':    return `LOGON LTG: ${action.ltgName}`
    case 'LogonToPltg':   return `LOGON PLTG: ${action.pltgName}`
    case 'LogonToHost':   return `LOGON HOST: ${action.hostName}`
    case 'GracefulLogoff': return 'GRACEFUL LOGOFF'
    case 'JackOut':       return 'JACK OUT'
    case 'Operation':     return formatEnum(action.operation)
  }
}

function operationOf(action: AvailableActionDto): string | null {
  return action.kind === 'Operation' ? action.operation : null
}

function formatEnum(s: string) { return s.replace(/_/g, ' ') }

function needsPrecision(op: string | null) {
  return op === 'LOCATE_FILE' || op === 'LOCATE_SLAVE' || op === 'LOCATE_ACCESS_NODE'
}

function needsQuery(op: string | null) {
  return op === 'LOCATE_FILE' || op === 'LOCATE_SLAVE' || op === 'LOCATE_ACCESS_NODE'
}

function needsScanner(op: string | null)    { return op === 'TAP_COMCALL' }
function needsEdit(op: string | null)       { return op === 'EDIT_FILE' }
function needsInactivity(op: string | null) { return op === 'NULL_OPERATION' }
function needsPasscode(op: string | null)   { return op === 'MAKE_COMCALL' }

interface CardState {
  precision: 'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'
  query: string
  scannerDeviceRating: number
  newContent: string
  inactivitySeconds: number
  hasValidPasscode: boolean
}

function defaultCardState(): CardState {
  return { precision: 'NORMAL', query: '', scannerDeviceRating: 0, newContent: '', inactivitySeconds: 0, hasValidPasscode: false }
}

function buildParams(op: string | null, cs: CardState): ActionParams | undefined {
  if (needsPrecision(op)) return { precision: cs.precision, query: cs.query }
  if (needsEdit(op))      return { newContent: cs.newContent === '' ? null : cs.newContent }
  if (needsInactivity(op)) return { inactivitySeconds: cs.inactivitySeconds }
  if (needsScanner(op))   return { scannerDeviceRating: cs.scannerDeviceRating }
  if (needsPasscode(op))  return { hasValidPasscode: cs.hasValidPasscode }
  return undefined
}

const SAFE_ACTION_TYPES = new Set(['FREE', 'SIMPLE', 'COMPLEX'])

export default function ActionsPanel({ actions, isActiveTurn, onAction }: Props) {
  const [cardStates, setCardStates] = useState<Record<number, CardState>>({})

  useEffect(() => {
    setCardStates({})
  }, [actions])

  function getState(idx: number): CardState {
    return cardStates[idx] ?? defaultCardState()
  }

  function patchState(idx: number, patch: Partial<CardState>) {
    setCardStates(prev => ({ ...prev, [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch } }))
  }

  function handleClick(action: AvailableActionDto) {
    if (!isActiveTurn) return
    const op = operationOf(action)
    const params = buildParams(op, getState(action.index))
    onAction(action.index, params)
  }

  return (
    <div className="panel actions-panel">
      <div className="panel-header">ACTIONS</div>
      <div className="panel-body">
        {actions.length === 0 ? (
          <div className="no-data">[ NO ACTIONS AVAILABLE ]</div>
        ) : (
          actions.map((action) => {
            const op = operationOf(action)
            const cs = getState(action.index)
            const disabled = !isActiveTurn
            const safeActionType = SAFE_ACTION_TYPES.has(action.actionType) ? action.actionType : 'UNKNOWN'

            return (
              <div
                key={action.index}
                className={`action-card ${disabled ? 'disabled' : ''}`}
                onClick={() => handleClick(action)}
              >
                <div className="action-card-header">
                  <span className="action-kind">{actionLabel(action)}</span>
                  <span className={`action-type ${safeActionType}`}>{action.actionType}</span>
                </div>
                {action.kind === 'Operation' && action.targetName && (
                  <div className="action-target">▸ {action.targetName}</div>
                )}

                {needsQuery(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">SEARCH TERM</div>
                    <input
                      type="text"
                      className="query-input"
                      placeholder="Search term…"
                      value={cs.query}
                      onChange={e => patchState(action.index, { query: e.target.value })}
                    />
                  </div>
                )}

                {needsPrecision(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">PRECISION</div>
                    {(['VERY_VAGUE', 'VAGUE', 'NORMAL', 'SPECIFIC', 'VERY_SPECIFIC'] as const).map(v => (
                      <button
                        key={v}
                        className={`toggle-btn ${cs.precision === v ? 'active' : ''}`}
                        onClick={() => patchState(action.index, { precision: v })}
                      >
                        {formatEnum(v)}
                      </button>
                    ))}
                  </div>
                )}

                {needsScanner(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">SCANNER RATING</div>
                    <div className="stepper">
                      <button
                        className="stepper-btn"
                        onClick={() => patchState(action.index, { scannerDeviceRating: Math.max(0, cs.scannerDeviceRating - 1) })}
                      >−</button>
                      <span>{cs.scannerDeviceRating}</span>
                      <button
                        className="stepper-btn"
                        disabled={cs.scannerDeviceRating >= 10}
                        onClick={() => patchState(action.index, { scannerDeviceRating: cs.scannerDeviceRating + 1 })}
                      >+</button>
                    </div>
                  </div>
                )}

                {needsEdit(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <textarea
                      className="edit-textarea"
                      placeholder="New file content…"
                      value={cs.newContent}
                      onChange={e => patchState(action.index, { newContent: e.target.value })}
                      rows={3}
                      maxLength={4096}
                    />
                    <div className="edit-hint">Leave empty to erase file</div>
                  </div>
                )}

                {needsInactivity(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">INACTIVITY (seconds)</div>
                    <input
                      type="number"
                      className="inactivity-input"
                      min={0}
                      max={3600}
                      step={1}
                      value={cs.inactivitySeconds}
                      onChange={e => patchState(action.index, { inactivitySeconds: Math.min(3600, Math.max(0, Number(e.target.value) || 0)) })}
                    />
                  </div>
                )}

                {needsPasscode(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">VALID PASSCODE</div>
                    <button
                      className={`toggle-btn ${cs.hasValidPasscode ? 'active' : ''}`}
                      onClick={() => patchState(action.index, { hasValidPasscode: !cs.hasValidPasscode })}
                    >
                      {cs.hasValidPasscode ? 'YES' : 'NO'}
                    </button>
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
