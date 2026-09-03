# Deferred Implementation

Features and behaviors that are explicitly out of scope for the current milestone, stubbed, or otherwise intentionally not yet implemented.

---

## 1. Decker action callback to user in the `Game` loop

**Source:** [prd_game.md](prd_game.md) · [design_game/game.md](design_game/game.md)

`Decker.action()` returns `DeckerAction`, which is a placeholder. In the game loop it returns immediately without side effects. The intent is a future callback to the user when it is the decker's turn; the exact mechanism is not yet designed. The WebSocket controller currently bypasses the `Game` loop for decker turns.

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

`DeckerStateDto.locationIndex` is always `0` when jacked in. The correct value — the index of the decker's current location in `visibleObjects`, resolved by object identity — is deferred. The `LocationPanel` in the UI currently relies on a fragile name-prefix match as a workaround.

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

`GameContext` does not spawn or manage security deckers as NPC opponents. The PRD anticipates that a host's security response could include deploying a counter-intrusion decker, but the NPC AI design (action selection, target priority, logon sequencing) is not yet specified. Until that design exists, only IC programs act as automated defenders. Belongs in a future `npc_ai.md` design document.


## 12. detectedIcons persistence wiring (MP-01 – MP-10)

**Source:** [design_core/ord.md](design_core/ord.md) · PRD MP-01 through MP-10

`Decker.detectedIcons: Set<Icon>` is declared and cleared on logoff, but never populated in production code. `noticeIcon()` and `noticeTriggeredIc()` in `DeckerOperationsExtensions.kt` return detection results that no call site persists. `visibleObjects()` shows all IC unconditionally rather than filtering through `detectedIcons`. Full wiring requires: (a) IC `action()` methods calling `noticeIcon()` before targeting and updating the decker's `detectedIcons`; (b) `visibleObjects()` filtering IC through `detectedIcons`. Deferred until the IC action-callback design (entry 1) is settled.

## 13. Scramble IC reactive trigger

**Source:** [discrepancies_without_prd.md](discrepancies_without_prd.md) (SAN-1)

Scramble IC is designed as a reactive IC that triggers when a decker destructs a file. However, `Scramble.action()` is currently a no-op, and no interception point in the game engine triggers it on file destruction operations. Until a game-engine hook is implemented (e.g. in `destructFile` or `decryptFile`), Scramble fires no reactive attack.
