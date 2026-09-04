# Iteration 3 — Domain Model Audit: `src/main/kotlin/com/shadowrun/matrix/combat/` (Kotlin data/sealed types)

Scope: all `combat/*.kt` domain types **except** `CombatResolver.kt` (later iteration).
Baseline: `design/audit/iter2_combat.md` → "Distilled spec additions → Domain types",
`design/audit/spec_baseline.md` §Combat, cross-checked against `design/design_core/combat.md`.
Every file read in full from line 1 via the Read tool this session. All files ≤ 100 lines → one verbatim excerpt each (Rule 2).

## Coverage table

| File | Lines | Verbatim excerpt (proves full read) | Notes |
|---|---|---|---|
| `AttackParticipant.kt` | 11 | `    val weaponPower: Int = attackDicePool,` | Fields + defaults match spec (`attackDicePool`, `weaponPower=attackDicePool`, `hackingPool=0`, `rawDamageLevel`, `modifiers=CombatModifiers()`). No discrepancy. |
| `AttackResult.kt` | 17 | `        val rawWeaponPower: Int,\n        val effectivePower: Int` | **D3C-1** — fifth `Hit` field named `effectivePower`; design doc `combat.md:46` names it `power`. |
| `BlackIcPinState.kt` | 5 | `data class BlackIcPinState(val pinningIc: BlackIC)` | Single field `pinningIc: BlackIC` matches spec (line 25 / combat.md:152). No discrepancy. |
| `Combat.kt` | 21 | `    val level: DamageLevel get() = when (securityRating.code) {` | Holds `CombatTurn(number=1)`, `CombatManeuver(type)`, `DumpShock`. `DumpShock.power = securityRating.value`; level BLUE→LIGHT, GREEN→MODERATE, ORANGE→SERIOUS, RED→DEADLY matches spec_baseline L49 / combat.md:354. No discrepancy. |
| `CombatInitiative.kt` | 6 | `    val initiativePasses: Int` | `score`, `initiativePasses` match spec (line 18 / combat.md:25). No discrepancy. |
| `CombatModifiers.kt` | 13 | `require(positionAttackTnBonus == 0 \|\| positionAttackPowerBonus == 0) {` | Three fields all default 0; `init` enforces TN-XOR-Power (not both). Matches combat.md:130-140 verbatim (require + message). No discrepancy. |
| `CripplerResult.kt` | 10 | `    val targetAttribute: PersonaAttributeType,` | `updatedDecker`, `targetAttribute`, `reduction` match spec (line 28 / combat.md:213). No discrepancy. |
| `DefenderParticipant.kt` | 11 | `    val armorCurrentRating: Int = 0,` | `bod`, `armorCurrentRating=0`, `personaStatus`, `securityCode` match spec (line 23 / combat.md:113). No discrepancy. |
| `IcDamageResult.kt` | 14 | `    val personaOnlyCrashed: Boolean = false` | All 6 fields + defaults match spec (line 26 / combat.md:179): incl `mpcpReductionOnKill=0`, `personaOnlyCrashed=false`. No discrepancy. |
| `IcSuppressionState.kt` | 13 | `    val icRating: Int` | `ic: IC`, `icRating: Int` match spec (line 32 / combat.md:277). No discrepancy. |
| `JackOutPinResult.kt` | 6 | `    val finalIcAttackTriggered: Boolean` | `succeeded`, `finalIcAttackTriggered` match spec (line 25 / combat.md:164). No discrepancy. |
| `ManeuverParticipant.kt` | 9 | `    val cloakRating: Int = 0,` | `evasion`, `sensor`, `cloakRating=0`, `lockOnRating=0`, `hackingPool=0` match spec (line 21 / combat.md:77). No discrepancy. |
| `ManeuverResult.kt` | 6 | `    data class Success(val netSuccesses: Int) : ManeuverResult()` | `Success(netSuccesses)` + `Failure` object match spec (combat.md:62). No discrepancy. |
| `SimsenseOverloadResult.kt` | 6 | `    val willpowerTestPassed: Boolean,` | `willpowerTestPassed`, `stressBoxesApplied` match spec (line 27 / combat.md:198). No discrepancy. |
| `SlowResult.kt` | 6 | `data class SlowResult(\n    val actionsLost: Int,` | `actionsLost`, `icInert` match spec (line 31 / combat.md:262). No discrepancy. |
| `TarBabyResult.kt` | 9 | `    val deckerNoticed: Boolean` | `updatedDecker`, `bothCrashed`, `deckerNoticed` match spec (line 29 / combat.md:229). No discrepancy. |
| `TrackState.kt` | 8 | `    val trackerMcpRating: Int` | Four fields `trackingIcRating`, `locationCycleTurnsRemaining`, `opponentSensorRating`, `trackerMcpRating` match spec (line 30 / combat.md:245). No discrepancy. |

Total files read: **17**. Total lines: **171**. Findings: **1**.

## Findings

### D3C-1 — `AttackResult.Hit` fifth field named `effectivePower`, design doc names it `power`

**Design:** `design/design_core/combat.md:40-53` specifies the data class verbatim with
`val power: Int` as the fifth field, with prose "`power` is the effective power after armor
reduction (`max(0, rawWeaponPower - armorRating)`)". The distilled spec (`iter2_combat.md:19`)
likewise lists `AttackResult.Hit(attackerSuccesses, rawDamageLevel, stagedDamageLevel, rawWeaponPower, power)`.

**Code:** `src/main/kotlin/com/shadowrun/matrix/combat/AttackResult.kt:9-10`:
```kotlin
        val rawWeaponPower: Int,
        val effectivePower: Int
```
The field is named `effectivePower`, not `power`.

**Violated clause:** Per-File Checklist bullet 1 ("Every field name matches the design doc —
case, pluralization, abbreviation") and Rule 9 (data-class field completeness/exact-name).
Semantic intent is identical (post-armor power), so this is a naming-mismatch (NM-class)
discrepancy, not a behavioural one — no runtime impact within the domain layer, but the design
doc and code disagree on the field name. Fix: rename either the code field to `power` or update
`combat.md:46`/`combat.md:53` to `effectivePower` (code-correct / doc-stale is the likely
resolution given `effectivePower` is the more descriptive name).

_Note: DOC-11 (`iter2_combat.md`) already flags `IcDamageResult.personaOnlyCrashed` as
"never set/read" at the design-doc level; the field IS present in code (`IcDamageResult.kt:13`)
with the correct default, so it is spec-conformant as a domain type and raises no D3C finding here._
