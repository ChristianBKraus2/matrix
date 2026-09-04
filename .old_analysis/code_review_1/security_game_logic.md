---
# Security Review — game_logic

## Summary

The game logic layer is a pure server-side simulation with no user-facing API of its own, so the attack surface is bounded: threats come either from malformed YAML configuration files loaded at startup, or from the WebSocket layer passing untrusted values into game-logic method parameters. Two findings stand out as most serious. First, `Decker.makeComcall` accepts a plain `hasValidPasscode: Boolean` from the caller and, when true, skips the System Test entirely and fabricates a success outcome — if that boolean is ever wired to a client-controlled field in the WebSocket server, it is a free privilege escalation. Second, both YAML loaders (`GridLoader`, `HostLoader`) instantiate SnakeYAML with its default `Yaml()` constructor, which supports arbitrary Java-type tags; a crafted YAML file can instantiate any class on the classpath. Below that severity, several combat-resolution methods accept caller-supplied integers that should instead be derived from authoritative game state (`relocateIcon`, `controlSlave`), the classpath path used to load host configs is taken verbatim from a YAML field, no numeric bounds checking exists on ratings parsed from YAML, and enum ordinal comparisons guard alert-level transitions in a way that breaks silently on reordering. Most of the internal logic (combat resolution, tally accumulation, persona attribute mutation) is correctly server-authoritative; no hardcoded credentials or secrets were found.

---

## Findings

### HIGH — `makeComcall` bypasses System Test via unverified boolean flag

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1079`

**Issue:** `makeComcall(host, diceRoller, hasValidPasscode = false)` accepts a plain `Boolean` parameter. When `true`, the method skips the entire System Test, constructs a synthetic `SystemTestOutcome(1, 0, true)`, and immediately returns a `MonitoredOperationHandle`. There is no passcode object, no stored credential to match against, and no server-side verification of any kind. Any caller — including the WebSocket handler if it maps an incoming client field to this parameter — can pass `hasValidPasscode = true` to guarantee success without spending a dice roll or incurring a tally increase.

**Recommendation:** Remove the `hasValidPasscode` shortcut from the public method signature. If licensed access is a game mechanic, model it as a property on the `Decker` or `Jackpoint` that was set by a prior verified game event (e.g., a successful Logon with a known passcode), not as a boolean the caller asserts at call time.

---

### HIGH — SnakeYAML default constructor allows arbitrary class instantiation

**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:17` and `HostLoader.kt:37`

**Issue:** Both loaders create `Yaml()` with no custom constructor or type-safe loader:
```kotlin
val yaml = Yaml()
val root = yaml.load<Map<String, Any>>(input)
```
SnakeYAML's default `Yaml()` processes `!!`-prefixed tags and will instantiate any class accessible on the classpath. A YAML file containing `!!java.lang.ProcessBuilder [["calc.exe"]]` or similar will execute arbitrary code during load. Additionally, `HostLoader` reads config paths from a YAML value and hands the path straight to `getResourceAsStream` — a classpath path controlled by the YAML author.

**Recommendation:** Replace `Yaml()` with `Yaml(SafeConstructor(LoaderOptions()))` to restrict deserialization to basic Java types only. For the `config` field in `GridLoader.buildHost`, validate that the resolved path does not escape the expected resource directory prefix (e.g., must start with `config/hosts/`) before passing it to `getResourceAsStream`.

---

### MEDIUM — `controlSlave` accepts caller-supplied skill value without an upper bound

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:883`

**Issue:** `controlSlave(device, host, diceRoller, effectiveSkill: Int? = null)` uses the caller-supplied `effectiveSkill` directly as the dice pool:
```kotlin
val skill = effectiveSkill ?: computerSkill
val deckerResult = diceRoller.roll(skill, tn)
```
No validation is applied. A caller can pass `Int.MAX_VALUE` or any arbitrarily large value to guarantee a success, bypassing the in-game constraint that the effective skill is the average of two real attributes. The `DiceRoller` only requires `numberOfDice > 0`, so it will silently accept any positive integer.

**Recommendation:** Enforce an upper bound before use, e.g., `require(effectiveSkill == null || effectiveSkill <= computerSkill * 2)`, or better, compute the manufacturing/B&R average inside the method from decker attributes rather than accepting a pre-computed integer from the caller.

---

### MEDIUM — `relocateIcon` accepts opponent statistics from the caller, not game state

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1134`

