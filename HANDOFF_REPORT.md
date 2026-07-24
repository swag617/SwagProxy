# SwagProxy — Build Handoff Report

**Date:** 2026-07-22
**Built from:** `Gameplan/SwagProxy-AI-Prompt-Document.md`, followed in the §7 build order.
**Status:** Feature-complete for v1 as specified. Compiles clean, all unit
tests pass, and every behavior in the doc has been exercised live against
real Velocity/Geyser/Floodgate builds (not mocked) — see "What was actually
tested" below.

---

## What this project is

A standalone Java 21 application (`com.swag.swagproxy.Main`, Maven, shaded
into a single `SwagProxy.jar`) that downloads and supervises a Velocity
proxy with Geyser + Floodgate plugins: auto-updates components via staging,
runs timezone-aware scheduled restarts with warnings, and auto-rolls-back a
bad update if it crashes Velocity within a configurable window. It is not a
plugin of any kind and contains no Minecraft protocol code, per the doc's
hard non-goals.

## Package layout

```
com.swag.swagproxy
  Main, Layout, Bootstrapper, BootstrapException     - entry point, path layout, startup orchestration
  config/          - swagproxy.yml model + loader/validator (ConfigManager, SwagProxyConfig, ...)
  download/        - GeyserApiClient, VelocityApiClient, UpdateManager (stage/apply/rollback), BuildTracker
  process/         - ProxySupervisor (launch/passthrough/crash-relaunch), ConsoleRouter (manual commands)
  schedule/        - RestartScheduler (timezone-aware fire-time math, warnings)
  velocity/        - VelocityTomlWriter, GeyserConfigManager (config generation/patching)
  util/            - Log (rotating file+console), Sha256, DurationParser, HttpUtil
```

31 Java files total (28 main + 3 test). 13 JUnit tests, all passing
(`DurationParserTest`, `RestartSchedulerTest`, `VelocityApiClientTest`).

## Deliverables (per §0.6 / §6)

