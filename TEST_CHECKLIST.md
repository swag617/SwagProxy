# SwagProxy manual test checklist

Per §6.5 of the gameplan document. Each item below has been exercised at
least once during development (see notes); "Verified during build" means it
was run live against the real download APIs and a real Velocity/Geyser/
Floodgate stack, not mocked. Re-run these yourself before trusting a new
environment (new OS, new Java version, a real network with real clients).

---

## 1. Clean first boot

**Steps:**
1. Create an empty directory, copy `SwagProxy.jar` into it.
2. Run `java -jar SwagProxy.jar`.

**Expect:** `swagproxy.yml` is generated with comments; Velocity, Geyser,
and Floodgate jars are downloaded automatically (no manual steps); a
`proxy/velocity.toml` and `proxy/plugins/Geyser-Velocity/config.yml` are
generated; `proxy/forwarding.secret` is created; Velocity boots, logs
"Loaded 3 plugins" and "Done (...)!", and is listening on the configured
Java port (default 25565). Geyser logs "Started Geyser on UDP port 19132".

**Status:** ✅ Verified during build (2026-07-22) in an isolated empty
directory — full boot in ~3 seconds, zero manual intervention.

---

## 2. Java + Bedrock login

**Steps:**
1. With SwagProxy running (from #1), connect a Java Edition client to
   `<host>:25565`.
2. Connect a Bedrock Edition client (console, mobile, or Win10) to
   `<host>:19132`.

**Expect:** Both clients land on the `try-order[0]` backend server (e.g.
`hub`). The Bedrock player's username is prefixed with `.` (Floodgate) and
requires no Java account. Both show up if the backend runs `/glist` (via
Velocity) or your hub plugin's player list.

**Status:** ⚠️ **Requires a real backend server and real clients** — not
something this environment can exercise (no Minecraft client, and no
backend/SwagHub server was stood up here). Confirmed indirectly: Velocity's
own log shows both listeners bound successfully (`Listening on
/[0:0:0:0:0:0:0:0]:25565` and Geyser's `Started Geyser on UDP port 19132`),
and `player-info-forwarding-mode = "modern"` / `force-key-authentication =
false` / `java.auth-type: floodgate` were all confirmed present in the
generated configs — these are the three settings that make Bedrock+Java
login/forwarding actually work. **You must still verify with real clients
before going live.**

---

## 3. Scheduled restart fires with warnings, in the correct timezone

**Steps:**
1. Set a `restart-schedule` entry a minute or two in the future (in any
   timezone different from the host machine's own), with `warnings:
   [60, 10]`.
2. Watch the console/log.

**Expect:** At boot, SwagProxy logs "Next scheduled restart (<timezone>):
<timestamp>)" showing the correct local time in *that* timezone, not the
host's. At T-60s and T-10s, "Restart warning: ... remaining" is logged and a
command is sent to Velocity's console (see README "Restart warnings" for why
it may show "command does not exist" without a broadcast plugin installed —
that's expected). At T-0, Velocity receives `shutdown <restart-message>`,
shuts down cleanly, and SwagProxy relaunches it.

**Status:** ✅ Verified during build via the `restart in <time>` manual
command (same code path as scheduled entries): warning fired at the correct
offset (10s before a 15s-out restart), restart executed on time, Velocity
shut down cleanly and relaunched successfully. Timezone-correctness of
`computeNextFireTime` is additionally covered by automated unit tests
(`RestartSchedulerTest`) across a same-timezone and a cross-timezone
(`Asia/Tokyo`) case.

---

## 4. Staged Geyser (or Velocity/Floodgate) update applied on restart

**Steps:**
1. With SwagProxy running, drop a `.staged` file next to the live jar
   (either let `update check` stage a real newer build, or manually copy a
   jar to e.g. `proxy/plugins/Geyser-Velocity.jar.staged` to simulate one).
2. Run `restart now` or `update apply`.

**Expect:** The current live jar is moved to `backups/<component>-<old
build>.jar`, the staged jar takes its place, `data/builds.json` is updated,
and Velocity relaunches successfully with the new jar loaded (check
Velocity's own log for the plugin version string).

**Status:** ✅ Verified during build: staged both a Velocity update and a
Geyser update (via the manual-drop convention) and confirmed via log output
and `data/builds.json` that the backup was created, the swap applied, and
the new build ran successfully with no rollback triggered.

---

## 5. Simulated bad jar triggers automatic rollback

**Steps:**
1. Stage an intentionally broken jar (e.g. a text file renamed `.staged`)
   next to a live component jar.
2. Run `update apply` (or wait for a scheduled restart).

**Expect:** Velocity fails to boot with the bad jar (nonzero exit).
SwagProxy detects this within `rollback-window-seconds`, restores the
previous jar from `backups/`, marks the bad build id as "skipped" in
`data/builds.json` (so it's never re-staged), logs a clear warning, and
relaunches successfully with the restored jar — all within one relaunch
cycle.

**Status:** ✅ Verified during build: staged a corrupt `velocity.jar`,
applied it, watched SwagProxy catch `Error: Invalid or corrupt jarfile`,
roll back automatically, mark the synthetic build id as skipped, and
relaunch successfully — total downtime under 3 seconds.

---

## 6. Crash-loop stops after threshold

**Steps:**
1. Corrupt the live `proxy/velocity.jar` directly (not via a staged update —
   this tests the pure crash-loop path with no rollback candidate).
2. Start SwagProxy and watch it try to launch.

**Expect:** Each crash is logged, relaunches happen with increasing backoff
(1s, 2s, 4s, ...), and after `crash-loop-threshold` (default 3) crashes
within 10 minutes, SwagProxy stops relaunching automatically and prints a
clearly-marked diagnostic block telling you to fix the jar and run
`restart now`.

**Status:** ✅ Verified during build: with a corrupted `velocity.jar` and
`crash-loop-threshold: 3`, SwagProxy relaunched at 1s then 2s backoff, then
stopped after the 3rd crash with the expected diagnostic banner. Restoring
the good jar and running SwagProxy again booted cleanly.

---

## Also worth checking before production use

- **Config validation errors are readable.** Introduce a typo (e.g.
  `java-port: "not a number"`, or a `try-order` entry not present in
  `servers`) and confirm SwagProxy prints a clear, multi-line list of
  problems instead of a stack trace, then exits.
  **Status:** ✅ Verified during build — a config with 4 simultaneous
  problems (bad port type, unknown `try-order` reference, unparseable
  time, invalid timezone) produced exactly 4 clear one-line messages and a
  clean exit code 1, no stack trace.
- **Ctrl+C / process termination shuts Velocity down gracefully** rather
  than leaving an orphaned subprocess — SwagProxy registers a JVM shutdown
  hook that sends `shutdown` to Velocity and waits for it to exit.
  **Status:** ⚠️ Implemented (`Bootstrapper` registers the hook,
  `ProxySupervisor.shutdownForExit()`), not separately live-verified in this
  pass — worth a manual Ctrl+C check.
