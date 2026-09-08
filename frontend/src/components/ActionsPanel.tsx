import { useEffect, useState } from 'react'
import type { ActionParams, AvailableActionDto } from '../types/messages'
import SelectLocateModal from './SelectLocateModal'

interface Props {
  actions: AvailableActionDto[]
  isActiveTurn: boolean
  onAction: (index: number, params?: ActionParams) => void
}

function actionLabel(action: AvailableActionDto): string {
  switch (action.kind) {
    case 'LogonToRtg':    return `LOGON RTG: ${action.rtgName}`
    case 'AccessLtg':     return 'ACCESS LTG'
    case 'AccessHost':    return 'ACCESS HOST'
    case 'SelectLocateTarget': return `SELECT ${formatEnum(action.operation)}`
    case 'GracefulLogoff': return 'GRACEFUL LOGOFF'
    case 'JackOut':       return 'JACK OUT'
    case 'Operation':     return formatEnum(action.operation)
  }
}

function formatEnum(s: string) { return s.replace(/_/g, ' ') }

interface CardState {
  query: string
  newContent: string
  dataSize: number
  selectedTarget: string
}

function defaultCardState(): CardState {
  return { query: '', newContent: '', dataSize: 100, selectedTarget: '' }
}

function buildParams(paramKind: string | null, cs: CardState): ActionParams | undefined {
  if (paramKind === 'query')               return { query: cs.query }
  if (paramKind === 'newContent')          return { newContent: cs.newContent === '' ? null : cs.newContent }
  if (paramKind === 'dataSize')            return { dataSize: cs.dataSize }
  return undefined
}

/** Action kinds whose card must not fire on click — they require a dropdown selection + CONFIRM. */
const SELECTION_KINDS = new Set(['AccessLtg', 'AccessHost'])

const SAFE_ACTION_TYPES = new Set(['FREE', 'SIMPLE', 'COMPLEX'])

export default function ActionsPanel({ actions, isActiveTurn, onAction }: Props) {
  const [cardStates, setCardStates] = useState<Record<number, CardState>>({})
  const [focusedCards, setFocusedCards] = useState<Set<number>>(new Set())

  // `actions` is a fresh array on every STATE broadcast, so depending on it directly would wipe
  // in-progress input (search terms, edit content, stepper values) on any re-broadcast (F-1).
  // Reset only when the action set *semantically* changes — its per-index identity signature.
  const actionsSignature = actions
    .map((a) => `${a.index}:${a.kind === 'Operation' ? a.operation : a.kind}`)
    .join('|')

  useEffect(() => {
    setCardStates({})
    setFocusedCards(new Set())
  }, [actionsSignature])

  function getState(idx: number): CardState {
    return cardStates[idx] ?? defaultCardState()
  }

  function patchState(idx: number, patch: Partial<CardState>) {
    setCardStates(prev => ({ ...prev, [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch } }))
  }

  function handleClick(action: AvailableActionDto) {
    if (!isActiveTurn) return
    // Selection cards (Access LTG/Host, Select Locate Target) act only via their CONFIRM button.
    if (SELECTION_KINDS.has(action.kind)) return
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

  // Ticket 06: the locate selection is a special case — rendered as a modal dialog, not an inline
  // action card. Pull it out of the card list and drive the modal from it.
  const selectLocate = actions.find(
    (a): a is Extract<AvailableActionDto, { kind: 'SelectLocateTarget' }> => a.kind === 'SelectLocateTarget'
  )
  const cardActions = actions.filter(a => a.kind !== 'SelectLocateTarget')

  return (
    <div className="panel actions-panel">
      <div className="panel-header">ACTIONS</div>
      <div className="panel-body">
        {cardActions.length === 0 ? (
          <div className="no-data">[ NO ACTIONS AVAILABLE ]</div>
        ) : (
          cardActions.map((action) => {
            const paramKind = action.kind === 'Operation' ? action.paramKind : null
            const cs = getState(action.index)
            const disabled = !isActiveTurn
            const safeActionType = SAFE_ACTION_TYPES.has(action.actionType) ? action.actionType : 'UNKNOWN'

            return (
              <div
                key={action.index}
                className={`action-card ${disabled ? 'disabled' : ''}`}
                role="button"
                tabIndex={disabled ? -1 : 0}
                aria-disabled={disabled}
                onClick={() => handleClick(action)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    handleClick(action)
                  }
                }}
              >
                <div className="action-card-header">
                  <span className="action-kind">{actionLabel(action)}</span>
                  <span className={`action-type ${safeActionType}`}>{action.actionType}</span>
                </div>
                {action.kind === 'Operation' && action.targetName && (
                  <div className="action-target">▸ {action.targetName}</div>
                )}

                {(action.kind === 'AccessLtg' || action.kind === 'AccessHost') && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">TARGET</div>
                    {(() => {
                      const names = action.kind === 'AccessLtg' ? action.ltgNames : action.hostNames
                      const selected = cs.selectedTarget || names[0] || ''
                      return (
                        <>
                          <select
                            className="target-select"
                            value={selected}
                            onChange={e => patchState(action.index, { selectedTarget: e.target.value })}
                          >
                            {names.map(n => <option key={n} value={n}>{n}</option>)}
                          </select>
                          <button
                            className="confirm-btn"
                            disabled={disabled || !selected}
                            onClick={() => onAction(action.index, { targetName: selected })}
                          >
                            CONFIRM
                          </button>
                        </>
                      )
                    })()}
                  </div>
                )}

                {paramKind === 'query' && (
                  <div className="action-control" onClick={e => e.stopPropagation()}>
                    <div className="ctrl-label">SEARCH TERM</div>
                    <input
                      type="text"
                      className="query-input"
                      placeholder="Regex / *fragment*…"
                      value={cs.query}
                      onChange={e => patchState(action.index, { query: e.target.value })}
                    />
                    <div className="edit-hint">Vagueness is derived from the query shape</div>
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
      {selectLocate && isActiveTurn && (
        <SelectLocateModal
          action={selectLocate}
          onSelect={name => onAction(selectLocate.index, { targetName: name })}
          onCancel={() => onAction(selectLocate.index, {})}
        />
      )}
    </div>
  )
}
