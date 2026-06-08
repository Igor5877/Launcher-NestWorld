package pro.gravit.launchermodules.discordgame;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.activity.Activity;
import pro.gravit.launcher.base.profiles.PlayerProfile;
import pro.gravit.launcher.client.ClientParams;

import java.time.Instant;

/**
 * Керує поточним станом Discord Rich Presence (логін / авторизований / в грі).
 * Клас відсутній в локальному LauncherClient, тому реалізований всередині модуля.
 */
public class DiscordActivityService {

    private ScopeConfig currentScope;
    private long startTimeSec;

    // Поточні змінні для підстановки тексту
    private String profileName = "";
    private String username = "";

    public DiscordActivityService() {
        this.startTimeSec = Instant.now().getEpochSecond();
    }

    public void resetStartTime() {
        this.startTimeSec = Instant.now().getEpochSecond();
    }

    /** Встановлює стан "на екрані логіну" */
    public void updateLoginStage() {
        currentScope = ClientModule.loginScopeConfig;
        profileName = "";
        username = "";
        pushToDiscord();
    }

    /** Встановлює стан "авторизований, вибирає сервер" */
    public void updateAuthorizedStage(PlayerProfile profile) {
        currentScope = ClientModule.authorizedScopeConfig;
        if (profile != null) {
            username = profile.username != null ? profile.username : "";
        }
        pushToDiscord();
    }

    /** Встановлює стан "в грі" */
    public void updateClientStage(ClientParams params) {
        currentScope = ClientModule.clientScopeConfig;
        if (params != null && params.profile != null) {
            profileName = params.profile.getTitle() != null ? params.profile.getTitle() : "";
        }
        pushToDiscord();
    }

    /**
     * Застосовує поточний scope до вже існуючого об'єкта {@link Activity}.
     * Викликається при ініціалізації Discord SDK (DiscordBridge.init).
     */
    public void applyToActivity(Activity activity) {
        if (currentScope == null || activity == null) return;

        String details = substitute(currentScope.getDetails());
        String state   = substitute(currentScope.getState());

        activity.setDetails(details);
        activity.setState(state);

        activity.timestamps().setStart(Instant.ofEpochSecond(startTimeSec));

        if (currentScope.getLargeImage() != null && !currentScope.getLargeImage().isEmpty()) {
            activity.assets().setLargeImage(currentScope.getLargeImage());
        }
        if (currentScope.getLargeText() != null && !currentScope.getLargeText().isEmpty()) {
            activity.assets().setLargeText(currentScope.getLargeText());
        }
        if (currentScope.getSmallImage() != null && !currentScope.getSmallImage().isEmpty()) {
            activity.assets().setSmallImage(currentScope.getSmallImage());
        }
        if (currentScope.getSmallText() != null && !currentScope.getSmallText().isEmpty()) {
            activity.assets().setSmallText(currentScope.getSmallText());
        }
    }

    /** Замінює змінні %profileName%, %username% у тексті */
    private String substitute(String text) {
        if (text == null) return "";
        return text
                .replace("%profileName%", profileName)
                .replace("%username%", username);
    }

    /**
     * Оновлює Activity в Discord, якщо SDK вже ініціалізовано.
     */
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
}
