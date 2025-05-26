package pro.gravit.launcher.gui.scenes.login;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.control.ScrollPane;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.StringConverter;
import javafx.fxml.FXML; // Added import
import pro.gravit.launcher.client.events.ClientExitPhase;
import pro.gravit.launcher.gui.StdJavaRuntimeProvider;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.impl.AbstractVisualComponent;
import pro.gravit.launcher.gui.scenes.AbstractScene;
import pro.gravit.launcher.runtime.LauncherEngine;
import pro.gravit.launcher.runtime.utils.LauncherUpdater;
import pro.gravit.launcher.base.events.request.AuthRequestEvent;
import pro.gravit.launcher.base.events.request.GetAvailabilityAuthRequestEvent;
import pro.gravit.launcher.base.profiles.Texture;
import pro.gravit.launcher.base.request.Request;
import pro.gravit.launcher.base.request.WebSocketEvent;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launcher.base.request.auth.GetAvailabilityAuthRequest;
import pro.gravit.launcher.base.request.auth.details.AuthPasswordDetails;
import pro.gravit.launcher.base.request.auth.password.*;
import pro.gravit.launcher.base.request.update.LauncherRequest;
import pro.gravit.launcher.base.request.update.ProfilesRequest;
import pro.gravit.utils.helper.LogHelper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


public class LoginScene extends AbstractScene {
    @FXML
    private ScrollPane newsScrollPane;

    @FXML
    private VBox newsVBox;
    private List<GetAvailabilityAuthRequestEvent.AuthAvailability> auth; //TODO: FIX? Field is assigned but never accessed.
    private CheckBox savePasswordCheckBox;
    private CheckBox autoenter;
    private Pane content;
    private AbstractVisualComponent contentComponent;
    private LoginAuthButtonComponent authButton;
    private ComboBox<GetAvailabilityAuthRequestEvent.AuthAvailability> authList;
    private GetAvailabilityAuthRequestEvent.AuthAvailability authAvailability;
    private final AuthFlow authFlow;

    public LoginScene(JavaFXApplication application) {
        super("scenes/login/login.fxml", application);
        LoginSceneAccessor accessor = new LoginSceneAccessor();
        this.authFlow = new AuthFlow(accessor, this::onSuccessLogin);
    }

    @Override
    public void doInit() {
        LookupHelper.<ButtonBase>lookup(header, "#controls", "#settings").setOnAction((e) -> {
            try {
                switchScene(application.gui.globalSettingsScene);
            } catch (Exception exception) {
                errorHandle(exception);
            }
        });
        authButton = new LoginAuthButtonComponent(LookupHelper.lookup(layout, "#authButton"), application,
                                                  (e) -> contextHelper.runCallback(authFlow::loginWithGui));
        savePasswordCheckBox = LookupHelper.lookup(layout, "#savePassword");
        if (application.runtimeSettings.password != null || application.runtimeSettings.oauthAccessToken != null) {
            LookupHelper.<CheckBox>lookup(layout, "#savePassword").setSelected(true);
        }
        autoenter = LookupHelper.lookup(layout, "#autoenter");
        autoenter.setSelected(application.runtimeSettings.autoAuth);
        autoenter.setOnAction((event) -> application.runtimeSettings.autoAuth = autoenter.isSelected());
        content = LookupHelper.lookup(layout, "#content");
        newsScrollPane = LookupHelper.lookup(layout, "#newsScrollPane");
        if (newsScrollPane != null) {
            Node contentNode = newsScrollPane.getContent();
            if (contentNode instanceof VBox && "newsVBox".equals(contentNode.getId())) {
                newsVBox = (VBox) contentNode;
            } else {
                // Log a warning if the content isn't the VBox we expect
                pro.gravit.utils.helper.LogHelper.warning("Content of #newsScrollPane is not the expected #newsVBox or its ID doesn't match.");
                // As a fallback, if the contentNode is a Parent itself, try a lookup within it.
                // This handles cases where the VBox might be wrapped by another transparent container.
                if (contentNode instanceof Parent) {
                    newsVBox = LookupHelper.lookup((Parent)contentNode, "#newsVBox");
                    if (newsVBox == null) {
                        pro.gravit.utils.helper.LogHelper.warning("#newsVBox not found even within ScrollPane's content Parent.");
                    }
                }
            }
        } else {
            pro.gravit.utils.helper.LogHelper.warning("#newsScrollPane not found in layout.");
        }
        if (application.guiModuleConfig.createAccountURL != null) {
            LookupHelper.<Text>lookup(header, "#createAccount")
                        .setOnMouseClicked((e) -> application.openURL(application.guiModuleConfig.createAccountURL));
        }

        if (application.guiModuleConfig.forgotPassURL != null) {
            LookupHelper.<Text>lookup(header, "#forgotPass")
                        .setOnMouseClicked((e) -> application.openURL(application.guiModuleConfig.forgotPassURL));
        }
        authList = LookupHelper.lookup(layout, "#authList");
        authList.setConverter(new AuthAvailabilityStringConverter());
        authList.setOnAction((e) -> changeAuthAvailability(authList.getSelectionModel().getSelectedItem()));
        authFlow.prepare();
        // Verify Launcher
    }

