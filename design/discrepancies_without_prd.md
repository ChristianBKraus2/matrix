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

## Confirmed findings (Iterations 3–7, session 2026-09-04)

### CM-1 — `resolveBlackHammer` never sets `personaOnlyCrashed` — Low/Medium
**File:** [CombatResolver.kt](../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) (`resolveBlackHammer`, ~line 370)
combat.md ICC-13 says Black Hammer is "identical to `resolveLethalBlackIc` **except** no final MPCP attack and no `blackIcPin`." `resolveLethalBlackIc` computes `personaOnlyCrashed = newCm.isCrashed && !newPhysicalCm.isCrashed` and passes it; `resolveBlackHammer` returns `IcDamageResult(..., dumpShockTriggered)` with the field defaulting to `false`. The `+2` icon-only-crash escalation (combat.md:655) is not among the listed exceptions, so per the literal spec the flag should still be computed. Low impact because Black Hammer is a one-shot decker utility (no persistent IC state to escalate), but it is a literal-spec divergence.

### CM-2 — `resolveKilljoy` never sets `personaOnlyCrashed` — Low/Medium
**File:** [CombatResolver.kt](../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) (`resolveKilljoy`, ~line 390)
Same as CM-1 for the mental track. Should mirror `resolveNonLethalBlackIc` (`newCm.isCrashed && !newMentalCm.isCrashed`). combat.md ICC-14 lists only the no-MPCP-attack and no-`blackIcPin` exceptions.

### NAV-1 — `logonToHost` tiered guard (M-13) not explicit; throws instead of returning Failure — Medium
**File:** [DeckerNavigationExtensions.kt](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt) (`logonToHost`, `OnHost` branch)
movement.md M-13 (line 245) requires: second-tier host → sibling second-tier host returns `LogonResult.Failure` as a precondition violation (no dice). The code only does `require(loc.host.connectedHosts.contains(host))`, which (a) throws `IllegalStateException` rather than returning `LogonResult.Failure`, and (b) enforces the tiered restriction only insofar as `connectedHosts` is constructed to exclude sibling second-tier hosts. Verify how `connectedHosts` is populated for tiered topologies; if it can include siblings, the guard is entirely absent.

### CFG-1 — `DeckerLoader` omits three load-time validation rules — Medium
**File:** [DeckerLoader.kt](../src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt)
creation.md CD-01 mandates load-time validation. Not enforced anywhere: (a) each persona program rating ≤ mpcp; (b) sum of the four persona programs ≤ mpcp × 3; (d) total utility Mp ≤ `storage_memory`. Malformed YAML silently produces an invalid decker. Note: rule (c) `responseIncrease ≤ min(3, floor(mpcp/4))` IS enforced by `Cyberdeck.init` (CD-02), so only (a), (b), (d) are gaps.

### HOST-1 — `Host.init` does not enforce exactly-5 nodes — Low
**File:** [Host.kt](../src/main/kotlin/com/shadowrun/matrix/network/Host.kt)
Spec: a host has exactly 5 nodes (one per `SubsystemType`). `init` only checks that all 5 subsystem types are *covered*; a host with 6+ nodes (a duplicate subsystem type) passes validation.

### RT-1 — `reconnectToken` stored regardless of role — Low
**File:** [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) (~line 99)
`if (msg.reconnectToken) reconnectTokenRef.current = msg.reconnectToken` runs on every `ControlMessage`. Spec: store only on `registered_decker`. Harmless today (server only sends it on that role) but not defensively gated.

### UI-1 — cleanup does not null `ws.onclose` / `ws.onerror` before close — Low
**File:** [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) (useEffect cleanup)
Spec asks for `ws.onclose = null; ws.onerror = null` before `ws.close()`. Code instead guards via `isMountedRef.current = false`, which the `onclose` handler checks — functionally equivalent for reconnect suppression, but a post-unmount `onerror` can still call `close()` again (benign).

### NM-1 — Cyberdeck rating field named `mcpRating` (design term is "MPCP") — Low/cosmetic
**Files:** [Cyberdeck.kt](../src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt), [Decker.kt](../src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt), et al.
Design docs use "MPCP"; code uses `mcpRating` throughout. Consistent internally and matches the `mcpRating` wire field in protocol.md (DeckerStateDto), so this is a naming/terminology divergence only, no behavioral impact.

### NM-2 — `PersonaAttributeType.SENSORS` (plural) and variant order transposed — Low
**File:** [Enums.kt](../src/main/kotlin/com/shadowrun/matrix/common/Enums.kt)
Enum variant is `SENSORS` (plural) while `Persona.sensor` and the YAML key are singular `sensor`; order is `BOD, EVASION, MASKING, SENSORS` vs spec `BOD, EVASION, SENSOR, MASKING`. Internally consistent (loader maps `data["sensor"]` → `SENSORS`), so no functional break unless `PersonaAttributeType.ordinal` is relied upon (not observed).

### NM-3 — `AttackResult.Hit.effectivePower` vs spec field `power` — Low/cosmetic
**File:** [AttackResult.kt](../src/main/kotlin/com/shadowrun/matrix/combat/AttackResult.kt)
`Hit`'s final field is `effectivePower`; spec baseline names it `power`. Internal type, not on the wire; `effectivePower` is arguably clearer. Naming only.

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
