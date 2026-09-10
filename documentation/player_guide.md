# Player Guide — Matrix of Shadowrun

You are a **decker**: a specialist hacker who jacks into the Matrix, navigates corporate networks, and extracts data while staying one step ahead of Intrusion Countermeasures. This guide covers how to use the application from the moment you connect to the moment you log off — or get dumped.

---

## Connecting and Jacking In

Open the application in your browser. You will see the join screen with the title **MATRIX OF SHADOWRUN v1.0 — CONNECT TO THE GRID**.

While the browser establishes a WebSocket connection, the status reads *ESTABLISHING CONNECTION…*. Once the connection is live, a form appears:

1. Enter your **decker handle** (up to 32 characters; letters, numbers, spaces, underscores, and dashes are allowed).
2. Enter the **jackpoint address** — the name of the LTG or host you are physically connected to (e.g. *UCAS-SEA-2206* or *Mitsuhama Pagoda*). Your game master provides this.
3. Click **JACK IN**.

If the name is already taken, too long, or the server is full, an error message appears below the input. Correct it and try again.

Once your credentials are accepted the server jacks you in immediately. The Narrative Panel shows the result — *Logged on to LTG: \<name\>* on success, or a failure message if the logon contest did not go your way. If jack-in succeeds you enter the game and wait for your turn to begin.

---

## The Game Screen

When it is your turn the screen shows five panels arranged in a grid.

```
┌─────────────────────────────────────────────────┐
│                  LOCATION                        │
├───────────────┬──────────────┬───────────────────┤
│    DECKER     │  NARRATIVE   │     ENTITIES      │
├───────────────┴──────────────┴───────────────────┤
│                   ACTIONS                        │
└─────────────────────────────────────────────────┘
```

### Location Panel (top)

Shows where you are in the Matrix right now:

| Field | Meaning |
|---|---|
| Node name | The RTG, LTG, PLTG, or host you currently occupy |
| Region | Geographic region of the node |
| Security code | Letter + number rating of this node (e.g. *Orange-6*) |
| Alert status | Current security alert level (None / Passive / Active / Shutdown) |
| Security tally | Accumulated tally that drives alert escalation |
| Counts | Number of connected hosts, LTGs, or RTGs visible from here |

### Decker Panel (left)

Your character's current state:

- **Name** — your handle
- **Pinned by Black IC** warning — displayed prominently if a Black IC has you pinned; you cannot jack out voluntarily while pinned
- **Physical damage** — boxes filled left-to-right; when all are filled you suffer severe consequences
- **Mental damage** — same scale; filling all boxes dumps you from the Matrix
- **Hacking pool** — dice available this turn (remaining / total shown)
- **MCP rating** — your cyberdeck's Master Control Program rating
- **Loaded programs** — each utility shown with its rating as filled dots

### Narrative Panel (centre)

A scrolling log of everything that has happened: action results, dice roll outcomes, IC activity, alert changes, and error messages.

When it is your turn the header reads **YOUR TURN — AWAITING ACTION**. When you are waiting for the game master's side to advance, the header returns to the panel title.

### Entities Panel (right)

Everything you can currently see in the node — IC programs, host subsystems, files, and remote devices. Each entity has a card showing:

- **IC program** — name, optional *ANALYZED* badge (shows rating, behaviour, and guarded node once analyzed), or *UNKNOWN* if not yet analyzed
- **Host subsystem** — type (ACCESS / CONTROL / INDEX / FILES / SLAVE) and a description
- **File** — name, size in Mp, *SCRAMBLED* badge if copy-protected, *POINTER* badge if it is a redirect
- **Device** — name and system address

You must analyze an IC program before you know its rating and what it is protecting.

When you first log onto a host you see only its subsystems — the IC, files, and devices residing there are **hidden until you locate them**. Use **Locate IC** to reveal lurking IC (a guarding Scramble is also revealed by **Analyze Subsystem**), **Locate File** to reveal files, and **Locate Slave** to reveal devices. IC that attacks you, or that the host triggers as your tally climbs, also becomes visible on its own.

