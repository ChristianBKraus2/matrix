---
# Correctness Review — ui

## Summary

The UI is well-structured with clear separation of concerns and sensible data-flow. Most correctness issues are concentrated in `useWebSocket.ts` and `ActionsPanel.tsx`. The most serious problem is a ghost-reconnect resource leak that fires indefinitely after component unmount (observable immediately in React 18 StrictMode). A stale-closure bug in `ActionsPanel.patchState` can silently overwrite parameter edits when state updates are batched. The narrative event list uses array-index keys, which will cause React to mis-map DOM nodes whenever the oldest event is pruned. Several lower-severity issues round out the findings.

## Findings

---

### HIGH — Ghost WebSocket reconnect loop after unmount

**File:** `frontend/src/hooks/useWebSocket.ts:110-125`

**Issue:** The `useEffect` cleanup correctly calls `clearTimeout(reconnectTimer.current)` and then `wsRef.current?.close()`. However, calling `ws.close()` on an open socket dispatches `ws.onclose` asynchronously — *after* the cleanup function has already returned. Inside that `onclose`, a new timer is scheduled: `reconnectTimer.current = setTimeout(() => connect(), ...)`. Nothing ever cancels this timer because the component is already unmounted. When the timer fires, `connect()` creates a brand-new `WebSocket`; if that too fails it will schedule another, and so on without bound. In React 18 StrictMode, the double-mount/unmount sequence triggers this on every page load. Even in production the leak matters if the component ever unmounts (e.g., hot-module reload, error boundary).

**Recommendation:** Set a mounted-flag ref and null out the `onclose` handler before calling `ws.close()` in cleanup, or simply reassign it to a no-op:

```ts
return () => {
  if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
  const ws = wsRef.current
  if (ws) {
    ws.onclose = null   // prevent onclose from rescheduling
    ws.onerror = null
    ws.close()
  }
}
```

---

### HIGH — `connect()` guard ignores CONNECTING state, risks duplicate sockets

**File:** `frontend/src/hooks/useWebSocket.ts:73-74`

**Issue:** The early-return guard is `if (wsRef.current?.readyState === WebSocket.OPEN) return`. If the current socket is still in `CONNECTING` state (readyState `0`), the guard does not fire. A concurrent call to `connect()` — possible during the ghost-reconnect scenario or any future call site — overwrites `wsRef.current` with a second `WebSocket` instance. The first socket's `onopen`/`onclose`/`onerror` handlers still fire against the now-stale `ws` local variable. This can produce duplicate `CONNECTED`/`DISCONNECTED` dispatches and overlapping reconnect timers.

**Recommendation:** Broaden the guard:

```ts
const state = wsRef.current?.readyState
if (state === WebSocket.OPEN || state === WebSocket.CONNECTING) return
```

---

### MEDIUM — `patchState` reads stale `cardStates` inside a functional updater

**File:** `frontend/src/components/ActionsPanel.tsx:60-63`

**Issue:** `patchState` passes a functional updater to `setCardStates` (correct for batching), but the spread source is `getState(idx)` which reads the *closure-captured* `cardStates`, not the `prev` argument supplied by React:

```ts
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({ ...prev, [idx]: { ...getState(idx), ...patch } }))
  //                                              ^^^^^^^^^^^^^ reads stale closure
}
```

If two React state updates are batched (e.g., rapid double-click on a stepper button), the second updater call receives the `prev` from the first update, but `getState(idx)` still returns the *original* `cardStates[idx]`. The second update therefore overwrites the first, silently discarding the user's first input.

**Recommendation:** Use `prev` inside the updater:

```ts
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({
    ...prev,
    [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch },
  }))
}
```

---

### MEDIUM — Array-index React keys on event list cause wrong DOM diffing when events are pruned

**File:** `frontend/src/components/NarrativePanel.tsx:26`

**Issue:** Events are rendered with `key={i}` (array index). The event buffer is capped at 20 items via `state.events.slice(-19)`. When a 21st event arrives, the oldest is dropped — all surviving items shift down by one index. React sees key `0` still exists but now points to what was previously the second event. This causes React to update the wrong DOM nodes in place rather than treating the removal correctly, breaking enter/exit animations and potentially flashing incorrect content while React reconciles.

**Recommendation:** Attach a stable, monotonically-incrementing sequence number to each event at dispatch time and use that as the key:

In the reducer, add `nextEventId: number` to `WsState` and increment it on each `RESULT`/`ERROR` action. Store `{ id: state.nextEventId, kind, msg }` in the events array, then render `key={ev.id}`.

---

### MEDIUM — `cardStates` persists stale form values across server state updates

**File:** `frontend/src/components/ActionsPanel.tsx:54`

**Issue:** `cardStates` is a component-local `useState` keyed by `action.index`. When the server sends a new `StateMessage` (e.g., after an action resolves), `actions` is replaced but `cardStates` is not reset. If a subsequent turn produces an action with the same `index` value (e.g., EDIT_FILE reappears at index 5), the textarea will still contain text from the previous interaction. More critically, if the `kind` of action at index 5 has *changed* between turns (different operation type), the `buildParams` function dispatches on the new `op` so the fields used are correct — but for same-kind actions reappearing, the stale content is silently pre-filled and could be sent to the server unintentionally.

**Recommendation:** Reset `cardStates` when the `actions` prop changes identity (via a `useEffect` comparing reference or a key derived from the action list).

---

### LOW — `String.replace('_', ' ')` only replaces the first underscore

**File:** `frontend/src/components/LocationPanel.tsx:31,41,50,59`

