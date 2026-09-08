# Matrix of Shadowrun — Claude Code Notes

## Project Layout

| Folder | Contents |
|---|---|
| `design/` | Design documents; PRDs are files starting with `prd_` |
| `code_review/` | Code review documents |
| `.tickets/` | Error tickets |

## Running tests

This is a Windows project. Use `gradlew.bat`, not `./gradlew`.

```powershell
# Unit tests (excludes integration/)
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test"

# Integration tests only
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat integrationTest"

# Both
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test integrationTest"
```

## Project Skills

Three project-specific skill files live in `.claude/skills/`. **Read the relevant one before starting the corresponding task** — they override the brief notes below.

| Task | Skill file |
|---|---|
| Resolve a ticket (end-to-end: code + tests + docs + commit + GitHub issue) | `.claude/skills/process_tickets.md` |
| Code review (full-codebase, completeness-provable) | `.claude/skills/code_review_guideline.md` |
| Design-vs-code conformance audit | `.claude/skills/align_design_and_code.md` |

## Ticket format and workflow

Tickets live in `.tickets/`. Naming convention: `NN_PascalCaseTitle.md` (two-digit counter prefix).

For the full end-to-end workflow (PRD check, manual test gate, GitHub issue sync, commit format, ADR requirement) read `.claude/skills/process_tickets.md` before writing any code.

## Architecture layers

| Layer | Packages / path | Key entry points |
|---|---|---|
| Engine | `game/`, `combat/`, `decker/`, `ic/`, `network/`, `operations/`, `programs/`, `config/`, `common/`, `utility/` | `Game.kt`, `GameContext.kt` |
| Server | `server/`, `server/dto/` | `WebSocketDeckerController.kt`, `SessionRegistry.kt`, `dto/Messages.kt`, `Main.kt` |
| UI | `frontend/src/` | `App.tsx`, `hooks/useWebSocket.ts`, `components/` |

Dependency direction: UI → WebSocket → Server → Engine (never reversed).

All packages above are under `src/main/kotlin/com/shadowrun/matrix/`.

## PRD cross-reference

| File | Domain areas |
|---|---|
| `design/prd_core.md` | Jacking in (M-01–M-18), cyberdeck/program mechanics (CD-01–CD-26), system operations catalog (SO-01–SO-14), cybercombat (CC-01–CC-33), IC catalog (ICC-01–ICC-15) |
| `design/prd_game.md` | Game loop, active-icon architecture, IC target selection, available-actions list |
| `design/prd_ui.md` | React layout (5 panels), WebSocket reconnect + reconnect token, action-card input controls |

## GitHub CLI

`gh` is not on the bash PATH. Use the full path:

```bash
'C:\Program Files\GitHub CLI\gh.exe'
```

When a body contains backticks or other characters that break shell quoting, write it to a temp
file and use `--body-file`, then delete the file afterwards:

```bash
# 1. Write body
cat > /tmp/issue_body.md << 'EOF'
...markdown body...
EOF

# 2. Update issue
powershell -Command "& 'C:\Program Files\GitHub CLI\gh.exe' issue edit <N> --body-file '/tmp/issue_body.md'"

# 3. Clean up
rm /tmp/issue_body.md
```

Alternatively, write the temp file with the Write tool and pass a Windows path:

```bash
powershell -Command "& 'C:\Program Files\GitHub CLI\gh.exe' issue edit <N> --body-file '.tickets\body_tmp.md'"
```

## Test strategy

- **Unit tests** (`gradlew.bat test`): isolated logic — single class, no IO, no server.
- **Integration tests** (`gradlew.bat integrationTest`): multi-component scenarios involving game state, combat, movement, or the WebSocket server end-to-end.
- For any ticket touching game-loop or movement/combat logic, add or extend an integration test under `src/test/…/integration/` using the `ScenarioBuilder` + `IntegrationTestBase` DSL.
