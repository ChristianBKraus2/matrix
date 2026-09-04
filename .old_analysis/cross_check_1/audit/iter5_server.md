# Iteration 5 — Server / Controller Layer Conformance Audit

Scope: `src/main/kotlin/com/shadowrun/matrix/server/*.kt` (top-level, NOT `dto/`).
Glob confirmed exactly 5 top-level files. DTOs read for Rule-12 wire tracing only
(`server/dto/Messages.kt`), not audited as assigned files here.

Against: `design/protocol.md` (read in full), `design/prd_ui.md` (UI-01..04, read in full),
`design/audit/spec_baseline.md` §Wire protocol.

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| DeckerDisconnectedException.kt | 3 | `class DeckerDisconnectedException : Exception("decker disconnected mid-turn")` | Marker exception used by TurnCoordinator.cancelIfActive → completeExceptionally, caught in conductTurn to broadcast "Decker disconnected — turn forfeit". No wire fields. No discrepancy. |
| MatrixServer.kt | 81 | (open) `private const val MAX_CONNECTIONS = 32`; (dispatch) `when (msgType) { "join" -> registry.receiveJoin(...) ; "action" -> registry.receiveAction(...) ; else -> ... UNKNOWN_MESSAGE_TYPE, details = msgType?.take(64) }`; (close) `} finally { registry.deregister(this) }` | Endpoint `/decker/ws`. Capacity gate sends `SERVER_FULL` before any client message and `return@webSocket` (protocol L152 ✓). Frame parse errors → `BAD_REQUEST` with `details = e.message?.take(256)` (L151 ✓). Two client kinds dispatched: join, action; unknown → `UNKNOWN_MESSAGE_TYPE` details=received type (L150 ✓). No discrepancy. |
| SessionRegistry.kt | 171 | (open) `private val reconnectTokens = HashMap<String, String>()`; (mid) `disconnectedDeckerNames.contains(name) -> { val storedToken = reconnectTokens[name] ; if (storedToken != null && (msg.reconnectToken == null || msg.reconnectToken != storedToken)) { JoinOutcome(ErrorCode.BAD_REQUEST, false, null) }`; (close) `"NOT_YOUR_TURN" -> ErrorCode.NOT_YOUR_TURN ; "NO_ACTION_PENDING" -> ErrorCode.NO_ACTION_PENDING` | join errors: NAME_TOO_LONG (len>32, L149 ✓), NAME_ALREADY_TAKEN, ALREADY_REGISTERED, BAD_REQUEST (bad/missing token, L73/UI-03 ✓; invalid chars). Token issued only on REGISTERED_DECKER join response and reissued on reconnect (UI-01/03 ✓). OBSERVER on connect, ACTIVE_CONTROLLER on promote, REGISTERED_DECKER on demote — token null on promote/demote. `reconnectTokens` never removed → see D5S-2. Findings: D5S-1, D5S-2. |
| TurnCoordinator.kt | 52 | `if (session != activeController) return@withLock null to "NOT_YOUR_TURN"` … `if (f == null || f.isCompleted) return@withLock null to "NO_ACTION_PENDING"` | Atomic claimAction validates active controller + incomplete pending future; error keys map 1:1 to protocol not_your_turn / no_action_pending (L144-145 ✓). cancelIfActive returns pending future for disconnect cancellation. No discrepancy. |
| WebSocketDeckerController.kt | 488 | (open) `private val actionTimeoutSeconds: Long = 120`; (mid) `SystemOperation.LOCATE_FILE -> { if (query.isBlank() && decker.interrogationStates["LOCATE_FILE@HOST"] == null) return DispatchResult(decker, false, 0, 0, "LOCATE_FILE requires a search term on the first call")`; (close) `private fun OperationResult.toDispatch(extra: String = ""): DispatchResult { ... DispatchResult(decker, this is OperationResult.Success, outcome.deckerSuccesses, outcome.hostSuccesses, details) }` | Turn lifecycle order matches protocol L129-135: Result → demote(REGISTERED_DECKER) → post-action StateMessage broadcast; timeout at 120s broadcasts ResultMessage(success=false,"Action timed out")+demote, NO post-action state (L137 ✓). ResultMessage always built with non-null Int successes; broadcastFail uses 0/0. params: TAP scannerDeviceRating `.coerceIn(0..10)` (L88 ✓), UPLOAD dataSize `?: 100` (L89 ✓), MAKE_COMCALL hasValidPasscode `?: false` (L88 ✓), NULL_OPERATION inactivitySeconds `?: 0` (L90 ✓), EDIT_FILE newContent null→erase (L87 ✓), LOCATE precision→QueryPrecision default NORMAL (L136 ✓). No branch for LOCATE_DECKER/SWAP_MEMORY (deferred, excluded from availableActions in domain layer). Findings: D5S-3; observations O1, O2. |

### Dispatch branches enumerated (WebSocketDeckerController)

Client `action` → `conductTurn` → `dispatch(action)`:
- `LogonToRtg` / `LogonToLtg` / `LogonToPltg` / `LogonToHost` → `decker.logonTo*`
- `GracefulLogoff` → `decker.gracefulLogoff` (captures preLogoff security rating)
- `JackOut` → black-IC-pin branch (Willpower test, final IC attack, dump-shock resolve) / plain jackOut
- `Operation` → `dispatchGridOperation` (host==null) else `dispatchHostOperation`

`dispatchGridOperation`: NULL_OPERATION, RELOCATE_ICON (rejected: needs host), LOCATE_ACCESS_NODE, ANALYZE_SECURITY, LOCATE_IC, DECRYPT_ACCESS, INVOKE_MEDIC, else→unsupported.

