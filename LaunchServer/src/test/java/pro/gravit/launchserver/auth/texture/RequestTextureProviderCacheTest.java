package pro.gravit.launchserver.auth.texture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reproduces "skin disappears after a while": the skin API (nginx limit_req) answers 503 once it is asked too often.
 */
public class RequestTextureProviderCacheTest {
    private static final UUID PLAYER = UUID.fromString("011b1b61-7039-421b-9f4d-31fcf7a195ef");

    private HttpServer server;
    private final AtomicInteger skinHits = new AtomicInteger();
    private final AtomicInteger capeHits = new AtomicInteger();
    private volatile int skinStatus = 200;
    private volatile int skinSize = 64;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private RequestTextureProvider provider;

    @BeforeEach
    public void start() throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skin-api/skins/", ex -> {
            skinHits.incrementAndGet();
            if (skinStatus == 200) {
                send(ex, 200, "image/png", png(skinSize));
            } else if (skinStatus == 404) {
                ex.sendResponseHeaders(404, -1);
            } else {
                send(ex, skinStatus, "text/html", "<html>busy</html>".getBytes());
            }
            ex.close();
        });
        server.createContext("/api/skin-api/capes/", ex -> {
            capeHits.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/skin-api";
        provider = new RequestTextureProvider(base + "/skins/%username%", base + "/capes/%username%");
        provider.clock = now::get;
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private static byte[] png(int size) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
    }

    private Map<String, ?> assets() {
        return provider.getAssets(PLAYER, "Steve", "Profile");
    }

    private void advanceSeconds(long seconds) {
        now.addAndGet(seconds * 1000L);
    }

    @Test
    public void repeatedProfileBuildsDoNotHitTheApi() {
        for (int i = 0; i < 20; i++) {
            Assertions.assertTrue(assets().containsKey("SKIN"));
        }
        Assertions.assertEquals(1, skinHits.get());
        Assertions.assertEquals(1, capeHits.get());
    }

    @Test
    public void skinSurvivesApiThrottling() {
        Assertions.assertTrue(assets().containsKey("SKIN"));
        advanceSeconds(provider.cacheTtlSeconds + 1);
        skinStatus = 503; // nginx limit_req
        Assertions.assertTrue(assets().containsKey("SKIN"), "stale texture must be served while the API fails");
        Assertions.assertEquals(2, skinHits.get());
    }

    @Test
    public void failedApiIsNotHammered() {
        assets();
        advanceSeconds(provider.cacheTtlSeconds + 1);
        skinStatus = 503;
        for (int i = 0; i < 10; i++) {
            assets();
        }
        Assertions.assertEquals(2, skinHits.get(), "only one retry inside the back-off window");
        advanceSeconds(provider.failureBackoffSeconds + 1);
        skinStatus = 200;
        Assertions.assertTrue(assets().containsKey("SKIN"));
        Assertions.assertEquals(3, skinHits.get());
    }

    @Test
    public void staleTextureExpires() {
        assets();
        skinStatus = 503;
        advanceSeconds(provider.staleTtlSeconds + 1);
        Assertions.assertFalse(assets().containsKey("SKIN"));
    }

    @Test
    public void notFoundIsRememberedBrieflyAndPickedUpLater() {
        skinStatus = 404;
        Assertions.assertFalse(assets().containsKey("SKIN"));
        Assertions.assertFalse(assets().containsKey("SKIN"));
        Assertions.assertEquals(1, skinHits.get());
        skinStatus = 200; // the player uploaded a skin
        advanceSeconds(provider.notFoundTtlSeconds + 1);
        Assertions.assertTrue(assets().containsKey("SKIN"));
    }

    @Test
    public void realChangeIsPickedUpAfterTtl() {
        assets();
        advanceSeconds(provider.cacheTtlSeconds + 1);
        skinSize = 128;
        provider.getAssets(PLAYER, "Steve", "Profile");
        Assertions.assertEquals(2, skinHits.get());
    }

    @Test
    public void invalidImageKeepsPreviousSkin() {
        assets();
        advanceSeconds(provider.cacheTtlSeconds + 1);
        skinSize = 100; // not a legal skin size -> rejected as IOException
        Assertions.assertTrue(assets().containsKey("SKIN"));
    }

    @Test
    public void cacheDisabledKeepsOldBehaviour() {
        provider.cacheTtlSeconds = 0;
        assets();
        assets();
        Assertions.assertEquals(2, skinHits.get());
        skinStatus = 503;
        Assertions.assertFalse(assets().containsKey("SKIN"));
    }

    @Test
    public void concurrentBuildsShareOneRequest() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> assets().containsKey("SKIN")));
            }
            for (var f : futures) {
                Assertions.assertTrue(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Assertions.assertEquals(1, skinHits.get());
    }

    @Test
    public void cacheIsBounded() {
        provider.cacheMaxEntries = 10;
        for (int i = 0; i < 40; i++) {
            provider.getAssets(PLAYER, "player" + i, "Profile");
            advanceSeconds(1);
        }
        Assertions.assertTrue(skinHits.get() == 40);
        skinHits.set(0);
        provider.getAssets(PLAYER, "player0", "Profile"); // evicted long ago
        Assertions.assertEquals(1, skinHits.get());
    }
}
