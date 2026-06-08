package pro.gravit.launchserver.auth.core;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.exception.AuthException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.events.request.AuthRequestEvent;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AzuriomCoreProvider extends AuthCoreProvider implements AuthSupportHardware, AuthSupportExtendedCheckServer {
    private transient final Logger logger = LogManager.getLogger();

    public String azuriomUrl;
    public MySQLCoreProvider sql;

    // Column names for Azuriom-specific data (override in config if needed)
    public String banColumn = "is_banned";
    public String lastLoginAtColumn = "last_login_at";
    public String lastLoginIpColumn = "last_login_ip";
    public String gameTokenColumn = "game_access_token";

    private static final long REFRESH_TTL_MS = TimeUnit.DAYS.toMillis(14);

    private transient AuthClient authClient;
    private transient String sqlCheckBanned;
    private transient String sqlUpdateLastLogin;
    private transient String sqlReadGameToken;
    private transient String sqlUpdateGameToken;

    @Override
    public void init(LaunchServer server, AuthProviderPair pair) {
        super.init(server, pair);
        if (azuriomUrl == null || azuriomUrl.isEmpty()) {
            logger.error("azuriomUrl is not configured!");
            return;
        }
        if (sql == null) {
            logger.error("'sql' section is not configured! AzuriomCoreProvider requires a database.");
            return;
        }
        authClient = new AuthClient(azuriomUrl);
        sql.init(server, pair);

        String t = sql.table;
        String uuidCol = sql.uuidColumn;
        sqlCheckBanned     = "SELECT `%s` FROM `%s` WHERE `%s`=? LIMIT 1".formatted(banColumn, t, uuidCol);
        sqlUpdateLastLogin = "UPDATE `%s` SET `%s`=NOW(), `%s`=? WHERE `%s`=?".formatted(t, lastLoginAtColumn, lastLoginIpColumn, uuidCol);
        sqlReadGameToken   = "SELECT `%s` FROM `%s` WHERE `%s`=? LIMIT 1".formatted(gameTokenColumn, t, uuidCol);
        sqlUpdateGameToken = "UPDATE `%s` SET `%s`=? WHERE `%s`=?".formatted(t, gameTokenColumn, uuidCol);

        logger.info("AzuriomCoreProvider initialized (database mode, 2-week refresh tokens).");
    }

    // ── SQL helpers ──────────────────────────────────────────────────────────

    private String uuidParam(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** Throws AuthException if the account is banned in Azuriom's is_banned column. */
    private void checkIsBanned(UUID uuid) throws pro.gravit.launchserver.auth.AuthException {
        try (Connection c = sql.getSQLConfig().getConnection()) {
            PreparedStatement s = c.prepareStatement(sqlCheckBanned);
            s.setString(1, uuidParam(uuid));
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    throw new pro.gravit.launchserver.auth.AuthException("You are banned");
                }
            }
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            throw e;
        } catch (SQLException e) {
            logger.error("SQL error checking ban for {}", uuid, e);
        }
    }

    /** Throws AuthException if the HWID is banned. */
    private void checkHwidBan(MySQLCoreProvider.MySQLUser user) throws pro.gravit.launchserver.auth.AuthException {
        if (user.hwidId > 0) {
            UserHardware hw = sql.getHardwareInfoById(String.valueOf(user.hwidId));
            if (hw != null && hw.isBanned()) {
                throw new pro.gravit.launchserver.auth.AuthException("Your hardware is banned");
            }
        }
    }

    /**
     * Updates last_login_at (to NOW()) and last_login_ip in the users table so that
     * Azuriom's admin panel shows activity even during launcher auto-login.
     */
    private void updateLastLogin(UUID uuid, String ip) {
        try (Connection c = sql.getSQLConfig().getConnection()) {
            PreparedStatement s = c.prepareStatement(sqlUpdateLastLogin);
            s.setString(1, ip != null ? ip : "");
            s.setString(2, uuidParam(uuid));
            s.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to update last_login for {}: {}", uuid, e.getMessage());
        }
    }

    private String readGameToken(UUID uuid) {
        try (Connection c = sql.getSQLConfig().getConnection()) {
            PreparedStatement s = c.prepareStatement(sqlReadGameToken);
            s.setString(1, uuidParam(uuid));
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logger.error("SQL error reading game_access_token for {}", uuid, e);
        }
        return null;
    }

    /**
     * Generates a new random refresh token with a 14-day expiry, stores it in
     * game_access_token, and returns the raw token value (without the username prefix).
     * Format: v1:{expiry_epoch_ms}:{random_hex}
     */
    private String generateAndStoreGameToken(UUID uuid) throws IOException {
        long expiry = System.currentTimeMillis() + REFRESH_TTL_MS;
        String secret = SecurityHelper.randomStringToken();
        String tokenValue = "v1:" + expiry + ":" + secret;
        try (Connection c = sql.getSQLConfig().getConnection()) {
            PreparedStatement s = c.prepareStatement(sqlUpdateGameToken);
            s.setString(1, tokenValue);
            s.setString(2, uuidParam(uuid));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to store refresh token", e);
        }
        return tokenValue;
    }

    private void enrichRoleFromAzuriom(MySQLCoreProvider.MySQLUser local, com.azuriom.azauth.model.User azUser) {
        if (local.getPermissions() != null && azUser.getRole() != null && azUser.getRole().getName() != null) {
            String role = azUser.getRole().getName();
            if (!local.getPermissions().hasRole(role)) {
                local.getPermissions().addRole(role);
            }
        }
    }

    private String ipOf(AuthResponse.AuthContext ctx) {
        return ctx != null ? ctx.ip : null;
    }

    // ── AuthCoreProvider overrides ───────────────────────────────────────────

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

    /**
     * Full login with username + password (and optional TOTP).
     * The request is forwarded to Azuriom over HTTP so that:
     *  - the client's real IP is recorded by Azuriom (when called from the launcher client side),
     *  - 2FA is handled natively by Azuriom,
     *  - the ban check at login comes from Azuriom.
     * LaunchServer additionally checks is_banned and HWID ban via SQL.
     */
    @Override
    public AuthManager.AuthReport authorize(String login, AuthResponse.AuthContext context,
                                            AuthRequest.AuthPasswordInterface password,
                                            boolean minecraftAccess) throws IOException, pro.gravit.launchserver.auth.AuthException {
        if (authClient == null) {
            throw new pro.gravit.launchserver.auth.AuthException("AzuriomCoreProvider is not configured");
        }

        String plainPassword;
        String totpCode = null;

        if (password instanceof Auth2FAPassword auth2fa) {
            if (!(auth2fa.firstPassword instanceof AuthPlainPassword)) {
                throw new pro.gravit.launchserver.auth.AuthException("Unsupported 2FA password type");
            }
            plainPassword = ((AuthPlainPassword) auth2fa.firstPassword).password;
            if (auth2fa.secondPassword instanceof AuthTOTPPassword totp) {
                totpCode = totp.totp;
            }
        } else if (password instanceof AuthPlainPassword plain) {
            plainPassword = plain.password;
        } else {
            throw new pro.gravit.launchserver.auth.AuthException("Unsupported password type");
        }

        try {
            com.azuriom.azauth.AuthResult<com.azuriom.azauth.model.User> result =
                    authClient.login(login, plainPassword);

            if (result.isPending() && result.asPending().require2fa()) {
                if (totpCode == null) throw pro.gravit.launchserver.auth.AuthException.need2FA();
                result = authClient.login(login, plainPassword, totpCode);
            }

            if (!result.isSuccess()) {
                throw new pro.gravit.launchserver.auth.AuthException("Authentication failed");
            }

            com.azuriom.azauth.model.User azUser = result.getSuccessResult();

            MySQLCoreProvider.MySQLUser local = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(azUser.getUuid());
            if (local == null) {
                logger.warn("User '{}' ({}) authenticated via Azuriom but absent in local DB.",
                        azUser.getUsername(), azUser.getUuid());
                throw new pro.gravit.launchserver.auth.AuthException("User not found in local database");
            }

            enrichRoleFromAzuriom(local, azUser);
            checkIsBanned(local.getUUID());
            checkHwidBan(local);
            updateLastLogin(local.getUUID(), ipOf(context));

            return buildAuthReport(local, minecraftAccess);

        } catch (AuthException e) {
            throw new pro.gravit.launchserver.auth.AuthException(e.getMessage(), e);
        }
    }

    /**
     * Called when the client sends a previously obtained Sanctum access_token.
     * Verifies it with Azuriom, then issues a LaunchServer JWT + game_access_token refresh token.
     */
    @Override
    public AuthManager.AuthReport reportFromOAuth(String accessToken, AuthResponse.AuthContext context) throws IOException {
        try {
            com.azuriom.azauth.model.User azUser = authClient.verify(accessToken);

            MySQLCoreProvider.MySQLUser local = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(azUser.getUuid());
            if (local == null) {
                logger.warn("User '{}' ({}) verified via Azuriom but absent in local DB.",
                        azUser.getUsername(), azUser.getUuid());
                throw new AuthException("User not found in local database");
            }

            enrichRoleFromAzuriom(local, azUser);
            checkIsBanned(local.getUUID());
            checkHwidBan(local);
            updateLastLogin(local.getUUID(), ipOf(context));

            boolean minecraftAccess = context != null && server.config.protectHandler.allowGetAccessToken(context);
            return buildAuthReport(local, minecraftAccess);

        } catch (AuthException e) {
            throw new pro.gravit.launchserver.auth.AuthException(AuthRequestEvent.OAUTH_TOKEN_INVALID);
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            throw e;
        }
    }

    /** Handles JWT access tokens (delegates to SQL/ECDSA verification). */
    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        return sql.getUserSessionByOAuthAccessToken(accessToken);
    }

    /**
     * Auto-login via the long-lived refresh token stored in game_access_token.
     * Verifies the token, checks bans, updates last_login_at so Azuriom sees the activity,
     * then issues a fresh JWT and rotates the refresh token (14-day window resets).
     */
    @Override
    public AuthManager.AuthReport refreshAccessToken(String refreshToken, AuthResponse.AuthContext context) {
        // Format: username.v1:EXPIRY_EPOCH_MS:RANDOM_HEX
        int dot = refreshToken.indexOf('.');
        if (dot < 1) return null;
        String username = refreshToken.substring(0, dot);
        String tokenValue = refreshToken.substring(dot + 1);

        // Validate format
        String[] parts = tokenValue.split(":", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) return null;

        long expiry;
        try {
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (System.currentTimeMillis() > expiry) {
            logger.info("Refresh token expired for '{}'", username);
            return null;
        }

        MySQLCoreProvider.MySQLUser user = (MySQLCoreProvider.MySQLUser) sql.getUserByUsername(username);
        if (user == null) return null;

        String stored = readGameToken(user.getUUID());
        if (!tokenValue.equals(stored)) {
            logger.warn("Refresh token mismatch for '{}' — possible replay or forced logout", username);
            return null;
        }

        try {
            checkIsBanned(user.getUUID());
            checkHwidBan(user);
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            logger.info("Banned user '{}' blocked during auto-login refresh: {}", username, e.getMessage());
            return null;
        }

        // Update Azuriom's last_login_at so the admin panel shows launcher activity
        updateLastLogin(user.getUUID(), ipOf(context));

        // Rotate refresh token to reset the 14-day window
        String newTokenValue;
        try {
            newTokenValue = generateAndStoreGameToken(user.getUUID());
        } catch (IOException e) {
            logger.error("Failed to rotate refresh token for '{}'", username, e);
            return null;
        }
        String newRefreshToken = username + "." + newTokenValue;

        String newAccessToken = LegacySessionHelper.makeAccessJwtTokenFromString(
                user,
                LocalDateTime.now(Clock.systemUTC()).plusSeconds(sql.expireSeconds),
                server.keyAgreementManager.ecdsaPrivateKey);

        return new AuthManager.AuthReport(null, newAccessToken, newRefreshToken,
                SECONDS.toMillis(sql.expireSeconds), sql.createSession(user));
    }

    // ── Shared helper ────────────────────────────────────────────────────────

    private AuthManager.AuthReport buildAuthReport(MySQLCoreProvider.MySQLUser user, boolean minecraftAccess) throws IOException {
        String accessToken = LegacySessionHelper.makeAccessJwtTokenFromString(
                user,
                LocalDateTime.now(Clock.systemUTC()).plusSeconds(sql.expireSeconds),
                server.keyAgreementManager.ecdsaPrivateKey);
        String gameTokenValue = generateAndStoreGameToken(user.getUUID());
        String refreshToken = user.getUsername() + "." + gameTokenValue;
        AbstractSQLCoreProvider.SQLUserSession session = sql.createSession(user);

        if (minecraftAccess) {
            String mcToken = SecurityHelper.randomStringToken();
            sql.updateAuth(user, mcToken);
            return AuthManager.AuthReport.ofOAuthWithMinecraft(mcToken, accessToken, refreshToken,
                    SECONDS.toMillis(sql.expireSeconds), session);
        }
        return AuthManager.AuthReport.ofOAuth(accessToken, refreshToken,
                SECONDS.toMillis(sql.expireSeconds), session);
    }

    // ── Server-join checks ───────────────────────────────────────────────────

    @Override
    public User checkServer(Client client, String username, String serverID) throws IOException {
        return sql.checkServer(client, username, serverID);
    }

    @Override
    public UserSession extendedCheckServer(Client client, String username, String serverID) throws IOException {
        MySQLCoreProvider.MySQLUser user = (MySQLCoreProvider.MySQLUser) sql.getUserByUsername(username);
        if (user == null) return null;
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
        return sql.joinServer(client, username, uuid, accessToken, serverID);
    }

    // ── HWID support (delegated to MySQLCoreProvider) ────────────────────────

    @Override
    public UserHardware getHardwareInfoByPublicKey(byte[] publicKey) {
        return sql.getHardwareInfoByPublicKey(publicKey);
    }

    @Override
    public UserHardware getHardwareInfoByData(pro.gravit.launcher.base.request.secure.HardwareReportRequest.HardwareInfo info) {
        return sql.getHardwareInfoByData(info);
    }

    @Override
    public UserHardware getHardwareInfoById(String id) {
        return sql.getHardwareInfoById(id);
    }

    @Override
    public UserHardware createHardwareInfo(pro.gravit.launcher.base.request.secure.HardwareReportRequest.HardwareInfo info, byte[] publicKey) {
        return sql.createHardwareInfo(info, publicKey);
    }

    @Override
    public void connectUserAndHardware(UserSession userSession, UserHardware hardware) {
        sql.connectUserAndHardware(userSession, hardware);
    }

    @Override
    public void addPublicKeyToHardwareInfo(UserHardware hardware, byte[] publicKey) {
        sql.addPublicKeyToHardwareInfo(hardware, publicKey);
    }

    @Override
    public Iterable<User> getUsersByHardwareInfo(UserHardware hardware) {
        return sql.getUsersByHardwareInfo(hardware);
    }

    @Override
    public void banHardware(UserHardware hardware) {
        sql.banHardware(hardware);
    }

    @Override
    public void unbanHardware(UserHardware hardware) {
        sql.unbanHardware(hardware);
    }

    @Override
    public void close() {
        if (sql != null) sql.close();
    }
}
