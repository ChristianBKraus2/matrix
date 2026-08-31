# Design vs Implementation Discrepancies

Audit conducted against all design documents (`design.md`, `protocol.md`, `movement.md`, `creation.md`,
`operations.md`, `combat.md`, `cyberdeck_and_program_mechanics.md`, `ord.md`, `game.md`, `design_ui.md`)
and all PRDs (`prd_core.md`, `prd_game.md`, `prd_ui.md`), cross-referenced with the full Kotlin/Spring
source tree.

---

## Summary Table

| ID   | Area                   | Severity | Short description                                              |
|------|------------------------|----------|----------------------------------------------------------------|
| C-1  | Combat                 | High     | Armor utility never reduces IC attack power in decker defense  |
| MC-1 | Movement / Creation    | Medium   | `jackInToHost` silently drops `deckerSuccesses`/`hostSuccesses` |
| IC-1 | Intrusion Countermeasures | Medium | Scramble IC has no reactive callback on Decrypt operations     |
| IC-2 | Intrusion Countermeasures | Medium | Reactive IC participates in normal initiative order            |
| CD-1 | Cyberdeck              | Medium   | `DownloadDestination` sealed class not implemented             |
| CD-5 | Cyberdeck              | Medium   | Medic utility cannot be invoked through the action interface   |
| OP-1 | Operations             | Medium   | `NULL_OPERATION` has no `paramKind`; `inactivitySeconds` UI missing |
| GL-1 | Game Logic             | Medium   | Meatworld comm action-timing displacement not implemented      |
| CD-2 | Cyberdeck              | Low      | `Accessory.kt` in wrong package                               |
| CD-3 | Cyberdeck              | Low      | CT-03 detection flag mismatch (`immuneToDumpShock` vs `isCyberterminal`) |
| CD-4 | Cyberdeck              | Low      | Design typo `invokeMediac`; code correctly has `invokeMedic`  |
| MC-2 | Movement / Creation    | Low      | Grid overloads for operations undocumented in `operations.md`  |
| OP-2 | Operations             | Low      | `SWAP_MEMORY` absent from `SystemOperation` enum entirely      |
| UI-1 | User Interface         | Low      | `protocol.md` `IcProgram` DTO omits `analyzed`/`guardedNodeType` fields |

---

## Discrepancy Detail

---

### C-1 — Armor utility never reduces IC attack power in decker defense
**Severity:** High (wrong game rule — incorrect combat resolution)

**Design says:**
`combat.md` specifies `resolveAttack` computes `effectivePower = max(0, power - defender.armorCurrentRating)`,
and its own verification table (CC-28) states: *"Armor-4 vs. Power 7 → effectivePower = 3; defender rolls vs. 3"*.
`asDefenderParticipant()` is the extension that builds the `DefenderParticipant` when IC attacks a decker.
`design/design_game/game.md` shows the extension code but silently passes `armorCurrentRating = 0`.

**Code does:**
`DeckerExtensions.kt` — `Decker.asDefenderParticipant()` always sets `armorCurrentRating = 0`,
regardless of whether an Armor utility is loaded:
```kotlin
return DefenderParticipant(bod = p.bod, armorCurrentRating = 0, ...)
```
As a result, Killer, Blaster, and Sparky IC attacks resolve with full weapon power against the decker.
Only the Black IC resolvers (`resolveLethalBlackIc`, `resolveNonLethalBlackIc`) manually read
`activeUtilities.firstOrNull { it.type == UtilityType.ARMOR }?.currentRating` and apply it.
All other IC ignore the Armor utility entirely.

**PRD verdict:** PRD CC-28 references the "Armor-4 vs Power 7" scenario as the canonical verification
test. The PRD supports the design expectation that Armor reduces effective power. Code is wrong.

---

### MC-1 — `jackInToHost` silently drops `deckerSuccesses` / `hostSuccesses`
**Severity:** Medium (missing data — ResultMessage always reports 0/0 successes for this operation)

**Design says:**
`protocol.md` states `ResultMessage.deckerSuccesses` and `ResultMessage.hostSuccesses` are *"always present
(never null)"* and carry the actual dice-roll counts for every action. `movement.md` defines
`LogonResult.Success(decker, location, deckerSuccesses, hostSuccesses)` with these as required fields.

