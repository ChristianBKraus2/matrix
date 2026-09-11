import { useEffect, useState } from 'react'
import type { ActionParams, AvailableActionDto, MatrixObjectDto } from '../types/messages'
import SelectLocateModal from './SelectLocateModal'

interface Props {
  actions: AvailableActionDto[]
  isActiveTurn: boolean
  onAction: (index: number, params?: ActionParams) => void
  selectedEntity: MatrixObjectDto | null
}

// ── Grouping ──────────────────────────────────────────────────────────────────

const NAVIGATE_KINDS = new Set(['LogonToRtg', 'AccessLtg', 'AccessHost', 'GracefulLogoff', 'JackOut'])
const NAVIGATE_OPS   = new Set(['DECRYPT_ACCESS'])
const LOCATE_OPS     = new Set(['LOCATE_ACCESS_NODE', 'LOCATE_FILE', 'LOCATE_SLAVE', 'LOCATE_IC'])
const HOST_OPS       = new Set(['ANALYZE_HOST', 'ANALYZE_SECURITY', 'ANALYZE_SUBSYSTEM'])
const IC_OPS         = new Set(['ANALYZE_IC'])
const ICON_OPS       = new Set(['ANALYZE_ICON'])
const FILE_OPS       = new Set(['DOWNLOAD_DATA', 'UPLOAD_DATA', 'EDIT_FILE', 'DECRYPT_FILE'])
const SLAVE_OPS      = new Set(['CONTROL_SLAVE', 'EDIT_SLAVE', 'MONITOR_SLAVE', 'DECRYPT_SLAVE'])

type Group = 'navigation' | 'locate' | 'host' | 'others'
type OthersSub = 'ic' | 'icon' | 'file' | 'slave' | 'misc'

function classifyAction(action: AvailableActionDto): Group {
  if (NAVIGATE_KINDS.has(action.kind)) return 'navigation'
  if (action.kind === 'Operation') {
    if (NAVIGATE_OPS.has(action.operation)) return 'navigation'
    if (LOCATE_OPS.has(action.operation))   return 'locate'
    if (HOST_OPS.has(action.operation))     return 'host'
  }
  return 'others'
}

function othersSubCategory(action: AvailableActionDto): OthersSub {
  if (action.kind !== 'Operation') return 'misc'
  if (IC_OPS.has(action.operation))    return 'ic'
  if (ICON_OPS.has(action.operation))  return 'icon'
  if (FILE_OPS.has(action.operation))  return 'file'
  if (SLAVE_OPS.has(action.operation)) return 'slave'
  return 'misc'
}

function filterOthers(actions: AvailableActionDto[], entity: MatrixObjectDto | null): AvailableActionDto[] {
  const kind = entity?.kind
  return actions.filter(a => {
    const sub = othersSubCategory(a)
    if (sub === 'misc') return true
    if (kind === 'IcProgram') return sub === 'ic' || sub === 'icon'
    if (kind === 'File')      return sub === 'file'
    if (kind === 'Device')    return sub === 'slave'
    return false
  })
}

// ── Label / card state helpers ────────────────────────────────────────────────

function actionLabel(action: AvailableActionDto): string {
  switch (action.kind) {
    case 'LogonToRtg':         return `LOGON RTG: ${action.rtgName}`
    case 'AccessLtg':          return 'ACCESS LTG'
    case 'AccessHost':         return 'ACCESS HOST'
    case 'SelectLocateTarget': return `SELECT ${formatEnum(action.operation)}`
    case 'GracefulLogoff':     return 'GRACEFUL LOGOFF'
    case 'JackOut':            return 'JACK OUT'
    case 'Operation':          return formatEnum(action.operation)
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
  if (paramKind === 'query')      return { query: cs.query }
  if (paramKind === 'newContent') return { newContent: cs.newContent === '' ? null : cs.newContent }
  if (paramKind === 'dataSize')   return { dataSize: cs.dataSize }
  return undefined
}

/** Action kinds whose card must not fire on click — they require a dropdown selection + CONFIRM. */
const SELECTION_KINDS = new Set(['AccessLtg', 'AccessHost'])

const SAFE_ACTION_TYPES = new Set(['FREE', 'SIMPLE', 'COMPLEX'])

// ── Component ─────────────────────────────────────────────────────────────────

export default function ActionsPanel({ actions, isActiveTurn, onAction, selectedEntity }: Props) {
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

  const navActions    = cardActions.filter(a => classifyAction(a) === 'navigation')
  const locateActions = cardActions.filter(a => classifyAction(a) === 'locate')
  const hostActions   = cardActions.filter(a => classifyAction(a) === 'host')
  const othersRaw     = cardActions.filter(a => classifyAction(a) === 'others')
  const othersActions = filterOthers(othersRaw, selectedEntity)

  function renderCard(action: AvailableActionDto) {
    const paramKind = action.kind === 'Operation' ? action.paramKind : null
    const cs = getState(action.index)
    const disabled = !isActiveTurn
    const safeActionType = SAFE_ACTION_TYPES.has(action.actionType) ? action.actionType : 'UNKNOWN'
    const badge = action.actionType === 'FREE' ? 'F' : action.actionType === 'SIMPLE' ? 'S' : null

    const isAccessAction = action.kind === 'AccessLtg' || action.kind === 'AccessHost'
    const accessNames = isAccessAction
      ? (action.kind === 'AccessLtg' ? action.ltgNames : action.hostNames)
      : []
    const accessSelected = cs.selectedTarget || accessNames[0] || ''

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
          {isAccessAction ? (
            <button
              className="confirm-btn"
              disabled={disabled || !accessSelected}
              onClick={e => { e.stopPropagation(); onAction(action.index, { targetName: accessSelected }) }}
            >
              OK
            </button>
          ) : (
            badge && <span className={`action-type ${safeActionType}`}>{badge}</span>
          )}
        </div>
        {action.kind === 'Operation' && action.targetName && (
          <div className="action-target">▸ {action.targetName}</div>
        )}

        {isAccessAction && (
          <div className="action-control" onClick={e => e.stopPropagation()}>
            <select
              className="target-select"
              value={accessSelected}
              onClick={e => e.stopPropagation()}
              onChange={e => patchState(action.index, { selectedTarget: e.target.value })}
            >
              {accessNames.map(n => <option key={n} value={n}>{n}</option>)}
            </select>
          </div>
        )}

        {paramKind === 'query' && (
          <div className="action-control" onClick={e => e.stopPropagation()}>
            <input
              type="text"
              className="query-input"
              placeholder="Search term…"
              value={cs.query}
              onChange={e => patchState(action.index, { query: e.target.value })}
            />
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
  }

  const groups = [
    { key: 'navigation', label: 'NAVIGATION', actions: navActions },
    { key: 'locate',     label: 'LOCATE',     actions: locateActions },
    { key: 'host',       label: 'HOST',       actions: hostActions },
    { key: 'others',     label: 'OTHERS',     actions: othersActions },
  ]

  return (
    <div className="panel actions-panel">
      <div className="panel-header">ACTIONS</div>
      <div className="panel-body">
        {groups.map(g => (
          <div key={g.key} className="action-group">
            <div className="action-group-header">{g.label}</div>
            <div className="action-group-body">
              {g.actions.length === 0 ? (
                <div className="no-data">[ NONE ]</div>
              ) : (
                g.actions.map(action => renderCard(action))
              )}
            </div>
          </div>
        ))}
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
