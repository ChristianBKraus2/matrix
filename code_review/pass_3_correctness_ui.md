# Correctness Review — ui

## Summary

The UI frontend is broadly correct: the WebSocket reducer is exhaustive, the join/reconnect flow is logically sound, and action-parameter dispatch maps cleanly to server expectations. Four genuine correctness issues were found. The most consequential is a crash-level defect in `DeckerPanel` where `String.repeat()` is called with the raw server-supplied rating value, which throws a `RangeError` for any negative rating. A second structural issue is that `NarrativePanel` relies entirely on CSS (`justify-content: flex-end`) to anchor events at the bottom of the panel; once the list overflows its container, new events are rendered off-screen with no programmatic scroll. Two lower-severity issues — stale `cardStates` on mid-turn state pushes, and `reconnectTokenRef` surviving a full disconnect — round out the findings.

## Findings

### [MEDIUM] `String.repeat()` crashes on negative program rating

**File:** frontend/src/components/DeckerPanel.tsx:65
**Issue:** The program-rating pip display is:
```ts
{'●'.repeat(Math.min(u.rating, 10))}
{'○'.repeat(Math.max(0, 10 - u.rating))}
```
`Math.max(0, 10 - u.rating)` correctly prevents the empty-pip count from going negative. However the filled-pip count uses `Math.min(u.rating, 10)`. If the server ever sends a negative rating (malformed message, backend bug), `Math.min(negative, 10)` returns the negative value, and `String.prototype.repeat` throws `RangeError: Invalid count value`. This crashes the entire `DeckerPanel` render tree.
**Recommendation:** Guard both operands: `Math.max(0, Math.min(u.rating, 10))` for filled pips, and `Math.max(0, 10 - Math.max(0, u.rating))` for empty pips.

---

### [MEDIUM] `NarrativePanel` has no programmatic scroll-to-bottom

**File:** frontend/src/components/NarrativePanel.tsx:21-53
**Issue:** The panel body uses `justify-content: flex-end` (App.css:431) to push events to the bottom when the list is short enough to fit. Once the list overflows the panel's height, `justify-content` no longer controls placement — the browser renders items from the top, and the scroll position stays wherever it was. New events appended at the end of the array are rendered below the visible viewport. The user must manually scroll down to read the latest result. With a cap of 20 events and a narrow panel, overflow occurs quickly during active play.
**Recommendation:** Attach a `ref` to the scroll container and call `ref.current.scrollTop = ref.current.scrollHeight` inside a `useEffect` that fires whenever `events` changes.

---

### [MEDIUM] Location node resolved by display name instead of stable index

**File:** frontend/src/components/LocationPanel.tsx:75-81
**Issue:** `visibleObjects.find(o => ... o.name === name)` resolves the current location object by matching the display name extracted from `decker.location` (e.g. `"Host: Public Access"` → `"Public Access"`). Every `MatrixObjectDto` variant already carries a stable `index` field. If two nodes visible at the same time share an identical display name, `find` picks the first one in the array, which may not be the node the decker is actually in. Location stats (alert level, security tally, topology) would silently display from the wrong object.
**Recommendation:** Include the node's `index` in `decker.location` (e.g. as a `locationIndex` field on `DeckerStateDto`) and match against that instead of the name string.

---

### [LOW] Card states reset on every `actions` array reference change

**File:** frontend/src/components/ActionsPanel.tsx:56-58
**Issue:**
```ts
useEffect(() => {
  setCardStates({})
}, [actions])
```
`actions` is a plain array field on `StateMessage`. React compares it by reference, and a new reference is produced on every incoming state push. During a player's active turn the server may push additional state updates (e.g. after an IC moves, or another player's action resolves). Each push wipes `cardStates`, clearing any precision level, passcode flag, or scanner rating the player has already configured mid-card before clicking the action button. The player sees the controls silently snap back to defaults.
**Recommendation:** Reset card states only when the set of available action *indices* changes (i.e. between turns), not on every reference-equal state push. A stable identity check over `actions.map(a => a.index).join(',')` as the effect dependency is sufficient.

---

