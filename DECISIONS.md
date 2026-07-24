# DECISIONS.md

Record of ambiguities encountered while building SwagProxy from
`Gameplan/SwagProxy-AI-Prompt-Document.md`, and the safest reasonable choice
made for each, per the agent instructions in §0.5 of that document.

---

## 1. PaperMC's v2 download API is dead — switched to the Fill v3 API

Verified live on 2026-07-22: `https://api.papermc.io/v2/projects/velocity`
returns **HTTP 410 Gone**. PaperMC has migrated to a new "Fill" API at
`https://fill.papermc.io/v3`, with a different JSON shape (project ->
`versions` grouped by version-group; per-version `builds`; a build's
`downloads` map keyed `server:default` with `name`/`url`/`checksums.sha256`).

- Implemented in `VelocityApiClient`, base URL overridable via the
  `swagproxy.papermc-api-base` system property.
- The Fill API has no "latest version" alias (unlike GeyserMC's API), so
  `VelocityApiClient` resolves the newest version itself: it fetches all
  version groups, filters out `-SNAPSHOT` versions unless
  `updates.velocity.channel: experimental`, and numerically compares the
  remaining dotted version strings to find the newest one, then fetches that
  version's `builds/latest`.

## 2. GeyserMC v2 API confirmed exactly as documented

Verified live: `https://download.geysermc.org/v2/projects/{geyser|floodgate}
/versions/latest/builds/latest` redirects to the resolved version/build and
returns `downloads.velocity.{name,sha256}` exactly as §4.2 describes. No
changes needed; implemented in `GeyserApiClient`, base URL overridable via
`swagproxy.geysermc-api-base`.

## 3. Geyser-Velocity config.yml schema — corrected after live testing

Initially bundled a template based on the GeyserMC wiki and a third-party
mirror `config.yml`, which describe an older schema with a `remote:` section
(`address`/`port`/`auth-type`, with `address: auto` recommended for plugin
installs). During the first-boot smoke test, the actual installed
Geyser-Velocity **2.11.0** (config-version 7) generated/migrated its config
to a materially different current schema: the `remote:` section is gone
entirely — `auth-type` now lives directly under a top-level `java:` section,
and `advanced.java.use-direct-connection: true` is the default, so no
explicit remote address/port is needed at all for a proxy-platform install.

Caught this because the bundled (stale) template caused `GeyserConfigManager`
to log "could not find section remote:" on the second boot, once Geyser had
already rewritten the file. **Fixed** by replacing the bundled template with
the exact config.yml a live Geyser-Velocity 2.11.0 instance generated (byte
for byte — the most authoritative source available), and changing the
patcher to target `bedrock.port` and `java.auth-type` only. Verified clean
(no warnings) on a subsequent boot. See `GeyserConfigManager`.

## 4. Vanilla Velocity has no built-in broadcast/alert command

The gameplan document (§4.4) assumes "Velocity's built-in `alert` message
command or equivalent" exists. Verified live against PaperMC's
built-in-commands documentation: vanilla Velocity's *only* built-in commands
are `/velocity`, `/server`, `/glist`, `/send`, and `/shutdown` (console-only,
graceful shutdown with an optional reason message shown to players). There is
no built-in way to broadcast an arbitrary chat message to every connected
player without either protocol code or a plugin — both of which are hard
non-goals for SwagProxy (§1, §8).

**Resolution:** the restart-time message (`shutdown <message>`) works as
specified, since `/shutdown` is real and accepts a message. For the earlier
warning broadcasts (600s/300s/60s/10s before), added a new config key
`warning-command-template` (default: `"alert {message}"`) — SwagProxy always
logs every warning to its own console/log regardless, and separately sends
this templated command to Velocity's stdin. If the admin has installed any
broadcast-capable plugin in `proxy/plugins/` (there are several small,
widely-used "/alert" plugins for Velocity; this is a normal drag-and-drop
addition, same as any other plugin) it will fire correctly; if not, Velocity
just logs "command not found" and proxy operation is otherwise unaffected.
This keeps SwagProxy itself protocol-free and plugin-free while leaving the
door open for the admin's own plugin stack — documented in README.md.

## 5. `warnings` is one shared list applied to every restart-schedule entry

§3/§4.4 phrase it as `restart-schedule: [...] plus warnings: [...]` (a
sibling key, not nested per-entry), so `warnings`, `warning-message`, and
`restart-message` are single global settings applied uniformly to every
scheduled restart, not configurable per schedule entry.

## 6. Manual drag-and-drop jars reuse the exact staged-apply code path

§6.4 requires "drop a jar in `plugins/`, it's applied on next restart like a
staged one — supervisor should detect and back up the replaced jar too."
Implemented by giving the manual workflow the *same* `<name>.jar.staged`
naming convention SwagProxy's own updater uses — `UpdateManager.applyStaged()`
doesn't care whether the `.staged` file was written by the download service
or dropped by hand, so backup/rollback/skip-list behavior is identical either
way. A manually-dropped file has no known build id, so one is synthesized
(`manual-<epoch-seconds>`) purely for builds.json/rollback bookkeeping.

---

# Patch 1 (post-build review fixes)

Applies `Gameplan/SwagProxy-Patch1-Prompt.md`. Continuing the numbering from
the original build.

## 7. Centralized, policy-compliant User-Agent on every outbound request

PaperMC's Fill API policy requires a non-generic User-Agent with contact
info; a default Java `HttpClient` UA is exactly what such policies move to
block. Centralized in `HttpUtil`: every request (both `getJson` and
`downloadTo`, used by `VelocityApiClient`, `GeyserApiClient`, and all jar
downloads) now goes through a single `newRequestBuilder(url)` that sets
`User-Agent: SwagProxy/<version> (<contact>)`.

- `<version>` comes from `Package.getImplementationVersion()`, which reads
  the jar manifest's `Implementation-Version` attribute — added to the
  shade plugin's `manifestEntries` in `pom.xml`. Falls back to `"dev"` when
  unset (e.g. running from an IDE/exploded classes dir, as confirmed by the
  unit test).
- `<contact>` comes from the new `updates.contact` `swagproxy.yml` key,
  wired in via `HttpUtil.configure(...)` at the top of
  `Bootstrapper.run()`, before any network call happens. A blank/missing
  value falls back to a placeholder (`https://github.com/SwagDev`) — still
  sent (a placeholder beats a generic UA), but `Bootstrapper` also logs a
  one-line startup notice telling the admin to set their own contact info
  if they've left the default in place.
- Added `Log.debug(...)`, gated behind `-Dswagproxy.debug=true` (off by
  default — a UA log line on every single request would be noisy), so the
  exact UA string can be confirmed on demand without cluttering normal
  operation.

**Live-reverified 2026-07-22:** ran a real first boot with
`-Dswagproxy.debug=true`; confirmed the debug line
`Outbound User-Agent for all API/download requests: SwagProxy/1.0-SNAPSHOT
(https://github.com/SwagDev)` and the placeholder-contact startup notice,
and confirmed all three components (Velocity via PaperMC's Fill API, Geyser
and Floodgate via GeyserMC's API) still downloaded successfully with the
new UA.

## 8. Velocity build selection now decided by the `channel` field, not version-name string matching

The original implementation excluded any version whose name contained
"-SNAPSHOT" to approximate "stable," then picked the numerically newest
remaining version. This ignored Fill v3's purpose-built per-build `channel`
field (`ALPHA`/`BETA`/`STABLE`/`RECOMMENDED`) and was demonstrably wrong in
two ways confirmed live: (a) a live "-SNAPSHOT"-named version
(`4.1.0-SNAPSHOT`) had builds with `channel: "STABLE"` — the old logic would
have excluded a legitimately stable build purely because of its name; (b)
`RECOMMENDED` (Fill's "this is the one to use" signal, replacing v2's
"promoted" flag) is not guaranteed to be the numerically newest version —
live data had version `3.5.1` as `RECOMMENDED` while the newer `4.0.0` was
only `STABLE`.

**New algorithm** (`VelocityApiClient`): fetch every version's full build
list (`/versions/{version}/builds`, which returns each build's own
`channel` — not just the single latest build via `builds/latest`, since
channel is not guaranteed to advance monotonically with build number
within a version), scanning versions newest-to-oldest (dotted-version
comparator retained *only* for scan/tie-break ordering, never for
inclusion/exclusion). A pure, HTTP-free `pickBest(candidates,
allowExperimental)` selects by tier priority first
(`RECOMMENDED > STABLE > BETA > ALPHA`, restricted to
`RECOMMENDED`/`STABLE` in stable mode), then newest version, then highest
build id — so a `RECOMMENDED` build always wins regardless of how new a
lower-tier build is, exactly matching the two live cases above. Scanning
stops the instant a `RECOMMENDED` build is found (nothing older could ever
outrank it), keeping the typical request count small.

Added 7 unit tests on the pure `pickBest` function (no HTTP/mocking
needed) covering: RECOMMENDED beating a same-version STABLE build with a
*higher* build number, RECOMMENDED at an *older* version beating STABLE at
a newer one (mirrors the live 3.5.1-vs-4.0.0 case), STABLE fallback when no
RECOMMENDED exists, stable mode never selecting BETA/ALPHA, and
experimental-mode widening (preferring BETA over a newer ALPHA, and falling
back to ALPHA when nothing else exists).

**Live-reverified 2026-07-22:** a real `update check` against the live Fill
API logged `Velocity build selection: 3.5.1-615 (channel: RECOMMENDED, mode:
stable)` — correctly picking the older-but-RECOMMENDED 3.5.1 over the
newer-but-merely-STABLE 4.0.0, proving the fix does what it was built to do
(the previous SNAPSHOT-string-filtering logic had been selecting 4.0.0).

**Follow-up needed, out of scope for this patch:** that same live run
surfaced that Velocity 3.5.1 + Geyser 2.11.0 together throw
`NoSuchMethodError: GsonComponentSerializer.toBuilder()` on boot — an
apparent Adventure-library version mismatch between the two components. The
previous (buggy) selection logic had been picking Velocity 4.0.0, which
happened not to hit this incompatibility, essentially by accident. This is
a real cross-component compatibility gap, not a defect in the selection
logic itself (which is now behaving correctly per its spec), but it means a
production SwagProxy tracking `channel: stable` today would hit a crash
loop against the current live Geyser 2.11.0 build. Flagging for a separate
follow-up — fixing it is out of scope for this patch (no refactoring
outside the 3 listed fixes).

## 9. README backend-migration key names corrected — Patch 1, Fix 3

No code changes (documentation only, per the patch's own instruction). The
`paper-global.yml` guidance previously named a nonexistent key
(`proxy-protocol-secret`). Replaced with the actual Paper settings:
`proxies.velocity.enabled`/`online-mode`/`secret` in `paper-global.yml`,
`settings.bungeecord: false` in `spigot.yml` (full key path made explicit),
and `online-mode=false` in `server.properties` — with the security
implication (an unfirewalled, `online-mode=false` backend is joinable by
anyone claiming any username) called out prominently next to the existing
firewall bullet. There is no SwagHub companion document in this repository
to mirror the correction into.

---

# Patch 2 (compatibility-resilient selection)

Applies `Gameplan/SwagProxy-Patch2-Prompt.md`. Motivated by the Patch 1
finding (#8) that correctly-selected Velocity 3.5.1 (RECOMMENDED) crashes
with Geyser 2.11.0. Continuing the numbering.

## 10. Selection reordered to per-version preference, walking newest-to-oldest — Fix 4

Patch 1's rule ("a RECOMMENDED build anywhere outranks any STABLE build,
regardless of version recency") is exactly what put Velocity 3.5.1 ahead of
4.0.0. Replaced with: walk versions newest-to-oldest; for each version,
prefer *that version's own* `STABLE` build, falling back to *that version's*
`RECOMMENDED` build only if it has no `STABLE`; the newest version with any
eligible build wins outright, full stop. `RECOMMENDED` no longer has any
special global priority over version recency — it only matters as a
per-version fallback. `experimental` mode's per-version tier list widens to
`STABLE, RECOMMENDED, BETA, ALPHA`, same walk otherwise.

Rationale (per the patch prompt, and consistent with what we actually
observed): GeyserMC tracks new Minecraft/Velocity versions aggressively
because Bedrock clients force-update; PaperMC's `RECOMMENDED` flag is
deliberately conservative and can lag behind by a full major version. For a
Geyser-first proxy, staying current is the safer default.

This explicitly supersedes and replaces Patch 1's `pickBest` tests (the
"RECOMMENDED beats a newer STABLE" cases) — `VelocityApiClientTest` now
tests the walk directly via a new pure `VelocityApiClient.walkCandidates(...)`
function, replacing `pickBest`.

## 11. Pin implementation: plain version, or "version-buildid", validated live

`updates.<component>.pin` accepts either a bare version string (e.g.
`"4.0.0"` — resolves to that version's highest build id, any channel,
trusting the operator's explicit choice over channel filtering entirely) or
a `"version-buildid"` composite (e.g. `"4.0.0-6"` — pins an exact build).
Parsed via a simple trailing `-<digits>` regex; if neither interpretation
matches a real version returned by the live API, the resulting
`DownloadException` names the pin, what was expected, and where to check
available versions — never a stack trace, since this can only be
discovered via a live network call (not at config-parse time).

A pinned component's candidate list becomes a single-element list (its
pin), which naturally disables *both* normal auto-advancement *and*
candidate probing (Fix 6) for that component — a pin is total: "always
this, no automatic exceptions." The pinned-notice log line is deduplicated
per-boot via a small `Set<String>` on `UpdateManager` (fresh each JVM run),
satisfying "logs once per boot."

**Live-reverified 2026-07-23:** `updates.velocity.pin: "4.0.0"` +
`update check` logged `velocity is pinned to "4.0.0"... automatic version
advancement is disabled` followed directly by `Staging velocity update:
4.1.0-SNAPSHOT-9 -> 4.0.0-6` — no other version was ever fetched or
considered.

## 12. Fresh-install candidate probing (the core fix)

**Design summary** (see `CandidateIterator`, `ProbeOutcome`,
`UpdateManager.probeNextCandidate`/`needsProbing`/`confirmCurrentBuildsGood`,
and the `ComponentBuildState.confirmedGood` field):

- Each `ComponentBuildState` gained a `confirmedGood` boolean. It resets to
  `false` whenever `liveBuild` actually *changes* (a specific build earns
  confirmation, not "whatever happens to be live"), and is set `true` either
  by `UpdateManager.confirmCurrentBuildsGood()` (called once a launch
  survives `rollback-window-seconds` without crashing — scheduled from
  `ProxySupervisor.launchProcess()` itself, so *every* launch path, not just
  updates, is covered) or immediately upon a successful rollback restore
  (see below).
- **Probing only activates when `needsProbing()` is true** — i.e. Velocity
  or Geyser has *never* been confirmed. This is the safety boundary that
  keeps probing scoped to "fresh install, or an update that never got the
  chance to be confirmed" per the acceptance rule, rather than firing on an
  unrelated crash of a long-stable, already-confirmed proxy (which still
  falls through to the pre-existing, unchanged crash-loop/backoff logic).
- **Fix for a real gap found while implementing this:** `UpdateManager.rollback()`
  now calls `state.confirmGood()` immediately after restoring the backup —
  the restored build *was* the working baseline before the bad update, so it
  shouldn't have to re-earn confirmation through another full
  rollback-window wait. Without this, `needsProbing()` would incorrectly
  stay `true` after every single successful rollback on a mature install,
  and DONE CRITERIA #4 (existing rollback behavior unchanged) would not
  actually hold — live-reverified: a corrupt staged jar on a
  previously-confirmed install still produces the exact original rollback
  log sequence, no probing messages at all.
- **Ambiguity rule** (`CandidateIterator`): advance Velocity's candidate
  first; only once Velocity's list (capped at 5) is exhausted does Geyser's
  candidate advance (resetting Velocity back to its own best candidate,
  since Geyser becomes the more likely remaining culprit once every
  Velocity candidate has been tried against Geyser's current pick).
  Floodgate is intentionally **not** varied — it's a thin auth relay, not a
  plausible cause of a Velocity boot crash, and adding a third dimension to
  the search would multiply the worst-case attempt count for no realistic
  benefit. If Floodgate genuinely is the problem, all Velocity×Geyser
  combinations exhaust and the final diagnostic tells the operator to check
  Floodgate/set its pin manually.
- Probe-driven installs bypass the normal staged/`applyStaged()`/`AppliedSwap`
  rollback machinery entirely (a dedicated `installProbeCandidate` does a
  direct download + backup + swap) — rolling back to a combination we just
  proved bad would be pointless, so probing and the swap-rollback system are
  kept as two independent, non-interfering mechanisms.
- **Skip-list aging** (Fix 6's "must age out" requirement): rather than
  trying to version-compare arbitrary buildId strings across two differently-
  shaped component ID formats to decide staleness, `ComponentBuildState.markSkipped`
  simply caps the list at 5 entries, FIFO (oldest evicted first). Simpler,
  bounded, and satisfies the stated intent ("doesn't blacklist a version
  forever") without fragile cross-component version parsing.
- The existing numeric crash-loop-threshold counter (`crashCountInWindow`)
  still increments during probing (harmless bookkeeping, visible via
  `status`), but the *stopping decision* is governed entirely by candidate
  exhaustion while `needsProbing()` is true — the numeric threshold check is
  skipped (an early `return`) for as long as candidates remain, matching
  "the existing crash-loop threshold becomes the final backstop only after
  ALL candidates are exhausted."

**Known limitation, accepted for scope:** if the *SwagProxy JVM itself*
(not just the Velocity subprocess) restarts mid-probing-sequence, the
in-memory `CandidateIterator` is lost and a fresh one is built starting
back at candidate #1 for both components. Worst case this wastes one probe
attempt re-trying something already skipped before normal advancement
resumes — bounded and harmless, just not maximally efficient. Persisting
probe-sequence position across JVM restarts would need additional
`builds.json` schema and was judged not worth the complexity for a rare
edge case (a SwagProxy-process-level crash is a different failure domain;
nothing in this project supervises SwagProxy itself, by design).

**Live-reverified 2026-07-23** (see HANDOFF_REPORT.md's Patch 2 section for
the full log excerpt): pre-seeded a corrupt `velocity.jar` in an empty
scratch directory (the patch's own "acceptable harness" for simulating a
bad candidate #1 on a fresh install) and confirmed SwagProxy crashed once,
then automatically probed to Velocity candidate 2/5 (`4.0.0-6`), installed
it, relaunched, and booted successfully with zero human intervention —
`builds.json` afterward showed `velocity.liveBuild: "4.0.0-6"` and
`skippedBuilds: ["4.1.0-SNAPSHOT-9"]`. Separately confirmed the
`confirmedGood` timer itself (using a temporarily shortened
`rollback-window-seconds` for a fast test): all three components flipped to
`confirmedGood: true` in `builds.json` once the window passed without a
crash.
