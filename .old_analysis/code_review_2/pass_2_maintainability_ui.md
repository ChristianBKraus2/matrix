# Maintainability Review — ui

## Summary

The UI codebase is compact and well-structured for its size. Component responsibilities are clear, the reducer pattern in `useWebSocket` is solid, and the TypeScript types in `messages.ts` are a strong foundation. However, several maintainability hazards are present: a duplicated (and incomplete) error-label map spreads across two files and silently degrades user-facing messages; inline styles in `LocationPanel` duplicate rules that already exist in the stylesheet; dead state (`deckerName`) and dead logic (`hasDice`) litter the codebase with confusion; and a handful of magic strings and magic numbers will cause future breakage when the backend changes. None of these are architectural problems, but they would all slow down the next developer touching this code.

---

## Findings

### [HIGH] ERROR_LABELS duplicated with incomplete coverage in NarrativePanel

**File:** `frontend/src/components/NarrativePanel.tsx:3` and `frontend/src/App.tsx:10`

**Issue:** `ERROR_LABELS` is defined twice. The copy in `App.tsx` is typed `Record<ErrorCode, string>` and covers all seven error codes. The copy in `NarrativePanel.tsx` is typed `Record<string, string>` and covers only four of them (`not_your_turn`, `no_action_pending`, `already_registered`, `name_already_taken`). The three missing codes — `name_too_long`, `unknown_message_type`, `bad_request` — fall through to the raw server string shown to the user. Whenever a new error code is added to the backend, only the `App.tsx` copy is likely to be updated.

**Recommendation:** Move one canonical `ERROR_LABELS: Record<ErrorCode, string>` into a shared constants file (e.g., `frontend/src/constants/errorLabels.ts`) and import it in both consumers. Restoring the `Record<ErrorCode, string>` type on the shared copy gives compile-time enforcement that every code is handled.

---

### [MEDIUM] Magic string `'not jacked in'` duplicated in LocationPanel

**File:** `frontend/src/components/LocationPanel.tsx:75` and `:87`

**Issue:** The sentinel string `'not jacked in'` is compared twice in the same component. It is a backend-generated value embedded directly as a string literal. If the server ever changes this string (e.g., to `'NOT_JACKED_IN'` to match the enum conventions used elsewhere), both comparisons silently stop matching and the component renders nothing.

**Recommendation:** Extract `const NOT_JACKED_IN = 'not jacked in'` as a named constant at the top of the file (or in a shared constants module) and use it in both places.

---

### [MEDIUM] Inline styles in LocationPanel duplicate existing CSS rules

**File:** `frontend/src/components/LocationPanel.tsx:86`

**Issue:** The `panel-body` div carries `style={{ flexDirection: 'row', flexWrap: 'wrap', gap: '14px 24px', alignItems: 'flex-start' }}`. These four declarations are already present word-for-word in `App.css` lines 265–270 under `.location-panel .panel-body`. The inline style takes precedence over the stylesheet, so the CSS rule is silently overridden and any future CSS change to that class has no effect.

**Recommendation:** Remove the inline `style` prop entirely. The CSS rule already covers it.

---

### [MEDIUM] `deckerName` is stored in reducer state but never consumed

**File:** `frontend/src/hooks/useWebSocket.ts:20` and `:41`

**Issue:** `WsState.deckerName` is initialised to `null`, populated in the `CONTROL` reducer case, and returned as part of the hook's spread (`...state`). No component reads it. It is dead state that adds noise to the hook's interface and the type, and has to be maintained in the reducer indefinitely.

**Recommendation:** Remove `deckerName` from `WsState` and the reducer unless there is a concrete plan to use it. If future UI needs it, re-add it then.

---

### [MEDIUM] `hasDice` check is always `true` — dead logic

**File:** `frontend/src/components/NarrativePanel.tsx:29`

**Issue:** `hasDice` is computed as `ev.msg.deckerSuccesses !== undefined || ev.msg.hostSuccesses !== undefined`. In `messages.ts`, both `deckerSuccesses: number` and `hostSuccesses: number` are required (non-optional) fields on `ResultMessage`, so both checks are always `true`. The guarded dice display block always renders. This looks like a leftover from a time when the fields were optional.

**Recommendation:** Remove the `hasDice` variable and the conditional wrapper; render the dice span unconditionally. If the fields ever become genuinely optional again, TypeScript will enforce updating the type before re-adding the guard.

---

### [MEDIUM] `reconnected` banner flag never resets to `false`

**File:** `frontend/src/hooks/useWebSocket.ts:43`

**Issue:** The reducer only ever sets `reconnected: true`; there is no path back to `false` while the socket stays connected. The banner in `App.tsx` (`SESSION RESTORED — reconnected to active game`) therefore persists for the entire session after the first reconnect. A second reconnect also has no visible effect because the value is already `true`.

**Recommendation:** Either dismiss the banner after a timeout (e.g., with a `useEffect` + `useState` in the banner component) or add a `DISMISS_RECONNECT` action to the reducer that `App` dispatches after a few seconds.

---

### [MEDIUM] Magic numbers in useWebSocket reconnect logic

**File:** `frontend/src/hooks/useWebSocket.ts:74` and `:83` and `:117`

**Issue:** Three numeric literals appear inline with no explanation: the initial reconnect delay (`3000` ms), the maximum reconnect delay (`30000` ms), and the event-queue capacity expressed as a slice offset (`-19`, meaning keep the last 20). Their meaning and relationships are not obvious from context.

**Recommendation:** Extract named constants at the top of the file:
```ts
const RECONNECT_INITIAL_MS = 3_000
const RECONNECT_MAX_MS     = 30_000
const MAX_EVENTS           = 20
```
Then use `state.events.slice(-(MAX_EVENTS - 1))` for the slice.

