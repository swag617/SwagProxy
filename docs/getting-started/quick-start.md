# Quick Start

## Requirements

- **Java 21 or newer** on the machine running SwagProxy. Velocity itself is launched using whichever `java` binary SwagProxy was started with (`java.home`), so make sure that JVM is new enough for whatever Velocity version you're tracking (check Velocity's own release notes).
- **Outbound HTTPS access** to `download.geysermc.org` and `fill.papermc.io` (or your configured mirrors) so SwagProxy can fetch updates.

## First boot

```bash
mkdir my-proxy && cd my-proxy
java -jar SwagProxy.jar
```

On an empty folder, this single command will:

1. Generate a commented `swagproxy.yml` with sane defaults.
2. Download the latest Velocity, Geyser, and Floodgate builds — no manual downloads needed.
3. Generate `proxy/velocity.toml` and patch `proxy/plugins/Geyser-Velocity/config.yml`.
4. Generate `proxy/forwarding.secret`.
5. Launch Velocity, listening for Java clients on port `25565` and Bedrock clients on UDP port `19132` by default.

Edit `swagproxy.yml` and restart (or use the `restart now` console command) to change ports, backend servers, MOTD, restart schedule, or update settings — it's the **only** file you should hand-edit. See the [Configuration Reference](configuration.md) for every key.

> **Tip:** `swagproxy.yml`'s `updates.contact` defaults to a placeholder (`https://github.com/SwagDev`). PaperMC's download API policy requires identifiable contact info in the User-Agent — SwagProxy logs a one-line reminder at startup until you set your own URL or email.

## What gets created

After the first boot, your folder looks like this:

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

See [Directory Layout](directory-layout.md) for the full breakdown of what each path is for.

## Next steps

- Point your backend servers at SwagProxy — see the [Backend Migration Checklist](../backend/migration.md) if you're moving from an existing standalone Geyser + BungeeCord setup.
- Review the [Configuration Reference](configuration.md) to set your MOTD, backend servers, and restart schedule.
- Learn the [Console Commands](../operations/console-commands.md) for day-to-day operation.
