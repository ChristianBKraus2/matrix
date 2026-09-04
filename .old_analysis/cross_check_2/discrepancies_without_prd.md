# Discrepancies Without PRD

Design-vs-code discrepancies found during the alignment audit. Organized by prefix.

Prefix conventions: AP- ActionsPanel · AS- AnalyzeSecurity · CD- Cyberdeck ·
CM- ConditionMonitor/combat · DC- Dead code · DF- Detection factor ·
DS- Dead/stub field · DU- Download/upload · DUP- Duplication · EP- EntitiesPanel ·
GC- GameContext · GL- Game loop · GM- game.md · GR- Grid · IC- IC behavior ·
IM- INVOKE_MEDIC · INT- Interrogation · MC- Missing/coverage · MS- missing.md ·
NM- Naming mismatch · NFR- Non-functional · OP- Operations · PB- Probe ·
PG- PrivateGrid · PR- Protocol · RL- Relocate icon · RT- Reconnect token ·
SD- Shadowing · TS- TypeScript type · TRK- Track/lock · UI- UI component · UP- Upload

---

## Status summary (2026-09-04)

- **Fixed (code), verified green (`test` + `integrationTest`):** CM-1, CM-2, CFG-1, CFGH-1, RT-1, UI-1.
- **Resolved by design-owner decision (docs/spec only, no code change):** NAV-1, NM-1, NM-2, NM-3.
- **Retracted (false positive):** HOST-1.
- **Deferred:** grid `security_sheaf` → deferred.md #14; dormant game-loop bugs D4G-3/D4G-4 → deferred.md #1.

No open findings remain.

---

## Confirmed findings (Iterations 3–7, session 2026-09-04)

### CM-1 — `resolveBlackHammer` never sets `personaOnlyCrashed` — Low/Medium
**File:** [CombatResolver.kt](../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) (`resolveBlackHammer`, ~line 370)
combat.md ICC-13 says Black Hammer is "identical to `resolveLethalBlackIc` **except** no final MPCP attack and no `blackIcPin`." `resolveLethalBlackIc` computes `personaOnlyCrashed = newCm.isCrashed && !newPhysicalCm.isCrashed` and passes it; `resolveBlackHammer` returns `IcDamageResult(..., dumpShockTriggered)` with the field defaulting to `false`. The `+2` icon-only-crash escalation (combat.md:655) is not among the listed exceptions, so per the literal spec the flag should still be computed. Low impact because Black Hammer is a one-shot decker utility (no persistent IC state to escalate), but it is a literal-spec divergence.
**RESOLVED (2026-09-04):** `resolveBlackHammer` now computes and passes `personaOnlyCrashed = newCm.isCrashed && !newPhysicalCm.isCrashed`.

### CM-2 — `resolveKilljoy` never sets `personaOnlyCrashed` — Low/Medium
**File:** [CombatResolver.kt](../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) (`resolveKilljoy`, ~line 390)
Same as CM-1 for the mental track. Should mirror `resolveNonLethalBlackIc` (`newCm.isCrashed && !newMentalCm.isCrashed`). combat.md ICC-14 lists only the no-MPCP-attack and no-`blackIcPin` exceptions.
**RESOLVED (2026-09-04):** `resolveKilljoy` now computes and passes `personaOnlyCrashed = newCm.isCrashed && !newMentalCm.isCrashed`.

### NAV-1 — `logonToHost` tiered guard (M-13) not explicit; throws instead of returning Failure — Medium
**File:** [DeckerNavigationExtensions.kt](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt) (`logonToHost`, `OnHost` branch)
movement.md M-13 (line 245) requires: second-tier host → sibling second-tier host returns `LogonResult.Failure` as a precondition violation (no dice). The code only does `require(loc.host.connectedHosts.contains(host))`, which (a) throws `IllegalStateException` rather than returning `LogonResult.Failure`, and (b) enforces the tiered restriction only insofar as `connectedHosts` is constructed to exclude sibling second-tier hosts. Verify how `connectedHosts` is populated for tiered topologies; if it can include siblings, the guard is entirely absent.
**RESOLVED (2026-09-04, design owner): throwing is OK.** Enforcing the M-13 guard via `connectedHosts` membership + `IllegalStateException` is accepted as-is; movement.md M-13 was reworded to match (precondition violation, not `LogonResult.Failure`). No code change. See rationale/options below.

