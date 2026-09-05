# Todo — Things to Address Going Forward

Consolidated from `deferred.md`, `code_review/things_to_note.md`, `.old_analysis/code_review_6/pass_6_code_review.md`, `.old_analysis/cross_check_1/discrepancies_old4.md`, and `design/design_core/missing.md`. Items are grouped by what is needed to act on them.

---

## 1. Actionable Code Fixes (no design work needed)

All items in this section have been resolved. No pending fixes remain.

---

## 2. Missing Tests

Regression coverage that should exist but does not.

| ID | Location | What to test |
|----|----------|-------------|
| MC1-D8TB-3 | `GameTest` | `runCombatTurn` test reimplements the loop inline with anonymous `ActiveIcon`s (cannot be passed to `GameContext`). Fix by either accepting the test as a loop-spec or rewriting with real deckers at specific initiative scores. Blocked until game loop is wired (see §4 below). |

---

## 3. Design Decisions Needed Before Implementation

These items are blocked on spec/design work that does not yet exist.

### 3a. Grid `security_sheaf` (D7C-3 / deferred.md #14)

Out of scope — see [out_of_scope.md](out_of_scope.md) §1.

---

## 4. Deferred Features (intentionally out of scope for the current milestone)

All items from `deferred.md` are reproduced here for completeness. The authoritative spec entries and context remain in [deferred.md](deferred.md).

| # | Feature | Blocker |
|---|---------|---------|
| 1 | **Game loop + Decker action callback.** `Game.runCombatTurn`/`runOutOfCombatTurn` are unreachable in production. WebSocket controller bypasses the loop. Two dormant correctness bugs live inside it: **D4G-3** (IC move never persists — `moveIfNeeded()` returns `IcMoved` but neither the IC nor `GameContext` is mutated; [Game.kt:43](../src/main/kotlin/com/shadowrun/matrix/game/Game.kt#L43) discards the `ActionResult`) and **D4G-4** (crashed IC can re-act — initiative list is built once; a removed IC keeps residual initiative). See GL-3 in `cross_check_1/discrepancies_old4.md` for exact fix recipe. | Loop callback design not yet specified. |
| 2 | **`SWAP_MEMORY` operation.** Excluded from `availableActions` until a memory-management refactor is complete. | Memory-management design. |
| 3 | **`LOCATE_DECKER` operation.** Excluded from `availableActions`. Requires a passcode-ledger design that does not yet exist in any PRD. | Passcode-ledger design. |
| 4 | **`locationIndex` real lookup.** `DeckerStateDto.locationIndex` is always 0 while jacked in. `LocationPanel` renders `visibleObjects[0]` as the decker's location. Correct only if the current location is always element 0 — an ordering contract not stated in the protocol. | Stable `visibleObjects` ordering contract must be defined before a real index can be computed server-side. |
| 5 | **Utility upgrade/modification operations.** `source_code: true` field is parsed and stored but upgrade/modification operations are out of scope. | Milestone scope. |
| 6 | **Offline-storage download routing.** `DownloadHandle.destination` defaults to `StorageMemory`. Routing completed downloads to `OfflineStorage` is not wired up. | Not yet wired. |
| 7 | **`ANALYZE_ICON` for `File` and `Device` targets.** Currently only handles `IcProgram`. `FileIcon` and `DeviceIcon` variants in `Icon` sealed class and updated dispatch in `analyzeIcon()` are needed. Action cards are suppressed to avoid silent failures. | Domain model extension needed. |
| 8 | **Companion plug-pull with Black IC active (ICC-10).** Entirely undesigned: whether Willpower test is skipped, who triggers the final attack, what calls `resolveJackOutWithPin`. Belongs in `combat.md` (Black IC Pin) and `movement.md` (`jackOut`). | Design needed. |
| 9 | **Evade Detection — IC re-detection countdown (rules p. 224–225).** `combat.md` returns `ManeuverResult.Success(netSuccesses)` but does not design the re-detection countdown or the tally-shortening rule. SR3 p. 224: IC re-detect the evading icon after a number of Combat Turns equal to the evasion net successes; each security tally point added during the evasion period shortens this window by 1 turn. Neither the countdown state nor the tally-shortening is modelled anywhere. Belongs in `combat.md` (Combat Maneuvers section). | Design needed. |
| 11 | **Security decker spawning in `GameContext` (GC-2).** `security_decker_count` field is parsed by `HostLoader` into `TriggerStep.securityDeckerCount` but nothing consumes it — no spawn path exists. NPC AI design (action selection, target priority, logon sequencing) unspecified. | `npc_ai.md` design document needed. |
| 12 | **`detectedIcons` persistence wiring (MP-01–MP-10).** `Decker.detectedIcons` declared and cleared on logoff but never populated. `noticeIcon()`/`noticeTriggeredIc()` results are discarded at call sites. `visibleObjects()` shows all IC unconditionally. | Depends on game loop wiring (item 1). |
| 13 | **Scramble IC reactive trigger.** `Scramble.action()` is a no-op; no game-engine hook triggers it on file destruction. | Game-engine hook needed in `destructFile`/`decryptFile`. |
| 14 | **Grid-level `security_sheaf` loading (D7C-3).** Out of scope — see [out_of_scope.md](out_of_scope.md) §1. | — |
| 15 | **WebSocket transport authentication (S-5).** `/decker/ws` enforces same-origin allow-list but no client authentication. Assumes trusted-LAN/localhost deployment. | Deployment threat model and token scheme decision needed. |

---

## 5. Cleanup / Documentation

Low-priority items that don't change behavior.

- **CR6-MIN-01** — `locateAccessNode` NotFound branch: subsystem-type names always exist on a valid host (`Host.init` enforces all 5 types), so a standard query against a type name can never return `NotFound`. Add a comment explaining this invariant so future readers do not expect `NotFound` for standard subsystem queries.
- **CR6-NIT-01** — `ControlSlave` creates a full `Decker` copy just to pass `effectiveSkill` override to `SystemTestResolver`. Could instead add an optional skill-override parameter to `resolve()`. Not a blocking concern.
- **TTN-SCANNER** — `Host.datalineScannerRatings` defaults to empty. Until a scenario/loader populates it, every `TAP_COMCALL` faces no scanner. The mechanic is correctly implemented as an opposed test (SR3 p. 219) but inert in a real game until a loader populates scanner ratings.
