# Iteration 2 — Design Docs: movement.md & game.md

Conformance audit distillation. Files read in full via sequential Read from line 1 to last line in this session.

## Coverage Table

| File | Lines | Verbatim excerpts (copied tokens) | Notes / findings |
|---|---|---|---|
| `design/design_core/movement.md` | 370 | **(opening, ~L19)** `data class OnLTG(val ltg: LTG) : MatrixLocation()` / `data class OnRTG(val rtg: RTG) : MatrixLocation()` / `data class OnPLTG(val pltg: PLTG) : MatrixLocation()` / `data class OnHost(val host: Host) : MatrixLocation()` — **(middle, ~L206)** `**Tally persistence (M-09):** if target ` + `ltg` + ` shares the same parent RTG as current LTG, the RTG tally is unchanged.` — **(closing, ~L299)** `| PLTG-to-PLTG hop | New PLTG tally starts at 0 — no carry-over from source PLTG |` | Eight pure methods on `Decker`; tally persistence rules; graceful-logoff vs jack-out; Black-IC pin. Internal contradiction between `logonToLtg` preconditions and its step-3 "current LTG" tally rule, and between that method and the summary table's "sibling LTG" row (DOC-1, DOC-2). Passcode-devalidation snippet is dubious Kotlin (DOC-3). |
| `design/design_game/game.md` | 449 | **(opening, ~L24)** `fun initiative(context: GameContext, diceRoller: DiceRoller): CombatInitiative` / `suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult` — **(middle, ~L216)** `protected fun moveIfNeeded(target: Decker, context: GameContext): ActionResult.IcMoved? {` ... `// IC moves: caller replaces this IC instance in context.activeIc with a copy at the new node` / `return ActionResult.IcMoved("$name moved to $targetNode")` — **(closing, ~L445)** `- IC action dispatch: all 11 subtypes (` + "`Killer`, `Crippler`, `Probe`, `Scramble`, `TarBaby`, `Blaster`, `Ripper`, `Sparky`, `TarPit`, `LethalBlackIC`, `NonLethalBlackIC`" + `)` | ActiveIcon contract, GameContext API, Game turn loop (proactive/physical/reactive segments), 11 IC subclass `action()` bodies. `initiative()` returns `CombatInitiative` but `ActiveIconState.currentInitiative` is `Int` (DOC-5). IC-move side-effect never persisted by any caller (DOC-4). `withRatingBonus` only defined on NonLethalBlackIC (DOC-6). No interrogation state map present (DOC-7). Out-of-combat loop multiplies a no-op action (DOC-8). |

---

## Distilled spec additions (checkable facts)

### movement.md — jackpoint / entry rules
- `MatrixLocation` sealed class has exactly 4 variants: `OnLTG(ltg)`, `OnRTG(rtg)`, `OnPLTG(pltg)`, `OnHost(host)`; `null` = jacked out (movement.md L18-24, L95).
- Active security tally is read from the contained grid/host object's `securityTally` field (L26).
- `jackInToLtg` preconditions: `persona == null`, `jackpoint != null`, `jackpoint.type ∈ {LEGAL_ACCESS, ILLEGAL_ACCESS, TELECOM, ILLEGAL_JUNCTION_BOX}` (L136-139).
- `jackInToHost` preconditions: `persona == null`, `jackpoint != null`, `jackpoint.type ∈ {WORKSTATION, CONSOLE, REMOTE_DEVICE, ILLEGAL_JUNCTION_BOX}`, `jackpoint.connectsToHost == host` (L155-159).
- `jackInToHost` node mapping: WORKSTATION→Access node, REMOTE_DEVICE→Slave node, CONSOLE→Control node, ILLEGAL_JUNCTION_BOX→Access node (L164-168).
- SystemTest call shape per logon: `SystemTestResolver.resolve(decker, <SystemOperation>, <target>.subsystemRatings.access, <target>.securityRating.value, diceRoller)`; TN reduced by utility `currentRating` mapped to operation (CD-15), floored at 2; host rolls against `decker.effectiveDetectionFactor` (L120-124, L142, L162, L185, L204, L229, L248).

