package pro.gravit.launcher.gui.scenes.serverinfo;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import pro.gravit.launcher.gui.JavaFXApplication;

import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import pro.gravit.launcher.gui.components.ToggleSwitch;
import pro.gravit.launcher.gui.components.UserBlock;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.scenes.AbstractScene;
import pro.gravit.launcher.gui.scenes.interfaces.SceneSupportUserBlock;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.optional.OptionalFile;
import pro.gravit.launcher.base.profiles.optional.OptionalView;
import pro.gravit.utils.helper.*;

public class ServerInfoScene extends AbstractScene implements SceneSupportUserBlock {
    // Вертикальні кропи для вузької панелі прев'ю (щоб не розтягувало горизонтальні картинки списку серверів)
    private static final String SERVER_BUTTON_DEFAULT_IMAGE = "images/servers/Info/example.png";
    private static final String SERVER_BUTTON_CUSTOM_IMAGE = "images/servers/Info/%s.png";
    // Горизонтальні картинки зі списку серверів - запасний варіант, поки вертикальні кропи не додані
    private static final String SERVER_BUTTON_DEFAULT_IMAGE_FALLBACK = "images/servers/example.png";
    private static final String SERVER_BUTTON_CUSTOM_IMAGE_FALLBACK = "images/servers/%s.png";
    private UserBlock userBlock;
    private final Map<OptionalFile, ToggleSwitch> modToggles = new HashMap<>();

    public ServerInfoScene(JavaFXApplication application) {
        super("scenes/serverinfo/serverinfo.fxml", application);
    }

