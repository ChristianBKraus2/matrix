# Iteration 3 — Decker domain model conformance audit

Scope: `src/main/kotlin/com/shadowrun/matrix/decker/` DOMAIN types only.
`*Extensions.kt` (DeckerMemoryExtensions, DeckerNavigationExtensions, DeckerOperationsExtensions)
are business-logic files and were SKIPPED per assignment. Every listed file read in full via `Read`
from line 1 to last line in this session.

## Coverage table

| File | Lines | Verbatim excerpt(s) | Notes |
|---|---|---|---|
| `ActiveMemory.kt` | 20 | L5-7 `data class PendingUpload(` / `val utility: Utility,` / `val turnsRemaining: Int` ; L15-19 `data class InsufficientMemory(` … `val requiredMp: Int,` `val availableMp: Int` | `PendingUpload` + sealed `LoadUtilityResult{Success, InsufficientMemory}` match iter2 distill (CD-07/08). Pure data carriers; no formulas. No findings. |
| `Cyberdeck.kt` | 92 | L34 `val maxResponseIncrease: Int get() = minOf(3, mcpRating / 4)` ; L73-74 `val activeMp = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }` `require(activeMp <= activeMemoryMp)` ; L85-89 `fun detectionFactor(maskingRating: Int, sleazeRating: Int? = null): Int = if (sleazeRating != null) ceil((maskingRating + sleazeRating) / 2.0).toInt() else ceil(maskingRating / 2.0).toInt()` | RI clamp = min(3, floor(MPCP/4)) ✓ (CD-02). init: rating≤MPCP, Σ≤MPCP×3, activeMp(incl pending)≤activeMemoryMp, storageMp≤storageMemoryMp ✓. DF formula ceil((M+S)/2) or ceil(M/2) ✓ (CD-18). No `model` field (uses `name`) — see D3D-2. |
| `Cyberterminal.kt` | 49 | L33 `require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01), got $mcpRating" }` ; L41 `responseIncrease = 0,` ; L47 `isCyberterminal = true` | Factory (not subclass). MPCP≤4 (CT-01) ✓, RI forced 0 (CT-02) ✓, isCyberterminal=true ✓. −1 utility rating (CT-03) correctly deferred to resolver (documented L15-16), not stored here. No findings. |
| `Decker.kt` | 232 | L60 `val hackingPool: Int get() = (intelligence + cyberdeck.mcpRating) / 3` ; L67 `val effectiveDetectionFactor: Int get() = maxOf(2, detectionFactor - suppressionDfPenalty)` ; L221 `internal fun withUpdatedTally(hostSuccesses: Int): Decker {` | Hacking Pool floor((Int+MPCP)/3) ✓. effectiveDF = max(2, DF−penalty) ✓ (CD-18a); suppressionDfPenalty = suppressedIc.size (CC-22) ✓. detectionFactor getter reads live Sleaze `currentRating` from activeUtilities (excludes pending) ✓, masking from persona?.masking→program rating fallback ✓. actionsPerTurn = ceil(reaction/10)+RI ✓ (SO-01/02). No findings on calculated fields. |
| `DownloadDestination.kt` | 10 | L6-9 `sealed class DownloadDestination {` `object ActiveMemory` `object StorageMemory` `data class OfflineStorage(val accessory: Accessory.OfflineStorage)` | Sealed variants match design (ACC-01). Type is currently unused (DownloadHandle carries no destination) per iter2 L70 — see D3D-3. |
| `MedicResult.kt` | 12 | L8-12 `data class MedicResult(` `val updatedDecker: Decker,` `val boxesRepaired: Int,` `val medicRating: Int` | Fields match iter2 distill; `medicRating` = currentRating AFTER mandatory decrement (CD-20). Doc comment cites wrong PRD refs — see D3D-1. |
| `MovementResult.kt` | 24 | L5-8 `sealed class LogonResult {` `data class Success(val decker: Decker, val location: MatrixLocation, val deckerSuccesses: Int = 0, val hostSuccesses: Int = 0)` ; L18-23 `sealed class LogoffResult {` `data class GracefulSuccess` … `data class JackOut(val decker: Decker, val dumpShock: Boolean)` | LogonResult{Success,Failure} + LogoffResult{GracefulSuccess,JackOut}. deckerSuccesses/hostSuccesses always present (protocol ResultMessage). No findings. |
| `Persona.kt` | 38 | L8-11 `data class Persona(` `val bod: Int,` `val evasion: Int,` `val masking: Int,` `val sensor: Int,` ; L25-30 `fun attribute(type: PersonaAttributeType): Int = when (type) { … PersonaAttributeType.SENSORS -> sensor }` | Bod/Evasion/Masking/Sensor fields ✓. `reaction` stored (base+RI×2 computed at load, default 0) — formula not applied in this domain type; see D3D-4. attribute()/withAttribute() enum mapping complete. |

