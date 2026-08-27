import { useState } from 'react'
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
    case 'Operation':     return action.operation.replace(/_/g, ' ')
  }
}

function operationOf(action: AvailableActionDto): string | null {
  return action.kind === 'Operation' ? action.operation : null
}

function needsPrecision(op: string | null) {
  return op === 'LOCATE_FILE' || op === 'LOCATE_SLAVE' || op === 'LOCATE_ACCESS_NODE'
}

function needsPasscode(op: string | null) { return op === 'MAKE_COMCALL' }
function needsScanner(op: string | null)  { return op === 'TAP_COMCALL' }
function needsEdit(op: string | null)     { return op === 'EDIT_FILE' }

interface CardState {
  precision: 'NORMAL' | 'HIGH'
  hasValidPasscode: boolean
  scannerDeviceRating: number
  newContent: string
}

function defaultCardState(): CardState {
  return { precision: 'NORMAL', hasValidPasscode: false, scannerDeviceRating: 0, newContent: '' }
}

function buildParams(op: string | null, cs: CardState): ActionParams | undefined {
  if (needsPrecision(op)) return { precision: cs.precision }
  if (needsPasscode(op))  return { hasValidPasscode: cs.hasValidPasscode }
  if (needsScanner(op))   return { scannerDeviceRating: cs.scannerDeviceRating }
  if (needsEdit(op))      return { newContent: cs.newContent === '' ? null : cs.newContent }
  return undefined
}

export default function ActionsPanel({ actions, isActiveTurn, onAction }: Props) {
  const [cardStates, setCardStates] = useState<Record<number, CardState>>({})

  function getState(idx: number): CardState {
    return cardStates[idx] ?? defaultCardState()
  }

  function patchState(idx: number, patch: Partial<CardState>) {
    setCardStates(prev => ({ ...prev, [idx]: { ...getState(idx), ...patch } }))
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

            return (
              <div
                key={action.index}
                className={`action-card ${disabled ? 'disabled' : ''}`}
                onClick={() => handleClick(action)}
              >
                <div className="action-card-header">
                  <span className="action-kind">{actionLabel(action)}</span>
                  <span className={`action-type ${action.actionType}`}>{action.actionType}</span>
                </div>
                {action.kind === 'Operation' && action.targetName && (
                  <div className="action-target">▸ {action.targetName}</div>
                )}

                {/* Inline controls — stop click propagation so changing a control doesn't fire action */}
                {needsPrecision(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">PRECISION</div>
                    {(['NORMAL', 'HIGH'] as const).map(v => (
                      <button
                        key={v}
                        className={`toggle-btn ${cs.precision === v ? 'active' : ''}`}
                        onClick={() => patchState(action.index, { precision: v })}
                      >
                        {v}
                      </button>
                    ))}
                  </div>
                )}

                {needsPasscode(op) && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">VALID PASSCODE</div>
                    {(['NO', 'YES'] as const).map(v => {
                      const val = v === 'YES'
                      return (
                        <button
                          key={v}
                          className={`toggle-btn ${cs.hasValidPasscode === val ? 'active' : ''}`}
                          onClick={() => patchState(action.index, { hasValidPasscode: val })}
                        >
                          {v}
                        </button>
                      )
                    })}
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
                    />
                    <div className="edit-hint">Leave empty to erase file</div>
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
