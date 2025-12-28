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
import pro.gravit.launchserver.auth.core.interfaces.UserHardware;
import pro.gravit.launchserver.helper.LegacySessionHelper;
import pro.gravit.launchserver.manangers.AuthManager;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launchserver.socket.response.auth.AuthResponse;
import pro.gravit.utils.helper.SecurityHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AzuriomCoreProvider extends AuthCoreProvider {
    private transient final Logger logger = LogManager.getLogger();
    public String azuriomUrl;
    public SQLCoreProvider sql;
    private transient AuthClient authClient;

    @Override
    public void init(LaunchServer server, AuthProviderPair pair) {
        super.init(server, pair);
        if (azuriomUrl == null || azuriomUrl.isEmpty()) {
            logger.error("azuriomUrl is not configured");
            return;
        }
        if (sql == null) {
            logger.error("sql provider is not configured inside AzuriomCoreProvider");
            return;
        }
        this.authClient = new AuthClient(azuriomUrl);
        sql.init(server, pair);
    }

    @Override
    public User getUserByUsername(String username) {
        return sql.getUserByUsername(username);
    }

    @Override
    public User getUserByUUID(UUID uuid) {
        return sql.getUserByUUID(uuid);
    }

    @Override
    public User getUserByLogin(String login) {
        return sql.getUserByLogin(login);
    }

    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (authClient == null) {
            logger.error("Azuriom provider is not configured");
            return null;
        }
        try {
            // This method is only for verifying an existing token. If it fails, we don't try to refresh,
            // we just fail, which will force the user to log in with their password.
            com.azuriom.azauth.model.User azuriomUser = authClient.verify(accessToken);
            AbstractSQLCoreProvider.SQLUser localUser = (AbstractSQLCoreProvider.SQLUser) sql.getUserByUUID(azuriomUser.getUuid());

            if (localUser == null) {
                logger.warn("User '{}' (UUID: {}) authenticated via Azuriom but not found in local database.", azuriomUser.getUsername(), azuriomUser.getUuid());
                return null;
            }

            enrichUserWithAzuriomData(localUser, azuriomUser);
            checkHwidBan(localUser);

            return sql.createSession(localUser);
        } catch (AuthException e) {
            // Any exception during verification means the token is invalid.
            // We throw OAuthAccessTokenExpired to signal the client to clear the token and re-authenticate.
            throw new OAuthAccessTokenExpired();
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            // This could be a HWID ban or another local issue.
            logger.error("Local user check failed during token verification", e);
            throw new OAuthAccessTokenExpired(); // Also force re-authentication
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
            com.azuriom.azauth.AuthResult<com.azuriom.azauth.model.User> result = authClient.login(login, plainPassword);

            if (result.isPending() && result.asPending().require2fa()) {
                if (totpCode == null) {
                    throw pro.gravit.launchserver.auth.AuthException.need2FA();
                }
                result = authClient.login(login, plainPassword, totpCode);
            }

            if (!result.isSuccess()) {
                throw new pro.gravit.launchserver.auth.AuthException("Authentication failed: " + result.toString());
            }

            com.azuriom.azauth.model.User azuriomUser = result.getSuccessResult();
            AbstractSQLCoreProvider.SQLUser localUser = (AbstractSQLCoreProvider.SQLUser) sql.getUserByUUID(azuriomUser.getUuid());

            if (localUser == null) {
                logger.warn("User '{}' (UUID: {}) authenticated via Azuriom but not found in local database.", azuriomUser.getUsername(), azuriomUser.getUuid());
                throw new pro.gravit.launchserver.auth.AuthException("User not found in local database");
            }

            enrichUserWithAzuriomData(localUser, azuriomUser);
            checkHwidBan(localUser);

            // Manually create the AuthReport instead of calling sql.authorize()
            UserSession session = sql.createSession(localUser);
            var accessToken = LegacySessionHelper.makeAccessJwtTokenFromString(localUser, LocalDateTime.now(Clock.systemUTC()).plusSeconds(sql.expireSeconds), server.keyAgreementManager.ecdsaPrivateKey);
            var refreshToken = localUser.getUsername().concat(".").concat(LegacySessionHelper.makeRefreshTokenFromPassword(localUser.getUsername(), localUser.password, server.keyAgreementManager.legacySalt));
            
            if (minecraftAccess) {
                String minecraftAccessToken = SecurityHelper.randomStringToken();
                sql.updateAuth(localUser, minecraftAccessToken);
                return AuthManager.AuthReport.ofOAuthWithMinecraft(minecraftAccessToken, accessToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
            } else {
                return AuthManager.AuthReport.ofOAuth(accessToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
            }

        } catch (AuthException e) {
            throw new pro.gravit.launchserver.auth.AuthException(e.getMessage(), e);
        }
    }

    private void enrichUserWithAzuriomData(AbstractSQLCoreProvider.SQLUser localUser, com.azuriom.azauth.model.User azuriomUser) {
        // localUser.permissions already contains permissions from the local DB.
        // Now we add the role from Azuriom.
        if (localUser.getPermissions() != null && azuriomUser.getRole() != null && azuriomUser.getRole().getName() != null) {
            String azuriomRole = azuriomUser.getRole().getName();
            if (!localUser.getPermissions().hasRole(azuriomRole)) {
                localUser.getPermissions().addRole(azuriomRole);
            }
        }
    }

    private void checkHwidBan(AbstractSQLCoreProvider.SQLUser localUser) throws pro.gravit.launchserver.auth.AuthException {
        if (sql.hardwareIdColumn != null && localUser instanceof SQLCoreProvider.SQLUser && ((SQLCoreProvider.SQLUser) localUser).hwidId > 0) {
            UserHardware hardware = sql.getHardwareInfoById(String.valueOf(((SQLCoreProvider.SQLUser) localUser).hwidId));
            if (hardware != null && hardware.isBanned()) {
                throw new pro.gravit.launchserver.auth.AuthException("Your hardware is banned");
            }
        }
    }

    @Override
    public AuthManager.AuthReport refreshAccessToken(String refreshToken, AuthResponse.AuthContext context) {
        // Azuriom's azauth library does not support refresh tokens.
        // Returning null will force re-authentication via password.
        return null;
    }

    @Override
    public User checkServer(Client client, String username, String serverID) throws IOException {
        return sql.checkServer(client, username, serverID);
    }

    @Override
    public List<GetAvailabilityAuthRequestEvent.AuthAvailabilityDetails> getDetails(Client client) {
        return List.of(new AuthPasswordDetails(), new AuthTotpDetails("SHA1"));
    }

    @Override
    public boolean joinServer(Client client, String username, UUID uuid, String accessToken, String serverID) throws IOException {
        return sql.joinServer(client, username, uuid, accessToken, serverID);
    }

    @Override
    public void close() {
        if (sql != null) {
            sql.close();
        }
    }
}