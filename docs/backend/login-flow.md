# Login Flow

1. Everyone connects to SwagProxy first — Java via TCP `java-port`, Bedrock via UDP `bedrock-port`.
2. **Bedrock:** Geyser translates the session continuously (not just at login); Floodgate authenticates via Xbox (`.`-prefixed username, no Java account needed).
3. **Java:** clients pass straight through — Geyser never touches them.
4. Both land on the `try-order[0]` backend (typically your hub server).
5. The hub's `bungeecord:main` `Connect` plugin messages come back to this same Velocity instance, which repoints the player at the target backend. Velocity answers that channel natively — SwagProxy needs no code for it.

## Why this requires modern forwarding

Because SwagProxy authenticates and forwards player identity to backends using Velocity's own modern forwarding protocol (not legacy BungeeCord forwarding), every backend server must be configured to trust it — see the [Backend Migration Checklist](migration.md) for the exact `paper-global.yml`, `spigot.yml`, and `server.properties` keys this requires.

## Verifying it end to end

SwagProxy itself doesn't contain any client-facing protocol code, so the surest way to confirm login works is a real client test:

1. Connect a Java Edition client to `<host>:<java-port>`.
2. Connect a Bedrock Edition client (console, mobile, or Win10) to `<host>:<bedrock-port>`.
3. Both should land on the `try-order[0]` backend. The Bedrock player's username should be prefixed with `.` (Floodgate).
4. Both should show up in `/glist` (via Velocity) or your hub plugin's own player list.

Short of real clients, Velocity's own log confirms both listeners bound successfully (`Listening on /[0:0:0:0:0:0:0:0]:<port>` and Geyser's `Started Geyser on UDP port <port>`), and you can confirm `player-info-forwarding-mode = "modern"`, `force-key-authentication = false`, and `java.auth-type: floodgate` are present in the generated configs — these three settings are what make Bedrock+Java login/forwarding actually work.

## Next steps

- [Backend Migration Checklist](migration.md)
- [Troubleshooting](../reference/troubleshooting.md)
