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
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FoxBridgePlugin extends JavaPlugin implements CommandExecutor {
    private HttpServer server;
    private ExecutorService executor;
    private Path data, plugins, logs, staging, backups;
    private volatile byte[] keyHash;
    private final Map<String, Rate> rates = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean diagnosticRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean cloudflaredTestRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

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
                sender.sendMessage("§c[FoxBridge] Диагностика доступна только из консоли сервера.");
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
        if (sub.equals("cloudflared")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage("§c[FoxBridge] Проверка cloudflared доступна только из консоли сервера.");
                return true;
            }
            if (!cloudflaredTestRunning.compareAndSet(false, true)) {
                sender.sendMessage("§e[FoxBridge] Проверка cloudflared уже выполняется.");
                return true;
            }
            try {
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        checkCloudflared(sender);
                    } finally {
                        cloudflaredTestRunning.set(false);
                    }
                });
            } catch (Exception e) {
                cloudflaredTestRunning.set(false);
                sender.sendMessage("§c[FoxBridge] Не удалось запустить проверку: " + e.getMessage());
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
        sendDiagnostic(sender, "§6========== FoxBridge: диагностика хостинга ==========");
        sendDiagnostic(sender, "§eJava: §f" + System.getProperty("java.version"));
        sendDiagnostic(sender, "§eОС: §f" + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        sendDiagnostic(sender, "§eПапка сервера: §f" + Paths.get("").toAbsolutePath().normalize());
        sendDiagnostic(sender, "§eПроверка запуска дочернего процесса Java...");
        Path javaBin = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        if (Files.isRegularFile(javaBin)) {
            sendDiagnostic(sender, "§7  " + runDiagnosticProcess(javaBin, "-version"));
        } else {
            sendDiagnostic(sender, "§c  Исполняемый файл Java не найден: " + javaBin);
        }

        sendDiagnostic(sender, "§eПроверка исходящего HTTPS (Cloudflare)... §f" + testHttps("https://www.cloudflare.com/cdn-cgi/trace"));
        sendDiagnostic(sender, "§eПроверка исходящего HTTPS (GitHub)... §f" + testHttps("https://github.com/"));

        sendDiagnostic(sender, "§eПроверка фонового процесса (10 секунд наблюдения)... §f" + testLongRunningProcess());

        Path cloudflared = findExecutable("cloudflared");
        if (cloudflared == null) {
            sendDiagnostic(sender, "§eCloudflared: §cне найден в PATH");
            sendDiagnostic(sender, "§7  Программа может быть просто не установлена — это не доказывает запрет хостинга.");
        } else {
            sendDiagnostic(sender, "§eCloudflared: §aнайден §f" + cloudflared);
            sendDiagnostic(sender, "§7  " + runDiagnosticProcess(cloudflared, "--version"));
        }

        sendDiagnostic(sender, "§eFoxBridge API bind: §f" + bind + ":" + port);
        sendDiagnostic(sender, "§eTLS API: §f" + (tls ? "включён" : "выключен"));
        sendDiagnostic(sender, "§aДиагностика завершена. Настройки и сетевые порты не изменялись.");
        sendDiagnostic(sender, "§6==================================================");
    }


    /**
     * Downloads the official Linux x86_64 cloudflared binary, verifies the SHA-256
     * digest published by the official Cloudflare GitHub release API, then tests
     * a temporary Quick Tunnel to the closed local port 1. No FoxBridge API port
     * or listener setting is changed and the temporary process is always stopped.
     */
    private void checkCloudflared(CommandSender sender) {
        sendDiagnostic(sender, "§6========== FoxBridge: проверка cloudflared ==========");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            sendDiagnostic(sender, "§cАвтозагрузка поддерживается только на Linux amd64; обнаружено: " + os + " / " + arch);
            sendDiagnostic(sender, "§6==================================================");
            return;
        }

        Path toolDir = data.resolve("tools").normalize();
        Path binary = toolDir.resolve("cloudflared").normalize();
        Path logFile = toolDir.resolve("cloudflared-test.log").normalize();
        Process process = null;
        try {
            Files.createDirectories(toolDir);
            sendDiagnostic(sender, "§eПолучение официальной версии Cloudflare и SHA-256...");
            String release = downloadOfficialCloudflared(binary);
            sendDiagnostic(sender, "§aБинарник загружен и SHA-256 подтверждён. Версия релиза: §f" + release);

            Files.deleteIfExists(logFile);
            sendDiagnostic(sender, "§eЗапуск временного Quick Tunnel на 45 секунд...");
            sendDiagnostic(sender, "§7Туннель направлен только на 127.0.0.1:1 (закрытый порт); FoxBridge API не публикуется.");
            process = new ProcessBuilder(binary.toString(), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:1")
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

            boolean exited = process.waitFor(45, TimeUnit.SECONDS);
            String output = readSmallLog(logFile);
            if (!exited) {
                boolean connected = output.contains("Registered tunnel connection");
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
                if (connected) {
                    sendDiagnostic(sender, "§aУспех: cloudflared установил соединение с сетью Cloudflare и работал 45 секунд.");
                } else {
                    sendDiagnostic(sender, "§eПроцесс cloudflared оставался активным 45 секунд, но подтверждение подключения к edge не найдено.");
                    sendDiagnostic(sender, "§7Последние сообщения: " + safeLogSummary(output));
                }
                sendDiagnostic(sender, "§7Временный процесс остановлен.");
            } else {
                sendDiagnostic(sender, "§ccloudflared завершился раньше 45 секунд (код " + process.exitValue() + ").");
                sendDiagnostic(sender, "§7Сообщения: " + safeLogSummary(output));
            }
        } catch (Exception e) {
            sendDiagnostic(sender, "§cПроверка cloudflared не удалась: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                try { if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            }
            try { Files.deleteIfExists(logFile); } catch (IOException ignored) {}
            sendDiagnostic(sender, "§aПроверка завершена. Настройки FoxBridge API и сетевые порты не изменялись.");
            sendDiagnostic(sender, "§6==================================================");
        }
    }

    private String downloadOfficialCloudflared(Path destination) throws Exception {
        URL api = new URL("https://api.github.com/repos/cloudflare/cloudflared/releases/latest");
        HttpURLConnection meta = (HttpURLConnection) api.openConnection();
        meta.setConnectTimeout(10000);
        meta.setReadTimeout(15000);
        meta.setRequestProperty("Accept", "application/vnd.github+json");
        meta.setRequestProperty("User-Agent", "FoxBridge-Cloudflared-Installer/1.0");
        String json;
        try {
            int code = meta.getResponseCode();
            if (code != 200) throw new IOException("GitHub Releases API ответил HTTP " + code);
            try (InputStream in = meta.getInputStream()) { json = new String(readBounded(in, 2 * 1024 * 1024), StandardCharsets.UTF_8); }
        } finally { meta.disconnect(); }

        String asset = jsonObjectContaining(json, "\"name\"\\s*:\\s*\"cloudflared-linux-amd64\"");
        if (asset == null) throw new IOException("Официальный релиз не содержит cloudflared-linux-amd64.");
        String downloadUrl = jsonString(asset, "browser_download_url");
        String digest = jsonString(asset, "digest");
        String tag = jsonString(jsonObjectContaining(json, "\"tag_name\"\\s*:"), "tag_name");
        long expectedSize = jsonLong(asset, "size");
        if (downloadUrl == null || !downloadUrl.startsWith("https://github.com/cloudflare/cloudflared/releases/download/")
                || !downloadUrl.endsWith("/cloudflared-linux-amd64")) throw new IOException("Недопустимый адрес официального бинарника.");
        if (digest == null || !digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IOException("В GitHub Release отсутствует SHA-256 digest.");
        if (expectedSize < 1024 * 1024 || expectedSize > 50L * 1024 * 1024) throw new IOException("Размер бинарника вне безопасного диапазона.");

        Path temp = destination.resolveSibling("cloudflared.download");
        Files.deleteIfExists(temp);
        HttpURLConnection download = (HttpURLConnection) new URL(downloadUrl).openConnection();
        download.setConnectTimeout(15000);
        download.setReadTimeout(30000);
        download.setInstanceFollowRedirects(true);
        download.setRequestProperty("User-Agent", "FoxBridge-Cloudflared-Installer/1.0");
        try {
            int code = download.getResponseCode();
            if (code != 200) throw new IOException("Скачивание завершилось с HTTP " + code);
            String host = download.getURL().getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com"))) throw new IOException("Неожиданный хост перенаправления: " + host);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (InputStream in = download.getInputStream();
                 OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    count += n;
                    if (count > expectedSize || count > 50L * 1024 * 1024) throw new IOException("Файл превышает ожидаемый размер.");
                    sha256.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (count != expectedSize) throw new IOException("Размер скачанного файла не совпадает с GitHub Release.");
            String actual = HexFormat.of().formatHex(sha256.digest());
            String expected = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("SHA-256 не совпал с опубликованным GitHub digest; бинарник удалён.");
            }
            try {
                Files.setPosixFilePermissions(temp, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException e) {
                if (!temp.toFile().setExecutable(true, true)) throw new IOException("Не удалось сделать бинарник исполняемым.");
            }
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
            Files.writeString(destination.resolveSibling("cloudflared.sha256"), actual + "  cloudflared\\n", StandardCharsets.UTF_8);
            return tag == null ? "latest" : tag;
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        } finally { download.disconnect(); }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > max) throw new IOException("Ответ GitHub API слишком большой.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String jsonObjectContaining(String json, String keyPattern) {
        Matcher m = Pattern.compile(keyPattern).matcher(json);
        if (!m.find()) return null;
        int start = json.lastIndexOf('{', m.start());
        if (start < 0) return null;
        boolean quoted = false, escaped = false;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else {
                if (ch == '"') quoted = true;
                else if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String jsonString(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static long jsonLong(String json, String key) {
        if (json == null) return -1;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String readSmallLog(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "";
            byte[] bytes = Files.readAllBytes(file);
            int start = Math.max(0, bytes.length - 12000);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }

    private static String safeLogSummary(String log) {
        if (log == null || log.isBlank()) return "нет вывода";
        String cleaned = log.replaceAll("https?://\\S+", "[ссылка скрыта]")
                .replaceAll("[\\r\\n]+", " ").trim();
        return cleaned.length() > 300 ? cleaned.substring(cleaned.length() - 300) : cleaned;
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
