# Testing Review — ui

## Summary

The frontend has zero test coverage: no test runner, no testing library, no test files, and no `test` script in `package.json`. This is a total absence of automated verification for a UI that contains non-trivial pure logic (string parsing, parameter building, state reduction, index clamping), stateful interaction logic (WebSocket lifecycle with exponential backoff, event ring-buffer, pending-join handshake), and several rendering branches that each depend on specific server message shapes. The most urgent gap is the `useWebSocket` reducer and reconnection machinery, which is the single most complex unit in the frontend and entirely unexercised. A second category of high-value, zero-effort wins are the pure functions scattered across `ActionsPanel.tsx` and `LocationPanel.tsx` that could be unit tested in minutes with no mocking required. The recommended setup is Vitest (native Vite integration, no config) plus `@testing-library/react` and `@testing-library/user-event`; a lightweight WebSocket mock (or `msw`) covers the hook.

---

## Findings

### [CRITICAL] No test infrastructure exists at all
**File:** `frontend/package.json`
**Issue:** The `scripts` block contains only `dev`, `build`, and `preview`. There is no `test` script. `devDependencies` has no test runner (`vitest`, `jest`), no component testing library (`@testing-library/react`, `@testing-library/user-event`), no WebSocket mock (`msw`, `mock-socket`), and no assertion helpers. It is impossible to run any automated test against the frontend today.
**Recommendation:** Add Vitest as it requires zero separate config when used with the existing Vite setup. Minimal additions:
```json
"scripts": {
  "test": "vitest run",
  "test:watch": "vitest"
},
"devDependencies": {
  "vitest": "^1.x",
  "@vitest/coverage-v8": "^1.x",
  "jsdom": "^24.x",
  "@testing-library/react": "^16.x",
  "@testing-library/user-event": "^14.x"
}
```
Add `test: { environment: 'jsdom' }` to `vite.config.ts`.

---

### [HIGH] `useWebSocket` reducer is complex and completely untested
**File:** `frontend/src/hooks/useWebSocket.ts:32`
**Issue:** The `reducer` function handles six action types and contains several non-obvious rules that are easy to break silently:
- `DISCONNECTED` resets `role`, `gameState`, and `reconnected` to their initial values but intentionally preserves `deckerName` — a regression here would break the reconnection flow.
- `CONTROL` only overwrites `deckerName` when `action.msg.deckerName` is truthy (`?? state.deckerName`), and only sets `reconnected` to `true` (never back to `false`) — there is no test verifying this one-way latch.
- `RESULT` and `ERROR` both keep only the last 20 events via `.slice(-19)` — the off-by-one (slice `-19` + new item = 20 total) is a detail that tests would pin down.
**Recommendation:** Extract the reducer and `initialState` to a separate module (they are already pure) and write unit tests covering each case. Example cases to cover: DISCONNECTED clears role/gameState; CONTROL with `reconnect: true` sets the flag; CONTROL without `deckerName` preserves existing name; event buffer caps at 20 entries; RESULT/ERROR append in order.

---

### [HIGH] `useWebSocket` connection/reconnection lifecycle is untested
**File:** `frontend/src/hooks/useWebSocket.ts:76`
**Issue:** The `connect` callback and `join` function contain several behaviours that are currently unverified:
- Exponential backoff: `reconnectDelay` doubles on each `onclose` up to a 30 000 ms cap.
- The pending-name pattern: when `join` is called before the socket is open, the name is stored in `pendingNameRef`; on `onopen` → first `control` message with `role === 'observer'`, the queued name is sent and the ref is cleared.
- `ws.onerror` immediately calls `ws.close()` — verifying this prevents the socket from entering a half-open state.
- `sendAction` is a no-op when the socket is not in `OPEN` state.
**Recommendation:** Mock `WebSocket` (a simple class stub satisfying `readyState`, `send`, `onopen`, `onmessage`, `onclose`, `onerror`) in a Vitest test. Use `renderHook` from `@testing-library/react` to drive the hook. Verify each scenario above with `vi.useFakeTimers()` for backoff assertions.

