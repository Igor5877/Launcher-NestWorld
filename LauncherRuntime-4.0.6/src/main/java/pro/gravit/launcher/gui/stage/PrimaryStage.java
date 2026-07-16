package pro.gravit.launcher.gui.stage;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.config.DesignConstants;
import pro.gravit.launcher.gui.impl.AbstractStage;
import pro.gravit.launcher.gui.impl.AbstractVisualComponent;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;

public class PrimaryStage extends AbstractStage {
    private static final double RESIZE_MARGIN = 6.0;
    private static final int RESIZE_SOUTH = 1;
    private static final int RESIZE_EAST = 2;

    private double resizeStartMouseX, resizeStartMouseY;
    private double resizeStartWidth, resizeStartHeight, resizeStartStageX, resizeStartStageY;
    private double pendingWidth, pendingHeight;
    private boolean resizeApplyScheduled = false;

    private final double initialX, initialY;

    public PrimaryStage(JavaFXApplication application, Stage primaryStage, String title) {
        super(application, primaryStage);
        primaryStage.setTitle(title);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setResizable(true);
        scene.setFill(Color.TRANSPARENT);

        // Дозволяємо тягнути головне вікно за розмір: стек з дизайном фіксованого
        // розміру (930x560) обгортаємо в rootPane, що заповнює реальне вікно,
        // і розтягуємо весь вміст рівно до країв вікна (без збереження пропорцій).
        // Збереження пропорцій (letterbox) навмисно НЕ використовується: воно лишало
        // прозорі "мертві" смуги, де межа вікна не збігалася з намальованим GUI.
        // WM сам НЕ дає зони ресайзу для беззрамкового (TRANSPARENT) вікна навіть при
        // setResizable(true) (перевірено), тож зону тягання реалізовано вручну нижче.
        stackPane.setMinSize(DesignConstants.WINDOW_WIDTH, DesignConstants.WINDOW_HEIGHT);
        stackPane.setPrefSize(DesignConstants.WINDOW_WIDTH, DesignConstants.WINDOW_HEIGHT);
        stackPane.setMaxSize(DesignConstants.WINDOW_WIDTH, DesignConstants.WINDOW_HEIGHT);
        StackPane rootPane = new StackPane();
        scene.setRoot(rootPane);
        rootPane.getChildren().add(stackPane);
        stackPane.scaleXProperty().bind(Bindings.createDoubleBinding(
                () -> scaleOrOne(rootPane.getWidth() / DesignConstants.WINDOW_WIDTH), rootPane.widthProperty()));
        stackPane.scaleYProperty().bind(Bindings.createDoubleBinding(
                () -> scaleOrOne(rootPane.getHeight() / DesignConstants.WINDOW_HEIGHT), rootPane.heightProperty()));
        // Хендли ресайзу додаються ПОВЕРХ stackPane (останніми дітьми rootPane), інакше курсор
        // на межі "перебивається" -fx-cursor: hand з кнопок/елементів інтерфейсу, що опиняються
        // під тими самими 6px (JavaFX бере курсор найглибшого/фронтового вузла під мишею).
        addResizeHandle(rootPane, RESIZE_EAST, Cursor.E_RESIZE, RESIZE_MARGIN, -1, Pos.CENTER_RIGHT);
        addResizeHandle(rootPane, RESIZE_SOUTH, Cursor.S_RESIZE, -1, RESIZE_MARGIN, Pos.BOTTOM_CENTER);
        addResizeHandle(rootPane, RESIZE_EAST | RESIZE_SOUTH, Cursor.SE_RESIZE, RESIZE_MARGIN, RESIZE_MARGIN, Pos.BOTTOM_RIGHT);

        stage.setWidth(DesignConstants.WINDOW_WIDTH);
        stage.setHeight(DesignConstants.WINDOW_HEIGHT);
        stage.setMinWidth(DesignConstants.WINDOW_WIDTH * 0.65);
        stage.setMinHeight(DesignConstants.WINDOW_HEIGHT * 0.65);

        // Центруємо вікно самі (а не покладаємось на дефолтне розміщення ОС), щоб мати
        // ТОЧНО відоме, фіксоване значення, до якого завжди повертає кнопка "на весь екран" —
        // незалежно від того, як користувач посунув/розтягнув вікно до цього.
        Rectangle2D primaryBounds = Screen.getPrimary().getVisualBounds();
        this.initialX = primaryBounds.getMinX() + (primaryBounds.getWidth() - DesignConstants.WINDOW_WIDTH) / 2;
        this.initialY = primaryBounds.getMinY() + (primaryBounds.getHeight() - DesignConstants.WINDOW_HEIGHT) / 2;
        stage.setX(initialX);
        stage.setY(initialY);

        // Icons
        try {
            Image icon = new Image(JavaFXApplication.getResourceURL("favicon.png").toString());
            stage.getIcons().add(icon);
        } catch (IOException e) {
            LogHelper.error(e);
        }
        setClipRadius(DesignConstants.SCENE_CLIP_RADIUS, DesignConstants.SCENE_CLIP_RADIUS);
    }

