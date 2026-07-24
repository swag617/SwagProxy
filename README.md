# SwagProxy

A self-updating, self-restarting dual-protocol (Java + Bedrock) proxy
launcher for any Minecraft network.

SwagProxy is a **standalone Java application** — not a Bukkit plugin, not a
Velocity plugin, not a new proxy implementation. It downloads and boots
[Velocity](https://papermc.io/software/velocity) with
[Geyser](https://geysermc.org/) and [Floodgate](https://wiki.geysermc.org/floodgate/)
as plugins, then supervises that process for its entire life: auto-updating
components, running scheduled restarts, and recovering automatically from
crashes or bad updates.

There is exactly **one proxy**. Players connect to SwagProxy — Java over TCP,
Bedrock over UDP — and stay connected to it for their whole session; moving
between backend servers (e.g. via your own hub/lobby plugin) just repoints
the existing connection. Never chain SwagProxy behind or in front of
another proxy.

---

## Quick start

```
mkdir my-proxy && cd my-proxy
java -jar SwagProxy.jar
```

On an empty folder, this single command will:

1. Generate a commented `swagproxy.yml` with sane defaults.
2. Download the latest Velocity, Geyser, and Floodgate builds — no manual
   downloads needed.
3. Generate `proxy/velocity.toml` and patch `proxy/plugins/Geyser-Velocity/config.yml`.
4. Generate `proxy/forwarding.secret`.
5. Launch Velocity, listening for Java clients on port `25565` and Bedrock
   clients on UDP port `19132` by default.

Edit `swagproxy.yml` and restart (or use the `restart now` console command)
to change ports, backend servers, MOTD, restart schedule, or update
settings — it's the **only** file you should hand-edit.

### Requirements

- Java 21 or newer on the machine running SwagProxy. Velocity itself is
  launched using whichever `java` binary SwagProxy was started with
  (`java.home`), so make sure that JVM is new enough for whatever Velocity
  version you're tracking (Velocity's own release notes will say).
- Outbound HTTPS access to `download.geysermc.org` and `fill.papermc.io`
  (or your configured mirrors) so SwagProxy can fetch updates.

---

## Directory layout

```
SwagProxy.jar
swagproxy.yml            <- the ONLY file the admin edits
proxy/                    <- managed Velocity installation
  velocity.jar
  velocity.toml           <- regenerated from swagproxy.yml every boot
  forwarding.secret
  plugins/
    Geyser-Velocity.jar
    floodgate-velocity.jar
    Geyser-Velocity/config.yml   <- generated once, then only patched
logs/                     <- SwagProxy's own rotating logs (proxy/logs/ has Velocity's own)
backups/                  <- previous jar versions, used for rollback
data/
  builds.json             <- tracks the live build and any rolled-back ("skipped") builds per component
```

---

## Configuration reference (`swagproxy.yml`)

| Key | Purpose |
|---|---|
| `java-port`, `bedrock-port`, `bind` | Listen ports/address. `bind: 0.0.0.0` (the default) is public-facing, matching Velocity's own default — restrict it to a specific interface if you don't want that. |
| `motd`, `max-players`, `online-mode` | Server-list MOTD (MiniMessage), the displayed max player count, and whether Java clients need a premium Mojang account. Bedrock/Floodgate players are unaffected by `online-mode`. |
| `servers` | Map of backend name -> `host:port`. |
| `try-order` | Which backend(s) new connections try first. |
| `forwarding` | Only `modern` is supported — SwagProxy generates/maintains `forwarding.secret` for you. |
| `updates.velocity/geyser/floodgate` | Per-component `auto: true/false` toggle; Velocity also has a `channel: stable\|experimental` setting (see "Update selection" below). |
| `updates.velocity/geyser/floodgate.pin` | Emergency override: pin to an exact version (e.g. `"4.0.0"`) or `"version-buildid"` (e.g. `"4.0.0-6"`), bypassing channel selection and automatic advancement entirely. Unset by default. |
| `updates.contact` | Contact URL/email sent in SwagProxy's User-Agent on every download API request (PaperMC's API policy requires this). |
| `updates.check-interval-minutes` | How often SwagProxy polls for new builds. |
| `updates.apply` | Always `on-restart` in v1 — staged updates are never hot-swapped into a running proxy. |
| `restart-schedule` | List of `{time, timezone, days}` entries — see below. |
| `warnings`, `warning-message`, `restart-message`, `warning-command-template` | Warning broadcast timing/text — see "Restart warnings" below. |
| `supervisor.*` | Crash-loop/rollback tuning and Velocity JVM args — see "Crash handling & rollback". |

