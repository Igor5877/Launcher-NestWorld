package pro.gravit.launcher.request;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.gravit.launcher.base.events.request.AuthRequestEvent;
import pro.gravit.launcher.base.events.request.RestoreRequestEvent;
import pro.gravit.launcher.base.events.request.SetProfileRequestEvent;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.ClientProfileBuilder;
import pro.gravit.launcher.base.request.Request;
import pro.gravit.launcher.base.request.RequestService;
import pro.gravit.launcher.base.request.WebSocketEvent;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launcher.base.request.auth.RestoreRequest;
import pro.gravit.launcher.base.request.auth.SetProfileRequest;
import pro.gravit.launcher.base.request.auth.password.AuthOAuthPassword;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * After the network changes the WebSocket is re-opened and the server sees a brand new connection: no authorization,
 * no launcher signature and no selected profile. Request.reconnect() has to bring all of it back, otherwise
 * the update stage fails with "Access denied".
 */
public class ReconnectRestoreTest {
    private final List<String> log = new ArrayList<>();
    private RestoreRequest lastRestore;
    private FakeService service;

    private class FakeService implements RequestService {
        @SuppressWarnings("unchecked")
        @Override
        public <T extends WebSocketEvent> CompletableFuture<T> request(Request<T> request) throws IOException {
            WebSocketEvent event;
            if (request instanceof AuthRequest auth) {
                Assertions.assertInstanceOf(AuthOAuthPassword.class, auth.password);
                log.add("auth");
                AuthRequestEvent e = new AuthRequestEvent();
                e.oauth = new AuthRequestEvent.OAuthRequestEvent("access", "refresh", 3_600_000);
                event = e;
            } else if (request instanceof RestoreRequest restore) {
                lastRestore = restore;
                log.add("restore" + (restore.accessToken == null ? "(tokens)" : "(access)"));
                event = new RestoreRequestEvent();
            } else if (request instanceof SetProfileRequest setProfile) {
                log.add("setProfile:" + setProfile.client);
                event = new SetProfileRequestEvent(null);
            } else {
                throw new IOException("unexpected " + request);
            }
            return CompletableFuture.completedFuture((T) event);
        }

        @Override
        public void open() {
            log.add("open");
        }

        @Override
        public void registerEventHandler(EventHandler handler) {
        }

        @Override
        public void unregisterEventHandler(EventHandler handler) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

    @BeforeEach
    public void setUp() {
        service = new FakeService();
        Request.setRequestService(service);
        Request.setOAuth("azuriom", new AuthRequestEvent.OAuthRequestEvent("access", "refresh", 3_600_000));
        Request.clearExtendedTokens();
        Request.addExtendedToken("launcher", new Request.ExtendedToken("launcher-jwt", 3_600_000));
        Request.rememberSelectedProfile(null);
    }

    @AfterEach
    public void tearDown() {
        Request.setRequestService(null);
        Request.setOAuth(null, null);
        Request.clearExtendedTokens();
        Request.rememberSelectedProfile(null);
    }

    @Test
    public void reconnectRestoresAuthSignatureAndProfile() throws Exception {
        Request.rememberSelectedProfile(new ClientProfileBuilder().setTitle("Sky").createClientProfile());

        Request.reconnect();

        Assertions.assertEquals(List.of("open", "auth", "restore(tokens)", "setProfile:Sky"), log);
        Assertions.assertEquals("launcher-jwt", lastRestore.extended.get("launcher"),
                "the launcher token must reach the new connection or checkSign stays false");
    }

    @Test
    public void reconnectWithoutProfileDoesNotSelectOne() throws Exception {
        Request.reconnect();
        Assertions.assertEquals(List.of("open", "auth", "restore(tokens)"), log);
    }

    @Test
    public void noExtendedTokensMeansNoRestoreRequest() throws Exception {
        Request.clearExtendedTokens();
        Request.restore(false, false, false);
        Assertions.assertEquals(List.of("auth"), log);
    }

    @Test
    public void backgroundRefreshOfValidTokenSendsNothing() throws Exception {
        Request.restore(false, true, false);
        Assertions.assertEquals(List.of(), log);
    }

    @Test
    public void selectedProfileIsRememberedFromServerAnswer() {
        ClientProfile profile = new ClientProfileBuilder().setTitle("Sky").createClientProfile();
        Request.rememberSelectedProfile(profile);
        Assertions.assertSame(profile, Request.getSelectedProfile());
    }
}
