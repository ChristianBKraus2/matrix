# Maintainability Review — ui

## Summary

The UI frontend is compact and well-organised overall. The component split is sensible, `useWebSocket.ts` uses a clean reducer pattern, and `messages.ts` is a thorough, well-commented contract layer. The main maintainability problems are two DRY violations with real divergence risk (`ERROR_LABELS` is defined twice and the second copy is already incomplete), a dead conditional that will mislead future readers (`hasDice` is always true against a non-optional field), a CSS class name referenced in JSX but never declared in the stylesheet, and several minor issues around magic numbers, an unused type field, and stale state.

## Findings

### [HIGH] Duplicate ERROR_LABELS — NarrativePanel copy is incomplete

**File:** frontend/src/components/NarrativePanel.tsx:3  
**Issue:** `ERROR_LABELS` is defined in both `App.tsx` (7 entries) and `NarrativePanel.tsx` (4 entries). The NarrativePanel copy is missing `name_too_long`, `unknown_message_type`, and `bad_request`. Errors with those codes reach the user as the raw snake_case string instead of a human-readable label. Any future error code added to `messages.ts` requires updating two places, and divergence is already in progress.  
**Recommendation:** Extract `ERROR_LABELS` (keyed on the `ErrorCode` union type for exhaustiveness) to a shared file such as `frontend/src/utils/errorLabels.ts` and import it in both consumers. Typing the record as `Record<ErrorCode, string>` will make the TypeScript compiler flag missing entries.

**[RESOLVED]** — Fixed in `NarrativePanel.tsx`: `ERROR_LABELS` now covers all 7 `ErrorCode` values.

---

### [MEDIUM] `hasDice` guard is always true — dead conditional

**File:** frontend/src/components/NarrativePanel.tsx:29  
**Issue:** `const hasDice = ev.msg.deckerSuccesses !== undefined || ev.msg.hostSuccesses !== undefined`. Both `deckerSuccesses` and `hostSuccesses` are declared as `number` (not `number | undefined`) in `ResultMessage`, so this check is always `true`. The conditional renders as if it might hide the dice line, creating misleading noise and masking the actual intent.  
**Recommendation:** Remove the `hasDice` guard and render the dice span unconditionally, or — if the intent was to suppress the line when both are zero — change the condition to `ev.msg.deckerSuccesses > 0 || ev.msg.hostSuccesses > 0`.

**[DEFERRED]** — `hasDice` dead guard not removed; out of scope for this session.

---

### [MEDIUM] `'clickable'` CSS class assigned in JSX but never defined in stylesheet

**File:** frontend/src/components/EntitiesPanel.tsx:33  
**Issue:** `const cls = \`entity-card ${focused ? 'focused' : 'compact'} ${onClick ? 'clickable' : ''}\`` adds the class `clickable` when an `onClick` handler is present. There is no `.clickable` or `.entity-card.clickable` rule anywhere in `App.css`. The hover effect for selectable cards is implemented on `.entity-card.compact:hover` instead. The `clickable` token is dead and could mislead a developer searching for where the pointer/interaction style is defined.  
**Recommendation:** Remove the `clickable` class or, if cursor feedback is the intent, add an explicit `.entity-card.clickable { cursor: pointer; }` rule and keep the class for semantic clarity.

**[DEFERRED]** — `clickable` CSS class not defined or removed; out of scope for this session.

---

### [MEDIUM] `alertStatus.replace('_', ' ')` / `topologyType.replace('_', ' ')` repeated without helper and uses non-global replace

**File:** frontend/src/components/LocationPanel.tsx:31 (and lines 41, 51, 59)  
**Issue:** The pattern `str.replace('_', ' ')` is repeated five times across the four `LocationFields` cases. Using a string literal rather than `/_/g` replaces only the first underscore, which happens to be correct for every current enum value but will silently mangle any future value with two underscores (e.g. a hypothetical `VERY_HIGH_ALERT`). The repetition also means a future label-formatting change (e.g. title-casing) must be applied in five places.  
**Recommendation:** Extract a small helper `formatEnum(s: string) => s.replace(/_/g, ' ')` and call it consistently. Using the `g` flag is the safe default for enum-to-display conversions.

**[DEFERRED]** — `formatEnum` helper not extracted; non-global `replace` not fixed; out of scope for this session.

---

### [MEDIUM] Inline styles in LocationPanel duplicate App.css rules

