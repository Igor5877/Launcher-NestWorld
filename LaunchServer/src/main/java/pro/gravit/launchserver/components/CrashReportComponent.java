package pro.gravit.launchserver.components;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.utils.helper.IOHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class CrashReportComponent extends Component implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger();
    
    // Конфігурація
    public boolean enabled = true;
    public String serverHost = "localhost";
    public int serverPort = 9275;
    public long maxFileSize = 20971520; // 20MB за замовчуванням
    public String storagePath = "crash";
    public boolean requireAuth = true;
    public int rateLimitPerHour = 10;
    public int maxReportsPerUser = 100;
    public boolean cleanupOldReports = true;
    public int maxReportAgeDays = 30;
    
    // Для rate limiting
    private final ConcurrentHashMap<String, AtomicInteger> userReportCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> userLastReportTime = new ConcurrentHashMap<>();
    
    private transient LaunchServer server;
    private transient Path crashDir;
    
    @Override
    public void init(LaunchServer launchServer) {
        this.server = launchServer;
        
        // Читаємо конфігурацію з системних властивостей
        this.serverHost = System.getProperty("crash.server.host", this.serverHost);
        this.serverPort = Integer.parseInt(System.getProperty("crash.server.port", String.valueOf(this.serverPort)));
        this.maxFileSize = Long.parseLong(System.getProperty("crash.max.file.size", String.valueOf(this.maxFileSize)));
        this.storagePath = System.getProperty("crash.storage.path", this.storagePath);
        this.enabled = Boolean.parseBoolean(System.getProperty("crash.enabled", String.valueOf(this.enabled)));
        
        if (!enabled) {
            logger.info("CrashReportComponent is disabled");
            return;
        }
        
        // Створюємо директорію для crash reports
        crashDir = Paths.get(storagePath);
        try {
            if (!Files.exists(crashDir)) {
                Files.createDirectories(crashDir);
                logger.info("Created crash reports directory: {}", crashDir.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to create crash reports directory", e);
            throw new RuntimeException("Failed to initialize crash reports directory", e);
        }
        
        logger.info("CrashReportComponent initialized:");
        logger.info("  Server: {}:{}", serverHost, serverPort);
        logger.info("  Max file size: {} bytes", maxFileSize);
        logger.info("  Storage path: {}", crashDir.toAbsolutePath());
        logger.info("  Rate limit: {} reports per hour", rateLimitPerHour);
        logger.info("  Max reports per user: {}", maxReportsPerUser);
        
        // Запускаємо cleanup task якщо увімкнено
        if (cleanupOldReports) {
            startCleanupTask();
        }
    }
    
    public boolean canUserSendReport(String username) {
        if (!enabled) return false;
        
        long currentTime = System.currentTimeMillis();
        long hourInMillis = 3600000; // 1 година
        
        // Перевіряємо rate limiting
        String key = username;
        AtomicInteger count = userReportCount.computeIfAbsent(key, k -> new AtomicInteger(0));
        Long lastTime = userLastReportTime.get(key);
        
        if (lastTime != null && (currentTime - lastTime) < hourInMillis) {
            if (count.get() >= rateLimitPerHour) {
                logger.warn("Rate limit exceeded for user: {}", username);
                return false;
            }
        } else {
            // Скидаємо лічильник через годину
            count.set(0);
        }
        
        return true;
    }
    
    public void recordUserReport(String username) {
        String key = username;
        userReportCount.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        userLastReportTime.put(key, System.currentTimeMillis());
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
    
    public Path getUserCrashDir(String username) throws IOException {
        Path userDir = crashDir.resolve(username);
        if (!Files.exists(userDir)) {
            Files.createDirectories(userDir);
        }
        return userDir;
    }
    
    public boolean validateClient(Client client) {
        if (!enabled) return false;
        
        if (requireAuth && !client.isAuth) {
            logger.warn("Unauthenticated client attempted to send crash report from IP: {}", client.ip);
            return false;
        }
        
        return true;
    }
    
    private void startCleanupTask() {
        // Запускаємо задачу очищення старих звітів кожні 24 години
        server.service.scheduleAtFixedRate(() -> {
            try {
                cleanupOldReports();
            } catch (Exception e) {
                logger.error("Failed to cleanup old crash reports", e);
            }
        }, 24, 24, java.util.concurrent.TimeUnit.HOURS);
    }
    
    private void cleanupOldReports() {
        if (!Files.exists(crashDir)) return;
        
        long maxAge = System.currentTimeMillis() - (maxReportAgeDays * 24L * 60L * 60L * 1000L);
        
        try {
            Files.walk(crashDir)
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
    
    @Override
    public void close() {
        logger.info("CrashReportComponent closed");
    }
}