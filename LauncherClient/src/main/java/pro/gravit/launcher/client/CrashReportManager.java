package pro.gravit.launcher.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;
import pro.gravit.launcher.base.request.CrashReportRequest;
import pro.gravit.launcher.base.request.Request;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CrashReportManager {
    /**
     * Larger reports are not sent: the server rejects anything above its WebSocket frame limit anyway
     */
    private static final long MAX_REPORT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 8;
    private static final long BASE_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long MAX_RETRY_MILLIS = TimeUnit.HOURS.toMillis(6);

    // daemon: the crash watcher must never keep the game process alive
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, (r) -> {
        Thread thread = new Thread(r, "CrashReportManager");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean initialized = false;
    private static Path crashReportsDir;
    private static Path sentReportsLogFile;
    /**
     * Reports that are done: sent, or refused by the server for good
     */
    private static final Set<String> sentReports = ConcurrentHashMap.newKeySet();
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private record Attempt(int count, long nextAt) {
    }

    public static synchronized void initialize(Path gameDir) {
        if (initialized) return;

        crashReportsDir = gameDir.resolve("crash-reports");
        sentReportsLogFile = gameDir.resolve("sent_crash_reports.log");
        initialized = true;

        try {
            if (Files.exists(sentReportsLogFile)) {
                sentReports.addAll(Files.readAllLines(sentReportsLogFile));
            }
        } catch (IOException e) {
            LogHelper.error("Failed to load sent crash reports log: %s", e.getMessage());
        }

        LogHelper.info("CrashReportManager initialized, watching: %s", crashReportsDir);

        // Запускаємо моніторинг кожні 30 секунд
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForNewCrashes();
            } catch (Throwable e) {
                LogHelper.error("Error checking for new crashes: %s", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public static void shutdown() {
        if (!scheduler.isShutdown()) {
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

        List<Path> crashFiles;
        try (Stream<Path> files = Files.list(crashReportsDir)) {
            crashFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .filter(path -> path.getFileName().toString().startsWith("crash-"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LogHelper.error("Error checking crash reports directory: %s", e.getMessage());
            return;
        }

        long currentTime = System.currentTimeMillis();
        for (Path crashFile : crashFiles) {
            String crashFileName = crashFile.getFileName().toString();
            if (sentReports.contains(crashFileName) || inFlight.contains(crashFileName)) {
                continue; // Already sent or being sent right now
            }
            Attempt attempt = attempts.get(crashFileName);
            if (attempt != null && (attempt.count() >= MAX_ATTEMPTS || currentTime < attempt.nextAt())) {
                continue; // gave up, or waiting before the next attempt
            }

            try {
                // Перевіряємо що файл не змінювався останні 5 секунд (файл завершений)
                long lastModified = Files.getLastModifiedTime(crashFile).toMillis();
                if (currentTime - lastModified < 5000) {
                    continue; // Файл ще може записуватися
                }
                if (Files.size(crashFile) > MAX_REPORT_BYTES) {
                    LogHelper.warning("Crash report %s is too large to send, skipping", crashFileName);
                    markDone(crashFileName);
                    continue;
                }
            } catch (IOException e) {
                LogHelper.error("Cannot read crash report %s: %s", crashFileName, e.getMessage());
                continue;
            }

            if (!inFlight.add(crashFileName)) {
                continue;
            }
            LogHelper.info("New crash detected: %s", crashFileName);

            // Надсилаємо crash report асинхронно
            CompletableFuture.runAsync(() -> {
                try {
                    sendCrashReport(crashFile);
                } catch (Exception e) {
                    LogHelper.error("Failed to send crash report: %s (%s)", crashFile, e.getMessage());
                    scheduleRetry(crashFileName);
                } finally {
                    inFlight.remove(crashFileName);
                }
            });
        }
    }

    private static void scheduleRetry(String filename) {
        attempts.merge(filename, new Attempt(1, System.currentTimeMillis() + BASE_RETRY_MILLIS), (old, ignored) -> {
            int count = old.count() + 1;
            long delay = Math.min(MAX_RETRY_MILLIS, BASE_RETRY_MILLIS << Math.min(count, 20));
            return new Attempt(count, System.currentTimeMillis() + delay);
        });
    }

    private static synchronized void markDone(String filename) {
        sentReports.add(filename);
        attempts.remove(filename);
        try {
            Files.writeString(sentReportsLogFile, filename + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LogHelper.error("Failed to update sent crash reports log: %s", e.getMessage());
        }
    }

    /**
     * The server answered "no" in a way that will not change by asking again
     */
    private static boolean isPermanentRefusal(String message) {
        if (message == null) return false;
        return message.startsWith("Invalid crash report format")
                || message.startsWith("File size exceeds limit")
                || message.startsWith("Too many stored crash reports")
                || message.startsWith("Crash reports not enabled");
    }

    private static String readReport(Path crashFile) throws IOException {
        // a crash report may contain bytes that are not valid UTF-8: replace them instead of failing
        return new String(Files.readAllBytes(crashFile), StandardCharsets.UTF_8);
    }

    private static void sendCrashReport(Path crashFile) throws Exception {
        if (!Request.isAvailable()) {
            LogHelper.warning("Request service not available, cannot send crash report");
            throw new IllegalStateException("Request service not available");
        }

        String filename = crashFile.getFileName().toString();
        String content = readReport(crashFile);

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
            LogHelper.error("Failed to save crash report json for diagnostics: %s", e.getMessage());
        }

        CrashReportRequestEvent event = request.request();

        if (event.success) {
            LogHelper.info("Crash report sent successfully: %s", filename);
            markDone(filename);
            LogHelper.info("Server response: %s", event.message);
            if (event.savedPath != null) {
                LogHelper.info("Saved to: %s", event.savedPath);
            }
        } else if (isPermanentRefusal(event.message)) {
            LogHelper.warning("Crash report %s was refused by the server and will not be sent again: %s", filename, event.message);
            markDone(filename);
        } else {
            LogHelper.error("Failed to send crash report: %s", event.message);
            scheduleRetry(filename);
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
                if (Files.size(crashFile) > MAX_REPORT_BYTES) {
                    return new CrashReportRequestEvent(false, "Crash report is too large to send");
                }
                String content = readReport(crashFile);
                String gameVersion = extractGameVersion(content);
                String forgeVersion = extractForgeVersion(content);

                CrashReportRequest request = new CrashReportRequest(filename, content, gameVersion, forgeVersion);
                return request.request();

            } catch (Exception e) {
                LogHelper.error("Failed to send crash report manually: %s", e.getMessage());
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

        try (Stream<Path> files = Files.list(crashReportsDir)) {
            return files
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
            LogHelper.error("Error getting crash files: %s", e.getMessage());
            return List.of();
        }
    }
}
