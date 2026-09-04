# Iteration 8 — Config Test-File Conformance Audit (batch F)

Scope: `src/test/kotlin/com/shadowrun/matrix/config/` — `DeckerConfigTest.kt`, `GridLoadTest.kt`.
These two files were unassigned in batches A–E; audited here directly (read in full, line 1 → last)
to close the manifest. Baselines: `iter7_config.md` (D7C-1..7), `iter2_cyberdeck.md`
(HackingPool/DetectionFactor/RI formulas), `spec_baseline.md`. Rule 6: a test's expectation is
part of the spec surface.

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `config/DeckerConfigTest.kt` | 135 | (open, L16) `input.use { DeckerLoader.load(it) }` — (mid, L80) `assertEquals(4, decker.hackingPool)  // (intelligence=6 + mpcp=8) / 3 = 4` — (close, L124) `assertTrue(vault.offline, "Saeder-Krupp Research Vault should be offline")` | Loads `headcrash.yaml` → Decker+Cyberdeck. HackingPool ⌊(6+8)/3⌋=4 ✓; detectionFactor ⌈(6+5)/2⌉=6 ✓; RI≤cap; persona rating≤MPCP & Σ≤MPCP×3; storage fit; offline-host flag. All formula assertions conformant. D8TF-2 (misleading "sleaze active" test); D8TF-3 (cyberterminal path uncovered). |
| `config/GridLoadTest.kt` | 118 | (open, L22) `private val matrix = GridInitializer.initialize()` — (mid, L68) `assertTrue(matrix.rtgs.size >= 19, "Expected at least 19 RTGs, got ${matrix.rtgs.size}")` — (close, L116) `assertNotNull(aztPltg, "Aztechnology PLTG not found under AZT RTG")` | RTG count ≥19, UCAS GREEN/4, UCAS-SEA region, Mitsuhama Pagoda ORANGE/6, **LTG inherits sec+subsystem from parent RTG (L97-102)** ✓, AZT ORANGE/3, Aztechnology PLTG present. All grid-load assertions conformant. D8TF-1 (dead `winRoller`/`buildDecker` helpers); D8TF-3 (D7C-2/3/5 uncovered). |

Total: 2 files, 253 lines.

## Findings

### D8TF-1 — GridLoadTest `winRoller()` and `buildDecker()` are dead code with a wrong-face comment
`GridLoadTest.kt:25-32` (`winRoller`) and `:34-62` (`buildDecker`) are `private` helpers that **no
`@Test` in the file invokes** — every test asserts only grid-structure facts off the `matrix` field.
Additionally the `winRoller` comment (L30) `return if (call <= 6) 5 else 0  // decker: face=6
(success), host: face=1 (no success)` misstates the stub: it returns face **5** (not 6) for the
decker window and **0** for the host — and `0` is below `DiceRoller`'s `nextInt(1,7)` domain, so were
the helper ever wired in it would feed an out-of-range face. **Verdict:** code-quality only (dead,
no runtime impact); mirrors the stale-roller-comment pattern flagged as D8TD-3/D8TC-2. No spec
contradiction.

### D8TF-2 — `detection factor … with sleaze active` never activates sleaze
`DeckerConfigTest.kt:83-94`. The test name claims "with sleaze active", but the body reads the
sleaze rating from `storedUtilities` (L89-90) — its own comment (L85) concedes "to be active it must
also be in `activeUtilities`" — and then calls `decker.cyberdeck.detectionFactor(masking, sleaze)`
directly. It verifies the **formula** ⌈(6+5)/2⌉=6, not the "sleaze active" precondition (sleaze is
never added to `activeUtilities`, and no assertion checks activation). **Verdict:** weak/misleading
test (type b/c) — formula-correct but the scenario the name advertises is not exercised. Low.

### D8TF-3 — Config-loader gaps (D7C-1/2/3/5) have no test coverage
- **D7C-1 (cyberterminal never constructible from config):** `DeckerConfigTest` loads only a
  Cyberdeck (`headcrash.yaml`); no config test loads a `type: cyberterminal` deck, so the
  documented-but-unimplemented cyberterminal loader path is uncaught.
- **D7C-2 (`connectedHosts` never populated):** `GridLoadTest` asserts host presence/security but
  never asserts `host.connectedHosts` for a TIERED/HOST_HOST host — the empty-links gap is untested.
- **D7C-3 (grid `security_sheaf` not loadable):** no test asserts a grid carries trigger steps /
  non-empty `securitySheaf`.
- **D7C-5 (PLTG security inheritance):** `Aztlan RTG has Aztechnology PLTG` (L111-117) asserts only
  the PLTG's **existence** (`owner == "Aztechnology"`), never that it inherits/loads a security
  rating — so the PLTG-inheritance inconsistency is uncovered.
**Verdict:** coverage gaps for the confirmed iter7 config findings; consistent with those findings
being latent (the tests assert what the loaders *do*, not the missing behavior).

## Root cause
Both files assert loader behavior correctly wherever they assert (HackingPool/DetectionFactor/RI
formulas, RTG/LTG/PLTG structure, LTG→RTG inheritance, offline flag). The findings are (1) two
quality defects (dead helper + misleading test name) and (2) coverage gaps that coincide exactly
with the iter7 loader gaps — the config test suite exercises the happy path and does not probe the
D7C-1/2/3/5 shortfalls.
