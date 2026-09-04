---
# Maintainability Review — ui

## Summary

The UI is compact and generally well-structured: the reducer in `useWebSocket.ts` is clean, the type definitions in `messages.ts` are precise, and the CSS variable system is consistently applied. The main maintainability problems are two outright DRY violations (a duplicated constant and two near-identical field components), a fragile parallel-structure pattern in `ActionsPanel` that requires touching three separate locations every time a new operation param type is added, a set of raw magic strings for operation names that tie together without any single source of truth, and several inline styles that duplicate CSS class rules already present in `App.css`.

---

## Findings

### [HIGH] `ERROR_LABELS` is defined identically in two files

**File:** `frontend/src/App.tsx:10` and `frontend/src/NarrativePanel.tsx:3`

**Issue:** The full `ERROR_LABELS: Record<string, string>` object — with the same four keys and values — is copy-pasted verbatim in both files. When a new server error code is introduced, a developer must remember to update both locations; one will inevitably be missed.

**Recommendation:** Extract to `frontend/src/constants/errorLabels.ts` (or add to `messages.ts` alongside `ErrorMessage`) and import it in both consumers.

---

### [HIGH] Parallel fragile extension point in `ActionsPanel` — adding a param type requires three separate edits

**File:** `frontend/src/components/ActionsPanel.tsx:26`

**Issue:** Four single-line predicate functions (`needsPrecision`, `needsPasscode`, `needsScanner`, `needsEdit`) each test one magic string. `buildParams` mirrors them in a matching if-chain. The JSX render then repeats the same predicates a third time to show the correct inline control. Adding a new operation param type (e.g. `needsTarget`) requires writing a new predicate, adding a branch to `buildParams`, and adding a JSX block — three disconnected changes with no compiler help if any one is missed.

**Recommendation:** Replace the four predicates and `buildParams` with a single discriminated lookup:

```ts
type ParamSpec =
  | { kind: 'precision' }
  | { kind: 'passcode' }
  | { kind: 'scanner' }
  | { kind: 'edit' }

const OPERATION_PARAM: Record<string, ParamSpec> = {
  LOCATE_FILE:         { kind: 'precision' },
  LOCATE_SLAVE:        { kind: 'precision' },
  LOCATE_ACCESS_NODE:  { kind: 'precision' },
  MAKE_COMCALL:        { kind: 'passcode' },
  TAP_COMCALL:         { kind: 'scanner' },
  EDIT_FILE:           { kind: 'edit' },
}
```

Both `buildParams` and the JSX render switch on `spec.kind`, so every new operation is registered in exactly one place.

---

### [MEDIUM] `Field` (LocationPanel) and `EF` (EntitiesPanel) are near-identical components defined twice

**File:** `frontend/src/components/LocationPanel.tsx:16` and `frontend/src/components/EntitiesPanel.tsx:15`

**Issue:** Both components render a `label` + `value` pair. `Field` adds an optional `cls` prop; `EF` omits it. Otherwise the DOM structure and purpose are the same. Having two names for essentially the same primitive makes it harder to apply a styling change uniformly and signals to a new contributor that they are conceptually distinct when they are not.

**Recommendation:** Create `frontend/src/components/FieldRow.tsx` (or add to a shared `ui` module) with an optional `cls` prop, and import it in both panels. The cryptic name `EF` disappears as a bonus.

---

### [MEDIUM] Operation names are raw magic strings with no shared constant definition

**File:** `frontend/src/components/ActionsPanel.tsx:27`

**Issue:** Strings like `'LOCATE_FILE'`, `'LOCATE_SLAVE'`, `'LOCATE_ACCESS_NODE'`, `'MAKE_COMCALL'`, `'TAP_COMCALL'`, and `'EDIT_FILE'` appear only in `ActionsPanel.tsx`. They are not exported, not referenced in `messages.ts`, and have no TypeScript type constraining them. If the server renames an operation, the compiler will not flag the stale string.

**Recommendation:** Either add a union type `OperationKind = 'LOCATE_FILE' | 'MAKE_COMCALL' | ...` to `messages.ts` (replacing `operation: string` in `AvailableActionDto`) or at minimum export an `OPERATIONS` const object from a constants file. This gives rename-refactoring a single point of truth.

---

### [MEDIUM] `obj.alertStatus.replace('_', ' ')` and similar transforms repeated without a helper

**File:** `frontend/src/components/LocationPanel.tsx:31`, `:41`, `:50`, `:59`

**Issue:** The pattern `someEnumValue.replace('_', ' ')` appears four times across three `case` branches of `LocationFields`. `topologyType` receives the same treatment. When a new field is added the transform is silently omitted (it already is omitted for `securityCode`). The intent — "display enum values in a human-readable way" — is not named anywhere.

**Recommendation:** Extract `function formatEnumLabel(s: string): string { return s.replace(/_/g, ' ') }` and call it at each site. Note the current code also only replaces the *first* underscore (`replace` with a plain string, not a regex); using `/_/g` is likely the correct intent.

---

### [LOW] Magic number `19` encodes a silent max-event-log size of 20