    private static double scaleOrOne(double scale) {
        return scale > 0 ? scale : 1.0;
    }

    /* Ресайз навмисно лише за правий край, нижній край і правий-нижній кут: лівий верхній
       кут вікна має лишатись абсолютно нерухомим. На частині WM/GTK сам виклик
       stage.setWidth/setHeight самовільно зсуває X/Y (схоже на ресайз "від центру"),
       тож при кожному застосуванні розміру примусово повертаємо X/Y до вихідного —
       це перебиває будь-яке самовільне зміщення від WM.
       Верхній і лівий край не беруть участі — там же зона перетягування вікна за заголовок.
       Живе оновлення під час тягання є, але коалесується через Platform.runLater (не частіше
       одного разу за кадр) — на GTK/Linux синхронний stage.setWidth/setHeight на КОЖНУ мишачу
       подію провокував gtk_window_resize: assertion 'width/height > 0' failed і тремтіння. */
    private void addResizeHandle(StackPane root, int mask, Cursor cursor, double width, double height, Pos alignment) {
        Region handle = new Region();
        handle.setCursor(cursor);
        handle.setMouseTransparent(false);
        handle.setPickOnBounds(true);
        if (width > 0) {
            handle.setMinWidth(width);
            handle.setMaxWidth(width);
        }
        if (height > 0) {
            handle.setMinHeight(height);
            handle.setMaxHeight(height);
        }
        StackPane.setAlignment(handle, alignment);
        root.getChildren().add(handle);

        handle.setOnMousePressed(event -> {
            if (stage.isMaximized()) return;
            resizeStartMouseX = event.getScreenX();
            resizeStartMouseY = event.getScreenY();
            resizeStartWidth = stage.getWidth();
            resizeStartHeight = stage.getHeight();
            resizeStartStageX = stage.getX();
            resizeStartStageY = stage.getY();
        });
        handle.setOnMouseDragged(event -> {
            if (stage.isMaximized()) return;
            double dx = event.getScreenX() - resizeStartMouseX;
            double dy = event.getScreenY() - resizeStartMouseY;
            pendingWidth = (mask & RESIZE_EAST) != 0
                    ? Math.max(stage.getMinWidth(), resizeStartWidth + dx) : stage.getWidth();
            pendingHeight = (mask & RESIZE_SOUTH) != 0
                    ? Math.max(stage.getMinHeight(), resizeStartHeight + dy) : stage.getHeight();
            scheduleResizeApply();
        });
    }

    private void scheduleResizeApply() {
        if (resizeApplyScheduled) return;
        resizeApplyScheduled = true;
        Platform.runLater(() -> {
            resizeApplyScheduled = false;
            stage.setWidth(pendingWidth);
            stage.setHeight(pendingHeight);
            stage.setX(resizeStartStageX);
            stage.setY(resizeStartStageY);
        });
    }

    /* Розгортання на весь робочий стіл (не F11 fullscreen — звичайне вікно, просто розміром
       на весь екран). Використовуємо ВБУДОВАНИЙ Stage.setMaximized() замість ручного
       підрахунку геометрії — це "рідний" протокол maximize, яким говорить із WM, тож саме
       WM пам'ятає і повертає коректну позицію/розмір при виході, а не наш власний код. */
    @Override
    public void toggleMaximize() {
        stage.setMaximized(!stage.isMaximized());
    }

    public void pushBackground(AbstractVisualComponent component) {
        scenePosition.incrementAndGet();
        addBefore(visualComponent.getLayout(), component.getLayout());
    }

    public void pullBackground(AbstractVisualComponent component) {
        scenePosition.decrementAndGet();
        pull(component.getLayout());
    }

    @Override
    public void close() {
        Platform.exit();
    }
}
