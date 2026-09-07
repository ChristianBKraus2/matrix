## Issue

Three cleanup items for the Location (top) panel:

1. The `PARENT RTG` field shown when on an LTG is unnecessary clutter.
2. The security tally should only be visible after the decker has successfully run **Analyze Security** on that system (per PRD: the operation is what reveals the tally).
3. The `HOSTS` and `PLTGs` counts on an LTG are not useful to display.

## Solution

### Chosen approach

**Backend — `securityTally` gating:**
- Added `analyzeSecuritySystems: Set<String>` to `Decker` to track which systems the decker has successfully analyzed security on.
- Both `analyzeSecurity(host)` and `analyzeSecurity(grid)` now add the system name to this set when `outcome.deckerWins`.
- The set is cleared on graceful logoff (both success and forced-jackout paths), mirroring the `analyzedIcNames` pattern.
- `MatrixObjectDto.toDto()` now accepts `analyzeSecuritySystems`; `securityTally` is serialized as `null` when the system name is not in the set (type changed from `Int` to `Int?` on `GridNode`, `LocalGrid`, `HostNode`).
- `WebSocketDeckerController` passes `decker.analyzeSecuritySystems` to both `toDto()` calls.

**Frontend:**
- `messages.ts`: `securityTally` typed as `number | null` on the three affected union arms.
- `LocationPanel.tsx`:
  - `LocalGrid` case: removed `PARENT RTG`, `HOSTS`, `PLTGs` fields.
  - All three location types (`GridNode`, `LocalGrid`, `HostNode`): `TALLY` field rendered only when `securityTally !== null`.

### Options considered but not taken

**Hide tally in frontend only (no backend change):** The tally value would still be transmitted to all clients regardless of whether they've analyzed security. Rejected because it would expose game state to observers who shouldn't have it, and it is inconsistent with the existing `analyzed` gate on IC programs.