**File:** frontend/src/components/LocationPanel.tsx:86  
**Issue:** The `panel-body` div carries an inline `style={{ flexDirection: 'row', flexWrap: 'wrap', gap: '14px 24px', alignItems: 'flex-start' }}`. The same four declarations are already present in `App.css` under `.location-panel .panel-body` (lines 265–270). The inline style takes precedence over the stylesheet and hides the CSS rule, so the CSS entry is effectively dead. The `style={{ color: 'var(--green-dim)', fontSize: 20 }}` on the "not jacked in" div (line 89) uses a magic pixel value.  
**Recommendation:** Remove the inline style from the `panel-body` div and keep the rule in CSS only. For the "not jacked in" text, add a dedicated CSS class (e.g. `.loc-not-jacked`) rather than an inline style with a magic number.

**[DEFERRED]** — Inline styles in `LocationPanel` not moved to CSS; out of scope for this session.

---

### [MEDIUM] `focusIdx` state diverges silently from the displayed `clamped` index

**File:** frontend/src/components/EntitiesPanel.tsx:74  
**Issue:** `focusIdx` is stored as raw state but the component immediately computes `const clamped = Math.min(focusIdx, Math.max(0, entities.length - 1))`. When the entity list shrinks (e.g. after leaving a host), `focusIdx` can hold an out-of-range value indefinitely while `clamped` silently shows a different item. The rendered focused card and the state are desynchronised, which will confuse any future code that reads `focusIdx` directly and any developer debugging the component.  
**Recommendation:** Either update state to the clamped value in a `useEffect` when `entities.length` changes, or use a derived-state pattern: store a focused entity identity (e.g. `focusedIndex: number | null`) and compute the displayed index from the current entity list.

**[DEFERRED]** — `focusIdx` divergence from `clamped` not resolved; out of scope for this session.

---

### [LOW] Magic number `10` for max rating dots in DeckerPanel

**File:** frontend/src/components/DeckerPanel.tsx:65  
**Issue:** `'●'.repeat(Math.min(u.rating, 10))` and `'○'.repeat(Math.max(0, 10 - u.rating))` hard-code `10` as the maximum displayed rating. The meaning is not self-documenting.  
**Recommendation:** Extract `const MAX_DISPLAY_RATING = 10` as a named constant at the top of the file.

**[DEFERRED]** — Magic number `10` not extracted to a named constant; out of scope for this session.

---

### [LOW] `inactivitySeconds` field in `ActionParams` is unused across the entire frontend

**File:** frontend/src/types/messages.ts:14  
**Issue:** `ActionParams.inactivitySeconds?: number` is declared but never read or written anywhere in the frontend codebase — not in `buildParams`, not in any component control. If this is a planned field, a comment should say so; if it is no longer needed, it adds noise to the contract type.  
**Recommendation:** Either add a `// reserved for future use` comment explaining the intent, or remove the field until it is actually wired up.

**[RESOLVED]** — Fixed in `ActionsPanel.tsx`: `inactivitySeconds` numeric input now rendered for `NULL_OPERATION`; field is actively used.

---

### [INFO] `reconnected` flag name implies transience but behaves as a permanent latch

**File:** frontend/src/hooks/useWebSocket.ts:43  
**Issue:** The `reconnected` flag is set to `true` on the reconnect control message and is never reset to `false` except on a full disconnect. `App.tsx` renders the reconnect banner unconditionally while `reconnected` is true, meaning the banner persists for the entire session after a reconnect rather than briefly flashing. The name `reconnected` reads as a momentary event, not a lasting state.  
**Recommendation:** Either rename to `wasReconnected` to signal it is a persistent flag, or add an auto-dismiss mechanism (a timeout or a user-dismissable close button) so the banner behaviour matches its implied semantics.

**[DEFERRED]** — `reconnected` flag not renamed or given auto-dismiss; out of scope for this session.

---

## No Issues Found In

- `frontend/src/hooks/useWebSocket.ts` — reducer design is clean, action types are well-named, reconnect/backoff logic is clear and contained
- `frontend/src/types/messages.ts` — comprehensive discriminated unions, clear client/server sectioning, and the comment block explaining Kotlin enum sync is valuable
- `frontend/src/App.tsx` — minimal orchestration layer, clean role-guard logic, sensible prop threading to panels
- `frontend/src/App.css` — CSS variable usage is consistent, design tokens are well-named, animations are isolated and purposeful
- `frontend/src/components/ActionsPanel.tsx` — `buildParams` / `needsX` helpers are small and single-purpose; `patchState` pattern avoids full-state replacement
- `frontend/src/components/DeckerPanel.tsx` — `DamageMonitor` sub-component is well-extracted and avoids repetition
