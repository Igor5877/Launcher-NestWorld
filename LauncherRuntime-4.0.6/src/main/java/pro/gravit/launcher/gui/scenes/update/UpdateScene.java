package pro.gravit.launcher.gui.scenes.update;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.scenes.AbstractScene;
import pro.gravit.launcher.core.hasher.FileNameMatcher;
import pro.gravit.launcher.core.hasher.HashedDir;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.optional.OptionalView;
import pro.gravit.utils.helper.LogHelper;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public class UpdateScene extends AbstractScene {
    private ProgressBar progressBar;
    private Label speed;
    private Label speedSubtext;
    private Label volume;
    private Label percentLabel;
    private Label stateLabel;
    private VBox logOutput;
    private ScrollPane logScroll;
    private Label logLineCount;
    private Button retryButton;
    private Button exitButton;

    private Label profileName;
    private Label profileVersion;
    private Pane statusCard;
    private Pane phaseBadge;
    private Label phaseBadgeText;
    private Label phaseBadgeCount;
    private Label statusPhaseLabel;
    private Label statusPercentLabel;

    private HBox[] phaseRows;
    private Label[] phaseIcons;

    private VisualDownloader downloader;
    private volatile DownloadStatus downloadStatus = DownloadStatus.COMPLETE;
    private Runnable lastUpdateRequest;
    private Phase currentPhase;
    private int logLines = 0;

    public UpdateScene(JavaFXApplication application) {
        super("scenes/update/update.fxml", application);
    }

    @Override
    protected void doInit() {
        progressBar = LookupHelper.lookup(layout, "#progress");
        speed = LookupHelper.lookup(layout, "#statusValue");
        speedSubtext = LookupHelper.lookup(layout, "#statusSubtext");
        statusCard = LookupHelper.lookup(layout, "#statusCard");
        statusPhaseLabel = LookupHelper.lookup(layout, "#statusPhaseLabel");
        statusPercentLabel = LookupHelper.lookup(layout, "#statusPercentLabel");
        volume = LookupHelper.lookup(layout, "#volume");
        percentLabel = LookupHelper.lookup(layout, "#percentLabel");
        stateLabel = LookupHelper.lookup(layout, "#stateLabel");
        logScroll = LookupHelper.lookup(layout, "#logScroll");
        logOutput = (VBox) logScroll.getContent();
        logLineCount = LookupHelper.lookup(layout, "#logLineCount");
        retryButton = LookupHelper.lookup(layout, "#retryButton");
        exitButton = LookupHelper.lookup(layout, "#exitButton");
        profileName = LookupHelper.lookup(layout, "#profileName");
        profileVersion = LookupHelper.lookup(layout, "#profileVersion");
        phaseBadge = LookupHelper.lookup(layout, "#phaseBadge");
        phaseBadgeText = LookupHelper.lookup(layout, "#phaseBadgeText");
        phaseBadgeCount = LookupHelper.lookup(layout, "#phaseBadgeCount");

        phaseRows = new HBox[]{
                LookupHelper.lookup(layout, "#phaseRowJava"),
                LookupHelper.lookup(layout, "#phaseRowAssets"),
                LookupHelper.lookup(layout, "#phaseRowClient"),
                LookupHelper.lookup(layout, "#phaseRowLaunch")
        };
        phaseIcons = new Label[]{
                LookupHelper.lookup(layout, "#phaseIconJava"),
                LookupHelper.lookup(layout, "#phaseIconAssets"),
                LookupHelper.lookup(layout, "#phaseIconClient"),
                LookupHelper.lookup(layout, "#phaseIconLaunch")
        };

        downloader = new VisualDownloader(application, progressBar, speed, volume, this::errorHandle,
                                          (log) -> contextHelper.runInFxThread(() -> addLog(log)), this::onUpdateStatus);
        progressBar.progressProperty().addListener((observable, oldValue, newValue) -> {
            int percent = (int) Math.round(Math.min(1.0, Math.max(0.0, newValue.doubleValue())) * 100.0);
            percentLabel.setText(percent + "%");
            statusPercentLabel.setText(percent + "%");
        });
        LookupHelper.<ButtonBase>lookup(layout, "#cancel").setOnAction((e) -> handleCancelOrBack());
        exitButton.setOnAction((e) -> handleCancelOrBack());
        retryButton.setOnAction((e) -> retryUpdate());
    }

    private void handleCancelOrBack() {
        if (downloadStatus == DownloadStatus.DOWNLOAD && downloader.isDownload()) {
            downloader.cancel();
        } else if (downloadStatus == DownloadStatus.ERROR || downloadStatus == DownloadStatus.COMPLETE) {
            try {
                switchToBackScene();
            } catch (Exception exception) {
                errorHandle(exception);
            }
        }
    }

    private void retryUpdate() {
        if (lastUpdateRequest != null) {
            reset();
            lastUpdateRequest.run();
        }
    }

    private void onUpdateStatus(DownloadStatus newStatus) {
        this.downloadStatus = newStatus;
        LogHelper.debug("Update download status: %s", newStatus.toString());
    }

    public void sendUpdateAssetRequest(String dirName, Path dir, FileNameMatcher matcher, boolean digest,
            String assetIndex, boolean test, Consumer<HashedDir> onSuccess) {
        lastUpdateRequest = () -> downloader.sendUpdateAssetRequest(dirName, dir, matcher, digest, assetIndex, test, onSuccess);
        lastUpdateRequest.run();
    }

    public void sendUpdateRequest(String dirName, Path dir, FileNameMatcher matcher, boolean digest, OptionalView view,
            boolean optionalsEnabled, boolean test, Consumer<HashedDir> onSuccess) {
        lastUpdateRequest = () -> downloader.sendUpdateRequest(dirName, dir, matcher, digest, view, optionalsEnabled, test, onSuccess);
        lastUpdateRequest.run();
    }

    public void addLog(String string) {
        LogHelper.dev("Update event %s", string);
        Label line = new Label(string);
        line.getStyleClass().add("log-line");
        String lower = string.toLowerCase();
        if (lower.contains("exception") || lower.contains("error")) {
            line.getStyleClass().add("log-line-error");
        } else if (lower.contains("complete") || lower.contains("success")) {
            line.getStyleClass().add("log-line-success");
        }
        logOutput.getChildren().add(line);
        logLines++;
        logLineCount.setText(MessageFormat.format(
                application.getTranslation("runtime.scenes.update.logLineCount"), logLines));
        Platform.runLater(() -> logScroll.setVvalue(1.0));
    }

    public void setPhase(Phase phase) {
        this.currentPhase = phase;
        for (int i = 0; i < phaseRows.length; i++) {
            HBox row = phaseRows[i];
            Label icon = phaseIcons[i];
            row.getStyleClass().removeAll("phase-row-done", "phase-row-current", "phase-row-error");
            if (i < phase.ordinal()) {
                row.getStyleClass().add("phase-row-done");
                icon.setText("✓");
            } else if (i == phase.ordinal()) {
                row.getStyleClass().add("phase-row-current");
                icon.setText("●");
            } else {
                icon.setText("○");
            }
        }
        String count = (phase.ordinal() + 1) + "/" + phaseRows.length;
        phaseBadgeCount.setText(count);
        statusPhaseLabel.setText(count);
    }

    @Override
    public void reset() {
        progressBar.progressProperty().setValue(0);
        progressBar.getStyleClass().removeAll("progressError");
        logOutput.getChildren().clear();
        logLines = 0;
        logLineCount.setText(MessageFormat.format(
                application.getTranslation("runtime.scenes.update.logLineCount"), 0));
        volume.setText("");
        percentLabel.setText("0%");
        speed.setText("0");
        speedSubtext.setText("MB/S");
        statusCard.getStyleClass().remove("status-error");
        phaseBadge.getStyleClass().remove("phase-badge-error");
        phaseBadgeText.setText(application.getTranslation("runtime.scenes.update.status.downloading"));
        stateLabel.setText(application.getTranslation("runtime.scenes.update.status.downloading"));
        retryButton.setVisible(false);
        retryButton.setManaged(false);
        exitButton.setVisible(false);
        exitButton.setManaged(false);
        for (HBox row : phaseRows) {
            row.getStyleClass().removeAll("phase-row-done", "phase-row-current", "phase-row-error");
        }
        for (Label icon : phaseIcons) {
            icon.setText("○");
        }
        currentPhase = null;

        ClientProfile profile = application.profilesService.getProfile();
        if (profile != null) {
            profileName.setText(profile.getTitle());
            profileVersion.setText("Minecraft %s".formatted(profile.getVersion().toString()));
        }
    }

    @Override
    public void errorHandle(Throwable e) {
        if (e instanceof CompletionException) {
            e = e.getCause();
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        addLog("Exception %s: %s".formatted(e.getClass(), message));
        progressBar.getStyleClass().add("progressError");
        speed.setText("ERR");
        speedSubtext.setText(message);
        statusCard.getStyleClass().add("status-error");
        phaseBadge.getStyleClass().add("phase-badge-error");
        phaseBadgeText.setText(application.getTranslation("runtime.scenes.update.status.error"));
        stateLabel.setText(application.getTranslation("runtime.scenes.update.status.paused"));
        if (currentPhase != null) {
            HBox row = phaseRows[currentPhase.ordinal()];
            row.getStyleClass().removeAll("phase-row-current");
            row.getStyleClass().add("phase-row-error");
            phaseIcons[currentPhase.ordinal()].setText("✕");
        }
        retryButton.setVisible(true);
        retryButton.setManaged(true);
        exitButton.setVisible(true);
        exitButton.setManaged(true);
        LogHelper.error(e);
    }

    @Override
    public boolean isDisableReturnBack() {
        return true;
    }

    @Override
    public String getName() {
        return "update";
    }

    public enum DownloadStatus {
        ERROR, HASHING, REQUEST, DOWNLOAD, COMPLETE, DELETE
    }

    public enum Phase {
        JAVA, ASSETS, CLIENT, LAUNCH
    }
}
