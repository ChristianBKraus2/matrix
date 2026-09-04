# Security Review — game_logic

## Summary

The game logic layer is a pure Kotlin library with no network surface of its own; all external input reaches it through the server layer. From a security lens the primary concerns are therefore API-level trust assumptions that make it easy for a server caller to introduce vulnerabilities, and game-integrity bugs whose exploitation is equivalent to cheating. Four concrete issues were found: an unverifiable bypass flag in `makeComcall`, a security-tally reset bug in `logonToRtg` that erases accumulated intrusion evidence on every re-entry, an invariant violation in `Cyberdeck.copy()` that throws an uncaught exception when Black/Gray IC reduces MPCP to zero during combat (leaving decker state inconsistent), and an empty-query loophole in the interrogation operations that trivially locates the first file or device without a real search term. Two lower-severity input-validation gaps round out the findings.

## Findings

### [HIGH] `makeComcall` passcode bypass is caller-supplied with no validation mechanism
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:448
**Issue:** `makeComcall` accepts `hasValidPasscode: Boolean = false`. When `true` the entire System Test is replaced with a hardcoded fake outcome `SystemTestOutcome(1, 0, true)`, granting instant success and zero tally increase. The library provides no API for verifying whether a passcode is actually valid — it is entirely the caller's responsibility. If the server layer passes this flag based on anything derived from client input (e.g. a player-submitted passcode field) without independent server-side validation, an attacker can bypass all host communication security at zero cost. The risk is elevated because the default is `false`, which makes the "safe" path the default, but the bypass path is a single boolean flip away with no guard.
**Recommendation:** Remove the bypass flag from the game-logic API. Move passcode validation to a separate server-layer predicate that returns a verified `Boolean` only after checking the passcode against a server-authoritative store. The game-logic function should perform a System Test unconditionally and let the caller apply any legitimate TN modifier that a valid passcode grants rather than skipping the test altogether.

**[RESOLVED]** — `hasValidPasscode` removed from `ActionParams`; `makeComcall` now always passes `false` with a TODO comment, eliminating the client-supplied bypass.

### [MEDIUM] `logonToRtg` resets security tally instead of accumulating it
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:104
**Issue:** Inside `logonToRtg`, the `buildLocation` lambda writes `rtg.copy(securityTally = hostTallyDelta)`, replacing the RTG's existing tally with only the delta from the current logon test. Every other navigation function accumulates correctly: `logonToLtg` uses `ltg.securityTally + hostTallyDelta` (line 131) and `logonToHost` uses `host.securityTally + hostTallyDelta` (line 190). The inconsistency means a decker can log off and back on to an RTG to reset its security tally to at most a small delta, permanently suppressing security escalation on that node. This breaks the core intrusion-detection mechanic for RTG-level traversal.
**Recommendation:** Change line 104 to `MatrixLocation.OnRTG(rtg.copy(securityTally = rtg.securityTally + hostTallyDelta))` to match the pattern used by all other `logonTo*` functions.

**[RESOLVED]** — Fixed in `DeckerNavigationExtensions.kt`: `logonToRtg` now accumulates as `rtg.securityTally + hostTallyDelta`, consistent with all other logon functions.

### [MEDIUM] Combat-reduced MPCP causes `Cyberdeck.copy()` to throw, leaving decker state inconsistent
**File:** src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:198
**Issue:** `resolveBlasterMpcpTest` (line 198), `resolveRipperMpcpTest` (line 222), and `resolveSparkyMpcpTest` (line 233) all call `decker.cyberdeck.copy(mcpRating = max(0, mcpRating - reduction))`. If `reduction` equals `mcpRating`, the result is `mcpRating = 0`. The `Cyberdeck.init` block then enforces `responseIncrease <= maxResponseIncrease` where `maxResponseIncrease = mcpRating / 4 = 0`. Any deck with `responseIncrease >= 1` (valid and common: Shadowrun allows up to MPCP/4 extra dice) will throw `IllegalArgumentException` at copy time. This exception is caught by the broad `catch (e: Exception)` in `Game.runCombatTurn()` (Game.kt:26), so the game does not crash — but `context.updateDecker()` is never called, leaving the in-context decker at its pre-attack state while the IC action is treated as having fired. The decker escapes damage that the rules require.
**Recommendation:** Before copying, clamp `responseIncrease` to `max(0, newMcpRating) / 4` when reducing MPCP. Alternatively, model MPCP reduction as a separate field (e.g. `mcpDamage`) that does not violate the construction invariant, and derive the effective MPCP dynamically.

**[RESOLVED]** — Fixed in `CombatResolver.kt`: all MPCP-reduction calls now cap `responseIncrease = min(ri, newMcp / 4)`, preventing the `Cyberdeck.copy()` invariant violation.

