# Concurrency Review — ui

## Summary

The UI layer is built on a single `useReducer`-driven WebSocket hook (`useWebSocket`) that serialises all server messages through a pure reducer, which is the right architecture choice and eliminates most React state-tearing risks. The purely-functional display components (`DeckerPanel`, `LocationPanel`, `NarrativePanel`) are clean. Two genuine concurrency defects exist: a post-unmount reconnect timer that escapes its cleanup fence, and a stale-closure bug inside `ActionsPanel.patchState` that reads render-snapshot state from inside a functional updater. A third issue — the `connect()` guard missing the CONNECTING state — can produce orphaned duplicate sockets during rapid reconnects. Everything else is low-severity or informational.

---

## Findings

### [HIGH] Post-unmount reconnect timer created after useEffect cleanup

**File:** `frontend/src/hooks/useWebSocket.ts:114–119` and `125–131`

**Issue:** The `useEffect` cleanup cancels the current reconnect timer and calls `wsRef.current?.close()`. However, `WebSocket.close()` dispatches its `onclose` event asynchronously. By the time `onclose` fires, the cleanup has already returned. The `onclose` handler then writes a new timer into `reconnectTimer.current` and eventually calls `connect()`, which creates a fresh `WebSocket`, registers new event listeners, and calls `dispatch` — all against an unmounted component. The new timer is never cleared because there is no live cleanup fence to cancel it. The result is an unbounded reconnect loop and a leaked socket that is never closed.

**Recommendation:** Introduce an `unmounted` ref (or `AbortController`) set to `true` inside the cleanup, and guard every async callback before it touches React state or schedules timers:

```typescript
const unmountedRef = useRef(false)

useEffect(() => {
  unmountedRef.current = false
  connect()
  return () => {
    unmountedRef.current = true
    if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
    wsRef.current?.close()
  }
}, [connect])
```

Then in `onclose`:
```typescript
ws.onclose = () => {
  if (unmountedRef.current) return          // <-- guard
  dispatch({ type: 'DISCONNECTED' })
  reconnectTimer.current = setTimeout(() => {
    if (unmountedRef.current) return        // <-- guard
    reconnectDelay.current = Math.min(reconnectDelay.current * 2, 30000)
    connect()
  }, reconnectDelay.current)
}
```

---

### [MEDIUM] `connect()` guard bypasses CONNECTING state, allowing orphaned duplicate sockets

**File:** `frontend/src/hooks/useWebSocket.ts:77`

**Issue:** The early-return guard is:
```typescript
if (wsRef.current?.readyState === WebSocket.OPEN) return
```
`WebSocket.OPEN` is `1`. `WebSocket.CONNECTING` is `0`. When `connect()` is called while a socket is still in the CONNECTING state (e.g., due to a rapid close/reconnect cycle or the timer firing before the first `onopen`), the guard is not hit. A second socket is created and `wsRef.current` is overwritten. The first socket is now orphaned — its `onopen`, `onmessage`, and `onclose` still fire (they captured the old `ws` variable) but `wsRef.current` points to the new socket. When the orphaned socket eventually fires `onclose`, it re-invokes `connect()`, potentially spawning a third socket. In a pathological case this compounds until the tab is closed.

**Recommendation:** Expand the guard to cover both live states:
```typescript
const rs = wsRef.current?.readyState
if (rs === WebSocket.OPEN || rs === WebSocket.CONNECTING) return
```

---

### [MEDIUM] `patchState` stale closure — reads render-snapshot state inside functional updater

**File:** `frontend/src/components/ActionsPanel.tsx:60–63`