**Issue:**
```kotlin
fun relocateIcon(opponentSensor: Int, trackerMcpRating: Int, diceRoller: DiceRoller): OperationResult
```
Both `opponentSensor` and `trackerMcpRating` are parameters supplied by the caller; neither is derived from the authoritative game state in `GameContext`. Passing `opponentSensor = 0` makes the decker's target number `max(2, 0 - relocate)` which, with any relocate rating, floors to 2 (minimum). Passing `trackerMcpRating = 0` gives the tracker zero dice, guaranteeing the decker wins. The same parameters control track removal, which has a meaningful in-game security consequence.

**Recommendation:** The method should accept either the `Decker` who is tracking (looked up from `GameContext`) or a typed `TrackState` object that already encodes the tracker's statistics, not raw integers the caller can fabricate.

---

### MEDIUM — No numeric bounds validation on YAML-parsed ratings

**File:** `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:190`, `GridLoader.kt:143`

**Issue:** Rating values parsed from YAML are used without any range check:
```kotlin
val map = value as Map<String, Int>
return SubsystemRatings(access = map["access"] ?: error("missing access rating"), ...)
```
A YAML with `access: 0` or `access: -5` produces a zero or negative subsystem rating. When this rating is later passed to `diceRoller.roll(numberOfDice, targetNumber)`, `require(targetNumber >= 2)` will throw an `IllegalArgumentException`, crashing the game engine mid-session. A rating of `0` passed as the dice count will also throw `require(numberOfDice > 0)`. IC ratings follow the same unchecked path through `buildIc`.

**Recommendation:** Add a validation pass in `parseSubsystemRatings` and `buildIc` that enforces `1..12` (or the appropriate game-legal range) for all integer fields. Throw a descriptive `IllegalArgumentException` at load time so the error surfaces during startup rather than mid-combat.

---

### MEDIUM — YAML `config` path loaded without validation (classpath path traversal)

**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:127`

**Issue:**
```kotlin
val configPath = data["config"] as? String
val stream = GridLoader::class.java.classLoader.getResourceAsStream(configPath)
```
The classpath resource path is taken verbatim from a YAML field with no prefix check or character filtering. On many runtimes, `getResourceAsStream` accepts paths such as `../../some/other/resource`. An attacker who can modify `grid.yaml` can cause arbitrary classpath resources (including other application config files) to be loaded and parsed as host configuration, potentially causing information disclosure or crashes.

**Recommendation:** Validate that `configPath` matches an expected prefix (e.g., starts with `config/hosts/` and contains no `..` segments) before calling `getResourceAsStream`.

---

### LOW — `asDefenderParticipant()` force-casts crash the engine on unexpected state

**File:** `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:7`

**Issue:**
```kotlin
fun Decker.asDefenderParticipant(): DefenderParticipant = DefenderParticipant(
    bod = persona!!.bod,
    securityCode = (currentLocation as MatrixLocation.OnHost).host.securityRating.code
)
```
Both `persona!!` and `(currentLocation as MatrixLocation.OnHost)` are unchecked and will throw a `NullPointerException` or `ClassCastException` respectively if the decker is not in a jacked-in, on-host state. This method is called from every IC action path (e.g., `Killer.action`, `Blaster.action`). If `findTarget` returns a decker in an inconsistent state (e.g., in the middle of a logoff), the IC action crashes the game engine with an unhandled exception rather than returning a graceful `ActionResult`.

**Recommendation:** Replace the force-casts with safe null checks that return a sentinel or throw an `IllegalStateException` with a meaningful message. Guard at the call sites in IC action methods: if `asDefenderParticipant()` cannot be constructed, return `ActionResult.NoTarget`.

---

### LOW — Alert status comparison relies on enum ordinal

**File:** `src/main/kotlin/com/shadowrun\matrix\game\GameContext.kt:49`

**Issue:**
```kotlin
if (transition.ordinal > host.alertStatus.ordinal)
    updateHost(applyAlertTransition(host, transition))
