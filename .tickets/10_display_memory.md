# 10 Display Memory on Programs Line

## Issue

I would like to see (in the same line as the label Programs) the amount of free and total memory as well as offline storage.

## Solution

### Chosen approach

Extended the `DeckerStateDto` (backend + frontend) with three new fields: `freeActiveMemoryMp`, `totalActiveMemoryMp`, and `offlineStorageCount`. These are populated in `Decker.toDto()` from `cyberdeck.freeActiveMemoryMp`, `cyberdeck.activeMemoryMp`, and `offlineStorageFiles.size`.

In `DeckerPanel.tsx` the `PROGRAMS` section title was converted to a flex row: the label stays on the left and a dimmed `X/Y Mp` (and `| offline: N` when non-zero) appears on the right. The `.section-title` CSS class was updated to `display: flex; justify-content: space-between` to support this layout; a new `.programs-memory` class provides the smaller, dimmer right-side text.

Key files changed:
- `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt`
- `frontend/src/types/messages.ts`
- `frontend/src/components/DeckerPanel.tsx`
- `frontend/src/App.css`
- `design/prd_ui.md`
- `src/test/kotlin/…/integration/WebSocketServerIntegrationTest.kt`
- `src/test/kotlin/…/server/SessionRegistryTest.kt`

### Options considered but not taken

- **Show storage memory (storedUtilities Mp / storageMemoryMp) instead of active memory** — rejected because "free and total memory" in the Shadowrun context refers to active memory (the slot where programs run), not the storage pool. Storage capacity is a deck spec, not a running state indicator.
- **Add a separate line beneath PROGRAMS** — rejected because the ticket explicitly asks for the info on the same line as the label.
