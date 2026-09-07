## Issue

The join screen only showed a decker handle field. A jack-in location field was missing, and neither field had defaults.

## Solution

Added a JACK IN LOCATION input field to the join screen alongside the existing DECKER HANDLE field. Both fields are required before the JACK IN button is enabled. Defaults: `HeadCrash` (decker handle) and `UCAS-SEA` (Seattle LTG from `grid.yaml`).

Frontend changes:
- `messages.ts`: added `jackPointName: string` to `JoinMessage`
- `useWebSocket.ts`: threaded `jackPointName` through `join()`, the observer auto-reconnect handler (`registeredJackPointRef`), and persisted both the reconnect token and jackpoint in `sessionStorage` (survives page refresh per UI-02). Auto-reconnect now requires a non-empty jackpoint to prevent silent corrupt state when session history predates this field.
- `App.tsx`: added the JACK IN LOCATION input with default `UCAS-SEA`; `handleSubmit` passes both fields to `join()`

Backend `JoinMessage.jackPointName: String = ""` already existed; no backend changes were needed.
