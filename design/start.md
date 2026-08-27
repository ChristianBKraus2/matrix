# Starting the Matrix Server

## Start the backend server

```powershell
powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat run"
```

The server starts on **http://localhost:8080** and serves the built React frontend as static files.
The WebSocket endpoint is at `ws://localhost:8080/decker/ws`.

## First steps in the UI

1. Open **http://localhost:8080** in a browser
2. Enter a decker name and click **Join** — the server registers you as the active decker (HeadCrash)
3. The state panel shows the decker's current location and available actions
4. Select an action and submit — the server processes the turn and updates the state

## Frontend dev mode (hot reload)

If you want live-reload while editing the React frontend, start the Vite dev server separately:

```powershell
cd frontend
npm run dev
```

Then open **http://localhost:5173**. WebSocket traffic to `/decker/ws` is proxied to the backend at `localhost:8080`, so the backend must also be running.

## Notes

- The backend game loop runs continuously, waiting for a decker to join via WebSocket before each turn
- Only one decker session is supported at a time (HeadCrash loaded from `headcrash.yaml`)
- Logging is silent by default — `logback-classic` is test-only; add it to `runtimeOnly` in `build.gradle.kts` to enable console logs
