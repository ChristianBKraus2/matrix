# Iteration 2 — Design Doc Audit: `design/design_core/combat.md`

## Coverage table

| File | Lines | Verbatim excerpts (proves full read) | Notes/findings |
|---|---|---|---|
| `design/design_core/combat.md` | 772 | **Opening third (line 82):** `    val cloakRating: Int = 0,` — from the `ManeuverParticipant` data class. **Middle third (line 408):** `1. \`moverTn = max(2, opponent.sensor - mover.cloakRating)\`` — from `resolveManeuver` step 1. **Closing third (line 695):** `4. \`cycleTurns = ceil(10.0 / net).toInt()\`` — from `resolveTrackLock` step 4. | Read line 1 → 772 in a single Read. 13 candidate findings; the dominant theme is a systematic PRD cross-reference drift between the method-body sections (CC-27..CC-30) and the Verification table (CC-31..CC-33), plus two method-signature/param defects and one promised-but-unspecified IC subtype (Scramble). |

Total lines: **772**. Candidate findings: **13**.

---

## Distilled spec additions

Checkable facts for later `CombatResolver.kt` conformance (all line refs into `combat.md`):

### Domain types
- `CombatInitiative(score: Int, initiativePasses: Int)` — line 25. `initiativePasses` = number of initiative dice (1 + Response Increase for deckers; 1–4 for IC by Security Code) (line 31).
- `AttackResult.Hit(attackerSuccesses, rawDamageLevel, stagedDamageLevel, rawWeaponPower, power)` + `AttackResult.Miss` object — lines 40–50. `rawWeaponPower` = pre-armor power; `power` = effective post-armor power = `max(0, rawWeaponPower - armorRating)` (line 53).
- `ManeuverResult.Success(netSuccesses)` / `Failure` — lines 62–65.
- `ManeuverParticipant(evasion, sensor, cloakRating=0, lockOnRating=0, hackingPool=0)` — lines 77–83. `cloakRating` reduces mover TN; `lockOnRating` reduces opponent TN (line 86).
- `AttackParticipant(attackDicePool, weaponPower=attackDicePool, hackingPool=0, rawDamageLevel, modifiers=CombatModifiers())` — lines 95–101. Decker: both = program `currentRating`; IC: `attackDicePool` = host Security Value, `weaponPower` = IC Rating; use `icAttackParticipant(ic, securityCode)` (line 104).
- `DefenderParticipant(bod, armorCurrentRating=0, personaStatus, securityCode)` — lines 113–118.
- `CombatModifiers(parryAttackBonus=0, positionAttackTnBonus=0, positionAttackPowerBonus=0)` with `init` require: TN bonus XOR Power bonus, not both — lines 130–140.
- `BlackIcPinState(pinningIc: BlackIC)` — line 152. `JackOutPinResult(succeeded, finalIcAttackTriggered)` — lines 164–167.
- `IcDamageResult(updatedDecker, iconDamage, simsenseOverload, dumpShockTriggered, mpcpReductionOnKill=0, personaOnlyCrashed=false)` — lines 179–186.
- `SimsenseOverloadResult(willpowerTestPassed, stressBoxesApplied)` — stressBoxes 1 on fail, 0 on success — lines 198–204.
- `CripplerResult(updatedDecker, targetAttribute, reduction)` — lines 213–217.
- `TarBabyResult(updatedDecker, bothCrashed, deckerNoticed)` — lines 229–233.
- `TrackState(trackingIcRating, locationCycleTurnsRemaining, opponentSensorRating, trackerMcpRating)` — lines 245–250.
- `SlowResult(actionsLost, icInert)`; `icInert=true` when `actionsLost >= icInitiative.initiativePasses` — lines 261–268.
- `IcSuppressionState(ic: IC, icRating: Int)` — lines 276–280.

### Decker changes (lines 291–322)
- Add `blackIcPin: BlackIcPinState? = null`, `trackState: TrackState? = null`, `suppressedIc: List<IcSuppressionState> = emptyList()`.
- `isPinnedByBlackIc: Boolean get() = blackIcPin != null` (line 303).
- `suppressionDfPenalty: Int get() = suppressedIc.size` (line 315). Effective DF = `detectionFactor - suppressionDfPenalty` (line 318).
- `advanceCombatTurn()` decrements `trackState?.locationCycleTurnsRemaining`, nulling it at 0 (line 320).

