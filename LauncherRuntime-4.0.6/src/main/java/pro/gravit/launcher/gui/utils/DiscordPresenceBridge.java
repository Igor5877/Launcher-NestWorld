package pro.gravit.launcher.gui.utils;

import pro.gravit.utils.helper.LogHelper;

import java.lang.reflect.Method;

/**
 * М'який (reflection-based) міст до опціонального DiscordGame_lmodule: рантайм не має
 * жорсткої залежності на класи модуля, тож усе працює однаково, і якщо модуль не встановлений.
 */
public class DiscordPresenceBridge {
    private static final String SERVICE_CLASS = "pro.gravit.launcher.client.api.DiscordGameService";
    private static volatile boolean unavailable = false;

    private DiscordPresenceBridge() {
    }

    public static void updateServerSelectStage(String serverName) {
        invoke("updateServerSelectStage", new Class<?>[]{String.class}, new Object[]{serverName});
    }

    public static void updateServerMenuStage() {
        invoke("updateServerMenuStage", new Class<?>[0], new Object[0]);
    }

    /**
     * Пауза власного Discord-з'єднання лаунчера (наприклад, поки в debug-сцені лишається
     * відкритим вікно лаунчера паралельно з процесом гри, щоб не було двох конкуруючих
     * з'єднань з тим самим appId).
     */
    public static void pausePresence() {
        invoke("pausePresence", new Class<?>[0], new Object[0]);
    }

    public static void resumePresence() {
        invoke("resumePresence", new Class<?>[0], new Object[0]);
    }

    private static void invoke(String methodName, Class<?>[] paramTypes, Object[] args) {
        if (unavailable) return;
        try {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS);
            Object activityService = serviceClass.getMethod("getDiscordActivityService").invoke(null);
            if (activityService == null) return;
            Method method = activityService.getClass().getMethod(methodName, paramTypes);
            method.invoke(activityService, args);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            unavailable = true;
        } catch (Throwable e) {
            LogHelper.dev("DiscordPresenceBridge: failed to call %s: %s", methodName, e.toString());
        }
    }
}
