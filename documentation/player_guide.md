# Player Guide — Matrix of Shadowrun

You are a **decker**: a specialist hacker who jacks into the Matrix, navigates corporate networks, and extracts data while staying one step ahead of Intrusion Countermeasures. This guide covers how to use the application from the moment you connect to the moment you log off — or get dumped.

---

## Connecting and Jacking In

Open the application in your browser. You will see the join screen with the title **MATRIX OF SHADOWRUN v1.0 — CONNECT TO THE GRID**.

While the browser establishes a WebSocket connection, the status reads *ESTABLISHING CONNECTION…*. Once the connection is live, a form appears:

1. Enter your **decker handle** (up to 32 characters; letters, numbers, spaces, underscores, and dashes are allowed).
2. Click **JACK IN**.

If the name is already taken, too long, or the server is full, an error message appears below the input. Correct it and try again.

Once your handle is accepted you enter the game and wait for your turn to begin.

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
| **Precision** | Numeric stepper | Locate operations (Locate File, Locate Slave, Locate IC, Locate Access Node) |
| **Search query** | Text field | Locate Access Node — specify what you are searching for |
| **New content** | Text area | Edit File — type the replacement file contents |
| **Data size** | Numeric stepper | Upload Data — specify how many Mp to upload |

Fill in the parameter and click the confirm button that appears on the card.

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
| Logon to RTG | Move to a connected RTG |
| Logon to LTG | Move to a child LTG under your current RTG |
| Logon to PLTG | Move to a private LTG |
| Logon to Host | Enter a host system |
| Graceful Logoff | Cleanly disconnect and exit the Matrix |
| Jack Out | Emergency disconnect — skips the logoff protocol; may cause dump shock |

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

Locate operations are multi-step searches. You choose a **precision** value (higher = more dice committed = better chance of a useful result). Results accumulate across turns until the target is found or you give up.

- **Locate File** — find a specific file
- **Locate Slave** — find a remote-controlled device
- **Locate IC** — find a lurking IC program
- **Locate Access Node** — find a gateway to another system; the search query narrows the target

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

If your browser disconnects you can rejoin with the same handle. Use the same decker name in the join form. The server issues a reconnect token when you first join — if your browser preserved the session, reconnection is automatic. If not, re-entering your name reclaims the session if the token matches.

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
