import { useState } from 'react'
import { useWebSocket } from './hooks/useWebSocket'
import DeckerPanel from './components/DeckerPanel'
import LocationPanel from './components/LocationPanel'
import EntitiesPanel from './components/EntitiesPanel'
import ActionsPanel from './components/ActionsPanel'
import NarrativePanel from './components/NarrativePanel'
import type { ErrorCode, GameEvent } from './types/messages'

const ERROR_LABELS: Record<ErrorCode, string> = {
  not_your_turn:         'Not your turn',
  no_action_pending:     'No action pending',
  already_registered:    'Already registered',
  name_already_taken:    'Decker name already taken',
  name_too_long:         'Decker name too long (max 32 characters)',
  unknown_message_type:  'Unknown message type',
  bad_request:           'Bad request',
  server_full:           'Server at capacity',
}

function JoinScreen({
  connected,
  events,
  onJoin,
}: {
  connected: boolean
  events: GameEvent[]
  onJoin: (name: string) => void
}) {
  const [name, setName] = useState('')
  // Suppress errors that predate the most recent join attempt: an error is shown only when the
  // latest event is an error that arrived after the last submit (F-5). Derived during render — no
  // effect, no stale error left dangling after a subsequent non-error event.
  const [ackedEventCount, setAckedEventCount] = useState(0)

  const last = events[events.length - 1]
  const error =
    last?.kind === 'error' && events.length > ackedEventCount
      ? ERROR_LABELS[last.msg.message] ?? last.msg.message
      : ''

  const handleSubmit = () => {
    if (!name.trim()) return
    setAckedEventCount(events.length)
    onJoin(name.trim())
  }

  return (
    <div className="join-screen">
      <div className="join-box">
        <div className="join-title">MATRIX OF SHADOWRUN v1.0</div>
        <div className="join-subtitle">▸ CONNECT TO THE GRID ◂</div>

        {!connected ? (
          <div className="join-status blink">ESTABLISHING CONNECTION...</div>
        ) : (
          <div className="join-form">
            <label className="join-label">DECKER HANDLE</label>
            <input
              className="join-input"
              value={name}
              onChange={e => setName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleSubmit()}
              autoFocus
              maxLength={32}
            />
            {error && <div className="join-error">{error}</div>}
            <button
              className="join-btn"
              onClick={handleSubmit}
              disabled={!name.trim()}
            >
              JACK IN
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

export default function App() {
  const ws = useWebSocket()
  const isRegistered = ws.role === 'registered_decker' || ws.role === 'active_controller'

  if (!isRegistered) {
    return (
      <JoinScreen
        connected={ws.connected}
        events={ws.events}
        onJoin={ws.join}
      />
    )
  }

  if (!ws.gameState) {
    return (
      <div className="waiting blink">SYNCHRONISING WITH HOST...</div>
    )
  }

  const { gameState, role, sendAction, events } = ws

  return (
    <div className="game-grid">
      <LocationPanel gameState={gameState} />
      <DeckerPanel decker={gameState.decker} />
      <NarrativePanel events={events} isActiveTurn={role === 'active_controller'} />
      <EntitiesPanel visibleObjects={gameState.visibleObjects} />
      <ActionsPanel
        actions={gameState.availableActions}
        isActiveTurn={role === 'active_controller'}
        onAction={sendAction}
      />
    </div>
  )
}
