# Matrix of Shadowrun — Claude Code Notes

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
