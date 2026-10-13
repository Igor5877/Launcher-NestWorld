package pro.gravit.launchserver.auth.texture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launcher.base.profiles.Texture;
import pro.gravit.utils.helper.CommonHelper;
import pro.gravit.utils.helper.IOHelper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

public final class RequestTextureProvider extends TextureProvider {
    private static final Logger logger = LogManager.getLogger();

    // Instance
    public String skinURL;
    public String cloakURL;

    public String skinLocalPath;
    public String cloakLocalPath;

    /**
     * Seconds a successful lookup is served from memory without contacting the skin API.
     * 0 (or less) disables the cache completely: every profile build requests the API again.
     */
    public long cacheTtlSeconds = 600;
    /**
     * Seconds an answer "this player has no skin/cape" (HTTP 404) is remembered.
     */
    public long notFoundTtlSeconds = 120;
    /**
     * Seconds the last successful texture may still be served while the API fails (5xx, 429, timeout, bad image).
     */
    public long staleTtlSeconds = 86400;
    /**
     * Seconds to leave the API alone after a failed request for the same player.
     */
    public long failureBackoffSeconds = 30;
    /**
     * Upper bound of cached players; the least recently used ones are dropped first.
     */
    public int cacheMaxEntries = 5000;

    private transient final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private transient final Map<String, CompletableFuture<Void>> inflight = new ConcurrentHashMap<>();
    transient LongSupplier clock = System::currentTimeMillis;

    public RequestTextureProvider() {
    }

    public RequestTextureProvider(String skinURL, String cloakURL) {
        this.skinURL = skinURL;
        this.cloakURL = cloakURL;
    }

    private static Texture getTexture(String url, boolean cloak) throws IOException {
        try {
            return new Texture(url, cloak, null);
        } catch (FileNotFoundException ignored) {
            return null; // Simply not found
        }
    }

    private static Texture getTexture(String url, Path local, boolean cloak) throws IOException {
        try {
            return new Texture(url, local, cloak, null);
        } catch (FileNotFoundException ignored) {
            return null; // Simply not found
        }
    }

    public static String getTextureURL(String url, UUID uuid, String username, String client) {
        return CommonHelper.replace(url, "username", IOHelper.urlEncode(username),
                "uuid", IOHelper.urlEncode(uuid.toString()), "hash", IOHelper.urlEncode(Launcher.toHash(uuid)),
                "client", IOHelper.urlEncode(client == null ? "unknown" : client));
    }

    @Override
    public void close() {
        cache.clear();
        inflight.clear();
    }

    @Override
    public Texture getCloakTexture(UUID uuid, String username, String client) throws IOException {
        String textureUrl = getTextureURL(cloakURL, uuid, username, client);
        if (cloakLocalPath == null) {
            return getTexture(textureUrl, true);
        } else {
            String path = getTextureURL(cloakLocalPath, uuid, username, client);
            return getTexture(textureUrl, Paths.get(path), true);
        }
    }

    @Override
    public Texture getSkinTexture(UUID uuid, String username, String client) throws IOException {
        String textureUrl = getTextureURL(skinURL, uuid, username, client);
        if (skinLocalPath == null) {
            return getTexture(textureUrl, false);
        } else {
            String path = getTextureURL(skinLocalPath, uuid, username, client);
            return getTexture(textureUrl, Paths.get(path), false);
        }
    }

