# Version Selection & Pinning

## Velocity: channel-based, per-version selection

PaperMC's Fill v3 API tags every build with a `channel`: `ALPHA`, `BETA`, `STABLE`, or `RECOMMENDED` (`RECOMMENDED` replaces the old v2 "promoted" flag).

For `updates.velocity.channel: stable` (the default), SwagProxy walks Velocity versions **newest-to-oldest** and, for each version, prefers *that version's own* `STABLE` build over its `RECOMMENDED` build — the newest version with an eligible build wins, full stop. `RECOMMENDED` is only ever a per-version fallback, never a global override of a newer version's `STABLE` build.

`channel: experimental` widens the same per-version walk to also accept `BETA` and `ALPHA` builds (tier order: `STABLE, RECOMMENDED, BETA, ALPHA`).

**Rationale:** GeyserMC tracks new Minecraft/Velocity versions aggressively because Bedrock clients force-update ahead of the Java release cycle, while PaperMC's `RECOMMENDED` flag is deliberately conservative and can lag behind by a full major version. For a Geyser-first proxy, staying current is the safer default.

## Geyser & Floodgate

GeyserMC's v2 download API has a "latest build" alias that resolves directly — no channel concept needed. SwagProxy fetches `latest builds/latest` for both Geyser and Floodgate.

## Pinning (emergency override)

If the current best combination of Velocity/Geyser/Floodgate doesn't work together, set `updates.<component>.pin` to a known-good version:

```yaml
updates:
  velocity:
    pin: "4.0.0"          # resolves to that version's highest build id, any channel
  geyser:
    pin: "2.11.0-6"        # or an exact "version-buildid" composite
```

A pinned component always uses exactly that build:

- No channel selection, no automatic advancement.
- Excluded from [fresh-install candidate probing](../architecture/candidate-probing.md) entirely — its candidate list becomes a single fixed element.
- Logged once per boot: `<component> is pinned to "<pin>" — automatic version advancement is disabled for this component.`
- An unresolvable pin (a version/build that doesn't exist in the live API response) produces a clear error message naming the pin and where to check available versions — never a stack trace, since resolution requires a live network call.

## Why this exists

A real live-testing finding: Velocity 3.5.1 (marked `RECOMMENDED`) and Geyser 2.11.0 together threw `NoSuchMethodError: GsonComponentSerializer.toBuilder()` on boot — an Adventure-library version mismatch between the two projects' releases at the time. The per-version-newest-first selection rule exists specifically so a `RECOMMENDED` flag on an older version can never silently override a newer, healthier, `STABLE`-tagged version. If a similar cross-component incompatibility ever recurs, `pin` is the documented, supported way to lock in a known-good combination while upstream sorts it out.

## Next steps

- [Update, Staging & Rollback](../architecture/updates-and-rollback.md)
- [Fresh-Install Candidate Probing](../architecture/candidate-probing.md)
- [Design Decisions](decisions.md)
