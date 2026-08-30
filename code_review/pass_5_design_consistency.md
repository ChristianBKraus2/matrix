# Pass 5 — Design Doc Consistency Audit

Legend: ✅ ok · ⚠️ deviation · ❌ missing from code · ➕ extra in code (not in design)

---

## combat.md

| Item | Status | Note | File |
|------|--------|------|------|
| rollDeckerInitiative | ✅ | Formula, commPenalty, numDice=max(1,…), score=dice.sum()+reaction, CombatInitiative all match | CombatResolver.kt:36 |
| rollIcInitiative | ✅ | numDice=ic.initiativeDice(securityCode), score=dice.sum()+ic.rating; IC.kt 1/2/3/4 dice per code correct | CombatResolver.kt:44 |
| resolveManeuver | ✅ | moverTn, opponentTn, dice pools, net>0 condition, ManeuverResult variants all match | CombatResolver.kt:53 |
| attackTn table | ✅ | All 8 cells match CC-24 | CombatResolver.kt:472 |
| resolveJackOutWithPin | ✅ | Willpower vs pin.rating; JackOutPinResult(true,true) on success | CombatResolver.kt:143 |
| resolveTarBaby/TarPit contest | ✅ | Opposed test, bothCrashed, noticed, TarPit MPCP test on bothCrashed | CombatResolver.kt:503 |
| resolveNonLethalBlackIc — final MPCP dice | ⚠️ | Design: final MPCP attack at ic.rating (standard); impl uses ic.rating × 2, same as lethal variant | CombatResolver.kt:343 |
| icAttackParticipant — IC attack pool (CC-23) | ⚠️ | Design: IC rolls Security Value dice only; impl sets pool = ic.rating + securityValue | CombatResolver.kt:443 |
| resolveLethalBlackIc — MPCP trigger condition | 🔴 | **PRD cross-check (ICC-11):** PRD confirms the design is correct — the final double-rating MPCP attack fires only when the decker dies (Physical CM fills). When the icon crashes first, ICC-11 says only the IC effective rating increases by 2; no MPCP attack occurs. The code triggering on either icon OR physical CM crash is a **bug**, not an acceptable extension. Reclassified ⚠️→🔴. | CombatResolver.kt:282 |
| resolveLethalBlackIc — IC rating +2 after icon crash (step 7) | ❌ | Design: if icon crashes first, IC effective rating +2 for subsequent tests; no such signal in IcDamageResult and no impl logic | CombatResolver.kt:282 |
| IcDamageResult — extra mpcpReductionOnKill | ➕ | Fifth field not in design spec; added for lethal/non-lethal Black IC paths | CombatResolver.kt |
| resolveBlackHammer — secondary damage staging | ⚠️ | Design: identical to resolveLethalBlackIc (body damage staged from rawLevel); impl stages from attack.stagedDamageLevel → double-staged | CombatResolver.kt:366 |
| resolveKilljoy — secondary damage staging | ⚠️ | Same double-staging bug as resolveBlackHammer for mental damage | CombatResolver.kt:382 |
| reduceMcpRating — responseIncrease cap | ⚠️ | Design: only mcpRating updated; impl also clamps responseIncrease to newMcp/4; undocumented | CombatResolver.kt:499 |
| MPCP tests — max(2,tn) floor | ⚠️ | Design specifies TN = hardening + mcpRating with no floor; impl adds max(2,tn) on all MPCP tests | CombatResolver.kt:495 |
| resolveAttack — defender roll skip | ⚠️ | Design: roll defender.bod unconditionally; impl skips roll when effectivePower < 2 | CombatResolver.kt:81 |
| resolveCrippler/resolveProbe — TN source | ⚠️ | Design: use decker.detectionFactor; impl uses decker.effectiveDetectionFactor (subtracts suppressionDfPenalty) — arguably correct game-rules intent but literal deviation | CombatResolver.kt:158 |
| resolveRipper — attribute floor | ⚠️ | Design says "identical to resolveCrippler" (floor 1); impl uses floor 0 to enable ICC-07 kill trigger; impl is game-rules-correct but spec text is inconsistent | CombatResolver.kt:197 |
| suppressIc/unsuppressIc — signature | ⚠️ | Design shows extension-style methods on decker; impl takes explicit `decker: Decker` parameter; expected for stateless object but formal deviation | CombatResolver.kt:412 |
| resolveSlow/resolveTrackLock/resolveDumpShock — max(2,…) floors | ⚠️ | Design specifies no TN floors on these three methods; impl adds max(2,…) guards | CombatResolver.kt:461 |

**Summary:** 6 ✅ · 12 ⚠️ · 1 ❌ · 1 ➕

---

## cyberdeck_and_program_mechanics.md