### Restart schedule & timezones

Each `restart-schedule` entry is independent and **carries its own
timezone** (any `java.time.ZoneId`, e.g. `America/New_York`, `UTC`,
`Europe/London`) — SwagProxy never assumes the host machine's local
timezone. Example: a proxy hosted on a UTC server can still restart at
4:30 AM *Eastern* every day, correctly following DST, by setting
`timezone: "America/New_York"`.

```yaml
restart-schedule:
  - time: "04:30"
    timezone: "America/New_York"
    days: [MON, TUE, WED, THU, FRI, SAT, SUN]
```

### Restart warnings

At each offset in `warnings` (seconds before the restart), SwagProxy:

1. Logs the warning to its own console/log.
2. Sends a console command to Velocity built from `warning-command-template`
   (default `"alert {message}"`), with `{message}` filled in from
   `warning-message` (which itself has `{time}` filled in with a
   human-readable countdown).

**Important:** vanilla Velocity has no built-in broadcast/chat command (only
`/velocity`, `/server`, `/glist`, `/send`, `/shutdown`). For the warning to
actually reach players in-game, install a small broadcast-capable plugin
(several free `/alert`-style Velocity plugins exist) into `proxy/plugins/` —
it's just another jar, dropped in the same way as any manual update (see
"Manual jar drops" below). Without one, the warning is still logged by
SwagProxy itself, and the command simply fails harmlessly in Velocity's own
log ("command not found"). The final restart message (`shutdown <message>`)
always works regardless, since `/shutdown <reason>` is a real built-in
Velocity command.

### Manual console commands

Typed directly into SwagProxy's own console (not Velocity's):

| Command | Effect |
|---|---|
| `status` | Show whether Velocity is running, recent crash count, and rollback-watch state. |
| `restart now` | Gracefully restart Velocity immediately, applying any staged updates. |
| `restart in <time>` | Schedule a one-off restart, e.g. `restart in 10m`, `restart in 1h30m`, `restart in 90`. |
| `update check` | Check all enabled components for updates and stage anything newer right away. |
| `update apply` | Alias for `restart now`, phrased for "I know something's staged, apply it." |
| `help` | Print this list. |
| *(anything else)* | Forwarded verbatim to Velocity's own console. |

---

## How updates, staging, and rollback work

1. On a timer (`updates.check-interval-minutes`) and at every boot, SwagProxy
   polls each enabled component's official download API for the latest
   build and compares it against `data/builds.json`.
2. A newer build is downloaded to a temp file, its SHA256 verified against
   the API's published hash (when available), then atomically moved to
   `<name>.jar.staged` next to the live jar. **The live jar is never touched
   outside of an actual restart.**
3. At the next restart (scheduled or manual), for every component with a
   `.staged` file present: the current live jar is moved to
   `backups/<component>-<old-build>.jar`, the staged file takes its place,
   and `data/builds.json` is updated. SwagProxy then watches for crashes for
   `supervisor.rollback-window-seconds` (default 120s).
4. If Velocity crashes (or crash-loops) within that window, SwagProxy
   **automatically restores every jar it just swapped** from `backups/`,
   marks the bad build id as "skipped" in `builds.json` so it's never
   re-staged, logs a clear warning, and relaunches with the known-good
   jars. A bad build should never take the network down for more than one
   relaunch cycle.

### Manual jar drops

You can drop a replacement jar straight into `proxy/plugins/` (or replace
`proxy/velocity.jar`) yourself — just name it `<TargetFileName>.staged`
(e.g. `Geyser-Velocity.jar.staged`), exactly like SwagProxy's own updater
does. It goes through the **exact same** apply-and-backup code path at the
next restart: the file it replaces is backed up, and if the new jar turns
out to be bad, the same crash/rollback logic protects you.

### Velocity update selection

For `updates.velocity.channel: stable` (the default), SwagProxy walks
Velocity versions newest-to-oldest and, for each version, prefers *that
version's own* `STABLE` build over its `RECOMMENDED` build — the newest
version with an eligible build wins, full stop. `RECOMMENDED` (PaperMC's
hand-picked "safest" flag) is only ever a per-version fallback, never a
global override of a newer version's `STABLE` build — GeyserMC tracks new
versions aggressively (Bedrock force-updates), so staying current matters
more here than PaperMC's own deliberately-conservative recommendation.
`channel: experimental` widens the same per-version walk to also accept
`BETA` and `ALPHA` builds.

