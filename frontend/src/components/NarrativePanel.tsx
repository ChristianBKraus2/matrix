import { useEffect, useRef } from 'react'
import type { GameEvent } from '../types/messages'

const ERROR_LABELS: Record<string, string> = {
  not_your_turn:        'Not your turn',
  no_action_pending:    'No action pending',
  already_registered:   'Already registered',
  name_already_taken:   'Decker name already taken',
  name_too_long:        'Name too long',
  unknown_message_type: 'Unknown message type',
  bad_request:          'Bad request',
  server_full:          'Server at capacity',
}

interface Props {
  events: GameEvent[]
  isActiveTurn: boolean
}

export default function NarrativePanel({ events, isActiveTurn }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [events])
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
                return (
                  <div key={i} className={`event-item ${cls}`}>
                    <span className="event-badge">
                      {ev.msg.success ? 'SUCCESS' : 'FAILURE'}
                    </span>
                    <span className="event-dice">
                      [{ev.msg.deckerSuccesses}d vs {ev.msg.hostSuccesses}h]
                    </span>
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
            <div ref={bottomRef} />
          </div>
        )}
      </div>
    </div>
  )
}
