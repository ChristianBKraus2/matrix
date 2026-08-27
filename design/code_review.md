# Code Review Plan

## Scope

Three code parts reviewed independently per category, plus one cross-cutting review per
category. Output: `code_review/` folder, 32 files total.

## Code Parts

| Part | Source |
|---|---|
| **game_logic** | `src/main/kotlin/…` except `server/` — packages: `game`, `network`, `operations`, `decker`, `combat`, `ic`, `programs`, `common`, `utility`, `config`, `accessories` |
| **server** | `src/main/kotlin/…/server/` — `MatrixServer.kt`, `SessionRegistry.kt`, `WebSocketDeckerController.kt`, `DeckerDisconnectedException.kt`, `dto/` |
| **ui** | `frontend/src/` — `App.tsx`, `hooks/useWebSocket.ts`, `components/*.tsx`, `types/messages.ts`, `App.css` |

## Review Categories

| # | Category | Focus |
|---|---|---|
| 1 | **correctness** | Logic bugs, edge cases, off-by-one, wrong state assumptions |
| 2 | **security** | Input validation, client trust, auth enforcement, data exposure |
| 3 | **concurrency** | Thread safety, coroutine/blocking mixing, races, deadlocks |
| 4 | **error_handling** | Silent swallowing, missing guards, error propagation to user |
| 5 | **performance** | Algorithmic complexity, unnecessary allocations, blocking in hot paths |
| 6 | **architecture** | SRP, coupling, layer violations, separation of concerns |
| 7 | **maintainability** | Naming, DRY, dead code, cyclomatic complexity |
| 8 | **testing** | Coverage gaps, test quality, missing edge cases |

## Output File Matrix

### Per-part (24 files)
```
code_review/correctness_game_logic.md     code_review/correctness_server.md     code_review/correctness_ui.md
code_review/security_game_logic.md        code_review/security_server.md        code_review/security_ui.md
code_review/concurrency_game_logic.md     code_review/concurrency_server.md     code_review/concurrency_ui.md
code_review/error_handling_game_logic.md  code_review/error_handling_server.md  code_review/error_handling_ui.md
code_review/performance_game_logic.md     code_review/performance_server.md     code_review/performance_ui.md
code_review/architecture_game_logic.md    code_review/architecture_server.md    code_review/architecture_ui.md
code_review/maintainability_game_logic.md code_review/maintainability_server.md code_review/maintainability_ui.md
code_review/testing_game_logic.md         code_review/testing_server.md         code_review/testing_ui.md
```

### Cross-cutting (8 files)
```
code_review/correctness_complete.md
code_review/security_complete.md
code_review/concurrency_complete.md
code_review/error_handling_complete.md
code_review/performance_complete.md
code_review/architecture_complete.md
code_review/maintainability_complete.md
code_review/testing_complete.md
```

## Finding Format (per file)

```markdown
# {Category} Review — {Part}

## Summary
One-paragraph overview from this lens.

## Findings

### [SEVERITY] Short title
**File:** relative/path/to/File.kt:line
**Issue:** What the problem is.
**Recommendation:** How to fix it.

## No Issues Found In
- Areas that looked clean
```

Severity levels: **CRITICAL** · **HIGH** · **MEDIUM** · **LOW** · **INFO**

## Known Issues (pre-identified from initial read)

These should be confirmed and expanded during review:

| Area | File | Issue |
|---|---|---|
| concurrency/server | `SessionRegistry.kt:25-63` | `synchronized(lock)` held inside `suspend` functions — unsafe with coroutines |
| concurrency/server | `WebSocketDeckerController.kt:53,73` | `runBlocking` inside a coroutine context — deadlock risk |
| concurrency/server | `SessionRegistry.kt:22` | `@Volatile pendingAction` — visibility only, check-then-act not atomic |
| error_handling/server | `MatrixServer.kt:29` | `runCatching {}` silently swallows all parse/dispatch errors with no logging |
| security/server | `SessionRegistry.kt:29` | No validation on `deckerName` length or content |
| correctness/server | `WebSocketDeckerController.kt:220,230` | `LOCATE_DECKER` and `SWAP_MEMORY` silently return no-op strings |
| maintainability/server | `WebSocketDeckerController.kt:143,241` | `@Suppress("UNUSED_PARAMETER")` on `host`, `diceRoller`, `cmd` |
| correctness/ui | `hooks/useWebSocket.ts` | No state reset on reconnect — stale game state after disconnect |

## Execution

Run all 32 reviews. Recommended execution order: one category at a time, all three parts plus
complete in each pass. This way related findings are fresh together.

After all files are written, verify tests still pass:
```
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test integrationTest"
```
