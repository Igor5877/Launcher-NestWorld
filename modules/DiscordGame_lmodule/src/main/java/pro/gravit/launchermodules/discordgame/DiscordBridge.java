package pro.gravit.launchermodules.discordgame;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.GameSDKException;
import de.jcm.discordgamesdk.LogLevel;
import de.jcm.discordgamesdk.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.gravit.launcher.client.ClientLauncherEntryPoint;
import pro.gravit.launcher.runtime.LauncherEngine;
import pro.gravit.launchermodules.discordgame.event.DiscordInitEvent;
import pro.gravit.utils.helper.CommonHelper;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.JVMHelper;
import pro.gravit.utils.helper.UnpackHelper;

import java.io.IOException;
import java.nio.file.Path;

public class DiscordBridge {

    private static final Logger logger = LoggerFactory.getLogger(DiscordBridge.class);

    // Singleton сервісу активності — реалізований у модулі, оскільки його немає в LauncherClient
    public static final DiscordActivityService activityService = new DiscordActivityService();

    private static Thread thread;
    private static Core core;
    private static CreateParams params;
    private static Activity activity;

    public static void init(long appId, boolean isClient) throws IOException {
        if (JVMHelper.ARCH_TYPE == JVMHelper.ARCH.ARM32 || JVMHelper.ARCH_TYPE == JVMHelper.ARCH.ARM64) {
            logger.info("Cannot initialize Discord Game SDK: unsupported architecture");
            return;
        }

        params = new CreateParams();
        params.setClientID(appId);
        // CreateParams.getDefaultFlags() | 1 — дозволяє запуск без відкритого Discord
        params.setFlags(CreateParams.getDefaultFlags() | 1);

        try {
            core = new Core(params);
            core.setLogHook(LogLevel.VERBOSE, (level, s) -> {
                switch (level) {
                    case ERROR -> logger.error("{}", s);
                    case WARN  -> logger.warn("{}", s);
                    case INFO, VERBOSE -> logger.info("{}", s);
                    case DEBUG -> logger.debug("{}", s);
                }
            });

            activity = new Activity();
            activityService.applyToActivity(activity);
            activityService.resetStartTime();
            core.activityManager().updateActivity(activity);

        } catch (GameSDKException e) {
            logger.info("Failed to start Discord Game SDK — Discord app is probably not running");
            close();
            return;
        }

        if (isClient) {
            ClientLauncherEntryPoint.modulesManager.invokeEvent(new DiscordInitEvent(core));
        } else {
            LauncherEngine.modulesManager.invokeEvent(new DiscordInitEvent(core));
        }

        logger.debug("Discord Game SDK initialized. Application ID: {}", appId);
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
        }
        if (core != null) {
            try {
                core.close();
            } catch (Throwable e) {
                logger.warn("DiscordGame core object not closed correctly. Discord is down?");
            }
        }
        core = null;
        activity = null;
    }

    @SuppressWarnings("unused")
    private static String loadNative(Path baseDir, String name, String osFolder, String arch) throws IOException {
        String nativeLib = JVMHelper.NATIVE_PREFIX.concat(name).concat(JVMHelper.NATIVE_EXTENSION);
        Path pathToLib = baseDir.resolve(nativeLib);
        String libraryPath = String.join(IOHelper.CROSS_SEPARATOR, "native", osFolder, arch, nativeLib);
        UnpackHelper.unpack(IOHelper.getResourceURL(libraryPath), pathToLib);
        System.load(pathToLib.toAbsolutePath().toString());
        return pathToLib.toAbsolutePath().toString();
    }
}