---

### [MEDIUM] Inline style on decker name in DeckerPanel

**File:** `frontend/src/components/DeckerPanel.tsx:38`

**Issue:** `style={{ fontSize: 24, letterSpacing: 2, marginBottom: 4 }}` is applied directly to the decker name `div`. The rest of the component's typography uses CSS classes defined in `App.css`. This one inline style is inconsistent with that convention and will not respond to future theming changes.

**Recommendation:** Add a `.decker-name` CSS class to `App.css` and replace the inline style with `className="decker-name"`.

---

### [LOW] `EF` is a cryptic component name

**File:** `frontend/src/components/EntitiesPanel.tsx:15`

**Issue:** The `EF` component renders a labeled field pair. Its name gives no indication of purpose to a developer reading the file for the first time.

**Recommendation:** Rename to `EntityField` (or `LabeledField` if it is intended for broader reuse). The component is small; the rename is trivial.

---

### [LOW] `focusIdx` state is never reset when entity list shrinks

**File:** `frontend/src/components/EntitiesPanel.tsx:74`

**Issue:** When the `visibleObjects` list shrinks and the focused index falls out of range, `clamped` silently corrects it for rendering, but `focusIdx` in state retains the stale value. If entities are added back and the list grows, the previously-stale index becomes active again unexpectedly.

**Recommendation:** Either derive the focused entity purely from clamped render-time logic (as is done now) and document the intentional mismatch, or reset `focusIdx` to `0` in a `useEffect` that depends on `entities.length` to keep the state self-consistent.

---

### [LOW] `locKey` prefix array contains an inconsistently-cased entry

**File:** `frontend/src/components/LocationPanel.tsx:9`

**Issue:** `const prefixes = ['RTG: ', 'LTG: ', 'PLTG: ', 'Host: ']` — the first three prefixes are all-caps, but `'Host: '` uses title case. This reflects the backend's output and may be intentional, but the inconsistency is not documented and will confuse anyone checking whether a new node type's prefix needs to be added.

**Recommendation:** Add a brief comment explaining that these string prefixes match the server's `location` field format exactly, and link to the server class or serialisation method that generates them.

---

### [LOW] `CardState` partially duplicates `ActionParams` shape

**File:** `frontend/src/components/ActionsPanel.tsx:34`

**Issue:** `CardState` holds `precision`, `hasValidPasscode`, `scannerDeviceRating`, and `newContent` — a superset of the fields in `ActionParams`. The `buildParams` function then selectively copies from `CardState` into `ActionParams`. If `ActionParams` gains a new field, `CardState` and `buildParams` must both be updated manually.

**Recommendation:** Consider making `CardState` extend or directly reuse `ActionParams` field types, or at minimum co-locate a comment on `CardState` explaining that it mirrors `ActionParams` so maintainers know to check both when making changes.

---

### [LOW] `JoinScreen` component defined inline in App.tsx

**File:** `frontend/src/App.tsx:20`

**Issue:** `JoinScreen` is a self-contained component (~55 lines) defined inside `App.tsx`. As the file grows, having two components sharing a single file reduces discoverability and makes the module's purpose less obvious.

**Recommendation:** Move `JoinScreen` to `frontend/src/components/JoinScreen.tsx`.

---

### [LOW] Magic number `10` for utility rating display

**File:** `frontend/src/components/DeckerPanel.tsx:65`

**Issue:** `Math.min(u.rating, 10)` and `Math.max(0, 10 - u.rating)` cap the displayed pip count at 10. The value `10` has no named definition.

**Recommendation:** Extract `const MAX_DISPLAYED_RATING = 10` near the top of the component.

---

### [INFO] `.badge-blue` CSS class defined but never applied

**File:** `frontend/src/App.css:336`

**Issue:** `.badge-blue` is defined in the stylesheet but no component emits it.

**Recommendation:** Remove it, or add a comment indicating it is reserved for future use (e.g., a forthcoming Device badge).

---

### [INFO] `.action-operation` CSS class defined but never applied

**File:** `frontend/src/App.css:375`

**Issue:** `.action-operation` is defined but not referenced by any component. It appears to be a leftover from an earlier iteration of `ActionsPanel`.

**Recommendation:** Remove the rule.

---

### [INFO] Hard-coded colour `#000a00` in `.game-grid` not using CSS variable

**File:** `frontend/src/App.css:40`

**Issue:** `background: #000a00` on `.game-grid` is the only colour in the stylesheet that is not referenced through a `:root` CSS variable, making it invisible to any future theme-change that touches the variable definitions.

**Recommendation:** Add `--bg-grid: #000a00` to `:root` and use `background: var(--bg-grid)`.

---

## No Issues Found In

- **`messages.ts`** — Type definitions are thorough, the discriminated unions are correct, the comment block documenting Kotlin mirror types is a good practice, and the `ErrorCode` union type enables exhaustive checking.
- **`useWebSocket.ts` reducer** — The reducer is pure, all action cases are handled, and the state shape is logically cohesive.
- **`ActionsPanel.tsx` param-building logic** — `operationOf`, `needsPrecision`, `needsPasscode`, `needsScanner`, `needsEdit`, and `buildParams` are small, focused, and easy to extend when new operations are added.
- **`DeckerPanel.tsx` `DamageMonitor`** — Clean, reusable sub-component with a clear interface.
- **`App.tsx` role-check logic** — `isRegistered` derives cleanly from `ws.role` and the two-phase rendering (join → waiting → game) is easy to follow.
- **CSS variable system** — Colour palette, glow, border, and font are all centralised in `:root` and used consistently throughout (one exception noted above).
