package pro.gravit.launchserver.components;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class CrashReportComponentTest {
    @TempDir
    Path dir;
    CrashReportComponent component;

    @BeforeEach
    public void setUp() throws Exception {
        component = new CrashReportComponent();
        component.useStorage(dir.resolve("crash"));
    }

    @Test
    public void namesCannotEscapeTheStorage() throws Exception {
        for (String evil : new String[]{"..", "../..", "../../etc", "a/b", "a\\b", "/etc/passwd", "...", ".hidden", "", null}) {
            Path userDir = component.getUserCrashDir(evil, evil);
            Assertions.assertTrue(userDir.startsWith(component.getCrashDir()), "escaped with " + evil + " -> " + userDir);
            Assertions.assertNotEquals(component.getCrashDir(), userDir);
        }
        Assertions.assertEquals("Steve_1", CrashReportComponent.sanitize("Steve 1"));
        Assertions.assertEquals("a.b-c_d", CrashReportComponent.sanitize("a.b-c_d"));
    }

    @Test
    public void rateLimitIsAtomicUnderConcurrency() throws Exception {
        component.rateLimitPerHour = 5;
        AtomicInteger accepted = new AtomicInteger();
        Thread[] threads = new Thread[32];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                if (component.tryAcquire("Steve", null)) accepted.incrementAndGet();
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        Assertions.assertEquals(5, accepted.get());
        Assertions.assertTrue(component.tryAcquire("Alex", null), "another user is not affected");
    }

    @Test
    public void storeIsAtomicAndKeepsEveryReport() throws Exception {
        Path userDir = component.getUserCrashDir("Sky", "Steve");
        byte[] data = "---- Minecraft Crash Report ----".getBytes(StandardCharsets.UTF_8);
        Path a = component.store(userDir, "crash-2026-01-01_00.00.00.txt", data);
        Path b = component.store(userDir, "crash-2026-01-01_00.00.00.txt", data); // same second
        Assertions.assertNotEquals(a, b);
        try (Stream<Path> files = Files.list(userDir)) {
            Assertions.assertEquals(2, files.count(), "no temp files may be left behind");
        }
    }

    @Test
    public void quotasAreEnforced() throws Exception {
        component.maxReportsPerUser = 2;
        Path userDir = component.getUserCrashDir("Sky", "Steve");
        byte[] data = new byte[100];
        Assertions.assertNull(component.checkQuota(userDir, data.length));
        component.store(userDir, "crash-1.txt", data);
        component.store(userDir, "crash-2.txt", data);
        Assertions.assertEquals("Too many stored crash reports", component.checkQuota(userDir, data.length));

        component.maxReportsPerUser = 100;
        component.maxTotalBytes = 250;
        Assertions.assertEquals("Crash report storage is full", component.checkQuota(userDir, 100));
    }

    @Test
    public void cleanupRemovesOldReportsAndEmptyDirectories() throws Exception {
        Path userDir = component.getUserCrashDir("Sky", "Steve");
        Path old = component.store(userDir, "crash-old.txt", new byte[10]);
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 40L * 24 * 3600 * 1000));
        component.cleanupOldReports();
        Assertions.assertFalse(Files.exists(old));
        Assertions.assertFalse(Files.exists(userDir), "empty user directory must be removed");
        Assertions.assertTrue(Files.exists(component.getCrashDir()));
    }

    @Test
    public void reportValidation() {
        Assertions.assertFalse(component.isValidCrashReport(null));
        Assertions.assertFalse(component.isValidCrashReport("   "));
        Assertions.assertTrue(component.isValidCrashReport("---- Minecraft Crash Report ----"));
    }
}