- `pom.xml` — Maven project, `maven-shade-plugin` fat jar, `Main-Class` set.
- `src/main/resources/default-swagproxy.yml` — the generated-default config, fully commented.
- `src/main/resources/default-geyser-config.yml` — bundled Geyser config template (see decision #3 below — this is the *actual* file a live Geyser 2.11.0 generates, not a guess).
- `README.md` — setup, full config reference, update/staging/rollback explanation, backend migration checklist, manual drag-and-drop workflow.
- `DECISIONS.md` — 6 logged ambiguity resolutions, all discovered/verified via live testing, not guessed.
- `TEST_CHECKLIST.md` — the §6.5 checklist, with a per-item live-verification status.
- `HANDOFF_REPORT.md` — this file.

## Build order followed (§7) — all steps complete

1. Config load/generate + directory bootstrap — done, validated with a
   4-simultaneous-error bad-config test (clean messages, exit 1, no stack trace).
2. Download service (Geyser API, then Velocity) + build tracking + staging — done.
3. Process supervisor: launch, passthrough, crash relaunch — done.
4. velocity.toml generation + Geyser config patching; first full boot
   milestone — done, **and actually booted**: Velocity 4.0.0 + Geyser
   2.11.0 + Floodgate 2.2.5 all loaded and listened on both ports in a live test.
5. Restart scheduler + warning broadcasts + staged-apply + backups — done.
6. Rollback logic + supervisor console commands — done.
7. README + test checklist — done (this pass).

## Key decisions made (full detail in DECISIONS.md)

1. **PaperMC's v2 download API is dead** (confirmed live: HTTP 410 Gone).
   Switched to the new Fill v3 API (`fill.papermc.io`), which has a
   different shape and no "latest version" alias — `VelocityApiClient`
   resolves the newest stable (or experimental) version itself via a custom
   dotted-version comparator (unit tested).
2. GeyserMC's v2 download API matched the doc exactly, no changes needed.
3. **The bundled Geyser config template was initially wrong** — verified
   against older docs/mirrors first, then caught during live testing that
   the actual installed Geyser 2.11.0 uses a materially different current
   schema (`remote:` section replaced by top-level `java:`, no explicit
   remote address/port needed for proxy installs). **Fixed** by capturing
   the exact config.yml a live instance generated and using it verbatim as
   the new bundled template, and repointing the patcher at `java.auth-type`
   instead of `remote.auth-type`. This is the kind of thing worth
   double-checking again if/when Geyser ships another config-version bump.
4. **Vanilla Velocity has no built-in broadcast/alert command** (verified
   live against PaperMC's built-in-commands docs — only `/velocity`,
   `/server`, `/glist`, `/send`, `/shutdown` exist), contradicting the
   gameplan doc's assumption. Resolved with a configurable
   `warning-command-template` (default `"alert {message}"`) that assumes an
   admin-installed broadcast plugin; harmless no-op if none is installed.
   Documented prominently in README.
5. `warnings`/`warning-message`/`restart-message`/`warning-command-template`
   are global settings shared by every `restart-schedule` entry, matching
   the doc's literal phrasing (not per-entry).
6. Manual drag-and-drop jar replacement reuses the exact same
   `<name>.jar.staged` + backup/rollback code path as auto-staged updates.

## What was actually tested live (not just read/reasoned about)

All of the following were run against real downloaded Velocity 4.0.0 /
Geyser 2.11.0 / Floodgate 2.2.5 builds in an isolated scratch directory
(cleaned up afterward, nothing left in the repo):

- Clean first boot: zero manual downloads, correct ports bound, both
  listeners up, plugins loaded.
- Second boot (reusing installed jars): confirmed no re-download, and
  confirmed the Geyser config patch is now clean (this is what surfaced
  decision #3 above).
- Console commands: `status`, `help`, `update check`, `restart in <time>`,
  and arbitrary passthrough (`glist`) all behave correctly.
- Timezone-aware restart scheduling + warning timing: a 15s-out manual
  restart fired its 10s warning at exactly the right offset and the restart
  itself executed on schedule.
- Staged update applied cleanly (both a real Velocity update at boot and a
  manually-dropped Geyser jar) with correct backup creation and
  `builds.json` bookkeeping, no rollback triggered for a good jar.
- **Rollback**: staged a corrupt `velocity.jar`, applied it, watched
  SwagProxy catch the crash, restore the backup, mark the build skipped,
  and relaunch successfully — under 3 seconds total.
- **Crash-loop threshold**: corrupted the live jar directly (no rollback
  candidate), watched 3 crashes with 1s/2s exponential backoff, then the
  loud stop diagnostic, exactly at the configured threshold.
- Config validation: a config with 4 simultaneous problems produced exactly
  4 clear messages and exit code 1.

**Not tested here** (needs a real environment): actual Minecraft Java/
Bedrock client logins (no MC client or backend server available in this
environment — Velocity's and Geyser's own logs confirm both listeners bind
and the forwarding/auth-type settings needed for it are correctly set), and
a real interactive-terminal Ctrl+C (Windows console process management in
this harness only supports forceful termination, so the shutdown-hook path
is implemented but not independently re-verified — the mechanism used,
`Runtime.addShutdownHook`, is the standard correct approach).

## Suggested next steps for whoever picks this up

1. Stand up a real backend (e.g. SwagHub, per the companion doc) and do a
   real Java + real Bedrock client login end to end — checklist item #2 in
   TEST_CHECKLIST.md.
2. Do a real Ctrl+C test in an actual interactive terminal (not this
   harness) to confirm the graceful-shutdown hook.
3. If Geyser ships another config-version bump in the future, re-verify
   `GeyserConfigManager`'s section/key names against a freshly-generated
   config.yml — this schema has already moved once during this build.
4. Consider whether `updates.velocity.channel: experimental` actually
   produces a sane result — it was implemented and reasoned through but
   only the `stable` path was exercised live (there were no non-SNAPSHOT
   experimental versions to distinguish in the live API response at build
   time).

---

## Patch 1 (2026-07-22) — post-build review fixes

Applied `Gameplan/SwagProxy-Patch1-Prompt.md`, a scoped 3-fix patch from an
independent review. No refactoring outside the listed items. DECISIONS.md
entries #7–#9 have the full detail; summary below.

### Fix 1 — Policy-compliant User-Agent on every outbound request

Centralized in `HttpUtil`: every request now sends
`User-Agent: SwagProxy/<version> (<contact>)`. Version comes from the jar
manifest (`Implementation-Version`, now set via the shade plugin in
`pom.xml`; falls back to `"dev"`). Contact comes from a new `updates.contact`
config key, defaulting to a placeholder with a startup notice nudging the
admin to set their own. Added a gated `Log.debug(...)` (`-Dswagproxy.debug`)
to make the UA visible on demand without noise in normal operation. 2 new
unit tests (`HttpUtilTest`).

### Fix 2 — Channel-based Velocity build selection

Replaced `-SNAPSHOT`-name filtering with Fill v3's actual per-build
`channel` field (`RECOMMENDED > STABLE > BETA > ALPHA`, tier-priority
selection, RECOMMENDED wins regardless of version recency). 7 new unit
tests on a pure, HTTP-free `pickBest(...)` function. Live re-verification
directly proved the fix mattered: the live API currently has version 3.5.1
marked `RECOMMENDED` while the newer 4.0.0 is only `STABLE` — the old logic
was silently picking 4.0.0; the new logic correctly picks 3.5.1.

**New finding surfaced by this fix, not fixed here (out of scope):** the
now-correctly-selected Velocity 3.5.1 + the current Geyser 2.11.0 build
throw `NoSuchMethodError: GsonComponentSerializer.toBuilder()` on boot — an
Adventure-library mismatch between the two projects' current releases. The
old (buggy) logic had been masking this by picking Velocity 4.0.0 instead,
which happens not to hit it. **Whoever picks this up next should treat this
as a live production risk**: today, `channel: stable` (the default) will
successfully select Velocity 3.5.1 and then crash-loop against Geyser
2.11.0. Nothing to fix in SwagProxy's own code — this is upstream
version-compatibility, likely needs either a Geyser update, a Velocity
pin, or an upstream bug report.

### Fix 3 — README backend-migration correction (docs only)

Replaced the incorrect `paper-global.yml` key (`proxy-protocol-secret`,
which doesn't exist) with the real Paper settings across all three files
backends need touched: `paper-global.yml` (`proxies.velocity.*`),
`spigot.yml` (`settings.bungeecord: false`), and `server.properties`
(`online-mode=false`), with the firewall-is-mandatory security implication
spelled out explicitly. No code changed. No SwagHub companion doc exists in
this repo to mirror it into.

### Verification

- Full project compiles clean; all 22 unit tests pass (13 original + 2
  `HttpUtilTest` + 7 new `VelocityApiClientTest` channel-selection tests).
- Live re-verification: one real `update check` run (fresh scratch
  directory, `-Dswagproxy.debug=true`) against both live APIs, confirming
  (1) the UA debug line and placeholder-contact notice, and (2) the
  `Velocity build selection: 3.5.1-615 (channel: RECOMMENDED, mode: stable)`
  log line — both Geyser and Floodgate also downloaded successfully under
  the new UA in the same run.
- No files touched outside the 3 fixes' scope (config model, `HttpUtil`,
  `VelocityApiClient`, `Log`, `pom.xml`, `README.md`, `DECISIONS.md`,
  `HANDOFF_REPORT.md`, and the two new/updated test files).

---

## Patch 2 (2026-07-23) — compatibility-resilient selection

Applied `Gameplan/SwagProxy-Patch2-Prompt.md`, directly motivated by the
Patch 1 finding above. DECISIONS.md entries #10–#12 have the full detail;
summary and live evidence below. Owner's acceptance rule: *a fresh install
always results in a booted proxy, and updates never leave the proxy down.*

### Fix 4 — Reordered Velocity selection: newest version's STABLE first

Replaced Patch 1's "a RECOMMENDED build anywhere outranks any STABLE build"
rule — that rule is exactly what put the older, RECOMMENDED 3.5.1 ahead of
the newer, merely-STABLE 4.0.0. New rule: walk versions newest-to-oldest;
per version, prefer that version's own STABLE build, falling back to its
RECOMMENDED only if it has none. The newest version with anything eligible
wins outright. `VelocityApiClientTest`'s old "RECOMMENDED wins globally"
cases were replaced with walk-order tests against the new
`VelocityApiClient.walkCandidates(...)`.

### Fix 5 — Per-component `pin` override

`updates.velocity/geyser/floodgate.pin` accepts a version (`"4.0.0"`) or an
exact `"version-buildid"` (`"4.0.0-6"`), bypassing channel selection and all
automatic advancement — including exclusion from Fix 6's probing. Logs once
per boot that a component is pinned. An unresolvable pin produces a clear
message (not a stack trace), since resolution requires a live API call.

**Live-reverified:** with `updates.velocity.pin: "4.0.0"` set, `update check`
logged:
```
velocity is pinned to "4.0.0" (updates.velocity.pin) — automatic version advancement is disabled for this component.
Staging velocity update: 4.1.0-SNAPSHOT-9 -> 4.0.0-6
```
No other version was ever fetched — confirmed by the request pattern (only
calls for the pinned version) and the log showing exactly one candidate.

### Fix 6 — Fresh-install candidate probing (the core fix)

The existing rollback system only self-heals when a previous jar exists to
restore. On a fresh install there's nothing to roll back to, so a bad
first-choice combination used to crash-loop to the threshold and stop —
violating the acceptance rule. Added:

- `ComponentBuildState.confirmedGood` — true once a build has survived a
  full `rollback-window-seconds` without crashing; resets to false whenever
  `liveBuild` changes to something different.
- `UpdateManager.needsProbing()` — true iff Velocity or Geyser has never
  been confirmed. This is the safety gate: an unrelated crash on a mature,
  already-confirmed install still goes through the original, completely
  unmodified crash-loop/backoff path.
- `CandidateIterator` — pure, unit-tested ambiguity-rule logic: advance
  Velocity's ranked candidates (cap 5) first; once exhausted, advance
  Geyser's (resetting Velocity to its own best pick). Floodgate is fixed
  throughout — not a plausible cause of a Velocity crash, and adding a third
  dimension wasn't worth the search-space blowup.
- On a crash with `needsProbing()` true, `UpdateManager.probeNextCandidate()`
  downloads and directly installs the next candidate, marks the build it
  just moved away from as skipped (bounded 5-entry FIFO per component, so a
  temporarily-bad pairing doesn't blacklist a version forever), and the
  supervisor relaunches immediately (no backoff — probing is already
  naturally paced and bounded). Once a combination survives the window,
  `confirmCurrentBuildsGood()` records it as the baseline and probing never
  reactivates for that install.
- **A real bug caught while wiring this up:** `UpdateManager.rollback()` was
  not re-confirming the restored build, which would have made
  `needsProbing()` incorrectly stay true after every single rollback on a
  mature install — risking DONE CRITERIA #4 (existing rollback unchanged).
  Fixed by having `rollback()` call `confirmGood()` immediately on restore,
  since the restored build was already the proven-working baseline.

**Live-reverified (fresh-install probe test, empty scratch dir, corrupt
`velocity.jar` pre-seeded as the "acceptable harness" bad-candidate-#1
simulation the patch prompt permits):**
```
[SwagProxy] Launching Velocity: ...
Error: Invalid or corrupt jarfile velocity.jar
[SwagProxy] [ERROR] Velocity exited unexpectedly with code 1.
[SwagProxy] Velocity candidate ranking (mode: stable): 4.1.0-SNAPSHOT-9 > 4.0.0-6 > 4.0.0-SNAPSHOT-5 > 3.6.0-SNAPSHOT-613 > 3.5.1-615
[SwagProxy] Installed probe candidate for velocity: 4.0.0-6
[SwagProxy] [WARN] Boot failed with Velocity 4.1.0-SNAPSHOT-9 — trying next candidate 4.0.0-6 (2/5)
[SwagProxy] Launching Velocity: ...
[INFO]: Booting up Velocity 4.0.0...
[INFO]: Loaded plugin geyser 2.11.0-b1202 ...
[INFO]: Listening on /[0:0:0:0:0:0:0:0]:25565
[geyser]: Started Geyser on UDP port 19132
```
Zero human intervention between the crash and the fully booted proxy.
Final `builds.json`: `velocity.liveBuild: "4.0.0-6"`,
`velocity.skippedBuilds: ["4.1.0-SNAPSHOT-9"]`. (Caveat: since the harness
pre-seeded garbage rather than a real downloaded candidate #1, the log's
"Boot failed with Velocity 4.1.0-SNAPSHOT-9" attribution is a harness
artifact — the garbage file crashed, not that real build — but the
mechanism exercised (fetch real candidates, advance, install, relaunch,
succeed) is exactly the real code path a genuine bad-candidate-1 would hit.)

Separately verified the `confirmedGood` timer itself (temporarily-shortened
`rollback-window-seconds` for a fast test): all three components flipped to
`confirmedGood: true` in `builds.json` after surviving the window with no
crash.

**Mature-install rollback re-verification (DONE CRITERIA #4):** established
a confirmed-good baseline, then staged and applied a corrupt jar exactly as
in the original checklist. Log sequence was byte-for-byte the same shape as
Patch 0/1 (`Rolled back velocity to ... — build ... marked bad`, `Relaunching
Velocity in 1s (crash 1/3 ...)`) with **no probing messages at all**,
confirming `needsProbing()` correctly stayed false throughout because of the
rollback-confirms-immediately fix above.

### Verification

- Full project compiles clean; all 37 unit tests pass (22 from Patch 1 + 8
  new `CandidateIteratorTest` + 6 new `ComponentBuildStateTest` + 13 in the
  rewritten `VelocityApiClientTest`, replacing the 7 Patch-1-specific ones
  that Fix 4 explicitly supersedes).
- All 4 DONE CRITERIA live verifications above were run against the real
  PaperMC Fill API and GeyserMC API — nothing mocked.
- No files touched outside the 3 fixes' scope (config model, the two API
  clients, `UpdateManager`, `ProxySupervisor`, `ComponentBuildState`, two new
  small classes (`CandidateIterator`, `ProbeOutcome`), `default-swagproxy.yml`
  comments, `README.md`, `DECISIONS.md`, `HANDOFF_REPORT.md`, and test files).

### Suggested next steps

1. Consider whether a persisted probe-sequence position (surviving a
   SwagProxy-process-level restart mid-probe, not just a Velocity
   subprocess crash) is worth the added `builds.json` schema complexity —
   currently out of scope, documented as a known limitation in DECISIONS.md #12.
2. The Patch 1 Adventure-ABI incompatibility (3.5.1 + Geyser 2.11.0) is now
   moot for the *default* channel behavior (Fix 4 picks 4.0.0/4.1.0-SNAPSHOT
   first, both of which booted cleanly in every live test this patch ran),
   but it's still worth an upstream bug report if anyone tracks
   `channel: experimental` or ever needs to pin back to 3.5.x.