### ConditionMonitor (lines 332–344)
- `applyDamage(damage: DamageLevel) = copy(damage = minOf(maxBoxes, damage + damage.boxes))`; overload `applyDamage(stressBoxes: Int)`; `isCrashed get() = damage >= maxBoxes`.
- `DamageLevel.boxes`: LIGHT=1, MODERATE=3, SERIOUS=6, DEADLY=10 (line 344).

### Initiative (lines 370–398)
- `rollDeckerInitiative`: `responseDice = cyberdeck.responseIncrease`; `commPenalty = meatworldComm ? 1 : 0`; `numDice = max(1, 1 + responseDice - commPenalty)`; roll numDice D6 + `persona!!.reaction` = score. Persona Reaction = base + RI×2; must NOT read physical-augmentation attributes (line 380).
- `rollIcInitiative`: `numDice = ic.initiativeDice(securityCode)`; roll + `ic.rating` = score. Mid-turn trigger: caller subtracts `10 × completedPasses` (line 396).

### Maneuvers (lines 408–413)
- `moverTn = max(2, opponent.sensor - mover.cloakRating)`; `opponentTn = max(2, mover.evasion - opponent.lockOnRating)`.
- Roll `mover.evasion + mover.hackingPool` vs moverTn; roll `opponent.sensor + opponent.hackingPool` vs opponentTn. `net = moverSuccesses - opponentSuccesses`. Success iff `net > 0` (equal successes = fail, line 736).

### Attack (lines 427–448)
- `tn = attackTn(status, code)`; `+= parryAttackBonus`; `-= positionAttackTnBonus`; `power = weaponPower + positionAttackPowerBonus`; `effectivePower = max(0, power - armorCurrentRating)`.
- Roll `attackDicePool + hackingPool` vs `max(2, tn)`. 0 successes → Miss. Else roll `defender.bod` vs effectivePower; `net = attackerSuccesses - defenderSuccesses`; `staged = stage(rawDamageLevel, net)`.
- `attackTn` table (lines 441–447): BLUE 6/3, GREEN 5/4, ORANGE 4/5, RED 3/6 (INTRUDING/LEGITIMATE).
- `stage(base, net)`: shift by `net / 2` (integer, toward zero), clamped [LIGHT, DEADLY] (line 448).

### Icon damage (lines 460–466)
- Apply staged to `persona!!.conditionMonitor`. Simsense overload: BlackIC → null; DEADLY → null + auto dumpShock; else `overloadTn = LIGHT→2, MODERATE→3, SERIOUS→5`, roll `willpower`, 0 successes → 1 Stun box, `stressBoxesApplied=1`. Crash → dumpShockTriggered.

### Dump shock (lines 474–478)
- `shock = DumpShock(host.securityRating)`; roll `decker.body` vs `shock.power`; `actualLevel = stage(shock.level, -successes)`; apply to Mental CM (Stun).

### Black IC pin (lines 490–492)
- Roll `willpower` vs `blackIcPin!!.pinningIc.rating`. `>=1` → succeeded + finalIcAttackTriggered; else both false.

### Suppression (lines 504–522)
- `suppressIc`: append `IcSuppressionState(ic, ic.rating)`; do NOT add rating to tally.
- `unsuppressIc`: remove state, call `securityTallyIncrement(state.icRating)`, DF +1.

