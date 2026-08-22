# Backend Migration Checklist

Converting an existing "Geyser Standalone + BungeeCord" network to SwagProxy:

- **Remove Geyser-Spigot from every backend.** Geyser must live only on the proxy — a copy on a backend is a routing/auth bypass hole.
- **Keep/install Floodgate-Spigot** on any backend that needs the Floodgate API (e.g. your hub server), and **copy the proxy's Floodgate `key.pem`** to every backend's Floodgate folder, replacing whatever key was there before — all instances must share one key or Bedrock players get rejected at the backend hop. **Never distribute this key outside your own network.**
- **Switch backends to Velocity modern forwarding.** This touches three files on every backend:

  **`config/paper-global.yml`:**
  ```yaml
  proxies:
    velocity:
      enabled: true
      online-mode: true    # match the proxy's own online-mode setting
      secret: "<paste the contents of proxy/forwarding.secret here>"
  ```

  **`spigot.yml`:** `settings.bungeecord: false` — **must** be `false` when using Velocity modern forwarding (that setting is for the old BungeeCord/legacy forwarding protocol, not this one).

  **`server.properties`:** `online-mode=false` — the *proxy* authenticates players now, not the backend. **This is exactly why the firewall bullet below is mandatory:** an unfirewalled backend with `online-mode=false` can be joined directly by anyone claiming any username, completely bypassing the proxy's auth and Velocity's forwarding secret.

- **Firewall every backend** so only the proxy machine can reach them directly — players should never be able to connect straight to a backend.
- **Install ViaVersion on backends** — Bedrock clients (via Geyser) are force-updated ahead of the Java client release cycle, so backends need it to stay compatible.
- **Decommission** the old standalone Geyser server and old BungeeCord once you've verified logins work end to end through SwagProxy.

## Where the forwarding secret comes from

SwagProxy generates `proxy/forwarding.secret` for you on first boot (32 random bytes, base64url-encoded) if it doesn't already exist. It never regenerates an existing one, so every backend can be configured once and stay in sync as long as you don't delete the file.

## Next steps

- [Login Flow](login-flow.md)
- [Architecture Overview](../architecture/overview.md)
