# Game Engine Findings

Files reviewed in full by the author: `DiceRoller.kt`, `Decker.kt`, `DeckerOperationsExtensions.kt`,
`DeckerNavigationExtensions.kt`, `DeckerMemoryExtensions.kt`, `game/DeckerExtensions.kt`,
`game/GameContext.kt`, `game/Game.kt`, `ic/IC.kt`, `combat/CombatResolver.kt`, `common/Enums.kt`.
Reviewed by the (completed) subagent: `network/`, `operations/`, `programs/`, `config/` (37 files) —
result was clean (1 LOW + 4 INFO; see below).

---

## 🟠 E-1 (MEDIUM) — `resolvePointerChain` treats an exploding die as a flat 1D6

**Category:** Correctness
**Where:** [DeckerOperationsExtensions.kt:547](../../src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt#L547),
[DiceRoller.kt](../../src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt)

```kotlin
val chainLength = diceRoller.roll(1, 6).dice.firstOrNull() ?: error(...)
```

`DiceRoller.rollOne()` **explodes on 6** (`do { face = random.nextInt(1,7); total += face } while (face == 6)`),
so a single die's face value is unbounded (6 → reroll and add). Using `roll(1, 6).dice.first()` as a
"1D6 chain length" therefore produces the *wrong distribution* and can yield values far above 6
(6+6+3 = 15, etc.).

**Failure scenario:** A pointer chain intended to be 1–6 links long occasionally resolves to 7, 12, …
links, distorting the operation's cost/behavior. This is exactly the exploding-dice pitfall the
guideline §12 warns about, applied to the production code rather than a test stub.

**Fix:** Use a non-exploding roll for flat die values — e.g. a dedicated `diceRoller.flat(1, 6)` or
`random.nextInt(1, 7)` — reserving the exploding `roll()` for success-count tests.

---

## 🟡 E-2 (LOW) — `persona!!` in navigation extension

**Category:** Maintainability / Correctness (defensive)
**Where:** [DeckerNavigationExtensions.kt:95](../../src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt#L95)

```kotlin
result.decker.persona!!
```

The one bare `!!` on `persona` in the codebase (everywhere else uses
`requireNotNull(decker.persona) { "..." }`). It is guarded by a preceding success result so it should not
fire in practice, but §12 flags `persona!!` specifically because it crashes with an uninformative NPE if
the invariant ever breaks. Replace with `requireNotNull(...) { "context message" }` for consistency and a
useful failure message.

---

## 🟡 E-3 (LOW) — Tally *decrease* not propagated

**Category:** Correctness
**Where:** [GameContext.kt](../../src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt) — `applyDeckerOperationResult`

Host/trigger updates are applied only when `newTally > oldTally`. If a mechanic ever lowers the security
tally (e.g. IC suppression accounting), the host state and triggers will not reflect the decrease.
Currently no confirmed path decreases the tally, so impact is low — but the guard silently assumes
monotonic growth. Either handle `!=` or document the monotonic invariant explicitly.

---

## 🔵 E-4 (INFO) — Deferred game-loop defects confirmed current

**Where:** [Game.kt:40-49](../../src/main/kotlin/com/shadowrun/matrix/game/Game.kt#L40-L49), [IC.kt](../../src/main/kotlin/com/shadowrun/matrix/ic/IC.kt),
[deferred.md #1](../../design/deferred.md)

- **D4G-3** (IC move never persists): `Game.kt:43` discards the `ActionResult` from `state.icon.action(...)`;
  `IC.moveIfNeeded()` returns `IcMoved(...)` without mutating `guardedNode`.
- **D4G-4** (crashed IC can re-act): initiative list built once, re-selection gated only on
  `currentInitiative > 0`.

Both are **deferred with the dormant `Game` loop** (the WebSocket controller bypasses it) and are
accurately documented in `deferred.md`. **Currency verified — not reported as active bugs.**

---

## 🔵 E-5 (INFO) — `CombatResolver` is clean and faithful

**Where:** [CombatResolver.kt](../../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt)

Reviewed in full. Strong quality: `requireNotNull(persona) { "..." }` on every persona access (no `!!`);
`require(...)` guards for invariants (cyberterminal Black-Hammer/Killjoy immunity ICC-13/CT-04, Black IC
pin precondition); `stage()` damage-staging by `net/2` matches the ruleset; Black IC MPCP-death "double
rating" attack (p. 230) and `runDownloadedFiles = emptyList()` on MPCP-zero are handled; sentinel
`attackerSuccesses = 1` for Black IC is documented with a caller warning. No findings.

One item to confirm when the combat data classes are reviewed (blocked — see gate): the sentinel
contract in `resolveLethalBlackIc`/`resolveNonLethalBlackIc` relies on **callers** (TrackLock) not using
`attackerSuccesses` for cycling. `resolveTrackLock` uses `attack.attackerSuccesses` — verify it is never
fed a Black-IC-sourced `AttackResult.Hit`.

---

## 🔵 E-6 (INFO) — Static-analysis tooling not configured

`./gradlew.bat detekt` → task not found. No detekt config in the build. `tsc --noEmit` on the frontend
passed clean; `eslint` has no config file. Recommend wiring detekt + an eslint config into CI so the
per-file checklist can be partly mechanized on future reviews.

---

## Data classes / holders (combat, decker, game — 27 files, reviewed)

All 27 value/result/holder types read in full. The layer is clean — **no correctness, security, or
concurrency defect**; everything is `val`-only + `copy()`, no exposed mutable collections. Notable items:

### 🟡 E-7 (LOW) — `AttackResult.Hit.attackerSuccesses` sentinel contract is undocumented on the type
**Where:** [AttackResult.kt:7](../../src/main/kotlin/com/shadowrun/matrix/combat/AttackResult.kt#L7),
[CombatResolver.kt:302,362,423-424](../../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt#L302)

Black IC resolvers build `AttackResult.Hit(1, …)` where `attackerSuccesses = 1` is a **sentinel** ("a hit
occurred"), not a real count — the resolver kdoc warns callers (TrackLock) not to use it for cycling.
`resolveTrackLock` does exactly that (`net = attack.attackerSuccesses - evadeSuccesses`). **Assessed
latent, not live:** `resolveTrackLock` has *no production caller* (only tests, always with genuine
multi-success hits), and Black-IC hits are otherwise consumed only for display strings. Risk is a future
wiring feeding a Black-IC `Hit` into track-lock cycling → wrong `cycleTurns`.
**Fix:** Move the warning onto the field, or make Black-IC hits structurally distinct (`attackerSuccesses:
Int?` or a `BlackIcHit` variant) so the type prevents the misuse.

### 🟡 E-8 (LOW) — `IcSuppressionState` matches IC by reference identity
**Where:** [IcSuppressionState.kt:10](../../src/main/kotlin/com/shadowrun/matrix/combat/IcSuppressionState.kt#L10),
[CombatResolver.kt:455](../../src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt#L455)

`IC` is a sealed class with **no `equals`/`hashCode` override** → referential identity. `unsuppressIc`
matches with `it.ic == ic`. Since every condition-monitor change produces a *new* IC instance, a caller
that obtains the IC from `context.activeIc` (a re-created instance) and passes it to `unsuppressIc` gets a
silent no-op: the crash tally is never restored and the IC stays suppressed forever. The `suppressedIc -
state` removal itself is correct (exact element).
**Fix:** Match by a stable key (guardedNode + name, or an IC id), or document that callers must pass the
exact suppressed instance.

### 🔵 E-9 (INFO) — Minor holder items
- Rating/pool holders (`CombatInitiative`, `ManeuverParticipant`, `DefenderParticipant`,
  `AttackParticipant`, `TrackState`, `Persona`) lack `require(... >= 0)` validation, unlike the
  `Cyberdeck`/`GameContext` precedent — negatives would flow silently into `max(2, …)`/`net/2` math.
- [AttackParticipant.kt:7](../../src/main/kotlin/com/shadowrun/matrix/combat/AttackParticipant.kt#L7)
  `weaponPower: Int = attackDicePool` conflates two unrelated quantities via a default; safe only because
  the sole production constructor sets it explicitly. Make it required.
- `DownloadDestination` uses plain `object` where siblings use `data object` (worse `toString()`).
- `Cyberdeck.init` (L73) re-inlines the `usedActiveMemoryMp` formula instead of reusing the property.

**Verified clean:** `Persona.attribute`/`withAttribute` are exhaustive over `PersonaAttributeType` (no
silent default); condition-monitor box counts match `DamageLevel.boxes` (1/3/6/10, no off-by-one);
`CombatModifiers`/`Cyberdeck`/`Cyberterminal` carry correct `require` guards. Deferred items 6
(DownloadDestination routing) and 8 (JackOutPinResult/ICC-10) match current code — not reported as bugs.

## Subagent-reviewed packages (network / operations / programs / config)

Completed cleanly. Reported: 1 LOW + 4 INFO (no HIGH/MEDIUM). These 37 files should be
spot-re-verified when the review is finalized, but the completed agent found no correctness or security
issues of note.