### IC resolvers
- `resolveCrippler` (531–536): roll `securityValue` vs `effectiveDetectionFactor` = icSuccesses; roll `persona!!.attribute(targetAttribute)` vs `ic.rating` = deckerSuccesses; `net = icSuccesses - deckerSuccesses`; `reduction = max(0, net/2)`; `newValue = max(1, current - reduction)`. Armor/Hardening no protection.
- `resolveKiller` (544): delegates to `resolveAttack`; rawDamageLevel = Blue/Green MODERATE, Orange/Red SERIOUS.
- `resolveProbe` (552): roll `ic.rating` vs `effectiveDetectionFactor` → successes = tally points.
- `resolveTarBaby` (563–570): roll `ic.rating` vs `utility.currentRating` and vice versa; `icSuccesses >= utilitySuccesses` → bothCrashed; else roll `persona!!.sensor` vs `ic.rating` for deckerNoticed.
- `resolveBlaster` (576): identical to resolveKiller. `resolveBlasterMpcpTest` (584–587): `tn = max(2, hardening + mcpRating)`; `attackRating = ratingOverride ?: ic.rating`; `reduction = successes/2`; `newMcpRating = max(0, mcpRating - reduction)`; re-clamp `responseIncrease` to `min(RI, floor(newMcpRating/4))` (CD-02) — applies to Ripper/Sparky/TarPit MPCP tests too.
- `resolveRipper` (595): as Crippler but attribute floor `max(0, ...)` not 1. `resolveRipperMpcpTest` identical to Blaster's.
- `resolveSparky` (603): as Killer. `resolveSparkyMpcpTest` (611–613): `tn = max(2, hardening + mcpRating + 2)`; roll `ic.rating`; returns `Pair<Decker, Int>`. `resolveSparkyBodyDamage` (619–623): `staged = stage(MODERATE, sparkySuccesses)`; `effectivePower = max(0, ic.rating - hardening)`; roll `decker.body`; `actual = stage(staged, -bodySuccesses)` → Physical CM.
- `resolveTarPit` (627): as TarBaby; on bothCrashed call `resolveTarPitMpcpTest` (635–639): `tn = max(2, hardening + mcpRating)`; 0 successes → reloadable; else corrupt all copies in `activeUtilities` + `storedUtilities`.
- `resolveLethalBlackIc` (649–658): DamageLevel Blue/Green MODERATE, Orange/Red SERIOUS; `power = ic.rating`; `effectivePower = max(0, power - hardening)`; icon test rolls `persona!!.bod` vs power (Armor applies); body test rolls `decker.body` vs effectivePower (Armor does NOT reduce, Hacking Pool NOT added); stage both; icon-crash-before-death sets IC rating `+2`; death sets dumpShockTriggered + inline final MPCP attack `ratingOverride = ic.rating * 2` reported in `mpcpReductionOnKill`; MPCP→0 deletes downloaded data; simsenseOverload null.
- `resolveNonLethalBlackIc` (664–666): as lethal but Mental (Willpower) damage; unconsciousness → auto-disconnect + final MPCP attack `ic.rating * 2`.
- `resolveBlackHammer` (676): as resolveLethalBlackIc but NO final MPCP attack and NO blackIcPin; precondition `!isCyberterminal`.
- `resolveKilljoy` (682): as resolveNonLethalBlackIc with same exceptions.
- `resolveTrackLock` (692–696): roll `persona!!.evasion` vs `max(2, trackRating)`; `evadeSuccesses >= attackerSuccesses` → null; `net = attackerSuccesses - evadeSuccesses`; `cycleTurns = ceil(10.0/net)`; TrackState all four fields = trackRating (except cycleTurns).
- `resolveSlow` (708–716): precondition PROACTIVE (reactive → `SlowResult(0,false)`); roll `securityValue` vs `slowRating` and vice versa; `net = slowSuccesses - icSuccesses`; `net<=0` → `(0,false)`; `actionsLost = net/2`; `icInert = (initiativePasses - actionsLost) <= 0`.

---

## Candidate findings

**DOC-1 — "nine IC subtypes" contradicts the 11 enumerated.** Line 11: `- All nine IC subtypes: White (Crippler, Killer, Probe, Scramble, Tar Baby), Gray (Blaster, Ripper, Sparky, Tar Pit), Black (Lethal, Non-Lethal)`. The enumerated list is 5 + 4 + 2 = 11 subtypes, not nine.

**DOC-2 — Scramble promised but never specified.** Line 11 lists `Scramble` as a White IC subtype and the Purpose (line 5) claims the doc covers ICC-01..ICC-15, but there is no `resolveScramble` algorithm, result type, or TN formula anywhere in the document (every other listed subtype has a `resolve*` section). Missing spec for a subtype the doc claims to cover.

**DOC-3 — `attackTn` cites two different PRD clauses in the same method.** Line 427: `1. \`tn = attackTn(defender.personaStatus, defender.securityCode)\` — CC-21 table (see below).` vs line 439: `**\`private fun attackTn(status: PersonaStatus, code: SecurityCode): Int\`** encodes the CC-24 table:`. The referenced table is described as both CC-21 and CC-24. (Verification line 739 uses CC-24.)

**DOC-4 — Parry/Position Attack PRD refs disagree between body and Verification table.** Body: line 428 `2. \`tn += attacker.modifiers.parryAttackBonus\` — from an opponent's prior Parry Attack (CC-18).` and line 429 `— from own prior Position Attack (CC-19).`. Verification: line 737 `... next incoming attack TN +3 (CC-19)` (Parry) and line 738 `... on next attack (CC-20)` (Position). Body says Parry=CC-18/Position=CC-19; table says Parry=CC-19/Position=CC-20.

