# Things to Note

Items surfaced during Step 1 of the review remediation (findings S-1/S-2/S-5 — closing the
client-trust boundary) and the follow-up design/PRD reconciliation. None of these block Step 1;
they are recorded so they aren't lost.

## Secure-by-default inertness (needs a loader before it does anything)

Step 1 moved two mechanics to server-side state:

- `Decker.knownPasscodes: Set<String>` — originally also drove the Make Comcall licensed-decker
  exception. **That exception has since been descoped** (see below); `knownPasscodes` now serves only
  logon legitimacy (`performLogon`, `DeckerNavigationExtensions.kt`).
- `Host.datalineScannerRatings: List<Int>` — drives Tap Comcall scanner detection (highest rating).

`datalineScannerRatings` defaults to **empty**. Until a scenario/loader populates it, every
`TAP_COMCALL` faces no scanner. This is secure by default — but it also means the mechanic is inert in
a real game until someone wires it up. **There is currently no loader that sets it.** Populating it
(scenario data / host construction) is out of scope for Step 1 and is required before Tap Comcall
behaves differently per scenario. (`MAKE_COMCALL` now always runs the full System Test.)

## Unused `cmd` param in `dispatchCommsOp`

After the controller stopped forwarding client fields to `makeComcall`/`tapComcall`
(`WebSocketDeckerController.kt`), the `cmd` parameter in `dispatchCommsOp` is no longer read on that
path. It was left in place intentionally rather than churning the signature during a security fix —
flag it for a future cleanup pass.

## Passcode key: RTG (design) vs host.name (code) — resolved by descoping

Historically the core design keyed Make Comcall passcode possession to an **RTG**
(`decker.hasValidPasscode(rtg)`, `design/design_core/operations.md`; "valid RTG passcode",
`design/prd_core.md`), while `design/design_core/movement.md` (passcode devalidation) keyed by
**host** — an internal inconsistency. The shipped code keyed `Decker.knownPasscodes` /
`hasValidPasscode` by **host/target name**, matching `movement.md` and the `performLogon` convention.

**Update (2026-09-04): the Make Comcall licensed-decker (RTG-passcode) exception was descoped by
product decision.** The RTG references were removed from `prd_core.md` and `operations.md`, the
`hasValidPasscode` helper was deleted (its only caller was `makeComcall`), and the T-1 skip test was
removed. `knownPasscodes` remains for logon legitimacy only (host-keyed), so the RTG-vs-host
divergence no longer exists — it is **resolved**, not deferred. `design/deferred.md` was updated
accordingly.

## Pre-existing design contradiction (NOT introduced by Step 1)

The scanner-test opposed-ness contradicts itself across the rules docs:

- `design/prd_core.md:344` — "Opposed Computer vs. scanner Device Rating test."
- `design/design_core/operations.md:663` — "not opposed — the scanner does not roll."

The code is **non-opposed** (the decker rolls, the scanner does not), matching `operations.md`.
Step 1 did not touch this logic. Reconcile in the design docs independently.

## Stale review-artifact reference

`design/discrepancies.md` **does not exist** — it was superseded by `design/align.md`, which is a
process/methodology document with no logged per-item findings. Prior discrepancy logs are archived
under `.old_analysis/`. Also, the `MC-` prefix defined in `align.md` means **"Missing/coverage,"**
not "Make Comcall." (Any note or memory referring to `design/discrepancies.md` or "MC- = Make
Comcall" is stale.)
