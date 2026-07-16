package pro.gravit.launcher.gui.scenes.settings;

import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;
import oshi.SystemInfo;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.components.UserBlock;
import pro.gravit.launcher.gui.config.DesignConstants;
import pro.gravit.launcher.gui.config.RuntimeSettings;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.scenes.AbstractScene.SceneAccessor;
import pro.gravit.launcher.gui.scenes.interfaces.SceneSupportUserBlock;
import pro.gravit.launcher.gui.scenes.settings.components.JavaSelectorComponent;
import pro.gravit.launcher.gui.scenes.settings.components.LanguageSelectorComponent;
import pro.gravit.launcher.gui.scenes.settings.components.ThemeSelectorComponent;
import pro.gravit.launcher.gui.utils.JavaFxUtils;
import pro.gravit.launcher.gui.utils.SystemMemory;
import pro.gravit.launcher.runtime.client.DirBridge;
import pro.gravit.utils.helper.JVMHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;

public class SettingsScene extends BaseSettingsScene implements SceneSupportUserBlock {

    private final static long MAX_JAVA_MEMORY_X64 = 32 * 1024;
    private final static long MAX_JAVA_MEMORY_X32 = 1536;
    private Label ramLabel;
    private Slider ramSlider;
    private RuntimeSettings.ProfileSettingsView profileSettings;
    private JavaSelectorComponent javaSelector;
    private ThemeSelectorComponent themeSelector;
    private LanguageSelectorComponent languageSelectorComponent;
    private UserBlock userBlock;
    private Pane javaTabContent;
    private Pane interfaceTabContent;
    private Region profileBar;
    private ComboBox<ClientProfile> profileCombo;
    private ClientProfile selectedProfile;

    public SettingsScene(JavaFXApplication application) {
        super("scenes/settings/settings.fxml", application);
    }

