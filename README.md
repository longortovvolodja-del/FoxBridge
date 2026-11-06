# FoxBridge

FoxBridge is a Paper 1.18.2 / Java 17 plugin that exposes a restricted, authenticated local API. FoxBridge-MCP is a separate Cloudflare Worker that exposes read-only MCP tools to ChatGPT.

## Components and versions

- **FoxBridge Java plugin:** 1.0.1
- **FoxBridge-MCP Worker:** 0.1.1
- The MCP service is not a second Minecraft plugin and does not produce a JAR.

## Dynamic tunnel registration

The MCP endpoint has a stable Cloudflare Worker URL. The Minecraft server may use a changing Cloudflare Quick Tunnel URL. On startup, FoxBridge starts cloudflared and registers the current HTTPS tunnel URL with FoxBridge-MCP. It then refreshes the registration every 45 seconds. The Worker stores the latest URL in a Durable Object and considers it stale after 120 seconds without a heartbeat.

No static `FOXBRIDGE_BASE_URL` Worker secret is needed.

## Setup

See [FoxBridge-MCP deployment and server setup](mcp-server/README.md).

## Safety

- FoxBridge binds its API to `127.0.0.1` by default.
- The local API still requires its own Bearer key.
- Only HTTPS Cloudflare Quick Tunnel URLs are accepted by the registration endpoint.
- The MCP tools are read-only except for the existing, separately gated staging flow in the Java API; no arbitrary console command execution is exposed.
- Never commit API keys, registration tokens, or MCP access tokens to Git.
