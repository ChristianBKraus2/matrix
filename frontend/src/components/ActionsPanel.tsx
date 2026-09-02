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

function formatEnum(s: string) { return s.replace(/_/g, ' ') }

interface CardState {
  precision: 'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'
  query: string
  scannerDeviceRating: number
  newContent: string
  hasValidPasscode: boolean
  dataSize: number
}

function defaultCardState(): CardState {
  return { precision: 'NORMAL', query: '', scannerDeviceRating: 0, newContent: '', hasValidPasscode: false, dataSize: 100 }
}

function buildParams(paramKind: string | null, cs: CardState): ActionParams | undefined {
  if (paramKind === 'precision')           return { precision: cs.precision, query: cs.query }
  if (paramKind === 'newContent')          return { newContent: cs.newContent === '' ? null : cs.newContent }
  if (paramKind === 'scannerDeviceRating') return { scannerDeviceRating: cs.scannerDeviceRating }
  if (paramKind === 'hasValidPasscode')    return { hasValidPasscode: cs.hasValidPasscode }
  if (paramKind === 'dataSize')            return { dataSize: cs.dataSize }
  return undefined
}

const SAFE_ACTION_TYPES = new Set(['FREE', 'SIMPLE', 'COMPLEX'])

export default function ActionsPanel({ actions, isActiveTurn, onAction }: Props) {
  const [cardStates, setCardStates] = useState<Record<number, CardState>>({})
  const [focusedCards, setFocusedCards] = useState<Set<number>>(new Set())

  useEffect(() => {
    setCardStates({})
    setFocusedCards(new Set())
  }, [actions])

  function getState(idx: number): CardState {
    return cardStates[idx] ?? defaultCardState()
  }

  function patchState(idx: number, patch: Partial<CardState>) {
    setCardStates(prev => ({ ...prev, [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch } }))
  }

  function handleClick(action: AvailableActionDto) {
    if (!isActiveTurn) return
    const paramKind = action.kind === 'Operation' ? action.paramKind : null
    if (paramKind === 'newContent') {
      setFocusedCards(prev => {
        const next = new Set(prev)
        if (next.has(action.index)) next.delete(action.index)
        else next.add(action.index)
        return next
      })
      return
    }
    const params = buildParams(paramKind, getState(action.index))
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
            const paramKind = action.kind === 'Operation' ? action.paramKind : null
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

                {paramKind === 'precision' && (
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

                {paramKind === 'precision' && (
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

                {paramKind === 'scannerDeviceRating' && (
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

                {paramKind === 'newContent' && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    {focusedCards.has(action.index) ? (
                      <>
                        <textarea
                          className="edit-textarea"
                          placeholder="New file content…"
                          value={cs.newContent}
                          onChange={e => patchState(action.index, { newContent: e.target.value })}
                          rows={3}
                          maxLength={4096}
                          autoFocus
                        />
                        <div className="edit-hint">Leave empty to erase file</div>
                        <button
                          className="confirm-btn"
                          disabled={disabled}
                          onClick={() => onAction(action.index, buildParams('newContent', cs))}
                        >
                          CONFIRM
                        </button>
                      </>
                    ) : (
                      <div className="edit-placeholder">[ click to enter content ]</div>
                    )}
                  </div>
                )}

                {paramKind === 'hasValidPasscode' && (
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

                {paramKind === 'dataSize' && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">DATA SIZE (Mp)</div>
                    <div className="stepper">
                      <button
                        className="stepper-btn"
                        onClick={() => patchState(action.index, { dataSize: Math.max(1, cs.dataSize - 10) })}
                      >−</button>
                      <span>{cs.dataSize}</span>
                      <button
                        className="stepper-btn"
                        onClick={() => patchState(action.index, { dataSize: cs.dataSize + 10 })}
                      >+</button>
                    </div>
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
