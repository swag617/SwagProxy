# PATCH PROMPT — SwagProxy Compatibility-Resilient Selection (Patch 2)
**Applies to:** SwagProxy after Patch 1
**Motivating incident:** Correct channel-based selection (Patch 1, Fix 2) chose Velocity 3.5.1 per PaperMC's RECOMMENDED channel, which crashes with current Geyser 2.11.0 (`NoSuchMethodError`, Adventure ABI mismatch). The previously verified working combo is Velocity 4.0.0 + Geyser 2.11.0 + Floodgate 2.2.5. Root cause: no machine-readable compatibility matrix exists between Velocity and Geyser, so ANY fixed selection rule can go stale. The fix is to make selection self-healing rather than trying to predict compatibility.
**Owner's acceptance rule:** "As long as it works as intended." Intended = a fresh `java -jar SwagProxy.jar` on an empty folder ALWAYS results in a booted proxy, and updates NEVER leave the proxy down.

---

## 0. AGENT INSTRUCTIONS
Same rules as prior patches: patch-scope only, complete files, never delete code outside the explicit replacements, all existing tests keep passing, DECISIONS.md entries continue numbering, append "Patch 2" to HANDOFF_REPORT.md. Re-verify live per DONE CRITERIA.

---

## FIX 4 — Reorder default Velocity selection: newest stable version first

**Rationale:** GeyserMC tracks the newest platform versions aggressively (Bedrock force-updates make them); PaperMC's RECOMMENDED channel is deliberately conservative and currently points at the older major line. For a Geyser-first proxy, Geyser's expectations govern.

**Change:** in `stable` channel mode, selection order becomes: newest version's newest `STABLE` build → that version's `RECOMMENDED` build if no STABLE → walk DOWN version groups (newest to oldest) repeating the same per-version preference. `RECOMMENDED` is no longer globally preferred over a newer version's STABLE. `experimental` mode widens per-version eligibility to BETA/ALPHA as in Patch 1. Update the Patch 1 unit tests to the new ordering (this explicitly supersedes Patch 1's "RECOMMENDED preferred" test) and update the config comment.

## FIX 5 — `pin` override per component

**Change:** new optional config keys `updates.velocity.pin`, `updates.geyser.pin`, `updates.floodgate.pin` (default unset). When set to a version (Velocity, e.g. `"4.0.0"`) or build id, the updater only ever selects/stages that pin — no automatic advancement, and the poll logs once per boot that the component is pinned. Commented in swagproxy.yml as the operator's emergency override when upstream combos break. Validation: a pin that cannot be resolved via the API is a clear config-style error message, not a stack trace.

## FIX 6 — Candidate-list fallback on unbootable fresh installs (the core fix)

**Problem:** the crash/rollback system already self-heals when a *previous good jar* exists. On a FRESH install there is no backup — a bad combo currently crash-loops to the threshold and stops, violating the acceptance rule.

**Change:**
- Selection (Fix 4 order) now produces a ranked CANDIDATE LIST (cap ~5 per component: e.g. for Velocity, newest-version STABLE, its RECOMMENDED, next version down's STABLE, ...), not a single answer. Candidate #1 is what gets installed/staged exactly as today.
- New behavior only when Velocity exits nonzero AND there is no rollback candidate for the swapped/installed jars (fresh install, or rollback already exhausted): mark the current combination's build ids skipped in `builds.json` (existing mechanism), install candidate #(n+1) for the component(s) just changed — on a fresh install where the faulty component is ambiguous, advance the Velocity candidate first (Velocity is the platform; Geyser/Floodgate "latest" is almost always intended), then Geyser if Velocity candidates are exhausted — and relaunch. Log each probe clearly: `[SwagProxy] Boot failed with Velocity <v> — trying next candidate <v'> (2/5)`.
- The existing crash-loop threshold becomes the final backstop only after ALL candidates are exhausted; its diagnostic must then list every combination tried.
- Once a combination boots and survives the rollback window, record it in `builds.json` as the known-good baseline (this is what future updates roll back TO).
- Skipped-build entries added by probing must age out (e.g. cleared when a newer build of that component appears) so a temporarily-bad pairing doesn't blacklist a version forever.
- Unit-test the candidate iterator (ordering, exhaustion, ambiguity rule); the full probe loop must additionally be verified live per DONE CRITERIA.

---

## DONE CRITERIA
1. All tests pass; config comments updated for all three fixes.
2. **Live fresh-install probe test:** in an empty scratch dir, force candidate #1 to be a known-bad combo (acceptable harness: inject a corrupt jar as the first candidate's download, or temporarily hardcode 3.5.1 first) and demonstrate SwagProxy probing to the next candidate and reaching a fully booted proxy with zero human intervention. Capture the log excerpt in HANDOFF_REPORT's Patch 2 section.
3. **Live pin test:** set `updates.velocity.pin: "4.0.0"`, run `update check`, confirm no other version is considered.
4. Existing rollback behavior for installs WITH a known-good baseline is unchanged (re-run the corrupt-staged-jar test from the original checklist).
5. DECISIONS.md #10+ written; no files outside scope touched.
