# Discrepancies Without a PRD Verdict

All five items identified during the design-vs-implementation audit have been resolved.

---

## CD-2 — `Accessory.kt` is in the wrong package ✓ resolved

**Decision:** File stays in `accessories/` package (cleaner separation).  
**Fix:** Updated `cyberdeck_and_program_mechanics.md` to reference `accessories/Accessory.kt`.

---

## CD-3 — Cyberterminal detection flag name mismatch ✓ resolved

**Decision:** Unified into one field: kept `isCyberterminal`, removed `immuneToDumpShock`.  
**Fix:** Removed `immuneToDumpShock` from `Cyberdeck`, updated `Cyberterminal` factory,
`CombatResolver`, `DeckerNavigationExtensions`, and all test files. Updated design doc to reference
`isCyberterminal` throughout.

---

## MC-2 — Grid-context operation overloads undocumented in `operations.md` ✓ resolved

**Decision:** Document the grid-context variants in `operations.md`.  
**Fix:** Added "Grid-Context Variants" section to `operations.md` documenting `analyzeSecurity`,
`analyzeIc`, `locateAccessNode`, and `locateIc` grid overloads.

---

## OP-2 — `SWAP_MEMORY` absent from the `SystemOperation` enum ✓ resolved

**Decision:** Add as a deferred placeholder following the `LOCATE_DECKER` precedent.  
**Fix:** Added `SWAP_MEMORY(null, null, SIMPLE, STANDARD)` to `SystemOperation.kt`, excluded from
`availableActions()`. Updated `protocol.md` deferred operations table and operation count in tests.

---

## UI-1 — `protocol.md` `IcProgram` DTO is incomplete vs `design_ui.md` ✓ resolved

**Decision:** Update `protocol.md` to match the code and `design_ui.md`.  
**Fix:** Updated `IcProgram` row in `protocol.md` `MatrixObjectDto` table to document `analyzed`,
`rating` (nullable), `behavior` (nullable), and `guardedNodeType` (nullable).

---

*No open discrepancies.*
