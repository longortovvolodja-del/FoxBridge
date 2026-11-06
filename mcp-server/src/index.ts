import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";

interface Env {
  MCP_ACCESS_TOKEN: string;
  FOXBRIDGE_BASE_URL: string;
  FOXBRIDGE_API_KEY: string;
  FOXBRIDGE_API_KEY_HEADER?: string;
}

const MAX_RESPONSE_BYTES = 512 * 1024;
const REQUEST_TIMEOUT_MS = 8_000;

function jsonError(message: string, status: number): Response {
  return Response.json({ error: message }, { status });
}

function isAllowedFilePath(path: string): boolean {
  if (!path || path.length > 240 || path.includes("\\") || path.includes("\0")) return false;
  if (path.split("/").some((part) => part === ".." || part === "." || part === "")) return false;
  return path.startsWith("plugins/") || path.startsWith("logs/");
}

async function foxbridgeGet(env: Env, route: string): Promise<string> {
  const base = new URL(env.FOXBRIDGE_BASE_URL);
  if (base.protocol !== "https:") {
    throw new Error("FoxBridge backend must use HTTPS");
  }
  const url = new URL(route, base);
  if (url.origin !== base.origin) throw new Error("Invalid backend route");

  const headerName = env.FOXBRIDGE_API_KEY_HEADER || "X-FoxBridge-Key";
  const response = await fetch(url, {
    method: "GET",
    headers: { [headerName]: env.FOXBRIDGE_API_KEY, "Accept": "application/json" },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  });

  const body = await response.text();
  if (new TextEncoder().encode(body).byteLength > MAX_RESPONSE_BYTES) {
    throw new Error("FoxBridge response is too large");
  }
  if (!response.ok) {
    throw new Error(`FoxBridge returned HTTP ${response.status}`);
  }
  return body;
}

function makeServer(env: Env) {
  const server = new McpServer({
    name: "FoxBridge MCP",
    version: "0.1.0"
  });

  server.registerTool(
    "foxbridge_status",
    { description: "Получить состояние Minecraft-сервера через FoxBridge.", inputSchema: {} },
    async () => ({
      content: [{ type: "text", text: await foxbridgeGet(env, "/v1/status") }]
    })
  );

  server.registerTool(
    "foxbridge_list_plugins",
    { description: "Получить список установленных плагинов Minecraft.", inputSchema: {} },
    async () => ({
      content: [{ type: "text", text: await foxbridgeGet(env, "/v1/plugins") }]
    })
  );

  server.registerTool(
    "foxbridge_latest_logs",
    { description: "Прочитать последние строки серверного лога.", inputSchema: {} },
    async () => ({
      content: [{ type: "text", text: await foxbridgeGet(env, "/v1/logs/latest") }]
    })
  );

  server.registerTool(
    "foxbridge_list_files",
    {
      description: "Посмотреть список файлов только в разрешённых каталогах plugins/ или logs/.",
      inputSchema: { path: z.enum(["plugins", "logs"]).default("plugins") }
    },
    async ({ path }) => ({
      content: [{ type: "text", text: await foxbridgeGet(env, `/v1/files?path=${encodeURIComponent(path)}`) }]
    })
  );

  server.registerTool(
    "foxbridge_read_file",
    {
      description: "Прочитать файл только внутри plugins/ или logs/. Нельзя читать другие каталоги.",
      inputSchema: { path: z.string().min(1).max(240) }
    },
    async ({ path }) => {
      if (!isAllowedFilePath(path)) {
        return { isError: true, content: [{ type: "text", text: "Доступ разрешён только к файлам внутри plugins/ и logs/." }] };
      }
      return {
        content: [{ type: "text", text: await foxbridgeGet(env, `/v1/read?path=${encodeURIComponent(path)}`) }]
      };
    }
  );

  return server;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/mcp") return jsonError("Not found", 404);

    if (!env.MCP_ACCESS_TOKEN || !env.FOXBRIDGE_BASE_URL || !env.FOXBRIDGE_API_KEY) {
      return jsonError("Server is not configured", 503);
    }

    const authorization = request.headers.get("Authorization") || "";
    if (authorization !== `Bearer ${env.MCP_ACCESS_TOKEN}`) {
      return jsonError("Unauthorized", 401);
    }

    const handler = createMcpHandler(() => makeServer(env));
    return handler(request, env as never, ctx);
  }
};
