# Iteration 8 — Server / DTO Test-File Conformance Audit

Strict design-vs-code conformance audit of the server/DTO **test** layer against
`design/protocol.md` (read in full this session), `design/prd_ui.md` UI-01..04 (read in full),
and the just-completed server/DTO audits `iter5_server.md` / `iter5_dto.md` (findings
D5S-1/2/3, D5D-1/2). Every assigned file was read in full from line 1 (align.md Rule 1).

A test finding here = (a) an assertion contradicting protocol/spec, (b) a disabled/trivial/
no-assert test, (c) a coverage gap for a known real/latent defect, or (d) a test asserting
behavior the protocol forbids. No assertion in any assigned file was found to **contradict**
the protocol — all confirmed findings are **coverage gaps**.

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `integration/WebSocketServerIntegrationTest.kt` | 176 | (open, L39) `assertEquals("control", obj["type"]?.jsonPrimitive?.content)` / `assertEquals("observer", obj["role"]?.jsonPrimitive?.content)`; (mid, L81) `assertEquals("LogonToLtg", actions[0].jsonObject["kind"]?.jsonPrimitive?.content)`; (close, L130) `assertEquals(setOf("UCAS-SEA", "UCAS-CHI", "UCAS-NYC", "UCAS-BOS"), ltgNames.toSet())` | Real WS round-trip via `testApplication`/`matrixModule`. Verifies `type` discriminator (control/state), `kind` discriminator on AvailableActionDto (`LogonToLtg`), turn lifecycle ordering L116-118 (result → registered_decker → post-action StateMessage broadcast — matches protocol L129-135 ✓). Action submitted as `{"type":"action","actionIndex":N}` (no `params` ever sent). Controller built with `actionTimeoutSeconds = 5`; timeout path never triggered. No contradicting assertion. |
| `server/WebSocketServerTest.kt` | 299 | (open, L55) `assertEquals("observer", obj["role"]?.jsonPrimitive?.content)`; (mid, L119) `assertEquals(ErrorCode.NOT_YOUR_TURN, error.message)`; (close, L294-295) `assertFalse(result.success)` / `assertTrue(result.details.contains("forfeit"))` | Covers error codes NOT_YOUR_TURN (L119), NAME_ALREADY_TAKEN (L95/L252), ALREADY_REGISTERED (L108), BAD_REQUEST (wrong token L214, invalid chars L263). reconnectToken: issuance asserted (L226 `requireNotNull(token)`), reuse succeeds (L195/L234), wrong token → `bad_request` (L214) — UI-01/03, protocol L73 ✓. Disconnect-forfeit ResultMessage(success=false) (L293-295). NO test triggers the 120s timeout; NO test for graceful-logoff token clear; NO UNKNOWN_MESSAGE_TYPE test. Reconnect tests do not assert a **new** token is issued (UI-03). |
| `server/SessionRegistryTest.kt` | 213 | (open, L50) `assertFalse(deferred.isCompleted)`; (mid, L94) `assertEquals(ErrorCode.NO_ACTION_PENDING, Json.decodeFromString<ErrorMessage>(session.nextText()).message)`; (close, L196) `assertEquals(ErrorCode.NAME_TOO_LONG, Json.decodeFromString<ErrorMessage>(session.nextText()).message)` | Covers NO_ACTION_PENDING (null L94, done L109), NAME_TOO_LONG (33-char L196), 32-char boundary succeeds → REGISTERED_DECKER (L184-187). Role broadcast: observer/registered_decker/active_controller via `broadcastWithRoles` (L124/135/211). `register returns false when maxConnections reached` (L169-176) uses `maxConnections = 1` and asserts only the **boolean** — no SERVER_FULL wire message, no 32 constant. L54-65/L68-80 are "does not throw" tests (no explicit assert, but exercise real no-throw paths — acceptable). No contradicting assertion. |
| `server/TurnCoordinatorTest.kt` | 109 | (open, L19) `assertEquals(session, coord.currentController())`; (mid, L74) `assertEquals("NOT_YOUR_TURN", error)`; (close, L96) `assertEquals("NO_ACTION_PENDING", error)` | Unit-tests claimAction error keys NOT_YOUR_TURN (L74) and NO_ACTION_PENDING (null L84, completed L96) — map 1:1 to protocol L144-145 ✓. cancelIfActive match/no-match (L44-64), setActive round-trip, currentControllerUnsafe. All assertions conformant. No wire-format concerns (internal coordinator). |
| `server/FakeWebSocketSession.kt` | 40 | (open, L20) `class FakeWebSocketSession : DefaultWebSocketServerSession`; (close, L39) `suspend fun nextText(): String = (withTimeout(5_000) { _outgoing.receive() } as Frame.Text).readText()` | Test double implementing `DefaultWebSocketServerSession`; captures outgoing frames on an UNLIMITED channel. No assertions, no protocol surface. Infrastructure. No finding. |
| `server/dto/DtoMappingTest.kt` | 232 | (open, L36) `assertEquals("not jacked in", decker.toDto().location)`; (mid, L104-106) `assertNull(dto.rating)` / `assertNull(dto.behavior)` / `assertNull(dto.guardedNodeType)`; (close, L212) `assertEquals(pltg.securityRating.code.name, dto.securityCode)` | domain→DTO mapping: Decker.location prefixes (not jacked in/RTG/LTG/Host/PLTG) L36-53,199. MatrixObject variants GridNode/LocalGrid/HostNode/HostSubsystem/IcProgram/File/Device/PrivateGrid with `index` + field names. IcProgram null-until-analyzed asserted three ways (L104-106 unanalyzed, L115-117 analyzed reveals, L127-129 analyzed=false hides) — protocol L210 ✓. AvailableAction Operation targetKind/targetName, Logon*/JackOut/GracefulLogoff. GAPS: no test asserts `paramKind` on Operation (protocol L190); no ResultMessage mapping test (deckerSuccesses/hostSuccesses non-null, protocol L61); no `actionType` field asserted. |

