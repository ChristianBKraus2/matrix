# Testing Review — ui

## Summary

The UI frontend has no test coverage whatsoever: there is no test framework in `package.json`, no test runner configured, and zero test files anywhere under `frontend/src/`. This means every state-management path in the WebSocket hook, every pure utility function, every component rendering branch, and every edge case in the action-parameter logic is completely unexercised by automated tests. Two concrete runtime bugs are also present that tests would have caught — a `RangeError` thrown when a server sends a negative utility rating, and a silent user-experience defect where several error codes produce raw snake_case strings in the narrative panel instead of human-readable messages.

## Findings

### [CRITICAL] No test framework and zero test files

**File:** frontend/package.json:1
**Issue:** `package.json` has no test runner dependency (vitest, jest, @testing-library/react, etc.) and no `test` script. There are zero `.test.ts`, `.test.tsx`, `.spec.ts`, or `.spec.tsx` files in the entire `frontend/src/` tree. Every component, hook, and utility is completely untested.
**Recommendation:** Add vitest + @testing-library/react as devDependencies and add a `"test": "vitest"` script. Start with pure-function unit tests for `reducer` (useWebSocket.ts), `buildParams` and `actionLabel` (ActionsPanel.tsx), and `locKey` (LocationPanel.tsx), then add component tests for the panels.

**[DEFERRED]** — No test framework added; out of scope for this session.

---

### [HIGH] RangeError crash if server sends negative utility rating

**File:** frontend/src/components/DeckerPanel.tsx:65
**Issue:** The rating dot-string is built with `'●'.repeat(Math.min(u.rating, 10))`. If the server ever sends a negative `rating` value (malformed data, deserialization bug), `Math.min(negative, 10)` stays negative, and `String.prototype.repeat` throws `RangeError: Invalid count value` — crashing the `DeckerPanel` render and triggering a blank screen with no error boundary in place.
**Recommendation:** Clamp the lower bound as well: `Math.max(0, Math.min(u.rating, 10))`. Add a unit test asserting that a decker with `rating: -1` renders without throwing.

**[RESOLVED]** — Fixed in `DeckerPanel.tsx`: filled-pip count now uses `Math.min(Math.max(0, u.rating), 10)`.

---

### [HIGH] NarrativePanel ERROR_LABELS covers only 4 of 7 error codes

**File:** frontend/src/components/NarrativePanel.tsx:3
**Issue:** `NarrativePanel` defines its own `ERROR_LABELS` with four entries (`not_your_turn`, `no_action_pending`, `already_registered`, `name_already_taken`). The canonical list in `App.tsx` and in `messages.ts` has seven `ErrorCode` variants. The three missing codes — `name_too_long`, `unknown_message_type`, `bad_request` — fall through to the raw snake_case fallback `ev.msg.message`, so users see strings like `"name_too_long"` in the narrative log rather than a friendly sentence. The duplicated map also drifts silently when error codes change.
**Recommendation:** Delete the local map in `NarrativePanel` and import `ERROR_LABELS` from `App.tsx` (or move it to a shared `errorLabels.ts` module). Type the map as `Record<ErrorCode, string>` so TypeScript enforces completeness. Add a rendering test that exercises each `ErrorCode` variant and asserts the human-readable string appears.

**[RESOLVED]** — Fixed in `NarrativePanel.tsx`: `ERROR_LABELS` now covers all 7 `ErrorCode` values.

---

### [MEDIUM] Pure reducer function has no unit tests

**File:** frontend/src/hooks/useWebSocket.ts:32
**Issue:** The `reducer` is a plain pure function — ideal for unit testing — but is entirely untested. Critical behaviours with no coverage include: `DISCONNECTED` resetting all fields to initial values; `CONTROL` setting `reconnected: true` only when `msg.reconnect === true` (not for truthy values); and the event ring-buffer logic (`events.slice(-19)` plus one new entry) that silently drops events beyond 20. An off-by-one in the slice index would never be caught.
**Recommendation:** Write direct unit tests for every `WsAction` variant against known input states. Specifically assert the ring-buffer keeps exactly 20 events when 21 are dispatched.

**[DEFERRED]** — Reducer unit tests not added; out of scope for this session.

---

### [MEDIUM] buildParams has untested branches including an empty-string-to-null conversion

