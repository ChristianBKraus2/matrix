# Design-vs-Code Discrepancies (excluding deferred items)

Audit run: 2026-09-02. Process: `design/align.md`. Deferred items excluded per `design/deferred.md`.

---

## INT-1 — interrogationStates uses enum keys, not context-scoped string keys

**Design:** `prd_game.md` specifies `interrogationStates: Map<String, InterrogationState>` with keys in the format `"OPERATION_NAME@CONTEXT"` (e.g. `"LOCATE_ACCESS_NODE@GRID"`, `"LOCATE_ACCESS_NODE@HOST"`), allowing `LOCATE_ACCESS_NODE` to maintain independent accumulated-success state when used at a grid node versus inside a host.

**Code:** `Decker.kt:48` declares `val interrogationStates: Map<SystemOperation, InterrogationState> = emptyMap()`. Both `locateAccessNode(host, ...)` and `locateAccessNode(grid, ...)` in `DeckerOperationsExtensions.kt` key on `SystemOperation.LOCATE_ACCESS_NODE` (lines 253, 281), sharing the same map entry regardless of context.

**Impact:** If a decker begins `LOCATE_ACCESS_NODE` on an LTG and then moves to a host and issues the same operation, the accumulated successes from the grid search are continued into the host search (and vice versa). These are different searches against different targets; they should start fresh. Any operation that a caller might want to disambiguate by context (present or future) is similarly affected.

**PRD verdict:** `prd_game.md` is explicit: string keys with `@CONTEXT` suffix. No PRD clause permits the current single-key design.

**Status / Fix required:** Change `interrogationStates` to `Map<String, InterrogationState>`. Use keys `"${operation.name}@${contextTag}"` where `contextTag` is `"HOST"`, `"RTG"`, `"LTG"`, or `"PLTG"` derived from `currentLocation`. Update all six read/write sites in `DeckerOperationsExtensions.kt`.

**todo** apply proposal

---

## MC-1 — ANALYZE_IC success never persisted; analyzed flag always false

**Design:** `design_core/operations.md` and `design_ui/design_ui.md` specify that after a successful `ANALYZE_IC` operation the IC's `rating`, `behavior`, and `guardedNodeType` become visible to the client (`IcProgram.analyzed === true`). `MatrixObjectDto.IcProgram` encodes this with `analyzed: Boolean` and conditionally exposes the detail fields only when `analyzed` is true.

**Code:** `Decker.visibleObjects()` (`Decker.kt:112`) always emits:
```kotlin
loc.host.icPrograms.forEach { add(MatrixObject.IcProgram(it)) }
```
The default for `analyzed` is `false`. `analyzeIc()` and `analyzeIcon()` in `DeckerOperationsExtensions.kt` (lines 113-133) return `OperationResult` which contains only the updated decker (tally increment) and outcome. There is no field on `Decker` that records which IC have been analyzed, and no write site updates `analyzed` to `true` in the production code path.

The `MatrixObjectDto.IcProgram` mapping in `MatrixObjectDto.kt:131-134` correctly gates `rating`, `behavior`, and `guardedNodeType` on `analyzed`, but because the flag is always `false`, those fields are always `null` for every client.

**Impact:** `ANALYZE_IC` and `ANALYZE_ICON` produce system-test results and tally increments, but the player never sees the IC details they paid an action to learn. The analyzed state is silently discarded on every state broadcast.

**PRD verdict:** No PRD clause permits discarding the analyzed result. The design intent is unambiguous.

**Status / Fix required:** Add `analyzedIcIds: Set<String>` (or equivalent) to `Decker`, populated by `analyzeIc()` / `analyzeIcon()` on success. Update `visibleObjects()` to emit `MatrixObject.IcProgram(ic, analyzed = ic.id in analyzedIcIds)`. Clear the set on logoff (both paths in `DeckerNavigationExtensions.kt` already clear analogous sets).

**todo** apply proposal

---

## CC-1 — Jack-out while pinned by Black IC hard-blocked instead of Willpower test

**Design:** `design_core/combat.md` specifies `CombatResolver.resolveJackOutWithPin()`: the pinned decker rolls Willpower dice against the Black IC's rating; one or more successes allows the jack-out (the IC still makes a final attack). Failure means the jack-out is rejected. The decker is NOT absolutely prevented from attempting.