    @Override
    protected void doInit() {
        super.doInit();
        this.userBlock = new UserBlock(layout, new SceneAccessor());

        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#navSettings")
                    .ifPresent(b -> b.getStyleClass().add("nav-item-active"));
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#navServers").ifPresent(b -> b.setOnAction((e) -> {
            try {
                // Settings is reachable straight from the login screen (gear icon) before
                // any authentication happens. ServerMenuScene assumes a real session
                // (profiles list, player info) - jumping there pre-auth left the user
                // stuck in a scene that looks logged in but isn't, with no way back.
                switchScene(application.authService.isAuth() ? application.gui.serverMenuScene : application.gui.loginScene);
            } catch (Exception exception) {
                errorHandle(exception);
            }
        }));

        initTabs();
        initProfileSelector();

        ramSlider = LookupHelper.lookup(componentList, "#ramSlider");
        ramLabel = LookupHelper.lookup(componentList, "#ramLabel");
        long maxSystemMemory;
        try {
            SystemInfo systemInfo = new SystemInfo();
            maxSystemMemory = (systemInfo.getHardware().getMemory().getTotal() >> 20);
        } catch (Throwable ignored) {
            try {
                maxSystemMemory = (SystemMemory.getPhysicalMemorySize() >> 20);
            } catch (Throwable ignored1) {
                maxSystemMemory = 2048;
            }
        }
        ramSlider.setMax(Math.min(maxSystemMemory, getJavaMaxMemory()));

        ramSlider.setSnapToTicks(true);
        ramSlider.setShowTickMarks(true);
        ramSlider.setShowTickLabels(true);
        ramSlider.setMinorTickCount(1);
        ramSlider.setMajorTickUnit(1024);
        ramSlider.setBlockIncrement(1024);
        ramSlider.setLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Double object) {
                return "%.0fG".formatted(object / 1024);
            }

            @Override
            public Double fromString(String string) {
                return null;
            }
        });

        themeSelector = new ThemeSelectorComponent(application, interfaceTabContent);
        languageSelectorComponent = new LanguageSelectorComponent(application, interfaceTabContent);

        Hyperlink updateDirLink = LookupHelper.lookup(componentList, "#folder", "#path");
        String directoryUpdates = DirBridge.dirUpdates.toAbsolutePath().toString();
        updateDirLink.setText(directoryUpdates);
        if (updateDirLink.getTooltip() != null) {
            updateDirLink.getTooltip().setText(directoryUpdates);
        }
        updateDirLink.setOnAction((e) -> application.openURL(directoryUpdates));
        LookupHelper.<ButtonBase>lookup(componentList, "#changeDir").setOnAction((e) -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle(application.getTranslation("runtime.scenes.settings.dirTitle"));
            directoryChooser.setInitialDirectory(DirBridge.dir.toFile());
            File choose = directoryChooser.showDialog(application.getMainStage().getStage());
            if (choose == null) return;
            Path newDir = choose.toPath().toAbsolutePath().normalize();
            Path currentUpdates = DirBridge.dirUpdates.toAbsolutePath().normalize();
            if (newDir.startsWith(currentUpdates)) {
                errorHandle(new IOException(application.getTranslation("runtime.scenes.settings.invalidUpdatesDir")));
                return;
            }
            try {
                DirBridge.move(newDir);
            } catch (IOException ex) {
                errorHandle(ex);
                return;
            }
            application.runtimeSettings.updatesDirPath = newDir.toString();
            application.runtimeSettings.updatesDir = newDir;
            String oldDir = DirBridge.dirUpdates.toString();
            DirBridge.dirUpdates = newDir;
            if (application.profilesService.getProfiles() != null) {
                for (ClientProfile profile : application.profilesService.getProfiles()) {
                    RuntimeSettings.ProfileSettings settings = application.getProfileSettings(profile);
                    if (settings.javaPath != null && settings.javaPath.startsWith(oldDir)) {
                        settings.javaPath = newDir.toString().concat(settings.javaPath.substring(oldDir.length()));
                    }
                }
            }
            application.javaService.update();
            updateDirLink.setText(application.runtimeSettings.updatesDirPath);
        });

        reset();
    }

    private void initTabs() {
        Region tabGeneral = LookupHelper.lookup(layout, "#settingslist");
        ScrollPane tabJavaScroll = LookupHelper.lookup(layout, "#tabJava");
        ScrollPane tabInterfaceScroll = LookupHelper.lookup(layout, "#tabInterface");
        javaTabContent = (Pane) tabJavaScroll.getContent();
        interfaceTabContent = (Pane) tabInterfaceScroll.getContent();
        Region tabJava = tabJavaScroll;
        Region tabInterface = tabInterfaceScroll;
        Region tabAccount = LookupHelper.lookup(layout, "#tabAccount");
        profileBar = LookupHelper.lookup(layout, "#profileBar");
        ButtonBase tabGeneralBtn = LookupHelper.lookup(layout, "#tabGeneralBtn");
        ButtonBase tabJavaBtn = LookupHelper.lookup(layout, "#tabJavaBtn");
        ButtonBase tabInterfaceBtn = LookupHelper.lookup(layout, "#tabInterfaceBtn");
        ButtonBase tabAccountBtn = LookupHelper.lookup(layout, "#tabAccountBtn");

        Region[] panes = {tabGeneral, tabJava, tabInterface, tabAccount};
        ButtonBase[] buttons = {tabGeneralBtn, tabJavaBtn, tabInterfaceBtn, tabAccountBtn};
        // Селектор клієнта потрібен тільки на вкладках, де налаштування прив'язані до профілю
        boolean[] needsProfileBar = {true, true, false, false};
        for (int i = 0; i < buttons.length; i++) {
            Region pane = panes[i];
            boolean showProfileBar = needsProfileBar[i];
            ButtonBase button = buttons[i];
            button.setOnAction((e) -> {
                for (int j = 0; j < panes.length; j++) {
                    boolean selected = panes[j] == pane;
                    panes[j].setVisible(selected);
                    panes[j].setManaged(selected);
                    buttons[j].getStyleClass().remove("settings-tab-active");
                    if (selected) buttons[j].getStyleClass().add("settings-tab-active");
                }
                profileBar.setVisible(showProfileBar);
                profileBar.setManaged(showProfileBar);
            });
        }
        tabGeneralBtn.getStyleClass().add("settings-tab-active");
    }

    private void initProfileSelector() {
        profileCombo = LookupHelper.lookup(layout, "#profileCombo");
        profileCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ClientProfile profile) {
                return profile == null ? "" : profile.getTitle();
            }

            @Override
            public ClientProfile fromString(String string) {
                return null;
            }
        });
        profileCombo.setOnAction((e) -> {
            ClientProfile selected = profileCombo.getValue();
            if (selected == null || selected == selectedProfile) return;
            selectedProfile = selected;
            reset();
        });
    }

    private void refreshProfileCombo() {
        List<ClientProfile> profiles = application.profilesService.getProfiles();
        boolean hasProfiles = profiles != null && !profiles.isEmpty();
        profileCombo.getItems().setAll(hasProfiles ? profiles : List.of());
        profileCombo.setDisable(!hasProfiles);
        if (!hasProfiles) {
            selectedProfile = null;
            return;
        }
        if (selectedProfile == null || !profiles.contains(selectedProfile)) {
            ClientProfile current = application.profilesService.getProfile();
            if (current != null && profiles.contains(current)) {
                selectedProfile = current;
            } else {
                selectedProfile = profiles.stream()
                                          .filter(p -> p.getUUID().equals(application.runtimeSettings.lastProfile))
                                          .findFirst()
                                          .orElse(profiles.get(0));
            }
        }
        profileCombo.getSelectionModel().select(selectedProfile);
    }

    private long getJavaMaxMemory() {
        if (application.javaService.isArchAvailable(JVMHelper.ARCH.X86_64) || application.javaService.isArchAvailable(
                JVMHelper.ARCH.ARM64)) {
            return MAX_JAVA_MEMORY_X64;
        }
        return MAX_JAVA_MEMORY_X32;
    }

    @Override
    public void reset() {
        super.reset();
        refreshProfileCombo();
        ClientProfile profile = selectedProfile;
        boolean hasProfile = profile != null;
        profileSettings = hasProfile ? new RuntimeSettings.ProfileSettingsView(application.getProfileSettings(profile)) : null;

        ramSlider.setDisable(!hasProfile);
        LookupHelper.<ComboBox<?>>lookup(javaTabContent, "#javaCombo").setDisable(!hasProfile);
        if (hasProfile) {
            javaSelector = new JavaSelectorComponent(application.javaService, javaTabContent, profileSettings, profile);
            ramSlider.setValue(profileSettings.ram);
        }
        ramSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (profileSettings == null) return;
            profileSettings.ram = newValue.intValue();
            updateRamLabel();
        });
        updateRamLabel();

        LookupHelper.<Button>lookupIfPossible(layout, "#savepanel", "#save").ifPresent(saveButton -> {
            saveButton.setText(application.getTranslation("runtime.components.serverButton.save"));
            saveButton.setOnAction(e -> {
                try {
                    if (profileSettings != null) {
                        profileSettings.apply();
                        application.triggerManager.process(profile, application.profilesService.getOptionalView(profile));
                    }
                    switchToBackScene();
                } catch (Exception exception) {
                    errorHandle(exception);
                }
            });
        });
        LookupHelper.<ButtonBase>lookupIfPossible(layout, "#savepanel", "#clientSettings").ifPresent(settingsButton -> {
            settingsButton.setText(application.getTranslation("runtime.scenes.settings.backButton"));
            settingsButton.setOnAction(e -> {
                try {
                    reset();
                    switchToBackScene();
                } catch (Exception exception) {
                    errorHandle(exception);
                }
            });
        });

        if (hasProfile) {
            add("Debug", application.runtimeSettings.globalSettings.debugAllClients || profileSettings.debug,
                (value) -> profileSettings.debug = value, application.runtimeSettings.globalSettings.debugAllClients);
            add("AutoEnter", profileSettings.autoEnter, (value) -> profileSettings.autoEnter = value, false);
            add("Fullscreen", profileSettings.fullScreen, (value) -> profileSettings.fullScreen = value, false);
            if (JVMHelper.OS_TYPE == JVMHelper.OS.LINUX) {
                add("WaylandSupport", profileSettings.waylandSupport, (value) -> profileSettings.waylandSupport = value, false);
            }
            if (application.authService.checkDebugPermission("skipupdate")) {
                add("DebugSkipUpdate", profileSettings.debugSkipUpdate, (value) -> profileSettings.debugSkipUpdate = value, false);
            }
            if (application.authService.checkDebugPermission("skipfilemonitor")) {
                add("DebugSkipFileMonitor", profileSettings.debugSkipFileMonitor, (value) -> profileSettings.debugSkipFileMonitor = value, false);
            }
        }
        RuntimeSettings.GlobalSettings globalSettings = application.runtimeSettings.globalSettings;
        add("PrismVSync", globalSettings.prismVSync, (value) -> globalSettings.prismVSync = value, false);
        add("DebugAllClients", globalSettings.debugAllClients, (value) -> globalSettings.debugAllClients = value, false);

        LookupHelper.<ImageView>lookupIfPossible(layout, "#accountAvatar").ifPresent((av) -> {
            JavaFxUtils.setStaticRadius(av, DesignConstants.AVATAR_IMAGE_RADIUS);
            JavaFxUtils.putAvatarToImageView(application, application.authService.getUsername(), av);
        });
        LookupHelper.<Label>lookupIfPossible(layout, "#accountNickname")
                    .ifPresent((e) -> e.setText(application.authService.getUsername()));
        LookupHelper.<Label>lookupIfPossible(layout, "#accountRole")
                    .ifPresent((e) -> e.setText(application.authService.getMainRole()));

        userBlock.reset();
    }

    @Override
    public UserBlock getUserBlock() {
        return userBlock;
    }

    @Override
    public String getName() {
        return "settings";
    }

    public void updateRamLabel() {
        if (profileSettings == null) {
            ramLabel.setText(application.getTranslation("runtime.scenes.settings.ramAuto"));
            return;
        }
        ramLabel.setText(profileSettings.ram == 0
                                 ? application.getTranslation("runtime.scenes.settings.ramAuto")
                                 : MessageFormat.format(application.getTranslation("runtime.scenes.settings.ram"),
                                                        profileSettings.ram));
    }
}
