package pro.gravit.launchserver.auth.core;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.exception.AuthException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.ClientPermissions;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AzuriomCoreProvider extends AuthCoreProvider implements AuthSupportHardware, AuthSupportExtendedCheckServer {
    private transient final Logger logger = LogManager.getLogger();
    public String azuriomUrl;
    public MySQLCoreProvider sql;
    private transient AuthClient authClient;
    private transient boolean isDatabaseMode = false;

    /**
     * Every access token check is an HTTP request to the Azuriom site. Clients check/restore their session often
     * (hourly refresh, reconnects, game start), so successful answers are remembered briefly.
     */
    private static final long VERIFY_CACHE_MILLIS = 30_000;
    private static final long VERIFY_FAIL_CACHE_MILLIS = 10_000;
    private static final int VERIFY_CACHE_MAX = 2000;
    private transient final ConcurrentHashMap<String, CachedVerify> verifyCache = new ConcurrentHashMap<>();

    private record CachedVerify(com.azuriom.azauth.model.User user, String error, long expiresAt) {
    }

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
            logger.warn("Azuriom provider: 'sql' section is not configured. Working WITHOUT database integration: " +
                    "joinServer/checkServer (ServerWrapper), token refresh and hardware features are unavailable.");
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
        UUID uuid = azuriomUser.getUuid();
        if (uuid == null) {
            // Azuriom users created without a game id: use the same id Minecraft computes for offline players
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + azuriomUser.getUsername()).getBytes(StandardCharsets.UTF_8));
        }
        User user = new OfflineUser(azuriomUser.getUsername(), uuid, new ClientPermissions());
        return new OfflineUserSession(user);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private com.azuriom.azauth.model.User verifyToken(String accessToken) throws AuthException {
        String key = sha256(accessToken);
        long now = System.currentTimeMillis();
        CachedVerify cached = verifyCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            if (cached.user() != null) return cached.user();
            throw new AuthException(cached.error());
        }
        if (verifyCache.size() > VERIFY_CACHE_MAX) {
            verifyCache.values().removeIf(e -> e.expiresAt() <= now);
            if (verifyCache.size() > VERIFY_CACHE_MAX) verifyCache.clear();
        }
        try {
            var user = authClient.verify(accessToken);
            verifyCache.put(key, new CachedVerify(user, null, now + VERIFY_CACHE_MILLIS));
            return user;
        } catch (AuthException e) {
            // a rejected token (not a network failure, that is an IOException/RuntimeException) is remembered shortly too
            verifyCache.put(key, new CachedVerify(null, String.valueOf(e.getMessage()), now + VERIFY_FAIL_CACHE_MILLIS));
            throw e;
        } catch (com.google.gson.JsonParseException e) {
            // e.g. "uuid": null: the AzAuth library cannot read users that have no game id and throws a runtime exception
            logger.error("Azuriom returned a user that cannot be read: {}", e.getMessage());
            var failure = new AuthException("Unexpected answer of Azuriom", e);
            verifyCache.put(key, new CachedVerify(null, failure.getMessage(), now + VERIFY_FAIL_CACHE_MILLIS));
            throw failure;
        }
    }

    /**
     * Tokens issued by refreshAccessToken come from the SQL provider (our own JWT), not from Azuriom.
     */
    private UserSession sqlSessionByJwt(String accessToken) throws OAuthAccessTokenExpired {
        if (!isDatabaseMode) return null;
        return sql.getUserSessionByOAuthAccessToken(accessToken);
    }
    
    @Override
    public AuthManager.AuthReport reportFromOAuth(String accessToken, AuthResponse.AuthContext context) throws IOException {
        try {
            try {
                if (sqlSessionByJwt(accessToken) != null) {
                    // token from refreshAccessToken
                    return sql.reportFromOAuth(accessToken, context);
                }
            } catch (OAuthAccessTokenExpired e) {
                throw new pro.gravit.launchserver.auth.AuthException(AuthRequestEvent.OAUTH_TOKEN_EXPIRE);
            }
            com.azuriom.azauth.model.User azuriomUser = verifyToken(accessToken);

            boolean minecraftAccess = server.config.protectHandler.allowGetAccessToken(context);

            if (!isDatabaseMode) {
                UserSession session = createOfflineSession(azuriomUser);
                User user = session.getUser();
                // no database: refresh tokens cannot be verified, so none is issued
                String refreshToken = null;

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
                throw new AuthException("User not found in local database");
            }

            enrichUserWithAzuriomData(localUser, azuriomUser);
            checkHwidBan(localUser);

            UserSession session = sql.createSession(localUser);
            var refreshToken = localUser.getUsername().concat(".").concat(LegacySessionHelper.makeRefreshTokenFromPassword(localUser.getUsername(), localUser.password, server.keyAgreementManager.legacySalt));
            
            if (minecraftAccess) {
                // Re-authorizing an existing session (reconnect, hourly refresh) must not invalidate the Minecraft
                // token of the game that is already running, so the stored one is reused
                String minecraftAccessToken = localUser.getAccessToken();
                if (minecraftAccessToken == null || minecraftAccessToken.isEmpty()) {
                    minecraftAccessToken = SecurityHelper.randomStringToken();
                    sql.updateAuth(localUser, minecraftAccessToken);
                }
                return AuthManager.AuthReport.ofOAuthWithMinecraft(minecraftAccessToken, accessToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
            } else {
                return AuthManager.AuthReport.ofOAuth(accessToken, refreshToken, SECONDS.toMillis(sql.expireSeconds), session);
            }

        } catch (AuthException e) {
            throw new pro.gravit.launchserver.auth.AuthException(pro.gravit.launcher.base.events.request.AuthRequestEvent.OAUTH_TOKEN_INVALID);
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            throw e;
        }
    }

    @Override
    public UserSession getUserSessionByOAuthAccessToken(String accessToken) throws OAuthAccessTokenExpired {
        if (authClient == null) {
            throw new OAuthAccessTokenExpired("Azuriom provider is not initialized");
        }
        UserSession jwtSession = sqlSessionByJwt(accessToken);
        if (jwtSession != null) {
            return jwtSession;
        }
        try {
            com.azuriom.azauth.model.User azuriomUser = verifyToken(accessToken);

            if (!isDatabaseMode) {
                return createOfflineSession(azuriomUser);
            }

            MySQLCoreProvider.MySQLUser localUser = (MySQLCoreProvider.MySQLUser) sql.getUserByUUID(azuriomUser.getUuid());

            if (localUser == null) {
                logger.warn("User '{}' (UUID: {}) authenticated via Azuriom but not found in local database.", 
                    azuriomUser.getUsername(), azuriomUser.getUuid());
                return null;
            }

            enrichUserWithAzuriomData(localUser, azuriomUser);
            checkHwidBan(localUser);

            return sql.createSession(localUser);

        } catch (AuthException e) {
            throw new OAuthAccessTokenExpired(e.getMessage());
        } catch (pro.gravit.launchserver.auth.AuthException e) {
            logger.error("Local user check failed during token verification", e);
            throw new OAuthAccessTokenExpired(e.getMessage());
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
                logger.debug("Azuriom rejected the login of '{}': {}", login, result);
                throw new pro.gravit.launchserver.auth.AuthException("Authentication failed");
            }

            com.azuriom.azauth.model.User azuriomUser = result.getSuccessResult();

            if (!isDatabaseMode) {
                UserSession session = createOfflineSession(azuriomUser);
                User user = session.getUser();
                var accessToken = azuriomUser.getAccessToken();
                String refreshToken = null; // no database: refresh tokens cannot be verified

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
            var accessToken = azuriomUser.getAccessToken();
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
        } catch (com.google.gson.JsonParseException e) {
            logger.error("Azuriom returned a user that cannot be read: {}", e.getMessage());
            throw new pro.gravit.launchserver.auth.AuthException("Unexpected answer of Azuriom");
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
        if (user.getUsername().equals(username) && serverID != null && serverID.equals(user.getServerId())) {
            return sql.createSession(user);
        }
        return null;
    }

    @Override
    public List<GetAvailabilityAuthRequestEvent.AuthAvailabilityDetails> getDetails(Client client) {
        return List.of(new AuthPasswordDetails(), new AuthTotpDetails("SHA1"));
    }

    /**
     * ВИПРАВЛЕННЯ: joinServer більше не покладається на client.getUser(),
     * який завжди null для підключень від ServerWrapper.
     *
     * Правильна логіка:
     * 1. Знаходимо гравця в БД по username
     * 2. Перевіряємо що accessToken збігається з тим що в БД (записаний під час авторизації)
     * 3. Записуємо serverID в БД для подальшої перевірки через checkServer
     */
    @Override
    public boolean joinServer(Client client, String username, UUID uuid, String accessToken, String serverID) throws IOException {
        if (!isDatabaseMode) {
            logger.warn("Database mode is disabled. Cannot join server for user '{}'.", username);
            return false;
        }

        // Шукаємо гравця напряму з БД — НЕ через client.getUser()
        User abstractUser = sql.getUserByUsername(username);
        if (!(abstractUser instanceof AbstractSQLCoreProvider.SQLUser user)) {
            logger.warn("joinServer: user '{}' not found in database", username);
            return false;
        }

        // Перевіряємо UUID якщо він переданий
        if (uuid != null && !user.getUUID().equals(uuid)) {
            logger.warn("joinServer: UUID mismatch for user '{}'. Expected: {}, got: {}",
                    username, user.getUUID(), uuid);
            return false;
        }

        // Перевіряємо accessToken
        String storedToken = user.getAccessToken();
        if (storedToken == null || accessToken == null
                || !MessageDigest.isEqual(storedToken.getBytes(StandardCharsets.UTF_8), accessToken.getBytes(StandardCharsets.UTF_8))) {
            logger.warn("joinServer: accessToken mismatch for user '{}'", username);
            return false;
        }

        // Записуємо serverID в БД
        boolean updated = sql.updateServerID(user, serverID);
        if (!updated) {
            logger.warn("joinServer: failed to update serverID for user '{}'", username);
        }
        return updated;
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