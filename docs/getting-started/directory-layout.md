# Directory Layout

SwagProxy manages a fixed set of paths relative to wherever `SwagProxy.jar` is run from (`Layout.java` is the single source of truth for these paths in code):

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

## What lives where

| Path | Purpose |
|---|---|
| `swagproxy.yml` | The only file you hand-edit. Everything else on this page is generated or managed from it. |
| `proxy/velocity.jar` | The live Velocity jar. Swapped only during a restart, never while running. |
| `proxy/velocity.toml` | Regenerated from `swagproxy.yml` on **every boot** — don't hand-edit, your changes will be overwritten. |
| `proxy/forwarding.secret` | A random secret generated once (32 bytes, base64url). Paste its contents into every backend's `paper-global.yml` for modern forwarding. |
| `proxy/plugins/Geyser-Velocity.jar` / `floodgate-velocity.jar` | Live Geyser and Floodgate jars, same staged/swap lifecycle as Velocity. |
| `proxy/plugins/Geyser-Velocity/config.yml` | Generated once by Geyser itself on first boot, then only **patched** (specific keys) by SwagProxy on subsequent boots — see [Architecture Overview](../architecture/overview.md). |
| `logs/` | SwagProxy's own rotating log files. Velocity's own logs live separately under `proxy/logs/`. |
| `backups/` | Previous jar versions moved here before a staged update is applied, named `<component>-<old-build>.jar`. Used to restore on rollback. |
| `data/builds.json` | Tracks the currently-live build id, any staged-but-not-yet-applied build, a per-component skip list of builds that were rolled back, and whether a component has ever been "confirmed good." |

## Staged files

New downloads never overwrite a live jar directly. They land next to it with a `.staged` suffix:

```
proxy/velocity.jar            <- live
proxy/velocity.jar.staged     <- downloaded, waiting for the next restart
```

`Layout.stagedPathFor(Path live)` is the single function that computes this naming convention, used identically whether the `.staged` file came from SwagProxy's own updater or was dropped in by hand (see [Manual Jar Drops](../architecture/updates-and-rollback.md#manual-jar-drops)).

## Next steps

- [Configuration Reference](configuration.md) — every `swagproxy.yml` key
- [Update, Staging & Rollback](../architecture/updates-and-rollback.md) — how `.staged` files get applied