| Item | Status | Note | File |
|------|--------|------|------|
| Utility constructor | ✅ | type, rating, attackDamageLevel, currentRating=rating, sourceCode=false all match | Utility.kt |
| UtilityType enum entries | ✅ | All types with correct multipliers and categories | Utility.kt |
| Cyberdeck.pendingUploads | ✅ | List\<PendingUpload\> defaulting to emptyList() | Cyberdeck.kt |
| usedActiveMemoryMp / freeActiveMemoryMp | ✅ | Both computed correctly | Cyberdeck.kt |
| init: utility rating ≤ MPCP | ✅ | Validated for both active and stored utilities | Cyberdeck.kt |
| hitchers / HitcherObserver | ✅ | Both present as specified under ACC-03 | Cyberdeck.kt |
| PersonaProgram constructor | ✅ | attributeType, rating, multiplier=1 — matches spec | PersonaProgram.kt |
| Cyberterminal class | ❌ | Design specifies a full Cyberterminal subclass of Cyberdeck; **no such class exists in Cyberdeck.kt** — it lives in Cyberterminal.kt but the design references it in the CD doc context | Cyberdeck.kt |
| Cyberdeck declared as `open class` | ⚠️ | Cyberdeck is a `data class` (final in Kotlin); design requires it to be subclassable by Cyberterminal | Cyberdeck.kt |
| immuneToDumpShock as open computed property | ⚠️ | Design: `open val immuneToDumpShock get() = false` overridden in Cyberterminal; impl: plain constructor param (included in equals/hashCode/copy by data class) | Cyberdeck.kt |
| detectionFactor on Decker (auto-derives sleaze) | ⚠️ | Design places detectionFactor on Decker as computed property reading activeUtilities; impl places it on Cyberdeck as a parameterized method — callers must supply sleaze rating explicitly | Cyberdeck.kt |
| Cyberdeck.name field | ➕ | impl has `name: String` constructor parameter; design's DeckCatalogEntry carries model name but Cyberdeck itself has no name field in spec | Cyberdeck.kt |

**Summary:** 7 ✅ · 3 ⚠️ · 1 ❌ · 1 ➕

---

## movement.md

| Item | Status | Note | File |
|------|--------|------|------|
| MatrixLocation sealed class | ✅ | OnLTG, OnRTG, OnPLTG, OnHost — exact match | MatrixLocation.kt |
| jackInToLtg — jackpoint type guard | ✅ | LTG_JACKPOINT_TYPES exactly as spec | DeckerNavigationExtensions.kt |
| logonToRtg — fresh tally on new RTG | ✅ | Target RTG preserves its own starting tally; prior RTG tally not carried | DeckerNavigationExtensions.kt |
| jackInToLtg — mergeRtgTally helper (M-09) | ❌ | Design notes a mergeRtgTally helper to propagate hostSuccesses to ltg.parentRtg.securityTally; no such helper exists; RTG tally never updated on jack-in | DeckerNavigationExtensions.kt |
| jackInToHost — ILLEGAL_JUNCTION_BOX | ⚠️ | Design lists as valid for jackInToHost (M-03); impl's HOST_JACKPOINT_TYPES omits it → throws | DeckerNavigationExtensions.kt |
| jackInToHost — persona.currentNode assignment | ❌ | Design requires persona placed at specific subsystem node by jackpoint type; impl never sets currentNode | DeckerNavigationExtensions.kt |
| logonToLtg — from-OnLTG for PLTG targets | ⚠️ | Design handles PLTG access inside logonToLtg with type-check branch; impl routes to separate logonToPltg and throws for OnLTG source | DeckerNavigationExtensions.kt |
| logonToLtg — returns OnPLTG for PLTG targets | ⚠️ | Design: buildLocation returns OnPLTG when target is PLTG; impl always returns OnLTG | DeckerNavigationExtensions.kt:130 |
| logonToPltg — extra method | ➕ | Design specifies exactly 7 public movement methods; impl has 8th method logonToPltg; causes M-11 tally-inheritance gap for OnRTG source | DeckerNavigationExtensions.kt |
| logonToHost — topology guard (M-13) | ⚠️ | Design: sibling-second-tier violation returns LogonResult.Failure; impl throws IllegalStateException | DeckerNavigationExtensions.kt:180 |
| gracefulLogoff — dump shock | ✅ | **PRD cross-check (CT-04):** The code checking `!cyberdeck.immuneToDumpShock` is correct. CT-04 explicitly grants cyberterminal users immunity to dump shock; the `Cyberterminal` subclass overrides `immuneToDumpShock = true` to implement this. The design doc saying "unconditionally" is the gap — it omits the CT-04 exception. Reclassified ⚠️→✅ (code is correct; design doc needs the CT-04 caveat added). | DeckerNavigationExtensions.kt:216 |
| jackOut — dump shock | ✅ | **PRD cross-check (CT-04):** Same as gracefulLogoff above. `immuneToDumpShock` is the implementation mechanism for CT-04, not an undocumented extension. Reclassified ⚠️→✅. | DeckerNavigationExtensions.kt:229 |

