package pro.gravit.launchserver.socket.response;

import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;
import pro.gravit.launchserver.components.CrashReportComponent;
import pro.gravit.launchserver.socket.Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CrashReportResponse extends SimpleResponse {
    private static final Logger logger = LogManager.getLogger();
    private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    
    public String filename;
    public String content;
    public String gameVersion;
    public String forgeVersion;
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
        
        // Валідуємо розмір файлу (у байтах, а не в символах)
        if (content == null) {
            sendResult(new CrashReportRequestEvent(false, "Invalid crash report format"));
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

        // Перевіряємо rate limiting (перевірка і облік одним атомарним кроком)
        if (!crashComponent.tryAcquire(username, client.ipAddress)) {
            sendResult(new CrashReportRequestEvent(false, "Rate limit exceeded"));
            return;
        }

        try {
            String clientName = client.profile == null ? "unknown" : client.profile.getTitle();
            // Створюємо директорію користувача
            Path userDir = crashComponent.getUserCrashDir(clientName, username);

            // Додаємо додаткову інформацію до звіту і зберігаємо одним записом
            byte[] data = enrichCrashReport(content, client).getBytes(StandardCharsets.UTF_8);
            String quotaError = crashComponent.checkQuota(userDir, data.length);
            if (quotaError != null) {
                sendResult(new CrashReportRequestEvent(false, quotaError));
                return;
            }
            Path filePath = crashComponent.store(userDir, generateFilename(filename), data);

            logger.info("Crash report saved for user '{}': {}", username, filePath);

            // Клієнту повертаємо лише відносний шлях, без структури каталогів сервера
            sendResult(new CrashReportRequestEvent(true, "Crash report saved successfully",
                crashComponent.getCrashDir().relativize(filePath).toString()));

        } catch (IOException e) {
            logger.error("Failed to save crash report for user '{}'", username, e);
            sendResult(new CrashReportRequestEvent(false, "Failed to save crash report"));
        }
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
    
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", " ");
    }

    private String enrichCrashReport(String originalContent, Client client) {
        StringBuilder enriched = new StringBuilder();
        
        // Додаємо метаінформацію
        enriched.append("// Crash report submitted via GravitLauncher\n");
        enriched.append("// Submitted by: ").append(oneLine(client.username)).append("\n");
        enriched.append("// Client IP: ").append(oneLine(client.ipAddress)).append("\n");
        enriched.append("// Submission time: ").append(LocalDateTime.now()).append("\n");
        enriched.append("// Launcher version: ").append(server.config.projectName).append("\n");
        enriched.append("\n");
        
        // Додаємо оригінальний контент
        enriched.append(originalContent);
        
        return enriched.toString();
    }
}