# Pass 5 — Test Coverage Audit

---

## PRD Rule Coverage Gaps

Rules with no test or insufficient test coverage in the current test suite.

| Rule | Description | Suggested Test |
|------|-------------|----------------|
| M-03 | Illegal junction-box jackpoint routing to LTG or Host depending on trunk connection | MovementTest: fixture with ILLEGAL_JUNCTION_BOX; verify both LTG and Host branches accepted, others rejected |
| M-12 | Failed logon leaves tally on LTG for memory window; jackpoint switch resets tally | MovementTest: (1) failed logon → tally stays accumulated; (2) same logon from different jackpoint → tally starts fresh |
| M-13 | Tiered topology: second-tier→sibling-second-tier direct move forbidden; must re-enter first-tier | MovementTest: tiered topology fixture; assert sibling logon throws; back-to-first-tier logon succeeds |
| M-14 | HOST_HOST chain: traversal must be in order; no shortcuts | MovementTest: 3+ host chain; non-adjacent logon throws; in-order traversal succeeds |
| M-18 | Involuntary disconnection (persona CM full) → dump shock, persona/location cleared | MovementTest or CombatResolverTest: fill CM to 10 boxes; assert dumpShock=true and persona/location cleared |
| CD-19 | Armor utility currentRating decrements by 1 when damage bleeds through to persona CM | CombatResolverTest: load Armor utility; call applyIcDamage with Power > armorRating; assert returnedDecker Armor.currentRating decremented |
| ACC-03 | HitcherObserver behavioral constraints: cannot alter persona, cannot control movements, no biofeedback | CyberdeckAndProgramMechanicsTest: Black IC damage with hitcher present → hitcher state unchanged; no modify-persona API on HitcherObserver |
| MP-04 | Located icon remains visible across turns unless Evade Detection succeeds | DeckerOperationsTest or integration: noticeIcon returns Detected; advance turn without evade; assert icon still in visible-icons set |
| MP-10 | Target decker is notified when Locate Decker succeeds | DeckerOperationsTest: call locateDecker; assert target Decker has notification flag/message set |
| SO-09 | Locate File on pointer-bearing file returns pointer (pointerToHost non-null), not direct data | SystemOperationsTest: DataFile with isPointer=true; accumulate ≥5 successes; assert LocateResult.Located contains pointer file with pointerToHost non-null |
| SO-12 | Aborting an ongoing download before completion produces a corrupted/unusable partial file | SystemOperationsTest or integration: start downloadData; abort mid-transfer; assert DataFile marked corrupted |
| CC-08 | IC triggered mid-turn loses 10 Initiative × completed Passes before acting | CombatResolverTest: rollIcInitiative with completedPasses param; assert score reduced by 10×completedPasses |
| CC-18 | Evade Detection duration = net successes; each tally point added while evading decrements duration | CombatResolverTest: (1) successful evade → evasionTurnsRemaining == netSuccesses; (2) Probe adds tally → evasionTurnsRemaining decrements |
| CC-25 | Legitimate passcode devalidated at logoff/jackout after being used against host's own IC | CombatResolverTest or DeckerOperationsTest: personaStatus=LEGITIMATE, attack host IC, then logoff → passcode invalid / status reverts to INTRUDING |
| AL-02 | Active Alert trigger spawns NPC security deckers into host as combatants | Integration AlertAndTallyTest or ICActivationTest: tally crosses Active Alert threshold on host with securityDeckerCount>0; assert new decker persona in GameContext icons |

---

## Untested File Behaviours

### server/MatrixServer.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| Unknown message type → UNKNOWN_MESSAGE_TYPE ErrorMessage | WebSocketServerIntegrationTest: send frame `{"type":"bogus"}`; assert ErrorMessage.UNKNOWN_MESSAGE_TYPE response |
| Malformed JSON frame → BAD_REQUEST ErrorMessage | WebSocketServerIntegrationTest: send `"not-json"`; assert BAD_REQUEST ErrorMessage |
| MAX_CONNECTIONS exceeded → SERVER_FULL + close | Integration test: open MAX_CONNECTIONS+1 connections; assert last one receives SERVER_FULL and closes immediately |

### server/WebSocketDeckerController.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| conductTurn action timeout → broadcasts "timed out" | WebSocketServerTest: register decker, start conductTurn with 1s timeout, send no action, assert broadcast contains "timed out" |
| Successful action dispatch (entire dispatchXxx tree unreached) | WebSocketServerTest: decker in host with ≥1 available action; send correct actionIndex; assert ResultMessage success=true |
| dispatchCommsOp (MAKE_COMCALL, TAP_COMCALL) | Extend successful-dispatch test: host offers MAKE_COMCALL; assert result dispatched and broadcast correctly |
| JackOut when pinned by Black IC → failure message | WebSocketServerTest: decker with blackIcPin set + JackOut action available; assert conductTurn broadcasts pinned failure |

### decker/DeckerMemoryExtensions.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| advanceCombatTurn depletion: currentRating=0 → removed from activeUtilities and storedUtilities | Unit test: place utility with currentRating=0 in activeUtilities; call advanceCombatTurn; assert utility absent from both lists |

### decker/DeckerNavigationExtensions.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| gracefulLogoff with active Track state → increased TN | MovementTest: decker with trackState.trackingIcRating>0; call gracefulLogoff; verify Track penalty affects test outcome |
| logonToLtg from OnPLTG source (M-08) | MovementTest: decker on PLTG; logonToLtg to reachable LTG; assert Success result |

### network/Matrix.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| getHost via PLTG branch (RTG→LTG→PLTG→Host) | NetworkTest: build RTG→LTG→PLTG→Host chain; assert matrix.getHost returns correct host via PLTG lookup |
| getRTG / getLTG / getHost returns null for unknown names | NetworkTest: call each with non-existent name; assert null returned |

### network/SecuritySheaf.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| init rejects duplicate tallyThreshold values | NetworkTest or SecuritySheafTest: two TriggerStep entries with same tallyThreshold; assert IllegalArgumentException |
| init rejects out-of-order thresholds | NetworkTest: TriggerStep entries in descending threshold order; assert IllegalArgumentException |

### config/HostLoader.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| buildIc: unknown IC type → IllegalStateException | HostLoaderTest: YAML with `type: unknownic`; assert error thrown |
| parseSecurityRating: invalid format (no dash) → IllegalArgumentException | HostLoaderTest: `security: 'INVALID'`; assert IllegalArgumentException |

### config/DeckCatalogLoader.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| load: missing 'decks' key → error | DeckCatalogLoaderTest: YAML `{other: value}`; assert IllegalStateException "missing 'decks' key" |

### config/ConfigUtils.kt

| Behaviour | Suggested Test |
|-----------|----------------|
| parseSubsystemRatings: null input → IllegalArgumentException | HostLoaderTest: buildFromMap with no 'ratings' key; assert IllegalArgumentException with "subsystem ratings map is required" |
