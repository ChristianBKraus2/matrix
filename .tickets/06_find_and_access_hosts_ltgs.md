# 06 Find and Access LTGs or Hosts

## Issue

The current solution displays 
- an action to locate an LTG or Host and the action supplies the information about the vagueness of the query. 
- actions to access single LTGs or Hosts. The access action displays all available LTGs or hosts - indepenent on whether the decker already knows their address or not.

I would like to change the behavior for both actions.

### Locate

The user specifies a query string that can use regex expressions, but does not specify the vagueness of the query string. The server should use the query string to locate all possible LTGs / PLTGs or Hosts were name matches the expression (if successful). Furthermore, the vagueness should be determined by the system based on the regex expression. Specifying the complete name is not vague specifying only some letters surrounded by `*` is most vague. 

The locate action should return the top 5 entries. On the UI the decker / user has to choose one of them. 

As a result, the ID - which is the complete name of the LTG or host is stored for the decker. This implies an extension of the data schema for storing and loading a decker.

### Access

Currently there is an action per LTG/host and all possible LTG/hosts are displayed. In future:
- I would like to restrict to the LTGs/hosts the user has the address for (see Locate)
- the possible hosts or LTGs should be displayed as a dropdown listbox. Whe the user has to specify the exact host or LTG that he wants to access. This implies that there are at most one Access LTG and one Access Host action.+ 

## Solution

### Chosen approach

Reworked Locate and Access into a **discovery-then-selection** model, and made the locate pick a
dedicated modal instead of an inline action.

**Locate (single test → top-5 → pick → store).** The decker types a regex query and no longer
supplies vagueness. New pure helpers in
[operations/LocateMatching.kt](../src/main/kotlin/com/shadowrun/matrix/operations/LocateMatching.kt):
`queryPrecisionFromRegex(query)` derives the TN modifier from the query shape (full literal name →
`VERY_SPECIFIC`; `*frag*` / `.*frag.*` / blank → `VERY_VAGUE`; trailing wildcard → `VAGUE`), and
`rankMatches(query, candidates)` compiles a case-insensitive regex (glob fallback for `*frag*`, invalid
regex → empty), keeps `containsMatchIn` hits, ranks exact matches first then shortest then
alphabetical, and caps at 5. `SystemTestResolver.resolveLocate(...)` runs one System Test with no
cross-turn accumulation. In
[DeckerOperationsExtensions.kt](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt)
a shared `runLocate(...)` backs `locateAccessNode`, `locateFile` and `locateSlave`; on a decker win it
ranks the relevant candidate pool, parks up to 5 names in `Decker.pendingLocate`, and returns
`LocateResult.Candidates(names)` (or `LocateResult.None`). `selectLocateTarget(name)` stores the pick
(into `knownAddresses`, or host-qualified `"<host>::<name>"` in `locatedFiles` / `locatedSlaves`) and
clears `pendingLocate`; `cancelLocateSelection()` clears it without storing.

**Access (strict gating, one action each).** `availableActions()` emits at most one
`AccessLtg(targets)` and one `AccessHost(targets)`, targets filtered to `name ∈ knownAddresses` and
emitted only when non-empty. `LogonToRtg` stays per-target/ungated (RTG backbone); the old per-target
`LogonToLtg` / `LogonToPltg` / `LogonToHost` were removed. Downstream host ops are gated too:
`DOWNLOAD_DATA` / `EDIT_FILE` / `DECRYPT_FILE` appear only for `locatedFiles`, and
`CONTROL_SLAVE` / `EDIT_SLAVE` / `MONITOR_SLAVE` only for `locatedSlaves`; `LOCATE_FILE` / `LOCATE_SLAVE`
stay available for discovery. A successful jack-in / logon seeds the target into `knownAddresses`.

**State, persistence, DTOs, dispatch.** [Decker.kt](../src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt)
gained `knownAddresses` (persisted), run-scoped `locatedFiles` / `locatedSlaves`, and transient
`pendingLocate`. [DeckerLoader.kt](../src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt) reads
an optional `known_addresses` list. `AvailableAction` / `AvailableActionDto` gained
`AccessLtg` / `AccessHost` / `SelectLocateTarget` (and lost the per-target logons); locate `Operation`
DTOs use `paramKind = "query"`; `ActionParams` gained `targetName`; `DeckerStateDto` gained
`knownAddresses`. In
[WebSocketDeckerController.kt](../src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt),
`AccessLtg` / `AccessHost` resolve `targetName` and log on; `SelectLocateTarget` with a name selects it
and with a blank/absent name cancels the pending locate.

**UI.** Locate cards keep a single SEARCH TERM (regex) input, no precision toggle. Access LTG / Access
Host render a name dropdown + CONFIRM. The locate pick is a **special case**: instead of an inline
action card it opens a modal — [SelectLocateModal.tsx](../frontend/src/components/SelectLocateModal.tsx)
renders a fixed-size list of exactly 5 rows; real candidates are clickable rows (clicking selects
immediately — no dropdown, no confirm), and remaining rows are inert empty padding so the dialog never
resizes. Esc or a backdrop click dismisses it, storing no address (via `cancelLocateSelection`).
[ActionsPanel.tsx](../frontend/src/components/ActionsPanel.tsx) filters `SelectLocateTarget` out of the
card list and drives the modal; styles are in [App.css](../frontend/src/App.css).

**Tests.** New [LocateMatchingTest.kt](../src/test/kotlin/com/shadowrun/matrix/operations/LocateMatchingTest.kt)
and [LocateAndAccessIntegrationTest.kt](../src/test/kotlin/com/shadowrun/matrix/integration/LocateAndAccessIntegrationTest.kt)
(jack-in → Access Host absent → locate → select → Access Host offered for only the located host →
logon). Rewritten locate / `selectLocateTarget` tests and updated gating/Access expectations across
`DeckerOperationsTest.kt`, `SystemOperationsTest.kt`, `DeckerVisibilityTest.kt`, `DtoMappingTest.kt`,
`WebSocketServerIntegrationTest.kt`, `ScenarioBuilder.kt`, `FileOperationsTest.kt`,
`SlaveOperationsTest.kt`. `gradlew.bat test integrationTest` — BUILD SUCCESSFUL; frontend
`tsc` / `vite build` clean.

### Options considered but not taken

- **Keep the SR3 multi-turn interrogation accumulation (net successes toward a 5/3 threshold).** —
  rejected: the ticket asks for a query that returns the top-5 matches for the decker to choose from,
  which is fundamentally a single discovery test, not an accumulate-until-pinned dialogue. Keeping
  accumulation would contradict the requested pick-one UX and add state with no user-visible benefit.
- **Let the decker keep choosing the query vagueness (precision selector).** — rejected: the ticket
  explicitly requires the *system* to derive vagueness from the query shape, so the precision control
  was removed in favour of `queryPrecisionFromRegex`.
- **Render `SelectLocateTarget` as an inline action card with a dropdown + CONFIRM (the first
  implementation).** — rejected per user feedback: the selection is a special, blocking case and
  should interrupt with a modal; a fixed 5-row clickable list (padded, non-resizing) is clearer than a
  dropdown and removes the extra confirm click.
- **Gate Access only for non-attached targets (allow directly-attached logons without an address).** —
  rejected: the ticket asks to restrict Access strictly to addresses the decker holds, so gating is
  applied uniformly even to directly-reachable targets (only the RTG backbone stays ungated).
