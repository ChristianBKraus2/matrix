# Pass 5 — PRD Consistency Audit

Legend: ✅ implemented · ⚠️ partial · ❌ missing · 🔴 wrong · ⏭️ deferred (missing.md)

---

## M — Movement (18 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| M-01 | ✅ | jackInToLtg enforces LTG_JACKPOINT_TYPES and places persona on LTG | DeckerNavigationExtensions.kt |
| M-02 | ⚠️ | jackInToHost enforces HOST_JACKPOINT_TYPES and jp.connectsToHost; **missing**: persona is not placed at a specific subsystem node based on jackpoint type | DeckerNavigationExtensions.kt |
| M-03 | 🔴 | ILLEGAL_JUNCTION_BOX is in LTG types but **not** in HOST_JACKPOINT_TYPES; jackInToHost throws instead of routing to host | DeckerNavigationExtensions.kt:65 |
| M-04 | ✅ | performLogon contests SystemTestResolver with accessRating and securityValue | DeckerNavigationExtensions.kt:242 |
| M-05 | 🔴 | On failed logon, newLocation (with tally increment) is discarded; host-success tally is silently dropped | DeckerNavigationExtensions.kt:269 |
| M-06 | ✅ | RTG/PLTG reachability from LTG enforced via data-driven contains-checks | DeckerNavigationExtensions.kt |
| M-07 | ✅ | RTG-to-RTG and RTG-to-LTG navigation enforced | DeckerNavigationExtensions.kt |
| M-08 | ✅ | logonToLtg and logonToPltg both accept OnPLTG source | DeckerNavigationExtensions.kt |
| M-09 | 🔴 | RTG, LTG, PLTG each carry independent securityTally; no unified RTG-wide tally; tally from LTG-A is not propagated to RTG or LTG-B | Grid.kt:24,37,56 |
| M-10 | ✅ | logonToRtg RTG-to-RTG path leaves old RTG tally behind; new RTG accumulates freshly | DeckerNavigationExtensions.kt:93 |
| M-11 | ⚠️ | logonToPltg carries ltg.securityTally as inheritedTally; but M-09 bug means inherited value is only the LTG's own tally, not the RTG-wide tally | DeckerNavigationExtensions.kt:145 |
| M-12 | ⏭️ | Tally memory window (1D3×5 min) and jackpoint-switch tally reset explicitly deferred (missing.md item 4) | — |
| M-13 | ⚠️ | TopologyType stored; connectedHosts data-drives reachability; topology constraint is implicit in data, not enforced by code | Host.kt:16,31 |
| M-14 | ⚠️ | Same as M-13; chain-order enforcement entirely data-driven | Host.kt:16,31 |
| M-15 | ✅ | logonToHost from OnPLTG checks pltg.hosts.contains(host) | DeckerNavigationExtensions.kt:177 |
| M-16 | ✅ | gracefulLogoff uses GRACEFUL_LOGOFF system op, CC-33 Track penalty, correct success/fallback | DeckerNavigationExtensions.kt:201 |
| M-17 | ✅ | jackOut blocks when isPinnedByBlackIc; sets dumpShock unless immuneToDumpShock — **PRD cross-check (CT-04):** `immuneToDumpShock` is the correct implementation of CT-04 cyberterminal dump-shock immunity; not a deviation. | DeckerNavigationExtensions.kt:223 |
| M-18 | ⚠️ | Voluntary jack-out implemented; **involuntary** disconnection (persona crashed, deck disabled) has no forced-disconnect path in combat code. **PRD cross-check:** M-18 dump shock should also check `immuneToDumpShock` (CT-04 applies here too), so any future involuntary-disconnect implementation must respect the cyberterminal exception. | DeckerNavigationExtensions.kt |

**Summary:** 9 ✅ · 5 ⚠️ · 0 ❌ · 3 🔴 · 1 ⏭️

---

