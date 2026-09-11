# 18 Analyze Subsystem

## Issue

There are currently multiple analyze subsystem action boxes - one per existing subsystem.
I want one action box with a dropdown listbox having one entry per existing subsystem. Use the same color schema and layout as LogonHost.

## Clarification

After initial implementation the user revised the approach: instead of a dropdown, the `ANALYZE_SUBSYSTEM` card should appear contextually in the **Others** group when a `HostSubsystem` entity is selected in the right panel — the selected entity already encodes the target, so no dropdown is needed.

## Solution

### Chosen approach

`ANALYZE_SUBSYSTEM` is moved from the **Host** group to the entity-context-filtered **Others** group. The backend continues to emit one `AvailableAction.Operation(ANALYZE_SUBSYSTEM, HostSubsystem(node))` per subsystem node (unchanged). In the frontend, `ANALYZE_SUBSYSTEM` is added to a new `SUBSYSTEM_OPS` set and classified as sub-category `'subsystem'` within Others. `filterOthers` shows the single matching card only when a `HostSubsystem` entity whose `subsystemType` equals the card's `targetName` is focused in the Entities panel.

Key files changed:
- `frontend/src/components/ActionsPanel.tsx` — new `SUBSYSTEM_OPS` set, `'subsystem'` sub-category, updated `filterOthers`
- `design/prd_ui.md` — updated Host group table and Others entity-context table

### Options considered but not taken

- **Single dropdown card (first approach)** — implemented a new `AvailableAction.AnalyzeSubsystem` sealed class with a dropdown (matching `AccessLtg`/`AccessHost`). Rejected because the user pointed out that clicking the subsystem entity already selects the target, making the dropdown redundant.
