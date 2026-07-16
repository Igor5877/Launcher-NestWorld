package pro.gravit.launcher.gui.scenes.servermenu;

import javafx.event.EventHandler;
import javafx.scene.control.ButtonBase;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.components.ServerButton;
import pro.gravit.launcher.gui.components.UserBlock;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.scenes.AbstractScene;
import pro.gravit.launcher.gui.scenes.interfaces.SceneSupportUserBlock;
import pro.gravit.launcher.runtime.client.ServerPinger;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.utils.helper.CommonHelper;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ServerMenuScene extends AbstractScene implements SceneSupportUserBlock {
    private List<ClientProfile> lastProfiles;
    private UserBlock userBlock;

    public ServerMenuScene(JavaFXApplication application) {
        super("scenes/servermenu/servermenu.fxml", application);
    }

    @Override
    public void doInit() {
        this.userBlock = new UserBlock(layout, new SceneAccessor());
        LookupHelper.<ButtonBase>lookup(layout, "#navServers").getStyleClass().add("nav-item-active");
        LookupHelper.<ButtonBase>lookup(layout, "#navSettings").setOnAction((e) -> {
            try {
                switchScene(application.gui.settingsScene);
                application.gui.settingsScene.reset();
            } catch (Exception exception) {
                errorHandle(exception);
            }
        });

        ScrollPane scrollPane = LookupHelper.lookup(layout, "#servers");
        reset();
        isResetOnShow = true;
    }

    static class ServerButtonCache {
        public ServerButton serverButton;
        public int position;
    }

    @Override
    public void reset() {
        pro.gravit.launcher.gui.utils.DiscordPresenceBridge.updateServerMenuStage();
        if (lastProfiles == application.profilesService.getProfiles()) return;
        lastProfiles = application.profilesService.getProfiles();
        Map<ClientProfile, ServerButtonCache> serverButtonCacheMap = new LinkedHashMap<>();
        
        List<ClientProfile> profiles = new ArrayList<>(lastProfiles);
        profiles.sort(Comparator.comparingInt(ClientProfile::getSortIndex).thenComparing(ClientProfile::getTitle));
        int position = 0;
        for (ClientProfile profile : profiles) {
            ServerButtonCache cache = new ServerButtonCache();
            cache.serverButton = ServerButton.createServerButton(application, profile);
            cache.position = position;
            serverButtonCacheMap.put(profile, cache);
            position++;
        }
        ScrollPane scrollPane = LookupHelper.lookup(layout, "#servers");
        VBox serverList = (VBox) scrollPane.getContent();
        serverList.setSpacing(20);
        serverList.getChildren().clear();
        application.pingService.clear();

        LookupHelper.<Label>lookup(layout, "#statsServers").setText(
                MessageFormat.format(application.getTranslation("runtime.scenes.servermenu.statsServers"), profiles.size()));
        Label statsOnlineLabel = LookupHelper.lookup(layout, "#statsOnline");
        statsOnlineLabel.setText(MessageFormat.format(application.getTranslation("runtime.scenes.servermenu.statsOnline"), 0));
        AtomicLong totalOnline = new AtomicLong(0);
        for (ClientProfile profile : lastProfiles) {
            for (ClientProfile.ServerProfile serverProfile : profile.getServers()) {
                application.pingService.getPingReport(serverProfile.name).thenAccept((report) -> {
                    if (report == null) return;
                    long total = totalOnline.addAndGet(report.playersOnline);
                    contextHelper.runInFxThread(() -> statsOnlineLabel.setText(
                            MessageFormat.format(application.getTranslation("runtime.scenes.servermenu.statsOnline"), total)));
                });
            }
        }

        serverButtonCacheMap.forEach((profile, serverButtonCache) -> {
            EventHandler<? super MouseEvent> handle = (event) -> {
                if (!event.getButton().equals(MouseButton.PRIMARY)) return;
                changeServer(profile);
                try {
                    switchScene(application.gui.serverInfoScene);
                    application.gui.serverInfoScene.reset();
                } catch (Exception e) {
                    errorHandle(e);
                }
            };
            serverButtonCache.serverButton.addTo(serverList, serverButtonCache.position);
            serverButtonCache.serverButton.setOnMouseClicked(handle);
        });
        CommonHelper.newThread("ServerPinger", true, () -> {
            for (ClientProfile profile : lastProfiles) {
                for (ClientProfile.ServerProfile serverProfile : profile.getServers()) {
                    if (!serverProfile.socketPing || serverProfile.serverAddress == null) continue;
                    try {
                        ServerPinger pinger = new ServerPinger(serverProfile, profile.getVersion());
                        long pingStart = System.currentTimeMillis();
                        ServerPinger.Result result = pinger.ping();
                        long pingMs = System.currentTimeMillis() - pingStart;
                        contextHelper.runInFxThread(
                                () -> application.pingService.addReport(serverProfile.name, result, pingMs));
                    } catch (IOException ignored) {
                    }
                }
            }
        }).start();
        userBlock.reset();
    }

    @Override
    public UserBlock getUserBlock() {
        return userBlock;
    }

    @Override
    public String getName() {
        return "serverMenu";
    }

    private void changeServer(ClientProfile profile) {
        application.profilesService.setProfile(profile);
        application.runtimeSettings.lastProfile = profile.getUUID();
    }
}