**Issue:**
```typescript
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({ ...prev, [idx]: { ...getState(idx), ...patch } }))
}
```
`getState(idx)` closes over `cardStates` from the render in which `patchState` was defined. Inside the functional updater, `prev` is the latest committed state — but `getState(idx)` still reads the render-snapshot value. If React batches two sequential calls to `patchState` (a realistic scenario in React 18's automatic batching), the second updater spreads `getState(idx)` (stale) over `prev[idx]` (up-to-date), silently discarding the first update's changes.

**Recommendation:** Replace `getState(idx)` inside the updater with a read from `prev`:
```typescript
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({
    ...prev,
    [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch },
  }))
}
```

---

### [LOW] `EntitiesPanel` focus index is positional, not identity-based; silently shifts on server updates

**File:** `frontend/src/components/EntitiesPanel.tsx:74–75, 93`

**Issue:** Focus is stored as an integer index into the `entities` array derived from `visibleObjects`. When the server pushes a new `state` message, `visibleObjects` is replaced wholesale. If any entity is removed, inserted, or reordered before the focused position, `focusIdx` now silently points to a different entity. The `clamped` guard prevents an out-of-bounds crash, but it does not preserve identity. The `onClick` handler writes `entities.findIndex(e => e.index === obj.index)` (positional index, not entity id), which is consistent but still maps to a moving target.

**Recommendation:** Store the focused entity's `index` field (the stable server-assigned id) instead of its array position:
```typescript
const [focusId, setFocusId] = useState<number | null>(null)
const focused = entities.find(e => e.index === focusId) ?? entities[0]
```

---

### [LOW] `JoinScreen` error is set by `useEffect` but never cleared on non-error events

**File:** `frontend/src/App.tsx:32–37`

**Issue:**
```typescript
useEffect(() => {
  const last = events[events.length - 1]
  if (last?.kind === 'error') {
    setError(ERROR_LABELS[last.msg.message] ?? last.msg.message)
  }
}, [events])
```
The effect only sets `error`; it never clears it. If a `result` event (or any non-error event) arrives after an error, the stale error message remains visible. In the current server protocol the join screen cannot receive `result` events, so this is harmless in practice — but the logic is fragile: if the server ever sends a non-error event while the user is on the join screen, a confusing stale error will persist.

**Recommendation:** Clear the error on any non-error event:
```typescript
useEffect(() => {
  const last = events[events.length - 1]
  if (!last) return
  if (last.kind === 'error') {
    setError(ERROR_LABELS[last.msg.message] ?? last.msg.message)
  } else {
    setError('')
  }
}, [events])
```

---

### [INFO] `reconnected` banner is never auto-dismissed

**File:** `frontend/src/App.tsx:103–105`, `frontend/src/hooks/useWebSocket.ts:43`

**Issue:** `reconnected` is set to `true` in the `CONTROL` reducer case when `msg.reconnect === true`, and cleared only on `DISCONNECTED`. The reconnect banner therefore remains on screen for the entire session after a single reconnect, with no timeout or dismiss mechanism.

**Recommendation:** Auto-dismiss after a few seconds with a local `useEffect` and timer in `App`, or expose a `dismissReconnected` action from the hook.

---

### [INFO] `NarrativePanel` uses array index as `key` for an append-only, front-truncated list

**File:** `frontend/src/components/NarrativePanel.tsx:27`

**Issue:** `events.map((ev, i) => ... key={i} ...)`. The events list is bounded at 20 items via `slice(-19)`. When the 21st event arrives, the oldest event is dropped from the front, shifting every element's index by −1. React reconciles by key, so `key={0}` is now reused for the former `key={1}` element. Since `NarrativePanel` event rows are stateless (no local state, no animations keyed to identity), this causes no data corruption — React will simply re-render all rows. However, if CSS transitions or future state are added to event items, this will produce visible glitches.

**Recommendation:** Use a monotonically increasing sequence number assigned at dispatch time in the reducer, or append a render-stable id to each `GameEvent`. For the current stateless rendering this is safe to defer.

---

## No Issues Found In

- **`useWebSocket` reducer** — pure function with no side effects; all state transitions are atomic and correctly model the protocol state machine.
- **`useWebSocket` message handler** — single-threaded JS event loop guarantees that `onmessage` callbacks cannot interleave; message ordering within a connection is correct.
- **`useWebSocket` `join` / `sendAction`** — both use `useCallback([])` with ref access, avoiding stale closures over mutable state.
- **`DeckerPanel`** — stateless; purely derived from props. No concurrency surface.
- **`LocationPanel`** — stateless; purely derived from props. No concurrency surface.
- **`NarrativePanel`** — stateless; purely derived from props (key issue noted above is informational only).
- **`ActionsPanel` `handleClick`** — reads `cardStates` directly from render closure (not inside a setter), so the snapshot is consistent with the rendered UI at click time. No staleness concern here.
- **React concurrent-mode tearing** — all shared state lives inside `useReducer`; no external mutable stores are read during render. No tearing risk.
