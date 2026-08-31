# Design vs. Implementation Discrepancies

Comparing design documents in `design/design_core/`, `design/design_game/`, and `design/design_ui/` against implementation code.  
Each entry notes whether the PRD (`prd_core.md`, `prd_game.md`, `prd_ui.md`) supports the **design**, the **code**, or **neither**.

---

## Combat (`combat.md` vs `CombatResolver.kt`, `IcDamageResult.kt`, `IC.kt`)

### C-1 — `IcDamageResult` has extra `personaOnlyCrashed` field

**Design (`combat.md`):** `IcDamageResult` has five fields: `updatedDecker`, `iconDamage`, `simsenseOverload`, `dumpShockTriggered`, `mpcpReductionOnKill`. No `personaOnlyCrashed`.

**Code (`IcDamageResult.kt`):** adds `val personaOnlyCrashed: Boolean = false`. Used by `LethalBlackIC.action()` to detect when the persona crashes before the decker dies and apply the +2 IC rating bonus.

**PRD (ICC-11):** "If the decker's persona icon is destroyed first … the Black IC gains a +2 Rating bonus." **PRD supports code.** The field is required to propagate this outcome from `CombatResolver` to the `IC` action handler.

**Todo:** Adapt Design — **Done:** added `val personaOnlyCrashed: Boolean = false` to `IcDamageResult` in `combat.md`

---

### C-2 — `applyIcDamage` overwrites `blackIcPin` without null-check

**Design (`combat.md`):** "The pin is only set if the decker does not already have a Black IC pin (`decker.blackIcPin == null`)."

**Code (`CombatResolver.kt`):** `applyIcDamage` unconditionally replaces any existing pin. No guard on `decker.blackIcPin == null`.

**PRD (ICC-10):** describes the pin as a single ASIST subversion state that begins on the first hit — implying only one active pin at a time. **PRD supports design.** (Note: in practice this code path is dead — `LethalBlackIC` and `NonLethalBlackIC` use their own dedicated resolution methods that bypass `applyIcDamage`.)

**Todo:** Double check whether code is actually dead. If yes, remove from design and code — **Done:** confirmed dead; removed BlackIC pin branch from `applyIcDamage` in `CombatResolver.kt` and `combat.md`; removed 3 dead tests from `CombatResolverTest.kt`

---

### C-3 — `ConditionMonitor.applyDamage(Int)` overload not in design

**Design (`combat.md`):** specifies only `applyDamage(DamageLevel)` on `ConditionMonitor`.

**Code (`CombatResolver.kt` line 122):** calls `applyDamage(stressBoxes: Int)` — an additional overload that applies a raw stress-box count.

**PRD (CC-31):** specifies that Sparky/Blaster physical overflow applies one stress box at a time. **PRD supports the behavior** but does not prescribe the API signature; the overload is a convenience, not a contradiction.

**Todo:** Adapt design to align — **Done:** added `fun applyDamage(stressBoxes: Int): ConditionMonitor` overload to `ConditionMonitor` in `combat.md` 

---

### C-4 — `resolveAttack` rolls `attackDicePool + hackingPool`; design only specifies `attackDicePool`

**Design (`combat.md` step 6):** "Attacker rolls `attackDicePool` dice."

**Code (`CombatResolver.kt` line 80):** `diceRoller.roll(attacker.attackDicePool + attacker.hackingPool, tn)`.

**PRD (prd_core.md, Hacking Pool section):** "The hacking pool may be added to any offensive cybercombat test." **PRD supports code.**

**Todo:** Adapt Design — **Done:** updated `resolveAttack` step 6 in `combat.md` to `attackDicePool + hackingPool`

---

### C-5 — `suppressIc` moved to `CombatResolver` with extra `host` parameter

**Design (`combat.md`):** `suppressIc(ic: IC): Decker` as an instance method on `Decker`.

**Code (`CombatResolver.kt`):** `CombatResolver.suppressIc(decker: Decker, ic: IC, host: Host): Decker` — static method with an additional `host` parameter used to deduct MPCP on the host's security rating.

**PRD:** does not specify API placement or signature. **PRD supports neither specifically** — the extra `host` parameter enables rating-based MPCP deduction which is rule-correct, but the method location change is an API-level deviation.

