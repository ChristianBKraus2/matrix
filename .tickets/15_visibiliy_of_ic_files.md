# 15 Visibility of IC and Files

## Issue

When the decker enters a host the contained IC, Files, etc. are immediately visible. They must be located first. Double check this statement against extracted_text.txt.

## Clarification (from discussion with the user)

- The SR3 rules (`extraction/extracted_text.txt`) confirm the report: the free Sensor Test for "Noticing New Icons" (p. 215) fires only when a *new* icon **enters the area the decker occupies**. On logon the decker is the newcomer, so resident IC is **not** auto-detected — it "must first be located" (p. 215, "Noticing Triggered IC"). Locate IC (p. 217) "automatically locates the IC program(s) if the System Test succeeds — no Sensor Test." Analyze Subsystem (p. 216) identifies guarding Scramble IC.
- During testing the user found that Locate IC revealed nothing and that a triggered Probe appeared twice. Investigation showed the host YAML declares IC in two independent pools — always-resident `ic_programs` and tally-triggered `activated_ic`. The user confirmed the intent: a resident Probe (rating 1) present from the start **plus** a distinct triggered Probe (rating 5) at the first threshold. The resident Probe must be revealed by **Locate IC**; the triggered Probe appears via spawn detection; the two must be tracked as **distinct** icons.

## Solution

### Chosen approach

Resident IC, files, and devices are hidden on host entry and revealed only when located; IC visibility is tracked by icon identity rather than by name.

- **`Decker.visibleObjects()`** ([decker/Decker.kt](../src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt)) — files/devices are filtered to `locatedFiles` / `locatedSlaves`; IC is now filtered by **identity** (`IC.matchesIdentity`) against `detectedIcons` instead of by name, and the resident `host.icPrograms` ∪ `activeIc` union is de-duplicated by identity so an IC present in both pools renders once.
- **`jackInToHost` / `logonToHost`** ([decker/DeckerNavigationExtensions.kt](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt)) — removed the entry-time `noticeIcon` loop that auto-detected all resident IC on logon.
- **`Decker.locateIc(host, activeIc)`** ([decker/DeckerOperationsExtensions.kt](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt)) — on a successful System Test it now auto-locates (no Sensor Test) every IC present on the host, resident plus triggered, adding them to `detectedIcons`. `context.activeIc` is threaded through the controller dispatch chain ([server/WebSocketDeckerController.kt](../src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt)).
- **`Decker.analyzeSubsystem()`** — on success, any Scramble IC guarding the targeted subsystem is added to `detectedIcons`, so it becomes visible.
- Docs updated: PRD MP-11 / MP-12 and the Analyze Subsystem row ([design/prd_core.md](../design/prd_core.md)), the "Decker State — IC Visibility" section ([design/prd_game.md](../design/prd_game.md)), and the Entities Panel section of the [player guide](../documentation/player_guide.md).

### Options considered but not taken

- **Reveal only triggered `activeIc` via Locate IC (dormant residents never shown)** — rejected because the user's confirmed design keeps a genuinely resident Probe that must be discoverable by Locate IC, not merely reinforcements from trigger steps.
- **Keep name-based IC filtering and simply de-duplicate by name** — rejected: it would collapse the *distinct* resident Probe (r1) and triggered Probe (r5) into one and still leak one when the other was detected. Identity-based filtering keeps distinct IC separate while merging truly identical ones.
- **Promote resident `icPrograms` into `activeIc` when triggered (single pool)** — rejected as out of scope; it changes the trigger/activation model and combat wiring for a fix that is fundamentally about *visibility*.
- **Gate `ANALYZE_IC` / `ANALYZE_ICON` actions to located IC only** — deferred; the available-action list still offers Analyze for resident IC. Tightening it is a separate concern and would require reworking integration tests that assume those actions are always present.
