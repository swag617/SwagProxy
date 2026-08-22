# 🌉 SwagProxy

> A self-updating, self-restarting dual-protocol (Java + Bedrock) proxy launcher for any Minecraft network

SwagProxy is a **standalone Java application** — not a Bukkit plugin, not a Velocity plugin, not a new proxy implementation. It downloads and boots [Velocity](https://papermc.io/software/velocity) with [Geyser](https://geysermc.org/) and [Floodgate](https://wiki.geysermc.org/floodgate/) as plugins, then supervises that process for its entire life: auto-updating components, running scheduled restarts, and recovering automatically from crashes or bad updates.

There is exactly **one proxy**. Players connect to SwagProxy — Java over TCP, Bedrock over UDP — and stay connected to it for their whole session; moving between backend servers just repoints the existing connection. Never chain SwagProxy behind or in front of another proxy.

---

## Features

- **Zero-manual-download first boot** — generates a commented `swagproxy.yml`, downloads the latest Velocity/Geyser/Floodgate builds, writes `velocity.toml` and Geyser's `config.yml`, and creates `forwarding.secret`, all from one `java -jar SwagProxy.jar`
- **Staged, restart-only updates** — new builds are downloaded and SHA256-verified to a `.staged` file; the live jar is never touched outside of an actual restart
- **Automatic crash rollback** — a bad update that crashes Velocity within the rollback window gets its jars restored automatically and the build marked "skipped"
- **Fresh-install candidate probing** — on a brand-new install with no known-good baseline, a failing first-choice Velocity/Geyser combination is automatically walked through up to 25 ranked alternatives until one boots
- **Timezone-aware restart schedule** — each entry carries its own IANA timezone, independent of the host machine's local time, with staged warning broadcasts
- **Per-component channel selection or version pinning** — track `stable`/`experimental` builds per-version, or pin an exact `version` / `version-buildid` as an emergency override
- **Manual jar drop support** — drop a `<name>.jar.staged` file into `proxy/plugins/` yourself and it goes through the exact same backup/rollback code path as an auto-staged update
- **Supervisor console** — `status`, `restart now`, `restart in <time>`, `update check`, `update apply`, `help`; anything else is forwarded straight to Velocity's own console
- **Exponential crash-loop backoff** — 1s, 2s, 4s... capped at 60s, stopping after a configurable threshold with a clear diagnostic instead of spinning forever

---

## Quick Links

| | |
|---|---|
| [Quick Start](getting-started/quick-start.md) | Get SwagProxy running from an empty folder |
| [Directory Layout](getting-started/directory-layout.md) | What SwagProxy creates and manages on disk |
| [Configuration Reference](getting-started/configuration.md) | Every `swagproxy.yml` key explained |
| [Architecture Overview](architecture/overview.md) | How the pieces fit together |
| [Update, Staging & Rollback](architecture/updates-and-rollback.md) | The full update lifecycle |
| [Console Commands](operations/console-commands.md) | Supervisor console reference |
| [Backend Migration Checklist](backend/migration.md) | Moving from standalone Geyser + BungeeCord |
| [Troubleshooting](reference/troubleshooting.md) | Common issues and fixes |

---

## Requirements

| Dependency | Required |
|---|---|
| Java 21+ (running SwagProxy itself, and whatever Velocity build it launches) | Yes |
| Outbound HTTPS to `download.geysermc.org` and `fill.papermc.io` (or configured mirrors) | Yes |
| A backend server (hub/lobby) for `try-order[0]` to route players to | Yes |
| A Velocity broadcast/`/alert` plugin, for warning messages to reach players in-game | No (soft) |

> SwagProxy contains no Minecraft protocol code of its own — it only downloads, configures, and supervises Velocity/Geyser/Floodgate.

> **Download / Source:** [GitHub](https://github.com/swag617/SwagProxy)
