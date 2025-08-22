package pro.gravit.launcher.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;
import pro.gravit.launcher.base.request.CrashReportRequest;
import pro.gravit.launcher.base.request.Request;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class CrashReportManager {
    private static final Logger logger = LogManager.getLogger();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static boolean initialized = false;
    private static Path crashReportsDir;
    private static Path sentReportsLogFile;
    private static final Set<String> sentReports = new HashSet<>();

    public static void initialize(Path gameDir) {
        if (initialized) return;

        crashReportsDir = gameDir.resolve("crash-reports");
        sentReportsLogFile = gameDir.resolve("sent_crash_reports.log");
        initialized = true;

        try {
            if (Files.exists(sentReportsLogFile)) {
                sentReports.addAll(Files.readAllLines(sentReportsLogFile));
            }
        } catch (IOException e) {
            logger.error("Failed to load sent crash reports log", e);
        }

        LogHelper.info("CrashReportManager initialized, watching: %s", crashReportsDir);

        // Запускаємо моніторинг кожні 30 секунд
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForNewCrashes();
            } catch (Exception e) {
                logger.error("Error checking for new crashes", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    public static void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
    }
    
    private static void checkForNewCrashes() {
        if (!initialized || !Files.exists(crashReportsDir)) {
            return;
        }

        try {
            List<Path> crashFiles = Files.list(crashReportsDir)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .filter(path -> path.getFileName().toString().startsWith("crash-"))
                    .collect(Collectors.toList());

            for (Path crashFile : crashFiles) {
                String crashFileName = crashFile.getFileName().toString();
                if (sentReports.contains(crashFileName)) {
                    continue; // Already sent
                }

                // Перевіряємо що файл не змінювався останні 5 секунд (файл завершений)
                long lastModified = Files.getLastModifiedTime(crashFile).toMillis();
                long currentTime = System.currentTimeMillis();

                if (currentTime - lastModified < 5000) {
                    continue; // Файл ще може записуватися
                }

                LogHelper.info("New crash detected: %s", crashFileName);

                // Надсилаємо crash report асинхронно
                CompletableFuture.runAsync(() -> {
                    try {
                        sendCrashReport(crashFile);
                    } catch (Exception e) {
                        logger.error("Failed to send crash report: {}", crashFile, e);
                    }
                });
            }

        } catch (IOException e) {
            logger.error("Error checking crash reports directory", e);
        }
    }
    
    private static void sendCrashReport(Path crashFile) throws Exception {
        if (!Request.isAvailable()) {
            LogHelper.warning("Request service not available, cannot send crash report");
            return;
        }

        String filename = crashFile.getFileName().toString();
        String content = Files.readString(crashFile);

        // Витягаємо інформацію про версії з crash report
        String gameVersion = extractGameVersion(content);
        String forgeVersion = extractForgeVersion(content);

        CrashReportRequest request = new CrashReportRequest(filename, content, gameVersion, forgeVersion);

        // Save for diagnostics
        try {
            Gson gson = new Gson();
            JsonObject jsonObject = gson.toJsonTree(request).getAsJsonObject();
            jsonObject.addProperty("type", request.getType());
            String json = jsonObject.toString();

            Path jsonCrashReportsDir = crashReportsDir.getParent().resolve("crash-reports-json");
            Files.createDirectories(jsonCrashReportsDir);
            Path jsonFile = jsonCrashReportsDir.resolve(filename.replace(".txt", ".json"));
            Files.writeString(jsonFile, json);
            LogHelper.info("Saved crash report json for diagnostics: %s", jsonFile.toString());
        } catch (Exception e) {
            logger.error("Failed to save crash report json for diagnostics", e);
        }

        try {
            CrashReportRequestEvent event = request.request();

            if (event.success) {
                LogHelper.info("Crash report sent successfully: %s", filename);
                sentReports.add(filename);
                try {
                    Files.writeString(sentReportsLogFile, filename + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    logger.error("Failed to update sent crash reports log", e);
                }
                LogHelper.info("Server response: %s", event.message);
                if (event.savedPath != null) {
                    LogHelper.info("Saved to: %s", event.savedPath);
                }
            } else {
                LogHelper.error("Failed to send crash report: %s", event.message);
            }

        } catch (Exception e) {
            LogHelper.error("Error sending crash report: %s", e.getMessage());
            throw e;
        }
    }
    
    private static String extractGameVersion(String content) {
        // Шукаємо рядок типу "Minecraft Version: 1.16.5"
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("Minecraft Version:")) {
                return line.substring(line.indexOf(":") + 1).trim();
            }
        }
        return "unknown";
    }
    
    private static String extractForgeVersion(String content) {
        // Шукаємо рядок типу "Forge: net.minecraftforge:36.2.39"
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("Forge:")) {
                String forgeInfo = line.substring(line.indexOf(":") + 1).trim();
                if (forgeInfo.contains(":")) {
                    return forgeInfo.substring(forgeInfo.lastIndexOf(":") + 1);
                }
                return forgeInfo;
            }
        }
        return "unknown";
    }
    
    /**
     * Ручне надсилання crash report (для майбутнього GUI)
     */
    public static CompletableFuture<CrashReportRequestEvent> sendCrashReportManual(Path crashFile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filename = crashFile.getFileName().toString();
                String content = Files.readString(crashFile);
                String gameVersion = extractGameVersion(content);
                String forgeVersion = extractForgeVersion(content);
                
                CrashReportRequest request = new CrashReportRequest(filename, content, gameVersion, forgeVersion);
                return request.request();
                
            } catch (Exception e) {
                logger.error("Failed to send crash report manually", e);
                return new CrashReportRequestEvent(false, "Failed to send: " + e.getMessage());
            }
        });
    }
    
    /**
     * Отримання списку всіх crash files
     */
    public static List<Path> getAllCrashFiles() {
        if (!initialized || !Files.exists(crashReportsDir)) {
            return List.of();
        }
        
        try {
            return Files.list(crashReportsDir)
                .filter(path -> path.getFileName().toString().endsWith(".txt"))
                .filter(path -> path.getFileName().toString().startsWith("crash-"))
                .sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error getting crash files", e);
            return List.of();
        }
    }
}