# Console Commands

Typed directly into SwagProxy's **own** console (`ConsoleRouter`, reading stdin on a dedicated daemon thread) — not Velocity's.

| Command | Effect |
|---|---|
| `status` | Show whether Velocity is running (with pid), recent crash count vs. threshold, and rollback-watch state. |
| `restart now` | Gracefully restart Velocity immediately, applying any staged updates. |
| `restart in <time>` | Schedule a one-off restart, e.g. `restart in 10m`, `restart in 1h30m`, `restart in 90` (bare seconds). |
| `update check` | Check all enabled components for updates and stage anything newer right away. |
| `update apply` | Alias for `restart now`, phrased for "I know something's staged, apply it." |
| `help` | Print this list. |
| *(anything else)* | Forwarded verbatim to Velocity's own console. |

## `status` output

```
Velocity process: RUNNING (pid 12345)
Recent crashes (last 10 min): 0/3
```

If a crash-loop threshold has been hit, an extra line appears: `[crash-loop threshold exceeded — use 'restart now' to try again]`. If updates were just applied and SwagProxy is watching for a rollback-triggering crash, a `Watching for rollback: N component(s) updated at <time>` line appears too.

## `restart in <time>` duration format

Parsed by `DurationParser` — accepts combinations like `10m`, `1h30m`, `90` (bare number = seconds), and similar shorthand. An invalid duration string logs a clear error instead of crashing the console thread.

## Everything else is passthrough

Any line that doesn't match a recognized supervisor command is sent straight to Velocity's stdin untouched — so you can still run Velocity's own built-in commands (`/velocity`, `/server`, `/glist`, `/send`, `/shutdown`, without the leading slash) or any command from a plugin installed in `proxy/plugins/` directly from SwagProxy's console.

## Next steps

- [Restart Schedule & Warnings](restart-schedule.md)
- [Crash Handling & Tuning](crash-handling.md)
