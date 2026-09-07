## Issue
For the decker the MCP rating is displayed, but not the attributes like sensor. Display them, too.

## Solution
Added the four persona firmware attributes (Bod, Evasion, Masking, Sensor) to the decker state pipeline and condensed their display into a single stat line.

- **`server/dto/DeckerStateDto.kt`**: Added non-nullable `bod`, `evasion`, `masking`, `sensor` fields. In `toDto()`, each reads from the live `persona` value when jacked in, with a fallback to `cyberdeck.personaPrograms` (firmware baseline) so the values are always defined.
- **`frontend/src/types/messages.ts`**: Added `bod: number`, `evasion: number`, `masking: number`, `sensor: number`.
- **`frontend/src/components/DeckerPanel.tsx`**: Replaced the separate MCP RATING row and PERSONA section with a single compact stat line — label `R:B/E/M/S`, value `mcpRating:bod/evasion/masking/sensor`.
- Updated two test stubs (`WebSocketServerIntegrationTest.kt`, `SessionRegistryTest.kt`) to pass `0` for the four new fields.
- Updated `design/prd_ui.md`, `design/protocol.md`, and `design/design_ui/design_ui.md` to document the new fields and compact display format.