**Code:** `WebSocketDeckerController.dispatch()` (line 149-153):
```kotlin
is AvailableAction.JackOut -> {
    if (decker.isPinnedByBlackIc)
        DispatchResult(decker, false, 0, 0, "Pinned by Black IC — cannot jack out")
    else
        decker.jackOut().toDispatch()
}
```
`CombatResolver.resolveJackOutWithPin()` exists in `CombatResolver.kt:142-151` and is never called from this path.

**Impact:** A pinned decker can never jack out voluntarily, regardless of Willpower. The Black IC's final attack (which should fire even on a successful escape) is also never triggered.

**PRD verdict:** `prd_core.md` (cybercombat) describes the Willpower-vs-IC-rating test for escaping a pin. Hard-blocking contradicts this.

**Status / Fix required:** When `isPinnedByBlackIc`, call `CombatResolver.resolveJackOutWithPin(decker, diceRoller)` to get a `JackOutPinResult`. If `succeeded`, proceed with `jackOut()`. If `finalIcAttackTriggered`, resolve the IC's final attack before clearing the pin. If not `succeeded`, return a failure result.

**todo** apply proposal

---

## CM-1 — Dump shock flag set but damage never applied

**Design:** `design_core/combat.md` specifies `CombatResolver.resolveDumpShock(decker, host, diceRoller)`: the decker rolls Body dice vs the host's Security Rating value; net staging applies a DamageLevel to the mental condition monitor. Cyberterminal users are immune (CT-04).

**Code:** `jackOut()` and `gracefulLogoff()` (failure branch) in `DeckerNavigationExtensions.kt` (lines 246-253, 234-242) both set `dumpShock = !cyberdeck.isCyberterminal` in `LogoffResult.JackOut`. The controller's `LogoffResult.toDispatch()` (line 384) only converts the flag to a string:
```kotlin
is LogoffResult.JackOut -> DispatchResult(decker, true, 0, 0,
    if (dumpShock) "Jacked out (dump shock!)" else "Jacked out")
```
`CombatResolver.resolveDumpShock()` exists at `CombatResolver.kt:131-138` and is never called from this path. The decker's `mentalConditionMonitor` takes zero damage.

**Impact:** Deckers jack out and suffer no consequence. The dump shock mechanic (a core deterrent to voluntary jack-out) is announced in the message but never resolved.

**PRD verdict:** `prd_core.md` specifies dump shock damage on every involuntary disconnect and on graceful logoff failure. No clause allows skipping the dice roll.

**Status / Fix required:** In `WebSocketDeckerController`, after receiving a `LogoffResult.JackOut` with `dumpShock = true`, call `CombatResolver.resolveDumpShock(decker, host, diceRoller)` and use the returned updated decker as the session state. Requires the host reference to be available at dispatch time (it is: `(decker.currentLocation as? MatrixLocation.OnHost)?.host`).

**todo** apply proposal

---

## OP-1 — Grid NULL_OPERATION skips system test entirely

**Design:** `design_core/operations.md` describes `NULL_OPERATION` as a System Test at the current node (control subsystem TN, security value as dice pool, with inactivity time bonus added to the host security value). The operation is listed for both host and grid contexts.

**Code:** `dispatchGridOperation()` in `WebSocketDeckerController.kt:174`:
```kotlin
SystemOperation.NULL_OPERATION -> DispatchResult(decker, true, 0, 0, "Turn passed")
```
No system test is resolved. No tally is updated. In contrast, the host path (`dispatchMiscOp()` line 347) correctly calls `decker.nullOperation(host, p?.inactivitySeconds ?: 0, diceRoller)`. The extension `Decker.nullOperation(grid, ...)` (`DeckerOperationsExtensions.kt:487`) and `SystemTestResolver.resolveNullOperation(grid, ...)` (`SystemTestResolver.kt:74`) exist but are never called from the grid dispatch path.

**Impact:** A decker on a grid node who passes their turn accrues no host security successes and no tally increase — the operation is risk-free. This breaks the inactivity pressure that NULL_OPERATION is designed to create on all nodes.

**PRD verdict:** `prd_core.md` (SO individual table, Null Operation) applies to any node with a security value. No clause exempts grid nodes.

