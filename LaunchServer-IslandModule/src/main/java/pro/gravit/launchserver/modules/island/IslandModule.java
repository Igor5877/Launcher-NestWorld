package pro.gravit.launchserver.modules.island;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import pro.gravit.launcher.base.modules.LauncherInitContext;
import pro.gravit.launcher.base.modules.LauncherModule;
import pro.gravit.launcher.base.modules.LauncherModuleInfo;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.modules.events.LaunchServerFullInitEvent;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launchserver.socket.response.auth.SetProfileResponse;
import pro.gravit.utils.Version;
import pro.gravit.utils.helper.LogHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IslandModule extends LauncherModule {
    private static final String MODULE_NAME = "IslandModule";
    private static final Version MODULE_VERSION = new Version(1, 0, 0);
    private IslandConfig config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private ExecutorService executor;
    private LaunchServer server;
    /**
     * setProfile is also sent again after a reconnect: the island must not be started twice in a row for one player
     */
    private static final long DEDUPE_MILLIS = 60_000;
    private final ConcurrentHashMap<String, Long> recentStarts = new ConcurrentHashMap<>();

    public IslandModule() {
        super(new LauncherModuleInfo(MODULE_NAME, MODULE_VERSION));
    }

    @Override
    public void init(LauncherInitContext initContext) {
        registerEvent(this::finishInit, LaunchServerFullInitEvent.class);
    }

    private void finishInit(LaunchServerFullInitEvent event) {
        this.server = event.server;
        loadConfig(server);
        executor = Executors.newCachedThreadPool((r) -> {
            Thread thread = new Thread(r, "IslandModule");
            thread.setDaemon(true);
            return thread;
        });

        server.authHookManager.setProfileHook.registerHook((response, client) -> {
            try {
                 onSetProfile(response, client);
            } catch (Exception e) {
                LogHelper.error(e);
            }
            return false; // Don't interrupt the auth flow
        });

        LogHelper.info("IslandModule initialized. Enabled: %s", config.enabled);
        if (config.enabled) {
            LogHelper.debug("Allowed profiles: %s", config.profiles);
            validateConfig();
        }
    }

    private void validateConfig() {
        try {
            URL url = new URL(config.apiUrl);
            String host = url.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host) || "[::1]".equals(host);
            if (!"https".equalsIgnoreCase(url.getProtocol()) && !loopback) {
                LogHelper.warning("IslandModule: api_url %s is not HTTPS, the api_key is sent in clear text", config.apiUrl);
            }
        } catch (Exception e) {
            LogHelper.error("IslandModule: invalid api_url %s: %s", config.apiUrl, e.getMessage());
        }
        if (config.apiKey == null || config.apiKey.isEmpty() || "your_secret_token_here".equals(config.apiKey)) {
            LogHelper.warning("IslandModule: api_key is not configured");
        }
    }

    private void loadConfig(LaunchServer server) {
        // Use modulesConfigManager which is available in LauncherModule
        Path configPath = modulesConfigManager.getModuleConfig(MODULE_NAME);
        try {
            if (!Files.exists(configPath)) {
                config = new IslandConfig();
                // Create parent directories if they don't exist
                if(configPath.getParent() != null) Files.createDirectories(configPath.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
                     gson.toJson(config, writer);
                }
            } else {
                try (BufferedReader reader = Files.newBufferedReader(configPath)) {
                    config = gson.fromJson(reader, IslandConfig.class);
                }
                if (config == null) { // empty file
                    LogHelper.warning("IslandModule: config %s is empty, using defaults", configPath);
                    config = new IslandConfig();
                }
            }
        } catch (IOException e) {
            LogHelper.error(e);
            config = new IslandConfig(); // Fallback
        }
    }

    private void onSetProfile(SetProfileResponse response, Client client) {
        if (!config.enabled) {
            return;
        }
        
        // Find profile by name from response
        ClientProfile profile = null;
        for (ClientProfile p : server.getProfiles()) {
            if (p.getTitle().equals(response.client)) {
                profile = p;
                break;
            }
        }

        if(profile == null) {
            LogHelper.warning("Client profile %s not found for user %s", response.client, client.username);
            return;
        }
        
        // Check if the profile UUID is in the allowed list
        String profileUUID = profile.getUUID().toString();
        if (config.profiles == null || !config.profiles.contains(profileUUID)) {
            LogHelper.debug("Profile %s (UUID: %s) is not in allowed list. Allowed: %s", profile.getTitle(), profileUUID, config.profiles);
            return;
        }

        String apiUrl = config.apiUrl;
        String username = client.username;
        UUID uuid = client.uuid;
        if (username == null || username.isEmpty() || uuid == null) {
            LogHelper.warning("IslandModule: cannot start an island for an unauthorized client");
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = recentStarts.put(username + "|" + profileUUID, now);
        if (previous != null && now - previous < DEDUPE_MILLIS) {
            LogHelper.debug("Island start for %s on %s was requested %d ms ago, skipping", username, profile.getTitle(), now - previous);
            return;
        }
        if (recentStarts.size() > 10_000) {
            recentStarts.values().removeIf(t -> now - t >= DEDUPE_MILLIS);
        }
        
        final String targetUrl = apiUrl;
        final String finalUsername = username;
        final UUID finalUuid = uuid;
        
        LogHelper.debug("Sending island start request for user %s (UUID: %s) on profile %s", username, uuid, profile.getTitle());

        // Send request asynchronously
        executor.submit(() -> sendApiRequest(targetUrl, finalUsername, finalUuid));
    }

    private void sendApiRequest(String urlString, String username, UUID uuid) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Launcher-Token", config.apiKey);
            conn.setConnectTimeout(config.apiTimeoutMs);
            conn.setReadTimeout(config.apiTimeoutMs);
            conn.setDoOutput(true);

            // Prepare JSON body. UUID without dashes as requested (though standard is usually with dashes, user logs imply no dashes for DB, but let's stick to user request "uuid-гравця-без-дефісів-або-з-ними" - user said "without or with", but logs showed "011b1b61...". 
            // The user's provided code had `.replace("-", "")`. I will keep that logic as it seems safer for external systems that might not handle dashes well if they expect raw hex.
            JsonObject body = new JsonObject();
            body.addProperty("username", username);
            body.addProperty("uuid", uuid.toString().replace("-", ""));
            String jsonInputString = body.toString(); // properly escaped, unlike string formatting

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                LogHelper.debug("Island API request successful: %d", responseCode);
            } else {
                LogHelper.warning("Island API request for %s failed: HTTP %d", username, responseCode);
            }

        } catch (Exception e) {
            LogHelper.warning("Failed to send request to Island API for %s: %s", username, e.getMessage());
        } finally {
            if(conn != null) {
                conn.disconnect();
            }
        }
    }
}
