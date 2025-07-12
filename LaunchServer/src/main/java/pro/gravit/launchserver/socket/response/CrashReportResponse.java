package pro.gravit.launchserver.socket.response;

import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;
import pro.gravit.launchserver.components.CrashReportComponent;
import pro.gravit.launchserver.socket.Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CrashReportResponse extends SimpleResponse {
    private static final Logger logger = LogManager.getLogger();
    private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    
    public String filename;
    public byte[] content;
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

        // Перевіряємо rate limiting
        if (!crashComponent.canUserSendReport(username)) {
            sendResult(new CrashReportRequestEvent(false, "Rate limit exceeded"));
            return;
        }

        // Валідуємо розмір файлу
        if (content == null || content.length > crashComponent.maxFileSize) {
            sendResult(new CrashReportRequestEvent(false,
                String.format("File size exceeds limit of %d bytes", crashComponent.maxFileSize)));
            return;
        }
        String contentString = new String(content, StandardCharsets.UTF_8);
        // Валідуємо контент
        if (!crashComponent.isValidCrashReport(contentString)) {
            sendResult(new CrashReportRequestEvent(false, "Invalid crash report format"));
            return;
        }

        try {
            // Створюємо директорію користувача
            Path userDir = crashComponent.getUserCrashDir(username);

            // Генеруємо ім'я файлу
            String generatedFilename = generateFilename(filename);
            Path filePath = userDir.resolve(generatedFilename);

            // Зберігаємо файл
            Files.write(filePath, content);

            // Записуємо лог
            crashComponent.recordUserReport(username);

            logger.info("Crash report saved for user '{}': {}", username, filePath.toAbsolutePath());

            // Додаємо додаткову інформацію до файлу
            String enrichedContent = enrichCrashReport(contentString, client);
            Files.write(filePath, enrichedContent.getBytes(StandardCharsets.UTF_8));

            sendResult(new CrashReportRequestEvent(true, "Crash report saved successfully",
                filePath.toAbsolutePath().toString()));

        } catch (IOException e) {
            logger.error("Failed to save crash report for user '{}'", username, e);
            sendResult(new CrashReportRequestEvent(false, "Failed to save crash report: " + e.getMessage()));
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
    
    private String enrichCrashReport(String originalContent, Client client) {
        StringBuilder enriched = new StringBuilder();
        
        // Додаємо метаінформацію
        enriched.append("// Crash report submitted via GravitLauncher\n");
        enriched.append("// Submitted by: ").append(client.username).append("\n");
        enriched.append("// Client IP: ").append(ip).append("\n");
        enriched.append("// Submission time: ").append(LocalDateTime.now()).append("\n");
        enriched.append("// Launcher version: ").append(server.config.projectName).append("\n");
        enriched.append("\n");
        
        // Додаємо оригінальний контент
        enriched.append(originalContent);
        
        return enriched.toString();
    }
}