### CFG-1 — `DeckerLoader` omits three load-time validation rules — Medium
**File:** [DeckerLoader.kt](../src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt)
creation.md CD-01 mandates load-time validation. Not enforced anywhere: (a) each persona program rating ≤ mpcp; (b) sum of the four persona programs ≤ mpcp × 3; (d) total utility Mp ≤ `storage_memory`. Malformed YAML silently produces an invalid decker. Note: rule (c) `responseIncrease ≤ min(3, floor(mpcp/4))` IS enforced by `Cyberdeck.init` (CD-02), so only (a), (b), (d) are gaps.
**RESOLVED (2026-09-04):** added three `require(...)` checks to `DeckerLoader.buildCyberdeck` for (a), (b — `<=`, so the boundary case sum == mpcp×3 passes), and (d). Verified headcrash.yaml satisfies all three (its own comments confirm 6≤8, 24≤24, 374≤2000).

### HOST-1 — `Host.init` does not enforce exactly-5 nodes — RETRACTED (not a discrepancy)
**File:** [Host.kt](../src/main/kotlin/com/shadowrun/matrix/network/Host.kt)
Original claim: a host should have exactly 5 nodes (one per `SubsystemType`), but `init` only checks all types are *covered*. **Retracted (2026-09-04):** this was a spec-baseline misreading. `NetworkTest."Host allows multiple nodes of the same subsystem type"` (NetworkTest.kt:130) explicitly builds a 6-node host with two `FILES` nodes ("Archive A"/"Archive B") and asserts it is allowed — multiple nodes per subsystem type (e.g. several file archives) is an intended feature, **confirmed by the design owner: "a host might have more than 5 nodes."** The real invariant is "at least one node per type," which the existing `require(coveredTypes == entries.toSet())` correctly enforces. An exactly-5 check was applied, broke that test, and has been reverted. No code change stands.

### CFGH-1 — `HostLoader` silently drops extra same-type nodes — RESOLVED
**File:** [HostLoader.kt](../src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt)
Surfaced while retracting HOST-1. `HostLoader.buildFromMap` set `nodes = nodesByType.values.toList()`, collapsing the node list to one per subsystem type (keeping `.first()`), which contradicts the domain model, `NetworkTest.kt:130`, and the design owner's clarification that a host may hold more than 5 nodes. This was pass-1 finding **D7C-7** over-correcting against ord.md's then-stated "exactly five Nodes."
**RESOLVED (2026-09-04):** the loader now passes the full `nodes` list to `Host`; `nodesByType` is retained only as a first-of-type lookup for IC / security-sheaf `guarded_node` resolution (the misleading "duplicate subsystem types" warning was removed). Also corrected the stale design doc: [ord.md](design_core/ord.md) `Host → Node` cardinality changed from `1:5` / "exactly five Nodes" to `1:5..*` / "at least five … may hold additional nodes of the same type" (text + both Mermaid diagrams). Note: `guarded_node` YAML still binds an IC to the *first* node of a given type — it cannot yet target a specific archive among duplicates; recorded as a known limitation, not a regression.

### RT-1 — `reconnectToken` stored regardless of role — Low
**File:** [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) (~line 99)
`if (msg.reconnectToken) reconnectTokenRef.current = msg.reconnectToken` runs on every `ControlMessage`. Spec: store only on `registered_decker`. Harmless today (server only sends it on that role) but not defensively gated.
**RESOLVED (2026-09-04):** guard tightened to `if (msg.role === 'registered_decker' && msg.reconnectToken)`.

### UI-1 — cleanup does not null `ws.onclose` / `ws.onerror` before close — Low
**File:** [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) (useEffect cleanup)
Spec asks for `ws.onclose = null; ws.onerror = null` before `ws.close()`. Code instead guards via `isMountedRef.current = false`, which the `onclose` handler checks — functionally equivalent for reconnect suppression, but a post-unmount `onerror` can still call `close()` again (benign).
**RESOLVED (2026-09-04):** the useEffect cleanup now nulls `ws.onclose` and `ws.onerror` before calling `ws.close()`.