**Todo:** Adapt Design — **Done:** updated `suppressIc` in `combat.md` to `CombatResolver.suppressIc(decker: Decker, ic: IC, host: Host): Decker`

---

## Game Layer (`game.md` vs `IC.kt`)

### G-1 — `game.md` Ripper action references non-existent `CripplerResult.attributeReachedZero`

**Design (`game.md` line 301):** `if (result.attributeReachedZero)` to decide whether to call `resolveRipperMpcpTest`.

**`CripplerResult` (`combat.md`):** fields are `updatedDecker`, `targetAttribute`, `reduction` only. No `attributeReachedZero` field.

**Code (`IC.kt`):** correctly checks `(finalDecker.persona?.attribute(result.targetAttribute) ?: 0) == 0` directly on the updated decker state. This avoids the non-existent field.

**PRD (ICC-07):** "If the attribute is reduced to 0: GM makes a Ripper Test." **PRD supports code behavior.** The design doc (`game.md`) references a field that the data model (`combat.md`) never defines; code correctly works around it.

**ToDo:** Update Design — **Done:** fixed Ripper pseudocode in `game.md` to check `(finalDecker.persona?.attribute(result.targetAttribute) ?: 0) == 0`

---

### G-2 — `game.md` `LethalBlackIC.action()` missing `personaOnlyCrashed` handling

**Design (`game.md` lines 352–358):** pseudocode calls `resolveLethalBlackIc`, updates the decker, and returns. No branch for `personaOnlyCrashed`.

**Code (`IC.kt` lines 214–221):** after updating the decker, checks `result.personaOnlyCrashed`; if true, removes the current IC instance and adds `withRatingBonus(2)` as replacement.

**PRD (ICC-11):** "If the decker's persona icon is destroyed first … the Black IC gains a +2 Rating bonus for its next attack." **PRD supports code.** The design pseudocode is incomplete.

**Todo:** Update Design — **Done:** added `personaOnlyCrashed` branch to `LethalBlackIC.action()` in `game.md`

---

## Operations (`operations.md` vs `DeckerOperationsExtensions.kt`)

### OP-1 — `analyzeIcon` TN missing `persona.sensor` component

**Design (`operations.md`):** `TN = max(2, host.controlRating - persona.sensor - analyze.currentRating)`.

**Code (`DeckerOperationsExtensions.kt` lines 123–126):** passes only `host.subsystemRatings.control` as TN to `SystemTestResolver.resolve()`; `SystemTestResolver` subtracts `analyze.currentRating` internally, but `persona.sensor` is never applied.

**PRD (`prd_core.md` line 317):** "Decker may subtract Sensor Rating + Analyze rating from TN, but TN may not drop below 2." **PRD supports design.** The Sensor Rating subtraction is missing from the code. This is a functional bug that makes Analyze Icon harder than it should be.

**ToDo:** Update Code — **Done:** fixed `analyzeIcon` TN in `DeckerOperationsExtensions.kt` to subtract `persona.sensor` before passing to `SystemTestResolver`

---

### OP-2 — `tapComcall` scanner success/failure condition inverted in design doc

**Design (`operations.md`):** "If zero successes → tap succeeds (scanner does not detect). If any successes → tap fails (scanner detects)."

**Code (`DeckerOperationsExtensions.kt` lines 534–538):** `if (scannerResult.successes == 0) { return Pair(OperationResult.Failure(...), null) }` — zero successes means the tap is **detected** (failure).

**PRD (`prd_core.md` line 341):** "The decker needs at least 1 success on this test; failure means the scanner detects the tap." **PRD supports code.** The design document (`operations.md`) has the win/lose condition backwards. Code is correct per PRD.

**Todo:** Update Design — **Done:** corrected `tapComcall` scanner condition in `operations.md` to "0 successes → tap fails (scanner detects the tap)" in both the spec and verification table

---

### OP-3 — `LocatedTarget` variant names and `AccessNodeTarget` payload type differ

**Design (`operations.md`):** `LocatedTarget.File(file: DataFile)`, `LocatedTarget.Slave(device: SlaveDevice)`, `LocatedTarget.AccessNode(node: MatrixNode)`.

