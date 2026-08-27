import type { GameEvent } from '../types/messages'

const ERROR_LABELS: Record<string, string> = {
  not_your_turn:      'Not your turn',
  no_action_pending:  'No action pending',
  already_registered: 'Already registered',
  name_already_taken: 'Decker name already taken',
}

interface Props {
  events: GameEvent[]
  isActiveTurn: boolean
}

export default function NarrativePanel({ events, isActiveTurn }: Props) {
  return (
    <div className={`panel narrative-panel ${isActiveTurn ? 'active-turn' : ''}`}>
      <div className="panel-header">
        {isActiveTurn ? '▶ YOUR TURN — AWAITING ACTION' : 'NARRATIVE'}
      </div>
      <div className="panel-body">
        {events.length === 0 ? (
          <div className="no-data">[ AWAITING EVENTS ]</div>
        ) : (
          <div className="event-list">
            {events.map((ev, i) => {
              if (ev.kind === 'result') {
                const cls = ev.msg.success ? 'result-success' : 'result-failure'
                const hasDice =
                  ev.msg.deckerSuccesses !== undefined || ev.msg.hostSuccesses !== undefined
                return (
                  <div key={i} className={`event-item ${cls}`}>
                    <span className="event-badge">
                      {ev.msg.success ? 'SUCCESS' : 'FAILURE'}
                    </span>
                    {hasDice && (
                      <span className="event-dice">
                        [{ev.msg.deckerSuccesses ?? 0}d vs {ev.msg.hostSuccesses ?? 0}h]
                      </span>
                    )}
                    {' '}{ev.msg.details}
                  </div>
                )
              }
              return (
                <div key={i} className="event-item error">
                  <span className="event-badge">ERROR</span>
                  {' '}{ERROR_LABELS[ev.msg.message] ?? ev.msg.message}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