**File:** `frontend/src/hooks/useWebSocket.ts:48` and `:53`

**Issue:** `state.events.slice(-19)` keeps the 19 most-recent events so that after appending the new one the list stays at 20. The number 19 appears twice (once for `RESULT`, once for `ERROR`) with no comment or named constant explaining the limit.

**Recommendation:** Declare `const MAX_EVENTS = 20` near the top of the file and use `state.events.slice(-(MAX_EVENTS - 1))` in both reducer cases.

---

### [LOW] Magic numbers `3000` and `30000` for reconnect timing

**File:** `frontend/src/hooks/useWebSocket.ts:71`, `:80`, `:113`

**Issue:** Initial reconnect delay and maximum reconnect cap are inlined without names.

**Recommendation:** Declare `const RECONNECT_INITIAL_MS = 3_000` and `const RECONNECT_MAX_MS = 30_000` at the top of the hook file.

---

### [LOW] Magic number `10` for program-rating dot display

**File:** `frontend/src/components/DeckerPanel.tsx:65`

**Issue:** `Math.min(u.rating, 10)` and `10 - u.rating` hard-code the maximum number of rating dots at 10 without explanation.

**Recommendation:** Extract `const MAX_RATING_DOTS = 10` as a module-level constant.

---

### [LOW] Inline styles in `DeckerPanel` and `LocationPanel` duplicate CSS rules

**File:** `frontend/src/components/DeckerPanel.tsx:38` and `frontend/src/components/LocationPanel.tsx:86`, `:89`

**Issue:** `DeckerPanel` applies `style={{ fontSize: 24, letterSpacing: 2, marginBottom: 4 }}` directly on a `div`. `LocationPanel` passes `style={{ flexDirection: 'row', flexWrap: 'wrap', gap: '14px 24px', alignItems: 'flex-start' }}` to the panel body, but `App.css` already defines `.location-panel .panel-body` with the same `flex-direction`, `flex-wrap`, `gap`, and `align-items` values. The inline style redundantly overrides the class rule, meaning the CSS class is effectively dead for those properties.

**Recommendation:** Remove the duplicated inline styles and rely on the CSS class. Add a CSS class such as `.decker-name` for the decker name's sizing rather than using an inline style.

---

### [LOW] `locKey` parses location strings from a hardcoded prefix list

**File:** `frontend/src/components/LocationPanel.tsx:8`

**Issue:** The function iterates over `['RTG: ', 'LTG: ', 'PLTG: ', 'Host: ']` to split `decker.location` into a type prefix and a node name. This is parsing a presentation-formatted string rather than receiving structured data. The prefix list is disconnected from the server's serialisation logic; if the server changes capitalisation or spacing the display silently degrades to showing the raw string.

**Recommendation:** If the server can be changed, prefer sending `{ locationType: 'RTG', locationName: '...' }` as structured fields on `DeckerStateDto`. If not, at minimum declare the prefix list as a named constant and add a comment explaining the expected format.

---

### [INFO] `EF` is a cryptic component name

**File:** `frontend/src/components/EntitiesPanel.tsx:15`

**Issue:** The name `EF` is not self-documenting. A reader must look at the implementation to understand it renders an entity field row. This is a minor barrier for anyone new to the file.

**Recommendation:** Rename to `EntityField` (or consolidate with `Field` from `LocationPanel` as described above).

---

### [INFO] `focusIdx` stale-state pattern in `EntitiesPanel` may surprise future maintainers

**File:** `frontend/src/components/EntitiesPanel.tsx:74`

**Issue:** When the entity list shrinks (e.g. an IC is defeated), `focusIdx` is silently clamped via `const clamped = Math.min(...)` at render time. The state value itself is never corrected. This means the focused card can jump unexpectedly without user interaction. While currently harmless because `clamped` is used consistently, any future code that reads `focusIdx` directly (e.g. to determine what to highlight in another panel) will see the stale value.

**Recommendation:** Use a `useEffect` to reset `focusIdx` when `entities.length` drops below the current index, keeping state truthful rather than patching it on read.

---

## Clean Areas

- `messages.ts` is a thorough, well-organised type file; the discriminated union for `MatrixObjectDto` and `AvailableActionDto` gives exhaustive switch coverage without any `any` casts.
- The reducer in `useWebSocket.ts` is pure and easy to test in isolation; all side-effects (WebSocket creation, timers) are confined to `connect` and the cleanup effect.
- CSS custom properties are used uniformly; there is no stray hex colour outside `:root`, making a colour scheme change a single-file edit.
- The reconnect logic (exponential backoff, cleanup on unmount) is correct and self-contained.
- `DamageMonitor` in `DeckerPanel.tsx` is a well-extracted, single-purpose subcomponent with a clear prop interface.
- `actionLabel` in `ActionsPanel.tsx` is a clean exhaustive switch with no default fallback needed.
- `vite.config.ts` keeps the WebSocket proxy config minimal and readable; the dev/production URL logic in `useWebSocket.ts` is a one-liner that correctly handles both protocols.
---