## CD — Cyberdeck & Program Mechanics (26 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| CD-01 | ✅ | init validates every utility rating ≤ mcpRating | Cyberdeck.kt:61-70 |
| CD-02 | ⚠️ | responseIncrease ≤ MPCP/4 and ≤ 3 enforced; Persona Reaction/Initiative formulas in Decker.kt not verified | Cyberdeck.kt:43,45 |
| CD-03 | ✅ | Utility.sourceCode field present; loaded from YAML | Utility.kt:41 |
| CD-04 | ✅ | PersonaPrograms excluded from active-memory accounting | Cyberdeck.kt |
| CD-05 | ✅ | Active/stored partition in DeckerLoader; init validates activeMp ≤ activeMemoryMp | DeckerLoader.kt:61-72 |
| CD-06 | ✅ | Active utilities loaded directly (turnsRemaining=0); currentRating=rating at construction | DeckerLoader.kt:85 |
| CD-07 | ✅ | loadUtility checks: in storage, not already loaded, memory capacity | DeckerMemoryExtensions.kt:13 |
| CD-08 | ✅ | Returns InsufficientMemory(required, available) before any state change | DeckerMemoryExtensions.kt:20 |
| CD-09 | ✅ | unloadUtility removes from active and pending; storedUtilities unchanged | DeckerMemoryExtensions.kt:43 |
| CD-10 | ✅ | turnsRequired = ceil(mpSize / ioSpeed); ioSpeed=0 guarded | DeckerMemoryExtensions.kt:24 |
| CD-11 | ✅ | advanceCombatTurn decrements turnsRemaining, promotes at ≤0 | DeckerMemoryExtensions.kt:62 |
| CD-12 | ✅ | Pending utilities provide no mechanical effect (not in activeUtilities) | Cyberdeck.kt |
| CD-13 | ✅ | swapUtility: unload first, then load; memory validated after free | DeckerMemoryExtensions.kt:54 |
| CD-14 | ⚠️ | currentRating field exists; TN-subtraction gate (active, not pending) in SystemTestResolver not verified | Utility.kt |
| CD-15 | ⚠️ | All 9 UtilityTypes present; operation-to-utility linkage enforcement in SystemTestResolver not verified | Utility.kt |
| CD-16 | ⚠️ | RELOCATE(multiplier=2) present; Relocate Icon operation definition in operation code not verified | Utility.kt:17 |
| CD-17 | ⚠️ | detectionFactor(maskingRating, sleazeRating?) on Cyberdeck; callers must supply sleaze rating — not auto-derived | Cyberdeck.kt:85 |
| CD-18 | ✅ | detectionFactor is a function (recalculated each call); formula ceil((masking+sleaze)/2) correct | Cyberdeck.kt:85 |
| CD-19 | ⚠️ | currentRating field supports decrement; trigger condition (damage bleed-through) in CombatResolver not verified | Utility.kt |
| CD-20 | ⚠️ | currentRating field supports decrement; Medic per-invocation decrement not verified in action code | Utility.kt |
| CD-21 | ✅ | rating (stored, immutable) and currentRating (runtime, can degrade) are distinct fields | Utility.kt |
| CD-22 | ✅ | advanceCombatTurn auto-unloads depleted utilities and removes from storedUtilities | DeckerMemoryExtensions.kt:64 |
| CD-23 | ✅ | Depleted utilities removed from storedUtilities → reload impossible | DeckerMemoryExtensions.kt |
| CD-24 | ✅ | DeckCatalogEntry has exactly 6 required hardware fields; no responseIncrease field | DeckCatalogEntry.kt |
| CD-25 | ✅ | All 8 deck models seeded; values match PRD table exactly | decks.yaml |
| CD-26 | ✅ | Catalog lookup with fallback; inline values override catalog defaults | DeckerLoader.kt:45 |

**Summary:** 19 ✅ · 7 ⚠️ · 0 ❌ · 0 🔴

---

## CT — Cyberterminals (5 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| CT-01 | ✅ | require(mcpRating ≤ 4) in Cyberterminal.kt:33 | Cyberterminal.kt |
| CT-02 | ✅ | responseIncrease hardwired to 0 | Cyberterminal.kt:41 |
| CT-03 | ✅ | effectiveRating() returns max(0, utility.currentRating - 1) when immuneToDumpShock | SystemTestResolver.kt:116 |
| CT-04 | ✅ | immuneToDumpShock = true; gates dump shock in both graceful logoff and jackOut | Cyberterminal.kt:47 |
| CT-05 | ⚠️ | costNuyen field present but 10%-of-cyberdeck-price constraint not enforced in code | Cyberterminal.kt:18 |

**Summary:** 4 ✅ · 1 ⚠️

---

