# Deferred Implementation

Features and behaviors that are explicitly out of scope for the current milestone, stubbed, or otherwise intentionally not yet implemented.

---

## 1. Decker action callback to user in the `Game` loop

**Source:** [prd_game.md](prd_game.md) · [design_game/game.md](design_game/game.md)

`Decker.action()` returns `DeckerAction`, which is a placeholder. In the game loop it returns immediately without side effects. The intent is a future callback to the user when it is the decker's turn; the exact mechanism is not yet designed. The WebSocket controller currently bypasses the `Game` loop for decker turns.

Because `Game.runCombatTurn`/`runOutOfCombatTurn` are unreachable in production, two correctness defects sit dormant inside the loop and are deferred **with** it (they can only be fixed and tested once the loop is wired):
- **D4G-3 — IC move never persists.** [IC.kt](../src/main/kotlin/com/shadowrun/matrix/ic/IC.kt) `moveIfNeeded()` returns `ActionResult.IcMoved(...)` without mutating `guardedNode` or calling `removeIc`/`addIc`, and [Game.kt:43](../src/main/kotlin/com/shadowrun/matrix/game/Game.kt#L43) discards the `ActionResult` from `state.icon.action(...)`. An anchored proactive IC announces a move every turn forever and never reaches its target. design_game/game.md L221 specifies the caller must replace the IC in `context.activeIc` at the new node; no path does.
- **D4G-4 — crashed IC can re-act the same turn.** [Game.kt:40-49](../src/main/kotlin/com/shadowrun/matrix/game/Game.kt#L40-L49) builds the initiative list once and gates re-selection only on `currentInitiative > 0`. An IC that calls `context.removeIc(this)` mid-`action()` keeps its `ActiveIconState` with residual initiative and can be selected again after removal.

Fix when wiring the loop: capture `action()`'s result and, on `IcMoved`, replace the IC at the new node (or mutate via `GameContext`); and skip `states` whose icon is no longer in `context.activeIc`.

---

## 2. `SWAP_MEMORY` operation

**Source:** [prd_game.md](prd_game.md) · [protocol.md](protocol.md) · [design_game/game.md](design_game/game.md)

`SWAP_MEMORY` is excluded from `availableActions` and will not appear as a player option until a memory-management refactor is complete.

---

## 3. `LOCATE_DECKER` operation

**Source:** [prd_game.md](prd_game.md) · [protocol.md](protocol.md) · [design_game/game.md](design_game/game.md)

`LOCATE_DECKER` is excluded from `availableActions`. It requires a passcode-ledger design that does not yet exist in any PRD.

---

## 4. `locationIndex` proper lookup by object identity

**Source:** [protocol.md](protocol.md) · [design_ui/design_ui.md](design_ui/design_ui.md)

`DeckerStateDto.locationIndex` is always `0` when jacked in (`DeckerStateDto.kt:28` —
`locationIndex = if (currentLocation != null) 0 else null`). The correct value — the index of the
decker's current location in `visibleObjects`, resolved by object identity — is deferred. The
`LocationPanel` (`LocationPanel.tsx:79-85`) now **prefers this stub index** (`visibleObjects[0]`) and
keeps a name-prefix match only as an *unreachable fallback* (it is never taken while jacked in,
because `locationIndex` is non-null). Until the backend populates a real index, the panel renders
whatever object sits at index 0. (Verified S3, finding D6F-2 — this entry previously described the
name-match as the primary path, which is inverted from the current code.)

---

## 5. Utility upgrade and modification operations

**Source:** [prd_core.md](prd_core.md) (CD-03)

The `source_code: true` field on utility YAML entries is parsed and stored, but the upgrade and modification operations that would use it are out of scope for this milestone. Regular (non-source-code) copies may be run but not altered.

---

## 6. Offline-storage download routing (`DownloadHandle.destination`)

**Source:** [design_core/cyberdeck_and_program_mechanics.md](design_core/cyberdeck_and_program_mechanics.md) · [discrepancies_without_prd.md](discrepancies_without_prd.md) (NM-1)

`DownloadHandle` now includes a `destination: DownloadDestination` field defaulting to `StorageMemory`. The `DownloadDestination` sealed class is implemented; setting `destination` to `OfflineStorage` routes the download to external storage without consuming `storedUtilities` capacity on the cyberdeck. Routing of completed downloads to offline storage is not yet wired up.

---

## 7. `ANALYZE_ICON` for `File` and `Device` targets

**Source:** [discrepancies_without_prd.md](discrepancies_without_prd.md) (AI-1)

`analyzeIcon()` currently only handles `IcProgram`. The PRD intends it to work on any icon type. Full compliance requires `FileIcon` and `DeviceIcon` variants in the `Icon` sealed class and an updated dispatch in `analyzeIcon()`. Until then, those action cards are suppressed to avoid silent failures.

---

## 8. Companion plug-pull while Black IC is active (ICC-10)

**Source:** [design_core/missing.md](design_core/missing.md)

PRD ICC-10: if a companion at the jackpoint manually pulls the plug while Black IC is active, Black IC gets one automatic final attack. This scenario is entirely undesigned. Open questions: whether the decker's Willpower test is skipped, who triggers the final-attack resolution, and what calls `resolveJackOutWithPin` (or a variant). Belongs in `combat.md` (Black IC Pin section) and `movement.md` (`jackOut` section).

---

## 11. Security decker spawning in `GameContext` (GC-2)

**Source:** [design_game/game.md](design_game/game.md)

`GameContext` does not spawn or manage security deckers as NPC opponents. The PRD anticipates that a host's security response could include deploying a counter-intrusion decker, but the NPC AI design (action selection, target priority, logon sequencing) is not yet specified. Until that design exists, only IC programs act as automated defenders. Belongs in a future `npc_ai.md` design document. (Currency, S3: the `security_decker_count` field *is* now parsed by `HostLoader` into `TriggerStep.securityDeckerCount`, but nothing consumes it — no spawn path exists.)


## 12. detectedIcons persistence wiring (MP-01 – MP-10)

**Source:** [design_core/ord.md](design_core/ord.md) · PRD MP-01 through MP-10

`Decker.detectedIcons: Set<Icon>` is declared and cleared on logoff, but never populated in production code. `noticeIcon()` and `noticeTriggeredIc()` in `DeckerOperationsExtensions.kt` return detection results that no call site persists. `visibleObjects()` shows all IC unconditionally rather than filtering through `detectedIcons`. Full wiring requires: (a) IC `action()` methods calling `noticeIcon()` before targeting and updating the decker's `detectedIcons`; (b) `visibleObjects()` filtering IC through `detectedIcons`. Deferred until the IC action-callback design (entry 1) is settled.

## 13. Scramble IC reactive trigger

**Source:** [discrepancies_without_prd.md](discrepancies_without_prd.md) (SAN-1)

Scramble IC is designed as a reactive IC that triggers when a decker destructs a file. However, `Scramble.action()` is currently a no-op, and no interception point in the game engine triggers it on file destruction operations. Until a game-engine hook is implemented (e.g. in `destructFile` or `decryptFile`), Scramble fires no reactive attack.

## 14. Grid-level `security_sheaf` loading (D7C-3)

**Source:** [design_core/ord.md](design_core/ord.md) · [config/GridLoader.kt](../src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt)

`GridLoader` parses no `security_sheaf` for RTG/LTG/PLTG, so each grid's `securitySheaf` falls to the empty default and grid-level tally escalation can never fire from config. `HostLoader` has a `buildSecuritySheaf`/`buildTriggerStep` parser, but it is host-specific: `buildTriggerStep` resolves `activatedIc` against a host's subsystem nodes, which grids do not have. Deferred rather than fixed because the grid-security-sheaf model is undesigned:
- Which tally a grid trigger counts, and what action fires (grids have no subsystem nodes, so "spawn IC in node X" has no grid analogue) is not specified in `ord.md` separately from the host case.
- No entry in `grid.yaml` declares a grid `security_sheaf`, so there is no example to validate against and no test would exercise new loader code.

Before implementing: define the grid `security_sheaf` schema and semantics in `ord.md`, add at least one `grid.yaml` example, then adapt the parser (it cannot simply be lifted from `HostLoader`).