**Status / Fix required:** Replace the grid branch with:
```kotlin
SystemOperation.NULL_OPERATION -> decker.nullOperation(grid, p?.inactivitySeconds?.coerceIn(0, 3600) ?: 0, diceRoller).toDispatch()
```
where `grid` is already resolved earlier in `dispatchGridOperation()`.

**todo** apply proposal

---

## DS-1 — Decker.detectedIcons never populated in production code

**Design:** `design_core/ord.md` and Matrix Perception PRD clauses (MP-01 through MP-10) describe a Sensor Test workflow by which a decker discovers icons. The `detectedIcons: Set<Icon>` field on `Decker` encodes which icons the decker has noticed.

**Code:** `Decker.kt:49` declares `val detectedIcons: Set<Icon> = emptySet()`. It is cleared on logoff (both paths in `DeckerNavigationExtensions.kt`). The functions `noticeIcon()` and `noticeTriggeredIc()` in `DeckerOperationsExtensions.kt` (lines 50-81) return `SensorTestResult` / `IcDetectionResult` respectively, but no production call site updates `detectedIcons` from these results. `visibleObjects()` does not gate IC visibility on `detectedIcons` — it shows all IC unconditionally. The set is not mapped into any DTO field.

**Impact:** The Matrix Perception mechanic (Sensor Tests to notice icons before they act) has no effect on game state. All icons are visible to the decker at all times regardless of Sensor score, and `detectedIcons` always remains empty. `noticeIcon()` and `noticeTriggeredIc()` are effectively dead code.

**PRD verdict:** PRD MP-01 through MP-09 describe a distinct visibility layer. The field stub suggests this was planned but not wired.

**Status / Fix required:** Either: (a) wire the IC behavior `action()` methods to call `noticeIcon()` and update `detectedIcons` on the decker before targeting, and update `visibleObjects()` to filter IC through `detectedIcons`; or (b) if this is intentionally deferred, add it to `design/deferred.md` so it is not re-flagged in future audits.

**todo** apply proposal

---

## Files read with no discrepancies found

The following files were read during the audit and contained no deviations from the design docs (field names, algorithms, defaults, and wire format all consistent):

**Domain model:** `Enums.kt`, `SharedTypes.kt`, `Cyberdeck.kt`, `Persona.kt`, `Utility.kt`, `IC.kt` (all hierarchy), `SystemOperation.kt`, `AvailableAction.kt`, `InterrogationState.kt`, `Host.kt`, `Grid.kt` (RTG/LTG/PLTG), `MatrixObject.kt`, `Combat.kt`, `CombatResolver.kt` (all resolution methods), `SystemTestResolver.kt` (all overloads).

**Business logic:** `DeckerNavigationExtensions.kt` (all logon/logoff paths), `DeckerMemoryExtensions.kt` (loadUtility, unloadUtility, swapUtility, advanceCombatTurn), `DeckerOperationsExtensions.kt` (all operations except noted stub in DS-1), `GameContext.kt`, `Game.kt`.

**Server/Controller/DTOs:** `WebSocketDeckerController.kt` (all dispatch branches except CC-1/CM-1/OP-1 noted above), `DeckerStateDto.kt` (`toDto()` mapping), `MatrixObjectDto.kt` (all variants and mapping), `AvailableActionDto.kt` (all variants including `paramKind` mapping — matches design_ui.md table exactly).

**Frontend:** `frontend/src/types/messages.ts` (all types match server DTO definitions), `frontend/src/components/ActionsPanel.tsx` (all five `paramKind` controls rendered correctly: `precision`, `hasValidPasscode`, `scannerDeviceRating`, `newContent`, `dataSize`).

**Tests:** `DeckerVisibilityTest.kt` (assertions match code behaviour), `DtoMappingTest.kt` (all DTO mapping tests encode correct expectations).

---

## Deferred items not flagged (per design/deferred.md)

- Decker.action() callback wiring (deferred entry 1)
- SWAP_MEMORY operation dispatch (deferred entry 2)
- LOCATE_DECKER dispatch via controller (deferred entry 3)
- locationIndex stub — always 0 when jacked in (deferred / design doc explicitly marks as future improvement)
- `Scramble.action()` no-op (deferred entry 9)
