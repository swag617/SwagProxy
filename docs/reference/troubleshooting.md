# Troubleshooting

## Velocity won't boot at all on a fresh install

If Velocity or Geyser has never survived a boot, SwagProxy automatically tries the next-best build combination for you — see [Fresh-Install Candidate Probing](../architecture/candidate-probing.md). Watch the logs for `Velocity candidate ranking` / `Installed probe candidate` lines. If every combination is exhausted, the final diagnostic lists everything tried; consider setting `updates.velocity.pin` / `updates.geyser.pin` to a known-good combination (see [Version Selection & Pinning](version-selection.md)).

## Warning broadcasts aren't reaching players in chat

This is expected out of the box. Vanilla Velocity has **no built-in broadcast/chat command** — only `/velocity`, `/server`, `/glist`, `/send`, and `/shutdown`. SwagProxy always logs every warning to its own console regardless, but for the message to reach players in-game you need a small broadcast-capable Velocity plugin (e.g. an `/alert`-style plugin) installed in `proxy/plugins/`. Without one, Velocity's own log will show something like "command not found" for the warning command, harmlessly. See [Restart Schedule & Warnings](../operations/restart-schedule.md).

## "could not find section remote:" in Geyser's logs

This was an issue during early development caused by a stale bundled Geyser config template targeting an older schema (see [Design Decisions](decisions.md#3-geyser-velocity-configyml-schema-corrected)). The bundled template now matches Geyser-Velocity 2.11.0's current schema (`java.auth-type` instead of `remote.auth-type`). If you see this again after a future Geyser update, it likely means Geyser has shipped another config-version bump — check `GeyserConfigManager`'s targeted keys against a freshly-generated `config.yml`.

## Config won't load / "swagproxy.yml is not valid YAML"

SwagProxy reports every validation problem together as a clear, numbered list rather than a stack trace — check the console output for each specific line/key named. Common causes: a `try-order` entry not present in `servers`, an unparseable `restart-schedule` time (must be 24-hour `HH:mm`), or an invalid IANA timezone string. See [Configuration Reference](../getting-started/configuration.md#validation).

## An update keeps getting rolled back

Check `logs/latest.log` and `proxy/logs/latest.log` for the actual Velocity crash cause first — it's usually a plugin or component incompatibility, not a bug in the swap itself. Once you've identified a known-good combination, consider pinning the problematic component with `updates.<component>.pin` (see [Version Selection & Pinning](version-selection.md)) until the upstream issue is resolved.

## SwagProxy stopped relaunching Velocity automatically

You've hit the crash-loop threshold (`supervisor.crash-loop-threshold`, default 3 crashes in 10 minutes). Check the logs for the underlying cause, fix it (or pin a known-good version), then run `restart now` from SwagProxy's console — this resets the crash counter. See [Crash Handling & Tuning](../operations/crash-handling.md).

## Players can join a backend directly, bypassing the proxy

This is a firewall/config gap, not a SwagProxy bug. Every backend must be firewalled so only the proxy machine can reach it directly, and `server.properties` must have `online-mode=false` **only** in combination with that firewall — see the [Backend Migration Checklist](../backend/migration.md) for the full explanation of why this combination is mandatory together.

## Next steps

- [Design Decisions](decisions.md) — the full history of ambiguities and how they were resolved
- [Crash Handling & Tuning](../operations/crash-handling.md)
