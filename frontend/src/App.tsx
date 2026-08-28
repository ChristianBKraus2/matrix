import { useEffect, useState } from 'react'
import { useWebSocket } from './hooks/useWebSocket'
import DeckerPanel from './components/DeckerPanel'
import LocationPanel from './components/LocationPanel'
import EntitiesPanel from './components/EntitiesPanel'
import ActionsPanel from './components/ActionsPanel'
import NarrativePanel from './components/NarrativePanel'
import type { GameEvent } from './types/messages'

const ERROR_LABELS: Record<string, string> = {
  not_your_turn:      'Not your turn',
  no_action_pending:  'No action pending',
  already_registered: 'Already registered',
  name_already_taken: 'Decker name already taken',
  name_too_long:      'Decker name too long (max 32 characters)',
  content_too_large:  'File content too large (max 4096 bytes)',
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
  const [error, setError] = useState('')

  useEffect(() => {
    const last = events[events.length - 1]
    if (last?.kind === 'error') {
      setError(ERROR_LABELS[last.msg.message] ?? last.msg.message)
    }
  }, [events])

  const handleSubmit = () => {
    if (!name.trim()) return
    setError('')
    onJoin(name.trim())
  }

  return (
    <div className="join-screen">
      <div className="join-box">
        <div className="join-title">MATRIX OF SHADOWRUN</div>
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
