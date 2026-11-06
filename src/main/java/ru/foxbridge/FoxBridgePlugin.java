package ru.foxbridge;

import com.sun.net.httpserver.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class FoxBridgePlugin extends JavaPlugin implements CommandExecutor {
    private HttpServer server;
    private ExecutorService executor;
    private Path data, plugins, logs, staging, backups;
    private volatile byte[] keyHash;
    private final Map<String, Rate> rates = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean diagnosticRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override public void onEnable() {
        saveDefaultConfig();
        data = getDataFolder().toPath().toAbsolutePath().normalize();
        plugins = data.getParent();
        logs = plugins.getParent().resolve("logs").normalize();
        staging = data.resolve("staging");
        backups = data.resolve("backups");
        try {
            Files.createDirectories(staging);
            Files.createDirectories(backups);
            loadKey();
            startServer();
        } catch (Exception e) {
            getLogger().severe("FoxBridge не запущен: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Objects.requireNonNull(getCommand("foxbridge")).setExecutor(this);
        getLogger().info("FoxBridge 1.0.0 запущен.");
    }

    @Override public void onDisable() { stopServer(); }

    private void loadKey() throws Exception {
        Path file = data.resolve("security.yml");
        if (!Files.exists(file)) saveResource("security.yml", false);
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        String hash = cfg.getString("key_hash", "");
        if (hash == null || hash.isBlank()) {
            String key = newKey();
            keyHash = sha(key.getBytes(StandardCharsets.UTF_8));
            cfg.set("key_hash", HexFormat.of().formatHex(keyHash));
            cfg.save(file.toFile());
            getLogger().warning("FOXBRIDGE KEY (сохраните сейчас, повторно не показывается): " + key);
        } else keyHash = HexFormat.of().parseHex(hash.trim());
    }

    private static String newKey() {
        byte[] b = new byte[32]; new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
    private static byte[] sha(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }

    private void startServer() throws Exception {
        stopServer();
        String bind = getConfig().getString("server.bind_address", "127.0.0.1");
        InetAddress addr = InetAddress.getByName(bind);
        boolean tls = getConfig().getBoolean("tls.enabled", false);
        if (!addr.isLoopbackAddress() && !tls) throw new IllegalStateException("Удалённый доступ требует TLS.");
        InetSocketAddress socket = new InetSocketAddress(addr, getConfig().getInt("server.port", 25965));
        int backlog = getConfig().getInt("server.backlog", 16);
        if (tls) {
            HttpsServer https = HttpsServer.create(socket, backlog);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext()));
            server = https;
        } else server = HttpServer.create(socket, backlog);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "FoxBridge-HTTP"); t.setDaemon(true); return t;
        });
        server.setExecutor(executor);
        server.createContext("/", new Handler());
        server.start();
    }

    private SSLContext sslContext() throws Exception {
        String name = getConfig().getString("tls.keystore", "foxbridge.p12");
        String pass = getConfig().getString("tls.password", "");
        if (pass.isBlank() || pass.equals("CHANGE_THIS")) throw new IllegalStateException("Задайте tls.password.");
        Path file = data.resolve(name).normalize();
        if (!file.startsWith(data) || !Files.isRegularFile(file)) throw new IllegalStateException("PKCS12 keystore не найден.");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) { ks.load(in, pass.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    private void stopServer() {
        if (server != null) { server.stop(0); server = null; }
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("diagnose") || sub.equals("diag")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sendDiagnostic(sender, "§c[FoxBridge] Диагностика доступна только из консоли сервера.");
                return true;
            }
            if (!diagnosticRunning.compareAndSet(false, true)) {
                sender.sendMessage("§e[FoxBridge] Диагностика уже выполняется.");
                return true;
            }
            String bind = getConfig().getString("server.bind_address", "127.0.0.1");
            int port = getConfig().getInt("server.port", 25965);
            boolean tls = getConfig().getBoolean("tls.enabled", false);
            try {
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        diagnose(sender, bind, port, tls);
                    } catch (Exception e) {
                        sendDiagnostic(sender, "§cОшибка диагностики: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        diagnosticRunning.set(false);
                    }
                });
            } catch (Exception e) {
                diagnosticRunning.set(false);
                sender.sendMessage("§c[FoxBridge] Не удалось запустить диагностику: " + e.getMessage());
            }
            return true;
        }
        if (sub.equals("status")) {
            sender.sendMessage("§6[FoxBridge] §f" + (server == null ? "API остановлен" : "API работает"));
            return true;
        }
        if (sub.equals("reload")) {
            if (!sender.hasPermission("foxbridge.admin")) return deny(sender);
            reloadConfig();
            try { startServer(); sender.sendMessage("§a[FoxBridge] Настройки перечитаны."); }
            catch (Exception e) { sender.sendMessage("§c[FoxBridge] Ошибка запуска: " + e.getMessage()); }
            return true;
        }
        if (sub.equals("resetkey")) {
            if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("§cТолько консоль."); return true; }
            try {
                String key = newKey(); keyHash = sha(key.getBytes(StandardCharsets.UTF_8));
                var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(data.resolve("security.yml").toFile());
                cfg.set("key_hash", HexFormat.of().formatHex(keyHash)); cfg.save(data.resolve("security.yml").toFile());
                sender.sendMessage("§aНовый ключ (сохраните сейчас): §f" + key);
            } catch (Exception e) { sender.sendMessage("§cОшибка: " + e.getMessage()); }
            return true;
        }
        if (sub.equals("approve")) {
            if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("§cТолько консоль."); return true; }
            if (args.length != 2 || !args[1].matches("[A-Za-z0-9._-]+\\.jar")) { sender.sendMessage("Использование: foxbridge approve <имя.jar>"); return true; }
            try {
                Path src = staging.resolve(args[1]).normalize(), dst = plugins.resolve(args[1]).normalize();
                if (!src.startsWith(staging) || !dst.startsWith(plugins) || !Files.isRegularFile(src)) throw new IOException("Файл staging не найден.");
                if (Files.exists(dst)) Files.copy(dst, backups.resolve(args[1] + "." + System.currentTimeMillis() + ".bak"));
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                sender.sendMessage("§aJAR перенесён в plugins/. Для загрузки нужен полный перезапуск.");
            } catch (Exception e) { sender.sendMessage("§cОшибка: " + e.getMessage()); }
            return true;
        }
        sender.sendMessage("§eИспользование: /foxbridge <status|reload|resetkey|approve>");
        return true;
    }

    private void diagnose(CommandSender sender, String bind, int port, boolean tls) {
        sender.sendMessage("§6========== FoxBridge: диагностика хостинга ==========");
        sender.sendMessage("§eJava: §f" + System.getProperty("java.version"));
        sender.sendMessage("§eОС: §f" + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        sender.sendMessage("§eПапка сервера: §f" + Paths.get("").toAbsolutePath().normalize());
        sender.sendMessage("§eПроверка запуска дочернего процесса Java...");
        Path javaBin = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        if (Files.isRegularFile(javaBin)) {
            sender.sendMessage("§7  " + runDiagnosticProcess(javaBin, "-version"));
        } else {
            sender.sendMessage("§c  Исполняемый файл Java не найден: " + javaBin);
        }

        sender.sendMessage("§eПроверка исходящего HTTPS (Cloudflare)... §f" + testHttps("https://www.cloudflare.com/cdn-cgi/trace"));
        sender.sendMessage("§eПроверка исходящего HTTPS (GitHub)... §f" + testHttps("https://github.com/"));

        sender.sendMessage("§eПроверка фонового процесса (10 секунд наблюдения)... §f" + testLongRunningProcess());

        Path cloudflared = findExecutable("cloudflared");
        if (cloudflared == null) {
            sender.sendMessage("§eCloudflared: §cне найден в PATH");
            sender.sendMessage("§7  Программа может быть просто не установлена — это не доказывает запрет хостинга.");
        } else {
            sender.sendMessage("§eCloudflared: §aнайден §f" + cloudflared);
            sender.sendMessage("§7  " + runDiagnosticProcess(cloudflared, "--version"));
        }

        sender.sendMessage("§eFoxBridge API bind: §f" + getConfig().getString("server.bind_address", "127.0.0.1")
                + ":" + getConfig().getInt("server.port", 25965));
        sender.sendMessage("§eTLS API: §f" + (getConfig().getBoolean("tls.enabled", false) ? "включён" : "выключен"));
        sender.sendMessage("§aДиагностика завершена. Настройки и сетевые порты не изменялись.");
        sender.sendMessage("§6==================================================");
    }

    private void sendDiagnostic(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (isEnabled()) sender.sendMessage(message);
        });
    }

    private String testHttps(String address) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "FoxBridge-Diagnostics/1.0");
            int code = connection.getResponseCode();
            return code >= 200 && code < 500
                    ? "соединение установлено, HTTP " + code
                    : "HTTPS доступен, но сервер ответил HTTP " + code;
        } catch (Exception e) {
            return "не удалось: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String testLongRunningProcess() {
        Process process = null;
        try {
            // Проверяем, что хостинг не завершает дочерний процесс сразу после запуска.
            // Процесс намеренно ограничен по времени и всегда останавливается диагностикой.
            process = new ProcessBuilder("sh", "-c", "sleep 15")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                return "процесс завершился раньше 10 секунд (код " + process.exitValue() + ")";
            }
            boolean alive = process.isAlive();
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            return alive
                    ? "успешно: дочерний процесс оставался активным 10 секунд; тестовый процесс остановлен"
                    : "процесс неожиданно завершился до проверки";
        } catch (SecurityException e) {
            return "запуск запрещён политикой безопасности Java/хостинга";
        } catch (Exception e) {
            return "не удалось: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private Path findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) continue;
            Path candidate = Paths.get(dir).resolve(windows ? name + ".exe" : name).normalize();
            if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate;
        }
        return null;
    }

    private String runDiagnosticProcess(Path executable, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "не завершился за 5 секунд (остановлен)";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (output.length() > 240) output = output.substring(0, 240) + "...";
            return "запуск успешен, код " + process.exitValue()
                    + (output.isBlank() ? "" : "; " + output.replace("\n", " "));
        } catch (SecurityException e) {
            return "запуск запрещён политикой безопасности Java/хостинга";
        } catch (Exception e) {
            return "не удалось запустить: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private boolean deny(CommandSender s) { s.sendMessage("§cНедостаточно прав."); return true; }

    private final class Handler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                String ip = ex.getRemoteAddress().getAddress().getHostAddress();
                if (!rateAllowed(ip)) { send(ex, 429, "{\"error\":\"rate_limited\"}"); return; }
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.startsWith("Bearer ") || !MessageDigest.isEqual(keyHash, digest(auth.substring(7).trim()))) {
                    send(ex, 401, "{\"error\":\"unauthorized\"}"); return;
                }
                String path = ex.getRequestURI().getPath();
                Map<String,String> q = query(ex.getRequestURI().getRawQuery());
                if (ex.getRequestMethod().equals("GET") && path.equals("/v1/status")) {
                    send(ex, 200, "{\"ok\":true,\"name\":\"FoxBridge\",\"version\":\"1.0.0\",\"server\":\"Paper\",\"minecraft\":\"1.18.2\"}");
                } else if (ex.getRequestMethod().equals("GET") && path.equals("/v1/plugins")) {
                    String result = Bukkit.getScheduler().callSyncMethod(FoxBridgePlugin.this, FoxBridgePlugin.this::pluginsJson).get(5, TimeUnit.SECONDS);
                    send(ex, 200, result);
                } else if (ex.getRequestMethod().equals("GET") && path.equals("/v1/files")) {
                    send(ex, 200, list(q.getOrDefault("path", "plugins")));
                } else if (ex.getRequestMethod().equals("GET") && (path.equals("/v1/read") || path.equals("/v1/logs/latest"))) {
                    read(ex, path.equals("/v1/logs/latest") ? "logs/latest.log" : q.getOrDefault("path", ""));
                } else if (ex.getRequestMethod().equals("POST") && path.equals("/v1/stage")) stage(ex);
                else send(ex, 404, "{\"error\":\"not_found\"}");
            } catch (SecurityException e) { send(ex, 403, "{\"error\":\"forbidden\"}"); }
            catch (Exception e) { send(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}"); }
            finally { ex.close(); }
        }
    }

    private byte[] digest(String value) {
        try { return sha(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { return new byte[0]; }
    }
    private boolean rateAllowed(String ip) {
        long now = System.currentTimeMillis();
        Rate r = rates.computeIfAbsent(ip, k -> new Rate(now));
        synchronized (r) {
            if (now - r.start > 60000) { r.start = now; r.count.set(0); }
            return r.count.incrementAndGet() <= Math.max(1, getConfig().getInt("security.max_requests_per_minute_per_ip", 60));
        }
    }
    private String pluginsJson() {
        List<String> list = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins())
            list.add("{\"name\":\"" + esc(p.getName()) + "\",\"version\":\"" + esc(p.getDescription().getVersion()) + "\",\"enabled\":" + p.isEnabled() + "}");
        return "{\"plugins\":[" + String.join(",", list) + "]}";
    }
    private String list(String rel) throws IOException {
        Path dir = resolve(rel);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("not_a_directory");
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (!Files.isSymbolicLink(p))
                out.add("{\"name\":\"" + esc(p.getFileName().toString()) + "\",\"directory\":" + Files.isDirectory(p) + ",\"size\":" + (Files.isRegularFile(p) ? Files.size(p) : 0) + "}");
        }
        return "{\"path\":\"" + esc(rel) + "\",\"items\":[" + String.join(",", out) + "]}";
    }
    private void read(HttpExchange ex, String rel) throws IOException {
        Path p = resolve(rel);
        if (!Files.isRegularFile(p) || Files.isSymbolicLink(p)) throw new IllegalArgumentException("not_a_file");
        long max = Math.max(1024, getConfig().getLong("access.max_read_bytes", 524288));
        if (Files.size(p) > max) throw new IllegalArgumentException("file_too_large");
        byte[] b = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b);
    }
    private Path resolve(String rel) throws IOException {
        if (rel == null || rel.isBlank() || rel.indexOf('\0') >= 0) throw new SecurityException("invalid_path");
        String clean = rel.replace('\\', '/');
        if (clean.startsWith("/")) throw new SecurityException("absolute_path");
        Path part = Paths.get(clean).normalize();
        if (part.startsWith("..") || part.getNameCount() < 1) throw new SecurityException("path_traversal");
        Path root, target;
        String first = part.getName(0).toString();
        if (first.equals("plugins") && getConfig().getBoolean("access.allow_plugins_directory", true)) root = plugins;
        else if (first.equals("logs") && getConfig().getBoolean("access.allow_logs_directory", true)) root = logs;
        else throw new SecurityException("root_not_allowed");
        target = part.getNameCount() == 1 ? root : root.resolve(part.subpath(1, part.getNameCount()));
        target = target.toAbsolutePath().normalize();
        if (!target.startsWith(root.toAbsolutePath().normalize())) throw new SecurityException("path_escape");
        Path realRoot = root.toRealPath(), cursor = target;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) cursor = cursor.getParent();
        if (cursor == null || !cursor.toRealPath().startsWith(realRoot)) throw new SecurityException("symlink_escape");
        Path walk = root;
        for (Path segment : root.relativize(target)) {
            walk = walk.resolve(segment);
            if (Files.isSymbolicLink(walk)) throw new SecurityException("symlink_forbidden");
        }
        return target;
    }
    private void stage(HttpExchange ex) throws IOException {
        if (!getConfig().getBoolean("uploads.enabled", true)) throw new SecurityException("uploads_disabled");
        String name = ex.getRequestHeaders().getFirst("X-Filename");
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.jar")) throw new IllegalArgumentException("invalid_filename");
        long max = Math.max(1024, getConfig().getLong("access.max_upload_bytes", 67108864));
        Path temp = staging.resolve(name + ".uploading"), dest = staging.resolve(name);
        long count = 0;
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) { count += n; if (count > max) throw new IOException("upload_too_large"); out.write(buf, 0, n); }
        } catch (Exception e) { Files.deleteIfExists(temp); throw e; }
        Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
        send(ex, 201, "{\"ok\":true,\"staged\":\"" + esc(name) + "\",\"bytes\":" + count + ",\"installed\":false}");
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> m = new HashMap<>();
        if (raw != null) for (String p : raw.split("&")) {
            String[] kv = p.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }
    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length); ex.getResponseBody().write(b);
    }
    private static String esc(String s) {
        if (s == null) return "unknown";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    private static final class Rate {
        long start; AtomicInteger count = new AtomicInteger();
        Rate(long start) { this.start = start; }
    }
}