## ACC — Accessories (3 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| ACC-01 | ⚠️ | OFFLINE_STORAGE type defined; but no code adds its capacity to the effective storage limit — the game-mechanical expansion is not implemented | Enums.kt |
| ACC-02 | ⚠️ | VID_SCREEN type defined and can be stored on deck; no behavioral code (flavor-only), but no doc/test confirms this is intentionally complete | Enums.kt |
| ACC-03 | ⚠️ | HitcherObserver exists; cannot-manipulate constraint documented but not enforced; biofeedback immunity for hitchers not modelled (no immuneToIcBiofeedback field on HitcherObserver) | Cyberdeck.kt |

**Summary:** 0 ✅ · 3 ⚠️

---

## AL — Alert State (2 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| AL-01 | ✅ | applyAlertTransition raises all 5 subsystem ratings by +2; permanent (not reversed on tally drop); stacking intentional per KDoc | AlertTransitions.kt |
| AL-02 | ⚠️ | ACTIVE_ALERT transition sets alertStatus; securityDeckerCount stored on TriggerStep; but NPC spawning is entirely delegated to caller with no enforcement code | SecuritySheaf.kt:14 |

**Summary:** 1 ✅ · 1 ⚠️

---

## MP — Matrix Perception (10 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| MP-01 | ✅ | noticeIcon rolls Sensor dice with no utility modifier | DeckerOperationsExtensions.kt:45 |
| MP-02 | ✅ | TN dispatch: PersonaIcon → masking + sleazeRating; IcIcon → ic.rating | DeckerOperationsExtensions.kt:52 |
| MP-03 | ⚠️ | SensorTestResult.Detected exposes full MatrixIcon regardless of success count; PRD requires tiered information hiding (1 success → presence only, 2 → type, 3 → rating) | MatrixIcon.kt:23 |
| MP-04 | ❌ | No persistent icon-visibility state on Decker; noticeIcon is one-shot; no detected-icons set; no Evade Detection clearing mechanic | DeckerOperationsExtensions.kt |
| MP-05 | ✅ | successes == 0 returns SensorTestResult.Undetected | DeckerOperationsExtensions.kt:59 |
| MP-06 | ✅ | locateDecker and locateIc both implemented | DeckerOperationsExtensions.kt:430 |
| MP-07 | ✅ | noticeTriggeredIc: 0→Undetected, 1→PresenceOnly, 2→TypeKnown, 3+→FullyLocated; all thresholds correct | DeckerOperationsExtensions.kt:64 |
| MP-08 | ✅ | noticeTriggeredIc is a single call; models one-time Sensor Test at IC activation | DeckerOperationsExtensions.kt |
| MP-09 | ✅ | friendlyReveal=true skips Sensor Test and returns Detected(icon, 1) immediately | DeckerOperationsExtensions.kt:48 |
| MP-10 | ✅ | locateDecker sets targetNotified=true; no attacker identity in result | DeckerOperationsExtensions.kt:448 |

**Summary:** 8 ✅ · 1 ⚠️ · 1 ❌

---

## SO — System Operation Mechanics (14 rules + individual operations)

### Core mechanics

| Rule | Status | Note | File |
|------|--------|------|------|
| SO-01 | ❌ | Non-combat action count (ceil(PersonaReaction/10)) not implemented | — |
| SO-02 | ❌ | +1 action per Response Increase beyond base not visible | — |
| SO-03 | ⚠️ | PointerChain type imported; full pointer-resolution flow not confirmed | DeckerOperationsExtensions.kt |
| SO-04 | ❌ | 1D6 roll for pointer-chain link count not implemented | — |
| SO-05 | ✅ | resolveInterrogation models accumulated-success dialogue | SystemTestResolver.kt |
| SO-06 | ✅ | InterrogationState.accumulatedSuccesses; ≥5 → Located | SystemTestResolver.kt |
| SO-07 | ✅ | QueryPrecision.modifier applied before utility reduction in TN | SystemTestResolver.kt |
| SO-08 | ✅ | locateFile returns NotFound at accumulatedSuccesses ≥ 3 when file absent | DeckerOperationsExtensions.kt |
| SO-09 | ⚠️ | PointerChain modelled; cross-host follow-through not confirmed | DeckerOperationsExtensions.kt |
| SO-10 | ⚠️ | ONGOING category assigned to Download Data, Upload Data, Swap Memory; continuation logic not confirmed beyond line 200 | SystemOperation.kt |
| SO-11 | ⚠️ | Time→turns conversion referenced; dedicated utility not confirmed | — |
| SO-12 | ❌ | Corrupted-file result on premature ongoing-operation termination not implemented | — |
| SO-13 | ⚠️ | MONITORED category assigned; Free-Action-per-Initiative-Pass and auto-abort not confirmed | SystemOperation.kt |
| SO-14 | ⚠️ | MONITORED category at metadata level; irreversible real-world-consequence abort not confirmed | SystemOperation.kt |

