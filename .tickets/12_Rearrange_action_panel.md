# 12 Rearrange Action Panel

## Issue

The action panel displayed all available actions as a single flat scrollable list, making it hard to scan and find the right action quickly. The request was to reorganize the panel into four labeled groups, reduce visual noise on action cost badges, and context-filter the "Others" group based on the selected entity.

## Solution

### Chosen approach

Grouped the action panel into four equally-sized boxes arranged in a single horizontal row: **Navigation**, **Locate**, **Host**, and **Others**. Within each box, cards are displayed in a 2-column grid that scrolls vertically on overflow.

**Action cost badge** simplified: FREE → `F`, SIMPLE → `S`, COMPLEX → no badge (removing visual clutter for the most common action type).

**Others group** is context-filtered based on the focused entity in the Entities panel:
- `IcProgram` focused → IC + Icon + Misc actions
- `File` focused → File + Misc actions
- `Device` focused → Slave + Misc actions
- Nothing / `HostSubsystem` → Misc only

To share the focused entity, `selectedEntity` state was lifted into `App.tsx` and passed down to both `EntitiesPanel` (via `onEntitySelect` callback) and `ActionsPanel` (as `selectedEntity` prop). `EntitiesPanel` reports the currently focused entity via a `useEffect` on `focused?.index`.

**Key files changed:**
- `frontend/src/components/ActionsPanel.tsx` — grouping logic, 4-box layout, compact badges
- `frontend/src/components/EntitiesPanel.tsx` — `onEntitySelect` prop + `useEffect` to report focused entity
- `frontend/src/App.tsx` — lifted `selectedEntity` state (placed unconditionally before early returns to satisfy Rules of Hooks)
- `frontend/src/App.css` — 4-box flex layout, `.action-group` / `.action-group-header` / `.action-group-body` with 2-column grid and `max-height` scroll

### Options considered but not taken

- **2×2 grid layout** — rejected because the user explicitly requested all four boxes in a single horizontal row for better side-by-side comparison of available actions.
- **Server-side grouping** — rejected because grouping is a pure display concern and adding it server-side would unnecessarily couple the engine to UI layout decisions.
