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
  eventSeq: number
}

type WsAction =
  | { type: 'CONNECTED' }
  | { type: 'DISCONNECTED' }
  | { type: 'CONTROL'; msg: ControlMessage }
  | { type: 'STATE'; msg: StateMessage }
  | { type: 'RESULT'; msg: ResultMessage }
  | { type: 'ERROR'; msg: ErrorMessage }
  | { type: 'CLEAR_EVENTS' }

function reducer(state: WsState, action: WsAction): WsState {
  switch (action.type) {
    case 'CONNECTED':
      return { ...state, connected: true }
    case 'DISCONNECTED':
      return { ...state, connected: false, role: null, gameState: null, events: [] }
    case 'CLEAR_EVENTS':
      return { ...state, events: [] }
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
        eventSeq: state.eventSeq + 1,
        events: [...state.events.slice(-19), { kind: 'result', id: state.eventSeq, msg: action.msg }],
      }
    case 'ERROR':
      return {
        ...state,
        eventSeq: state.eventSeq + 1,
        events: [...state.events.slice(-19), { kind: 'error', id: state.eventSeq, msg: action.msg }],
      }
  }
}

const initialState: WsState = {
  connected: false,
  role: null,
  deckerName: null,
  gameState: null,
  events: [],
  eventSeq: 0,
}

export function useWebSocket() {
  const [state, dispatch] = useReducer(reducer, initialState)
  const wsRef = useRef<WebSocket | null>(null)
  const pendingNameRef = useRef<string | null>(null)
  const pendingJackPointRef = useRef<string | null>(null)
  const reconnectTokenRef = useRef<string | null>(null)
  const registeredNameRef = useRef<string | null>(null)
  const registeredJackPointRef = useRef<string | null>(null)
  const isMountedRef = useRef(true)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const reconnectDelay = useRef(3000)
  const suppressReconnectRef = useRef(false)
  const wasJackedInRef = useRef(false)

  const connect = useCallback(() => {
    const state = wsRef.current?.readyState
    if (state === WebSocket.OPEN || state === WebSocket.CONNECTING) return
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
            if (msg.role === 'registered_decker' && msg.reconnectToken) {
              reconnectTokenRef.current = msg.reconnectToken
              sessionStorage.setItem('matrix_reconnect_token', msg.reconnectToken)
            }
            if (msg.deckerName) registeredNameRef.current = msg.deckerName
            if (msg.role === 'observer') {
              dispatch({ type: 'CLEAR_EVENTS' })
              const nameToSend = pendingNameRef.current ?? registeredNameRef.current
              const jackPointToSend = pendingJackPointRef.current ?? registeredJackPointRef.current ?? ''
              if (nameToSend && jackPointToSend) {
                if (pendingJackPointRef.current != null) registeredJackPointRef.current = pendingJackPointRef.current
                const join: JoinMessage = {
                  type: 'join',
                  deckerName: nameToSend,
                  jackPointName: jackPointToSend,
                  ...(reconnectTokenRef.current ? { reconnectToken: reconnectTokenRef.current } : {}),
                }
                pendingNameRef.current = null
                pendingJackPointRef.current = null
                ws.send(JSON.stringify(join))
              }
            }
            break
          case 'state': {
            const isJackedIn = msg.decker.jackedIn
            if (!isJackedIn && wasJackedInRef.current) {
              reconnectTokenRef.current = null
              suppressReconnectRef.current = true
              sessionStorage.removeItem('matrix_reconnect_token')
              sessionStorage.removeItem('matrix_jack_point')
              ws.close()
            }
            wasJackedInRef.current = isJackedIn
            dispatch({ type: 'STATE', msg })
            break
          }
          case 'result':
            dispatch({ type: 'RESULT', msg })
            break
          case 'error':
            dispatch({ type: 'ERROR', msg })
            break
          default:
            console.warn('[useWebSocket] unhandled message type:', (msg as { type: string }).type)
        }
      } catch (err) {
        console.error('[useWebSocket] failed to parse message:', err)
      }
    }

    ws.onclose = () => {
      if (!isMountedRef.current) return
      dispatch({ type: 'DISCONNECTED' })
      if (suppressReconnectRef.current) return
      reconnectTimer.current = setTimeout(() => {
        reconnectDelay.current = Math.min(reconnectDelay.current * 2, 30000)
        connect()
      }, reconnectDelay.current)
    }

    ws.onerror = (ev) => { console.error('[useWebSocket] WebSocket error', ev); ws.close() }
  }, [])

  useEffect(() => {
    const storedToken = sessionStorage.getItem('matrix_reconnect_token')
    if (storedToken) reconnectTokenRef.current = storedToken
    const storedJackPoint = sessionStorage.getItem('matrix_jack_point')
    if (storedJackPoint) registeredJackPointRef.current = storedJackPoint
    connect()
    return () => {
      isMountedRef.current = false
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
      const ws = wsRef.current
      if (ws) {
        ws.onclose = null
        ws.onerror = null
        ws.close()
      }
    }
  }, [connect])

  const join = useCallback((name: string, jackPointName: string) => {
    pendingNameRef.current = name
    pendingJackPointRef.current = jackPointName
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      registeredJackPointRef.current = jackPointName
      sessionStorage.setItem('matrix_jack_point', jackPointName)
      const msg: JoinMessage = {
        type: 'join',
        deckerName: name,
        jackPointName: jackPointName,
        ...(reconnectTokenRef.current ? { reconnectToken: reconnectTokenRef.current } : {}),
      }
      pendingNameRef.current = null
      pendingJackPointRef.current = null
      wsRef.current.send(JSON.stringify(msg))
    } else {
      suppressReconnectRef.current = false
      connect()
    }
  }, [connect])

  const sendAction = useCallback((actionIndex: number, params?: ActionParams) => {
    if (state.role !== 'active_controller') return
    if (wsRef.current?.readyState !== WebSocket.OPEN) return
    const msg: ActionCommand = {
      type: 'action',
      actionIndex,
      ...(params ? { params } : {}),
    }
    wsRef.current.send(JSON.stringify(msg))
  }, [state.role])

  return { ...state, join, sendAction }
}