**Summary:** 3 ✅ · 6 ⚠️ · 2 ❌ · 1 ➕

---

## operations.md + game.md

| Item | Status | Note | File |
|------|--------|------|------|
| AnalyzeHost | ✅ | Signature, precondition, net-success algorithm, 7-success all-reveal, AnalyzeHostResult all match | DeckerOperationsExtensions.kt |
| LocateFile — threshold and NotFound | ✅ | Located ≥5, NotFound ≥3 when absent — both match | DeckerOperationsExtensions.kt |
| LocateSlave — 3-success threshold | ✅ | Located ≥3 (not 5) — correct | DeckerOperationsExtensions.kt |
| EditFile | ✅ | Signature matches; optional authentication extension additive; TN = control - rw (floor 2) correct | DeckerOperationsExtensions.kt |
| NullOperation | ✅ | Delegates to resolveNullOperation with inactivitySeconds — matches | DeckerOperationsExtensions.kt |
| ControlSlave/EditSlave/MonitorSlave — MonitoredOperationHandle | ✅ | All return Pair\<OperationResult, MonitoredOperationHandle?\> — matches | DeckerOperationsExtensions.kt |
| LocateDecker — two-step and targetNotified | ✅ | Index Test then Sensor Test; targetNotified=true on success — matches | DeckerOperationsExtensions.kt |
| MakeComcall — passcode bypass | ✅ | hasValidPasscode=true short-circuits all tests — matches | DeckerOperationsExtensions.kt |
| TapComcall — scanner test | ✅ | Caller supplies highest scanner rating; TN = scannerRating - commlink.currentRating (floor 2); no tally — matches | DeckerOperationsExtensions.kt |
| Game.runOutOfCombatTurn/runCombatTurn — initiative loop | ✅ | Sorted descending, highest initiative >0, action(), decrement by 10 — matches | Game.kt |
| GameContext — required query methods | ✅ | unauthorizedDeckerInNode/Host, updateDecker, removeIc all present | GameContext.kt |
| LocateFile/LocateSlave/LocateAccessNode — return type | ⚠️ | Design: returns Pair\<OperationResult, InterrogationState\>; impl returns Pair\<OperationResult, LocateResult\> with state kept in Decker.interrogationStates map | DeckerOperationsExtensions.kt |
| LocateAccessNode — missing NotFound branch | ⚠️ | Design: NotFound at ≥3 successes when resource absent; locateAccessNode has no NotFound branch — can only return Located or Ongoing | DeckerOperationsExtensions.kt:238 |
| DownloadData — return type | ⚠️ | Design: returns DownloadHandle; impl returns Pair\<OperationResult, DownloadHandle?\>; impl extends contract to cover failure (not in spec) | DeckerOperationsExtensions.kt |
| ControlSlave — bypasses SystemTestResolver | ⚠️ | Design: all operations use SystemTestResolver.resolve(); controlSlave manually calls diceRoller.roll() twice and builds SystemTestOutcome directly | DeckerOperationsExtensions.kt:316 |
| LocateDecker — sleaze rating source | ⚠️ | Design: TN reads sleaze from targetPersona.sleaze; impl: caller-supplied targetSleazeRating: Int parameter | DeckerOperationsExtensions.kt:434 |
| Game — inCombat constructor parameter | ⚠️ | Design: class Game(context, diceRoller, inCombat: Boolean); impl omits inCombat entirely | Game.kt:10 |
| Game.runOutOfCombatTurn/runCombatTurn — suspend modifier | ⚠️ | Design: plain fun; impl: suspend fun (adds coroutine dependency) | Game.kt:16 |
| GameContext — MutableList vs List constructor params | ⚠️ | Design: MutableList\<Decker\>, MutableList\<IC\>; impl takes List and converts internally; 7 extra methods not in spec (addIc, resetToSingleDecker, etc.) | GameContext.kt |

**Summary:** 11 ✅ · 8 ⚠️

---

## Overall Design Audit Totals

| Doc | ✅ | ⚠️ | ❌ | ➕ |
|-----|-----|-----|-----|-----|
| combat.md | 6 | 12 | 1 | 1 |
| cyberdeck_and_program_mechanics.md | 7 | 3 | 1 | 1 |
| movement.md | 3 | 6 | 2 | 1 |
| operations.md + game.md | 11 | 8 | 0 | 0 |
| **Total** | **27** | **29** | **4** | **3** |

**Highest-severity design deviations:**
1. **CC-23 / combat.md** — IC attack pool adds ic.rating to Security Value; should be SV only → inflated IC attack power
2. **CC-32 / combat.md** — Dump shock stages to Physical CM; PRD says Mental CM
3. **resolveNonLethalBlackIc** — final MPCP attack uses ic.rating × 2 instead of ic.rating (standard)
4. **resolveBlackHammer / resolveKilljoy** — secondary damage double-staged
5. **movement.md M-09** — no unified RTG-wide tally; each node has independent securityTally
