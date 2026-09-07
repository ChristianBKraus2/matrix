#  01 JackIn together with Login

## Issue

Currently the player only logs into server. As a result he is not yet part of the game loop and never has the chance to jackIn. Both has to be done together.
This implies that the player has to enter the jackIn point together with the decker handle.

## Solution

### Chosen approach — `jackPointName` in `JoinMessage`

The player provides both their decker handle and the jackpoint address in the same `JoinMessage`.
After the server confirms registration (`ControlMessage(role: registered_decker)`), it immediately
calls `performJackIn()` in `WebSocketDeckerController`, which resolves the jackpoint name to an
`LTG` or `Host` in `GameContext`, performs the System Test (dice contest via the existing
`jackInToLtg` / `jackInToHost` extension functions in `DeckerNavigationExtensions.kt`), and
broadcasts a `ResultMessage` to all sessions. The first game turn (`StateMessage`) follows only
after the jackIn result has been sent. This satisfies the "together" constraint: a single client
message triggers both login and jack-in.

**Key files changed:**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt` — `jackPointName: String = ""` added to `JoinMessage`
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt` — `jackPointDeferreds` map and `awaitJackPointName()` suspension method; deferred completed in `receiveJoin()`
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt` — `performJackIn()` method added
- `src/main/kotlin/com/shadowrun/matrix/Main.kt` — `performJackIn()` called before the turn loop

### Options considered but not taken

**JackIn as first `AvailableAction` (sequential, two-step):** Add a `JackIn` variant to
`AvailableAction` and return it from `availableActions()` when `persona == null`. The player
would first join, then receive a `StateMessage` with `[JackIn]` as the only available action,
and submit an `ActionCommand` to execute it. This fits the existing action-dispatch loop
cleanly but requires a separate client round-trip after join and does not satisfy the "together
in one step" constraint.

**Auto-jackIn with no client input (server-only):** Trigger jack-in automatically on join
without any jackpoint information from the client, using the jackpoint pre-configured on the
`Decker` server-side. This collapses the two steps but gives the player no agency — the
jackpoint is invisible to them. The ticket explicitly requires the player to *enter* the jackIn
point together with the decker handle, so this option was rejected.