### Actions Panel (bottom)

Every action available to you right now is shown as a card with:

- **Label** — what the action does
- **Type badge** — `FREE`, `SIMPLE`, or `COMPLEX` (more complex actions cost more of your turn)
- **Target** — which entity this action applies to, if any
- **Parameter controls** — some actions require additional input before you can submit them (see below)

Click a card to select it. If it has no parameters, it is submitted immediately. If it has parameters, controls appear on the card — fill them in and confirm.

---

## Taking Actions

### Actions without parameters

Click the action card. The action is sent to the server immediately and the result appears in the Narrative Panel.

### Actions with parameters

Some actions expose inline controls when you click them:

| Parameter type | Control | When it appears |
|---|---|---|
| **Search term** | Text field | Locate operations (Locate File, Locate Slave, Locate Access Node) — type what you are looking for; a regex is accepted. You do not set a vagueness level; the system judges how vague your term is from its shape. |
| **New content** | Text area | Edit File — type the replacement file contents |
| **Data size** | Numeric stepper | Upload Data — specify how many Mp to upload |
| **Target choice** | Dropdown + confirm | Access LTG / Access Host — pick which known LTG or host to enter |

Fill in the parameter and click the confirm button that appears on the card.

When a Locate search succeeds, a **pop-up list** of up to five matches appears. Click the one you want to select it (or press Esc / click outside the list to cancel without choosing anything).

### Action timeout

If you do not submit an action within **120 seconds** of your turn starting, the server advances automatically and your turn is skipped.

---

## Navigating the Matrix

The Matrix is a layered network. Movement is always a **COMPLEX** action.

```
RTG (Regional Telecommunications Grid)
 └─ LTG (Local Telecommunications Grid)
     └─ PLTG (Private LTG)
     └─ Host
```

| Action | What it does |
|---|---|
| Logon to RTG | Move to a connected RTG (the backbone; always available, no address needed) |
| Access LTG | Enter a known LTG or private LTG — pick the destination from a dropdown |
| Access Host | Enter a known host — pick the destination from a dropdown |
| Graceful Logoff | Cleanly disconnect and exit the Matrix |
| Jack Out | Emergency disconnect — skips the logoff protocol; may cause dump shock |

**Access LTG** and **Access Host** only list destinations whose address you already know. You learn an address by jacking in / logging on to it, or by running **Locate Access Node** and picking the result. If a host or LTG is not in your known addresses, it will not appear as a destination.

---

## Operating Inside a Host

Once inside a host you have access to the full set of Matrix operations.

### Before you act: analyze first

Most objects start as unknowns. Use these to learn what you are dealing with:

- **Analyze Host** — reveals the host's full security rating
- **Analyze Security** — examines the active security configuration
- **Analyze Subsystem** — reveals what a host subsystem does
- **Analyze IC / Analyze Icon** — reveals an IC program's rating, behaviour, and the node it is guarding

### Finding things: Locate operations

Locate operations are searches. You type a **search term** describing your target (a regex works; the system decides how vague it is from the shape of the term — a full exact name is treated as very specific, a bare fragment wrapped in `*` as very vague). A single test resolves the search; there is no multi-turn grind and no threshold to reach.

If the search succeeds it shows up to **five matches** in a pop-up list. Pick one (or press Esc to cancel). Your choice is remembered:

- **Locate Access Node** — find a gateway (LTG, private LTG, or host). The chosen address is stored, and only then does an **Access LTG** / **Access Host** action offer that destination. You cannot enter a host or LTG you have not located first (aside from the RTG backbone).
- **Locate File** — find a specific file. You must locate a file before you can Download, Edit, or Decrypt it.
- **Locate Slave** — find a remote-controlled device. You must locate a slave before you can Control, Edit, or Monitor it.
- **Locate IC** — find a lurking IC program.

### File operations

| Action | What it does |
|---|---|
| Download Data | Copy a file off the host to your deck |
| Edit File | Overwrite a file's contents with new text you provide |
| Upload Data | Write a new file onto the host; specify size in Mp |
| Decrypt File | Break the scramble protection on a copy-protected file |

