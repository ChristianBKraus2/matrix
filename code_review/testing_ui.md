---
# Testing Review — ui

## Summary

The frontend has zero test coverage. There is no test framework installed (`package.json` lists no vitest, jest, or @testing-library/react dependency and has no `test` script), and no test files exist anywhere under `frontend/src/`. This means every finding below describes behaviour that is completely unverified. Several of the untested paths involve non-trivial logic — a state reducer, an exponential reconnect loop, event-buffer capping, and multi-step parameter building — that are realistic sources of silent regression. The review therefore focuses on which behaviours are most important to cover first, and on two correctness defects found during reading that a test suite would have caught.

## Findings

### [CRITICAL] No test infrastructure or test files whatsoever
**File:** frontend/package.json:1
**Issue:** `package.json` declares no testing dependency (vitest, jest, @testing-library/react, jsdom) and has no `test` script. There are zero `.test.ts`, `.test.tsx`, `.spec.ts`, or `.spec.tsx` files under `frontend/src/`. Every code path described in the findings below is untested.
**Recommendation:** Add vitest + @testing-library/react + jsdom. A minimal starting configuration is a `vitest.config.ts` that sets `environment: 'jsdom'` and a `test` script in `package.json`. This unblocks all other items.

---

### [HIGH] JoinScreen error is never cleared when a non-error event arrives
**File:** frontend/src/App.tsx:29
**Issue:** The `useEffect` in `JoinScreen` sets the displayed error only when the latest event has `kind === 'error'`. It has no `else` branch to clear `error` when a different event kind (e.g. `result`) arrives last. In practice this matters if the server sends a spurious result event after a failed join attempt: the old error text stays on screen while the user has already moved on. More importantly, no test verifies the clearing behaviour at all, so any future change to event ordering could silently regress it.
**Recommendation:** Add an `else { setError('') }` branch in the effect, and write a test that renders `JoinScreen`, feeds it an error event, feeds it a subsequent non-error event, and asserts the error div is no longer present.

---

### [HIGH] `useWebSocket` reducer and reconnect logic have no unit tests
**File:** frontend/src/hooks/useWebSocket.ts:31
**Issue:** The reducer is a pure function and is easily unit-testable in isolation, but is not tested. The critical paths include: `DISCONNECTED` sets `role` to `null` but leaves `gameState` populated (tested implication: after a disconnect the JoinScreen is shown with stale game data still in memory), the 20-event cap via `slice(-19)` (off-by-one is easy here), and the exponential back-off cap at 30 000 ms. The auto-re-join path — where a `control` message arriving with `role === 'observer'` triggers a `join` send using `pendingNameRef` — is exercised on every reconnect but is also completely untested.
**Recommendation:** Extract `reducer` as a named export and write pure unit tests covering every action type. Test the reconnect + auto-join flow with a fake WebSocket (e.g. via `mock-socket` or a hand-rolled stub).

---

### [HIGH] NarrativePanel uses array index as React key; no test would catch stale rendering
**File:** frontend/src/components/NarrativePanel.tsx:32
**Issue:** Events are rendered with `key={i}` where `i` is the position in the array. When the 20-event buffer is full and the oldest event is evicted (`slice(-19)` + append), every surviving event shifts one position. React sees the same numeric keys mapped to different content, does not remount those elements, and can therefore display stale text in each row. Because there are no tests, this silent corruption is not caught.
**Recommendation:** Use a stable key derived from message content and timestamp, or assign a monotonically increasing sequence number to each `GameEvent` at append time. Add a test that fills the buffer to 20, pushes a 21st event, and asserts that the oldest event text is gone and the newest is present.

---

### [MEDIUM] `ERROR_LABELS` is duplicated across two files and can silently drift
**File:** frontend/src/App.tsx:10 and frontend/src/components/NarrativePanel.tsx:3
**Issue:** The same `Record<string, string>` literal is defined in both `App.tsx` (used for the join screen) and `NarrativePanel.tsx` (used for in-game error events). Adding a new server error code requires updating both places; missing one means the raw code is displayed in one context and the human-readable label in the other.
**Recommendation:** Extract `ERROR_LABELS` to `src/constants/errorLabels.ts` and import it in both files. A simple unit test asserting the exported map contains the expected keys would catch any future divergence from the server's error vocabulary.

---

### [MEDIUM] `buildParams` edge cases are untested
**File:** frontend/src/components/ActionsPanel.tsx:45
**Issue:** `buildParams` contains non-obvious mapping logic: when `op === 'EDIT_FILE'` and `newContent` is an empty string, it sends `null` (erase semantics vs. update semantics). The precision default (`'NORMAL'`) comes from `defaultCardState` and is silently applied even when the user never touched the control. There is no test that verifies these boundaries, so a future refactor could change `'' → null` to `'' → ''` or change the default precision without any failing test.
**Recommendation:** Unit-test `buildParams` (it is a pure function) covering: precision present/absent, empty-string newContent → null, non-empty newContent → string, passcode toggle, scanner rating of 0.

---

