package pro.gravit.launchserver.manangers;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.gravit.launcher.base.ClientPermissions;
import pro.gravit.launchserver.auth.AuthException;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.auth.core.RejectAuthCoreProvider;
import pro.gravit.launchserver.auth.core.User;
import pro.gravit.launchserver.socket.Client;

import java.util.UUID;

/**
 * A connection that is already authorized may be re-authorized (token refresh, session restore) only as the
 * same user: the "already logged in" guard was removed from AuthManager.check and must not leave a hole.
 */
public class ReauthTest {
    private static User user(String name, UUID uuid) {
        return new User() {
            @Override
            public String getUsername() {
                return name;
            }

            @Override
            public UUID getUUID() {
                return uuid;
            }

            @Override
            public ClientPermissions getPermissions() {
                return new ClientPermissions();
            }
        };
    }

    private static AuthProviderPair pair(String name) {
        AuthProviderPair pair = new AuthProviderPair(new RejectAuthCoreProvider(), null);
        pair.name = name;
        return pair;
    }

    private static Client authorized(String authId, User user) {
        Client client = new Client();
        client.isAuth = true;
        client.auth_id = authId;
        client.username = user.getUsername();
        client.uuid = user.getUUID();
        return client;
    }

    private static final UUID STEVE = UUID.fromString("011b1b61-7039-421b-9f4d-31fcf7a195ef");
    private static final UUID ALEX = UUID.fromString("5b7c3f8e-2a8c-3b3c-8b0b-0d7c0c7a0f11");

    @Test
    public void freshConnectionMayAuthorize() {
        Assertions.assertDoesNotThrow(() -> AuthManager.checkReauth(new Client(), pair("azuriom"), user("Steve", STEVE)));
    }

    @Test
    public void sameUserMayRefreshTheSession() {
        Client client = authorized("azuriom", user("Steve", STEVE));
        Assertions.assertDoesNotThrow(() -> AuthManager.checkReauth(client, pair("azuriom"), user("Steve", STEVE)));
    }

    @Test
    public void anotherUserIsRejected() {
        Client client = authorized("azuriom", user("Steve", STEVE));
        AuthException e = Assertions.assertThrows(AuthException.class,
                () -> AuthManager.checkReauth(client, pair("azuriom"), user("Alex", ALEX)));
        Assertions.assertEquals("You are already logged in", e.getMessage());
    }

    @Test
    public void anotherAuthProviderIsRejected() {
        Client client = authorized("azuriom", user("Steve", STEVE));
        Assertions.assertThrows(AuthException.class,
                () -> AuthManager.checkReauth(client, pair("std"), user("Steve", STEVE)));
    }
}
