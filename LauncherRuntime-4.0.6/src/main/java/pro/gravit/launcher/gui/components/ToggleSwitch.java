package pro.gravit.launcher.gui.components;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.function.Consumer;

public class ToggleSwitch extends StackPane {
    private static final double WIDTH = 34.0;
    private static final double HEIGHT = 18.0;
    private static final double THUMB_SIZE = 12.0;
    private static final double PADDING = 3.0;
    private static final double TRAVEL = WIDTH - THUMB_SIZE - PADDING * 2;

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Region track = new Region();
    private final Circle thumb = new Circle(THUMB_SIZE / 2);
    private final TranslateTransition transition = new TranslateTransition(Duration.millis(160), thumb);
    private Consumer<Boolean> onToggle;
    private boolean silent = false;

    public ToggleSwitch() {
        setPrefSize(WIDTH, HEIGHT);
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setAlignment(Pos.CENTER_LEFT);
        setCursor(Cursor.HAND);

        track.setPrefSize(WIDTH, HEIGHT);
        track.setMinSize(WIDTH, HEIGHT);
        track.setMaxSize(WIDTH, HEIGHT);
        track.getStyleClass().add("toggle-switch-track");

        thumb.getStyleClass().add("toggle-switch-thumb");
        StackPane.setMargin(thumb, new Insets(0, 0, 0, PADDING));

        getChildren().addAll(track, thumb);

        setOnMouseClicked((e) -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                setSelected(!isSelected());
            }
        });

        selected.addListener((obs, oldVal, newVal) -> {
            if (silent) return;
            applyState(newVal, true);
            if (onToggle != null) onToggle.accept(newVal);
        });
    }

    private void applyState(boolean value, boolean animate) {
        if (value) {
            if (!track.getStyleClass().contains("toggle-switch-track-on")) {
                track.getStyleClass().add("toggle-switch-track-on");
            }
        } else {
            track.getStyleClass().remove("toggle-switch-track-on");
        }
        transition.stop();
        transition.setToX(value ? TRAVEL : 0);
        if (animate) {
            transition.playFromStart();
        } else {
            thumb.setTranslateX(value ? TRAVEL : 0);
        }
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    /** Встановлює стан без анімації і без виклику onToggle - для синхронізації з іншим джерелом правди (напр. каскадні залежності). */
    public void setSelectedSilently(boolean value) {
        silent = true;
        selected.set(value);
        silent = false;
        applyState(value, false);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public void setOnToggle(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }
}