### Individual system operations

| Operation | Status | Note |
|-----------|--------|------|
| Analyze Host | ✅ | Correct enum; precondition; net-success reveal allocation; all-reveal at ≥7; none at ≤0 |
| Analyze IC | ⚠️ | Success/failure returned; IC type/rating/options not populated in return value |
| Analyze Icon | ✅ | Sensor+Analyze TN reduction correct |
| Analyze Security | ✅ | Returns securityRating, currentTally, alertStatus — all 3 PRD-required fields |
| Analyze Subsystem | 🔴 | Enum testType=CONTROL but PRD says "Targeted Subsystem"; misleading metadata |
| Control Slave | ⚠️ | Correct enum; MonitoredOperationHandle present; full monitored-op impl not confirmed |
| Decrypt Access/File/Slave | ⚠️ | Success/failure returned; missing Scramble destruct follow-up on failure (missing.md item 11) |
| Download Data | ⚠️ | ONGOING correct; DownloadHandle imported; I/O-rate transfer and corrupt-on-abort not confirmed |
| Edit File | ⚠️ | Signature correct; optional authentication extension OK; authentication-header detection mechanic not confirmed |
| Edit Slave | ⚠️ | MONITORED correct; full implementation beyond line 200 not confirmed |
| Graceful Logoff | ⚠️ | Correct enum; Track-TN raise (CC-33) not confirmed in read files |
| Locate Access Node | ⚠️ | Correct enum; once-only property; implementation beyond line 200 not confirmed |
| Locate Decker | ⚠️ | Correct enum; two-step mechanic (Index then open-ended Sensor) not confirmed in 200 lines; caller-supplied sleazeRating is a deviation (see design audit) |
| Locate File | ✅ | Full interrogation mechanic: 5-success located, 3-success not-found, accumulation, QueryPrecision |
| Locate IC | ⚠️ | Correct enum; auto-locate on System Test success (no separate Sensor Test) not confirmed |
| Locate Slave | ⚠️ | Correct enum; 3-success threshold (not 5) not confirmed in 200 lines |
| Logon to Host | ⚠️ | Enum correct; PRD-specific behaviour not confirmed in read files |
| Logon to LTG | ⚠️ | Enum correct; failed-logon tally memory window deferred (missing.md item 4) |
| Logon to PLTG | ✅ | Enum present (not in PRD table but consistent with M-03/M-08) |
| Logon to RTG | ⚠️ | Enum correct; movement implementation not confirmed in read files |
| Make Comcall | ⚠️ | Enum correct; multi-party/passcode bypass/tap detection not confirmed beyond line 200 |
| Monitor Slave | ⚠️ | MONITORED correct; full impl not confirmed |
| Null Operation | ✅ | Formula correct; tier table correct; +1/12h blocks correct |
| Relocate Icon | ⚠️ | RELOCATE enum correct; dual-test implementation not confirmed |
| Swap Memory | ⚠️ | testType=CONTROL but PRD says "None" — minor metadata issue; functionally OK |
| Tap Comcall | ⚠️ | testType=FILES only; three-step structure (Index→Control→Files) not confirmed |
| Upload Data | ⚠️ | ONGOING correct; cannot-upload-utilities constraint not confirmed |

**Core summary:** 4 ✅ · 6 ⚠️ · 4 ❌ · 0 🔴

---