### movement.md — RTG / LTG / PLTG navigation
- `logonToRtg` origins: `OnLTG` (to its parent RTG) or `OnRTG` (peer RTG long-distance hop) (L179-182).
- `logonToLtg` origins: `OnRTG` (ltg attached to that RTG) or `OnPLTG` (PLTG supports all LTG ops, M-08) (L199-202). Does NOT accept PLTG targets — `PLTG` and `LTG` are sibling types, not subtypes (L197).
- `logonToPltg` origins: `OnLTG` (pltg attached) or `OnPLTG` (PLTG-to-PLTG hop); any other origin incl. `OnHost` throws `IllegalStateException` (L220-223).
- `logonToHost` origins: `OnLTG` (open-access), `OnPLTG` (private-grid M-15), `OnHost` (target in `currentHost.connectedHosts`, tiered/host-host M-13/M-14) (L240-243).
- Tiered guard (M-13): 2nd-tier→sibling-2nd-tier returns `LogonResult.Failure` (precondition, not a roll) (L245).
- Topology table: Open Access = any host on LTG directly; Tiered = first-tier only; Host-Host = chain order; Private Grid = any host on PLTG once on PLTG (L334-337). Helper `reachableHosts(location): Set<Host>` (L339).

### movement.md — tally persistence rules (which grids share tally / when reset)
- Logon to LTG (win or lose): add `hostSuccesses` to LTG/parent RTG tally; RTG tally tracked on `ltg.parentRtg.securityTally`; helper `mergeRtgTally(ltg, outcome)` (L147, L295).
- Sibling LTG same RTG: RTG tally unchanged; LTG shares RTG tally (L206, L296).
- Different RTG (M-10): new RTG tally starts at 0, no carry-over (L187, L297).
- Enter PLTG from public grid (`OnLTG`): PLTG `securityTally = inheritedTally + outcome.hostSuccesses` where `inheritedTally` = LTG's **parent RTG's** `securityTally` (M-11) (L226-230, L298).
- PLTG-to-PLTG hop: `inheritedTally = 0`, independent tally (L228, L299).
- Logon to Host: add `hostSuccesses` to host tally, separate from grid tally (L249, L300).
- Failed LTG logon retry from SAME jackpoint within `1D3×5` min window: tally continues from current value (not reset) (L210, L303, L368).
- Failed LTG logon retry from DIFFERENT jackpoint: fresh tally at 0 (L210, L304, L369).
- New decker logs on mid-reset: initial tally = current reduced value, not 0 (L302, L324).
- System reset schedule (rules p.212): Blue resets fully in 2D6 min; Green/Orange/Red begin reset after 3D6 min if no alert; if alert, roll 1D6 every Green 5 / Orange 10 / Red 15 min and reduce tally by roll (L310-322). Reset timer held by game engine; no `Host`/`LTG` self-decrement (L326).

### movement.md — graceful logoff vs jack-out; Black-IC pin
- `gracefulLogoff`: precondition `currentLocation != null`; effective TN = `accessRating`, +`trackState.trackingIcRating` if `trackState != null` (CC-33); on win clears persona, `currentLocation = null` → `GracefulSuccess`; on fail → `JackOut(decker, dumpShock = !decker.cyberdeck.isCyberterminal)` (L259-267).
- `jackOut`: precondition `currentLocation != null` AND NOT `pinnedByBlackIC` (else `IllegalStateException`); clears persona, returns `JackOut(dumpShock = !decker.cyberdeck.isCyberterminal)` (L277-283).
- Dump-shock damage (Power = host Security Value, level from Security Code) applied by caller separately (L285).
- Passcode devalidation (rules p.226): if `PersonaStatus == LEGITIMATE`, host devalidates passcode on BOTH graceful logoff and jack-out; caller marks invalid (L269, L287).