---

### [HIGH] `JoinScreen` error display logic is untested
**File:** `frontend/src/App.tsx:32`
**Issue:** `JoinScreen` uses a `useEffect` on the `events` array to detect the last event and populate the `error` state when its `kind` is `'error'`. This is the only mechanism for surfacing server-side registration errors to the user. No test verifies: (a) an error event causes the error message to appear; (b) a non-error event does not overwrite a prior error; (c) submitting a blank name (guarded by `!name.trim()`) never calls `onJoin`; (d) pressing Enter fires `handleSubmit`.
**Recommendation:** Render `JoinScreen` with `@testing-library/react`, simulate user input and key events with `userEvent`, and pass controlled `events` props to cover the effect path. Use `vi.fn()` for the `onJoin` spy.

---

### [MEDIUM] `actionLabel()` and `buildParams()` are pure functions with no tests
**File:** `frontend/src/components/ActionsPanel.tsx:10`
**Issue:** `actionLabel` is a switch over all seven `AvailableActionDto` kinds — if a new kind is added to `messages.ts` or the Kotlin backend the TypeScript exhaustiveness will catch missing branches at compile time, but the label *text* for existing branches is never verified. `buildParams` branches on four operation predicates and constructs `ActionParams` objects — the empty-string-to-null conversion for `EDIT_FILE` (`newContent === '' ? null : cs.newContent`, line 49) is subtle and could regress silently.
**Recommendation:** These functions can be imported and tested in isolation with no React setup. Add a `ActionsPanel.test.ts` with table-driven tests for all `actionLabel` cases and the `buildParams` edge cases (empty string → null, non-empty string → value, precision default, passcode false/true, scanner rating 0).

---

### [MEDIUM] `locKey()` string parsing is untested
**File:** `frontend/src/components/LocationPanel.tsx:8`
**Issue:** `locKey` iterates a hardcoded list of prefixes (`'RTG: '`, `'LTG: '`, `'PLTG: '`, `'Host: '`) and slices them. The slice uses `p.slice(0, -2)` to strip the trailing `': '` from the prefix label. If the prefix list is extended (e.g. a new node type) or the colon-space convention changes, the display label will silently be wrong. The fallback (no matching prefix) returns `{ prefix: '', name: location }` — this path is also untested.
**Recommendation:** Plain unit tests, no React needed. Cover: each known prefix, a location string with no matching prefix, an empty string.

---

### [MEDIUM] `EntitiesPanel` focus index clamping is untested
**File:** `frontend/src/components/EntitiesPanel.tsx:75`
**Issue:** When the `visibleObjects` list shrinks (e.g. an IC is defeated mid-run), `clamped = Math.min(focusIdx, Math.max(0, entities.length - 1))` prevents an out-of-bounds render. This is correct but non-trivial defensive logic — no test confirms that clicking entity N, then receiving a state update where N no longer exists, still renders without error. An empty `entities` array with `focusIdx > 0` is also unexercised.
**Recommendation:** Render the component with `@testing-library/react`, click to set `focusIdx`, then re-render with a shorter `visibleObjects` array via `rerender`. Assert the focused card is shown and no crash occurs. Also test the zero-entity empty state.

---

### [MEDIUM] `ActionsPanel` click-propagation guard is untested
**File:** `frontend/src/components/ActionsPanel.tsx:99`
**Issue:** Inline controls (precision toggles, passcode toggle, scanner stepper, edit textarea) call `e.stopPropagation()` to prevent clicking a control from also firing the parent card's `handleClick`. There is no test confirming this: if `stopPropagation` were accidentally removed, every control interaction would also submit the action to the server.
**Recommendation:** Render `ActionsPanel` with a mock `onAction`, simulate a click on a precision toggle button, and assert `onAction` was **not** called.

