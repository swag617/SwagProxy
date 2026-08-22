# Crash Handling & Tuning

## Config keys

| Key | Default | Purpose |
|---|---|---|
| `supervisor.restart-on-crash` | `true` | Whether SwagProxy relaunches Velocity at all after an unexpected (nonzero-exit) crash. |
| `supervisor.crash-loop-threshold` | `3` | After this many crashes within a rolling 10-minute window, SwagProxy stops relaunching automatically and prints a loud diagnostic. |
| `supervisor.rollback-window-seconds` | `120` | How long after a staged update is applied SwagProxy watches for a crash before treating the update — and, on a fresh install, the current build combination — as confirmed good. |
| `supervisor.jvm-args` | `["-Xms512M", "-Xmx2G"]` | Extra JVM arguments used when launching Velocity, e.g. heap sizing. |

## What happens on an unexpected exit

`ProxySupervisor.handleExit()` runs this sequence, in order, every time Velocity exits without SwagProxy having requested it (i.e. not a graceful `restart now`/scheduled restart/shutdown):

1. Log the crash and record its timestamp (a rolling 10-minute window, old entries pruned automatically).
2. **If** a staged update was applied within `rollback-window-seconds` of this crash: automatically roll it back (see [Update, Staging & Rollback](../architecture/updates-and-rollback.md)) and relaunch with the restored jars.
3. **Else if** the install still needs [candidate probing](../architecture/candidate-probing.md) (Velocity or Geyser has never been confirmed good): try the next ranked candidate combination and relaunch immediately, no backoff.
4. **Else** (ordinary crash-loop path): if the crash count in the last 10 minutes has reached `crash-loop-threshold`, stop relaunching and print a diagnostic. Otherwise, relaunch after an exponential backoff delay.

## Exponential backoff

Relaunch delay backs off exponentially per crash in the 10-minute window — **1s, 2s, 4s, 8s...**, capped at 60 seconds — so a persistent crash doesn't spin the CPU or hammer the download APIs.

## The stop diagnostic

Once the threshold is hit, SwagProxy stops relaunching automatically and prints a clearly-marked block:

```
=====================================================================
 SwagProxy: Velocity has crashed 3 times in the last 10 minutes (threshold: 3).
 Automatic relaunching has been stopped to avoid a crash loop.
 Check logs/latest.log for the cause, then run 'restart now' to try again.
=====================================================================
```

Running `restart now` resets the crash counter and tries again. Check `logs/latest.log` (SwagProxy's own log) and `proxy/logs/latest.log` (Velocity's own log) for the underlying cause first.

## Graceful shutdown

A JVM shutdown hook (registered in `Bootstrapper.run()`) sends `shutdown` to Velocity's console and waits up to 30 seconds for it to exit cleanly on Ctrl+C or process termination, rather than leaving an orphaned subprocess. If Velocity doesn't exit in time, it's forcibly terminated.

## Next steps

- [Fresh-Install Candidate Probing](../architecture/candidate-probing.md)
- [Console Commands](console-commands.md)
- [Troubleshooting](../reference/troubleshooting.md)