```
The security-critical invariant "alerts only escalate, never de-escalate" is enforced entirely by the ordinal position of `AlertStatus` enum members (`NO_ALERT=0`, `PASSIVE_ALERT=1`, `ACTIVE_ALERT=2`). Reordering these entries for any reason (alphabetical sort, code style refactor) would silently invert the gate, allowing a tally reset to de-escalate a RED-alert host to NO_ALERT.

**Recommendation:** Replace with an explicit comparison that does not depend on ordinal:
```kotlin
val escalates = when (host.alertStatus) {
    AlertStatus.NO_ALERT     -> true
    AlertStatus.PASSIVE_ALERT -> transition == AlertStatus.ACTIVE_ALERT
    AlertStatus.ACTIVE_ALERT  -> false
}
if (escalates) updateHost(applyAlertTransition(host, transition))
```

---

### LOW — Decker/host/file names logged without sanitization (log injection)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt` (throughout)

**Issue:** Names from YAML (host names, decker names, file names) are embedded directly in log messages via string interpolation:
```kotlin
logger.info { "[$name] editFile → ${file.name} (delete=${newContent == null})" }
```
A YAML-provided name containing newline characters or ANSI escape sequences can forge additional log lines or corrupt log files, which complicates security audit trails.

**Recommendation:** Strip or escape control characters from names when they are first loaded in `DeckerLoader`/`HostLoader`, or use a dedicated `sanitize(name: String)` helper before embedding any user-supplied string in a log message.

---

### LOW — `BufferedMessage` enforces only word count, not message byte length

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1177`

**Issue:**
```kotlin
require(text.split("\\s+".toRegex()).size <= 100) { "Buffered message exceeds 100 words" }
```
The rule allows 100 words of unlimited individual length. A single "word" with millions of characters passes the check. If the message is serialized into a WebSocket frame (which the server layer does), an oversized message could allocate disproportionate memory on each Combat Turn broadcast.

**Recommendation:** Add a byte/character limit in addition to the word count:
```kotlin
require(text.length <= 2000) { "Buffered message exceeds maximum length" }
```

---

### INFO — `LocateResult.Located` and `MonitoredOperationHandle.target` typed as `Any`

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt:67`, `MonitoredOperationHandle.kt:13`

**Issue:** Both `LocateResult.Located(val target: Any, ...)` and `MonitoredOperationHandle(val target: Any, ...)` erase type information. Call sites must cast blindly; a wrong concrete type causes a `ClassCastException` that propagates through the game engine without a meaningful error boundary.

**Recommendation:** Parameterize with a bounded type: `Located<T : MatrixObject>(val target: T, ...)` and `MonitoredOperationHandle<T>(val target: T, ...)`. This makes type errors compile-time rather than runtime.

---

### INFO — `InterrogationState.query` not length-bounded before use as substring filter

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:757`

**Issue:**
```kotlin
val file = host.dataFiles.firstOrNull { it.name.contains(state.query, ignoreCase = true) }
```
`state.query` comes from `InterrogationState` which is constructed with a raw `String` from the caller. There is no length check. A very long query string is wasteful but harmless in typical game scenarios; it becomes relevant only if the query is ever populated from client WebSocket input.

**Recommendation:** Add `require(query.length <= 256)` when constructing `InterrogationState` if the query field can originate from client input.

---

## Clean Areas

- **Dice rolling** (`DiceRoller`): Validates `numberOfDice > 0` and `targetNumber >= 2` before any roll; exploding-dice logic is bounded by the loop termination condition `face != 6`.
- **Cyberdeck construction** (`Cyberdeck.init`): Enforces all MPCP constraints (persona program ratings, utility ratings, active memory capacity, storage capacity) at construction time rather than at use time.
- **Navigation topology enforcement** (`Decker.logonToRtg/Ltg/Pltg/Host`): Each logon method verifies that the target is reachable from the current location before performing the System Test; there is no way to teleport to an arbitrary host.
- **Alert escalation logic** (`applyAlertTransition`): Correctly applies permanent subsystem rating increases on Passive Alert and never reverts them.
- **Tally accumulation** (`withUpdatedTally`): Tally is incremented only by the host's own dice roll result, never by a caller-supplied value.
- **Dump shock and pin-release** (`CombatResolver.resolveJackOutWithPin`): Correctly guards jack-out with a willpower contest and always fires a final IC attack even on success.
- **IC targeting** (`IC.findTarget`): Only targets deckers with `PersonaStatus.INTRUDING`, preventing IC from attacking legitimately logged-in personas.
- **No hardcoded credentials or secrets** found anywhere in the reviewed files.