---

### [MEDIUM] `NarrativePanel` ERROR_LABELS is incomplete relative to `App.tsx`
**File:** `frontend/src/components/NarrativePanel.tsx:3`
**Issue:** `NarrativePanel` defines its own `ERROR_LABELS` map with only 4 entries (`not_your_turn`, `no_action_pending`, `already_registered`, `name_already_taken`). `App.tsx` defines the authoritative map with 7 entries — also covering `name_too_long`, `unknown_message_type`, and `bad_request`. If the server sends one of those three error codes during an active game session, `NarrativePanel` falls back to displaying the raw snake_case key rather than a human-readable label. This is both a testing gap and a latent display bug.
**Recommendation:** (1) Move the error-label map to a shared constant in `types/messages.ts` or a `constants.ts` file and import it in both `App.tsx` and `NarrativePanel.tsx`, eliminating the duplication. (2) Add a test that renders `NarrativePanel` with an event containing each `ErrorCode` value and asserts the displayed text matches the expected label.

---

### [LOW] `hasDice` check in `NarrativePanel` is dead code
**File:** `frontend/src/components/NarrativePanel.tsx:29`
**Issue:** `hasDice` is computed as `ev.msg.deckerSuccesses !== undefined || ev.msg.hostSuccesses !== undefined`. However, `ResultMessage` declares both fields as `number` (non-optional) — they can never be `undefined` in a well-typed codebase. The condition is therefore always `true`, and the `{hasDice && ...}` branch always renders. Tests would have caught this: a test using a fully-typed mock `ResultMessage` would immediately surface the fact that the guard serves no purpose.
**Recommendation:** Remove the `hasDice` variable and render the dice span unconditionally, or — if there is a real need to hide dice for certain results — add `deckerSuccesses?: number` to the `ResultMessage` interface and cover both cases with tests.

---

### [LOW] `DamageMonitor` box rendering is untested
**File:** `frontend/src/components/DeckerPanel.tsx:7`
**Issue:** `DamageMonitor` renders `maxBoxes` spans, marking each as `damaged` or `healthy` based on index vs damage count. Edge cases (damage === 0, damage === maxBoxes, damage > maxBoxes) are unverified. The `'●'.repeat(Math.min(u.rating, 10))` utility rating display in the parent also clamps silently.
**Recommendation:** Snapshot or structural tests for the monitor component with boundary values.

---

### [LOW] `App` routing logic between screens is untested
**File:** `frontend/src/App.tsx:79`
**Issue:** The `App` component has three rendering branches: `JoinScreen` (not registered), waiting screen (registered but no game state), and the full game grid. These transitions depend on `ws.role` and `ws.gameState` values. No test verifies that the correct screen renders for each combination, or that the `reconnected` banner is shown/hidden correctly.
**Recommendation:** Mock `useWebSocket` with `vi.mock` and test each of the three rendering states plus the reconnect-banner condition.

---

### [INFO] Recommended testing priority order
**File:** all frontend source
**Issue:** Given zero existing tests, the highest return on investment (in order) is:
1. Extract and unit test the `reducer` — pure, no mocking, covers the riskiest state logic.
2. Unit test `actionLabel`, `buildParams`, `locKey` — pure functions, no React, five minutes each.
3. Integration test `JoinScreen` with `@testing-library/react` — covers the primary user interaction path.
4. Hook test `useWebSocket` with a WebSocket stub — covers reconnection, pending-join, sendAction guard.
5. Component tests for `EntitiesPanel` (focus clamping) and `ActionsPanel` (propagation guard).

---

## No Issues Found In

- `frontend/src/types/messages.ts` — pure type declarations; nothing to test, but these types are the contract surface that tests elsewhere should rely on as fixtures.
- `LocationPanel` `LocationFields` switch — the `default: return null` for unrecognised node kinds is safe defensive code; a test would be a nice regression guard but is low risk.