**Code (`DeckerOperationsExtensions.kt`):** `LocatedTarget.FileTarget(file)`, `LocatedTarget.SlaveTarget(device)`, `LocatedTarget.AccessNodeTarget(query: String)`. `AccessNodeTarget` holds a search-query string rather than the resolved `MatrixNode`.

**PRD:** does not specify sealed-class naming or payload types. **PRD supports neither** — this is a design-vs-code naming and type difference with no PRD guidance.

**Todo:** Update Design — **Done:** updated `LocatedTarget` variant names in `operations.md` to `FileTarget`, `SlaveTarget`, `AccessNodeTarget(query: String)`

---

### OP-4 — `Persona.sleaze: Utility?` in design vs `Persona.sleazeRating: Int` in code

**Design (`operations.md`):** references `targetPersona.sleaze?.currentRating ?: 0`, implying `sleaze` is a nullable `Utility` object.

**Code (`Persona.kt`):** `val sleazeRating: Int = 0` — a plain integer, not a `Utility` reference.

**PRD:** specifies Sleaze as a utility with a rating, but does not prescribe how it is stored on `Persona`. **PRD supports neither** specifically — the code is functionally equivalent (both produce the same integer rating) but the type model differs from the design doc.

**Todo:** Update Design — **Done:** replaced `sleaze?.currentRating ?: 0` with `sleazeRating` in `noticeIcon` and `locateDecker` specs in `operations.md`

---

## Movement / Logon (`movement.md` vs `DeckerNavigationExtensions.kt`)

### GL-1 — `LogonResult` has extra `deckerSuccesses` and `hostSuccesses` fields

**Design (`movement.md`):** `LogonResult.Success(decker: Decker, location: MatrixLocation)` and `LogonResult.Failure(decker: Decker, location: MatrixLocation?)`. No dice-count fields.

**Code (`DeckerNavigationExtensions.kt` lines 286–290):** constructs `LogonResult.Success` and `LogonResult.Failure` with additional `deckerSuccesses: Int` and `hostSuccesses: Int` parameters.

**PRD UI (`design_ui/design_ui.md`, `ResultMessage`):** `deckerSuccesses: number` and `hostSuccesses: number` are top-level fields on every `ResultMessage` sent to the client. **PRD UI supports code.** The extra fields flow through to the WebSocket response so the client can display dice outcomes.

**Todo:** Update Design — **Done:** added `deckerSuccesses: Int` and `hostSuccesses: Int` fields to `LogonResult.Success` and `LogonResult.Failure` in `movement.md`

---

## WebSocket Dispatch (`game.md` + `design_ui.md` vs `WebSocketDeckerController.kt`)

### UI-1 — Grid context dispatch does not handle `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`

**Design (`game.md` line 396):** grid context (`OnLTG / OnRTG / OnPLTG`) supports: `RELOCATE_ICON`, `NULL_OPERATION`, `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`.

**Code (`WebSocketDeckerController.kt`, `dispatchGridOperation`):** only handles `NULL_OPERATION` (returns success) and `RELOCATE_ICON` (returns an error — see UI-2). All other operations return "not supported on grid."

**PRD (M-07):** "LOCATE_ACCESS_NODE — available from RTG." Partially confirms at least one of the four missing operations. **PRD partially supports design.** This is a functional gap: four valid grid operations are silently rejected by the server.

**Todo:** Implement the corresponding code — **Done:** added `resolveInterrogation(Grid)` to `SystemTestResolver`, four grid extension overloads to `DeckerOperationsExtensions`, and rewrote `dispatchGridOperation` in `WebSocketDeckerController` to handle `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`

---

### UI-2 — `RELOCATE_ICON` rejected on grid; design lists it as grid-valid

**Design (`game.md` line 396):** lists `RELOCATE_ICON` as valid in grid context.

**Code (`WebSocketDeckerController.kt`):** `RELOCATE_ICON` in `dispatchGridOperation` returns the error "RELOCATE_ICON requires a host context."

**PRD:** Relocate Icon requires a Control subsystem test. Control is a host subsystem, not a grid-level resource. **PRD supports code** — Relocate Icon can only be performed inside a host where the Control subsystem is accessible. The design doc (`game.md`) appears to incorrectly include it in the grid-context operation list.

**Todo:** Update Design — **Done:** removed `RELOCATE_ICON` from grid context operations list in `game.md`