### game.md — turn / action economy, initiative, game loop
- `ActiveIcon` interface: `initiative(context, diceRoller): CombatInitiative` + `suspend action(context, diceRoller): ActionResult` (L24-27).
- `ActionResult` variants: `IcAttack(message)`, `IcMoved(message)`, `NoTarget`, `DeckerAction` (L39-44).
- Out-of-combat: `runOutOfCombatTurn()` calls `decker.action()` `decker.actionsPerTurn` times per decker; `actionsPerTurn` = ⌈Persona Reaction ÷ 10⌉ + Response Increase (SO-01/SO-02); no IC (L129-132).
- In-combat `runCombatTurn()` three segments (CC-01/CC-02/CC-04):
  1. Proactive list = non-meatworld-comm deckers + proactive IC (`behavior != REACTIVE`); reactive IC & meatworld-comm deckers excluded. Meatworld-comm list = deckers with `meatworldComm == true` (L143-146).
  2. Roll `icon.initiative()` for each proactive icon; build `MutableList<ActiveIconState>` sorted descending by `currentInitiative` (L147).
  3. Action loop: pick highest `currentInitiative > 0`, call `action()`, decrement that entry by 10; repeat until all ≤ 0 (L148-149).
  4. Physical segment: each meatworld-comm decker `action()` once (L153).
  5. Reactive IC end-of-turn: each reactive IC in `activeIc` `action()` once, after all decker actions (L157).
  6. Housekeeping: `decker.advanceCombatTurn()` on each decker (CD-11/CC-33) (L161).
  7. Loop to next turn; combat ends when `activeIc` empty or caller signals externally (L163).
- Player decker turns in production bypass `Game`: `WebSocketDeckerController.conductTurn(context, diceRoller)` handles input/dispatch/broadcast; never called via `ActiveIcon`; `Decker.action()` is a no-op `DeckerAction` placeholder (L181-188).

### game.md — session / turn coordination (GameContext API)
- `GameContext` ctor: `(host, val securityCode, deckers, val matrix = Matrix(), activeIc = emptyList())`; `host` is `var` `private set`; `deckers`/`activeIc` read-only views (L61-72).
- Methods: `unauthorizedDeckerInNode(node)`, `unauthorizedDeckerInHost()`, `updateDecker(old,new)`, `removeIc(ic)`, `addIc(ic)`, `resetToSingleDecker(decker)`, `deckerByName(name)`, `updateHost(new)`, `checkTriggers(oldTally,newTally)`, `applyDeckerOperationResult(old,new)`, `addToSecurityTally(points)` (L74-85).
- `unauthorizedDeckerInNode` returns first decker with `persona.currentNode == node` AND `persona.status == INTRUDING` (L88).
- `checkTriggers` fires steps whose threshold ∈ `(oldTally, newTally]`; spawns IC via `addIc`, applies alert transition via `updateHost` (AL-01/AL-02) (L94).
- `addToSecurityTally` used by Probe IC (ICC-03: successes added immediately) (L96, L264).
- `ActiveIconState(icon: ActiveIcon, currentInitiative: Int)`; list rebuilt each turn (L104-111).
- `findTarget`: if `guardedNode != null` → `unauthorizedDeckerInNode(guardedNode) ?: unauthorizedDeckerInHost()`, else `unauthorizedDeckerInHost()` (L209-211).
- `moveIfNeeded`: returns null if `guardedNode == null`, if target has no node, if `targetNode == guardedNode`, or if `behavior == REACTIVE`; else returns `IcMoved` (L216-223).
- Every non-reactive IC `action()` pattern: `findTarget` → `moveIfNeeded` → resolver → apply to context (L229). 11 IC subtypes (L445).
- `Decker.asDefenderParticipant()` requires non-null persona and `currentLocation as OnHost`; armor from ARMOR utility `currentRating ?: 0` (L405-416).

### game.md — available-actions location filtering
- Host context (`OnHost`): full op table — `ANALYZE_HOST, LOCATE_FILE, LOCATE_SLAVE, LOCATE_IC, ANALYZE_IC, DOWNLOAD_DATA, EDIT_FILE, CONTROL_SLAVE, DECRYPT_*` etc. (L427).
- Grid context (`OnLTG/OnRTG/OnPLTG`): only `NULL_OPERATION, LOCATE_ACCESS_NODE` (M-07 from RTG), `ANALYZE_SECURITY, LOCATE_IC, DECRYPT_ACCESS` (L428).
- Filter applied inside `Decker.availableActions()`, not server dispatch (L430).
- `swapUtility()` and `locateDecker()` exist on `Decker` but excluded from `availableActions()` and not dispatched via `WebSocketDeckerController` (overrides prd_game deferral) (L432).

---

## Candidate findings