### NM-1 — Cyberdeck rating field named `mcpRating` (design term is "MPCP") — Low/cosmetic
**Files:** [Cyberdeck.kt](../src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt), [Decker.kt](../src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt), et al.
Design docs use "MPCP"; code uses `mcpRating` throughout. Consistent internally and matches the `mcpRating` wire field in protocol.md (DeckerStateDto), so this is a naming/terminology divergence only, no behavioral impact.
**RESOLVED (2026-09-04, design owner): keep `mcpRating`.** Documented in prd_core.md and spec_baseline.md. No code change. See rationale/options below.

### NM-2 — `PersonaAttributeType.SENSORS` (plural) and variant order transposed — Low
**File:** [Enums.kt](../src/main/kotlin/com/shadowrun/matrix/common/Enums.kt)
Enum variant is `SENSORS` (plural) while `Persona.sensor` and the YAML key are singular `sensor`; order is `BOD, EVASION, MASKING, SENSORS` vs spec `BOD, EVASION, SENSOR, MASKING`. Internally consistent (loader maps `data["sensor"]` → `SENSORS`), so no functional break unless `PersonaAttributeType.ordinal` is relied upon (not observed).
**RESOLVED (2026-09-04, design owner): keep `SENSORS`.** Documented in prd_core.md; spec_baseline.md updated to the code's name and order. No code change. See rationale/options below.

### NM-3 — `AttackResult.Hit.effectivePower` vs spec field `power` — Low/cosmetic
**File:** [AttackResult.kt](../src/main/kotlin/com/shadowrun/matrix/combat/AttackResult.kt)
`Hit`'s final field is `effectivePower`; spec baseline names it `power`. Internal type, not on the wire; `effectivePower` is arguably clearer. Naming only.
**RESOLVED (2026-09-04, design owner): keep `effectivePower`.** spec_baseline.md updated to match. No code change. See rationale/options below.

---

## Considered but NOT flagged (adversarial check)