**File:** frontend/src/components/ActionsPanel.tsx:45
**Issue:** `buildParams` is a pure function with five distinct branches (`precision`, `passcode`, `scanner`, `edit`, default). The edit branch contains a non-obvious data transformation: `cs.newContent === '' ? null : cs.newContent` (empty string is sent as `null` to signal erasure). This semantic distinction is invisible from the UI and completely untested. An incorrect change to this logic would silently alter game behaviour.
**Recommendation:** Extract `buildParams` into a separate utility file and add unit tests for each branch, including the empty-vs-non-empty `newContent` cases. Also add a test confirming that `buildParams` returns `undefined` for operations that need no params (e.g., `LOGON_RTG`).

**[DEFERRED]** — `buildParams` unit tests not added; out of scope for this session.

---

### [MEDIUM] Reconnect token never cleared on DISCONNECTED — stale token re-sent after full session drop

**File:** frontend/src/hooks/useWebSocket.ts:37
**Issue:** `reconnectTokenRef` is only ever written (`reconnectTokenRef.current = msg.reconnectToken`) and never cleared. The `DISCONNECTED` reducer case resets React state but leaves the ref intact. After a full connection drop where the server-side session has expired, the next join attempt will still include the old `reconnectToken`, potentially causing the server to reject it or attach the client to a dead session. There is no test that simulates a disconnect-then-rejoin cycle to catch this.
**Recommendation:** Clear `reconnectTokenRef.current = null` in the `ws.onclose` handler (or add a `DISCONNECTED` side-effect in the connect closure). Add an integration-style test using a mock WebSocket to verify that after a close event the next join message contains no `reconnectToken`.

**[RESOLVED]** — Fixed in `useWebSocket.ts`: `reconnectTokenRef.current = null` now set in the `onclose` handler.

---

### [LOW] Scanner rating stepper has no upper bound

**File:** frontend/src/components/ActionsPanel.tsx:140
**Issue:** The TAP_COMCALL scanner-rating stepper allows incrementing `scannerDeviceRating` without limit. A user could send an arbitrarily large value. The decrement is correctly bounded at 0 (`Math.max(0, cs.scannerDeviceRating - 1)`), but there is no corresponding `Math.min`. This is untested.
**Recommendation:** Apply a reasonable cap (e.g., device rating max of 10 or 12 per SR3 rules) via `Math.min` in the increment handler. Add a test asserting the value cannot exceed the cap.

**[RESOLVED]** — Fixed in `ActionsPanel.tsx`: the `+` stepper is disabled when `cs.scannerDeviceRating >= 10`.

---

### [LOW] EntitiesPanel focus-clamp logic is untested after entity list shrinks

**File:** frontend/src/components/EntitiesPanel.tsx:75
**Issue:** `clamped = Math.min(focusIdx, Math.max(0, entities.length - 1))` handles the case where the focused index exceeds the new list length after a state update. This logic is correct but untested; a regression here would cause `entities[clamped]` to be `undefined`, crashing the focused `EntityCard` render.
**Recommendation:** Add component tests: render with 3 entities, click to focus index 2, then re-render with 1 entity and assert that the focused card shows the remaining entity without error.

**[DEFERRED]** — `EntitiesPanel` focus-clamp component test not added; out of scope for this session.

---

### [INFO] TypeScript enum mirrors in messages.ts have no drift-detection test

**File:** frontend/src/types/messages.ts:53
**Issue:** A comment documents that five TypeScript union types mirror Kotlin enums and must be updated manually when the Kotlin side changes. There is no automated test (e.g., a snapshot of the serialised Kotlin enum names fetched from an endpoint, or a contract test) to catch drift. A Kotlin enum rename would produce silent rendering fallbacks in the UI.
**Recommendation:** Consider generating the TypeScript types from the Kotlin source (e.g., via a build task that serialises enum names to a JSON contract file) and adding a test that compares the generated output against the checked-in types.

**[DEFERRED]** — TypeScript enum drift-detection test not added; out of scope for this session.

## No Issues Found In

- App.tsx — JoinScreen error-display logic and the role-based render branch are straightforward; the `ERROR_LABELS` map is correctly typed as `Record<ErrorCode, string>` ensuring compile-time completeness.
- App.css — Pure styling; no testable logic.
- LocationPanel.tsx — `LocationFields` switch covers all four node kinds plus a `default: return null` guard; the `locKey` prefix parsing is simple and correct for the known format.
- NarrativePanel.tsx — The `hasDice` guard (`deckerSuccesses !== undefined`) correctly handles the optional dice fields before rendering them.
