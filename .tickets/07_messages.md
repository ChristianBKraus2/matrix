# 7 Messages

## Issue

For message "Logged on to \<location\>" show not the complete technical toString, but only the name and the security code like:
- "UCAS-SEA, GREEN(4)" instead of "OnLTG(ltg=LTG(name=UCAS-SEA, parentRtg=UCAS, security=GREEN(4)))"
- "Ares Macrotechnology PLTG (Ares Macrotechnology)" instead of "OnPLTG(pltg=PLTG(name=Ares Macrotechnology PLTG, owner=Ares Macrotechnology, parentLtg=UCAS-SEA))"
- "Lone Star GridSec Seattle, ORANGE(7) - NO_ALERT" instead of "OnHost(host=Host(name=Lone Star GridSec Seattle, security=ORANGE(7), alert=NO_ALERT, tally=0))"

## Solution

### Chosen approach

Added a private `MatrixLocation.logLabel()` extension function in `WebSocketDeckerController` and replaced the raw `$location` string interpolation in `LogonResult.toDispatch()` with `location.logLabel()`. The function formats each location type as a concise human-readable string:

- `OnRTG` → RTG name only
- `OnLTG` → `"<name>, <CODE>(<value>)"` e.g. `"UCAS-SEA, GREEN(4)"`
- `OnPLTG` → `"<name> (<owner>)"` e.g. `"Ares Macrotechnology PLTG (Ares Macrotechnology)"`
- `OnHost` → `"<name>, <CODE>(<value>) - <alert>"` e.g. `"Lone Star GridSec Seattle, ORANGE(7) - NO_ALERT"`

Key file changed: `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt` (line 524, plus new `logLabel()` function).

### Options considered but not taken

- **Reuse the existing `MatrixLocation.label()` from `DeckerStateDto.kt`** — rejected because that function is `private` to its file and only produces terse names like `"LTG: UCAS-SEA"` without security rating or alert status, which does not meet the ticket's requirements.
- **Override `toString()` on `MatrixLocation` subclasses** — rejected because `toString()` is used for logging throughout the engine and changing it would affect all debug output, not just the "Logged on to" message.
