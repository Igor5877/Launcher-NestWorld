package pro.gravit.launchserver.auth.core;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.exception.AuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.ClientPermissions;
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
import pro.gravit.launchserver.auth.core.interfaces.provider.AuthSupportExtendedCheckServer;
import pro.gravit.launchserver.auth.core.interfaces.provider.AuthSupportHardware;
import pro.gravit.launchserver.helper.LegacySessionHelper;
import pro.gravit.launchserver.manangers.AuthManager;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launchserver.socket.response.auth.AuthResponse;
import pro.gravit.utils.helper.SecurityHelper;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AzuriomCoreProvider extends AuthCoreProvider implements AuthSupportHardware, AuthSupportExtendedCheckServer {
    private transient final Logger logger = LogManager.getLogger();
    public String azuriomUrl;
    public MySQLCoreProvider sql;
    private transient AuthClient authClient;
    private transient boolean isDatabaseMode = false;

    // --- Внутрішні реалізації для офлайн-режиму ---
    private record OfflineUser(String username, UUID uuid, ClientPermissions permissions) implements User {
        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public UUID getUUID() {
            return uuid;
        }

        @Override
        public ClientPermissions getPermissions() {
            return permissions;
        }
    }

    private record OfflineUserSession(User user) implements UserSession {
        @Override
        public String getID() {
            return "offline-" + user.getUUID().toString();
        }

        @Override
        public User getUser() {
            return user;
        }

        @Override
        public String getMinecraftAccessToken() {
            return null;
        }

        @Override
        public long getExpireIn() {
            return 0;
        }
    }

    @Override
    public void init(LaunchServer server, AuthProviderPair pair) {
        super.init(server, pair);
        if (azuriomUrl == null || azuriomUrl.isEmpty()) {
            logger.error("azuriomUrl is not configured! Azuriom provider cannot work.");
            return;
        }
        this.authClient = new AuthClient(azuriomUrl);

        if (sql != null) {
            sql.init(server, pair);
            isDatabaseMode = true;
            logger.info("Azuriom provider: Database integration is ENABLED with HWID support.");
        } else {
            isDatabaseMode = false;
            logger.warn("Azuriom provider: 'sql' section is not configured. Working WITHOUT database integration.");
        }
    }

    @Override
    public User getUserByUsername(String username) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get user by username '{}'.", username);
            return null;
        }
        return sql.getUserByUsername(username);
    }

    @Override
    public User getUserByUUID(UUID uuid) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get user by UUID '{}'.", uuid);
            return null;
        }
        return sql.getUserByUUID(uuid);
    }

    @Override
    public User getUserByLogin(String login) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get user by login '{}'.", login);
            return null;
        }
        return sql.getUserByLogin(login);
    }

    private UserSession createOfflineSession(com.azuriom.azauth.model.User azuriomUser) {
        User user = new OfflineUser(azuriomUser.getUsername(), azuriomUser.getUuid(), new ClientPermissions());
        return new OfflineUserSession(user);
    }

    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        Jws<Claims> claims;
        try {
            JwtParser parser = Jwts.parser().verifyWith(server.keyAgreementManager.ecdsaPublicKey).build();
            claims = parser.parseSignedClaims(accessToken);
        } catch (Exception e) {
            throw new OAuthAccessTokenExpired(e.getMessage());
        }
        Claims body = claims.getBody();
        String username = body.get("sub", String.class);
        UUID uuid = UUID.fromString(body.get("uuid", String.class));
        if (username == null || uuid == null) {
            throw new OAuthAccessTokenExpired("Invalid access token");
        }

        if (!isDatabaseMode) {
            User user = new OfflineUser(username, uuid, new ClientPermissions());
            return new OfflineUserSession(user);
        }

        MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(uuid);

        if (localUser == null) {
            logger.warn("User '{}' (UUID: {}) authenticated via JWT but not found in local database.", username, uuid);
            return null;
        }

        try {
            checkHwidBan(localUser);
            if (localUser.azuriomAccessToken != null) {
                authClient.verify(localUser.azuriomAccessToken);
            }
        } catch (pro.gravit.launchserver.auth.AuthException | AuthException e) {
            logger.error("Local user check failed during token verification", e);
            throw new OAuthAccessTokenExpired();
        }

        return sql.createSession(localUser);
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

            com.azuriom.azauth.model.User azuriomUser = result.asSuccess().getResult();
            String azuriomAccessToken = result.asSuccess().getResult().getAccessToken();

            if (!isDatabaseMode) {
                UserSession session = createOfflineSession(azuriomUser);
                User user = session.getUser();
                var accessToken = LegacySessionHelper.makeAccessJwtTokenFromString(user, LocalDateTime.now(Clock.systemUTC()).plusSeconds(3600), server.keyAgreementManager.ecdsaPrivateKey);
                var refreshToken = user.getUsername().concat(".").concat(LegacySessionHelper.makeRefreshTokenFromPassword(user.getUsername(), "mockpassword", server.keyAgreementManager.legacySalt));

                if (minecraftAccess) {
                    String minecraftAccessToken = SecurityHelper.randomStringToken();
                    return AuthManager.AuthReport.ofOAuthWithMinecraft(minecraftAccessToken, accessToken, refreshToken, SECONDS.toMillis(3600), session);
                } else {
                    return AuthManager.AuthReport.ofOAuth(accessToken, refreshToken, SECONDS.toMillis(3600), session);
                }
            }

            MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(azuriomUser.getUuid());

            if (localUser == null) {
                logger.warn("User '{}' (UUID: {}) authenticated via Azuriom but not found in local database.",
                    azuriomUser.getUsername(), azuriomUser.getUuid());
                throw new pro.gravit.launchserver.auth.AuthException("User not found in local database");
            }
            localUser.azuriomAccessToken = azuriomAccessToken;

            enrichUserWithAzuriomData(localUser, azuriomUser);
            checkHwidBan(localUser);

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

    private void enrichUserWithAzuriomData(MySQLCoreProvider.MySQLUser localUser, com.azuriom.azauth.model.User azuriomUser) {
        if (localUser.getPermissions() != null && azuriomUser.getRole() != null && azuriomUser.getRole().getName() != null) {
            String azuriomRole = azuriomUser.getRole().getName();
            if (!localUser.getPermissions().hasRole(azuriomRole)) {
                localUser.getPermissions().addRole(azuriomRole);
            }
        }
    }

    private void checkHwidBan(MySQLCoreProvider.MySQLUser localUser) throws pro.gravit.launchserver.auth.AuthException {
        if (isDatabaseMode && localUser.hwidId > 0) {
            UserHardware hardware = sql.getHardwareInfoById(String.valueOf(localUser.hwidId));
            if (hardware != null && hardware.isBanned()) {
                throw new pro.gravit.launchserver.auth.AuthException("Your hardware is banned");
            }
        }
    }

    @Override
    public AuthManager.AuthReport refreshAccessToken(String refreshToken, AuthResponse.AuthContext context) {
        if (!isDatabaseMode) {
            return null;
        }
        return sql.refreshAccessToken(refreshToken, context);
    }

    @Override
    public User checkServer(Client client, String username, String serverID) throws IOException {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot check server for user '{}'.", username);
            return null;
        }
        return sql.checkServer(client, username, serverID);
    }

    @Override
    public UserSession extendedCheckServer(Client client, String username, String serverID) throws IOException {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot extended check server for user '{}'.", username);
            return null;
        }
        MySQLCoreProvider.MySQLUser user = (MySQLCoreProvider.MySQLUser) sql.getUserByUsername(username);
        if (user == null) {
            return null;
        }
        if (user.getUsername().equals(username) && user.getServerId().equals(serverID)) {
            return sql.createSession(user);
        }
        return null;
    }

    @Override
    public List<GetAvailabilityAuthRequestEvent.AuthAvailabilityDetails> getDetails(Client client) {
        return List.of(new AuthPasswordDetails(), new AuthTotpDetails("SHA1"));
    }

    @Override
    public boolean joinServer(Client client, String username, UUID uuid, String accessToken, String serverID) throws IOException {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot join server for user '{}'.", username);
            return false;
        }
        return sql.joinServer(client, username, uuid, accessToken, serverID);
    }

    // ===== HWID Support Methods =====

    @Override
    public UserHardware getHardwareInfoByPublicKey(byte[] publicKey) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get hardware by public key.");
            return null;
        }
        return sql.getHardwareInfoByPublicKey(publicKey);
    }

    @Override
    public UserHardware getHardwareInfoByData(pro.gravit.launcher.base.request.secure.HardwareReportRequest.HardwareInfo info) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get hardware by data.");
            return null;
        }
        return sql.getHardwareInfoByData(info);
    }

    @Override
    public UserHardware getHardwareInfoById(String id) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get hardware by id.");
            return null;
        }
        return sql.getHardwareInfoById(id);
    }

    @Override
    public UserHardware createHardwareInfo(pro.gravit.launcher.base.request.secure.HardwareReportRequest.HardwareInfo hardwareInfo, byte[] publicKey) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot create hardware info.");
            return null;
        }
        return sql.createHardwareInfo(hardwareInfo, publicKey);
    }

    @Override
    public void connectUserAndHardware(UserSession userSession, UserHardware hardware) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot connect user and hardware.");
            return;
        }
        sql.connectUserAndHardware(userSession, hardware);
    }

    @Override
    public void addPublicKeyToHardwareInfo(UserHardware hardware, byte[] publicKey) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot add public key to hardware.");
            return;
        }
        sql.addPublicKeyToHardwareInfo(hardware, publicKey);
    }

    @Override
    public Iterable<User> getUsersByHardwareInfo(UserHardware hardware) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot get users by hardware.");
            return null;
        }
        return sql.getUsersByHardwareInfo(hardware);
    }

    @Override
    public void banHardware(UserHardware hardware) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot ban hardware.");
            return;
        }
        sql.banHardware(hardware);
    }

    @Override
    public void unbanHardware(UserHardware hardware) {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot unban hardware.");
            return;
        }
        sql.unbanHardware(hardware);
    }

    @Override
    public void close() {
        if (isDatabaseMode && sql != null) {
            sql.close();
        }
    }
}