`dispatchHostOperation` groups → `dispatchAnalyzeOp` (ANALYZE_HOST/IC/ICON/SECURITY/SUBSYSTEM), `dispatchLocateOp` (LOCATE_FILE/SLAVE/ACCESS_NODE/IC), `dispatchDataOp` (DOWNLOAD/EDIT_FILE/UPLOAD/DECRYPT_ACCESS/FILE/SLAVE), `dispatchSlaveOp` (CONTROL/EDIT/MONITOR_SLAVE), `dispatchCommsOp` (MAKE/TAP_COMCALL), `dispatchMiscOp` (NULL_OPERATION/RELOCATE_ICON/INVOKE_MEDIC), else→unsupported. LOCATE_DECKER and SWAP_MEMORY have NO branch anywhere (deferred).

## Findings

### D5S-1 — REGISTERED_DECKER control on demote carries no reconnectToken (latent reconnect hazard)
`SessionRegistry.kt:122-128` `demoteAfterTurn` sends
`ControlMessage(role = SessionRole.REGISTERED_DECKER, deckerName = deckerName)` with
`reconnectToken` defaulting to null. Same for the post-turn transition after every action.
Protocol L37: "`reconnectToken` is non-null only when `role` is `registered_decker`" — this is a
*necessary* condition (may be null), so the message is strictly protocol-conformant, and UI-01
requires a token only on the *registration acceptance* response (which `receiveJoin` does emit,
`SessionRegistry.kt:90-96`). **Verdict:** protocol permits null here; not a hard violation. Flagged
because a frontend that naively stores `reconnectToken` from every `ControlMessage` would clobber
its saved token to null on the first demote (UI-02 lifecycle). Belongs to frontend hook audit
(Iter 6) to confirm the client ignores null tokens rather than overwriting.

### D5S-2 — reconnectToken never cleared server-side on graceful logoff
`SessionRegistry.kt:29,73,81` — entries are written to `reconnectTokens` on join/reconnect and
**never removed** (no `remove`/`clear` anywhere; `deregister` at L100-111 deliberately retains the
token for reconnect). `GracefulLogoff` is dispatched as a decker action in
`WebSocketDeckerController.dispatch` (`WebSocketDeckerController.kt:153-156`) and the registry is
never notified, so the token survives graceful logoff.
Protocol L37: "The token survives disconnect but **is cleared on intentional logout (graceful
logoff)**." UI-04 scopes the clearing to the *client* ("cleared client-side when the user
deliberately logs out"). **Verdict:** ambiguous between the two docs. If L37 is read as a
server-side guarantee, this is a real gap — a logged-off slot remains reclaimable with the old
token, and the map grows unbounded over the process lifetime. If UI-04 governs (client-only), the
server is compliant and L37's wording is stale. No PRD clause resolves server-side token clearing;
document + reconcile L37 vs UI-04.

### D5S-3 — grid LOCATE_ACCESS_NODE omits the first-call `query` guard the host path enforces
`WebSocketDeckerController.kt:200-203` (`dispatchGridOperation`, LOCATE_ACCESS_NODE) calls
`locateWithState { prec, q -> decker.locateAccessNode(grid, q, prec, diceRoller) }` directly, with
no check that a query was supplied on the first call. The host path for the same operation
(`WebSocketDeckerController.kt:286-288`) does guard it:
`if (query.isBlank() && decker.interrogationStates["LOCATE_ACCESS_NODE@HOST"] == null) return ... "requires a search term on the first call"`.
Protocol L85: LOCATE_* `query` is "required on first call, ignored on continuation."
**Verdict:** grid-vs-host inconsistency (align.md Rule 10 partial-fix pattern). The grid path passes
an empty query into the resolver on first call instead of rejecting it. Real discrepancy vs L85 for
the grid dispatch path; add a matching first-call guard (keyed on the grid interrogation state) or
confirm the resolver tolerates a blank first query by design.

### Observations (documentation / code-quality, not protocol violations)
- **O1** `WebSocketDeckerController.kt:23` imports `LocateDeckerResult` but LOCATE_DECKER is deferred
  and has no dispatch branch — dead import (DC-).
- **O2** `WebSocketDeckerController.kt:9` is a single physical line containing two import statements
  (`import com.shadowrun.matrix.decker.*import com.shadowrun.matrix.game.ActionResult`); compiles but
  is a formatting defect. Not protocol-related.

### Confirmed conformant (no finding)
- All 8 error codes present with correct `@SerialName` (`dto/Messages.kt:17-26`): not_your_turn,
  no_action_pending, already_registered, name_already_taken, name_too_long, unknown_message_type,
  bad_request, server_full — match protocol L144-152.
- MAX_CONNECTIONS = 32; SERVER_FULL emitted at open before any client message (protocol L152).
- Timeout = 120s; timeout/disconnect/error paths broadcast ResultMessage(success=false) and demote,
  with no post-action StateMessage (protocol L137).
- ResultMessage.deckerSuccesses / hostSuccesses are non-nullable `Int` in the DTO and every
  construction site (incl. broadcastFail 0/0) supplies non-null values (protocol L61).
- reconnectToken issued on registration and reissued on valid reconnect; BAD_REQUEST on
  missing/wrong token for a disconnected name (UI-01/03, protocol L73).
- Deferred ops LOCATE_DECKER, SWAP_MEMORY have no dispatch branch and fall through to "Unsupported".
