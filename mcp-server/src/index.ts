import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";

interface Env {
  MCP_ACCESS_TOKEN: string;
  FOXBRIDGE_API_KEY: string;
  FOXBRIDGE_REGISTRATION_TOKEN: string;
  FOXBRIDGE_REGISTRY: DurableObjectNamespace;
}

interface TunnelRegistration {
  tunnelUrl: string;
  lastSeen: number;
}

const MAX_RESPONSE_BYTES = 512 * 1024;
const REQUEST_TIMEOUT_MS = 8_000;
const REGISTRATION_STALE_MS = 120_000;
const QUICK_TUNNEL_HOST = /^[a-z0-9-]+\.trycloudflare\.com$/i;

function jsonError(message: string, status: number): Response {
  return Response.json({ error: message }, { status, headers: { "Cache-Control": "no-store" } });
}

function textResult(text: string, isError = false) {
  return {
    resultType: "complete" as const,
    ...(isError ? { isError: true as const } : {}),
    content: [{ type: "text" as const, text }]
  };
}

function isAllowedFilePath(path: string): boolean {
  if (!path || path.length > 240 || path.includes("\\\\") || path.includes("\\0")) return false;
  if (path.split("/").some((part) => part === ".." || part === "." || part === "")) return false;
  return path.startsWith("plugins/") || path.startsWith("logs/");
}

function validateTunnelUrl(value: unknown): URL {
  if (typeof value !== "string" || value.length > 255) throw new Error("Invalid tunnel URL");
  const url = new URL(value);
  if (url.protocol !== "https:" || !QUICK_TUNNEL_HOST.test(url.hostname)
      || url.username || url.password || url.port || (url.pathname !== "/" && url.pathname !== "")
      || url.search || url.hash) {
    throw new Error("Only an HTTPS Cloudflare Quick Tunnel URL is accepted");
  }
  return url;
}

function registry(env: Env): DurableObjectStub {
  const id = env.FOXBRIDGE_REGISTRY.idFromName("foxbridge-primary-server");
  return env.FOXBRIDGE_REGISTRY.get(id);
}

async function currentRegistration(env: Env): Promise<TunnelRegistration> {
  const response = await registry(env).fetch("https://registry.internal/current");
  if (!response.ok) throw new Error("FoxBridge has not registered a tunnel");
  const value = await response.json() as TunnelRegistration;
  validateTunnelUrl(value.tunnelUrl);
  if (!Number.isFinite(value.lastSeen) || Date.now() - value.lastSeen > REGISTRATION_STALE_MS) {
    throw new Error("FoxBridge tunnel registration is stale; waiting for server heartbeat");
  }
  return value;
}

async function foxbridgeGet(env: Env, route: string): Promise<string> {
  const registration = await currentRegistration(env);
  const base = validateTunnelUrl(registration.tunnelUrl);
  const url = new URL(route, base);
  if (url.origin !== base.origin) throw new Error("Invalid backend route");

  const response = await fetch(url, {
    method: "GET",
    headers: {
      "Authorization": `Bearer ${env.FOXBRIDGE_API_KEY}`,
      "Accept": "application/json"
    },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  });

  const body = await response.text();
  if (new TextEncoder().encode(body).byteLength > MAX_RESPONSE_BYTES) {
    throw new Error("FoxBridge response is too large");
  }
  if (!response.ok) throw new Error(`FoxBridge returned HTTP ${response.status}`);
  return body;
}

