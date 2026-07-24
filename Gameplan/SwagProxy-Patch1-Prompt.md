# PATCH PROMPT — SwagProxy Post-Build Review Fixes (Patch 1)
**Applies to:** The completed SwagProxy build (see HANDOFF_REPORT.md)
**Scope:** Three targeted fixes from an independent review. This is a PATCH task — do not refactor, restructure, or "improve" anything outside the items below.

---

## 0. AGENT INSTRUCTIONS

1. Follow the same rules as the original build (§0 of SwagProxy-AI-Prompt-Document.md): complete files only, never delete existing code except where a fix below explicitly replaces logic, no placeholders.
2. Each fix below must keep all existing unit tests passing; add/update tests where specified.
3. Update DECISIONS.md with an entry per fix (numbered continuing from #6), and append a "Patch 1" section to HANDOFF_REPORT.md summarizing what changed and what was re-verified.
4. Re-run the affected live verifications (a real `update check` against both APIs) before declaring done.

---

## FIX 1 — Proper User-Agent on ALL outbound API/download requests (required)

**Problem:** PaperMC's Fill API policy requires every request to carry a valid User-Agent that is (a) not generic (no default Java/curl/wget-style UAs) and (b) includes a contact URL or email. The current implementation does not set one. Requests work today, but a default UA is exactly what PaperMC is moving to block — a future silent 403 would disable auto-updates without obvious cause.

**Required changes:**
- Every HTTP request made by SwagProxy (`VelocityApiClient`, `GeyserApiClient`, and all file downloads in the download service — GeyserMC requests included, as good citizenship even though only PaperMC mandates it) must send a User-Agent of the form: `SwagProxy/<version> (<contact>)`.
- `<version>` read from the jar's implementation version (set it in the shade/manifest config if not already present), falling back to `dev`.
- `<contact>` comes from a new `swagproxy.yml` key `updates.contact` (commented, default placeholder like `https://github.com/SwagDev` — the comment must tell the admin to set their own URL or email per PaperMC's API policy).
- If the admin leaves the placeholder, still send it (a placeholder UA is better than a generic one) but log a one-line startup notice suggesting they set `updates.contact`.
- Centralize this in `HttpUtil` (or equivalent) so no request path can miss it. Add a unit test asserting the UA header is present and correctly formatted on constructed requests.

## FIX 2 — Channel-based Velocity build selection (replace SNAPSHOT string-filtering)

**Problem:** `VelocityApiClient` currently resolves "newest stable" by excluding `-SNAPSHOT` version names and numerically comparing dotted version strings. Fill v3 has a first-class channel system — `ALPHA`, `BETA`, `STABLE`, `RECOMMENDED` — where `RECOMMENDED` replaces v2's "promoted" status and is currently used only by Velocity. Name-based filtering worked against this week's API response but ignores the purpose-built signal and will misbehave if PaperMC ships e.g. a pre-release without the `-SNAPSHOT` naming convention.

**Required changes:**
- Selection order for `updates.velocity.channel: stable` (the default): prefer the newest build with channel `RECOMMENDED`; if none exists anywhere, fall back to the newest `STABLE` build. Never select `BETA`/`ALPHA` in stable mode.
- `updates.velocity.channel: experimental`: consider `BETA` and `ALPHA` builds as well, still preferring the highest channel available for the newest version (RECOMMENDED > STABLE > BETA > ALPHA).
- Version ordering may still use the existing dotted-version comparator to pick the newest version group, but build eligibility within a version must be decided by the `channel` field, not the version-name string. Keep the comparator's unit tests; add tests covering: RECOMMENDED preferred over newer-build-number STABLE within the same version, fallback to STABLE when no RECOMMENDED exists, and experimental-channel widening.
- Continue using the response-embedded download URLs and sha256 checksums (do not construct URLs manually — already correct, do not regress this).
- Update the config comment for `updates.velocity.channel` to explain the channel mapping.

## FIX 3 — README backend-migration key names (documentation correction)

**Problem:** README's "Backend migration checklist" tells the admin to paste the forwarding secret into `paper-global.yml` as "`proxy-protocol-secret` / the modern-forwarding secret field" — that key does not exist. Wrong instructions on the one step performed by hand on every backend server.

**Required changes — replace that bullet's config guidance with the correct Paper settings:**
- In each backend's `config/paper-global.yml`:
  - `proxies.velocity.enabled: true`
  - `proxies.velocity.online-mode: true` (matching the proxy's online mode)
  - `proxies.velocity.secret: "<contents of proxy/forwarding.secret>"`
- In each backend's `spigot.yml`: `settings.bungeecord: false` (must be false when using Velocity modern forwarding — keep the existing correct mention, just ensure the full key path is shown).
- In each backend's `server.properties`: `online-mode=false` (the PROXY authenticates; note prominently that this is exactly why the firewall bullet is mandatory — an unfirewalled backend with online-mode=false is joinable by anyone with any name).
- Also mirror this corrected checklist in the SwagHub companion context if the README references it. Do not change any code for this fix.

---

## DONE CRITERIA

- All three fixes implemented; full project compiles; all unit tests (existing + new) pass.
- Live re-verification: one real `update check` run showing (1) the UA header being sent (log it at debug for the run), (2) Velocity build selection reporting which channel it chose.
- DECISIONS.md entries #7–#9 written; HANDOFF_REPORT.md "Patch 1" section appended.
- No files outside the touched scope modified.
