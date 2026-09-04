# Pass 5 — Audit Summary

Covers: PRD rules (pass_5_prd_consistency.md), design docs (pass_5_design_consistency.md), test coverage (pass_5_test_coverage.md).

---

## Totals at a Glance

| Audit | ✅ ok | ⚠️ partial / deviation | ❌ missing | 🔴 wrong |
|-------|-------|------------------------|------------|----------|
| PRD rules (152 items) | 75 | 56 | 12 | 6 |
| Design doc items (64) | 27 | 29 | 4 | — |
| PRD coverage gaps | — | — | 15 rules | — |
| Untested file behaviours | — | — | 17 behaviours | — |

**3 PRD rules intentionally deferred** (missing.md): M-12 (tally memory window), CC-03 (delayed-action physical sync), CC-25 (Legitimate passcode devalidation).

---

## Top Bugs (Wrong Implementations)

These are correctness bugs, not gaps — the code runs but produces wrong results:

1. **CC-23 / icAttackParticipant** — IC attack dice pool = ic.rating + securityValue; PRD requires Security Value only. Every IC combat roll has the IC's own rating incorrectly added, inflating attack power. Fix: `utilityRating = securityValue, hackingPool = 0`.  
   → `CombatResolver.kt:437`

2. **CC-32 / resolveDumpShock** — Dump shock staged damage is applied to `physicalConditionMonitor`; PRD says Stun damage → **Mental** CM. Dump shock currently has no effect on mental health at all.  
   → `CombatResolver.kt:137`

3. **M-09 / Grid.kt** — Each RTG, LTG, and PLTG carries an independent `securityTally` field. PRD requires a unified tally shared across an RTG and all its LTGs. Tally accumulated on LTG-A is never propagated to RTG or LTG-B.  
   → `Grid.kt:24,37,56`

4. **M-05 / performLogon** — On failed logon, `newLocation` (which carries the host-successes tally increment) is discarded; host successes silently vanish on failure, violating the rule that tally accumulates regardless of outcome.  
   → `DeckerNavigationExtensions.kt:269`

5. **M-03 / HOST_JACKPOINT_TYPES** — `ILLEGAL_JUNCTION_BOX` is in LTG jackpoint types but absent from host jackpoint types; jackInToHost throws an exception instead of allowing the connection.  
   → `DeckerNavigationExtensions.kt:65`

6. **resolveNonLethalBlackIc — final MPCP attack** — Uses `ic.rating × 2` dice; design spec says standard `ic.rating` (non-doubled).  
   → `CombatResolver.kt:343`

---

## High-Risk Design Deviations

These require design review to decide whether code or doc should be updated:

| Deviation | Impact | Location |
|-----------|--------|----------|
| resolveBlackHammer / resolveKilljoy — double-staged secondary damage | Secondary damage level too high | CombatResolver.kt:366,382 |
| MPCP tests add max(2,tn) floor not in spec | Slightly harder for IC to reduce MPCP | CombatResolver.kt:495 |
| resolveAttack skips defender roll when effectivePower < 2 | Defender never resists near-zero power hits | CombatResolver.kt:81 |
| Cyberdeck is `data class` (final); design requires it to be `open` for Cyberterminal subclass | Cyberterminal.kt duplicates fields rather than inheriting | Cyberdeck.kt |
| gracefulLogoff / jackOut: dump shock checks immuneToDumpShock; design hardcodes true | Cyberterminals never suffer dump shock (possibly intended) | DeckerNavigationExtensions.kt:216,229 |
| ControlSlave bypasses SystemTestResolver | Inconsistent with all other operations | DeckerOperationsExtensions.kt:316 |
| LocateAccessNode has no NotFound branch | Can never tell caller the access node doesn't exist | DeckerOperationsExtensions.kt:238 |

---

## Missing High-Priority Items

Rules with no implementation and no deferred status:

| Rule | Description |
|------|-------------|
| MP-04 | Persistent icon-visibility state; detected icons stay visible; Evade Detection clears them |
| SO-01 / SO-02 | Non-combat action count formula (ceil(Reaction/10) + Response Increase bonus) |
| SO-04 | 1D6 pointer-chain link count |
| SO-12 | Corrupted-file result on premature ongoing-operation termination |
| CC-01 / CC-02 | Turn-order rules (astral→Matrix→physical; reactive IC acts after all decker actions) |
| CC-08 | Mid-turn IC loses 10 Initiative per completed Pass |
| CC-09 / CC-10 / CC-11 | Per-phase action budget; Free Action list; Simple Action list |
| ICC-11/12 — IC rating +2 | No signal when icon crashes before decker; subsequent IC test rating not elevated |

---

## Deferred Rules (Intentionally Out of Scope)

Per `design/design_core/missing.md`:

| Rule | Description |
|------|-------------|
| M-12 | Tally memory window (1D3×5 min) and jackpoint-switch tally reset |
| CC-03 | Delayed-action sync to physical-world Initiative Pass |
| CC-04 (partial) | Action resolves in physical segment even with higher Initiative slot |
| CC-18 | Evade Detection re-detection countdown and tally shortening |
| CC-25 | Legitimate passcode devalidation at logoff/jackout |
| ICC-04 | Scramble destruct test on failed Decrypt |
| ICC-11/12 | Data/storage deletion when MPCP reaches 0 |
| Various decrypt | Scramble destruct follow-up on Decrypt failure |

---

## Recommended Action Priority

1. **Fix now (correctness bugs):** CC-23, CC-32, M-05, resolveNonLethalBlackIc MPCP dice
2. **Fix soon (wrong architecture):** M-09 (tally unification), M-03 (junction-box host logon)  
3. **Design review needed:** Cyberdeck open-vs-data-class, dump-shock immuneToDumpShock in movement
4. **Add tests (high value):** CC-23/CC-32 regressions, MP-04 icon visibility, SecuritySheaf init guards, MatrixServer error paths, WebSocketDeckerController dispatch tree
5. **Known gaps (backlog):** MP-04 persistent visibility, SO-01/02 action economy, CC action budget system