**Pin (emergency override):** if the current best combination of Velocity/
Geyser/Floodgate doesn't work together, set `updates.<component>.pin` to a
known-good version (or exact `version-buildid`) in `swagproxy.yml`. A
pinned component always uses exactly that build — no channel selection, no
automatic advancement, and it's also excluded from the fresh-install
probing described below.

### Fresh-install recovery (candidate probing)

The rollback described above only works when there's a previous jar to
restore — on a brand-new install there isn't one. If Velocity fails to boot
and there's no known-good baseline yet for Velocity or Geyser (i.e. neither
has ever survived a boot), SwagProxy automatically tries the next-best
Velocity build; once Velocity's options (up to 5) are exhausted, it starts
trying the next-best Geyser build instead (resetting Velocity back to its
own best pick). Floodgate isn't varied — it's not a plausible cause of a
Velocity crash. Once *any* combination survives the rollback window, it's
recorded as the known-good baseline and probing never activates again for
an unrelated later crash. Only if every combination is exhausted does
SwagProxy fall back to the standard crash-loop-threshold stop, with a
diagnostic listing every combination it tried.

---

## Crash handling & rollback tuning

- `supervisor.restart-on-crash`: whether SwagProxy relaunches Velocity at all
  after an unexpected (nonzero-exit) crash.
- `supervisor.crash-loop-threshold`: after this many crashes within a
  rolling 10-minute window, SwagProxy stops relaunching automatically and
  prints a loud diagnostic. Run `restart now` once you've investigated (see
  `logs/latest.log` and `proxy/logs/latest.log`) to try again — this resets
  the crash counter.
- Relaunch delay backs off exponentially per crash in the window (1s, 2s,
  4s, ... capped at 60s) so a persistent crash doesn't spin the CPU.
- `supervisor.rollback-window-seconds`: see "How updates... work" above.

---

## Backend migration checklist

Converting an existing "Geyser Standalone + BungeeCord" network to
SwagProxy:

- **Remove Geyser-Spigot from every backend.** Geyser must live only on the
  proxy — a copy on a backend is a routing/auth bypass hole.
- **Keep/install Floodgate-Spigot** on any backend that needs the Floodgate
  API (e.g. your hub server), and **copy the proxy's Floodgate `key.pem`** to every
  backend's Floodgate folder, replacing whatever key was there before — all
  instances must share one key or Bedrock players get rejected at the
  backend hop. **Never distribute this key outside your own network.**
- **Switch backends to Velocity modern forwarding.** This touches three
  files on every backend:
  - `config/paper-global.yml`:
    ```yaml
    proxies:
      velocity:
        enabled: true
        online-mode: true    # match the proxy's own online-mode setting
        secret: "<paste the contents of proxy/forwarding.secret here>"
    ```
  - `spigot.yml`: `settings.bungeecord: false` — **must** be `false` when
    using Velocity modern forwarding (that setting is for the old
    BungeeCord/legacy forwarding protocol, not this one).
  - `server.properties`: `online-mode=false` — the *proxy* authenticates
    players now, not the backend. **This is exactly why the firewall bullet
    below is mandatory:** an unfirewalled backend with `online-mode=false`
    can be joined directly by anyone claiming any username, completely
    bypassing the proxy's auth and Velocity's forwarding secret.
- **Firewall every backend** so only the proxy machine can reach them
  directly — players should never be able to connect straight to a backend.
- **Install ViaVersion on backends** — Bedrock clients (via Geyser) are
  force-updated ahead of the Java client release cycle, so backends need it
  to stay compatible.
- **Decommission** the old standalone Geyser server and old BungeeCord once
  you've verified logins work end to end through SwagProxy.

---

## Login flow (for reference)

1. Everyone connects to SwagProxy first — Java via TCP `java-port`, Bedrock
   via UDP `bedrock-port`.
2. Bedrock: Geyser translates the session continuously (not just at login);
   Floodgate authenticates via Xbox (`.`-prefixed username, no Java account
   needed). Java clients pass straight through — Geyser never touches them.
3. Both land on the `try-order[0]` backend (typically your hub server).
4. The hub's `bungeecord:main` `Connect` plugin messages come back to this
   same Velocity instance, which repoints the player at the target backend.
   Velocity answers that channel natively — SwagProxy needs no code for it.

---

## Known implementer notes

See `DECISIONS.md` for the full detail on a few places where live testing
diverged from the original design assumptions (a stale PaperMC download API,
a newer Geyser config schema than older docs describe, and vanilla
Velocity's lack of a built-in broadcast command) and how each was resolved.
