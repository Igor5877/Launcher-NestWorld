package pro.gravit.launchserver.binary;

import pro.gravit.launchserver.LaunchServer;
import pro.gravit.utils.helper.LogHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LinuxLauncherBinary extends LauncherBinary {
    public static final String LINUX_PRESTARTER_FILENAME = "LinuxPrestarter";
    public static final String LINUX_PRESTARTER_DIR = "LinuxPrestarter";


    public LinuxLauncherBinary(LaunchServer server) {
        super(server, server.dir.resolve(LINUX_PRESTARTER_FILENAME), "LinuxPrestarter");
    }

    @Override
    public void build() throws IOException {
        LogHelper.info("Building LinuxPrestarter binary...");
        Path sourceDir = server.dir.getParent().getParent().getParent().resolve(LINUX_PRESTARTER_DIR);
        Path targetFile = server.updatesDir.resolve(LINUX_PRESTARTER_FILENAME);

        String projectName = server.config.projectName;

        String javaAmd64Url = server.config.netty.javaDownloadURLAmd64;
        String javaArm64Url = server.config.netty.javaDownloadURLArm64;
        String javafxAmd64Url = server.config.netty.javafxDownloadURLAmd64;
        String javafxArm64Url = server.config.netty.javafxDownloadURLArm64;

        if (javaAmd64Url == null || javaAmd64Url.isEmpty()) {
            LogHelper.warning("javaDownloadURLAmd64 is not set in NettyConfig. Build will likely fail for users.");
            javaAmd64Url = "https://example.com/java-amd64.tar.gz"; // Placeholder
        }
        if (javafxAmd64Url == null || javafxAmd64Url.isEmpty()) {
            LogHelper.warning("javafxDownloadURLAmd64 is not set in NettyConfig. Build will likely fail for users.");
            javafxAmd64Url = "https://example.com/javafx-amd64.zip"; // Placeholder
        }

        if (javaArm64Url == null) {
            javaArm64Url = "";
        }
        if (javafxArm64Url == null) {
            javafxArm64Url = "";
        }

        String launcherUrl = "http://localhost:9274/Launcher.jar"; // Default value
        if (server.config.netty.binds != null && server.config.netty.binds.length > 0) {
            launcherUrl = String.format("http://%s:%d/Launcher.jar", server.config.netty.binds[0].address, server.config.netty.binds[0].port);
        }


        List<String> command = new ArrayList<>();
        command.add("go");
        command.add("build");
        command.add("-o");
        command.add(targetFile.toAbsolutePath().toString());
        command.add("-ldflags");

        String ldFlags = String.format(
            "-X main.ProjectName='%s' -X main.JavaDownloadURLAmd64='%s' -X main.JavaDownloadURLArm64='%s' " +
            "-X main.JavaFXDownloadURLAmd64='%s' -X main.JavaFXDownloadURLArm64='%s' -X main.LauncherDownloadURL='%s'",
            projectName, javaAmd64Url, javaArm64Url, javafxAmd64Url, javafxArm64Url, launcherUrl
        );
        command.add(ldFlags);
        command.add(".");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(sourceDir.toFile());

        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LogHelper.subInfo(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                LogHelper.info("LinuxPrestarter binary successfully built and placed in updates dir.");
            } else {
                throw new IOException("Go build process failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Go build process was interrupted", e);
        }
    }
}
