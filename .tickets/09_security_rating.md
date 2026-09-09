# 9 Security Rating Visibility

## Issue

Per SR3 rules (p. 545–546), the full Security Rating — both the **security code** (color) and the **security value** (number) — is unknown to the decker by default. Both are revealed only by a successful **Analyze Security** or **Analyze Host** operation.

The original implementation deviated in two ways:

1. **Security code always visible.** `LocationPanel.tsx` rendered `securityCode` unconditionally. The color should be hidden until analyzed.

2. **Security value never tracked for reveal.** The `analyzeSecuritySystems` set on `Decker` gated only the `securityTally` (per-run accumulating counter), not the `securityValue` (the host's static dice pool number). The security value had no reveal gate at all.

These two concepts must not be confused:

| Term | Meaning | Should be hidden? |
|---|---|---|
| Security Code | Color (Blue/Green/Orange/Red) | Yes — until Analyze Security/Host |
| Security Value | Static number — host's Security Test dice pool | Yes — until Analyze Security/Host |
| Security Tally | Accumulating intrusion counter for this run | Shown once in result message only |

## Solution

### Chosen approach

Reused the existing `analyzeSecuritySystems: Set<String>` field on `Decker` to gate all three disclosures (code, value, tally). No new Decker field was introduced, because all three are revealed by the same triggering operations.

**Key files changed:**

- `server/dto/MatrixObjectDto.kt` — Made `securityCode: String?` and added `securityValue: Int?` (nullable) to `GridNode`, `LocalGrid`, `PrivateGrid`, `HostNode`. Both are `null` unless the system name is in `analyzeSecuritySystems`. Removed `securityTally` from all DTOs entirely.

- `decker/DeckerOperationsExtensions.kt` — Two changes:
  1. `analyzeHost()`: when `revealedSecurityRating != null`, adds the host name to `analyzeSecuritySystems` on the returned decker (previously it never did this).
  2. `analyzeSecurity(grid)`: when the decker is on an **LTG**, also adds the parent RTG's name to `analyzeSecuritySystems`, because the LTG's security code is the parent RTG's code and must be visible in the GridNode DTO.

- `frontend/src/types/messages.ts` — Updated `MatrixObjectDto` union types to reflect nullable `securityCode`, new `securityValue`, and removed `securityTally`.

- `frontend/src/components/LocationPanel.tsx` — Renders `???` (dimmed) when `securityCode` is null; shows `SEC VALUE` row when non-null; removed `SEC TALLY` row entirely (tally is only in the result message).

- `design/prd_core.md`, `design/prd_ui.md` — Updated Analyze Security and Analyze Host descriptions; updated location-panel field tables.

### Options considered but not taken

- **Add a separate `analyzedRatingSystems: Set<String>`** — rejected because both the tally reveal and the code/value reveal are triggered by the same operations (Analyze Security, Analyze Host). One set serves all three gates without ambiguity.
- **Keep `securityTally` in the DTO and persist it in the location panel** — rejected based on manual testing feedback: the tally is a momentary reading delivered in the result message, not a persistent display. Persisting it would misrepresent the rules (SR3 p. 545).
