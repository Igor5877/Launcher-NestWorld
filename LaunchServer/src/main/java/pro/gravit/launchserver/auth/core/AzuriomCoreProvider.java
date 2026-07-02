package pro.gravit.launchserver.auth.core;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.exception.AuthException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.ClientPermissions;
import pro.gravit.launcher.base.events.request.GetAvailabilityAuthRequestEvent;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launcher.base.request.auth.details.AuthPasswordDetails;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AzuriomCoreProvider extends AuthCoreProvider implements AuthSupportHardware, AuthSupportExtendedCheckServer {
    private transient final Logger logger = LogManager.getLogger();
    public String azuriomUrl;
    public String azuriomTokenColumn = "access_token";
    public String actionLogsTable = "action_logs";
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
            // Azuriom stores game_id as a dashed UUID, but getUserByUUID() binds the
            // parameter without dashes — match both formats unless a custom query is set.
            if (sql.customQueryByUUIDSQL == null) {
                sql.customQueryByUUIDSQL = "SELECT %s FROM %s WHERE REPLACE(%s, '-', '') = ? LIMIT 1"
                        .formatted(sql.makeUserCols(), sql.table, sql.uuidColumn);
            }
            sql.init(server, pair);
            // Prevent updateAuth from clearing serverId — the default SQL does
            // SET serverId=NULL which breaks extendedCheckServer during server switch.
            if (sql.customUpdateAuthSQL == null) {
                sql.updateAuthSQL = "UPDATE %s SET %s=? WHERE %s=?".formatted(
                        sql.table, sql.accessTokenColumn, sql.uuidColumn);
            }
            isDatabaseMode = true;
            if (sql.accessTokenColumn.equals(azuriomTokenColumn)) {
                logger.warn("CONFIGURATION WARNING: sql.accessTokenColumn ('{}') == azuriomTokenColumn. " +
                        "updateAuth() on Minecraft join will overwrite the Azuriom token, breaking re-auth. " +
                        "Add a separate column (e.g. 'mc_access_token') and set sql.accessTokenColumn to it.",
                        sql.accessTokenColumn);
            }
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

    // Verifies an Azuriom access_token against the shared database (stored as plaintext).
    // Returns the user UUID on success, throws OAuthAccessTokenExpired on failure.
    private UUID verifyTokenFromDatabase(String accessToken) throws OAuthAccessTokenExpired {
        if (!isDatabaseMode) {
            throw new OAuthAccessTokenExpired("Database mode is disabled, cannot verify token via SQL");
        }
        String query = "SELECT %s FROM %s WHERE %s = ?".formatted(sql.uuidColumn, sql.table, azuriomTokenColumn);
        try (Connection conn = sql.mySQLHolder.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, accessToken);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new OAuthAccessTokenExpired("Token not found");
                }
                return UUID.fromString(rs.getString(sql.uuidColumn));
            }
        } catch (SQLException e) {
            logger.error("SQL error during token verification", e);
            throw new OAuthAccessTokenExpired("Database error during token verification");
        }
    }

    // Mirrors what Azuriom's AuthController::verify() does (users.last_login_* + action_logs
    // entry 'users.auth.api.verified'), but via direct SQL so it works even when the site's
    // access_token was rotated or set to NULL. Best-effort: failures must not break auth.
    private void logSiteAutoLogin(UUID uuid, String ip) {
        if (!isDatabaseMode) return;
        String updateQuery = "UPDATE %s SET last_login_at = NOW(), last_login_ip = ? WHERE %s = ?"
                .formatted(sql.table, sql.uuidColumn);
        String insertQuery = ("INSERT INTO %s (user_id, action, target_id, data, created_at, updated_at) " +
                "SELECT id, 'users.auth.api.verified', NULL, ?, NOW(), NOW() FROM %s WHERE %s = ?")
                .formatted(actionLogsTable, sql.table, sql.uuidColumn);
        try (Connection conn = sql.mySQLHolder.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
                stmt.setString(1, ip);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                stmt.setString(1, "{\"ip\":\"" + (ip == null ? "" : ip.replace("\"", "")) + "\"}");
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warn("Failed to log Azuriom auto-login for user {}: {}", uuid, e.getMessage());
        }
    }

    // Resolves a UUID from either an Azuriom access_token (plaintext in DB, sent on first
    // login right after the client's authenticate() call) or a LaunchServer JWT (all
    // subsequent auto-logins and refreshes).
    private UUID resolveUuidFromAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (isJwtToken(accessToken)) {
            try {
                var info = LegacySessionHelper.getJwtInfoFromAccessToken(accessToken, server.keyAgreementManager.ecdsaPublicKey);
                return info.uuid();
            } catch (ExpiredJwtException e) {
                throw new OAuthAccessTokenExpired("JWT expired");
            } catch (JwtException e) {
                throw new OAuthAccessTokenExpired("Invalid JWT: " + e.getMessage());
            }
        }
        return verifyTokenFromDatabase(accessToken);
    }

    // JWT tokens from LaunchServer always start with the base64url-encoded header "eyJ" ({"alg":...}).
    private static boolean isJwtToken(String token) {
        return token != null && token.startsWith("eyJ");
    }

    @Override
    public AuthManager.AuthReport reportFromOAuth(String accessToken, AuthResponse.AuthContext context) throws IOException {
        if (!isDatabaseMode) {
            logger.warn("reportFromOAuth called but database mode is disabled, rejecting token.");
            throw new pro.gravit.launchserver.auth.AuthException(
                    pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_INVALID);
        }
        try {
            boolean isJwt = isJwtToken(accessToken);
            UUID userUuid = resolveUuidFromAccessToken(accessToken);

            boolean minecraftAccess = server.config.protectHandler.allowGetAccessToken(context);

            MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(userUuid);

            if (localUser == null) {
                logger.warn("User with UUID '{}' verified via token but not found in local database.", userUuid);
                throw new pro.gravit.launchserver.auth.AuthException(pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_INVALID);
            }

            checkHwidBan(localUser);

            // Always issue our own JWT: launcher sessions must not depend on Azuriom's
            // access_token, which the site rotates on every login and clears on logout.
            String oauthToken = LegacySessionHelper.makeAccessJwtTokenFromString(localUser,
                    LocalDateTime.now(Clock.systemUTC()).plusSeconds(sql.expireSeconds),
                    server.keyAgreementManager.ecdsaPrivateKey);

            // JWT in = auto-login (manual logins arrive with a fresh Azuriom token and are
            // already logged by the site's authenticate()). Record it in the admin panel.
            if (isJwt) {
                logSiteAutoLogin(userUuid, context != null ? context.ip : null);
            }

            UserSession session = sql.createSession(localUser);
            var refreshToken = localUser.getUsername().concat(".").concat(LegacySessionHelper.makeRefreshTokenFromPassword(localUser.getUsername(), localUser.password, server.keyAgreementManager.legacySalt));

            if (minecraftAccess) {
                String minecraftAccessToken = SecurityHelper.randomStringToken();
                sql.updateAuth(localUser, minecraftAccessToken);
                return AuthManager.AuthReport.ofOAuthWithMinecraft(minecraftAccessToken, oauthToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
            } else {
                return AuthManager.AuthReport.ofOAuth(oauthToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
            }

        } catch (OAuthAccessTokenExpired e) {
            // Expired JWT or rotated/cleared Azuriom token — both recoverable: the client
            // sends RefreshTokenRequest (validated against the password hash) and retries.
            throw new pro.gravit.launchserver.auth.AuthException(pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_EXPIRE);
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            throw e;
        }
    }

    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (!isDatabaseMode) {
            throw new OAuthAccessTokenExpired("Database mode is disabled");
        }
        UUID userUuid = resolveUuidFromAccessToken(accessToken);

        MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(userUuid);

        if (localUser == null) {
            logger.warn("User with UUID '{}' verified via token but not found in local database.", userUuid);
            throw new OAuthAccessTokenExpired("User not found");
        }

        try {
            checkHwidBan(localUser);
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            // Wrapping as OAuthAccessTokenExpired forces a refresh cycle, after which
            // reportFromOAuth() will reject the user with the proper ban message.
            throw new OAuthAccessTokenExpired("HWID_BAN: " + e.getMessage());
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

            com.azuriom.azauth.model.User azuriomUser = result.getSuccessResult();

            if (!isDatabaseMode) {
                UserSession session = createOfflineSession(azuriomUser);
                User user = session.getUser();
                var accessToken = azuriomUser.getAccessToken();
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

            enrichUserWithAzuriomData(localUser, azuriomUser);
            checkHwidBan(localUser);

            UserSession session = sql.createSession(localUser);
            // Our own JWT, not azuriomUser.getAccessToken(): the site token is rotated by
            // Azuriom on every authenticate() and must never be stored as a session credential.
            var accessToken = LegacySessionHelper.makeAccessJwtTokenFromString(localUser,
                    LocalDateTime.now(Clock.systemUTC()).plusSeconds(sql.expireSeconds),
                    server.keyAgreementManager.ecdsaPrivateKey);
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
        if (serverID == null) return null;
        MySQLCoreProvider.MySQLUser user = (MySQLCoreProvider.MySQLUser) sql.getUserByUsername(username);
        if (user == null) {
            return null;
        }
        if (user.getUsername().equals(username) && Objects.equals(serverID, user.getServerId())) {
            return sql.createSession(user);
        }
        return null;
    }

    @Override
    public List<GetAvailabilityAuthRequestEvent.AuthAvailabilityDetails> getDetails(Client client) {
        AuthPasswordDetails details = new AuthPasswordDetails();
        details.url = azuriomUrl;
        return List.of(details);
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