function makeServer(env: Env) {
  const server = new McpServer({
    name: "FoxBridge MCP",
    version: "0.1.1"
  });

  server.registerTool(
    "foxbridge_status",
    { description: "Получить состояние Minecraft-сервера через FoxBridge.", inputSchema: z.object({}) },
    async () => textResult(await foxbridgeGet(env, "/v1/status"))
  );

  server.registerTool(
    "foxbridge_list_plugins",
    { description: "Получить список установленных плагинов Minecraft.", inputSchema: z.object({}) },
    async () => textResult(await foxbridgeGet(env, "/v1/plugins"))
  );

  server.registerTool(
    "foxbridge_latest_logs",
    { description: "Прочитать последние строки серверного лога.", inputSchema: z.object({}) },
    async () => textResult(await foxbridgeGet(env, "/v1/logs/latest"))
  );

  server.registerTool(
    "foxbridge_list_files",
    {
      description: "Посмотреть список файлов только в разрешённых каталогах plugins/ или logs/.",
      inputSchema: z.object({ path: z.enum(["plugins", "logs"]).default("plugins") })
    },
    async ({ path }) => textResult(await foxbridgeGet(env, `/v1/files?path=${encodeURIComponent(path)}`))
  );

  server.registerTool(
    "foxbridge_read_file",
    {
      description: "Прочитать файл только внутри plugins/ или logs/. Нельзя читать другие каталоги.",
      inputSchema: z.object({ path: z.string().min(1).max(240) })
    },
    async ({ path }) => {
      if (!isAllowedFilePath(path)) {
        return textResult("Доступ разрешён только к файлам внутри plugins/ и logs/.", true);
      }
      return textResult(await foxbridgeGet(env, `/v1/read?path=${encodeURIComponent(path)}`));
    }
  );

  return server;
}

export class FoxBridgeRegistry {
  constructor(private readonly state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/set" && request.method === "POST") {
      const body = await request.json() as TunnelRegistration;
      validateTunnelUrl(body.tunnelUrl);
      if (!Number.isFinite(body.lastSeen)) return jsonError("Invalid timestamp", 400);
      await this.state.storage.put<TunnelRegistration>("registration", body);
      return Response.json({ ok: true });
    }
    if (url.pathname === "/current" && request.method === "GET") {
      const value = await this.state.storage.get<TunnelRegistration>("registration");
      return value ? Response.json(value, { headers: { "Cache-Control": "no-store" } })
        : jsonError("No tunnel registered", 404);
    }
    return jsonError("Not found", 404);
  }
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/register") {
      if (request.method !== "POST") return jsonError("Method not allowed", 405);
      if (!env.FOXBRIDGE_REGISTRATION_TOKEN || !env.FOXBRIDGE_REGISTRY) {
        return jsonError("Registration service is not configured", 503);
      }
      const authorization = request.headers.get("Authorization") || "";
      if (authorization !== `Bearer ${env.FOXBRIDGE_REGISTRATION_TOKEN}`) {
        return jsonError("Unauthorized", 401);
      }
      try {
        const body = await request.json() as { tunnel_url?: unknown };
        const tunnel = validateTunnelUrl(body.tunnel_url);
        const id = env.FOXBRIDGE_REGISTRY.idFromName("foxbridge-primary-server");
        const stub = env.FOXBRIDGE_REGISTRY.get(id);
        const registration: TunnelRegistration = { tunnelUrl: tunnel.origin, lastSeen: Date.now() };
        const stored = await stub.fetch("https://registry.internal/set", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(registration)
        });
        if (!stored.ok) return jsonError("Could not store tunnel registration", 502);
        return Response.json({ ok: true, registered: true }, { headers: { "Cache-Control": "no-store" } });
      } catch {
        return jsonError("Invalid registration payload", 400);
      }
    }

    if (url.pathname !== "/mcp") return jsonError("Not found", 404);
    if (!env.MCP_ACCESS_TOKEN || !env.FOXBRIDGE_API_KEY || !env.FOXBRIDGE_REGISTRY) {
      return jsonError("MCP service is not configured", 503);
    }
    const authorization = request.headers.get("Authorization") || "";
    if (authorization !== `Bearer ${env.MCP_ACCESS_TOKEN}`) return jsonError("Unauthorized", 401);

    const handler = createMcpHandler(() => makeServer(env));
    return handler(request, env as never, ctx);
  }
};