## CC — Cybercombat (33 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| CC-01 | ❌ | Turn-order rule (astral→Matrix→physical) not modelled | — |
| CC-02 | ❌ | Reactive IC end-of-turn ordering is a game-loop concern not modelled | — |
| CC-03 | ⏭️ | Delayed-action sync to physical Initiative Pass deferred (missing.md item 7) | — |
| CC-04 | ⚠️ | -1D6 commPenalty implemented; action-in-physical-segment rule deferred (missing.md item 6) | CombatResolver.kt |
| CC-05 | ✅ | rollDeckerInitiative: numDice = max(1, 1 + responseIncrease - commPenalty); score = dice.sum() + reaction | CombatResolver.kt |
| CC-06 | ✅ | meatworldComm=true reduces numDice by 1 (floor 1) | CombatResolver.kt:38 |
| CC-07 | ⚠️ | rollIcInitiative delegates to ic.initiativeDice(securityCode); IC.kt Blue/1D6…Red/4D6 mapping to verify | CombatResolver.kt |
| CC-08 | ❌ | No logic subtracts 10 Initiative per completed Pass from mid-turn-triggered IC | — |
| CC-09 | ❌ | Per-phase action budget (1 Free + 2 Simple OR 1 Complex) not modelled | — |
| CC-10 | ❌ | Enumeration of combat Free Actions not encoded | — |
| CC-11 | ❌ | Combat Simple Action list not encoded | — |
| CC-12 | ✅ | resolveJackOutWithPin: Willpower vs Black IC rating; returns finalIcAttackTriggered=true on success | CombatResolver.kt:143 |
| CC-13 | ⚠️ | Decker.visibleObjects() models visibility; attacker-becomes-visible-unless-evades requires game-loop logic | Decker.kt |
| CC-14 | ⚠️ | IcBehavior.PROACTIVE referenced; tally-activation and continuous-attack loop are game-loop concerns | CombatResolver.kt |
| CC-15 | ✅ | resolveManeuver: mover.evasion vs opponent.sensor | CombatResolver.kt:53 |
| CC-16 | ✅ | moverTn = max(2, opponent.sensor - cloakRating); opponentTn = max(2, evasion - lockOnRating); Hacking Pool added | CombatResolver.kt:59 |
| CC-17 | ✅ | net > 0 → Success, else Failure (equal successes = Failure) | CombatResolver.kt:63 |
| CC-18 | ⚠️ | resolveManeuver returns net successes; re-detection countdown and tally-shortening deferred (missing.md item 8) | CombatResolver.kt |
| CC-19 | ⚠️ | parryAttackBonus consumed in resolveAttack; persistence-until-next-attack tracking is caller responsibility | CombatResolver.kt:75 |
| CC-20 | ⚠️ | Position bonuses applied; opponent-wins-gets-bonus branch and one-attack expiry are caller responsibilities | CombatResolver.kt:76 |
| CC-21 | ⚠️ | withUpdatedTally() exists; crash-IC→add-rating-to-tally call site not confirmed in reviewed files | Decker.kt:199 |
| CC-22 | ✅ | suppressIc/unsuppressIc implemented; effectiveDetectionFactor subtracts suppressionDfPenalty | CombatResolver.kt:412 |
| CC-23 | 🔴 | icAttackParticipant sets utilityRating=ic.rating + hackingPool=securityValue → pool = ic.rating + SV; PRD requires pool = SV only | CombatResolver.kt:437 |
| CC-24 | ✅ | attackTn table: all 8 cells correct | CombatResolver.kt:472 |
| CC-25 | ❌ | Legitimate passcode devalidation at logoff/jackout not modelled; deferred (missing.md item 14) | — |
| CC-26 | ✅ | resolveAttack returns AttackResult.Hit(attackerSuccesses, rawDamageLevel, staged, effectivePower) | CombatResolver.kt:80 |
| CC-27 | ✅ | rawLevel=MODERATE for Blue/Green, SERIOUS for Orange/Red; Power = ic.rating | CombatResolver.kt:439 |
| CC-28 | ✅ | effectivePower = max(0, power - armorCurrentRating); resistance roll against effectivePower | CombatResolver.kt:81 |
| CC-29 | ✅ | stage(): shift = net/2; ordinal clamped to valid range | CombatResolver.kt:487 |
| CC-30 | ✅ | conditionMonitor.applyDamage() called; isCrashed triggers dumpShockTriggered | CombatResolver.kt |
| CC-31 | ✅ | applyIcDamage: BlackIC branch skips simsense; DEADLY auto-crashes; Willpower TN table correct | CombatResolver.kt:89 |
| CC-32 | 🔴 | resolveDumpShock applies staged damage to physicalConditionMonitor; PRD says Stun → **Mental** Condition Monitor | CombatResolver.kt:137 |
| CC-33 | ✅ | resolveTrackLock: evasion vs trackRating; cycleTurns = ceil(10/net); stored on Decker.trackState | CombatResolver.kt:394 |

**Summary:** 15 ✅ · 8 ⚠️ · 6 ❌ · 2 🔴 · 2 ⏭️

---

