# 17 Decrypt Access

## Issue

The rules say:

Found it. The definition is at lines 1551–1559.

Decrypt Access is a system operation with these mechanics:

Test: Access
Utility: Decrypt
Action: Simple
What it does: It defeats scramble IC programs guarding access to a host. Specifically, if a System Access Node (SAN) has been scrambled with scramble IC, a decker must perform a successful Decrypt Access operation before they can attempt a Logon to Host on that SAN. It is essentially the prerequisite step to break through a scrambled entry point into a host.

This implies that Logon To Host should only be displayed when the host is either not encrypted or the decryption has been defeeted.

Furthermore, the Decrypt Access should only be displayed when the Host is actaully decrypted (and not yet defeated yet).

This is valid per host. The name / address of an host should either appear in the list of the access or decrypt action. If nothing is in the list the action should not be displayed at all.

## Solution

### Chosen approach

Added `decryptedSans: Set<String>` to `Decker` (alongside existing run-scoped sets `knownAddresses`, `locatedFiles`, `locatedSlaves`) to track which scramble-protected SANs have been successfully defeated.

Added `AvailableAction.DecryptAccess(targets: List<Host>)` as a new sealed-class variant parallel to `AccessHost`. A private helper `addHostNavigationActions()` in `Decker.availableActions()` splits all known hosts into two buckets:
- **accessible** (no scramble protection, or already in `decryptedSans`) → `AccessHost`
- **needs-decrypt** (scramble-protected and not yet in `decryptedSans`) → `DecryptAccess`

Only non-empty lists produce an action card; both lists are never both empty for the same host.

On a successful `decryptAccess(host, ...)` call the host name is added to `decryptedSans` in the returned `Decker`. The old `DECRYPT_ACCESS` system-operation path (which was ungated and dispatched through grid/host operation handlers) was removed entirely from `WebSocketDeckerController`, leaving only the new targeted action dispatch.

Key files changed:
- [AvailableAction.kt](src/main/kotlin/com/shadowrun/matrix/operations/AvailableAction.kt) — added `DecryptAccess` variant
- [Decker.kt](src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt) — added `decryptedSans` field; `addHostNavigationActions()` helper; replaced inline `AccessHost` additions with helper call
- [DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt) — `decryptAccess(host)` records success in `decryptedSans`
- [AvailableActionDto.kt](src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt) — added `DecryptAccess` DTO and mapping
- [WebSocketDeckerController.kt](src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt) — added `DecryptAccess` dispatch; removed old `DECRYPT_ACCESS` operation paths
- [messages.ts](frontend/src/types/messages.ts) — added `DecryptAccess` to the TypeScript discriminated union
- [ActionsPanel.tsx](frontend/src/components/ActionsPanel.tsx) — `NAVIGATE_KINDS` and `SELECTION_KINDS` include `DecryptAccess`; label and dropdown handling

### Options considered but not taken

- **Keep `DECRYPT_ACCESS` as a system operation with manual host selection** — rejected because system operations have no per-host targeting mechanism and would require a separate UI flow inconsistent with how `AccessHost` works. The new action type naturally reuses the existing dropdown-card pattern.
- **Gate `AccessHost` by checking scramble state at dispatch time** — rejected because gating should happen at action-availability time, not dispatch time; the UI must show the correct card from the start so the player knows what action to take.
- **Store `decryptedSans` as a `Set<Host>` reference** — rejected because `Host` objects can be recreated across runs; using the host name (a stable string key) is consistent with how `knownAddresses` works.
