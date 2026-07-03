package pro.gravit.launchserver.components;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.socket.Client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class CrashReportComponent extends Component implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger();
    private static final long HOUR_IN_MILLIS = 3600000L;

    // Конфігурація
    public boolean enabled = true;
    public long maxFileSize = 20971520; // 20MB за замовчуванням
    public String storagePath = "crash";
    public boolean requireAuth = true;
    public int rateLimitPerHour = 10;
    public int maxReportsPerUser = 100;
    public boolean cleanupOldReports = true;
    public int maxReportAgeDays = 30;

    // Інтеграція з тікет-системою Azuriom Support (обидва поля задані = увімкнено)
    public String ticketApiUrl = null;   // напр. https://site.example/api/support/crash
    public String ticketApiToken = null;
    public int ticketCommentMaxChars = 12000;

    // Для rate limiting (фіксоване вікно на годину)
    private static final class RateWindow {
        long windowStart;
        int count;
    }

    private final transient ConcurrentHashMap<String, RateWindow> userReports = new ConcurrentHashMap<>();

    private transient LaunchServer server;
    private transient Path crashDir;
    private transient HttpClient httpClient;

    @Override
    public void init(LaunchServer launchServer) {
        this.server = launchServer;

        // Читаємо конфігурацію з системних властивостей
        this.maxFileSize = Long.parseLong(System.getProperty("crash.max.file.size", String.valueOf(this.maxFileSize)));
        this.storagePath = System.getProperty("crash.storage.path", this.storagePath);
        this.enabled = Boolean.parseBoolean(System.getProperty("crash.enabled", String.valueOf(this.enabled)));
        this.ticketApiUrl = System.getProperty("crash.ticket.url", this.ticketApiUrl);
        this.ticketApiToken = System.getProperty("crash.ticket.token", this.ticketApiToken);

        if (!enabled) {
            logger.info("CrashReportComponent is disabled");
            return;
        }

        // Створюємо директорію для crash reports
        crashDir = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            if (!Files.exists(crashDir)) {
                Files.createDirectories(crashDir);
                logger.info("Created crash reports directory: {}", crashDir);
            }
        } catch (IOException e) {
            logger.error("Failed to create crash reports directory", e);
            throw new RuntimeException("Failed to initialize crash reports directory", e);
        }

        if (ticketApiUrl != null && ticketApiToken != null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        logger.info("CrashReportComponent initialized:");
        logger.info("  Max file size: {} bytes", maxFileSize);
        logger.info("  Storage path: {}", crashDir);
        logger.info("  Rate limit: {} reports per hour", rateLimitPerHour);
        logger.info("  Max reports per user: {}", maxReportsPerUser);
        logger.info("  Ticket API: {}", httpClient != null ? ticketApiUrl : "disabled");

        // Запускаємо cleanup task якщо увімкнено
        if (cleanupOldReports) {
            startCleanupTask();
        }
    }

    /**
     * Атомарно перевіряє rate limit і займає слот для звіту.
     */
    public boolean tryAcquireReportSlot(String username) {
        if (!enabled) return false;

        long currentTime = System.currentTimeMillis();
        RateWindow window = userReports.computeIfAbsent(username, k -> new RateWindow());
        synchronized (window) {
            if (currentTime - window.windowStart >= HOUR_IN_MILLIS) {
                window.windowStart = currentTime;
                window.count = 0;
            }
            if (window.count >= rateLimitPerHour) {
                logger.warn("Rate limit exceeded for user: {}", username);
                return false;
            }
            window.count++;
            return true;
        }
    }

    public boolean isValidCrashReport(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        // Перевіряємо що це схоже на Minecraft crash report
        return content.contains("Minecraft Crash Report") ||
               content.contains("java.lang.Exception") ||
               content.contains("at net.minecraft") ||
               content.contains("at net.minecraftforge");
    }

    public Path getUserCrashDir(String clientName, String username) throws IOException {
        String safeClientName = sanitizeName(clientName);
        String safeUsername = sanitizeName(username);
        Path userDir = crashDir.resolve(safeClientName).resolve(safeUsername).normalize();
        // Захист від path traversal: результат зобов'язаний лишатися всередині crashDir
        if (!userDir.startsWith(crashDir)) {
            throw new IOException("Invalid crash report path for client '" + clientName + "', user '" + username + "'");
        }
        if (!Files.exists(userDir)) {
            Files.createDirectories(userDir);
        }
        return userDir;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "_";
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty() || safe.chars().allMatch(c -> c == '.')) {
            return "_";
        }
        return safe;
    }

    /**
     * Перевіряє, чи не перевищено загальний ліміт збережених звітів користувача.
     */
    public boolean isUserQuotaExceeded(Path userDir) {
        try (Stream<Path> stream = Files.list(userDir)) {
            return stream.count() >= maxReportsPerUser;
        } catch (IOException e) {
            logger.warn("Failed to count crash reports in {}", userDir, e);
            return false;
        }
    }

    public boolean validateClient(Client client) {
        if (!enabled) return false;

        if (requireAuth && !client.isAuth) {
            logger.warn("Unauthenticated client attempted to send crash report from IP: {}", client.ipAddress);
            return false;
        }

        return true;
    }

    /**
     * Асинхронно створює тікет у Azuriom Support (або коментар до наявного —
     * дедуплікація по хешу краша на боці сайту). Помилки лише логуються.
     */
    public void submitTicketAsync(String username, String clientName, String gameVersion,
                                  String forgeVersion, String content, String savedPath) {
        if (httpClient == null) return;

        String hash = computeCrashHash(content);
        String subject = buildSubject(clientName, gameVersion, content, hash);

        String commentBody = content.length() > ticketCommentMaxChars
                ? content.substring(0, ticketCommentMaxChars) + "\n\n... [truncated, see full report on LaunchServer]"
                : content;
        commentBody += "\n\n// Forge version: " + forgeVersion
                + "\n// Full report: " + savedPath;

        JsonObject json = new JsonObject();
        json.addProperty("username", username);
        json.addProperty("subject", subject);
        json.addProperty("content", commentBody);
        json.addProperty("hash", hash);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ticketApiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ticketApiToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        logger.warn("Failed to submit crash ticket for '{}': {}", username, error.getMessage());
                    } else if (response.statusCode() == 200 || response.statusCode() == 201) {
                        logger.info("Crash ticket submitted for '{}' (hash {}): {}", username, hash, response.body());
                    } else {
                        logger.warn("Crash ticket rejected for '{}' (HTTP {}): {}", username, response.statusCode(), response.body());
                    }
                });
    }

    /**
     * Хеш нормалізованого stack trace для групування однакових крашів.
     * Номери рядків та id лямбд прибираються, щоб дрібні відмінності не ламали дедуплікацію.
     */
    public static String computeCrashHash(String content) {
        StringBuilder signature = new StringBuilder();
        int taken = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            boolean exceptionLine = signature.length() == 0
                    && (trimmed.contains("Exception") || trimmed.contains("Error:"));
            if (trimmed.startsWith("at ") || exceptionLine) {
                signature.append(trimmed.replaceAll(":\\d+\\)", ")").replaceAll("\\$\\d+", "\\$")).append('\n');
                if (++taken >= 25) break;
            }
        }
        String basis = signature.length() > 0
                ? signature.toString()
                : content.substring(0, Math.min(content.length(), 2000));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String buildSubject(String clientName, String gameVersion, String content, String hash) {
        String description = "Unknown crash";
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Description:")) {
                description = trimmed.substring("Description:".length()).trim();
                break;
            }
            if (trimmed.contains("Exception") && !trimmed.startsWith("at ")) {
                description = trimmed;
                break;
            }
        }
        String subject = String.format("Crash • %s %s • %s", clientName, gameVersion, description);
        if (subject.length() > 130) {
            subject = subject.substring(0, 130) + "…";
        }
        return subject + " [#" + hash + "]";
    }

    private void startCleanupTask() {
        // Запускаємо задачу очищення старих звітів кожні 24 години
        server.service.scheduleAtFixedRate(() -> {
            try {
                cleanupOldReports();
                cleanupStaleRateWindows();
            } catch (Exception e) {
                logger.error("Failed to cleanup old crash reports", e);
            }
        }, 24, 24, java.util.concurrent.TimeUnit.HOURS);
    }

    private void cleanupOldReports() {
        if (!Files.exists(crashDir)) return;

        long maxAge = System.currentTimeMillis() - (maxReportAgeDays * 24L * 60L * 60L * 1000L);

        try (Stream<Path> stream = Files.walk(crashDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("crash-"))
                .filter(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis() < maxAge;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(path -> {
                    try {
                        Files.delete(path);
                        logger.debug("Deleted old crash report: {}", path);
                    } catch (IOException e) {
                        logger.warn("Failed to delete old crash report: {}", path);
                    }
                });
        } catch (IOException e) {
            logger.error("Failed to cleanup old crash reports", e);
        }
    }

    private void cleanupStaleRateWindows() {
        long cutoff = System.currentTimeMillis() - 2 * HOUR_IN_MILLIS;
        userReports.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().windowStart < cutoff;
            }
        });
    }

    @Override
    public void close() {
        logger.info("CrashReportComponent closed");
    }
}