### Slave (device) operations

Remote devices controlled by the host can be interacted with once located:

| Action | What it does |
|---|---|
| Control Slave | Issue a command to the device |
| Edit Slave | Modify the device's control programming |
| Monitor Slave | Read the device's current status |

### Communications

| Action | What it does |
|---|---|
| Make Comcall | Initiate a Matrix call |
| Tap Comcall | Intercept an active call on the host |

### Utility actions

| Action | Condition |
|---|---|
| Invoke Medic | Available only if your Medic utility is loaded; attempts to heal stun damage |
| Null Operation | Do nothing; wastes the action deliberately |
| Relocate Icon | Move your icon within the host to a different node |
| Decrypt Access | Break an encrypted access node |
| Decrypt Slave | Break encryption on a slave controller |

---

## Security, Tally, and Alerts

Every action that the host detects adds to the **security tally**. As the tally rises, the host escalates:

| Alert level | What changes |
|---|---|
| **None** | Normal operations; IC patrols only |
| **Passive** | Host launches additional IC; response time drops |
| **Active** | Aggressive IC deployed; trace operations begin |
| **Shutdown** | Host locks down; all exits encrypted; emergency response imminent |

Analyze Security and Analyze Host give you the current tally and the thresholds for escalation, letting you judge how much time you have left.

---

## IC (Intrusion Countermeasures)

IC programs defend the host and will act against you. Before an IC is analyzed its capabilities are unknown. After analysis, the Entities Panel shows:

- **Rating** — how powerful the IC is
- **Behaviour** — what it does (e.g. Probe, Trace, Killer, Black)
- **Guarded node** — which subsystem it is protecting

Common IC behaviours to know:

| Behaviour | Threat |
|---|---|
| Probe | Detects your presence and raises the tally |
| Trace | Attempts to track your physical location |
| Scramble | Scrambles one of your programs |
| Blaster | Attacks your mental damage track |
| Killer | Attacks your physical damage track |
| Black IC | Extremely dangerous; can pin you, preventing voluntary jack-out |

When **Black IC** pins you, the Decker Panel shows a warning. You cannot use Graceful Logoff until you break free.

---

## Damage and Survival

Your decker has two damage tracks:

- **Physical damage** — represents harm to your body (from Black IC attacks while jacked in via a direct neural interface)
- **Mental damage** — represents biofeedback and dump shock

If mental damage fills all boxes you are forcibly ejected from the Matrix (dumped) and suffer dump shock. If physical damage fills all boxes the consequences are severe — up to and including death in the fiction.

Monitor both tracks in the Decker Panel. If you are taking damage, consider whether completing the run is worth the risk.

---

## Reconnecting After a Disconnect

If your browser disconnects you can rejoin with the same handle. Use the same decker name and jackpoint address in the join form. The server issues a reconnect token when you first join — if your browser preserved the session, reconnection is automatic and the jackpoint address is re-used. If not, re-enter both your decker name and the same jackpoint address used originally to reclaim the session.

---

## Logging Off

**Graceful Logoff** (COMPLEX action, available from any node): the clean way out. No tally penalty. Use this whenever you have time.

**Jack Out** (FREE action): emergency exit. Bypasses the logoff sequence. Risk of dump shock. Use only when things have gone wrong.

You cannot log off at all while **pinned by Black IC**. Defeat or evade the IC first.

---

## Quick Reference

| Situation | What to do |
|---|---|
| Just entered a host | Analyze Host, then Analyze Security |
| Unknown IC in the entities list | Analyze IC before it acts |
| Need a file | Locate File → Download Data |
| Need to modify a file | Locate File → Decrypt File (if scrambled) → Edit File |
| Need to control a device | Locate Slave → Control Slave |
| Tally climbing fast | Consider Graceful Logoff before alert escalates |
| Black IC warning showing | Cannot logoff; must fight or evade the IC |
| Out of time | Jack Out (FREE) and deal with the consequences later |
