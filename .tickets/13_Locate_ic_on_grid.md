# 13 Locate IC on Grid

## Issue

There is no IC on grid, so no Locate IC operation. Also double check against extracted_text.txt.

## Solution

### Chosen approach

Removed `LOCATE_IC` from `addGridSystemActions()` in [Decker.kt](src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt). IC programs are host-resident objects (confirmed by the rules text in `extraction/extracted_text.txt`), so `LOCATE_IC` makes no sense on RTG, LTG, or PLTG contexts — there is nothing to locate there.

Updated `design/prd_core.md` rule M-08a to explicitly cover both `ANALYZE_IC` and `LOCATE_IC` as host-only operations.

### Options considered but not taken

- **Keep LOCATE_IC on grids but return empty results** — rejected because surfacing an action that can never succeed is confusing to the player and not how the rules work.
