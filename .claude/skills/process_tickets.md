---
description: Process a ticket / issue of the customer that is either formulated in the .tickets folder or as a github issue. The issue is resolved by updating the PRD, design and code as well as closing the ticket / issue.
---

## Procedure

Process a ticket specified by its number `<number>`.

### 0) Create a todo list 

Create a todo list with steps 1 to 11. They must be all processed in this sequence.

### 1) Read the ticket

- Have a look into folder ./tickets of the project and search for a file with the following pattern: `<number>_*.md`. The filename has a pattern `<number>_<title>.md`.
- Have a look into the github issue # `<number>` of this project. Also fetch the title of the issue.

If only one of both exists, create the other one considering the issue number and the title. Also take over the description of the issue.

If neither exists, stop and ask the user which to create first before proceeding.

### 2) Read all PRDs

Read all three PRD files before planning:
- `design/prd_core.md`
- `design/prd_game.md`
- `design/prd_ui.md`

### 3) Plan a solution

Based on the issue description (and title) plan a solution for this issue.

### 4) Double Check against PRD

Based on the planned solution, check 
- whether the PRD would have to be modified. 
- whether the core of the game (everything except folder `/src/main/kotlin/com/shadowrun/matrix/game' or `frontend`) must be changed

If the PRD or the core game must be changed:

> ⛔ **STOP — User approval required.**  
> Present your planned changes to the PRD / core and do NOT continue until the user explicitly approves in this conversation.

### 5) Apply the correction

Implement the solution by updating the code and the unit tests. Then run all tests and confirm they are green before proceeding:

```
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test integrationTest"
```

If any tests fail, investigate the failure, fix the code, re-run, and repeat until all tests are green. Do not proceed to the next step until all tests pass.

### 6) Manual Test

At this point the user has to apply an additional MANDATORY manual test.

> ⛔ **STOP — Manual test required.**  
> Do NOT continue until the user explicitly confirms in this conversation that everything is OK. Only then continue with step 7.

### 7) Update PRD, Design and Documentation

Update the PRD (if necessary), the design documents and the player guide in the documentation folder.

### 8) Update the ticket

Update the ticket (file `.tickets/<number>_<title>.md`) with the solution. Add another section between the problem description and the solution that describes further clarification that you achieved through communicating with the user - if this applies. The ticket must follow this exact structure:

```markdown
# <N> <Title>

## Issue
<original problem description>

## Solution

### Chosen approach
<what was done and why, including key files changed>

### Options considered but not taken
- **<Alternative>** — rejected because <reason>
```

The `### Options considered but not taken` sub-section is mandatory and must contain at least one alternative (serves as an ADR).

### 9) Commit the changes

Stage all modified source, test, design, and documentation files. Commit with a message referencing the ticket number:

```
Fix #<N>: <title>
```

### 10) Update the GitHub Issue

#### a) Write the issue body to a temp file

Use the Write tool to create `.tickets/issue_body_tmp.md` with the full issue body (original problem description + solution content from the ticket).

#### b) Update the issue body

```bash
powershell -Command "& 'C:\Program Files\GitHub CLI\gh.exe' issue edit <N> --body-file '.tickets\issue_body_tmp.md'"
```

Never pass the body inline via `--body` or a PowerShell here-string — backticks in markdown break the quoting. Always use `--body-file`.

#### c) Close the issue

```bash
powershell -Command "& 'C:\Program Files\GitHub CLI\gh.exe' issue close <N>"
```

#### d) Delete the temp file

Remove `.tickets\issue_body_tmp.md` after the issue is updated.

### 11) Confirm and Finalize

Double check whether every task is complete including the following:

- All steps of the todo list are marked as done.
- A ticket in folder ./tickets exists and a git issue exists. The non-existing one was created.
- The ticket and the git issue contain the same issue description and solution.
- The ticket contains both `### Chosen approach` and `### Options considered but not taken` sub-sections.
- The PRD, design, documentation, code and all tests have been updated, if relevant.
- All changed files have been staged and committed. (If not, stage all changed files and commit additionally.)
