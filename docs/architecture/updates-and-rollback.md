# Update, Staging & Rollback

## How updates are found and staged

1. On a timer (`updates.check-interval-minutes`) and at every boot, SwagProxy polls each enabled component's official download API for the latest eligible build and compares it against `data/builds.json`.
2. A newer build is downloaded to a temp file, its SHA256 verified against the API's published hash (when available), then atomically moved to `<name>.jar.staged` next to the live jar. **The live jar is never touched outside of an actual restart.**
3. If the resolved build id is already staged (via a marker file, see below) or is on that component's skip list (previously rolled back), it's silently skipped — a flaky API check never destabilizes an already-running proxy. Failures during a check are logged as warnings and retried on the next cycle, never fatal.

Because a manually-dropped `.staged` file has no known build id, SwagProxy tracks each staged build's id in a small sidecar marker file (`<name>.jar.staged.buildid`) next to the staged jar itself.

## How a staged update is applied

At the next restart (scheduled or manual), for every component with a `.staged` file present:

1. The current live jar is moved to `backups/<component>-<old-build>.jar`.
2. The staged file atomically replaces it as the live jar, and the staged marker is deleted.
3. `data/builds.json` is updated with the new build id.
4. SwagProxy then watches for crashes for `supervisor.rollback-window-seconds` (default 120s) — this is the **rollback window**.

If Velocity crashes (or crash-loops) within that window, SwagProxy **automatically restores every jar it just swapped** from `backups/`, marks the bad build id as "skipped" in `builds.json` so it's never re-staged, logs a clear warning, and relaunches with the known-good jars. The restored build is immediately re-confirmed as good (see [Fresh-Install Candidate Probing](candidate-probing.md)) since it was the proven-working baseline before the bad update — it doesn't have to re-earn confirmation through another full rollback-window wait.

A bad build should never take the network down for more than one relaunch cycle.

## Manual jar drops

You can drop a replacement jar straight into `proxy/plugins/` (or replace `proxy/velocity.jar`) yourself — just name it `<TargetFileName>.staged` (e.g. `Geyser-Velocity.jar.staged`), exactly like SwagProxy's own updater does. It goes through the **exact same** apply-and-backup code path at the next restart: the file it replaces is backed up, and if the new jar turns out to be bad, the same crash/rollback logic protects you. Since a manually-dropped file has no known build id, one is synthesized (`manual-<epoch-seconds>`) purely for `builds.json`/rollback bookkeeping.

## Confirmed-good baselines

Every `ComponentBuildState` (one per component in `builds.json`) has a `confirmedGood` flag. It:

- Resets to `false` whenever `liveBuild` actually changes — a specific build earns confirmation, not "whatever happens to be live."
- Is set `true` by `UpdateManager.confirmCurrentBuildsGood()`, called once a launch survives `rollback-window-seconds` without crashing (scheduled from every launch path, not just updates).
- Is also set `true` immediately upon a successful rollback restore, for the reason above.

This flag is what gates [candidate probing](candidate-probing.md) — probing only ever activates when a component has *never* been confirmed.

## Next steps

- [Fresh-Install Candidate Probing](candidate-probing.md)
- [Version Selection & Pinning](../reference/version-selection.md)
- [Crash Handling & Tuning](../operations/crash-handling.md)
