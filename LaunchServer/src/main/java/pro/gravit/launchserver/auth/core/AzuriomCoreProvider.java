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
import pro.gravit.launcher.base.request.auth.details.AuthTotpDetails;
import pro.gravit.launcher.base.request.auth.password.Auth2FAPassword;
import pro.gravit.launcher.base.request.auth.password.AuthPlainPassword;
import pro.gravit.launcher.base.request.auth.password.AuthTOTPPassword;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.auth.MySQLSourceConfig;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AzuriomCoreProvider extends AuthCoreProvider implements AuthSupportHardware, AuthSupportExtendedCheckServer {
    private transient final Logger logger = LogManager.getLogger();
    public String azuriomUrl;
    public String azuriomTokenColumn = "access_token";
    public String actionLogsTable = "action_logs";
    public String bansTable = "bans";
    public String rolesTable = "roles";
    public String roleIdColumn = "role_id";
    // Мінімальна пауза між записами автовходу в action_logs: реконекти WebSocket
    // і щогодинні оновлення JWT не повинні засмічувати журнал адмін-панелі.
    public long autoLoginLogCooldownSeconds = 600;
    // Формат зберігання UUID у колонці game_id: "auto" (визначити по першому запису),
    // "dashed" (b5de213e-12bd-...) або "dashless" (b5de213e12bd...). Стандартний Azuriom
    // пише з дефісами, але старі бази/плагіни (AzLink) — без.
    public String uuidFormat = "auto";
    public MySQLCoreProvider sql;
    private transient AuthClient authClient;
    private transient boolean isDatabaseMode = false;
    private transient boolean dashlessUuidStorage = false;
    private transient ThreadPoolExecutor logExecutor;

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
            detectUuidStorageFormat();
            // Стокові методи біндять UUID у різних форматах (читання — без дефісів,
            // записи — з дефісами), а БД може зберігати будь-який. Нормалізуємо
            // ПАРАМЕТР до формату колонки: вираз навколо ? — sargable, індекс працює
            // (на відміну від REPLACE() навколо колонки, що дає повний скан).
            // Параметр читань приходить БЕЗ дефісів (getUserByUUID їх зрізає):
            String readParam = dashlessUuidStorage
                    ? "?"
                    : "INSERT(INSERT(INSERT(INSERT(?,9,0,'-'),14,0,'-'),19,0,'-'),24,0,'-')";
            // Параметр записів приходить З дефісами (updateAuth/updateServerID/hwid):
            String writeParam = dashlessUuidStorage ? "REPLACE(?, '-', '')" : "?";
            if (sql.customQueryByUUIDSQL == null) {
                sql.customQueryByUUIDSQL = "SELECT %s FROM %s WHERE %s = %s LIMIT 1"
                        .formatted(sql.makeUserCols(), sql.table, sql.uuidColumn, readParam);
            }
            // Permissions біндяться ДЕФІСНИМ uuid (requestPermissions(user.uuid.toString())) —
            // для бездефісної БД нормалізуємо параметр (простий варіант без rolesTable;
            // з rolesTable дефолт — рекурсивний CTE, його не чіпаємо: задайте custom-запит).
            if (dashlessUuidStorage && sql.permissionsTable != null && sql.rolesTable == null
                    && sql.customQueryPermissionsByUUIDSQL == null) {
                sql.customQueryPermissionsByUUIDSQL = "SELECT (%s) FROM %s WHERE %s = REPLACE(?, '-', '')"
                        .formatted(sql.permissionsPermissionColumn, sql.permissionsTable, sql.permissionsUUIDColumn);
            }
            sql.init(server, pair);
            // Черга логування автовходів: один фоновий потік, обмежена черга,
            // переповнення мовчки відкидається — best-effort за визначенням.
            logExecutor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(256), r -> {
                Thread t = new Thread(r, "azuriom-login-log");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());
            // Prevent updateAuth from clearing serverId — the default SQL does
            // SET serverId=NULL which breaks extendedCheckServer during server switch.
            if (sql.customUpdateAuthSQL == null) {
                sql.updateAuthSQL = "UPDATE %s SET %s=? WHERE %s = %s".formatted(
                        sql.table, sql.accessTokenColumn, sql.uuidColumn, writeParam);
            }
            if (sql.customUpdateServerIdSQL == null) {
                sql.updateServerIDSQL = "UPDATE %s SET %s=? WHERE %s = %s".formatted(
                        sql.table, sql.serverIDColumn, sql.uuidColumn, writeParam);
            }
            // HWID-прив'язка (UPDATE users SET hwid_id=? WHERE uuid=?) — той самий формат:
            sql.sqlUpdateUsers = "UPDATE %s SET `%s` = ? WHERE %s = %s".formatted(
                    sql.table, sql.hardwareIdColumn, sql.uuidColumn, writeParam);
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

    // Визначає, як БД зберігає UUID: з дефісами чи без. Порядок: явний конфіг
    // uuidFormat, інакше — перший непорожній запис у таблиці. Порожня таблиця або
    // збій БД → дефісний формат (стандарт Azuriom) з попередженням у лог.
    private void detectUuidStorageFormat() {
        if ("dashed".equalsIgnoreCase(uuidFormat)) {
            dashlessUuidStorage = false;
            return;
        }
        if ("dashless".equalsIgnoreCase(uuidFormat)) {
            dashlessUuidStorage = true;
            return;
        }
        String query = "SELECT %s FROM %s WHERE %s IS NOT NULL AND %s != '' LIMIT 1"
                .formatted(sql.uuidColumn, sql.table, sql.uuidColumn, sql.uuidColumn);
        try (Connection conn = sql.mySQLHolder.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setQueryTimeout(MySQLSourceConfig.TIMEOUT);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    dashlessUuidStorage = rs.getString(1).indexOf('-') < 0;
                    logger.info("Azuriom UUID storage format detected: {}",
                            dashlessUuidStorage ? "dashless" : "dashed");
                    return;
                }
            }
        } catch (SQLException e) {
            logger.error("Cannot detect UUID storage format, assuming dashed", e);
        }
        logger.warn("UUID format detection: no rows in {} — assuming dashed (set uuidFormat in config to override)", sql.table);
        dashlessUuidStorage = false;
    }

    // UUID у форматі, в якому його зберігає БД, — для власних запитів провайдера.
    private String uuidParam(UUID uuid) {
        String s = uuid.toString();
        return dashlessUuidStorage ? s.replace("-", "") : s;
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
            stmt.setQueryTimeout(MySQLSourceConfig.TIMEOUT);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new OAuthAccessTokenExpired("Token not found");
                }
                String rawUuid = rs.getString(sql.uuidColumn);
                if (rawUuid == null || rawUuid.isEmpty()) {
                    throw new OAuthAccessTokenExpired("User row has no UUID");
                }
                try {
                    return AbstractSQLCoreProvider.toUUID(rawUuid); // приймає обидва формати
                } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
                    logger.warn("Malformed UUID '{}' in {} for a valid token", rawUuid, sql.table);
                    throw new OAuthAccessTokenExpired("Malformed UUID in database");
                }
            }
        } catch (SQLException e) {
            logger.error("SQL error during token verification", e);
            throw new OAuthAccessTokenExpired("Database error during token verification");
        }
    }

    // Mirrors what Azuriom's AuthController::verify() does (users.last_login_* + action_logs
    // entry 'users.auth.api.verified'), but via direct SQL so it works even when the site's
    // access_token was rotated or set to NULL. Best-effort, off the auth thread: failures
    // must neither break nor slow down auth.
    private void logSiteAutoLogin(UUID uuid, String ip) {
        if (!isDatabaseMode || logExecutor == null) return;
        final String uuidStr = uuidParam(uuid);
        final String safeIp = ip == null ? "" : ip.replace("\"", "");
        // Рядок у action_logs — лише коли НАШ попередній запис старший за cooldown:
        // реконекти та оновлення JWT посеред сесії не є новими входами. Порівнюємо
        // тільки з власними записами (їх пише той самий NOW()) — last_login_at,
        // записаний Laravel'ом, може бути в іншій таймзоні й «жити в майбутньому»,
        // що придушувало б логування на години після входу на сайті.
        final String insertQuery = ("INSERT INTO %s (user_id, action, target_id, data, created_at, updated_at) " +
                "SELECT u.id, 'users.auth.api.verified', NULL, ?, NOW(), NOW() FROM %s u " +
                "WHERE u.%s = ? AND NOT EXISTS (SELECT 1 FROM %s al WHERE al.user_id = u.id " +
                "AND al.action = 'users.auth.api.verified' AND al.created_at > NOW() - INTERVAL %d SECOND)")
                .formatted(actionLogsTable, sql.table, sql.uuidColumn, actionLogsTable, autoLoginLogCooldownSeconds);
        final String updateQuery = "UPDATE %s SET last_login_at = NOW(), last_login_ip = ? WHERE %s = ?"
                .formatted(sql.table, sql.uuidColumn);
        logExecutor.execute(() -> {
            try (Connection conn = sql.mySQLHolder.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                    stmt.setString(1, "{\"ip\":\"" + safeIp + "\"}");
                    stmt.setString(2, uuidStr);
                    stmt.setQueryTimeout(MySQLSourceConfig.TIMEOUT);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
                    stmt.setString(1, safeIp);
                    stmt.setString(2, uuidStr);
                    stmt.setQueryTimeout(MySQLSourceConfig.TIMEOUT);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                logger.warn("Failed to log Azuriom auto-login for user {}: {}", uuidStr, e.getMessage());
            }
        });
    }

    // Активний бан на сайті = рядок у bans з removed_at IS NULL (Azuriom soft-delete'ить
    // зняті бани). Повертає причину бану або null. SQL-помилка = fail-open з логом:
    // збій БД не повинен відрізати всіх гравців (сам сайт кешує isBanned на годину).
    private String getSiteBanReason(UUID uuid) {
        if (!isDatabaseMode) return null;
        String query = ("SELECT b.reason FROM %s b JOIN %s u ON b.user_id = u.id " +
                "WHERE u.%s = ? AND b.removed_at IS NULL LIMIT 1")
                .formatted(bansTable, sql.table, sql.uuidColumn);
        try (Connection conn = sql.mySQLHolder.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, uuidParam(uuid));
            stmt.setQueryTimeout(MySQLSourceConfig.TIMEOUT);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String reason = rs.getString(1);
                    return reason == null || reason.isEmpty() ? "banned" : reason;
                }
            }
        } catch (SQLException e) {
            logger.error("SQL error during site ban check for {}", uuid, e);
        }
        return null;
    }

    // Роль користувача на сайті (users.role_id -> roles.name) для збагачення permissions
    // на шляхах автовходу, де немає об'єкта azauth User.
    private void enrichWithSiteRole(MySQLCoreProvider.MySQLUser localUser, UUID uuid) {
        if (!isDatabaseMode || localUser.getPermissions() == null) return;
        String query = "SELECT r.name FROM %s u JOIN %s r ON u.%s = r.id WHERE u.%s = ? LIMIT 1"
                .formatted(sql.table, rolesTable, roleIdColumn, sql.uuidColumn);
        try (Connection conn = sql.mySQLHolder.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, uuidParam(uuid));
            stmt.setQueryTimeout(MySQLSourceConfig.TIMEOUT);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String role = rs.getString(1);
                    if (role != null && !role.isEmpty() && !localUser.getPermissions().hasRole(role)) {
                        localUser.getPermissions().addRole(role);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("SQL error reading site role for {}", uuid, e);
        }
    }

    // Розпізнаний токен: uuid користувача + чи це справді був наш JWT (а не токен сайта).
    private record ResolvedToken(UUID uuid, boolean fromJwt) {
    }

    // Resolves a UUID from either an Azuriom access_token (plaintext in DB, sent on first
    // login right after the client's authenticate() call) or a LaunchServer JWT (all
    // subsequent auto-logins and refreshes). The "eyJ" prefix is only a hint: a random
    // Azuriom token can also start with it (~1 per 238k), so a failed JWT parse falls
    // back to the database lookup instead of rejecting the login.
    private ResolvedToken resolveAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (isJwtToken(accessToken)) {
            try {
                var info = LegacySessionHelper.getJwtInfoFromAccessToken(accessToken, server.keyAgreementManager.ecdsaPublicKey);
                return new ResolvedToken(info.uuid(), true);
            } catch (ExpiredJwtException e) {
                throw new OAuthAccessTokenExpired("JWT expired");
            } catch (JwtException e) {
                // не наш JWT — можливо, токен сайта з префіксом "eyJ"; пробуємо БД
            }
        }
        return new ResolvedToken(verifyTokenFromDatabase(accessToken), false);
    }

    // JWT tokens from LaunchServer always start with the base64url-encoded header "eyJ" ({"alg":...}).
    private static boolean isJwtToken(String token) {
        return token != null && token.startsWith("eyJ");
    }

    // Спільна збірка OAuth-звіту для reportFromOAuth() та authorize(): наш JWT як
    // accessToken (сесія лаунчера не залежить від токена сайта, який ротується),
    // refresh-токен від хешу пароля, опціональний minecraft-токен.
    private AuthManager.AuthReport makeOAuthReport(MySQLCoreProvider.MySQLUser localUser, boolean minecraftAccess) throws IOException {
        String oauthToken = LegacySessionHelper.makeAccessJwtTokenFromString(localUser,
                LocalDateTime.now(Clock.systemUTC()).plusSeconds(sql.expireSeconds),
                server.keyAgreementManager.ecdsaPrivateKey);
        UserSession session = sql.createSession(localUser);
        var refreshToken = localUser.getUsername().concat(".").concat(LegacySessionHelper.makeRefreshTokenFromPassword(localUser.getUsername(), localUser.password, server.keyAgreementManager.legacySalt));

        if (minecraftAccess) {
            String minecraftAccessToken = SecurityHelper.randomStringToken();
            sql.updateAuth(localUser, minecraftAccessToken);
            return AuthManager.AuthReport.ofOAuthWithMinecraft(minecraftAccessToken, oauthToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
        } else {
            return AuthManager.AuthReport.ofOAuth(oauthToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
        }
    }

    @Override
    public AuthManager.AuthReport reportFromOAuth(String accessToken, AuthResponse.AuthContext context) throws IOException {
        if (!isDatabaseMode) {
            // Без БД токен сайта — єдина сесійна валюта; перевіряємо його через API
            // сайта, як робив старий потік (verify() також оновить журнал панелі).
            try {
                com.azuriom.azauth.model.User azuriomUser = authClient.verify(accessToken);
                UserSession session = createOfflineSession(azuriomUser);
                User user = session.getUser();
                var refreshToken = user.getUsername().concat(".").concat(LegacySessionHelper.makeRefreshTokenFromPassword(user.getUsername(), "mockpassword", server.keyAgreementManager.legacySalt));
                boolean minecraftAccess = server.config.protectHandler.allowGetAccessToken(context);
                if (minecraftAccess) {
                    return AuthManager.AuthReport.ofOAuthWithMinecraft(SecurityHelper.randomStringToken(), accessToken, refreshToken, SECONDS.toMillis(3600), session);
                }
                return AuthManager.AuthReport.ofOAuth(accessToken, refreshToken, SECONDS.toMillis(3600), session);
            } catch (AuthException e) {
                throw new pro.gravit.launchserver.auth.AuthException(
                        pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_INVALID);
            }
        }
        try {
            ResolvedToken resolved = resolveAccessToken(accessToken);
            UUID userUuid = resolved.uuid();

            boolean minecraftAccess = server.config.protectHandler.allowGetAccessToken(context);

            MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(userUuid);

            if (localUser == null) {
                logger.warn("User with UUID '{}' verified via token but not found in local database.", userUuid);
                throw new pro.gravit.launchserver.auth.AuthException(pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_INVALID);
            }

            // Бани сайта живуть в окремій таблиці bans і не чіпають access_token,
            // тому їх треба перевіряти явно на кожному автовході.
            String banReason = getSiteBanReason(userUuid);
            if (banReason != null) {
                throw new pro.gravit.launchserver.auth.AuthException("User banned: " + banReason);
            }
            checkHwidBan(localUser);
            enrichWithSiteRole(localUser, userUuid);

            // JWT = автовхід (ручні входи приходять зі свіжим токеном сайта і вже
            // залоговані authenticate()'ом самого сайта). Фіксуємо в адмін-панелі.
            if (resolved.fromJwt()) {
                logSiteAutoLogin(userUuid, context != null ? context.ip : null);
            }

            return makeOAuthReport(localUser, minecraftAccess);

        } catch (OAuthAccessTokenExpired e) {
            // Expired JWT or rotated/cleared Azuriom token — both recoverable: the client
            // sends RefreshTokenRequest (validated against the password hash) and retries.
            throw new pro.gravit.launchserver.auth.AuthException(pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_EXPIRE);
        }
    }

    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (!isDatabaseMode) {
            throw new OAuthAccessTokenExpired("Database mode is disabled");
        }
        UUID userUuid = resolveAccessToken(accessToken).uuid();

        MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(userUuid);

        if (localUser == null) {
            logger.warn("User with UUID '{}' verified via token but not found in local database.", userUuid);
            throw new OAuthAccessTokenExpired("User not found");
        }

        // Wrapping bans as OAuthAccessTokenExpired forces a refresh cycle, after which
        // reportFromOAuth() will reject the user with the proper ban message.
        String banReason = getSiteBanReason(userUuid);
        if (banReason != null) {
            throw new OAuthAccessTokenExpired("BANNED: " + banReason);
        }
        try {
            checkHwidBan(localUser);
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            throw new OAuthAccessTokenExpired("HWID_BAN: " + e.getMessage());
        }
        enrichWithSiteRole(localUser, userUuid);

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

            return makeOAuthReport(localUser, minecraftAccess);

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
        // AuthTotpDetails робить робочим серверний 2FA-шлях (authorize() кидає need2FA):
        // без нього клієнт з plain-паролем упирається в '2FA method not found'.
        return List.of(details, new AuthTotpDetails("TOTP", 6));
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
        if (logExecutor != null) {
            logExecutor.shutdown();
        }
        if (isDatabaseMode && sql != null) {
            sql.close();
        }
    }
}