**Issue:** `.replace('_', ' ')` (string argument, not regex) replaces only the *first* underscore. All current `AlertStatus`, `TopologyType`, and `SecurityCode` enum strings happen to contain at most one underscore so this accidentally works. If the server ever adds a value like `ACTIVE_ALERT_ELEVATED` or `HOST_HOST_STRICT`, the display would show `HOST HOST_STRICT`.

**Recommendation:** Use the global regex form throughout: `.replace(/_/g, ' ')`.

---

### LOW — `LocationPanel` name-match can return wrong object if two kinds share a name

**File:** `frontend/src/components/LocationPanel.tsx:75-81`

**Issue:** The `visibleObjects.find` that resolves the current location to a detailed object matches solely on `o.name === name`, across all four node kinds. If a `GridNode` named "Seattle" and a `LocalGrid` also named "Seattle" are both in `visibleObjects`, the find returns whichever appears first. `LocationFields` renders the correct sub-fields based on `obj.kind`, so the *displayed fields* would be consistent with the *found* object — but that object might be the wrong one (e.g., showing LTG details when the decker is actually on an RTG).

**Recommendation:** Additionally filter by the expected kind derived from the `locKey` prefix:

```ts
const expectedKind = { 'RTG': 'GridNode', 'LTG': 'LocalGrid', 'PLTG': 'PrivateGrid', 'Host': 'HostNode' }[prefix]
visibleObjects.find(o => o.kind === expectedKind && o.name === name) ?? null
```

---

### LOW — Scanner device rating stepper has no upper bound

**File:** `frontend/src/components/ActionsPanel.tsx:137`

**Issue:** The `+` stepper button increments `scannerDeviceRating` without limit. A user could send an arbitrarily large value (e.g., 999) to the server. The server should validate input, but the UI provides no guard.

**Recommendation:** Add a reasonable cap (e.g., 12, matching Shadowrun device rating limits) to the increment handler: `Math.min(12, cs.scannerDeviceRating + 1)`.

---

### LOW — `EntitiesPanel.focusIdx` can reach -1, bypassing the `clamped` guard

**File:** `frontend/src/components/EntitiesPanel.tsx:93`

**Issue:** The click handler sets `focusIdx` via `entities.findIndex(e => e.index === obj.index)`. `findIndex` returns `-1` if the entity is not found. If `focusIdx` is `-1`, then `clamped = Math.min(-1, Math.max(0, entities.length - 1))` evaluates to `-1` for any non-empty list (since `-1 < 0`). `entities[-1]` is `undefined` in JavaScript, and `<EntityCard obj={undefined} ...>` would crash at runtime.

While the scenario (clicking a card whose entity has simultaneously vanished from the list between event dispatch and handler execution) is unlikely, the guard does not cover it.

**Recommendation:** Clamp to `[0, entities.length - 1]` explicitly:

```ts
const clamped = Math.max(0, Math.min(focusIdx, entities.length - 1))
```

and additionally guard the focused-card render: `entities[clamped] ?? null`.

---

### INFO — `ERROR_LABELS` map is duplicated across two files

**File:** `frontend/src/App.tsx:10-15` and `frontend/src/components/NarrativePanel.tsx:3-8`

**Issue:** The same `ERROR_LABELS` object is copy-pasted in both files. If a new error code is added to the server, it must be updated in two places or the displayed message will differ between the join screen and the in-game narrative log.

**Recommendation:** Extract to a shared `frontend/src/utils/errorLabels.ts` and import from both consumers.

---

### INFO — JoinScreen error not cleared when a non-error event arrives

**File:** `frontend/src/App.tsx:29-34`

**Issue:** The `useEffect` only calls `setError(...)` when the last event is an error. If a `result` event arrives while the JoinScreen is still showing (unlikely but possible), the previous error message remains displayed indefinitely until the user clicks submit again. The user could see a stale error alongside a successful server response.

**Recommendation:** Clear the error unconditionally when the latest event is not an error:

```ts
if (last?.kind === 'error') {
  setError(ERROR_LABELS[last.msg.message] ?? last.msg.message)
} else if (last) {
  setError('')
}
```

---

## Clean Areas

- `useWebSocket.ts` reducer is pure and handles all message types correctly; state transitions for CONTROL/STATE/RESULT/ERROR are consistent with the type definitions.
- `DeckerPanel.tsx` damage monitor rendering is correct: the `i < damage` comparator matches the closed/open box semantics exactly, and `Math.min(u.rating, 10)` / `Math.max(0, 10 - u.rating)` safely clamp program dot displays.
- `ActionsPanel.tsx` `buildParams` correctly dispatches only the relevant param subset per operation type; the stop-propagation on inline controls correctly prevents action submission when adjusting parameters.
- `LocationPanel.tsx` `locKey` prefix stripping (`p.slice(0, -2)`) is correct for all four defined prefixes.
- `useWebSocket.ts` reconnect backoff: resetting `reconnectDelay` to `3000` on `onopen` and applying the multiplier inside the timer callback gives correct exponential backoff that resets after a successful connection.
- `useWebSocket.ts` join flow: `pendingNameRef` correctly handles the race between a pre-connection `join()` call and the arrival of the observer `control` message; no join message can be sent to a non-open socket.
- `NarrativePanel.tsx` dice display guard (`hasDice`) correctly handles partial `undefined` fields using `?? 0`.
- `EntitiesPanel.tsx` entity filtering via `isEntity` cleanly separates location-level nodes from in-node entities without casting.
---