### [LOW] `reconnectTokenRef` not cleared on disconnect; attached to new-handle join

**File:** frontend/src/hooks/useWebSocket.ts:37, 95-103
**Issue:** The `DISCONNECTED` reducer branch resets all `WsState` fields but does not touch `reconnectTokenRef.current`. If a player receives a reconnect token, loses connection, then (within the same browser tab) reconnects and calls `join()` with a different decker name, the stale token from the previous session is silently attached to the `JoinMessage`. Depending on server logic, this could either restore the old session under the wrong name or produce a confusing error.
**Recommendation:** Clear `reconnectTokenRef.current = null` inside the `ws.onclose` handler (or alongside the `DISCONNECTED` dispatch) so that a full reconnect starts without any prior session token.

---

### [LOW] `String.replace('_', ' ')` only replaces first underscore

**File:** frontend/src/components/LocationPanel.tsx:31, 41, 53, 59
**Issue:** All four calls use the string-literal form of `replace`, which replaces only the first matching character. All current enum values (`NO_ALERT`, `PASSIVE_ALERT`, `OPEN_ACCESS`, `HOST_HOST`, etc.) contain at most one underscore, so rendering is correct today. Any future enum variant with two or more underscores would render with the trailing underscores intact.
**Recommendation:** Use the regex global form `.replace(/_/g, ' ')`. `ActionsPanel.tsx` line 18 already does this for `operation` labels and is the correct precedent.

---

### [LOW] `NarrativePanel` ERROR_LABELS is incomplete relative to the full ErrorCode union

**File:** frontend/src/components/NarrativePanel.tsx:3-8
**Issue:** `NarrativePanel` defines its own local `ERROR_LABELS` map with four entries. The `ErrorCode` union in `messages.ts` defines seven codes; `name_too_long`, `unknown_message_type`, and `bad_request` are absent. These three codes fall through to the raw snake_case string via the `??` fallback and are shown verbatim to the player. `bad_request` and `unknown_message_type` can arrive during an active session (e.g. a malformed action send).
**Recommendation:** Remove the local copy and import the exhaustive `ERROR_LABELS` constant from `App.tsx`, which uses `Record<ErrorCode, string>` to enforce compile-time completeness.

---

### [INFO] `hasDice` check in NarrativePanel tests non-optional fields

**File:** frontend/src/components/NarrativePanel.tsx:29-30
**Issue:** `const hasDice = ev.msg.deckerSuccesses !== undefined || ev.msg.hostSuccesses !== undefined` guards the dice-score display. Both fields are required (non-optional) in the `ResultMessage` interface, so the check is always `true` and the guard is dead code. A future reader might assume the display is conditional on optional fields and replicate the pattern incorrectly.
**Recommendation:** Remove `hasDice` and render the dice span unconditionally, or make the fields optional in `ResultMessage` if there is a genuine case where they are absent.

## No Issues Found In

- `useWebSocket.ts` — reducer transitions are exhaustive and mutually exclusive; event-queue capping (`slice(-19)` + append = max 20 items) is arithmetically correct; `isMountedRef` correctly gates the reconnect callback so it does not fire after component unmount; exponential back-off cap at 30 s is applied correctly.
- `App.tsx` — registered-state gate (`registered_decker || active_controller`) correctly mirrors all server-role states; `ERROR_LABELS` uses `Record<ErrorCode, string>` to enforce exhaustiveness over all seven error codes; `JoinScreen` clears the error before each submission attempt.
- `EntitiesPanel.tsx` — `clamped` correctly constrains `focusIdx` to the valid range when the entity list shrinks; `key={obj.index}` uses the stable server-assigned index rather than array position; click handler resolves the new index via `findIndex` on stable `.index` equality.
- `ActionsPanel.tsx` — `buildParams` correctly maps each operation kind to exactly its required `ActionParams` subset; `stopPropagation` on `.action-control` divs correctly prevents control interactions from firing the enclosing card's action click; disabled cards use `pointer-events: none` which also blocks child control interactions.
- `messages.ts` — union discriminants are exhaustive; comment explicitly documents the coupling to Kotlin backend enum names and lists affected types.
