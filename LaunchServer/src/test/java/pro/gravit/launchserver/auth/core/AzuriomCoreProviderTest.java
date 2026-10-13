package pro.gravit.launchserver.auth.core;

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launcher.base.events.request.AuthRequestEvent;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.LaunchServerBuilder;
import pro.gravit.launchserver.auth.AuthException;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.config.LaunchServerConfig;
import pro.gravit.launchserver.config.LaunchServerRuntimeConfig;
import pro.gravit.launchserver.impl.TestLaunchServerConfigManager;
import pro.gravit.launchserver.manangers.AuthManager;
import pro.gravit.launchserver.manangers.CertificateManager;
import pro.gravit.launchserver.manangers.LaunchServerGsonManager;
import pro.gravit.launchserver.modules.impl.LaunchServerModulesManager;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launchserver.socket.response.auth.AuthResponse;
import pro.gravit.utils.command.StdCommandHandler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Security;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AzuriomCoreProvider without the sql section, against a fake Azuriom site.
 */
public class AzuriomCoreProviderTest {
    @TempDir
    static Path root;
    static LaunchServer launchServer;
    static HttpServer azuriom;
    static final AtomicInteger verifyHits = new AtomicInteger();
    static volatile String uuidJson = "\"011b1b61-7039-421b-9f4d-31fcf7a195ef\"";

    AzuriomCoreProvider provider;

    @BeforeAll
    static void start() throws Throwable {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        var modulesManager = new LaunchServerModulesManager(root.resolve("m"), root.resolve("c"), null);
        LaunchServerConfig config = LaunchServerConfig.getDefault(LaunchServer.LaunchServerEnv.TEST);
        Launcher.gsonManager = new LaunchServerGsonManager(modulesManager);
        Launcher.gsonManager.initGson();
        launchServer = new LaunchServerBuilder().setDir(root.resolve("d")).setEnv(LaunchServer.LaunchServerEnv.TEST)
                .setConfig(config).setRuntimeConfig(new LaunchServerRuntimeConfig()).setCertificateManager(new CertificateManager())
                .setLaunchServerConfigManager(new TestLaunchServerConfigManager()).setModulesManager(modulesManager)
                .setCommandHandler(new StdCommandHandler(false)).build();

        azuriom = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        azuriom.createContext("/api/auth/verify", ex -> {
            verifyHits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response;
            int status;
            if (body.contains("good-token")) {
                status = 200;
                response = ("{\"id\":1,\"username\":\"Steve\"," + (uuidJson == null ? "" : "\"uuid\":" + uuidJson + ",") + "\"email\":\"s@example.com\",\"email_verified\":true," +
                        "\"money\":0,\"banned\":false,\"created_at\":\"2026-01-01T00:00:00Z\",\"access_token\":\"good-token\"}").getBytes(StandardCharsets.UTF_8);
            } else {
                status = 401;
                response = "{\"status\":\"error\",\"message\":\"Invalid token\",\"reason\":\"invalid_token\"}".getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, response.length);
            ex.getResponseBody().write(response);
            ex.close();
        });
        azuriom.start();
    }

    @AfterAll
    static void stop() throws Exception {
        azuriom.stop(0);
        launchServer.close();
    }

    @BeforeEach
    void setUp() {
        verifyHits.set(0);
        uuidJson = "\"011b1b61-7039-421b-9f4d-31fcf7a195ef\"";
        newProvider();
    }

    private void newProvider() {
        provider = new AzuriomCoreProvider();
        provider.azuriomUrl = "http://127.0.0.1:" + azuriom.getAddress().getPort();
        provider.init(launchServer, new AuthProviderPair(provider, null));
    }

    private AuthResponse.AuthContext context() {
        return new AuthResponse.AuthContext(new Client(), null, null, "127.0.0.1", AuthResponse.ConnectTypes.CLIENT, null);
    }

    @Test
    void checkingTheSameTokenAgainDoesNotAskAzuriomAgain() throws Exception {
        for (int i = 0; i < 5; i++) {
            AuthManager.AuthReport report = provider.reportFromOAuth("good-token", context());
            Assertions.assertEquals("Steve", report.session().getUser().getUsername());
        }
        Assertions.assertEquals(1, verifyHits.get());
    }

    @Test
    void rejectedTokenIsRememberedBrieflyAndReportedAsInvalid() {
        for (int i = 0; i < 3; i++) {
            AuthException e = Assertions.assertThrows(AuthException.class, () -> provider.reportFromOAuth("bad-token", context()));
            Assertions.assertEquals(AuthRequestEvent.OAUTH_TOKEN_INVALID, e.getMessage());
        }
        Assertions.assertEquals(1, verifyHits.get());
    }

    @Test
    void sessionWithoutDatabaseGetsNoFakeRefreshToken() throws Exception {
        AuthManager.AuthReport report = provider.reportFromOAuth("good-token", context());
        Assertions.assertNull(report.oauthRefreshToken(), "refresh cannot be verified without a database, so none is issued");
        Assertions.assertEquals("good-token", report.oauthAccessToken());
        Assertions.assertNull(provider.refreshAccessToken("Steve.anything", context()));
    }

    @Test
    void userWithNullGameIdIsRejectedCleanly() {
        uuidJson = "null"; // AzAuth cannot parse this and throws a runtime exception
        newProvider();
        AuthException e = Assertions.assertThrows(AuthException.class, () -> provider.reportFromOAuth("good-token", context()));
        Assertions.assertEquals(AuthRequestEvent.OAUTH_TOKEN_INVALID, e.getMessage());
    }

    @Test
    void userWithoutGameIdGetsTheOfflineUuid() throws Exception {
        uuidJson = null; // the field is missing in the answer
        newProvider();
        UserSession session = provider.getUserSessionByOAuthAccessToken("good-token");
        UUID expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals(expected, session.getUser().getUUID());
        Assertions.assertNotNull(session.getID());
    }

    @Test
    void unknownTokenInSessionLookupIsReported() {
        Assertions.assertThrows(AuthCoreProvider.OAuthAccessTokenExpired.class, () -> provider.getUserSessionByOAuthAccessToken("bad-token"));
    }
}