    @Override
    protected void doInit() {
        this.userBlock = new UserBlock(layout, new SceneAccessor());

        LookupHelper.<ButtonBase>lookup(layout, "#back").setOnAction((e) -> goBack());
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#footerBack").ifPresent(b -> b.setOnAction((e) -> goBack()));

        // Settings and other buttons in the header
        LookupHelper.<ButtonBase>lookup(header, "#controls", "#settings").setOnAction((e) -> openSettings());
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#tabSettingsBtn").ifPresent(b -> b.setOnAction((e) -> openSettings()));
        initInfoTabs();

        // Бокова навігація
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#navServers").ifPresent(b -> b.setOnAction((e) -> {
            try {
                switchScene(application.gui.serverMenuScene);
            } catch (Exception exception) {
                errorHandle(exception);
            }
        }));
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#navSettings").ifPresent(b -> b.setOnAction((e) -> openSettings()));
        reset();
    }

    private void goBack() {
        try {
            switchScene(application.gui.serverMenuScene);
        } catch (Exception exception) {
            errorHandle(exception);
        }
    }

    private void openSettings() {
        try {
            switchScene(application.gui.settingsScene);
            application.gui.settingsScene.reset();
        } catch (Exception exception) {
            errorHandle(exception);
        }
    }

    private void initInfoTabs() {
        Region descPane = LookupHelper.lookup(layout, "#serverDescriptionPane");
        Region modsPane = LookupHelper.lookup(layout, "#tabMods");
        ButtonBase descBtn = LookupHelper.lookup(layout, "#tabDescBtn");
        ButtonBase modsBtn = LookupHelper.lookup(layout, "#tabModsBtn");

        descBtn.setOnAction((e) -> selectInfoTab(descPane, descBtn, modsPane, modsBtn));
        modsBtn.setOnAction((e) -> selectInfoTab(modsPane, modsBtn, descPane, descBtn));
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#savepanel", "#clientSettings")
                    .ifPresent(b -> b.setOnAction((e) -> selectInfoTab(modsPane, modsBtn, descPane, descBtn)));
    }

    private void selectInfoTab(Region show, ButtonBase showBtn, Region hide, ButtonBase hideBtn) {
        show.setVisible(true);
        show.setManaged(true);
        hide.setVisible(false);
        hide.setManaged(false);
        if (!showBtn.getStyleClass().contains("settings-tab-active")) {
            showBtn.getStyleClass().add("settings-tab-active");
        }
        hideBtn.getStyleClass().remove("settings-tab-active");
        if (show.getId() != null && show.getId().equals("tabMods")) {
            populateMods(application.profilesService.getProfile());
        }
    }

    private void populateMods(ClientProfile profile) {
        ScrollPane modsScroll = LookupHelper.lookup(layout, "#tabMods");
        VBox modsList = (VBox) modsScroll.getContent();
        modsList.getChildren().clear();
        modToggles.clear();
        if (profile == null) return;
        OptionalView view = application.profilesService.getOptionalView(profile);
        if (view == null) return;

        Map<String, List<OptionalFile>> categories = new LinkedHashMap<>();
        List<OptionalFile> files = new ArrayList<>(view.all);
        files.sort(Comparator.comparing((OptionalFile f) -> f.name, String.CASE_INSENSITIVE_ORDER));
        for (OptionalFile file : files) {
            if (!file.visible) continue;
            String category = file.category == null ? "GLOBAL" : file.category;
            categories.computeIfAbsent(category, (k) -> new ArrayList<>()).add(file);
        }

        for (Map.Entry<String, List<OptionalFile>> entry : categories.entrySet()) {
            Label categoryHeader = new Label(application.getTranslation(
                    "runtime.scenes.options.tabs." + entry.getKey(), entry.getKey()));
            categoryHeader.getStyleClass().add("mod-category-header");
            modsList.getChildren().add(categoryHeader);

            for (OptionalFile file : entry.getValue()) {
                HBox row = new HBox(10.0);
                row.getStyleClass().add("mod-row");
                row.setAlignment(Pos.CENTER_LEFT);
                VBox.setMargin(row, new Insets(0, 0, 0, 20.0 * Math.max(0, file.subTreeLevel - 1)));

                Label icon = new Label("🧩");
                icon.getStyleClass().add("mod-icon");

                VBox texts = new VBox(1.0);
                Label name = new Label(file.name);
                name.getStyleClass().add("mod-name");
                texts.getChildren().add(name);
                if (file.info != null && !file.info.isBlank()) {
                    Label description = new Label(file.info);
                    description.getStyleClass().add("mod-description");
                    description.setWrapText(true);
                    texts.getChildren().add(description);
                }
                HBox.setHgrow(texts, Priority.ALWAYS);

                Label badge = new Label(application.getTranslation("runtime.scenes.serverinfo.modOptional"));
                badge.getStyleClass().add("mod-badge");

                ToggleSwitch toggle = new ToggleSwitch();
                toggle.setSelectedSilently(view.isEnabled(file));
                toggle.setOnToggle((value) -> {
                    if (value) {
                        view.enable(file, true, this::syncModToggle);
                    } else {
                        view.disable(file, this::syncModToggle);
                    }
                    application.profilesService.setOptionalView(profile, view);
                });
                modToggles.put(file, toggle);

                row.getChildren().addAll(icon, texts, badge, toggle);
                modsList.getChildren().add(row);
            }
        }
    }

    private void syncModToggle(OptionalFile file, Boolean value) {
        ToggleSwitch toggle = modToggles.get(file);
        if (toggle != null) {
            contextHelper.runInFxThread(() -> toggle.setSelectedSilently(value));
        }
    }

    @Override
    public void reset() {
        ClientProfile profile = application.profilesService.getProfile();
        LookupHelper.<Label>lookupIfPossible(layout, "#serverName").ifPresent((e) -> e.setText(profile.getTitle()));
        LookupHelper.<ScrollPane>lookupIfPossible(layout, "#serverDescriptionPane").ifPresent((e) -> {
            var label = (Label) e.getContent();
            label.setText(profile.getInfo());
        });

        // Buttons are now in savepanel, which is in the main layout
        LookupHelper.<Button>lookupIfPossible(layout, "#savepanel", "#save").ifPresent(
                (e) -> e.setOnAction((event) -> runClient()));

        LookupHelper.lookupIfPossible(layout, "#serverLogo").ifPresent(node -> {
            Region serverLogo = (Region) node;
            URL logo = application.tryResource(String.format(SERVER_BUTTON_CUSTOM_IMAGE, profile.getUUID().toString()));
            if (logo == null) {
                logo = application.tryResource(String.format(SERVER_BUTTON_CUSTOM_IMAGE_FALLBACK, profile.getUUID().toString()));
            }
            if (logo == null) {
                logo = application.tryResource(SERVER_BUTTON_DEFAULT_IMAGE);
            }
            if (logo == null) {
                logo = application.tryResource(SERVER_BUTTON_DEFAULT_IMAGE_FALLBACK);
            }
            if (logo != null) {
                serverLogo.setBackground(new Background(new BackgroundImage(new Image(logo.toString()),
                        BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                        BackgroundPosition.CENTER, new BackgroundSize(0.0, 0.0, true, true, false, true))));
            }
        });

        updateStats(profile);
        this.userBlock.reset();
    }

    private void updateStats(ClientProfile profile) {
        AtomicLong totalOnline = new AtomicLong(0);
        AtomicLong totalMax = new AtomicLong(0);
        AtomicLong pingMs = new AtomicLong(-1);
        for (ClientProfile.ServerProfile serverProfile : profile.getServers()) {
            application.pingService.getPingReport(serverProfile.name).thenAccept((report) -> {
                if (report == null) return;
                totalOnline.addAndGet(report.playersOnline);
                totalMax.addAndGet(report.maxPlayers);
                pingMs.compareAndSet(-1, report.pingMs);
                contextHelper.runInFxThread(() -> applyStats(totalOnline.get(), totalMax.get(), pingMs.get(), report.tps));
            });
        }
    }

    private void applyStats(long online, long max, long pingMs, Double tps) {
        LookupHelper.<Label>lookupIfPossible(layout, "#headerOnlineCount").ifPresent((e) -> e.setText(String.valueOf(online)));
        showRow("#headerOnlineBadge", true);

        LookupHelper.<Label>lookupIfPossible(layout, "#statPlayersValue")
                    .ifPresent((e) -> e.setText(online + "/" + max));
        showRow("#statPlayersRow", true);

        if (pingMs >= 0) {
            LookupHelper.<Label>lookupIfPossible(layout, "#statPingValue")
                        .ifPresent((e) -> e.setText(pingMs + "ms"));
            showRow("#statPingRow", true);
        }

        if (tps != null) {
            LookupHelper.<Label>lookupIfPossible(layout, "#statTpsValue")
                        .ifPresent((e) -> e.setText("%.1f".formatted(tps)));
            showRow("#statTpsRow", true);
        }
    }

    private void showRow(String id, boolean visible) {
        LookupHelper.<Region>lookupIfPossible(layout, id).ifPresent((e) -> {
            e.setVisible(visible);
            e.setManaged(visible);
        });
    }

    private void runClient() {
        application.launchService.launchClient().thenAccept((clientInstance -> {
            if (application.runtimeSettings.globalSettings.debugAllClients || clientInstance.getSettings().debug) {
                contextHelper.runInFxThread(() -> {
                    try {
                        switchScene(application.gui.debugScene);
                        application.gui.debugScene.onClientInstance(clientInstance);
                    } catch (Exception ex) {
                        errorHandle(ex);
                    }
                });
            } else {
                clientInstance.start();
                clientInstance.getOnWriteParamsFuture().thenAccept((ok) -> {
                    LogHelper.info("Params write successful. Exit...");
                    Platform.exit();
                }).exceptionally((ex) -> {
                    contextHelper.runInFxThread(() -> errorHandle(ex));
                    return null;
                });
            }
        })).exceptionally((ex) -> {
            contextHelper.runInFxThread(() -> errorHandle(ex));
            return null;
        });
    }

    @Override
    public String getName() {
        return "serverinfo";
    }

    @Override
    public UserBlock getUserBlock() {
        return userBlock;
    }
}
