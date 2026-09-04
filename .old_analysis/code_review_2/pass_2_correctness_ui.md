# Correctness Review — ui

## Summary

The UI layer is structurally sound: role gating, null-guarding `gameState` before rendering the game grid, and the WebSocket reconnect backoff are all correct. The principal correctness gap is that the `events` ring buffer is never cleared when the WebSocket disconnects, so stale narrative events from a previous session bleed into the next session's NarrativePanel. Two further medium-severity issues exist in `ActionsPanel`: a stale-closure bug inside `patchState` that can silently drop rapid user input, and `cardStates` that survive across game-state updates and can submit stale action parameters. The remaining findings are low-severity edge-cases or cosmetic oversights.

---

## Findings

### [HIGH] `events` not cleared on disconnect — stale narrative after reconnect

**File:** `frontend/src/hooks/useWebSocket.ts:37`

**Issue:** The `DISCONNECTED` reducer branch resets `role`, `gameState`, and `reconnected`, but leaves `events` untouched:

```typescript
case 'DISCONNECTED':
  return { ...state, connected: false, role: null, gameState: null, reconnected: false }
```

After an automatic reconnect the user re-enters the join flow, rejoins, and then the game screen appears. Because `events` was never cleared it still contains every result and error message from the previous session. The NarrativePanel therefore opens showing history that belongs to a closed session, misleading the player about what just happened.

**Recommendation:** Add `events: []` to the `DISCONNECTED` branch, or add a dedicated `RESET_EVENTS` action dispatched from `CONNECTED` (before the first `control` message arrives).

---

### [MEDIUM] Stale-closure bug in `patchState` drops rapid consecutive patches

**File:** `frontend/src/components/ActionsPanel.tsx:61-63`

**Issue:** `patchState` calls `getState(idx)` — which reads from the render-time closure `cardStates` — inside the `setCardStates` functional updater:

```typescript
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({ ...prev, [idx]: { ...getState(idx), ...patch } }))
}
```

React batches multiple state updates, so if two `patchState` calls are enqueued before the component re-renders (e.g., clicking the stepper `+` twice quickly, or toggling precision and then clicking a toggle button), both updaters see the same stale `cardStates[idx]` from the closure. The second call therefore overwrites the first instead of merging on top of it.

**Recommendation:** Replace `getState(idx)` with `prev[idx] ?? defaultCardState()` inside the updater:

```typescript
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({ ...prev, [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch } }))
}
```

---

### [MEDIUM] `cardStates` not reset when `actions` prop changes — stale params submitted

**File:** `frontend/src/components/ActionsPanel.tsx:54`

**Issue:** `cardStates` is keyed by `action.index` and persists in component state for the entire session. When a new `StateMessage` arrives and `actions` changes (e.g., after an action is taken), the old `cardStates` entries are retained. If the server reuses an action index for a different action kind, or reuses it for the same kind against a different target, the previously entered `newContent`, `scannerDeviceRating`, or `precision` value from the old action will be sent along with the new one.

**Recommendation:** Reset `cardStates` when the `actions` array reference changes by adding a `useEffect`:

```typescript
useEffect(() => {
  setCardStates({})
}, [actions])
```

---

### [LOW] `.reconnect-banner` CSS class is undefined — banner renders unstyled

**File:** `frontend/src/App.tsx:103-105`

**Issue:** The reconnect banner is rendered as:

```tsx
{reconnected && (
  <div className="reconnect-banner">SESSION RESTORED — reconnected to active game</div>
)}
```

The class `.reconnect-banner` does not exist in `App.css`. The element renders as a plain unstyled `div` that flows inline with the game grid, likely collapsing or appearing invisibly inside a grid cell.

**Recommendation:** Add a `.reconnect-banner` rule (e.g., spanning all columns as a `grid-column: 1 / -1` overlay or top notification bar) and consider positioning it as a fixed overlay.

---

### [LOW] `reconnected` flag never cleared — "SESSION RESTORED" banner persists for entire session

**File:** `frontend/src/hooks/useWebSocket.ts:39-44` / `frontend/src/App.tsx:103`

**Issue:** `reconnected` is set to `true` when a `control` message arrives with `reconnect === true`, and is only reset on the next `DISCONNECTED` event. There is no timeout, no dismiss button, and no state transition that clears it after initial display. The "SESSION RESTORED" banner therefore remains on screen for the entire reconnected session.

**Recommendation:** Either auto-dismiss the banner after a few seconds (a `setTimeout` inside a `useEffect` in `App.tsx`) or expose a `clearReconnected` action from the hook so a dismiss button can clear it.

---

### [LOW] `focusIdx` not reset when `visibleObjects` changes — focused entity silently shifts

**File:** `frontend/src/components/EntitiesPanel.tsx:74-75`

**Issue:** `focusIdx` stores the array position of the focused entity. When `visibleObjects` changes (new game state), `focusIdx` is not reset. The `clamped` calculation prevents an out-of-bounds crash, but if entities are added, removed, or reordered, the same array position now points to a different entity without any user action. The player's focused card can silently change to something unrelated.

