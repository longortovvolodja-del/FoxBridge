# FoxBridge-MCP

Private MCP gateway that exposes a small, read-only set of FoxBridge operations to ChatGPT.

## Initial tools

- `foxbridge_status` — Minecraft server status
- `foxbridge_list_plugins` — installed plugins
- `foxbridge_list_files` — list files under `plugins/` or `logs/`
- `foxbridge_read_file` — read a file under those directories only
- `foxbridge_latest_logs` — latest server log

The gateway does **not** expose arbitrary console commands, arbitrary HTTP proxying, file deletion, or automatic plugin installation.

## Deploy to Cloudflare Workers

1. Install Node.js 20+ and run `npm install` in this directory.
2. Set secrets:
   ```sh
   npx wrangler secret put MCP_ACCESS_TOKEN
   npx wrangler secret put FOXBRIDGE_API_KEY
   npx wrangler secret put FOXBRIDGE_BASE_URL
   ```
3. The gateway sends the FoxBridge key as `Authorization: Bearer <key>`.
4. Run `npm run typecheck`, then `npm run deploy`.
5. The MCP endpoint will be `https://<worker-name>.<account>.workers.dev/mcp`.

Never commit secrets to GitHub or paste them into chat.

## Network connection through FoxBridge

The recommended initial route is a Cloudflare Quick Tunnel started by the FoxBridge plugin. The plugin keeps the API bound to loopback (normally `127.0.0.1:25965`); cloudflared creates an outbound HTTPS tunnel to that local API.

On the Minecraft server console:

1. Run `foxbridge cloudflared` once to download the official Linux amd64 binary and verify its SHA-256.
2. Run `foxbridge tunnel start`.
3. Copy the generated `https://….trycloudflare.com` URL from the console.
4. Set the Cloudflare Worker secret `FOXBRIDGE_BASE_URL` to that URL (without a path).
5. Keep `FOXBRIDGE_API_KEY` as the raw key printed by FoxBridge on first startup or by `foxbridge resetkey`. Never paste it into chat or commit it.
6. Use `foxbridge tunnel status` and `foxbridge tunnel stop` to inspect or stop the tunnel.

The Quick Tunnel URL is temporary and can change after the Minecraft server restarts or the tunnel is restarted. Update the Worker secret when it changes. A named Cloudflare Tunnel with a stable hostname can be configured later if the user has a Cloudflare account and domain.

The public tunnel URL does not replace FoxBridge authentication: every API request still requires the high-entropy Bearer API key. Keep that key private. Do **not** change FoxBridge to bind to `0.0.0.0` while TLS is disabled. The plugin only supports starting this helper when the API is bound to a loopback address and local TLS is disabled; Cloudflare provides HTTPS on the external side.

## FoxBridge API compatibility

This adapter currently expects these FoxBridge routes:

- `GET /v1/status`
- `GET /v1/plugins`
- `GET /v1/files?path=plugins|logs`
- `GET /v1/read?path=...`
- `GET /v1/logs/latest`

Before deployment, verify the exact API-key header name and response formats against the running FoxBridge build. The header is configurable; no key is stored in this repository.