Total: 6 files, 176+299+213+109+40+232 = **1069 lines** read in full.

---

## Findings

### D8TE-1 — `UNKNOWN_MESSAGE_TYPE` error code has no test coverage
No assigned test sends an unrecognized `type` and asserts the `unknown_message_type` response
with `details` = received value. Of the 8 protocol error codes (protocol.md L143-152), seven are
asserted somewhere (NOT_YOUR_TURN, NO_ACTION_PENDING, ALREADY_REGISTERED, NAME_ALREADY_TAKEN,
NAME_TOO_LONG, BAD_REQUEST, and SERVER_FULL only indirectly — see D8TE-2); `UNKNOWN_MESSAGE_TYPE`
is asserted nowhere.
**Verdict:** coverage gap vs protocol L150 (`unknown_message_type` … `details` contains the
received value). The dispatch `else` branch in `MatrixServer.kt` (iter5_server.md excerpt
`else -> ... UNKNOWN_MESSAGE_TYPE, details = msgType?.take(64)`) is untested.

### D8TE-2 — `SERVER_FULL` wire emission and MAX_CONNECTIONS=32 untested
`SessionRegistryTest.kt:169-176` (`register returns false when maxConnections reached`) uses
`maxConnections = 1` and asserts only `assertFalse(registry.register(s2, maxConnections = 1))` —
a boolean. No test asserts the `server_full` ErrorMessage / close is emitted at WebSocket open
before any client message, and no test exercises the real `MAX_CONNECTIONS = 32` constant
(`MatrixServer.kt`, iter5_server.md excerpt `private const val MAX_CONNECTIONS = 32`).
**Verdict:** coverage gap vs protocol L152 (`server_full` … connection refused at WebSocket open …
reached `MAX_CONNECTIONS` (32)). The registry boolean is covered; the wire behavior and the
32 value are not.

### D8TE-3 — 120s timeout path untested (ResultMessage success=false + demote, no post-action state)
Every controller in these tests is constructed with `actionTimeoutSeconds = 5`
(`WebSocketServerIntegrationTest.kt:104`, `WebSocketServerTest.kt:126,146,270`) and the action
always completes (or the session disconnects) before the timeout. No test drives a timeout to
assert `ResultMessage(success = false, details = "Action timed out")` + demotion + **no**
post-action StateMessage, and the default `120` (iter5_server.md excerpt
`private val actionTimeoutSeconds: Long = 120`) is never asserted.
**Verdict:** coverage gap vs protocol L137. The disconnect-forfeit test
(`WebSocketServerTest.kt:267-298`) exercises a *different* path (`DeckerDisconnectedException`,
"forfeit") and must not be mistaken for timeout coverage.