**DOC-5 — Dump shock PRD ref internally inconsistent (CC-29 vs CC-32).** Header line 470 `PRD: CC-29.` and DumpShock section line 354 `PRD: CC-29.`, but step 4 line 478 `... (CC-32: dump shock is Stun damage).` and Verification line 756 `... (CC-32)`. Same behavior cited as both CC-29 and CC-32.

**DOC-6 — Simsense overload PRD ref inconsistent (CC-28 vs CC-31).** Type doc line 204 `PRD: CC-28.` and `applyIcDamage` line 458 `PRD: CC-27, CC-28, ICC-10.`, but Verification lines 753–755 cite the same simsense/overload behaviors as `(CC-31)` / `(CC-31, ICC-10)`.

**DOC-7 — Track PRD ref inconsistent (CC-30 vs CC-33).** `TrackState` line 253 `PRD: CC-30.`, Decker line 322 `PRD: ICC-10, CC-22, CC-30.`, `resolveTrackLock` line 690 `PRD: CC-30.`, but Verification lines 766–767 cite Track as `(CC-33)`. (Purpose line 5 says the PRD runs CC-01..CC-33, implying the higher numbers are current and the body's CC-27..CC-30 refs are stale — root cause of DOC-3/5/6/7.)

**DOC-8 — `unsuppressIc` signature omits the `decker` parameter it operates on.** Line 515: `#### \`unsuppressIc(ic: IC, securityTallyIncrement: (Int) -> Unit): Decker\``. Step 1 (line 519) `Find and remove the \`IcSuppressionState\` matching \`ic\` from \`decker.suppressedIc\`.` and step 3 (line 521) `Return updated \`Decker\`.` both use `decker`, which is not a parameter. Every sibling resolver takes `decker: Decker` as its first arg; this one cannot compile as written.

**DOC-9 — `suppressIc` `host` param described as used but never referenced.** Line 500 signature `suppressIc(decker: Decker, ic: IC, host: Host): Decker`; line 502 `The \`host\` parameter is used for rating-based MPCP deduction.` The algorithm (steps 1–3, lines 504–506) only appends `IcSuppressionState` and returns the Decker; `host` is never read, and suppression is unrelated to MPCP deduction. Stale/unused parameter with a misleading justification.

**DOC-10 — `decker.effectiveDetectionFactor` referenced but never declared on Decker.** `resolveCrippler` line 531 and `resolveProbe` line 552 both roll `vs. decker.effectiveDetectionFactor`, but the Decker changes section (lines 312–318) only declares `suppressionDfPenalty` and describes effective DF as an expression (`detectionFactor - suppressionDfPenalty`), never as a property named `effectiveDetectionFactor`. The property the resolvers call is undefined in the type spec.

**DOC-11 — `IcDamageResult.personaOnlyCrashed` field is never set or read.** Line 186 declares `val personaOnlyCrashed: Boolean = false`, but no resolver in the document sets it (e.g. `applyIcDamage` line 466 returns `IcDamageResult(updatedDecker, attack, simsenseOverload, dumpShockTriggered)`), and no algorithm or Verification row references it. Dead/unspecified field.

**DOC-12 — Black Hammer/Killjoy called "identical" despite different signatures.** `resolveBlackHammer` line 674 takes `(targetDecker, attack: AttackResult.Hit, diceRoller)` and is described (line 676) as `Identical to \`resolveLethalBlackIc\` **except** ...`, but `resolveLethalBlackIc` (line 645) takes `(decker, ic: LethalBlackIC, securityCode, diceRoller)` and computes its own icon/body resistance tests internally rather than receiving a precomputed `AttackResult.Hit`. "Identical" is imprecise given the differing inputs (same for `resolveKilljoy` vs `resolveNonLethalBlackIc`, line 682).

**DOC-13 — Verification row mis-attributes decker meatworld initiative to CC-06.** Line 727: `| Decker RI 2 with meatworld comms | \`numDice = max(1, 1+2−1) = 2\`; score reduced by ~3.5 average (CC-06) |`. `rollDeckerInitiative` is governed by CC-04/CC-05 (line 372); CC-06 is `rollIcInitiative` (line 390). Wrong clause on a decker-initiative row.