- **`DownloadHandle.destination` field** — explicitly covered by deferred.md #6; not a discrepancy.
- **`securityRating: SecurityRating` bundling code+value on Grid/Host** — the design consolidates Security Code + Security Value; acknowledged in the spec baseline ("securityRating (code + value)"). Not a divergence.
- **`SystemOperation` has 29 entries** — the `LOGON_TO_RTG/LTG/HOST` operations are legitimate; the "26" reference figure was an incomplete enumeration. No extras.
- **`Program.name` / `Program.multiplier`, `Utility.type`** — structurally required to compute `mpSize` and dispatch; not spec violations.
- **Single `ATTACK` UtilityType with computed multiplier** — numerically equivalent to the four discrete attack tiers (LIGHT=2…DEADLY=5); implementation choice.
- **`SecuritySheaf.triggerSteps` vs `triggers`** — spec text was "(or similar)"; naming only.
- **`effectiveDetectionFactor` floor of 2** — enforced in [Decker.kt:67](../src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt#L67) (`maxOf(2, detectionFactor - suppressionDfPenalty)`). No gap (STR-1 retracted).
- **`AvailableActionDto.actionType` extra wire field** — protocol.md lists `actionType` on every AvailableActionDto variant; present as specified.
- **Server layer does not pre-filter `SWAP_MEMORY`/`LOCATE_DECKER`** — exclusion is the domain layer's responsibility (`availableActions()`); server trusts it. Deferred #2/#3.
- **`UtilityDto` Kotlin class name (vs `ActiveUtilityDto`)** — wire field is `activeUtilities`; internal type name is irrelevant to conformance.

---

## Design-owner decisions — rationale and options (resolved 2026-09-04)

The following pass-2 findings were **not** auto-fixed because the correct resolution was a design/naming decision, not a mechanical change. Each records why, the options weighed, and the decision taken. (Two other still-open items were routed to `deferred.md` instead: grid `security_sheaf` → deferred #14; the dormant game-loop bugs D4G-3/D4G-4 → deferred #1.) All four were resolved by the design owner on 2026-09-04 — see the per-item resolution lines.

### NAV-1 — `logonToHost` M-13 tiered guard: exception vs `LogonResult.Failure`
**Why not auto-fixed:** movement.md M-13 prose says return `LogonResult.Failure` for a sibling-second-tier hop, but the test-vector row calls it a "precondition violation returned," and *every* other precondition in `logonToHost` (and the sibling `logon*` methods) is enforced with `require(...)` → `IllegalStateException`. The current code (`require(loc.host.connectedHosts.contains(host))`) already rejects the hop **iff** `connectedHosts` is built to exclude sibling second-tier hosts — which [GridLoader.wireHostConnections] controls. So the "bug" is really two open questions only the design owner can settle:
1. Should precondition violations in `logonToHost` return `Failure` (per M-13 prose) or throw (per the established pattern and the test vector's wording)? Changing only M-13 to return `Failure` makes the method internally inconsistent.
2. Does `connectedHosts` already model the tiered restriction (making the guard redundant), or can it contain siblings (making it a real gap)?
**Options:** (A) leave as-is and reword movement.md M-13 to say "precondition violation (exception)" — cheapest, if `connectedHosts` already excludes siblings; (B) add an explicit second-tier sibling check that returns `LogonResult.Failure`, and change the other `require` preconditions to match for consistency — larger, needs a first-tier/second-tier tier model on `Host` that does not exist today; (C) verify `connectedHosts` construction and, if it can include siblings, fix the loader wiring (smallest behavioral fix, leaves the throw-vs-Failure question to the docs).
**RESOLVED (2026-09-04, design owner): throwing is OK — Option A.** movement.md M-13 reworded: the guard is enforced by `connectedHosts` membership and throws `IllegalStateException` (a precondition violation, consistent with the other `logon*` preconditions), not `LogonResult.Failure`. No code change.

### NM-1 — `mcpRating` vs design term "MPCP"
**Why not auto-fixed:** renaming `mcpRating` → `mpcp` would **diverge from the wire contract** — protocol.md's `DeckerStateDto` field is literally `mcpRating`, and the code is internally consistent. This is a terminology choice, not a defect.
**Options:** (A) keep `mcpRating` and note in a glossary that it denotes the SR3 "MPCP" — recommended, zero risk; (B) rename the domain field to `mpcp` *and* change the wire field + frontend type + all tests — large churn, breaks the current protocol string; only worth it if the wire name is also being revised.
**RESOLVED (2026-09-04, design owner): keep `mcpRating` — Option A.** Documented in prd_core.md ("MPCP Rating is represented in the domain model and wire protocol by the field name `mcpRating`; the config YAML key is `mpcp`") and in spec_baseline.md. No code change.

### NM-2 — `PersonaAttributeType.SENSORS` (plural) + order transposed
**Why not auto-fixed:** purely cosmetic and internally consistent (loader maps YAML `sensor` → `SENSORS`; `Persona.sensor` is singular). No `PersonaAttributeType.ordinal` use exists (only `DamageLevel.ordinal`), so a rename/reorder is *safe*, but it touches ~27 files and the canonical spelling is a design call (SR3 uses singular "Sensor").
**Options:** (A) rename `SENSORS` → `SENSOR` and reorder to `BOD, EVASION, SENSOR, MASKING` to match the spec, compiler-checked, safe but ~27-file churn — recommended if aligning to SR3 nomenclature; (B) keep `SENSORS` and update the spec baseline to record the plural as intended — zero code churn.
**RESOLVED (2026-09-04, design owner): keep `SENSORS` — Option B.** Documented in prd_core.md (sensor attribute is `PersonaAttributeType.SENSORS`, plural; YAML key singular `sensor`) and updated spec_baseline.md to the code's name and order. No code change.

### NM-3 — `AttackResult.Hit.effectivePower` vs spec field `power`
**Why not auto-fixed:** the code pairs `rawWeaponPower` + `effectivePower`, which is arguably *clearer* than the spec baseline's `power`, and the type is internal (never serialized). Renaming to `power` would lose the raw/effective distinction. The "spec" here is the paraphrased baseline, not a PRD clause.
**Options:** (A) keep `effectivePower` and update the spec baseline to match the clearer name — recommended; (B) rename `effectivePower` → `power` across `AttackResult`, `CombatResolver`, and the combat tests to match the baseline verbatim — cosmetic, mild clarity loss.
**RESOLVED (2026-09-04, design owner): keep `effectivePower` — Option A.** spec_baseline.md updated to `Hit(..., rawWeaponPower, effectivePower)`. No code change.
