package pro.gravit.launchserver.components;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.socket.Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class CrashReportComponent extends Component implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger();
    private static final long HOUR_MILLIS = TimeUnit.HOURS.toMillis(1);

    // Конфігурація
    public boolean enabled = true;
    public String serverHost = "localhost";
    public int serverPort = 9275;
    public long maxFileSize = 20971520; // 20MB за замовчуванням (у байтах; реально обмежено netty maxWebSocketRequestBytes)
    public String storagePath = "crash";
    public boolean requireAuth = true;
    public int rateLimitPerHour = 10;
    public int maxReportsPerUser = 100;
    public long maxTotalBytes = 2L * 1024 * 1024 * 1024; // загальний ліміт сховища
    public boolean cleanupOldReports = true;
    public int maxReportAgeDays = 30;

    // Для rate limiting: час останніх прийнятих звітів за ключем (користувач або IP)
    private transient final ConcurrentHashMap<String, ArrayDeque<Long>> recentReports = new ConcurrentHashMap<>();
    private transient final AtomicLong totalBytes = new AtomicLong();

    private transient LaunchServer server;
    private transient Path crashDir;

    @Override
    public void init(LaunchServer launchServer) {
        this.server = launchServer;

        // Читаємо конфігурацію з системних властивостей
        this.serverHost = System.getProperty("crash.server.host", this.serverHost);
        this.serverPort = (int) readLong("crash.server.port", this.serverPort);
        this.maxFileSize = readLong("crash.max.file.size", this.maxFileSize);
        this.storagePath = System.getProperty("crash.storage.path", this.storagePath);
        this.enabled = Boolean.parseBoolean(System.getProperty("crash.enabled", String.valueOf(this.enabled)));

        if (!enabled) {
            logger.info("CrashReportComponent is disabled");
            return;
        }

        // Створюємо директорію для crash reports
        try {
            useStorage(Paths.get(storagePath));
        } catch (IOException e) {
            logger.error("Failed to create crash reports directory", e);
            throw new RuntimeException("Failed to initialize crash reports directory", e);
        }

        logger.info("CrashReportComponent initialized:");
        logger.info("  Max file size: {} bytes", maxFileSize);
        logger.info("  Storage path: {} ({} bytes used, limit {})", crashDir, totalBytes.get(), maxTotalBytes);
        logger.info("  Rate limit: {} reports per hour", rateLimitPerHour);
        logger.info("  Max reports per user: {}", maxReportsPerUser);

        if (cleanupOldReports) {
            startCleanupTask();
        }
    }

    void useStorage(Path dir) throws IOException {
        crashDir = dir.toAbsolutePath().normalize();
        Files.createDirectories(crashDir);
        totalBytes.set(measureStorage());
    }

    private static long readLong(String property, long def) {
        String value = System.getProperty(property);
        if (value == null) return def;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid value '{}' of -D{}, using {}", value, property, def);
            return def;
        }
    }

    /**
     * Atomically checks and records a report of the given user. A report is accepted only if neither the user
     * nor their IP exceeded the hourly limit (the IP limit is higher: several players may share one address).
     */
    public boolean tryAcquire(String username, String ip) {
        if (!enabled) return false;
        long now = System.currentTimeMillis();
        if (!tryAcquireKey("u:" + username, rateLimitPerHour, now)) {
            logger.warn("Rate limit exceeded for user: {}", username);
            return false;
        }
        if (ip != null && !tryAcquireKey("ip:" + ip, Math.max(1, rateLimitPerHour) * 3, now)) {
            logger.warn("Rate limit exceeded for IP: {}", ip);
            return false;
        }
        return true;
    }

    private boolean tryAcquireKey(String key, int limit, long now) {
        ArrayDeque<Long> times = recentReports.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= HOUR_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
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

    /**
     * A single safe path component: only [A-Za-z0-9._-] survive and a name made of dots cannot climb out of the directory.
     */
    static String sanitize(String name) {
        String safe = name == null ? "" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
        safe = safe.replaceAll("^\\.+", "_");
        return safe.isEmpty() ? "_" : safe;
    }

    public Path getUserCrashDir(String clientName, String username) throws IOException {
        Path userDir = crashDir.resolve(sanitize(clientName)).resolve(sanitize(username)).normalize();
        if (!userDir.startsWith(crashDir)) {
            throw new IOException("Invalid crash report directory");
        }
        Files.createDirectories(userDir);
        return userDir;
    }

    /**
     * Checks the storage limits for one more report of {@code bytes} bytes.
     *
     * @return null if the report may be stored, otherwise the reason for the client
     */
    public String checkQuota(Path userDir, long bytes) throws IOException {
        if (totalBytes.get() + bytes > maxTotalBytes) {
            return "Crash report storage is full";
        }
        try (Stream<Path> files = Files.list(userDir)) {
            if (files.count() >= maxReportsPerUser) {
                return "Too many stored crash reports";
            }
        }
        return null;
    }

    /**
     * Writes the report as a temp file and renames it, so a reader (or a crash) never sees a half written report.
     *
     * @return the stored file
     */
    public Path store(Path userDir, String filename, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(userDir, ".upload-", ".tmp");
        try {
            Files.write(tmp, data);
            String base = filename.endsWith(".txt") ? filename.substring(0, filename.length() - 4) : filename;
            for (int i = 0; i < 100; i++) {
                Path target = userDir.resolve(i == 0 ? filename : base + "-" + i + ".txt");
                try {
                    // exclusive create reserves the name (rename alone would silently overwrite an existing report)
                    Files.createFile(target);
                } catch (FileAlreadyExistsException e) {
                    continue; // two reports in the same second: try the next name
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Files.deleteIfExists(target);
                    throw e;
                }
                totalBytes.addAndGet(data.length);
                return target;
            }
            throw new IOException("Cannot choose a file name for the crash report");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Path getCrashDir() {
        return crashDir;
    }

    public boolean validateClient(Client client) {
        if (!enabled) return false;

        if (requireAuth && !client.isAuth) {
            logger.warn("Unauthenticated client attempted to send crash report from IP: {}", client.ipAddress);
            return false;
        }

        return true;
    }

    private void startCleanupTask() {
        // Перша очистка через 5 хвилин після старту, далі кожні 24 години
        server.service.scheduleAtFixedRate(() -> {
            try {
                cleanupOldReports();
            } catch (Exception e) {
                logger.error("Failed to cleanup old crash reports", e);
            }
        }, 5, 24 * 60, TimeUnit.MINUTES);
    }

    private long measureStorage() {
        try (Stream<Path> walk = Files.walk(crashDir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            logger.warn("Failed to measure crash report storage", e);
            return 0;
        }
    }

    void cleanupOldReports() {
        if (crashDir == null || !Files.exists(crashDir)) return;

        long maxAge = System.currentTimeMillis() - (maxReportAgeDays * 24L * 60L * 60L * 1000L);

        try (Stream<Path> walk = Files.walk(crashDir)) {
            List<Path> old = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("crash-")
                            || path.getFileName().toString().startsWith(".upload-"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < maxAge;
                        } catch (IOException e) {
                            return false;
                        }
                    }).toList();
            for (Path path : old) {
                try {
                    Files.delete(path);
                    logger.debug("Deleted old crash report: {}", path);
                } catch (IOException e) {
                    logger.warn("Failed to delete old crash report: {}", path);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to cleanup old crash reports", e);
        }

        // Порожні директорії користувачів і клієнтів
        try (Stream<Path> walk = Files.walk(crashDir)) {
            for (Path dir : walk.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                if (dir.equals(crashDir)) continue;
                try (Stream<Path> children = Files.list(dir)) {
                    if (children.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                } catch (IOException ignored) {
                    // directory is in use or already gone
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to remove empty crash report directories", e);
        }

        totalBytes.set(measureStorage());
        recentReports.values().removeIf(times -> {
            synchronized (times) {
                return times.isEmpty() || System.currentTimeMillis() - times.peekLast() >= HOUR_MILLIS;
            }
        });
    }

    @Override
    public void close() {
        logger.info("CrashReportComponent closed");
    }
}
