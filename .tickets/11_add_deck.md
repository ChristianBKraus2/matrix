# 11 Add Deck Stats

## Issue

Display the hardening, response and IO speed of the cyberdeck. The first two in one line below the MCP rating. The IO-Speed in the same line as the memory.

## Solution

### Chosen approach

Added three new fields (`hardening`, `responseIncrease`, `ioSpeedMpPerTurn`) to `DeckerStateDto` on the server, mapped from the existing `Cyberdeck` domain object. Updated the matching TypeScript interface in `messages.ts`.

In `DeckerPanel.tsx`:
- Hacking Pool, Hardening, and Response are rendered as flex-wrap chips on a single container row (`display: flex; flex-wrap: wrap`). Each chip is `white-space: nowrap` so the label and value never split — when the panel is too narrow the chips wrap as complete units. At full width all three sit on one line.
- IO Speed is appended to the PROGRAMS memory line: `X/Y Mp | IO: Z Mp/t`.

Key files changed:
- `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt`
- `frontend/src/types/messages.ts`
- `frontend/src/components/DeckerPanel.tsx`
- `design/prd_ui.md`
- Two test files updated to supply the new required DTO constructor arguments.

### Options considered but not taken

- **Separate stat-rows for each value** — three independent `stat-row` divs (HACKING POOL / HARDENING / RESPONSE). Rejected because it uses three lines even when there is ample horizontal space, wasting panel height.
- **Single concatenated stat-row value** (`4d | HARDENING: 4 | RESPONSE: 2` in one `<span>`) — rejected because the label (`HACKING POOL`) wraps independently from the value string at narrow widths, producing a misaligned two-line label against a one-line value.
