# PROJECT PROMPT DOCUMENT — "SwagProxy"
### Self-updating, self-restarting dual-protocol (Java + Bedrock) proxy launcher for the Swag Network
**Companion to:** SwagHub-AI-Prompt-Document.md (the hub plugin runs on a backend Paper server BEHIND this proxy)
**Author:** Luke (SwagDev)

---

## 0. AGENT INSTRUCTIONS (read first)

You are building **SwagProxy** to completion from this document. Rules of engagement:
1. Build in the order given in §7. After every step the project must compile; after step 4 it must boot a working proxy.
2. Write complete files — never stubs, never "// rest of code here". When later asked to modify code, return full files and never delete existing code unless explicitly told to.
3. Where this document specifies exact behavior (staging, rollback, config ownership, timezone handling), implement it exactly — do not simplify away the rollback or crash-loop logic; they are the point of the project.
4. Verify external endpoints (§4.2) against live documentation before hardcoding; code base URLs as config-overridable constants.
5. If a genuine ambiguity blocks you, make the safest reasonable choice, note it in a `DECISIONS.md`, and continue — do not stall.
6. Deliverables: the full Maven project, generated-default `swagproxy.yml`, `README.md` (per §6.4), `DECISIONS.md`, and the §6.5 test checklist with instructions for each item.

---

## 1. What SwagProxy Is (and Is Not)

SwagProxy is a **standalone Java supervisor application** (NOT a Bukkit plugin, NOT a Velocity plugin, NOT a new proxy implementation) that:
1. Downloads and boots **Velocity** with **Geyser-Velocity** and **Floodgate-Velocity** as plugins, producing one process that accepts Java (TCP) and Bedrock (UDP) players and routes both to backend servers.
2. **Auto-updates Geyser/Floodgate/Velocity** by polling their official download APIs, staging new jars without touching the running proxy.
3. **Schedules restarts** (SwagRestartScheduler-style: named schedules, timezone-aware, countdown warnings) and applies staged updates during the restart window.
4. Supervises the Velocity subprocess: console passthrough, crash detection, automatic relaunch, and **automatic rollback** of a bad update.

