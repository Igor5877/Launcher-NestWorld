package pro.gravit.launchermodules.discordgame;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.activity.Activity;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.PlayerProfile;
import pro.gravit.launcher.client.ClientParams;
import pro.gravit.utils.helper.JVMHelper;

import java.time.Instant;

/**
 * Керує поточним станом Discord Rich Presence та підтримує всі плейсхолдери з README:
 * %uuid%, %profileVersion%, %profileName%, %profileUUID%, %profileHash%,
 * %username%, %launcherVersion%, %javaVersion%, %javaBits%, %os%, %avatarUrl%
 *
 * Клас відсутній у локальному LauncherClient, тому реалізований всередині модуля.
 */
public class DiscordActivityService {

    private ScopeConfig currentScope;
    private long startTimeSec;

    // Поточні значення плейсхолдерів
    private String uuid          = "";
    private String username      = "";
    private String profileName   = "";
    private String profileVersion = "";
    private String profileUUID   = "";
    private String profileHash   = "";
    private String avatarUrl     = "";

    // Статичні плейсхолдери — заповнюються один раз
    private static final String LAUNCHER_VERSION = ClientModule.version.getVersionString();
    private static final String JAVA_VERSION     = String.valueOf(JVMHelper.JVM_VERSION);
    private static final String JAVA_BITS        = String.valueOf(JVMHelper.JVM_BITS);
    private static final String OS               = getOsName();

    public DiscordActivityService() {
        this.startTimeSec = Instant.now().getEpochSecond();
    }

    public void resetStartTime() {
        this.startTimeSec = Instant.now().getEpochSecond();
    }

    // ──────────────────────────────── Оновлення стадій ────────────────────────────────

    /** Стан "на екрані логіну" — даних про гравця ще немає */
    public void updateLoginStage() {
        currentScope = ClientModule.loginScopeConfig;
        uuid = username = profileName = profileVersion = profileUUID = profileHash = avatarUrl = "";
        pushToDiscord();
    }

    /** Стан "авторизований, вибирає сервер" */
    public void updateAuthorizedStage(PlayerProfile profile) {
        currentScope = ClientModule.authorizedScopeConfig;
        if (profile != null) {
            username = orEmpty(profile.username);
            uuid     = profile.uuid != null ? profile.uuid.toString() : "";
            avatarUrl = getAvatarUrl(profile);
        }
        pushToDiscord();
    }

    /** Стан "у грі" */
    public void updateClientStage(ClientParams params) {
        currentScope = ClientModule.clientScopeConfig;
        if (params != null && params.profile != null) {
            ClientProfile p = params.profile;
            profileName    = orEmpty(p.getTitle());
            profileUUID    = p.getUUID() != null ? p.getUUID().toString() : "";
            profileHash    = profileUUID.replace("-", "");
            profileVersion = p.getVersion() != null ? p.getVersion().toString() : "";
        }
        pushToDiscord();
    }

    // ──────────────────────────────── Застосування до Activity ────────────────────────

    /**
     * Застосовує поточний scope до об'єкта {@link Activity}.
     * Викликається при ініціалізації Discord SDK в {@code DiscordBridge.init()}.
     */
    public void applyToActivity(Activity activity) {
        if (currentScope == null || activity == null) return;

        activity.setDetails(substitute(currentScope.getDetails()));
        activity.setState(substitute(currentScope.getState()));

        activity.timestamps().setStart(Instant.ofEpochSecond(startTimeSec));

        String largeKey = substitute(currentScope.getLargeImageKey());
        if (!largeKey.isEmpty()) {
            activity.assets().setLargeImage(largeKey);
        }
        String largeText = substitute(currentScope.getLargeImageText());
        if (!largeText.isEmpty()) {
            activity.assets().setLargeText(largeText);
        }
        String smallKey = substitute(currentScope.getSmallImageKey());
        if (!smallKey.isEmpty()) {
            activity.assets().setSmallImage(smallKey);
        }
        String smallText = substitute(currentScope.getSmallImageText());
        if (!smallText.isEmpty()) {
            activity.assets().setSmallText(smallText);
        }
    }

    // ──────────────────────────────── Допоміжні методи ────────────────────────────────

    /** Замінює всі плейсхолдери у тексті */
    private String substitute(String text) {
        if (text == null) return "";
        return text
                .replace("%uuid%",            uuid)
                .replace("%username%",         username)
                .replace("%profileName%",      profileName)
                .replace("%profileVersion%",   profileVersion)
                .replace("%profileUUID%",      profileUUID)
                .replace("%profileHash%",      profileHash)
                .replace("%avatarUrl%",        avatarUrl)
                .replace("%launcherVersion%",  LAUNCHER_VERSION)
                .replace("%javaVersion%",      JAVA_VERSION)
                .replace("%javaBits%",         JAVA_BITS)
                .replace("%os%",               OS);
    }

    /** Оновлює Activity в Discord, якщо SDK вже ініціалізовано */
    private void pushToDiscord() {
        Activity activity = DiscordBridge.getActivity();
        Core core = DiscordBridge.getCore();
        if (activity == null || core == null) return;

        applyToActivity(activity);
        try {
            core.activityManager().updateActivity(activity);
        } catch (Exception ignored) {
            // Discord може бути закритий
        }
    }

    private static String getOsName() {
        return switch (JVMHelper.OS_TYPE) {
            case MUSTDIE -> "Windows";
            case LINUX   -> "Linux";
            case MACOSX  -> "macOS";
        };
    }

    /** Витягує URL аватара з PlayerProfile (якщо TextureProvider налаштовано на відповідь AVATAR) */
    private static String getAvatarUrl(PlayerProfile profile) {
        if (profile.assets == null) return "";
        var tex = profile.assets.get("AVATAR");
        return tex != null && tex.url != null ? tex.url : "";
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
