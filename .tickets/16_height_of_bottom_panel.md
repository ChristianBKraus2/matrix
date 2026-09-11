# 16 Height of bottom panel

## Issue

In the bottom panel you display several rows of actions. These actions should either have a height of 1 (like Graceful logoff) or a height of 2 (like Locate File) that additionally have a search field or a dropdown list box.

The exception of this 1-2 rows rule are:
- Access actions: they should be redesigned as described below.
- Edit File: which remains an exception

### Access Action

The access action box should be changed as follows:
- Remove label Target
- Align the color schema of the drop down listbox to the search term edit field of Locate Actions
- Move the Confirm button to the right end of the first row and change the label to "OK". Also align the color schema.

### Fix the height of the bottom panel

Fix the height of the bottom panel such that 4 rows of height 1 or 2 rows of height 2 can be displayed without horizontal scroll bar.

## Solution

### Chosen approach

Restructured the `AccessLtg`/`AccessHost` action card in `frontend/src/components/ActionsPanel.tsx`:
- Removed the `TARGET` ctrl-label
- Moved the OK button (renamed from CONFIRM) into the card's first row alongside the action name
- Moved the dropdown (`<select>`) to the second row inside `action-control`, styled consistently with `query-input` (green on black, same font/size)

Added new CSS in `frontend/src/App.css`:
- `.target-select`: full-width, styled identically to `.query-input`
- `.confirm-btn`: green-dim border, `--green-faint` background, hover to full green — consistent with the action card theme
- Added `overflow: hidden` to `.action-group` so cards never bleed past the group border
- Changed the actions grid row from `auto` to `235px` (fixed height sufficient for 4 rows of height-1 cards or 2 rows of height-2 cards without a horizontal scrollbar)

Updated `design/prd_ui.md` to document the new two-row AccessLtg/AccessHost card layout and the fixed panel height requirement.

### Options considered but not taken

- **Inline single-row (select + OK in header)**: Putting the dropdown and OK button on the same row as the action name was tried first but made the header crowded and inconsistent with the two-row pattern of Locate actions; rejected in favour of the two-row layout.
- **Keep `auto` grid row height**: The original `auto` bottom row lets the panel grow with content, squeezing the middle panel on screens with many cards; rejected in favour of a fixed pixel height that caps the panel and ensures a predictable layout.
