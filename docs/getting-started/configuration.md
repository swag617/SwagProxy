# Configuration Reference

All settings live in `swagproxy.yml`, generated on first launch from the plugin's bundled defaults (`ConfigManager.loadOrCreate`). It is the **only** file you should hand-edit — `proxy/velocity.toml` and Geyser's `config.yml` are generated/patched from it on every boot, and hand edits to SwagProxy-owned keys in those files will be overwritten.

## Full default file

```yaml
# --- Network ---
java-port: 25565
bedrock-port: 19132
bind: 0.0.0.0

motd: "<gradient:#00c2ff:#7b2ff7>A SwagProxy Server</gradient>"
max-players: 100
online-mode: true

# --- Backend servers ---
servers:
  hub: 127.0.0.1:30001

try-order:
  - hub

forwarding: modern

# --- Auto-updates ---
updates:
  velocity:
    auto: true
    channel: stable
    # pin: "4.0.0"
  geyser:
    auto: true
    # pin: "2.11.0"
  floodgate:
    auto: true
    # pin: "2.2.5"
  check-interval-minutes: 60
  apply: on-restart
  contact: "https://github.com/SwagDev"

# --- Restart schedule ---
restart-schedule:
  - time: "04:30"
    timezone: "America/New_York"
    days: [MON, TUE, WED, THU, FRI, SAT, SUN]

warnings: [600, 300, 60, 10]
warning-message: "<yellow>Proxy restarting in <white>{time}</white>!</yellow>"
restart-message: "<red>Restarting now, back in a moment!</red>"
warning-command-template: "alert {message}"

# --- Supervisor ---
supervisor:
  restart-on-crash: true
  crash-loop-threshold: 3
  rollback-window-seconds: 120
  jvm-args:
    - "-Xms512M"
    - "-Xmx2G"
```

## Key reference

| Key | Default | Purpose |
|---|---|---|
| `java-port` | `25565` | TCP port Java clients connect to. |
| `bedrock-port` | `19132` | UDP port Bedrock clients connect to (via Geyser). |
| `bind` | `0.0.0.0` | Address to bind to. `0.0.0.0` matches Velocity's own default and is public-facing — restrict it to a specific interface if this proxy should **not** be reachable from the open internet. |
| `motd` | `"A SwagProxy Server"` | MiniMessage-formatted server-list MOTD. |
| `max-players` | `100` | Displayed max player count. |
| `online-mode` | `true` | Whether Java clients must be logged into a valid premium Mojang account. Bedrock/Floodgate players authenticate via Xbox regardless and are unaffected by this setting. |
| `servers` | `{hub: 127.0.0.1:30001}` | Map of backend name -> `host:port`. |
| `try-order` | `[hub]` | Which backend(s) new connections try first. The first entry is where every player (Java and Bedrock) initially lands. Every name here **must** exist in `servers` — validated at load. |
| `forwarding` | `modern` | The only forwarding mode SwagProxy manages. It generates and maintains `forwarding.secret` for you — validated to be exactly `"modern"`. |
| `updates.velocity.auto` / `.geyser.auto` / `.floodgate.auto` | `true` | Per-component toggle for automatic update checking/staging. |
| `updates.velocity.channel` | `stable` | `stable` or `experimental` — see [Version Selection & Pinning](../reference/version-selection.md). |
| `updates.<component>.pin` | unset | Emergency override: pin to an exact version (`"4.0.0"`) or `"version-buildid"` (`"4.0.0-6"`), bypassing channel selection and automatic advancement entirely. |
| `updates.check-interval-minutes` | `60` | How often SwagProxy polls for new builds. |
| `updates.apply` | `on-restart` | The only supported mode in v1 — staged updates are always applied at the next restart, never hot-swapped. Validated to be exactly `"on-restart"`. |
| `updates.contact` | `"https://github.com/SwagDev"` | Contact URL/email sent in SwagProxy's User-Agent on every download API request (PaperMC's Fill API policy requires this). Set it to your own info. |
| `restart-schedule` | one 04:30 America/New_York entry | List of `{time, timezone, days}` entries — see [Restart Schedule & Warnings](../operations/restart-schedule.md). |
| `warnings` | `[600, 300, 60, 10]` | Seconds-before-restart at which a warning is broadcast, applied to **every** schedule entry (one shared list, not per-entry). |
| `warning-message` | `"<yellow>Restarting in <white>{time}</white>!</yellow>"` | MiniMessage template for each warning; `{time}` becomes a human-readable countdown. |
| `restart-message` | `"<red>Restarting now!</red>"` | MiniMessage broadcast sent the moment the restart begins. |
| `warning-command-template` | `"alert {message}"` | Console command sent to Velocity's stdin at each warning offset. Vanilla Velocity has **no** built-in broadcast command — see [Restart Schedule & Warnings](../operations/restart-schedule.md). |
| `supervisor.restart-on-crash` | `true` | Whether SwagProxy relaunches Velocity at all after an unexpected (nonzero-exit) crash. |
| `supervisor.crash-loop-threshold` | `3` | Crashes within a rolling 10-minute window before SwagProxy stops relaunching automatically. |
| `supervisor.rollback-window-seconds` | `120` | How long after an applied update SwagProxy watches for a crash before treating the update as confirmed good. |
| `supervisor.jvm-args` | `["-Xms512M", "-Xmx2G"]` | Extra JVM arguments used when launching Velocity. |

## Validation

Every value is validated together, and every failure is collected into one human-readable report rather than a stack trace (`ConfigManager`). A config with 4 simultaneous problems (bad port type, an unknown `try-order` reference, an unparseable restart time, and an invalid timezone) produces exactly 4 clear one-line messages and exits with code 1.

Things that are validated:

- `java-port` / `bedrock-port` / `max-players` must be whole numbers.
- `online-mode` must be `true`/`false`.
- `forwarding` must be exactly `"modern"`.
- Every `try-order` entry must exist as a key in `servers`.
- `updates.velocity.channel` must be `"stable"` or `"experimental"`.
- `updates.<component>.pin`, if present, must not be blank.
- `updates.apply` must be exactly `"on-restart"`.
- Each `restart-schedule` entry needs a parseable 24-hour `time` (`HH:mm`) and a valid IANA `timezone`; `days` (if present) must be a list of `MON`.."SUN".
- `warnings` must be a list of numbers.

## Next steps

- [Restart Schedule & Warnings](../operations/restart-schedule.md)
- [Version Selection & Pinning](../reference/version-selection.md)
- [Crash Handling & Tuning](../operations/crash-handling.md)