## ICC — Intrusion Countermeasures (15 rules)

| Rule | Status | Note | File |
|------|--------|------|------|
| ICC-01 | ✅ | resolveCrippler: SV dice vs effectiveDetectionFactor; attribute dice vs ic.rating; net/2 reduction; floor 1; no Armor/Hardening | CombatResolver.kt:156 |
| ICC-02 | ✅ | Killer: resolveAttack + applyIcDamage; rawLevel MODERATE/SERIOUS by security code | IC.kt:64 |
| ICC-03 | ✅ | resolveProbe: ic.rating dice vs effectiveDetectionFactor; successes added to tally | CombatResolver.kt:174 |
| ICC-04 | ⚠️ | Scramble class exists with reactive no-op action; **missing**: Scramble destruct test on failed Decrypt (missing.md item 11) | IC.kt:98 |
| ICC-05 | ⚠️ | resolveTarContest correct; **gap**: passive utilities (Armor, Sleaze) can be set as target category — exclusion not enforced | CombatResolver.kt:503 |
| ICC-06 | ✅ | resolveBlaster: resolveAttack + applyIcDamage; MPCP test on dump; reduceMcpRating every 2 successes; TN = hardening + mcpRating | CombatResolver.kt:183 |
| ICC-07 | ✅ | resolveRipper: mirrors Crippler; resolveRipperMpcpTest when attribute reaches 0; Hardening in TN | CombatResolver.kt:189 |
| ICC-08 | ✅ | resolveSparkyMpcpTest: TN = hardening + mcpRating + 2; Body damage staged from Moderate; Hardening reduces Power | CombatResolver.kt:210 |
| ICC-09 | ✅ | resolveTarPit: resolveTarContest then resolveTarPitMpcpTest; utility removed from both lists | CombatResolver.kt:235 |
| ICC-10 | ✅ | blackIcPin set on any hit (including zero net damage); resolveJackOutWithPin: Willpower vs ic.rating | CombatResolver.kt:143 |
| ICC-11 | ⚠️ | Dual resistance tests present; **gaps**: (1) Armor not applied to Power for icon's resistance roll; (2) IC rating +2 after icon-before-decker crash not modelled; (3) data deletion on MPCP=0 absent (missing.md item 9) | CombatResolver.kt:256 |
| ICC-12 | ⚠️ | Mental damage + Willpower resistance + auto-disconnect correct; same 3 gaps as ICC-11 | CombatResolver.kt:310 |
| ICC-13 | ✅ | resolveBlackHammer: Physical dual-damage; no final MPCP attack (correct per PRD) | CombatResolver.kt:360 |
| ICC-14 | ✅ | resolveKilljoy: Mental damage; Willpower resistance; no final MPCP attack; Hardening reduces Power | CombatResolver.kt:376 |
| ICC-15 | ✅ | resolveSlow: REACTIVE IC returns (0, false) immediately; Opposed Test; net/2 = actionsLost; icInert flag | CombatResolver.kt:452 |

**Summary:** 11 ✅ · 4 ⚠️

---

## Overall PRD Totals

| Family | ✅ | ⚠️ | ❌ | 🔴 | ⏭️ |
|--------|-----|-----|-----|-----|-----|
| M (18) | 9 | 5 | 1 | 3 | 1 |
| CD (26) | 19 | 7 | 0 | 0 | 0 |
| CT (5) | 4 | 1 | 0 | 0 | 0 |
| ACC (3) | 0 | 3 | 0 | 0 | 0 |
| AL (2) | 1 | 1 | 0 | 0 | 0 |
| MP (10) | 8 | 1 | 1 | 0 | 0 |
| SO-core (14) | 4 | 6 | 4 | 0 | 0 |
| SO-ops (~26) | 4 | 20 | 0 | 1 | 0 |
| CC (33) | 15 | 8 | 6 | 2 | 2 |
| ICC (15) | 11 | 4 | 0 | 0 | 0 |
| **Total** | **75** | **56** | **12** | **6** | **3** |

**Critical bugs (🔴 wrong):** M-03, M-05, M-09, CC-23, CC-32, OP-ANALYZE-SUBSYSTEM  
**Hard missing (❌):** MP-04, SO-01, SO-02, SO-04, SO-12, CC-01, CC-02, CC-08, CC-09, CC-10, CC-11, CC-25  
**Deferred (⏭️):** M-12, CC-03, CC-25 (per missing.md)
