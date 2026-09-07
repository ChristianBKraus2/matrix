Not only display loaded programs, but also not loaded programs. 

Use Italics and a much darker, but still visible green.

-----

Additionally format the location in a line and not multiple rows.

## Solution

### Chosen approach

**Programs panel** — Added `storedUtilities: List<UtilityDto>` to `DeckerStateDto` (backend + frontend type). `DeckerPanel` now iterates `storedUtilities` instead of `activeUtilities`; programs not in `activeUtilities` get class `program-unloaded` (italic, `--green-dark: #005c16`). Section title changed from "LOADED PROGRAMS" to "PROGRAMS".

**Location line** — Replaced the stacked `loc-field-label` + `loc-name` divs in `LocationPanel` with a single `loc-header` flex row (`loc-prefix` span + `loc-name` span), rendering e.g. `RTG: Seattle` on one line. Root cause of all fields stacking: `.location-panel .panel-body` had `flex-direction: row; flex-wrap: wrap` in CSS but was missing `display: flex`, so the browser treated it as a block container. Added `display: flex` and removed the redundant inline style from `LocationPanel.tsx`.

### Options considered but not taken

**Two separate sections ("LOADED PROGRAMS" / "STORED PROGRAMS")** — Would have required more vertical space in the decker panel and extra section headers. A single "PROGRAMS" list with visual encoding (italic + dark green for unloaded) is more compact and communicates the same information at a glance. Rejected in favour of the single-list approach.

**Opacity instead of a separate colour for unloaded programs** — `opacity: 0.35` on the unloaded row would have achieved a similar dimming effect. Rejected because opacity also dims the text used for the pip rating, making it hard to count dots; a distinct colour variable (`--green-dark`) keeps contrast predictable and themeable.