### [MEDIUM] `EntitiesPanel` focus index clamping when the entity list shrinks
**File:** frontend/src/components/EntitiesPanel.tsx:74
**Issue:** `clamped = Math.min(focusIdx, Math.max(0, entities.length - 1))` is the only guard when entities are removed. If `focusIdx` is 3 and the new list has 2 entries, the focused card silently snaps to index 1. If the list drops to 0, `clamped` is 0 and `entities[clamped]` is `undefined`, but the `entities.length === 0` guard above prevents the crash. The silent focus jump is not verified by any test.
**Recommendation:** Write a test: render with 4 entities, click to focus index 3, simulate a state update that reduces to 2 entities, and assert the focused card shows the entity at the clamped position (not a stale or blank one).

---

### [MEDIUM] `ActionsPanel` card-level state can persist stale values across server state updates
**File:** frontend/src/components/ActionsPanel.tsx:54
**Issue:** `cardStates` is keyed by `action.index`. When the server sends a fresh `state` message after an action (e.g. the action list is rebuilt with the same index numbers mapped to different actions), the previous precision / passcode / scanner-rating values from `cardStates[n]` are used for the new action at that index. A precision of `'HIGH'` set for a `LOCATE_FILE` operation could be silently carried over to a new `LOCATE_SLAVE` action that arrives with the same index.
**Recommendation:** Reset `cardStates` whenever `actions` prop changes identity (via a `useEffect` with `actions` as dependency). Add a test that changes the action list and verifies the controls reset to defaults.

---

### [LOW] `actionLabel` returns `undefined` for unknown action kinds; no exhaustiveness test
**File:** frontend/src/components/ActionsPanel.tsx:10
**Issue:** The `actionLabel` switch has no `default` branch. TypeScript's exhaustiveness check prevents this at compile time for the current union, but the function signature accepts `AvailableActionDto` — if a new `kind` variant is added to the type but forgotten in the switch, the label renders as `undefined` and the action card shows a blank header with no error.
**Recommendation:** Add a `default: return action.kind` fallback (or an `assertNever`) and write a test for each `kind` variant to ensure a non-empty string is returned.

---

### [LOW] `DamageMonitor` renders incorrectly when `damage > maxBoxes`
**File:** frontend/src/components/DeckerPanel.tsx:7
**Issue:** If the server ever sends `physicalDamage > physicalMaxBoxes` (data validation is the server's responsibility but the UI should be defensive), all boxes render as `damaged` and the fraction displays as e.g. `14/10`. There is no clamp and no test for this edge case.
**Recommendation:** Clamp `damage` to `[0, maxBoxes]` inside `DamageMonitor` and add a test that verifies the rendered box count and fraction label when `damage` is out of range.

---

### [LOW] Scanner-rating stepper has no upper-bound guard
**File:** frontend/src/components/ActionsPanel.tsx:137
**Issue:** The `−` button clamps to 0 (`Math.max(0, ...)`), but the `+` button has no cap. A user can increment to any integer. The server will validate, but the UI could display an unrealistically large value. No test covers the lower-bound clamping behaviour either.
**Recommendation:** Add a reasonable upper bound (e.g. 12, matching the Shadowrun dice pool ceiling) and write tests for both the lower-bound clamp (cannot go below 0) and the upper-bound clamp.

---

### [LOW] `useWebSocket.join()` has no input validation at the hook level
**File:** frontend/src/hooks/useWebSocket.ts:129
**Issue:** `join('')` or `join('   ')` sends a message with an empty/whitespace `deckerName` to the server. The only guard is in `JoinScreen.handleSubmit`, so any caller that bypasses the screen (a future feature, a test harness, or a direct import) can send an invalid join. The server will reject it, but the error round-trip is slow and the failure is silent at the hook level.
**Recommendation:** Add `if (!name.trim()) return` at the top of `join()` and write a unit test that calls `join('')` and asserts no WebSocket message is sent.

---

### [INFO] No `test` script in package.json
**File:** frontend/package.json:5
**Issue:** There is no `"test"` entry in `scripts`. Running `npm test` silently does nothing. CI pipelines that call `npm test` would pass trivially.
**Recommendation:** After adding vitest, add `"test": "vitest run"` and `"test:watch": "vitest"`.

---

### [INFO] Vite config has no test configuration block
**File:** frontend/vite.config.ts:1
**Issue:** There is no `test` key in the Vite/Vitest config (environment, globals, setupFiles, coverage). This needs to be added before any tests can run.
**Recommendation:** Add a `test: { environment: 'jsdom', globals: true }` block when adopting vitest.

## Clean Areas

- The `reducer` in `useWebSocket.ts` is a pure function with no side-effects, making it straightforward to unit-test once a framework is in place.
- `buildParams` and `actionLabel` in `ActionsPanel.tsx` are pure functions — they have no component dependencies and can be tested with plain imports.
- `locKey` in `LocationPanel.tsx` is a pure string-parsing function, trivially unit-testable.
- The `ActionParams` / `AvailableActionDto` types in `messages.ts` are well-discriminated unions; TypeScript exhaustiveness checking provides a degree of compile-time safety.
- The 20-event cap logic (`slice(-19)`) is a single expression easy to cover with a property-based or table-driven test once vitest is present.
---
