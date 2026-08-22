# Restart Schedule & Warnings

## Schedule entries and timezones

Each `restart-schedule` entry is independent and **carries its own timezone** (any `java.time.ZoneId`, e.g. `America/New_York`, `UTC`, `Europe/London`) — SwagProxy never assumes the host machine's local timezone. Example: a proxy hosted on a UTC server can still restart at 4:30 AM *Eastern* every day, correctly following DST, by setting `timezone: "America/New_York"`.

```yaml
restart-schedule:
  - time: "04:30"
    timezone: "America/New_York"
    days: [MON, TUE, WED, THU, FRI, SAT, SUN]
```

- `time` — 24-hour `HH:mm`.
- `timezone` — any valid IANA zone id.
- `days` — a list of `MON`.."SUN" (full names like `MONDAY` also accepted); defaults to every day if omitted.

At boot, SwagProxy logs the next scheduled restart in that entry's own timezone, not the host's.

## Warning broadcasts

At each offset in `warnings` (seconds before the restart, one shared list applied to **every** schedule entry — not configurable per-entry), SwagProxy:

1. Logs the warning to its own console/log.
2. Sends a console command to Velocity built from `warning-command-template` (default `"alert {message}"`), with `{message}` filled in from `warning-message` (which itself has `{time}` filled in with a human-readable countdown like "10 minutes" or "30 seconds").

At the restart itself, Velocity receives `shutdown <restart-message>` — `/shutdown <reason>` is a real built-in Velocity command, so this part always works regardless of what plugins are installed.

> **Important:** vanilla Velocity has **no built-in broadcast/chat command** (only `/velocity`, `/server`, `/glist`, `/send`, `/shutdown`). For the earlier warnings to actually reach players in-game, install a small broadcast-capable plugin (several free `/alert`-style Velocity plugins exist) into `proxy/plugins/` — it's just another jar, dropped in the same way as any manual update (see [Manual Jar Drops](../architecture/updates-and-rollback.md#manual-jar-drops)). Without one, the warning is still logged by SwagProxy itself, and the command simply fails harmlessly in Velocity's own log ("command not found").

## Manual one-off restarts

The `restart in <time>` console command (see [Console Commands](console-commands.md)) uses the exact same warning/restart code path as a scheduled entry — a 15-second-out manual restart with `warnings: [10]` will fire its 10-second warning at exactly the right offset before executing.

## Next steps

- [Console Commands](console-commands.md)
- [Configuration Reference](../getting-started/configuration.md)