**Hard non-goals:** No Minecraft protocol code of any kind. No reimplementing or forking Velocity/Geyser. No Bukkit APIs (SwagAPI cannot run here — Velocity's plugin API is incompatible with Bukkit; SwagProxy itself replaces the SwagAPI-updater and SwagRestartScheduler roles for the proxy machine).

**Why this design:** replacing a "full server jar" (old Geyser Standalone topology) is painful; with SwagProxy, Geyser is just a file in `plugins/` — drag-and-drop a jar manually OR let the updater stage it, and the supervisor swaps it at the next restart. The supervisor lives outside the proxy JVM, which is the only clean way to restart/swap a running server automatically.

---

## 2. Tech & Project Setup

- Plain Java 21 application, **Maven** (consistent with the rest of the SwagDev ecosystem), `maven-shade-plugin` for a single fat `SwagProxy.jar` with `Main-Class` set in the manifest.
- Dependencies: minimal. Java's built-in `HttpClient` for downloads, SnakeYAML (shaded) for config, Gson or built-in for JSON API responses. No frameworks.
- Runs via `java -jar SwagProxy.jar` from a working directory it fully manages:
```
SwagProxy.jar
swagproxy.yml            <- the ONLY file the admin edits
proxy/                   <- managed Velocity installation
  velocity.jar
  velocity.toml          <- generated from swagproxy.yml (regenerated each boot, with a marker comment)
  forwarding.secret
  plugins/
    Geyser-Velocity.jar
    floodgate-velocity.jar
    Geyser-Velocity/config.yml    <- generated on first install, then only patched (see 4.3)
logs/
backups/                 <- previous jar versions for rollback
```

---

## 3. swagproxy.yml (single source of truth)

Generated with commented defaults on first run. Keys (all commented in the shipped file):
- `java-port: 25565`, `bedrock-port: 19132`, `bind: 0.0.0.0`
- `motd:` (MiniMessage), `max-players`, `online-mode: true`
- `servers:` map of name → address (hub, survival, ...), `try-order: [hub]`
- `forwarding: modern` (default; generates/keeps `forwarding.secret`) — README must explain pasting the secret into each backend's `paper-global.yml`
- `updates:` per-component: `velocity: {auto: true, channel: stable}`, `geyser: {auto: true}`, `floodgate: {auto: true}`; `check-interval-minutes: 60`; `apply: on-restart` (only mode in v1 — never hot-swap)
- `restart-schedule:` list of entries: `{time: "04:30", timezone: "America/New_York", days: [MON..SUN]}` plus `warnings: [600, 300, 60, 10]` (seconds before, broadcast messages MiniMessage-configurable)
- `supervisor:` `restart-on-crash: true`, `crash-loop-threshold: 3`, `rollback-window-seconds: 120`, `jvm-args: [...]`

---

## 4. Core Behaviors

### 4.1 Bootstrap
On start: read/create `swagproxy.yml` → ensure Velocity jar present (download if missing) → ensure Geyser + Floodgate jars present → generate `velocity.toml` from config (set `force-key-authentication = false` automatically — required for Bedrock chat; set servers, try-order, forwarding) → ensure Geyser config has `auth-type: floodgate` and correct `bedrock.port`/`remote` settings → launch Velocity as a subprocess with configured JVM args, inheriting a piped stdin/stdout.

### 4.2 Download sources (verified endpoints)
- **Geyser & Floodgate:** GeyserMC Downloads API — `https://download.geysermc.org/v2/projects/{geyser|floodgate}/versions/latest/builds/latest/downloads/velocity`. Also fetch the builds metadata endpoint first to compare build numbers against a locally tracked `builds.json` (mirror the AutoUpdateGeyser approach of tracking last applied build) so unchanged builds are not re-downloaded.
- **Velocity:** PaperMC downloads API (project `velocity`). NOTE TO IMPLEMENTER: PaperMC has migrated API versions before — fetch the current endpoint documentation at build time and code the base URL as a config-overridable constant.
- All downloads: to a temp file, verify SHA256 if the API provides one, then atomic-move to `plugins/<name>.jar.staged` (or `velocity.jar.staged`). Never write directly over a live jar.

### 4.3 Config generation rules
`velocity.toml` is regenerated each boot from `swagproxy.yml` (it is marked "managed by SwagProxy — edit swagproxy.yml instead"). Geyser/Floodgate configs are generated once, then only **patched** for the keys SwagProxy owns (ports, auth-type) so admins can still hand-tune the rest without losing changes.

### 4.4 Restart scheduler
- Timezone-aware scheduling per entry (use `java.time.ZoneId`; do not assume server timezone — this was the differentiating SwagRestartScheduler feature).
- Warning broadcasts: written into Velocity's stdin as console commands (Velocity's built-in `alert` message command or equivalent) at each configured warning offset.
- At restart time: send graceful `shutdown <message>` via stdin → wait up to 30s for clean exit → apply staged jars (move current → `backups/<name>-<build>.jar`, `.staged` → live) → relaunch.
- Manual triggers: interactive supervisor console commands typed into SwagProxy itself: `restart now|in <time>`, `update check`, `update apply`, `status`. Anything not a supervisor command is forwarded to Velocity's console untouched.

### 4.5 Crash handling & rollback (the meticulous part)
- If Velocity exits nonzero: relaunch (if `restart-on-crash`), with exponential backoff; after `crash-loop-threshold` consecutive crashes within 10 minutes, stop and print a loud diagnostic.
- **Rollback:** if a crash (or crash loop) occurs within `rollback-window-seconds` of applying staged updates, automatically restore the `backups/` jars from before the swap, relaunch, mark the bad build number in `builds.json` as skipped, and log a clear warning. A bad Geyser build must never take the network down for more than one relaunch cycle.

