package pro.gravit.launchserver.components;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class CrashReportComponentTest {
    @TempDir
    Path dir;
    CrashReportComponent component;
    Path storage;

    @BeforeEach
    public void setUp() {
        component = new CrashReportComponent();
        component.cleanupOldReports = false; // no scheduler needed
        component.storagePath = dir.resolve("crash").toString();
        component.init(null);
        storage = dir.resolve("crash").toAbsolutePath().normalize();
    }

    @Test
    public void namesCannotEscapeTheStorage() throws Exception {
        for (String evil : new String[]{"..", "../..", "../../etc", "a/b", "a\\b", "/etc/passwd", "...", ".hidden", "", null}) {
            Path userDir = component.getUserCrashDir(evil, evil);
            Assertions.assertTrue(userDir.startsWith(storage), "escaped with " + evil + " -> " + userDir);
            Assertions.assertNotEquals(storage, userDir);
        }
    }

    @Test
    public void rateLimitIsAtomicUnderConcurrency() throws Exception {
        component.rateLimitPerHour = 5;
        AtomicInteger accepted = new AtomicInteger();
        Thread[] threads = new Thread[32];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                if (component.tryAcquireReportSlot("Steve")) accepted.incrementAndGet();
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        Assertions.assertEquals(5, accepted.get());
        Assertions.assertTrue(component.tryAcquireReportSlot("Alex"), "another user is not affected");
    }

    @Test
    public void userQuotaIsEnforced() throws Exception {
        component.maxReportsPerUser = 2;
        Path userDir = component.getUserCrashDir("Sky", "Steve");
        Assertions.assertFalse(component.isUserQuotaExceeded(userDir));
        Files.writeString(userDir.resolve("crash-1.txt"), "x");
        Files.writeString(userDir.resolve("crash-2.txt"), "x");
        Assertions.assertTrue(component.isUserQuotaExceeded(userDir));
    }

    @Test
    public void reportValidation() {
        Assertions.assertFalse(component.isValidCrashReport(null));
        Assertions.assertFalse(component.isValidCrashReport("   "));
        Assertions.assertTrue(component.isValidCrashReport("---- Minecraft Crash Report ----"));
    }
}
