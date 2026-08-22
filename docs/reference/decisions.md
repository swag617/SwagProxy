# Design Decisions

This page summarizes the ambiguity resolutions logged in the repository's `DECISIONS.md`, made while building SwagProxy against its original gameplan document. Each was discovered and verified via **live testing**, not guessed.

## 1. PaperMC's v2 download API is dead — switched to the Fill v3 API

Verified live: `https://api.papermc.io/v2/projects/velocity` returns HTTP 410 Gone. PaperMC has migrated to a new "Fill" API at `https://fill.papermc.io/v3` with a different JSON shape and no "latest version" alias, so `VelocityApiClient` resolves the newest eligible version itself (see [Version Selection & Pinning](version-selection.md)).

## 2. GeyserMC's v2 API matched the docs exactly

No changes needed — `https://download.geysermc.org/v2/projects/{geyser|floodgate}/versions/latest/builds/latest` behaves exactly as documented.

## 3. Geyser-Velocity config.yml schema corrected after live testing

The initially-bundled config template was based on older GeyserMC wiki docs describing a `remote:` section. Live testing against actually-installed Geyser-Velocity 2.11.0 (config-version 7) showed that section is gone — `auth-type` now lives under a top-level `java:` section, with `advanced.java.use-direct-connection: true` as the default for proxy-platform installs. Fixed by capturing the exact config.yml a live instance generates and using it verbatim as the bundled template, repointing the patcher at `java.auth-type`.

## 4. Vanilla Velocity has no built-in broadcast/alert command

Verified against PaperMC's built-in-commands documentation: only `/velocity`, `/server`, `/glist`, `/send`, and `/shutdown` exist. Resolved with a configurable `warning-command-template` (default `"alert {message}"`) that assumes an admin-installed broadcast plugin — harmless no-op if none is installed, and SwagProxy always logs every warning itself regardless. See [Restart Schedule & Warnings](../operations/restart-schedule.md).

## 5. Warnings are one shared list, not per-schedule-entry

`warnings`, `warning-message`, and `restart-message` are single global settings applied uniformly to every `restart-schedule` entry.

## 6. Manual drag-and-drop jars reuse the exact staged-apply code path

A manually-dropped `<name>.jar.staged` file is applied through identical backup/rollback/skip-list logic as an auto-staged one — a synthesized `manual-<epoch-seconds>` id is used purely for bookkeeping since there's no known build id.

## 7. Policy-compliant User-Agent on every outbound request

PaperMC's Fill API policy requires a non-generic User-Agent with contact info. Centralized in `HttpUtil`: every request sends `User-Agent: SwagProxy/<version> (<contact>)`, where `<version>` comes from the jar manifest and `<contact>` from the `updates.contact` config key.

## 8. Velocity selection: channel field, not "-SNAPSHOT" string matching (superseded by #10)

An early implementation excluded any version with "-SNAPSHOT" in its name to approximate "stable." Live data proved this wrong both ways: a `-SNAPSHOT`-named version had `STABLE` builds, and `RECOMMENDED` wasn't guaranteed to be the numerically newest version. Replaced with Fill v3's actual per-build `channel` field.

## 9. README backend-migration key names corrected

A documentation-only fix: the `paper-global.yml` guidance previously named a nonexistent key. Replaced with the real Paper settings — see the [Backend Migration Checklist](../backend/migration.md).

## 10. Selection reordered to per-version preference, walking newest-to-oldest

Decision #8's rule ("a RECOMMENDED build anywhere outranks any STABLE build regardless of version recency") put an older `RECOMMENDED` Velocity 3.5.1 ahead of a newer, merely-`STABLE` 4.0.0 — which then crashed against the current Geyser build. Replaced with the per-version walk described in [Version Selection & Pinning](version-selection.md): the newest version with *any* eligible build wins outright; `RECOMMENDED` is now only a per-version fallback.

## 11. Pin implementation: plain version or "version-buildid"

`updates.<component>.pin` accepts either a bare version (resolves to that version's highest build, trusting the operator's choice over channel filtering) or an exact `"version-buildid"` composite. A pinned component's candidate list becomes a single fixed element, naturally disabling both automatic advancement and candidate probing for it.

## 12. Fresh-install candidate probing

The core fix enabling automatic recovery on a brand-new install with no rollback baseline — see [Fresh-Install Candidate Probing](../architecture/candidate-probing.md) for the full design and a live-verified example.

## Next steps

- [Troubleshooting](troubleshooting.md)
- [Architecture Overview](../architecture/overview.md)