**Code does:**
`DeckerNavigationExtensions.kt` — `jackInToHost` calls the shared `performLogon()` helper which correctly
populates both counts in the returned `LogonResult.Success`. However, `jackInToHost` then creates a new
`LogonResult.Success` to set the persona's starting node, omitting the forwarded counts:
```kotlin
// performLogon() returned result with deckerSuccesses/hostSuccesses correctly set
LogonResult.Success(updatedDecker, result.location)   // ← drops both counts; defaults to 0
```
All other movement methods (`jackInToLtg`, `logonToRtg`, `logonToLtg`, `logonToPltg`, `logonToHost`)
return the `performLogon()` result directly and preserve the counts correctly.

**PRD verdict:** PRD M-04/M-05 do not specify success-count reporting, but `protocol.md` (the wire
protocol spec) requires the counts. PRD supports the protocol spec. Code is wrong.

---

### IC-1 — Scramble IC has no reactive callback on Decrypt operations
**Severity:** Medium (missing feature — Scramble IC never triggers)

**Design says:**
`combat.md` and `game.md` describe Scramble as *"reactive IC that does not perform proactive actions.
It responds to decker operations (e.g. destructing a file) via the game engine, not through the standard
action turn."* PRD ICC-04 specifies that Scramble activates when a decker attempts to decrypt a
scramble-protected file, adding Security Tally points and potentially destroying the data.

**Code does:**
`IC.kt` — `Scramble.action()` unconditionally returns `ActionResult.NoTarget`. There is no callback
hook, no event-dispatch mechanism, and no call to `Scramble.action()` anywhere in
`DeckerOperationsExtensions.kt`'s `decryptFile()` (or similar) implementation.
Scramble IC on a host is thus completely inert for the entire run.

**PRD verdict:** PRD ICC-04 supports the design. Code does not implement the feature.

---

### IC-2 — Reactive IC participates in normal initiative order
**Severity:** Medium (wrong game rule — incorrect action timing)

**Design says:**
`combat.md` CC-02: *"Reactive IC programs that perform tasks at the end of a Combat Turn act after
all deckers have completed their allotted actions for that turn. The game engine must not resolve
reactive IC callbacks until the decker action phase for that turn is fully resolved."*

**Code does:**
`Game.kt` — `runCombatTurn()` builds one flat initiative list containing every `ActiveIcon` (deckers
and all IC) and processes them strictly by descending initiative score:
```kotlin
val icons: List<ActiveIcon> = context.deckers.toList() + context.activeIc.toList()
```
TarBaby and TarPit (`IcBehavior.REACTIVE`) are included in that list and can act before a decker
if their initiative score is higher. There is no end-of-turn deferral for reactive IC.

**PRD verdict:** PRD CC-02 supports the design. Code does not implement the deferral.

---

### CD-1 — `DownloadDestination` sealed class not implemented
**Severity:** Medium (missing feature — offline-storage downloads cannot be routed)

**Design says:**
`cyberdeck_and_program_mechanics.md` ACC-01 specifies a `DownloadDestination` sealed class:
```kotlin
sealed class DownloadDestination {
    object ActiveMemory   : DownloadDestination()
    object StorageMemory  : DownloadDestination()
    data class OfflineStorage(val accessory: Accessory) : DownloadDestination()
}
```
`DownloadHandle` is specified to carry an optional `destination: DownloadDestination` field so that
a download can be routed to an offline-storage accessory rather than the deck's own storage.

**Code does:**
`DownloadHandle.kt` — no `destination` field:
```kotlin
data class DownloadHandle(
    val file: DataFile, val totalMp: Int,
    val ioSpeedMpPerTurn: Int, val turnsRemaining: Int, val active: Boolean = true
)
```
`DownloadDestination` is not defined anywhere in the codebase. Downloads always implicitly target
the decker's storage memory; offline-storage accessories (ACC-01) have no download effect.

**PRD verdict:** PRD ACC-01 supports the design. Code does not implement the feature.

---

### CD-5 — Medic utility cannot be invoked through the action interface
**Severity:** Medium (missing feature — player has no way to activate Medic)