### 4.6 Logging
Velocity output passthrough to console + `logs/` with rotation. SwagProxy's own actions prefixed `[SwagProxy]` so update/restart activity is greppable.

---

## 5. Network Topology & Login Flow (context for the implementer)

**There is exactly ONE proxy.** SwagProxy replaces BOTH the old Geyser Standalone server AND the old BungeeCord. Players connect to SwagProxy and stay connected to it for their entire session; "moving servers" means SwagProxy repoints their existing connection at a different backend. Never chain SwagProxy behind/in front of another proxy.

**Login flow:**
1. Everyone connects to SwagProxy first — Java via TCP `java-port`, Bedrock via UDP `bedrock-port`.
2. Bedrock: Geyser translates the session (and continues translating every packet for the whole session — it is not a one-time login step), Floodgate authenticates via Xbox (`.` username prefix, no Java account needed). Java: passes straight through, Geyser never touches them.
3. Both land on the `hub` backend (first entry in `try-order`), where SwagHub runs.
4. SwagHub's `bungeecord:main` `Connect` messages (SwagHub doc §3) come back to this same Velocity, which repoints the player at the target backend. Velocity answers that channel natively; SwagProxy needs no code for this.

**Backend migration checklist (README section — for converting an existing Standalone+BungeeCord network):**
- Remove Geyser-Spigot from ALL backends if present — Geyser lives only on the proxy; a backend copy is a routing/auth bypass hole.
- Keep/install Floodgate-Spigot on backends that need the Floodgate API (SwagHub does), and copy the proxy Floodgate's `key.pem` to every backend Floodgate folder, replacing old keys — all instances must share one key or Bedrock players are rejected at the backend hop. Never distribute this key.
- Switch backends from BungeeCord forwarding (`bungeecord: true` in spigot.yml → false) to Velocity modern forwarding: paste `forwarding.secret` into each backend's `paper-global.yml`.
- Firewall all backends from direct player connections; only the proxy may reach them.
- ViaVersion recommended on backends (Bedrock clients force-update ahead of Java).
- Decommission the old Geyser Standalone server and old BungeeCord once verified.

---

## 6. Quality Bar

1. Full compiling Maven project, complete files, no placeholders. When modifying later, return full files and never delete existing code unless told to.
2. First-run experience: `java -jar SwagProxy.jar` on an empty folder → fully working dual-protocol proxy with commented config, zero manual downloads.
3. Every failure path has a human-readable message (download failed, port in use, bad YAML key) — never a bare stack trace for operator error.
4. README: setup, config reference, how updates/staging/rollback work, backend checklist, and the manual drag-and-drop workflow (drop jar in `plugins/`, it's applied on next restart like a staged one — supervisor should detect and back up the replaced jar too).
5. Manual test checklist: clean first boot; Java + Bedrock login; scheduled restart fires with warnings in correct timezone; staged Geyser update applied on restart; simulated bad jar triggers rollback; crash-loop stops after threshold.

---

## 7. Suggested Build Order

1. Config load/generate + directory bootstrap.
2. Download service (Geyser API first, then Velocity) + build tracking + staging.
3. Process supervisor: launch, passthrough, crash relaunch.
4. velocity.toml generation + Geyser config patching; first full boot milestone.
5. Restart scheduler + warning broadcasts + staged-apply + backups.
6. Rollback logic + supervisor console commands.
7. README + test checklist pass.

---

## 8. Open Questions

- ~~Companion Velocity plugin?~~ **DECIDED: No.** Auto-update and restarting are fully baked into the supervisor; restart warnings via Velocity's console `alert` are sufficient. Do not add plugin-extension seams, do not create a second artifact — keep the supervisor self-contained.
- ~~Whitelist/first-boot security~~ **DECIDED: default `bind: 0.0.0.0`** (this is a public-facing proxy and matches Velocity's own default), with a commented note in `swagproxy.yml` explaining how to restrict it. All open questions are now resolved — the agent should build without waiting on decisions.
