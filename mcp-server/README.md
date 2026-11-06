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

## Network requirement — not configured yet

Cloudflare Workers cannot reach the Minecraft plugin at `127.0.0.1` on the game host. The FoxBridge API must be made reachable over HTTPS through a secure tunnel or a properly secured public listener before this gateway can connect.

Do **not** change FoxBridge to bind to `0.0.0.0` while TLS is disabled. First choose and verify a secure network route supported by the hoster.

## FoxBridge API compatibility

This adapter currently expects these FoxBridge routes:

- `GET /v1/status`
- `GET /v1/plugins`
- `GET /v1/files?path=plugins|logs`
- `GET /v1/read?path=...`
- `GET /v1/logs/latest`

Before deployment, verify the exact API-key header name and response formats against the running FoxBridge build. The header is configurable; no key is stored in this repository.
