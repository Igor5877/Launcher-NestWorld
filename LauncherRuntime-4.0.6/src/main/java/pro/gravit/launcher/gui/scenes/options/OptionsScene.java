package pro.gravit.launcher.gui.scenes.options;

import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.components.UserBlock;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.scenes.AbstractScene;
import pro.gravit.launcher.gui.components.ServerButton;
import pro.gravit.launcher.gui.scenes.interfaces.SceneSupportUserBlock;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.optional.OptionalView;

public class OptionsScene extends AbstractScene implements SceneSupportUserBlock {
    private OptionsTab optionsTab;
    private UserBlock userBlock;

    public OptionsScene(JavaFXApplication application) {
        super("scenes/options/options.fxml", application);
    }

    @Override
    protected void doInit() {
        this.userBlock = new UserBlock(layout, new SceneAccessor());
        optionsTab = new OptionsTab(application, LookupHelper.lookup(layout, "#tabPane"));
    }

    @Override
public void reset() {
    ClientProfile profile = application.profilesService.getProfile(); // Повертаємо визначення profile
    
    // Логіка для кнопки "Зберегти"
    LookupHelper.<Button>lookupIfPossible(layout, "#savepanel", "#save").ifPresent(saveButton -> {
        saveButton.setText(application.getTranslation("runtime.components.serverButton.save"));
        saveButton.setOnAction(e -> {
            try {
                application.profilesService.setOptionalView(profile, optionsTab.getOptionalView());
                switchScene(application.gui.serverInfoScene);
            } catch (Exception exception) {
                errorHandle(exception);
            }
        });
    });

    // Логіка для кнопки "Скинути" (яка у вас #clientSettings)
    LookupHelper.<Button>lookupIfPossible(layout, "#savepanel", "#clientSettings").ifPresent(resetButton -> {
        resetButton.setText(application.getTranslation("runtime.components.serverButton.reset"));
        resetButton.setOnAction(e -> {
            optionsTab.clear();
            application.profilesService.setOptionalView(profile, new OptionalView(profile));
            optionsTab.addProfileOptionals(application.profilesService.getOptionalView());
        });
    });

    optionsTab.clear();
    LookupHelper.<Button>lookupIfPossible(layout, "#back").ifPresent(x -> x.setOnAction((e) -> {
        try {
            switchToBackScene();
        } catch (Exception exception) {
            errorHandle(exception);
        }
    }));
    optionsTab.addProfileOptionals(application.profilesService.getOptionalView());
    userBlock.reset();
}

    @Override
    public String getName() {
        return "options";
    }

    @Override
    public UserBlock getUserBlock() {
        return userBlock;
    }
}