**Design says:**
`cyberdeck_and_program_mechanics.md` CD-20 states `invokeMedic` costs a Complex Action and repairs
icon Condition Monitor boxes. PRD CD-20 confirms it is an explicit decker action.

**Code does:**
`DeckerOperationsExtensions.kt` defines `fun Decker.invokeMedic(...)` as a callable method.
However, `Decker.availableActions()` in `Decker.kt` never adds an `AvailableAction.Operation` entry
for Medic; there is no corresponding `SystemOperation` enum entry; and `WebSocketDeckerController`
has no dispatch branch for it. The method is unreachable from the game UI or the WebSocket protocol.

**PRD verdict:** PRD CD-20 supports the design. Code defines the resolver but provides no action hook.

---

### OP-1 — `NULL_OPERATION` has no `paramKind`; `inactivitySeconds` UI missing
**Severity:** Medium (missing feature — frontend cannot render inactivity-seconds control)

**Design says:**
`protocol.md` `params` table: `NULL_OPERATION | inactivitySeconds (int, 0–3600)`.
The backend `SystemTestResolver.resolveNullOperation()` applies a Security Value bonus based on the
supplied `inactivitySeconds` value, so the parameter is mechanically significant.

**Code does:**
`AvailableActionDto.kt` — the `paramKind` mapping covers `LOCATE_FILE`, `LOCATE_SLAVE`,
`LOCATE_ACCESS_NODE`, `MAKE_COMCALL`, `TAP_COMCALL`, and `EDIT_FILE`, but omits `NULL_OPERATION`:
```kotlin
else -> null   // NULL_OPERATION falls here
```
`design_ui.md`'s `paramKind` table also omits `NULL_OPERATION`. The frontend therefore receives
`paramKind: null` for NULL_OPERATION and renders no inline control, so the player cannot supply
`inactivitySeconds`; the backend always resolves with the minimum bonus.

**PRD verdict:** PRD SO individual table (Null Operation) supports the protocol spec.
Code is wrong (`AvailableActionDto`) and design_ui.md is incomplete.

---

### GL-1 — Meatworld comm action-timing displacement not implemented
**Severity:** Medium (missing feature — combat timing rule not enforced)

**Design says:**
`combat.md` CC-06: a decker communicating with the meatworld via a non-exempt channel (not hitcher
electrodes, not datascreen-only) has their Matrix actions resolved in the *physical-action slot* of
each Initiative Pass, even if their initiative score would let them act earlier. The design states
the game engine must insert the decker into the physical-action slot.

**Code does:**
`CombatResolver.rollDeckerInitiative()` applies the `commPenalty = if (meatworldComm) 1 else 0`
to reduce the number of initiative dice. `Game.runCombatTurn()` executes all icons (deckers and IC)
in a single pass sorted purely by initiative score. There is no concept of "physical-action slot" vs
"Matrix-action slot"; the timing displacement is never enforced.

**PRD verdict:** PRD CC-06 supports the design. Code partially implements the rule (dice penalty only).

---

### CD-2 — `Accessory.kt` in wrong package
**Severity:** Low (structural difference; no runtime effect)

**Design says:**
`cyberdeck_and_program_mechanics.md` specifies file path:
`src/main/kotlin/com/shadowrun/matrix/decker/Accessory.kt`

**Code does:**
The file lives at `src/main/kotlin/com/shadowrun/matrix/accessories/Accessory.kt`
(package `com.shadowrun.matrix.accessories`).

**PRD verdict:** PRD ACC-01–ACC-03 do not specify file locations. Neither supports nor contradicts.

---

### CD-3 — CT-03 cyberterminal detection uses `isCyberterminal` not `immuneToDumpShock`
**Severity:** Low (practical effect identical; flag choice differs from spec)

**Design says:**
`cyberdeck_and_program_mechanics.md` CT-03 helper comment: *"When the active decker is using a
Cyberterminal (`cyberdeck.immuneToDumpShock == true` is the distinguishing flag), each utility's
`currentRating` used in TN reduction is treated as `max(0, currentRating - 1)`."*

