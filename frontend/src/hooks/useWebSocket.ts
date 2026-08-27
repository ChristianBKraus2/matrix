import { useCallback, useEffect, useReducer, useRef } from 'react'
import type {
  ActionCommand,
  ActionParams,
  ControlMessage,
  ErrorMessage,
  GameEvent,
  JoinMessage,
  ResultMessage,
  Role,
  ServerMessage,
  StateMessage,
} from '../types/messages'

interface WsState {
  connected: boolean
  role: Role | null
  deckerName: string | null
  gameState: StateMessage | null
  events: GameEvent[]
}

type WsAction =
  | { type: 'CONNECTED' }
  | { type: 'DISCONNECTED' }
  | { type: 'CONTROL'; msg: ControlMessage }
  | { type: 'STATE'; msg: StateMessage }
  | { type: 'RESULT'; msg: ResultMessage }
  | { type: 'ERROR'; msg: ErrorMessage }

function reducer(state: WsState, action: WsAction): WsState {
  switch (action.type) {
    case 'CONNECTED':
      return { ...state, connected: true }
    case 'DISCONNECTED':
      return { ...state, connected: false, role: null }
    case 'CONTROL':
      return {
        ...state,
        role: action.msg.role,
        deckerName: action.msg.deckerName ?? state.deckerName,
      }
    case 'STATE':
      return { ...state, role: action.msg.role, gameState: action.msg }
    case 'RESULT':
      return {
        ...state,
        events: [...state.events.slice(-19), { kind: 'result', msg: action.msg }],
      }
    case 'ERROR':
      return {
        ...state,
        events: [...state.events.slice(-19), { kind: 'error', msg: action.msg }],
      }
  }
}

const initialState: WsState = {
  connected: false,
  role: null,
  deckerName: null,
  gameState: null,
  events: [],
}

export function useWebSocket() {
  const [state, dispatch] = useReducer(reducer, initialState)
  const wsRef = useRef<WebSocket | null>(null)
  const pendingNameRef = useRef<string | null>(null)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const reconnectDelay = useRef(3000)

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const ws = new WebSocket(`${proto}//${window.location.host}/decker/ws`)
    wsRef.current = ws

    ws.onopen = () => {
      reconnectDelay.current = 3000
      dispatch({ type: 'CONNECTED' })
    }

    ws.onmessage = (ev: MessageEvent) => {
      try {
        const msg = JSON.parse(ev.data as string) as ServerMessage
        switch (msg.type) {
          case 'control':
            dispatch({ type: 'CONTROL', msg })
            if (msg.role === 'observer' && pendingNameRef.current) {
              const join: JoinMessage = { type: 'join', deckerName: pendingNameRef.current }
              ws.send(JSON.stringify(join))
            }
            break
          case 'state':
            dispatch({ type: 'STATE', msg })
            break
          case 'result':
            dispatch({ type: 'RESULT', msg })
            break
          case 'error':
            dispatch({ type: 'ERROR', msg })
            break
        }
      } catch {
        // ignore malformed frames
      }
    }

    ws.onclose = () => {
      dispatch({ type: 'DISCONNECTED' })
      reconnectTimer.current = setTimeout(() => {
        reconnectDelay.current = Math.min(reconnectDelay.current * 2, 30000)
        connect()
      }, reconnectDelay.current)
    }

    ws.onerror = () => ws.close()
  }, [])

  useEffect(() => {
    connect()
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
      wsRef.current?.close()
    }
  }, [connect])

  const join = useCallback((name: string) => {
    pendingNameRef.current = name
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      const msg: JoinMessage = { type: 'join', deckerName: name }
      wsRef.current.send(JSON.stringify(msg))
    }
  }, [])

  const sendAction = useCallback((actionIndex: number, params?: ActionParams) => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) return
    const msg: ActionCommand = {
      type: 'action',
      actionIndex,
      ...(params ? { params } : {}),
    }
    wsRef.current.send(JSON.stringify(msg))
  }, [])

  return { ...state, join, sendAction }
}