Excerpt spacing: Decker.kt (232 lines, 101-300 tier) has excerpts at L60 and L221 (>30 lines apart, opening & closing thirds); L67 added as finding-adjacent. All ≤100-line files carry ≥1 verbatim excerpt.

## Findings

**D3D-1 — MedicResult doc comment cites wrong PRD clauses.**
`MedicResult.kt` L4: `* Result of invoking the Medic utility (CC-22 / CD-26).`
Medic degradation and repair are governed by CD-20 (Medic −1 currentRating per invocation, spec_baseline L36; iter2_cyberdeck L52). CC-22 is IC-suppression DF penalty and CD-26 is the deck-catalog unknown-model loader rule — neither concerns Medic. Doc-comment/citation only; code behaviour (`medicRating` = post-decrement rating, L5-6) is correct. Classify: code-quality (stale comment reference).

**D3D-2 — Cyberdeck has no `model` field; stores `name` instead.**
`Cyberdeck.kt` L12-13: `val name: String,` `val mcpRating: Int,`. The design catalog resolution (creation.md L94, DeckCatalogEntry `model`, iter2_cyberdeck L18/L59) keys on a `model:` field; the domain type exposes only `name`. The model→name mapping is implicit and never named on the domain object. Low severity / naming — the value is carried, only the field name differs; no data lost. Classify: naming (NM), document only.

**D3D-3 — DownloadDestination sealed class is defined but unused.**
`DownloadDestination.kt` L6-9 defines `ActiveMemory`/`StorageMemory`/`OfflineStorage`, but per iter2_cyberdeck L70 `DownloadHandle` still has no `destination` field and downloads always route to deck storage. The type matches the design (ACC-01) yet is dead in the current wiring. Classify: dead/stub field (DS), document only — confirm against the business-logic layer in Iteration 4/5.

**D3D-4 — Persona.reaction / sleazeRating defaults of 0 can silently mask a missing load-time value (Rule 9).**
`Persona.kt` L17 `val reaction: Int = 0,` and L19 `val sleazeRating: Int = 0,`. Persona Reaction = base + Response Increase×2 (creation.md calc field, spec_baseline L11) is computed by the loader, not in this domain type; a `Persona(...)` constructed without `reaction` defaults to 0, which would make `Decker.actionsPerTurn` (Decker.kt L88) wrong. Not a formula error in this file — flag for the loader/factory completeness check (Iteration 4). Classify: constructor-completeness watch, document only.

## Notes on the assigned verification checklist
- Hacking Pool, Detection Factor, effectiveDetectionFactor, Persona Reaction usage (actionsPerTurn), and Response-Increase clamp: all match the formulas exactly (no findings).
- Degradation logic (Armor −1, Medic −1/invoke, currentRating→0 auto-unload) is NOT implemented in the domain files — it lives in the SKIPPED `*Extensions.kt` business-logic layer. The domain files only carry results; `MedicResult.medicRating` correctly represents the post-decrement rating, and `Decker.detectionFactor` correctly reads the degradable live `currentRating` for Sleaze.
- Cyberterminal constraints (MPCP≤4, RI=0, −1 utility ratings) and active/storage memory limits are all enforced/documented correctly.
