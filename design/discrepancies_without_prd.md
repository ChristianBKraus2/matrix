# Discrepancies Without a PRD Verdict

These five items were identified during the design-vs-implementation audit but could not be resolved
by the PRD (either the PRD is silent, or it neither supports nor contradicts either side).
They are listed here for future decision by the team.

---

## CD-2 — `Accessory.kt` is in the wrong package

**Design says:** `cyberdeck_and_program_mechanics.md` specifies the file should live at
`src/main/kotlin/com/shadowrun/matrix/decker/Accessory.kt` (decker package).

**Code does:** The file is at `src/main/kotlin/com/shadowrun/matrix/accessories/Accessory.kt`
(package `com.shadowrun.matrix.accessories`).

**PRD verdict:** PRD ACC-01–ACC-03 do not specify file or package locations.

**Decision needed:** Should `Accessory.kt` stay in `accessories` (which is arguably cleaner) or move
to `decker` to match the design doc? No runtime impact either way.

---

## CD-3 — Cyberterminal detection flag name mismatch

**Design says:** `cyberdeck_and_program_mechanics.md` CT-03 helper comment references the flag
`cyberdeck.immuneToDumpShock` as the distinguishing field for cyberterminal detection.

**Code does:** `SystemTestResolver.effectiveRating()` tests `deck.isCyberterminal` instead.
`Cyberdeck` defines both `isCyberterminal: Boolean` and `immuneToDumpShock: Boolean`; the
`Cyberterminal` factory sets both to `true`, so behaviour is identical in practice.

**PRD verdict:** PRD CT-03 references "cyberterminal users" without naming a flag.

**Decision needed:** Unify the two fields into one (keep `isCyberterminal`, remove
`immuneToDumpShock`, and update the design doc) — or vice versa. Also update the design comment to
name whichever field is canonical.

---

## MC-2 — Grid-context operation overloads undocumented in `operations.md`

**Design says:** `operations.md` documents all system operations in host context only; no
grid-context variants are specified for `locateAccessNode`, `analyzeSecurity`, `analyzeIc`, or
`locateIc`.

**Code does:** `DeckerOperationsExtensions.kt` contains `Grid`-accepting overloads for all four
operations. `game.md` (Available Actions — Location-Context Filtering) implicitly validates the
intent by listing `LOCATE_ACCESS_NODE` and `ANALYZE_SECURITY` as valid grid-context operations.

**PRD verdict:** PRD references grid-context availability only implicitly (M-07). The omission is in
`operations.md` only.

**Decision needed:** Update `operations.md` to document the grid-context behaviour of these four
operations (straightforward documentation task, no code change needed).

---

## OP-2 — `SWAP_MEMORY` absent from the `SystemOperation` enum

**Design says:** `protocol.md` lists `SWAP_MEMORY` as a deferred operation. The existing
`LOCATE_DECKER` (also deferred) is present in the enum but excluded from `availableActions()` — the
correct deferral pattern.

**Code does:** `SystemOperation.kt` has no `SWAP_MEMORY` entry at all, diverging from the pattern
established by `LOCATE_DECKER`.

**PRD verdict:** PRD defers the feature. Neither supports nor contradicts the absence from the enum.

**Decision needed:** Add `SWAP_MEMORY` to `SystemOperation` (as a placeholder, excluded from
`availableActions`) to match the `LOCATE_DECKER` precedent, or leave it absent until implementation.

---

## UI-1 — `protocol.md` `IcProgram` DTO is incomplete vs `design_ui.md`

**Design says:** `protocol.md` `MatrixObjectDto` table lists the `IcProgram` kind as carrying fields
`name`, `rating`, `behavior` only — no mention of `analyzed` or `guardedNodeType`.

**Code does:** `MatrixObjectDto.IcProgram` carries five fields: `name`, `analyzed: Boolean`,
`rating: Int?`, `behavior: String?`, `guardedNodeType: String?`. When `analyzed == false`, the last
three are serialised as `null`. This matches `design_ui.md` exactly.

**PRD verdict:** No PRD requirement addresses DTO field-level detail. `design_ui.md` and the code
are internally consistent.

**Decision needed:** Update `protocol.md` to document `analyzed`, `rating` (nullable), `behavior`
(nullable), and `guardedNodeType` (nullable) for the `IcProgram` kind. No code change needed.

---

*End of unresolved discrepancies.*
