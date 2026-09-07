---
description: Process a ticket / issue of the customer that is either formulated in the .tickets folder or as a github issue. The issue is resolved by updating the PRD, design and code as well as closing the ticket / issue.
---

## Procedure

Process a ticket specified by its number <number>.

### 0) Create a todo list 

Create a todo list with steps 1 to 10. They must be all processed in this sequence.

### 1) Read the ticket

- Have a look into folder ./tickets of the project and search for a file with the following pattern: `<number>_*.md`. The filename has a pattern `<number>_<title>.md`.
- Have a look into the github issue # `<number>` of this project. Also fetch the title of the issue.

If only one of both exists, create the other one considering the issue number and the title. Also take over the description of the issue.

### 2) Read all PRDs

Read all three PRD files before planning:
- `design/prd_core.md`
- `design/prd_game.md`
- `design/prd_ui.md`

### 3) Plan a solution

Based on the issue description (and title) plan a solution for this issue.

### 4) Double Check against PRD

Based on the planned solution, check whether the PRD would have to be modified. If the PRD must be changed, get an **approval from the user before continuing**.

### 5) Apply the correction

Implement the solution by updating the code and the unit tests. Then run all tests and confirm they are green before proceeding:

```powershell
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test integrationTest"
```

Do not proceed to documentation until all tests pass.

### 6) Manual Test

At this point the user has to apply an additional MANDATORY manual test. Only when the user CONFIRMS that everything is OK, continue with (7).

### 7) Update PRD, Design and Documentation

Continue with this only when the user confirmed the manual test in the previous item.

Update the PRD (if necessary), the design documents and the player guide in the documentation folder.

### 8) Update the ticket

Update the ticket (file `.tickets/<number>_<title>.md`) with the solution. The Solution section must include:

- **Chosen approach** — what was done and why, including key files changed.
- **Options considered but not taken** — at least one alternative with a reason for rejection (serves as an ADR).

### 9) Commit the changes

Stage all modified source, test, design, and documentation files. Commit with a message referencing the ticket number:

```
Fix #<N>: <title>
```

### 10) Update the GitHub Issue

#### 1. Write the issue body to a temp file

Use the Write tool to create `.tickets/issue_body_tmp.md` with the full issue body (original problem description + solution content from the ticket).

#### 2. Update the issue body

```bash
powershell -Command "& 'C:\Program Files\GitHub CLI\gh.exe' issue edit <N> --body-file '.tickets\issue_body_tmp.md'"
```

Never pass the body inline via `--body` or a PowerShell here-string — backticks in markdown break the quoting. Always use `--body-file`.

#### 3. Close the issue

```bash
powershell -Command "& 'C:\Program Files\GitHub CLI\gh.exe' issue close <N>"
```

#### 4. Delete the temp file

Remove `.tickets\issue_body_tmp.md` after the issue is updated.

### Confirm and Finalize

Double check whether the every task is complete including the following:

- All steps of the todo list are marked as done.
- A ticket in folder ./tickets exists and a git issue exists. The non-existing one was created.
- The ticket and the git issue contain the same issue description and solution.
- The PRD, design, documentation, code and all tests have been updated, if relevant.
- All changed files have been staged and commited. (If not stage all changed files and commit additionally)
