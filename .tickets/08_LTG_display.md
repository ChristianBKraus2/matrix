# 8 LTG Display

## Issue

When the location is an LTG the same type of information should be displayed as for an RTG. As the LTG does not have some of this info, one has to retrieve the info from the parent RTG.

## Solution

### Chosen approach

Extended the `LocalGrid` case in `LocationFields` (`frontend/src/components/LocationPanel.tsx`) to look up the parent `GridNode` from `visibleObjects` using `obj.parentRtgName` and render its `region` and `securityCode` fields. The LTG's own `alertStatus`, `securityTally`, `hostCount`, and `pltgCount` are rendered alongside, mirroring the RTG layout field-for-field:

| RTG field | LTG equivalent |
|---|---|
| REGION | REGION (from parent RTG) |
| SEC | SEC (from parent RTG) |
| ALERT | ALERT |
| SEC TALLY | SEC TALLY |
| LTGs | HOSTS |
| RTGs | PLTGs |

`visibleObjects` was already available in `LocationPanel` and now passed down to `LocationFields`. All required data was already present in the `LocalGrid` DTO (`parentRtgName`, `hostCount`, `pltgCount`) and in the parent `GridNode` DTO (`region`, `securityCode`); no backend changes were needed.

`prd_ui.md` updated to reflect the expanded `LocalGrid` display fields.

### Options considered but not taken

- **Add `region` and `securityCode` directly to the `LocalGrid` DTO** — avoided because those fields already exist on the parent `GridNode` which is always in `visibleObjects`; duplicating them into the DTO would be redundant.