### D8TE-4 — D5S-3 grid `LOCATE_ACCESS_NODE` blank-query rejection is NOT tested (known real defect uncovered)
No assigned test dispatches `LOCATE_ACCESS_NODE` on the grid path with a blank `query` on the
first call. In fact **no** test sends any `params` object at all — every `ActionCommand` is
`{"type":"action","actionIndex":N}` (`WebSocketServerIntegrationTest.kt:92,115,134`). The
grid-vs-host inconsistency documented as D5S-3 (grid path omits the first-call query guard the
host path enforces, protocol L85) is therefore entirely uncaught by the test suite; a test
asserting `bad_request`/"requires a search term on the first call" for the grid path would
currently pass through into the resolver.
**Verdict:** coverage gap for the confirmed D5S-3 defect (protocol L85).

### D8TE-5 — D5S-2 token-clear-on-graceful-logoff is NOT tested (known latent defect uncovered)
No assigned test performs a `GRACEFUL_LOGOFF` and then attempts a reconnect with the old token
to assert it is rejected. The reconnect tests (`WebSocketServerTest.kt:180-235`) only cover
reconnect after `deregister` (disconnect), where the token is *intended* to survive. The
protocol L37 clause "the token … is cleared on intentional logout (graceful logoff)" — the exact
behavior D5S-2 says the server never implements — has zero test coverage, so the unbounded-map /
reclaimable-slot latent defect is uncaught.
**Verdict:** coverage gap for the confirmed D5S-2 defect (protocol L37 vs UI-04).

### D8TE-6 — `paramKind` mapping and action-param handling untested (DTO + server)
`DtoMappingTest.kt` asserts `index`, `targetKind`, `targetName`, and per-variant fields on
`AvailableActionDto.Operation` (L155-170) but never asserts the `paramKind` field, so the
protocol L190 map (precision / hasValidPasscode / scannerDeviceRating / newContent / dataSize /
null) is untested. Correspondingly, no server test sends `params`, so the server-side param
handling verified in iter5_server.md — `scannerDeviceRating.coerceIn(0..10)` (protocol L88),
`dataSize ?: 100` (L89), `hasValidPasscode ?: false` (L88), `inactivitySeconds ?: 0` (L90),
`newContent` null→erase (L87), `precision`→`QueryPrecision` default NORMAL (L136) — is exercised
by no test in this layer.
**Verdict:** coverage gap vs protocol L83-90 and L190. (`ResultMessage` domain→DTO mapping and
its non-null `deckerSuccesses`/`hostSuccesses`, protocol L61, are likewise absent from
`DtoMappingTest`; enforced only implicitly by the non-null Kotlin type at deserialization.)

### Observation O1 — reconnect tests do not assert a fresh token is issued (UI-03)
`WebSocketServerTest.kt:180-235` reconnect tests assert only `role == registered_decker` after a
valid-token rejoin. UI-03 requires that on a successful reconnect "a new token is issued"; no
test captures the post-reconnect token and asserts it is present/rotated. Minor coverage gap,
not a defect.

---

## Root cause

All findings are **coverage gaps**, not wrong assertions: the server/DTO test suite asserts
protocol-correct behavior everywhere it asserts at all (`type` vs `kind` discriminators, 7 of 8
error codes, reconnect issuance/reuse/BAD_REQUEST, NOT_YOUR_TURN/NO_ACTION_PENDING, turn-lifecycle
ordering, IcProgram null-until-analyzed). The gaps cluster where the suite never drives the
harder wire paths: (1) frame-level protocol errors handled in `MatrixServer` open/dispatch
(SERVER_FULL, UNKNOWN_MESSAGE_TYPE), (2) time-based / param-carrying action paths
(120s timeout, all `params`), and (3) the two known server defects (D5S-2, D5S-3), which — being
untested — remain silently latent.