**Code does:**
`SystemTestResolver.kt`:
```kotlin
internal fun effectiveRating(utility: Utility, deck: Cyberdeck): Int =
    if (deck.isCyberterminal) maxOf(0, utility.currentRating - 1)
    else utility.currentRating
```
`Cyberdeck.kt` defines both `isCyberterminal: Boolean` and `immuneToDumpShock: Boolean` as
separate fields. The `Cyberterminal` factory function sets both to `true`, so behavior is
identical in practice.

**PRD verdict:** PRD CT-03 references "cyberterminal users" without specifying a flag name.
Neither supports nor contradicts. Practical impact: none while the factory is always used.

---

### CD-4 — Design typo `invokeMediac`; code correctly uses `invokeMedic`
**Severity:** Low (design document typo; code is correct)

**Design says:**
`cyberdeck_and_program_mechanics.md` method signature section: `fun invokeMediac(diceRoller: DiceRoller): MedicResult`

**Code does:**
`DeckerOperationsExtensions.kt` declares `fun Decker.invokeMedic(diceRoller: DiceRoller): MedicResult`
(correct spelling).

**PRD verdict:** PRD CD-20 names the utility "Medic" throughout. PRD supports the code spelling.
The design document has a typo.

---

### MC-2 — Grid overloads for operations undocumented in `operations.md`
**Severity:** Low (code adds useful behavior not covered by design spec)

**Design says:**
`operations.md` documents each system operation in host context only. Grid-context variants of
`locateAccessNode`, `analyzeSecurity`, `analyzeIc`, and `locateIc` are not specified.

**Code does:**
`DeckerOperationsExtensions.kt` contains additional overloads accepting `Grid` (instead of `Host`)
for `locateAccessNode(grid, ...)`, `analyzeSecurity(grid, ...)`, `analyzeIc(ic, grid, ...)`, and
`locateIc(grid, ...)`. These are called when the decker is on a grid node and the corresponding
`AvailableAction.Operation` targets the grid.

`game.md` (Available Actions — Location-Context Filtering) does list `LOCATE_ACCESS_NODE` and
`ANALYZE_SECURITY` as valid grid-context operations, confirming the intent. The omission is in
`operations.md` only.

**PRD verdict:** PRD references grid-context availability implicitly via M-07. Neither supports nor
contradicts the extra overloads.

---

### OP-2 — `SWAP_MEMORY` absent from `SystemOperation` enum
**Severity:** Low (deferred feature missing its placeholder enum entry)

**Design says:**
`protocol.md` lists `SWAP_MEMORY` as a deferred operation: *"SWAP_MEMORY — Deferred — memory
management refactor pending"*. `prd_game.md` confirms the deferral. The implication is that the
operation exists as an enum value but is excluded from `availableActions()`.

**Code does:**
`SystemOperation.kt` — `SWAP_MEMORY` has no entry. `LOCATE_DECKER` (also deferred) is present in
the enum but excluded from `availableActions()`, which is the correct pattern. `SWAP_MEMORY` should
follow the same pattern but was omitted entirely.

**PRD verdict:** PRD defers the feature. Neither supports nor contradicts its absence from the enum,
but the `LOCATE_DECKER` precedent suggests it should exist.

---

### UI-1 — `protocol.md` `IcProgram` DTO omits `analyzed` and `guardedNodeType` fields
**Severity:** Low (internal design inconsistency; code and `design_ui.md` agree)

**Design says:**
`protocol.md` `MatrixObjectDto` table: `| IcProgram | name, rating, behavior |`
No mention of `analyzed` or `guardedNodeType`.

**Code does:**
`MatrixObjectDto.IcProgram` carries five fields: `name`, `analyzed: Boolean`, `rating: Int?`,
`behavior: String?`, `guardedNodeType: String?`. When `analyzed == false`, `rating`, `behavior`,
and `guardedNodeType` are serialised as `null`.

`design_ui.md` explicitly specifies this behavior: *"IcProgram card fields: name, `analyzed` status
badge … only when `analyzed === true` — rating, behavior, guardedNodeType."*

**PRD verdict:** No PRD requirement addresses DTO field-level detail. `design_ui.md` and the code
are internally consistent. `protocol.md` is incomplete and should be updated to match.

---

*End of discrepancies audit.*
