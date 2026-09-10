# 14 Size Actions in Box

## Issue

In the bottom panel we have 4 boxes that contain actions in a 2 column layout. Currently, these actions get a vertical scroll bar for a medium sized resolution of the screen. Change the layout of these boxes such that the number of columns (i.e. number of actions per line) is adapted to the screen size, but a vertical scroll bar is avoided.

## Solution

### Chosen approach

Replaced the fixed `grid-template-columns: 1fr 1fr` and `max-height` / `overflow-y: auto` in `.action-group-body` with a responsive `repeat(auto-fill, minmax(120px, 1fr))` grid and no height cap. This lets each group fill as many columns as fit at 120 px minimum, eliminating the vertical scrollbar at all resolutions.

To further reduce card height in the Locate group (which previously triggered the scroll), the "SEARCH TERM" label and "Vagueness is derived from the query shape" hint were removed from Locate action cards, and the placeholder was simplified to "Search term…". A `.query-input` CSS rule was added to ensure the input shrinks properly (`width: 100%; min-width: 0`).

Key files changed:
- `frontend/src/App.css` — `.action-group-body` layout, new `.query-input` rule
- `frontend/src/components/ActionsPanel.tsx` — removed label and hint from query-input cards
- `design/prd_ui.md` — updated column-layout description

### Options considered but not taken

- **Keep fixed 2-column grid but increase `max-height`** — rejected because it only shifts the threshold at which scrollbars appear rather than eliminating them; the problem recurs at other resolutions.
- **Switch to a single-column layout** — rejected because it wastes horizontal space on wider screens and makes the panel taller than necessary.
