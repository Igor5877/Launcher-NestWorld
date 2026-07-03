package pro.gravit.launcher.client;

import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;
import pro.gravit.launcher.base.request.CrashReportRequest;
import pro.gravit.launcher.base.request.Request;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CrashReportManager {
    // Ліміти витягу: великі краш-репорти не читаємо цілком —
    // надсилаємо початок (заголовок + stack trace) і кінець (System Details з модами)
    private static final int MAX_FULL_SIZE = 131072; // 128 KB — до цього розміру шлемо файл цілком
    private static final int HEAD_SIZE = 65536;      // 64 KB
    private static final int TAIL_SIZE = 49152;      // 48 KB

    private static final long STABILITY_CHECK_DELAY_MS = 1500;
    private static final int MAX_SEND_ATTEMPTS = 5;
    private static final long RETRY_BASE_DELAY_MS = 30_000;

    private static volatile boolean initialized = false;
    private static Path crashReportsDir;
    private static Path sentReportsLogFile;
    private static final Set<String> sentReports = ConcurrentHashMap.newKeySet();
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static final Object sentLogLock = new Object();

    private static ScheduledExecutorService scheduler;
    private static WatchService watchService;
    private static Thread watchThread;

    public static void initialize(Path gameDir) {
        if (initialized) return;

        crashReportsDir = gameDir.resolve("crash-reports");
        sentReportsLogFile = gameDir.resolve("sent_crash_reports.log");

        try {
            if (Files.exists(sentReportsLogFile)) {
                sentReports.addAll(Files.readAllLines(sentReportsLogFile));
            }
        } catch (IOException e) {
            LogHelper.error("Failed to load sent crash reports log: %s", e.getMessage());
        }

        try {
            Files.createDirectories(crashReportsDir);
        } catch (IOException e) {
            LogHelper.error("Failed to create crash reports directory: %s", e.getMessage());
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CrashReportSender");
            t.setDaemon(true);
            return t;
        });

        try {
            watchService = crashReportsDir.getFileSystem().newWatchService();
            crashReportsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException e) {
            LogHelper.error("Failed to start crash reports watch service: %s", e.getMessage());
            watchService = null;
        }

        initialized = true;
        LogHelper.info("CrashReportManager initialized, watching: %s", crashReportsDir);

        if (watchService != null) {
            watchThread = new Thread(CrashReportManager::watchLoop, "CrashReportWatcher");
            watchThread.setDaemon(true);
            watchThread.start();
        }

        // Одноразовий скан: краші попереднього запуску, які не встигли відправитись
        scheduler.execute(CrashReportManager::scanExistingReports);
    }

    public static void shutdown() {
        initialized = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void watchLoop() {
        while (initialized) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    scheduler.execute(CrashReportManager::scanExistingReports);
                    continue;
                }
                Object context = event.context();
                if (!(context instanceof Path)) continue;
                Path fileName = (Path) context;
                if (isCrashReportName(fileName.toString())) {
                    enqueue(crashReportsDir.resolve(fileName));
                }
            }
            if (!key.reset()) {
                LogHelper.warning("Crash reports directory is no longer accessible, watcher stopped");
                return;
            }
        }
    }

    private static boolean isCrashReportName(String name) {
        return name.startsWith("crash-") && name.endsWith(".txt");
    }

    private static void scanExistingReports() {
        try (Stream<Path> stream = Files.list(crashReportsDir)) {
            stream.filter(path -> isCrashReportName(path.getFileName().toString()))
                    .forEach(CrashReportManager::enqueue);
        } catch (IOException e) {
            LogHelper.error("Error scanning crash reports directory: %s", e.getMessage());
        }
    }

    private static void enqueue(Path crashFile) {
        String name = crashFile.getFileName().toString();
        if (sentReports.contains(name) || !inFlight.add(name)) {
            return;
        }
        LogHelper.info("New crash detected: %s", name);
        scheduler.schedule(() -> awaitStableAndSend(crashFile, -1, 1), STABILITY_CHECK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // Чекаємо, поки файл перестане рости (гра дописує репорт), потім надсилаємо
    private static void awaitStableAndSend(Path crashFile, long lastSize, int attempt) {
        String name = crashFile.getFileName().toString();
        try {
            if (!Files.exists(crashFile)) {
                inFlight.remove(name);
                return;
            }
            long size = Files.size(crashFile);
            if (size == 0 || size != lastSize) {
                scheduler.schedule(() -> awaitStableAndSend(crashFile, size, attempt), STABILITY_CHECK_DELAY_MS, TimeUnit.MILLISECONDS);
                return;
            }
        } catch (IOException e) {
            LogHelper.error("Failed to check crash report %s: %s", name, e.getMessage());
            inFlight.remove(name);
            return;
        }
        trySend(crashFile, attempt);
    }

    private static void trySend(Path crashFile, int attempt) {
        String name = crashFile.getFileName().toString();
        try {
            sendCrashReport(crashFile);
            inFlight.remove(name);
        } catch (Exception e) {
            LogHelper.error("Failed to send crash report %s (attempt %d/%d): %s", name, attempt, MAX_SEND_ATTEMPTS, e.getMessage());
            if (attempt < MAX_SEND_ATTEMPTS && initialized) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                scheduler.schedule(() -> trySend(crashFile, attempt + 1), delay, TimeUnit.MILLISECONDS);
            } else {
                inFlight.remove(name);
            }
        }
    }

    private static void sendCrashReport(Path crashFile) throws Exception {
        if (!Request.isAvailable()) {
            throw new IOException("Request service not available");
        }

        String filename = crashFile.getFileName().toString();
        String content = readExcerpt(crashFile);
        String gameVersion = extractGameVersion(content);
        String forgeVersion = extractForgeVersion(content);

        CrashReportRequest request = new CrashReportRequest(filename, content, gameVersion, forgeVersion);
        CrashReportRequestEvent event = request.request();

        if (!event.success) {
            throw new IOException("Server rejected crash report: " + event.message);
        }

        LogHelper.info("Crash report sent successfully: %s", filename);
        markSent(filename);
        if (event.savedPath != null) {
            LogHelper.info("Saved to: %s", event.savedPath);
        }
    }

    private static void markSent(String filename) {
        sentReports.add(filename);
        synchronized (sentLogLock) {
            try {
                Files.writeString(sentReportsLogFile, filename + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LogHelper.error("Failed to update sent crash reports log: %s", e.getMessage());
            }
        }
    }

    // Малі файли — цілком; великі — початок (заголовок + stack trace) і кінець (System Details)
    private static String readExcerpt(Path crashFile) throws IOException {
        long size = Files.size(crashFile);
        if (size <= MAX_FULL_SIZE) {
            return new String(Files.readAllBytes(crashFile), StandardCharsets.UTF_8);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(crashFile, StandardOpenOption.READ)) {
            String head = readChunk(channel, 0, HEAD_SIZE);
            String tail = readChunk(channel, size - TAIL_SIZE, TAIL_SIZE);
            return head
                    + "\n\n... [" + (size - HEAD_SIZE - TAIL_SIZE) + " bytes truncated by launcher] ...\n\n"
                    + tail;
        }
    }

    private static String readChunk(SeekableByteChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.position(position);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // читаємо до заповнення буфера або кінця файлу
        }
        buffer.flip();
        return new String(buffer.array(), 0, buffer.limit(), StandardCharsets.UTF_8);
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
                String content = readExcerpt(crashFile);
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

        try (Stream<Path> stream = Files.list(crashReportsDir)) {
            return stream
                    .filter(path -> isCrashReportName(path.getFileName().toString()))
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