**DOC-1 — `logonToLtg` step-3 tally rule references a "current LTG" the preconditions forbid**
movement.md L206 (step 3): "**Tally persistence (M-09):** if target `ltg` shares the same parent RTG as current LTG, the RTG tally is unchanged." But the method's own preconditions (L199-202) only permit origins `OnRTG` or `OnPLTG` — never `OnLTG`. There is no "current LTG" when this method runs, so the tally-persistence rule as written can never apply. Internal contradiction / stale step carried over from an earlier design where LTG→LTG was direct.

**DOC-2 — Security Tally Summary "sibling LTG" row has no method that implements it**
movement.md L296: "| Switch to sibling LTG (same RTG) | RTG tally unchanged; LTG shares RTG tally |". No public method accepts `OnLTG` as an origin for an LTG destination (`logonToLtg` requires `OnRTG`/`OnPLTG`, L199-202; `logonToRtg` goes LTG→RTG, L179-182). Table row and method surface disagree — either a missing method or a stale table entry.

**DOC-3 — Passcode-devalidation snippet is not valid assignment syntax**
movement.md L269: "The caller must set `decker.hasValidPasscode(host) = false`". `hasValidPasscode(host)` reads as a method call, not an assignable property; you cannot assign to a function result. Ambiguous whether this is a field, a map, or a method. Same wording implied at L287 ("mark the passcode invalid"). Staleness/imprecision — a later code iteration cannot compare against an unimplementable expression.

**DOC-4 — IC move side-effect ("replace instance in activeIc") is never performed by any caller**
game.md L221 comment: "// IC moves: caller replaces this IC instance in context.activeIc with a copy at the new node". Every IC subclass `action()` does `moveIfNeeded(target, context)?.let { return it }` (e.g. L234, L250, L262) — it returns `IcMoved` and takes no further action. The `Game.runCombatTurn` action loop (L148-149) only calls `action()` and decrements initiative by 10; it does not inspect the returned `IcMoved` nor replace the IC in `activeIc`. So a moved IC's `guardedNode` is never updated — the documented move never persists. Internal gap/contradiction between the move contract and the loop that is supposed to honor it.

**DOC-5 — `initiative()` return type (`CombatInitiative`) vs `ActiveIconState.currentInitiative` (`Int`) mismatch, mapping unspecified**
game.md L26: `fun initiative(...): CombatInitiative`. game.md L107: `val currentInitiative: Int`. runCombatTurn L147 says "Roll initiative ... by calling `icon.initiative(...)`. Build a `MutableList<ActiveIconState>` sorted descending by `currentInitiative`." The doc never states how a `CombatInitiative` becomes the `Int currentInitiative` (which field/score). A later code iteration cannot verify the conversion.

**DOC-6 — `withRatingBonus` used by `LethalBlackIC` but only defined on `NonLethalBlackIC`**
game.md L375: `LethalBlackIC.action` calls `context.addIc(withRatingBonus(2))`. The only `withRatingBonus` definition shown is on `NonLethalBlackIC` (L383: `fun withRatingBonus(bonus: Int) = NonLethalBlackIC(rating + bonus, guardedNode)`). `LethalBlackIC`'s own `withRatingBonus` is referenced but undefined in the doc — incomplete spec.

**DOC-7 — game.md contains no interrogation state map / session-coordination for interrogation**
The game-layer design doc (`game.md`, 449 lines) documents combat and out-of-combat turn coordination but has no interrogation state map, no interrogation session/turn coordination, and no INT-prefixed content anywhere. The audit brief expected an interrogation state map here. Either it lives in another doc (staleness of cross-references) or the game layer's interrogation coordination is undocumented. Flagged so a later iteration knows game.md is NOT the interrogation-state reference.

**DOC-8 — Out-of-combat loop multiplies a no-op action by `actionsPerTurn`**
game.md L132: `runOutOfCombatTurn()` "calls `decker.action(context, diceRoller)` `decker.actionsPerTurn` times per decker". But `Decker.action()` is defined as a no-op placeholder returning `ActionResult.DeckerAction` with no side effects (L181-183, L188). Iterating a side-effect-free call `actionsPerTurn` times accomplishes nothing; the action-economy multiplication is effectively dead unless `action()` is later given real behavior. Possible staleness (loop written for a future non-placeholder `action()`).