    /**
     * Skin and cape lookups hit an external HTTP API and download the image, so doing it on every profile build
     * (auth, checkServer, profileBy*) floods the API and, as soon as it throttles us, silently drops the skin.
     * The answer is therefore cached per player, a failure keeps serving the last good texture and failed
     * lookups are logged.
     */
    @Override
    public Map<String, Texture> getAssets(UUID uuid, String username, String client) {
        long ttlMs = TimeUnit.SECONDS.toMillis(cacheTtlSeconds);
        Map<String, Texture> result = new HashMap<>();
        if (ttlMs <= 0) {
            long now = clock.getAsLong();
            put(result, "SKIN", refresh("skin", false, Slot.EMPTY, uuid, username, client, now).texture());
            put(result, "CAPE", refresh("cape", true, Slot.EMPTY, uuid, username, client, now).texture());
            return result;
        }
        String key = cacheKey(uuid, username, client);
        Entry entry = cache.computeIfAbsent(key, k -> new Entry());
        long now = clock.getAsLong();
        entry.touched = now;
        if (needsRefresh(entry.skin, now) || needsRefresh(entry.cape, now)) {
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> other = inflight.putIfAbsent(key, mine);
            if (other == null) {
                try {
                    now = clock.getAsLong();
                    if (needsRefresh(entry.skin, now)) {
                        entry.skin = refresh("skin", false, entry.skin, uuid, username, client, now);
                    }
                    if (needsRefresh(entry.cape, now)) {
                        entry.cape = refresh("cape", true, entry.cape, uuid, username, client, now);
                    }
                } finally {
                    inflight.remove(key);
                    mine.complete(null);
                }
                evictIfNeeded();
            } else {
                // Someone else is already asking the API for this player: wait for that answer instead of repeating it
                try {
                    other.get(2L * IOHelper.HTTP_TIMEOUT + 1000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException ignored) {
                    // serve whatever is cached
                }
            }
        }
        now = clock.getAsLong();
        put(result, "SKIN", usable(entry.skin, now));
        put(result, "CAPE", usable(entry.cape, now));
        return result;
    }

    private static void put(Map<String, Texture> map, String name, Texture texture) {
        if (texture != null) {
            map.put(name, texture);
        }
    }

    private Slot refresh(String what, boolean cloak, Slot old, UUID uuid, String username, String client, long now) {
        try {
            Texture texture = cloak ? getCloakTexture(uuid, username, client) : getSkinTexture(uuid, username, client);
            return new Slot(texture, now, 0); // null texture = the API said there is none
        } catch (IOException e) {
            logger.warn("Failed to get {} texture of '{}' ({}). {}", what, username, e,
                    old.texture() != null ? "Keeping the last known one" : "No cached texture to fall back to");
            return new Slot(old.texture(), old.checkedAt(), now);
        }
    }

    private boolean needsRefresh(Slot slot, long now) {
        if (slot.checkedAt() != 0) {
            long ttl = TimeUnit.SECONDS.toMillis(slot.texture() != null ? cacheTtlSeconds : notFoundTtlSeconds);
            if (now - slot.checkedAt() < ttl) {
                return false; // fresh
            }
        }
        // recently failed: do not hammer the API
        return slot.failedAt() == 0 || now - slot.failedAt() >= TimeUnit.SECONDS.toMillis(failureBackoffSeconds);
    }

    private Texture usable(Slot slot, long now) {
        if (slot.texture() == null || slot.checkedAt() == 0) {
            return null;
        }
        long maxAge = TimeUnit.SECONDS.toMillis(Math.max(staleTtlSeconds, cacheTtlSeconds));
        return now - slot.checkedAt() < maxAge ? slot.texture() : null;
    }

    private String cacheKey(UUID uuid, String username, String client) {
        // Only parts that actually end up in the URL may split the cache; "client" differs between call sites
        StringBuilder key = new StringBuilder(String.valueOf(username));
        if (usesPlaceholder("uuid") || usesPlaceholder("hash")) {
            key.append('|').append(uuid);
        }
        if (usesPlaceholder("client")) {
            key.append('|').append(client);
        }
        return key.toString();
    }

    private boolean usesPlaceholder(String name) {
        String token = "%" + name + "%";
        return contains(skinURL, token) || contains(cloakURL, token)
                || contains(skinLocalPath, token) || contains(cloakLocalPath, token);
    }

    private static boolean contains(String value, String token) {
        return value != null && value.contains(token);
    }

    private void evictIfNeeded() {
        int max = cacheMaxEntries;
        if (max <= 0 || cache.size() <= max) {
            return;
        }
        synchronized (cache) {
            if (cache.size() <= max) {
                return;
            }
            var entries = new ArrayList<>(cache.entrySet());
            entries.sort(Comparator.comparingLong(e -> e.getValue().touched));
            int toRemove = cache.size() - (int) (max * 0.9);
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                if (!inflight.containsKey(entries.get(i).getKey())) {
                    cache.remove(entries.get(i).getKey());
                }
            }
        }
    }

    /**
     * @param texture   last known texture; null if the API answered "not found" (or never answered)
     * @param checkedAt when the API last gave a definitive answer (texture or 404), 0 = never
     * @param failedAt  when the last request failed, 0 = last request succeeded
     */
    private record Slot(Texture texture, long checkedAt, long failedAt) {
        static final Slot EMPTY = new Slot(null, 0, 0);
    }

    private static final class Entry {
        volatile Slot skin = Slot.EMPTY;
        volatile Slot cape = Slot.EMPTY;
        volatile long touched;
    }
}