### [MEDIUM] Empty query string in interrogation operations matches first file/device
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:181
**Issue:** `locateFile`, `locateSlave`, and `locateAccessNode` initialise a fresh `InterrogationState` with an empty query (`InterrogationState(operation, "")`). After accumulating enough successes, `locateFile` evaluates `host.dataFiles.firstOrNull { it.name.contains(state.query, ignoreCase = true) }`. Because every `String` contains the empty string, a decker who starts a new search without setting a query will find the first file in the host's data list after 5 accumulated successes. The same pattern applies to `locateSlave` (line 206) and `locateAccessNode` returns the empty string itself as the "located" node name (line 227). A player exploiting this can locate an arbitrary file or device on any host without knowing its name.
**Recommendation:** Require a non-blank query before the interrogation begins: add `require(query.isNotBlank()) { "Interrogation query must not be blank" }` in the `InterrogationState` constructor or at the top of each `locate*` function. The server layer must supply the query from a validated, player-provided search term.

**[RESOLVED]** — Fixed in `DeckerOperationsExtensions.kt`: `query: String = ""` parameter added with `require(existingState != null || query.isNotBlank())` guard.

### [LOW] `controlSlave` accepts unchecked `effectiveSkill` override
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:293
**Issue:** `controlSlave` accepts an optional `effectiveSkill: Int? = null` that replaces the decker's `computerSkill` with no range check. A caller could pass a negative value (producing a `roll(negativeInt, tn)` call that will throw due to the `require(numberOfDice > 0)` guard in `DiceRoller`) or an arbitrarily large value (massively inflating the dice pool). There is no documented upper-bound contract for this parameter.
**Recommendation:** Add `require(effectiveSkill == null || effectiveSkill in 1..20) { "effectiveSkill out of range" }` or derive the skill adjustment through a validated modifier type rather than a raw int override.

**[RESOLVED]** — Fixed in `DeckerOperationsExtensions.kt`: `require(effectiveSkill == null || effectiveSkill in 1..20)` guard added in `controlSlave`.

### [LOW] `editFile` accepts unbounded `newContent: ByteArray?` with no size check
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:262
**Issue:** The `newContent` parameter has no maximum-size validation. If the server layer forwards client-supplied file content directly, an attacker could cause excessive memory allocation. The game library stores this through the caller — if the resulting `DataFile` is kept in `Host.dataFiles`, repeated large writes accumulate in memory.
**Recommendation:** Define and enforce a maximum file size constant (e.g. aligned with the `storageMemoryMp` limit on the cyberdeck) and add `require(newContent == null || newContent.size <= MAX_FILE_BYTES)` before proceeding.

**[RESOLVED]** — Fixed in `DeckerOperationsExtensions.kt`: `require(newContent == null || newContent.size <= 4096)` size cap added to `editFile`.

### [INFO] Message content written to INFO log in `bufferMessage`
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:519
**Issue:** The first 40 characters of the buffered message text are emitted at INFO level. If game chat contains player-identifiable or sensitive in-game information (e.g. passcodes discussed in character), it will appear in server logs.
**Recommendation:** Downgrade to DEBUG level, or omit message content from the log entirely and log only the sender and recipient names.

**[DEFERRED]** — Log level not changed; out of scope for this session.

## No Issues Found In

- `src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt` — input guards (`numberOfDice > 0`, `targetNumber >= 2`) are present; exploding-dice loop is bounded by probability; no caller-controlled branching.
- `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt` — `init` block rigorously enforces MPCP bounds for persona programs and utilities, active/storage memory capacity, and response-increase limits at construction time.
- `src/main/kotlin/com/shadowrun/matrix/decker/Persona.kt` — simple value container; no logic paths.
- `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt` — `updateDecker` uses reference identity via `indexOf`, preventing name-collision substitution; `checkTriggers` correctly gates on strictly greater ordinal before escalating alert status.
- `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt` (topology validation) — every `logonTo*` function verifies the decker is at a topologically adjacent location before transit; attempts from invalid positions throw `IllegalStateException`.
- `src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt` — `loadUtility` checks storage presence and active-memory duplication before accepting; `advanceCombatTurn` depletion logic is consistent.
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt` — utility modifier application is correct; cyberterminal penalty is applied uniformly; effective TN is always clamped to minimum 2.
- `src/main/kotlin/com/shadowrun/matrix/network/SecuritySheaf.kt`, `Node.kt`, `Host.kt`, `Grid.kt` — data-carrier classes with no logic; `Host.init` enforces full subsystem coverage.
- `src/main/kotlin/com/shadowrun/matrix/common/Enums.kt`, `SharedTypes.kt` — no logic surface.
- `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt` — `findTarget` correctly restricts to `PersonaStatus.INTRUDING` deckers; `moveIfNeeded` correctly skips reactive IC and IC already on the target node.
- `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt` (attack and staging logic) — `stage()` ordinal arithmetic is correctly clamped to `DamageLevel.entries` bounds; `attackTn` correctly inverts the table for legitimate vs intruding personas.
