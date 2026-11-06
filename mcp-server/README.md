# FoxBridge-MCP

Private MCP gateway that exposes a small, read-only set of FoxBridge operations to ChatGPT.

## Components and versions

- FoxBridge Java plugin: **1.0.1** (Paper 1.18.2, Java 17)
- FoxBridge-MCP Worker: **0.1.1**
- The MCP service is external to Minecraft; it is not a second Paper plugin and does not produce a JAR.

## MCP tools

- `foxbridge_status` — Minecraft server status
- `foxbridge_list_plugins` — installed plugins
- `foxbridge_list_files` — list files under `plugins/` or `logs/`
- `foxbridge_read_file` — read a file under those directories only
- `foxbridge_latest_logs` — latest server log

The gateway does **not** expose arbitrary console commands, arbitrary HTTP proxying, file deletion, or automatic plugin installation.

## Dynamic tunnel registration

The Worker has a stable HTTPS address. The Minecraft server's Cloudflare Quick Tunnel URL may change after a restart. FoxBridge now registers its current URL with the Worker and sends a heartbeat every 45 seconds. The Worker stores the latest URL in a Durable Object and treats registrations older than 120 seconds as stale.

There is no static `FOXBRIDGE_BASE_URL` secret and no need to manually copy a new Quick Tunnel URL after a restart.

## Deploy from GitHub Actions (recommended for this repository)

A manual workflow is available at **Actions → Deploy FoxBridge-MCP → Run workflow**. Before running it, add these repository Actions secrets under **Settings → Secrets and variables → Actions**:

- `CLOUDFLARE_API_TOKEN` — an account API token for deploying Workers. Cloudflare documents the **Edit Cloudflare Workers** token template for GitHub Actions deployments.
- `CLOUDFLARE_ACCOUNT_ID` — your Cloudflare account ID.
- `MCP_ACCESS_TOKEN` — a new random secret for the future ChatGPT MCP connection.
- `FOXBRIDGE_API_KEY` — the current raw FoxBridge API key printed by the Minecraft console.
- `FOXBRIDGE_REGISTRATION_TOKEN` — a separate new random secret for tunnel registration.

The workflow installs dependencies, typechecks the MCP source, deploys the Worker and Durable Object, and writes the three runtime secrets to Cloudflare. It does not expose their values in the logs.

For the Cloudflare token, use the Account API Tokens page and the **Edit Cloudflare Workers** template, scoped to the account that will host the Worker. Cloudflare's current GitHub Actions guide requires both the API token and account ID for non-interactive deployment.

## 1. Deploy to Cloudflare Workers

Requirements: Node.js 22+, npm, and a Cloudflare account with Workers and Durable Objects enabled.

From this directory:

```sh
npm install
npx wrangler login
npx wrangler deploy
```

The Wrangler configuration creates the `FOXBRIDGE_REGISTRY` Durable Object binding and its initial SQLite migration.

After deployment, note the stable Worker hostname, for example:

`https://foxbridge-mcp.<your-workers-subdomain>.workers.dev`

The MCP endpoint is `/mcp`; the registration endpoint is `/register`.

## 2. Configure Worker secrets

Create three independent secrets. Do not put them in source files or GitHub commits.

```sh
npx wrangler secret put MCP_ACCESS_TOKEN
npx wrangler secret put FOXBRIDGE_API_KEY
npx wrangler secret put FOXBRIDGE_REGISTRATION_TOKEN
```

- `MCP_ACCESS_TOKEN`: credential used by the ChatGPT MCP connection.
- `FOXBRIDGE_API_KEY`: the FoxBridge API key printed once by the Minecraft console when the plugin first creates it. It is not the registration token.
- `FOXBRIDGE_REGISTRATION_TOKEN`: a separate, randomly generated secret used only by the Minecraft plugin to publish its current tunnel URL.

If the original FoxBridge API key was lost, use the Minecraft console command `foxbridge resetkey`, save the newly printed key securely, and update the Worker secret `FOXBRIDGE_API_KEY`. Resetting it invalidates the previous API key.

## 3. Configure the Minecraft server

Install FoxBridge 1.0.1 and start the server once so the plugin creates `plugins/FoxBridge/config.yml` and `plugins/FoxBridge/security.yml`. Stop the server before editing these files.

In `plugins/FoxBridge/config.yml`, set:

```yaml
mcp:
  registration_url: "https://foxbridge-mcp.<your-workers-subdomain>.workers.dev/register"
  registration_interval_seconds: 45
  auto_start_tunnel: true
```

In `plugins/FoxBridge/security.yml`, set `registration_token` to exactly the same value as the Worker secret `FOXBRIDGE_REGISTRATION_TOKEN`:

```yaml
registration_token: "YOUR_RANDOM_REGISTRATION_SECRET"
```

Keep the existing `key_hash` entry. Do not replace it with the raw API key. Restrict access to the plugin data folder and do not share either secret.

Start Minecraft. If the registration URL and token are configured, FoxBridge automatically starts its Quick Tunnel after startup, sends the current HTTPS URL to `/register`, and refreshes the registration every 45 seconds.

## 4. Verify the connection

The Minecraft console should show:

- FoxBridge 1.0.1 started.
- Cloudflare Quick Tunnel started.
- `FoxBridge-MCP: адрес туннеля зарегистрирован или обновлён.`

The Worker rejects requests to the Minecraft API until a fresh registration exists. A registration becomes stale after 120 seconds without a heartbeat. When Minecraft restarts and receives a new Quick Tunnel URL, the plugin automatically replaces the stored URL.

The stable MCP endpoint remains:

`https://foxbridge-mcp.<your-workers-subdomain>.workers.dev/mcp`

The MCP endpoint still requires `MCP_ACCESS_TOKEN`. Configure the ChatGPT connection using the authentication method supported by the ChatGPT plugin connection flow. Do not paste secrets into public files or chat.

## Troubleshooting

- **Worker returns 503 on /mcp:** check that the Worker secrets and Durable Object binding are configured, and that Minecraft has registered recently.
- **Worker returns 401 on /register:** the plugin's `registration_token` does not match `FOXBRIDGE_REGISTRATION_TOKEN`.
- **Worker returns 401 when calling the Minecraft API:** `FOXBRIDGE_API_KEY` does not match the key currently held by FoxBridge. If necessary, reset the key in the Minecraft console and update the Worker secret.
- **No tunnel registration log:** verify that `registration_url` is HTTPS and ends in `/register`, and that outbound HTTPS is allowed by the Minecraft host.
- **Tunnel starts but registration fails:** inspect the FoxBridge console log; the plugin never prints the registration token.