**Recommendation:** Reset `focusIdx` to 0 when the entity list identity changes:

```typescript
useEffect(() => { setFocusIdx(0) }, [visibleObjects])
```

Alternatively, track focus by `obj.index` (server-assigned identity) rather than array position.

---

### [LOW] Array index used as React `key` on ring-buffered event list

**File:** `frontend/src/components/NarrativePanel.tsx:26`

**Issue:** Events are rendered with `key={i}` (array position). The events array is a ring buffer capped at 20 entries:

```typescript
events: [...state.events.slice(-19), { kind: 'result', msg: action.msg }],
```

When the buffer is full, `slice(-19)` evicts the oldest entry, shifting all indices by one. React sees key `0` now pointing to what was previously key `1`, and reuses its DOM node. For simple text-only items this may not cause visible corruption, but it prevents React from correctly animating additions/removals and can misattribute CSS transition state.

**Recommendation:** Use a stable, monotonically increasing sequence number as the key. Attach a counter to each `GameEvent` when it is pushed into the array (e.g., add `seq: number` to the event type and increment a `useRef` counter in the reducer).

---

### [LOW] `String.replace('_', ' ')` replaces only the first underscore

**File:** `frontend/src/components/LocationPanel.tsx:31,41,52,59`

**Issue:** `alertStatus.replace('_', ' ')` and `topologyType.replace('_', ' ')` use a string literal, not a regex. `String.replace` with a string argument replaces only the first match. All current `AlertStatus` and `TopologyType` enum values have at most one underscore, so there is no visible bug today. However, any future enum variant with two underscores (e.g., a hypothetical `VERY_PASSIVE_ALERT`) would render with the second underscore intact.

**Recommendation:** Use a global regex: `.replace(/_/g, ' ')`. The same pattern is already used correctly in `ActionsPanel.tsx:18`.

---

### [LOW] `'●'.repeat()` throws `RangeError` on negative utility rating

**File:** `frontend/src/components/DeckerPanel.tsx:65`

**Issue:** Active utility ratings are rendered as:

```tsx
{'●'.repeat(Math.min(u.rating, 10))}
{'○'.repeat(Math.max(0, 10 - u.rating))}
```

`Math.min(u.rating, 10)` returns a negative number if `u.rating < 0`, and `String.prototype.repeat` throws `RangeError: Invalid count value` for negative arguments. If the server ever sends malformed data with a negative rating, the entire `DeckerPanel` will crash.

**Recommendation:** Clamp both ends: `Math.min(Math.max(0, u.rating), 10)`.

---

### [INFO] `ERROR_LABELS` in `NarrativePanel` is an incomplete subset of the authoritative map in `App.tsx`

**File:** `frontend/src/components/NarrativePanel.tsx:3-8`

**Issue:** `NarrativePanel` defines its own `ERROR_LABELS` with 4 entries; `App.tsx` defines the same map with 7. The three missing codes (`name_too_long`, `unknown_message_type`, `bad_request`) fall through to the raw `ev.msg.message` string, which is still understandable but inconsistent with the rest of the error display.

**Recommendation:** Move `ERROR_LABELS` (and the `ErrorCode` type it maps) to a shared utility module (e.g., `src/utils/labels.ts`) and import it in both `App.tsx` and `NarrativePanel.tsx`.

---

## No Issues Found In

- **`frontend/src/types/messages.ts`** — All union discriminants, interface shapes, and enum mirror types are internally consistent. Nullability annotations (`string | null`, optional `?`) are used correctly. The comment cross-referencing Kotlin enum class names is a good guard against drift.
- **`frontend/src/App.tsx` (role gating)** — The `isRegistered` check and the `ws.gameState` null-guard before rendering the game grid are in the correct order and cover both necessary pre-conditions before mounting child panels.
- **`frontend/src/components/DeckerPanel.tsx` (damage monitor)** — `Array.from({ length: maxBoxes })` correctly handles `maxBoxes === 0`; the `i < damage` comparison is sound for all non-negative integer inputs.
- **`frontend/src/components/LocationPanel.tsx` (null-safe location lookup)** — The `?? null` fallback and the `decker.location === 'not jacked in'` guard prevent crashes when no matching node is in `visibleObjects`. The `default: return null` branch in `LocationFields` silently suppresses entity-type nodes that should never appear as the current location.
- **`frontend/src/hooks/useWebSocket.ts` (reconnect backoff)** — The exponential backoff (3 s → 30 s cap) resets correctly on successful `onopen`. The `wsRef.current?.readyState === WebSocket.OPEN` guard in `connect` prevents duplicate simultaneous connections.
- **`frontend/src/components/ActionsPanel.tsx` (click propagation)** — All inline control containers correctly call `e.stopPropagation()`, preventing a control interaction from simultaneously firing the action.
