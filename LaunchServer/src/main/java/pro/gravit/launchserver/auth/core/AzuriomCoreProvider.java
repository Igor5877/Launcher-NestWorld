package pro.gravit.launchserver.auth.core;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.exception.AuthException;
import pro.gravit.launcher.base.events.request.GetAvailabilityAuthRequestEvent;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launcher.base.request.auth.details.AuthPasswordDetails;
import pro.gravit.launcher.base.request.auth.details.AuthTotpDetails;
import pro.gravit.launcher.base.request.auth.password.Auth2FAPassword;
import pro.gravit.launcher.base.request.auth.password.AuthPlainPassword;
import pro.gravit.launcher.base.request.auth.password.AuthTOTPPassword;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.manangers.AuthManager;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launcher.base.ClientPermissions;
import pro.gravit.launchserver.socket.response.auth.AuthResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class AzuriomCoreProvider extends AuthCoreProvider {
    private transient final Logger logger = LogManager.getLogger();
    public String azuriomUrl;
    private transient AuthClient authClient;

    @Override
    public void init(LaunchServer server, AuthProviderPair pair) {
        super.init(server, pair);
        if (azuriomUrl == null || azuriomUrl.isEmpty()) {
            logger.error("azuriomUrl is not configured for auth provider '{}'", pair.name);
            return;
        }
        this.authClient = new AuthClient(azuriomUrl);
    }

    @Override
    public User getUserByUsername(String username) {
        throw new UnsupportedOperationException("Azuriom provider does not support fetching user by username directly");
    }

    @Override
    public User getUserByUUID(UUID uuid) {
        throw new UnsupportedOperationException("Azuriom provider does not support fetching user by UUID directly");
    }

    @Override
    public User getUserByLogin(String login) {
        throw new UnsupportedOperationException("Azuriom provider does not support fetching user by login directly");
    }

    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (authClient == null) {
            // Provider not configured, cannot verify
            return null;
        }
        try {
            com.azuriom.azauth.model.User azuriomUser = authClient.verify(accessToken);
            AzuriomUser user = new AzuriomUser(azuriomUser);
            return new AzuriomUserSession(user, azuriomUser.getAccessToken());
        } catch (AuthException e) {
            // The azauth library throws a generic AuthException for various issues (e.g., invalid token, network error).
            // We'll check if the message suggests an invalid/expired token. This is a best-effort guess.
            // A more robust solution would require the azauth library to use more specific exception types.
            String message = e.getMessage();
            if (message != null && (message.contains("Invalid token") || message.contains("expired"))) {
                throw new OAuthAccessTokenExpired();
            }
            // For any other error during verification, we treat it as a failure and return null.
            logger.warn("Azuriom token verification failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public AuthManager.AuthReport authorize(String login, AuthResponse.AuthContext context, AuthRequest.AuthPasswordInterface password, boolean minecraftAccess) throws IOException, pro.gravit.launchserver.auth.AuthException {
        if (authClient == null) {
            throw new pro.gravit.launchserver.auth.AuthException("Azuriom provider is not configured");
        }

        String plainPassword;
        String totpCode = null;

        if (password instanceof Auth2FAPassword auth2fa) {
            if (!(auth2fa.firstPassword instanceof AuthPlainPassword)) {
                throw new pro.gravit.launchserver.auth.AuthException("Unsupported password type for 2FA");
            }
            plainPassword = ((AuthPlainPassword) auth2fa.firstPassword).password;
            if (auth2fa.secondPassword instanceof AuthTOTPPassword totp) {
                totpCode = totp.totp;
            }
        } else if (password instanceof AuthPlainPassword authPlain) {
            plainPassword = authPlain.password;
        } else {
            throw new pro.gravit.launchserver.auth.AuthException("Unsupported password type");
        }

        try {
            // First, try to login without 2FA code.
            com.azuriom.azauth.AuthResult<com.azuriom.azauth.model.User> result = authClient.login(login, plainPassword);

            // If 2FA is required
            if (result.isPending() && result.asPending().require2fa()) {
                if (totpCode == null) {
                    // 2FA is required, but client did not provide a code.
                    throw pro.gravit.launchserver.auth.AuthException.need2FA();
                }
                // 2FA is required and client provided a code, try again with the code.
                result = authClient.login(login, plainPassword, totpCode);
            }

            if (!result.isSuccess()) {
                 // Handle other errors like wrong password
                throw new pro.gravit.launchserver.auth.AuthException("Authentication failed: " + result.toString());
            }

            com.azuriom.azauth.model.User azuriomUser = result.getSuccessResult();
            AzuriomUser user = new AzuriomUser(azuriomUser);
            String accessToken = azuriomUser.getAccessToken();
            AzuriomUserSession session = new AzuriomUserSession(user, accessToken);
            // We use the same token for both because Azuriom doesn't distinguish them
            // and the system requires an OAuth token to be present.
            return AuthManager.AuthReport.ofOAuthWithMinecraft(accessToken, accessToken, null, 0, session);

        } catch (AuthException e) {
            throw new pro.gravit.launchserver.auth.AuthException(e.getMessage(), e);
        }
    }

    @Override
    public AuthManager.AuthReport refreshAccessToken(String refreshToken, AuthResponse.AuthContext context) {
        throw new UnsupportedOperationException("Azuriom provider does not support refreshing tokens");
    }

    @Override
    public User checkServer(Client client, String username, String serverID) throws IOException {
        throw new UnsupportedOperationException("Azuriom provider does not support checkServer");
    }
    
    @Override
    public List<GetAvailabilityAuthRequestEvent.AuthAvailabilityDetails> getDetails(Client client) {
        return List.of(new AuthPasswordDetails(), new AuthTotpDetails("SHA1"));
    }

    @Override
    public boolean joinServer(Client client, String username, UUID uuid, String accessToken, String serverID) throws IOException {
        throw new UnsupportedOperationException("Azuriom provider does not support joinServer");
    }

    @Override
    public void close() {
        // No-op
    }

    public static class AzuriomUser implements User {
        private final com.azuriom.azauth.model.User azuriomUser;
        private final ClientPermissions permissions;

        public AzuriomUser(com.azuriom.azauth.model.User azuriomUser) {
            this.azuriomUser = azuriomUser;
            this.permissions = new ClientPermissions();
            // In a real scenario, you might want to map roles/permissions from Azuriom
            // For now, we'll leave it empty.
        }

        @Override
        public String getUsername() {
            return azuriomUser.getUsername();
        }

        @Override
        public UUID getUUID() {
            return azuriomUser.getUuid();
        }

        @Override
        public ClientPermissions getPermissions() {
            return permissions;
        }
    }

    public static class AzuriomUserSession implements UserSession {
        private final AzuriomUser user;
        private final String accessToken;

        public AzuriomUserSession(AzuriomUser user, String accessToken) {
            this.user = user;
            this.accessToken = accessToken;
        }

        @Override
        public String getID() {
            return user.getUUID().toString();
        }

        @Override
        public User getUser() {
            return user;
        }

        @Override
        public String getMinecraftAccessToken() {
            return accessToken;
        }

        @Override
        public long getExpireIn() {
            return 0; // Azuriom tokens don't have a client-side expiration
        }
    }
}