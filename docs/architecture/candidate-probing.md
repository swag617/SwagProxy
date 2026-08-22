# Fresh-Install Candidate Probing

## The gap this solves

The rollback system described in [Update, Staging & Rollback](updates-and-rollback.md) only works when there's a previous jar to restore. On a **brand-new install** there isn't one — if Velocity fails to boot on its very first launch, there's nothing to roll back to. Without probing, a bad first-choice combination would crash-loop straight to the threshold and stop, leaving the network down with no automatic recovery.

**Acceptance rule:** a fresh install always results in a booted proxy, and updates never leave the proxy down.

## When probing activates

`UpdateManager.needsProbing()` is `true` if and only if Velocity **or** Geyser has never been confirmed good (see the `confirmedGood` flag in [Update, Staging & Rollback](updates-and-rollback.md#confirmed-good-baselines)). This is the safety boundary: an unrelated crash on a long-stable, already-confirmed proxy still falls through to the ordinary [crash-loop/backoff logic](../operations/crash-handling.md) untouched — probing never fires on a mature install.

## How it works

1. On a crash while `needsProbing()` is true, `UpdateManager.probeNextCandidate()` fetches up to 5 ranked candidate builds each for Velocity and Geyser (respecting any configured pin — a pinned component is excluded from probing entirely, since its candidate list is a single fixed element).
2. A `CandidateIterator` walks the search space with a fixed **ambiguity rule**: advance Velocity's candidate first; only once Velocity's list (capped at 5) is exhausted does Geyser's candidate advance — resetting Velocity back to its own best candidate, since Geyser becomes the more likely remaining culprit once every Velocity candidate has been tried against Geyser's current pick.
3. Floodgate is intentionally **not** varied — it's a thin auth relay, not a plausible cause of a Velocity boot crash. If Floodgate genuinely is the problem, all Velocity×Geyser combinations exhaust and the final diagnostic tells the operator to check Floodgate or set its pin manually.
4. The failing build is marked "skipped" (a per-component 5-entry FIFO list — old entries age out rather than blacklisting a version forever), the next candidate is downloaded and installed directly (`installProbeCandidate` — this bypasses the normal staged/`applyStaged()` machinery entirely, since rolling back to a combination just proven bad would be pointless), and the supervisor relaunches immediately with no backoff delay.
5. Once a combination survives the rollback window, `confirmCurrentBuildsGood()` records it as the baseline and probing never reactivates for that install.
6. If every candidate for both components is exhausted without a successful boot, SwagProxy falls back to the standard crash-loop-threshold stop, printing a diagnostic that lists every combination it tried and suggesting `updates.<component>.pin` as a manual escape hatch.

## Live-verified example

From a real fresh-install test (pre-seeded corrupt `velocity.jar` in an empty scratch directory):

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

Zero human intervention between the crash and the fully booted proxy. `builds.json` afterward showed `velocity.liveBuild: "4.0.0-6"` and `velocity.skippedBuilds: ["4.1.0-SNAPSHOT-9"]`.

## Known limitation

If the **SwagProxy JVM itself** (not just the Velocity subprocess) restarts mid-probing-sequence, the in-memory candidate iterator is lost and a fresh one is built starting back at candidate #1 for both components. Worst case this wastes one probe attempt re-trying something already skipped before normal advancement resumes — bounded and harmless, just not maximally efficient. This is an accepted tradeoff; a SwagProxy-process-level crash is a different failure domain from a Velocity subprocess crash, and nothing supervises SwagProxy itself by design.

## Next steps

- [Version Selection & Pinning](../reference/version-selection.md)
- [Crash Handling & Tuning](../operations/crash-handling.md)
- [Design Decisions](../reference/decisions.md)
