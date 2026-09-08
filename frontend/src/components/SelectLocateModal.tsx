import { useEffect } from 'react'
import type { AvailableActionDto } from '../types/messages'

interface Props {
  /** The pending SelectLocateTarget action carrying the located candidates. */
  action: Extract<AvailableActionDto, { kind: 'SelectLocateTarget' }>
  onSelect: (targetName: string) => void
  onCancel: () => void
}

/** Fixed number of rows the selection list always shows, so the dialog never resizes. */
const ROW_COUNT = 5

function formatEnum(s: string) { return s.replace(/_/g, ' ') }

/**
 * Ticket 06: locate selection is a special case — not an inline action card. It pops up as a modal
 * dialog with a fixed-size list of exactly ROW_COUNT rows. Real candidates are clickable; the
 * remaining rows are inert padding so the dialog keeps a constant size regardless of match count.
 * Esc or a backdrop click dismisses without storing an address.
 */
export default function SelectLocateModal({ action, onSelect, onCancel }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCancel() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  const rows = Array.from({ length: ROW_COUNT }, (_, i) => action.candidates[i] ?? null)

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal-dialog" onClick={e => e.stopPropagation()}>
        <div className="modal-title">SELECT {formatEnum(action.operation)}</div>
        <div className="select-list">
          {rows.map((name, i) =>
            name !== null ? (
              <div
                key={name}
                className="select-row"
                role="button"
                tabIndex={0}
                onClick={() => onSelect(name)}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onSelect(name)
                  }
                }}
              >
                {name}
              </div>
            ) : (
              <div key={`empty-${i}`} className="select-row empty" aria-hidden="true" />
            )
          )}
        </div>
        <div className="modal-hint">Click a target to store its address · Esc to cancel</div>
      </div>
    </div>
  )
}
