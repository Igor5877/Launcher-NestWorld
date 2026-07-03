package pro.gravit.launchserver.socket.response;

import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;
import pro.gravit.launchserver.components.CrashReportComponent;
import pro.gravit.launchserver.socket.Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CrashReportResponse extends SimpleResponse {
    private static final Logger logger = LogManager.getLogger();
    private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    public String filename;
    public String content;
    public String gameVersion;
    public String forgeVersion;
    public String profileName;
    public long timestamp;

    @Override
    public String getType() {
        return "crashReport";
    }

    @Override
    public void execute(ChannelHandlerContext ctx, Client client) {
        // Знаходимо компонент crash reports
        CrashReportComponent crashComponent = getCrashReportComponent();
        if (crashComponent == null) {
            sendResult(new CrashReportRequestEvent(false, "Crash reports not enabled"));
            return;
        }

        // Валідуємо клієнта
        if (!crashComponent.validateClient(client)) {
            sendResult(new CrashReportRequestEvent(false, "Access denied"));
            return;
        }

        // Отримуємо ім'я користувача
        String username = client.username;
        if (username == null) {
            sendResult(new CrashReportRequestEvent(false, "Username is required"));
            return;
        }

        // Валідуємо розмір файлу (в байтах, як він буде записаний на диск)
        if (content == null) {
            sendResult(new CrashReportRequestEvent(false, "Content is required"));
            return;
        }
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > crashComponent.maxFileSize) {
            sendResult(new CrashReportRequestEvent(false,
                String.format("File size exceeds limit of %d bytes", crashComponent.maxFileSize)));
            return;
        }

        // Валідуємо контент
        if (!crashComponent.isValidCrashReport(content)) {
            sendResult(new CrashReportRequestEvent(false, "Invalid crash report format"));
            return;
        }

        // Перевіряємо rate limiting (атомарно займаємо слот)
        if (!crashComponent.tryAcquireReportSlot(username)) {
            sendResult(new CrashReportRequestEvent(false, "Rate limit exceeded"));
            return;
        }

        try {
            String clientName = resolveClientName(client);
            // Створюємо директорію користувача
            Path userDir = crashComponent.getUserCrashDir(clientName, username);

            // Перевіряємо загальну квоту користувача
            if (crashComponent.isUserQuotaExceeded(userDir)) {
                sendResult(new CrashReportRequestEvent(false, "Report limit exceeded"));
                return;
            }

            // Додаємо метаінформацію та зберігаємо файл (унікальне ім'я — без перезапису)
            byte[] enrichedBytes = enrichCrashReport(content, client).getBytes(StandardCharsets.UTF_8);
            Path filePath = writeUnique(userDir, generateFilename(filename), enrichedBytes);

            logger.info("Crash report saved for user '{}': {}", username, filePath.toAbsolutePath());

            sendResult(new CrashReportRequestEvent(true, "Crash report saved successfully",
                filePath.toAbsolutePath().toString()));

        } catch (IOException e) {
            logger.error("Failed to save crash report for user '{}'", username, e);
            sendResult(new CrashReportRequestEvent(false, "Failed to save crash report"));
        }
    }

    // Пріоритет: профіль сесії (авторитетний) → назва профілю від клієнта → "unknown"
    private String resolveClientName(Client client) {
        if (client.profile != null) {
            return client.profile.getTitle();
        }
        if (profileName != null && !profileName.isBlank()) {
            return profileName;
        }
        return "unknown";
    }

    private CrashReportComponent getCrashReportComponent() {
        if (server.config.components != null) {
            for (var component : server.config.components.values()) {
                if (component instanceof CrashReportComponent) {
                    return (CrashReportComponent) component;
                }
            }
        }
        return null;
    }

    private String generateFilename(String originalFilename) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(FILENAME_FORMATTER);

        // Якщо оригінальне ім'я файлу містить інформацію про FML, зберігаємо її
        if (originalFilename != null && originalFilename.contains("fml")) {
            return String.format("crash-%s-fml.txt", timestamp);
        }

        return String.format("crash-%s.txt", timestamp);
    }

    private static Path writeUnique(Path userDir, String baseFilename, byte[] data) throws IOException {
        Path filePath = userDir.resolve(baseFilename);
        for (int i = 1; ; i++) {
            try {
                Files.write(filePath, data, StandardOpenOption.CREATE_NEW);
                return filePath;
            } catch (FileAlreadyExistsException e) {
                if (i > 1000) throw e;
                filePath = userDir.resolve(baseFilename.replace(".txt", "-" + i + ".txt"));
            }
        }
    }

    private String enrichCrashReport(String originalContent, Client client) {
        StringBuilder enriched = new StringBuilder();

        // Додаємо метаінформацію
        enriched.append("// Crash report submitted via GravitLauncher\n");
        enriched.append("// Submitted by: ").append(client.username).append("\n");
        enriched.append("// Client IP: ").append(client.ipAddress).append("\n");
        enriched.append("// Submission time: ").append(LocalDateTime.now()).append("\n");
        enriched.append("// Launcher version: ").append(server.config.projectName).append("\n");
        if (filename != null) {
            enriched.append("// Original filename: ").append(filename).append("\n");
        }
        if (gameVersion != null) {
            enriched.append("// Game version: ").append(gameVersion).append("\n");
        }
        if (forgeVersion != null) {
            enriched.append("// Forge version: ").append(forgeVersion).append("\n");
        }
        if (timestamp > 0) {
            enriched.append("// Client-reported time: ").append(Instant.ofEpochMilli(timestamp)).append("\n");
        }
        enriched.append("\n");

        // Додаємо оригінальний контент
        enriched.append(originalContent);

        return enriched.toString();
    }
}
