package pro.gravit.launchermodules.discordgame;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.GameSDKException;
import de.jcm.discordgamesdk.LogLevel;
import de.jcm.discordgamesdk.activity.Activity;
import pro.gravit.launcher.client.api.DiscordActivityService;
import pro.gravit.launcher.runtime.LauncherEngine;
import pro.gravit.launcher.client.ClientLauncherEntryPoint;
import pro.gravit.launchermodules.discordgame.event.DiscordInitEvent;
import pro.gravit.utils.helper.CommonHelper;
import pro.gravit.utils.helper.JVMHelper;
import pro.gravit.utils.helper.LogHelper;

public class DiscordBridge {

    public static final DiscordActivityService activityService = new DiscordActivityService();
    private static Thread thread;
    private static Core core;
    private static Activity activity;

    public static void init(long appId, boolean isClient) {
        if (appId == 0L) {
            LogHelper.info("[DiscordGame] Discord Game SDK is not configured (appId is not set), skipping initialization");
            return;
        }
        if (JVMHelper.ARCH_TYPE == JVMHelper.ARCH.ARM32 || JVMHelper.ARCH_TYPE == JVMHelper.ARCH.ARM64) {
            LogHelper.info("[DiscordGame] Cannot initialize Discord Game SDK because of launcher started at unsupported system && arch");
            return;
        }
        CreateParams params = new CreateParams();
        params.setClientID(appId);
        // NO_REQUIRE_DISCORD: не блокуємо і не завершуємо застосунок, якщо Discord не запущено.
        params.setFlags(CreateParams.Flags.NO_REQUIRE_DISCORD);
        try {
            core = new Core(params);
            core.setLogHook(LogLevel.WARN, (level, s) -> {
                switch (level) {
                    case ERROR -> LogHelper.error("[DiscordGame] %s", s);
                    case WARN -> LogHelper.warning("[DiscordGame] %s", s);
                    case INFO, VERBOSE -> LogHelper.info("[DiscordGame] %s", s);
                    case DEBUG -> LogHelper.debug("[DiscordGame] %s", s);
                }
            });
            activity = new Activity();
            activityService.applyToActivity(activity);
            activityService.resetStartTime();
            core.activityManager().updateActivity(DiscordBridge.getActivity());
        } catch (GameSDKException e) {
            LogHelper.info("[DiscordGame] Failed to start Discord Game SDK (%s). Most likely the local Discord app is not running", e.getResult());
            close();
            return;
        }
        if (isClient) {
            ClientLauncherEntryPoint.modulesManager.invokeEvent(new DiscordInitEvent(core));
        } else {
            LauncherEngine.modulesManager.invokeEvent(new DiscordInitEvent(core));
        }
        LogHelper.debug("[DiscordGame] Initialized Discord Game. Application ID %d", appId);
        thread = CommonHelper.newThread("DiscordGameBridge callbacks", true, new DiscordUpdateTask(core));
        thread.start();
    }

    public static Core getCore() {
        return core;
    }

    public static Activity getActivity() {
        return activity;
    }

    public static void close() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        if (core != null) {
            try {
                core.close();
            } catch (Throwable e) {
                LogHelper.warning("[DiscordGame] core object not closed correctly. Discord is down?");
            }
            core = null;
        }
    }
}
