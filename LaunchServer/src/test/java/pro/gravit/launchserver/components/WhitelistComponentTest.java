package pro.gravit.launchserver.components;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launchserver.socket.response.auth.AuthResponse;
import pro.gravit.utils.HookException;

/**
 * OAuth authorization carries no login, so a whitelist keyed by login must not be skipped for it
 * (previously the whole preHook was skipped for OAuth, which let anyone with a token in).
 */
public class WhitelistComponentTest {
    private static AuthResponse.AuthContext context(Client client, String login) {
        return new AuthResponse.AuthContext(client, login, null, "127.0.0.1", AuthResponse.ConnectTypes.CLIENT, null);
    }

    private static WhitelistComponent whitelist(String... names) {
        WhitelistComponent c = new WhitelistComponent();
        c.enabled = true;
        c.whitelist.addAll(java.util.List.of(names));
        return c;
    }

    @Test
    public void passwordLoginIsCheckedBeforeAuth() {
        var c = whitelist("steve");
        Client client = new Client();
        Assertions.assertThrows(HookException.class, () -> c.hookAuth(context(client, "alex"), client));
        Assertions.assertDoesNotThrow(() -> c.hookAuth(context(client, "steve"), client));
    }

    @Test
    public void oauthHasNoLoginBeforeAuthSoTheUserIsCheckedAfterIt() {
        var c = whitelist("Steve");
        Client client = new Client();
        Assertions.assertDoesNotThrow(() -> c.hookAuth(context(client, null), client));

        client.username = "Alex";
        Assertions.assertThrows(HookException.class, () -> c.hookPostAuth(context(client, null), client));
        client.username = "Steve";
        Assertions.assertDoesNotThrow(() -> c.hookPostAuth(context(client, null), client));
    }

    @Test
    public void disabledWhitelistLetsEveryoneIn() {
        var c = whitelist("Steve");
        c.enabled = false;
        Client client = new Client();
        client.username = "Alex";
        Assertions.assertDoesNotThrow(() -> c.hookAuth(context(client, "alex"), client));
        Assertions.assertDoesNotThrow(() -> c.hookPostAuth(context(client, null), client));
    }
}