    @Override
    protected void doPostInit() {

        if (!application.isDebugMode()) {
            // we would like to wait till launcher request success before start availability auth.
            // otherwise it will try to access same vars same time, and this causes a lot of multi-thread based errors
            // launcherRequest().finally(getAvailabilityAuth().finally(postInit()))
            launcherRequest();
        } else {
            getAvailabilityAuth();
        }
        loadNewsFeed(); // Add this line
    }

    private void loadNewsFeed() {
        CompletableFuture.runAsync(() -> {
            try {
                // User's URL choice
                URL url = new URL("https://nestworld.site/api/rss"); 
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream inputStream = connection.getInputStream();
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    // Crucial for namespace awareness:
                    factory.setNamespaceAware(true); 
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(inputStream);
                    inputStream.close();

                    doc.getDocumentElement().normalize();
                    NodeList itemList = doc.getElementsByTagName("item");

                    Platform.runLater(() -> newsVBox.getChildren().clear());

                    Pattern imgPattern = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");

                    for (int i = 0; i < Math.min(itemList.getLength(), 10); i++) {
                        Element item = (Element) itemList.item(i);
                        String title = "";
                        NodeList titleList = item.getElementsByTagName("title");
                        if(titleList.getLength() > 0) {
                            title = titleList.item(0).getTextContent();
                        }

                        String imageUrl = null;

                        // 1. Try <description>
                        NodeList descriptionList = item.getElementsByTagName("description");
                        if (descriptionList.getLength() > 0) {
                            String descriptionContent = descriptionList.item(0).getTextContent();
                            Matcher imgMatcher = imgPattern.matcher(descriptionContent);
                            if (imgMatcher.find()) {
                                String src = imgMatcher.group(1);
                                if (src != null && !src.isEmpty()) {
                                    if (src.startsWith("/")) { imageUrl = "https://nestworld.site" + src; }
                                    else { imageUrl = src; }
                                }
                            }
                        }

                        // 2. Try <content:encoded> (namespace-aware)
                        if (imageUrl == null) {
                            NodeList contentEncodedList = item.getElementsByTagNameNS("http://purl.org/rss/1.0/modules/content/", "encoded");
                            if (contentEncodedList.getLength() > 0) {
                                String contentEncodedContent = contentEncodedList.item(0).getTextContent();
                                Matcher imgMatcher = imgPattern.matcher(contentEncodedContent);
                                if (imgMatcher.find()) {
                                    String src = imgMatcher.group(1);
                                    if (src != null && !src.isEmpty()) {
                                        if (src.startsWith("/")) { imageUrl = "https://nestworld.site" + src; }
                                        else { imageUrl = src; }
                                    }
                                }
                            }
                        }
                        
                        // 3. Try <enclosure>
                        if (imageUrl == null) {
                            NodeList enclosureList = item.getElementsByTagName("enclosure");
                            for (int j = 0; j < enclosureList.getLength(); j++) {
                                Element enclosure = (Element) enclosureList.item(j);
                                String type = enclosure.getAttribute("type");
                                if (type != null && type.startsWith("image/")) {
                                    String enclosureUrl = enclosure.getAttribute("url");
                                    if (enclosureUrl != null && !enclosureUrl.isEmpty()) {
                                        imageUrl = enclosureUrl;
                                        break; 
                                    }
                                }
                            }
                        }

                        // 4. Try <media:thumbnail> (namespace-aware)
                        // Common Media RSS namespaces: "http://search.yahoo.com/mrss/" or "http://www.rssboard.org/media-rss"
                        // The user's feed snippet didn't show which one, so we might need to pick one or try both.
                        // Let's try the more common one first.
                        if (imageUrl == null) {
                            NodeList mediaThumbnailList = item.getElementsByTagNameNS("http://search.yahoo.com/mrss/", "thumbnail");
                            if (mediaThumbnailList.getLength() == 0) { // Fallback to another common media namespace if needed
                                mediaThumbnailList = item.getElementsByTagNameNS("http://www.rssboard.org/media-rss", "thumbnail");
                            }
                            if (mediaThumbnailList.getLength() > 0) {
                                Element mediaThumbnail = (Element) mediaThumbnailList.item(0);
                                String thumbnailUrl = mediaThumbnail.getAttribute("url");
                                if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                                    imageUrl = thumbnailUrl;
                                }
                            }
                        }
                        
                        // Logging the found URL for this item (user's suggestion)
                        pro.gravit.utils.helper.LogHelper.info("Item: \"" + title + "\", Attempted Image URL: " + (imageUrl == null ? "Not found" : imageUrl));

                        VBox newsItemBox = new VBox(5);
                        newsItemBox.getStyleClass().add("news-item-box");

                        if (imageUrl != null && !imageUrl.isEmpty()) {
                            try {
                                Image newsImage = new Image(imageUrl, true);
                                ImageView newsImageView = new ImageView(newsImage);
                                newsImageView.setFitWidth(150);
                                newsImageView.setPreserveRatio(true);
                                newsImageView.setSmooth(true);
                                newsItemBox.getChildren().add(newsImageView);
                            } catch (Exception e) {
                                pro.gravit.utils.helper.LogHelper.error("Failed to load RSS news image for item '" + title + "': " + imageUrl, e);
                            }
                        }

                        Text newsText = new Text(title);
                        newsText.setStyle("-fx-fill: -fx-colors-text;");
                        newsText.setWrappingWidth(180);
                        newsItemBox.getChildren().add(newsText);

                        final VBox finalNewsItemBox = newsItemBox;
                        Platform.runLater(() -> newsVBox.getChildren().add(finalNewsItemBox));
                    }
                } else {
                    pro.gravit.utils.helper.LogHelper.warning("Failed to fetch news: HTTP " + responseCode);
                    Platform.runLater(() -> newsVBox.getChildren().add(new Text("Failed to load news.")));
                }
            } catch (Exception e) {
                pro.gravit.utils.helper.LogHelper.error("Error loading news feed: " + e.getMessage(), e);
                Platform.runLater(() -> newsVBox.getChildren().add(new Text("Error loading news.")));
            }
        });
    }

    private void launcherRequest() {
        LauncherRequest launcherRequest = new LauncherRequest();
        processRequest(application.getTranslation("runtime.overlay.processing.text.launcher"), launcherRequest,
                       (result) -> {
                           if (result.needUpdate) {
                               try {
                                   LogHelper.debug("Start update processing");
                                   disable();
                                   StdJavaRuntimeProvider.updatePath = LauncherUpdater.prepareUpdate(
                                           new URI(result.url).toURL());
                                   LogHelper.debug("Exit with Platform.exit");
                                   Platform.exit();
                                   return;
                               } catch (Throwable e) {
                                   contextHelper.runInFxThread(() -> errorHandle(e));
                                   try {
                                       Thread.sleep(1500);
                                       LauncherEngine.modulesManager.invokeEvent(new ClientExitPhase(0));
                                       Platform.exit();
                                   } catch (Throwable ex) {
                                       LauncherEngine.exitLauncher(0);
                                   }
                               }
                           }
                           LogHelper.dev("Launcher update processed");
                           getAvailabilityAuth();
                       }, (event) -> LauncherEngine.exitLauncher(0));
    }

    private void getAvailabilityAuth() {
        GetAvailabilityAuthRequest getAvailabilityAuthRequest = new GetAvailabilityAuthRequest();
        processing(getAvailabilityAuthRequest,
                   application.getTranslation("runtime.overlay.processing.text.authAvailability"),
                   (auth) -> contextHelper.runInFxThread(() -> {
                       this.auth = auth.list;
                       authList.setVisible(auth.list.size() != 1);
                       authList.setManaged(auth.list.size() != 1);
                       for (GetAvailabilityAuthRequestEvent.AuthAvailability authAvailability : auth.list) {
                           if (!authAvailability.visible) {
                               continue;
                           }
                           if (application.runtimeSettings.lastAuth == null) {
                               if (authAvailability.name.equals("std") || this.authAvailability == null) {
                                   changeAuthAvailability(authAvailability);
                               }
                           } else if (authAvailability.name.equals(application.runtimeSettings.lastAuth.name))
                               changeAuthAvailability(authAvailability);
                           if(authAvailability.visible) {
                               addAuthAvailability(authAvailability);
                           }
                       }
                       if (this.authAvailability == null && !auth.list.isEmpty()) {
                           changeAuthAvailability(auth.list.get(0));
                       }
                       runAutoAuth();
                   }), null);
    }

    private void runAutoAuth() {
        if (application.guiModuleConfig.autoAuth || application.runtimeSettings.autoAuth) {
            contextHelper.runInFxThread(authFlow::loginWithGui);
        }
    }

    public void changeAuthAvailability(GetAvailabilityAuthRequestEvent.AuthAvailability authAvailability) {
        boolean isChanged = this.authAvailability != authAvailability; //TODO: FIX
        this.authAvailability = authAvailability;
        this.application.authService.setAuthAvailability(authAvailability);
        this.authList.selectionModelProperty().get().select(authAvailability);
        authFlow.init(authAvailability);
        LogHelper.info("Selected auth: %s", authAvailability.name);
    }

    public void addAuthAvailability(GetAvailabilityAuthRequestEvent.AuthAvailability authAvailability) {
        authList.getItems().add(authAvailability);
        LogHelper.info("Added %s: %s", authAvailability.name, authAvailability.displayName);
    }

    public <T extends WebSocketEvent> void processing(Request<T> request, String text, Consumer<T> onSuccess,
            Consumer<String> onError) {
        processRequest(text, request, onSuccess, (thr) -> onError.accept(thr.getCause().getMessage()), null);
    }


    @Override
    public void errorHandle(Throwable e) {
        super.errorHandle(e);
        contextHelper.runInFxThread(() -> authButton.setState(LoginAuthButtonComponent.AuthButtonState.ERROR));
    }

    @Override
    public void reset() {
        authFlow.reset();
    }

    @Override
    public String getName() {
        return "login";
    }

    private boolean checkSavePasswordAvailable(AuthRequest.AuthPasswordInterface password) {
        if (password instanceof Auth2FAPassword) return false;
        if (password instanceof AuthMultiPassword) return false;
        return authAvailability != null
                && authAvailability.details != null
                && !authAvailability.details.isEmpty()
                && authAvailability.details.get(0) instanceof AuthPasswordDetails;
    }

    public void onSuccessLogin(AuthFlow.SuccessAuth successAuth) {
        AuthRequestEvent result = successAuth.requestEvent();
        application.authService.setAuthResult(authAvailability.name, result);
        boolean savePassword = savePasswordCheckBox.isSelected();
        if (savePassword) {
            application.runtimeSettings.login = successAuth.recentLogin();
            if (result.oauth == null) {
                LogHelper.warning("Password not saved");
            } else {
                application.runtimeSettings.oauthAccessToken = result.oauth.accessToken;
                application.runtimeSettings.oauthRefreshToken = result.oauth.refreshToken;
                application.runtimeSettings.oauthExpire = Request.getTokenExpiredTime();
                application.runtimeSettings.password = null;
            }
            application.runtimeSettings.lastAuth = authAvailability;
        }
        if (result.playerProfile != null
                && result.playerProfile.assets != null) {
            try {
                Texture skin = result.playerProfile.assets.get("SKIN");
                Texture avatar = result.playerProfile.assets.get("AVATAR");
                if(skin != null || avatar != null) {
                    application.skinManager.addSkinWithAvatar(result.playerProfile.username,
                                                              skin != null ? new URI(skin.url) : null,
                                                              avatar != null ? new URI(avatar.url) : null);
                    application.skinManager.getSkin(result.playerProfile.username); //Cache skin
                }
            } catch (Exception e) {
                LogHelper.error(e);
            }
        }
        contextHelper.runInFxThread(() -> {
            if(application.gui.welcomeOverlay.isInit()) {
                application.gui.welcomeOverlay.reset();
            }
            showOverlay(application.gui.welcomeOverlay,
                                                      (e) -> application.gui.welcomeOverlay.hide(2000,
                                                                                                 (f) -> onGetProfiles()));});
    }

    public void onGetProfiles() {
        processing(new ProfilesRequest(), application.getTranslation("runtime.overlay.processing.text.profiles"),
                   (profiles) -> {
                       application.profilesService.setProfilesResult(profiles);
                       application.runtimeSettings.profiles = profiles.profiles;
                       contextHelper.runInFxThread(() -> {
                           application.securityService.startRequest();
                           if (application.gui.optionsScene != null) {
                               try {
                                   application.profilesService.loadAll();
                               } catch (Throwable ex) {
                                   errorHandle(ex);
                               }
                           }
                           if (application.getCurrentScene() instanceof LoginScene loginScene) {
                               loginScene.authFlow.isLoginStarted = false;
                           }
                           application.setMainScene(application.gui.serverMenuScene);
                       });
                   }, null);
    }

    public void clearPassword() {
        application.runtimeSettings.password = null;
        application.runtimeSettings.login = null;
        application.runtimeSettings.oauthAccessToken = null;
        application.runtimeSettings.oauthRefreshToken = null;
    }

    public AuthFlow getAuthFlow() {
        return authFlow;
    }

    private static class AuthAvailabilityStringConverter extends StringConverter<GetAvailabilityAuthRequestEvent.AuthAvailability> {
        @Override
        public String toString(GetAvailabilityAuthRequestEvent.AuthAvailability object) {
            return object == null ? "null" : object.displayName;
        }

        @Override
        public GetAvailabilityAuthRequestEvent.AuthAvailability fromString(String string) {
            return null;
        }
    }

    public class LoginSceneAccessor extends SceneAccessor {

        public void showContent(AbstractVisualComponent component) throws Exception {
            component.init();
            component.postInit();
            if (contentComponent != null) {
                content.getChildren().clear();
            }
            contentComponent = component;
            content.getChildren().add(component.getLayout());
        }

        public LoginAuthButtonComponent getAuthButton() {
            return authButton;
        }

        public void setState(LoginAuthButtonComponent.AuthButtonState state) {
            authButton.setState(state);
        }

        public boolean isEmptyContent() {
            return content.getChildren().isEmpty();
        }

        public void clearContent() {
            content.getChildren().clear();
        }

        public <T extends WebSocketEvent> void processing(Request<T> request, String text, Consumer<T> onSuccess,
                Consumer<String> onError) {
            LoginScene.this.processing(request, text, onSuccess, onError);
        }
    }


}
