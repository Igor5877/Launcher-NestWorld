package pro.gravit.launcher.gui.scenes.login.methods;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.AuthResult;
import com.azuriom.azauth.exception.AuthException;
import javafx.scene.control.TextField;
import pro.gravit.launcher.base.request.RequestException;
import pro.gravit.launcher.base.request.auth.password.AuthOAuthPassword;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.gui.helper.LookupHelper;
import pro.gravit.launcher.gui.impl.AbstractVisualComponent;
import pro.gravit.launcher.gui.impl.ContextHelper;
import pro.gravit.launcher.gui.scenes.login.AuthFlow;
import pro.gravit.launcher.gui.scenes.login.LoginAuthButtonComponent;
import pro.gravit.launcher.gui.scenes.login.LoginScene;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launcher.base.request.auth.details.AuthPasswordDetails;
import pro.gravit.utils.helper.LogHelper;

import java.util.concurrent.CompletableFuture;

public class LoginAndPasswordAuthMethod extends AbstractAuthMethod<AuthPasswordDetails> {
    private final LoginAndPasswordOverlay overlay;
    private final JavaFXApplication application;
    private final LoginScene.LoginSceneAccessor accessor;
    private final TotpAuthMethod.TotpOverlay totpOverlay;
    private volatile String pendingUrl;
    private volatile CompletableFuture<String> pendingCodeFuture;
    // Кнопка «Увійти» ніколи не дизейблиться фізично (LoginAuthButtonComponent лише
    // міняє стилі), а кожен повторний authenticate() ротує токен сайта і робить
    // недійсним попередній — тому дублікати кліків треба гасити тут.
    private final java.util.concurrent.atomic.AtomicBoolean authInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Дебаунс попередження "порожні поля" - без нього автоклікер спамить нотифікаціями
    // швидше, ніж вони встигають згаснути.
    private final java.util.concurrent.atomic.AtomicBoolean emptyFieldsWarningActive =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public LoginAndPasswordAuthMethod(LoginScene.LoginSceneAccessor accessor) {
        this.accessor = accessor;
        this.application = accessor.getApplication();
        this.overlay = new LoginAndPasswordOverlay(application);
        this.totpOverlay = new TotpAuthMethod.TotpOverlay(application);
    }

    @Override
    public void prepare() {
    }

    @Override
    public void reset() {
        overlay.reset();
    }

    @Override
    public CompletableFuture<Void> show(AuthPasswordDetails details) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            //accessor.showOverlay(overlay, (e) -> future.complete(null));
            ContextHelper.runInFxThreadStatic(() -> {
                accessor.showContent(overlay);
                future.complete(null);
            }).exceptionally((th) -> {
                LogHelper.error(th);
                return null;
            });
        } catch (Exception e) {
            accessor.errorHandle(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<AuthFlow.LoginAndPasswordResult> auth(AuthPasswordDetails details) {
        pendingUrl = details.url;
        overlay.future = new CompletableFuture<>();
        String login = overlay.login.getText();
        AuthRequest.AuthPasswordInterface password;
        if (overlay.password.getText().isEmpty() && overlay.password.getPromptText().equals(application.getTranslation(
                "runtime.scenes.login.password.saved"))) {
            password = application.runtimeSettings.password;
            return CompletableFuture.completedFuture(new AuthFlow.LoginAndPasswordResult(login, password));
        }
        return overlay.future;
    }

    @Override
    public void onAuthClicked() {
        CompletableFuture<String> codeFuture = pendingCodeFuture;
        if (codeFuture != null) {
            // Сабміт TOTP-коду; complete() ідемпотентний, повторний клік — no-op.
            totpOverlay.complete();
            return;
        }
        if (pendingUrl == null) {
            overlay.future.complete(overlay.getResult());
            return;
        }
        String login = overlay.login.getText();
        String rawPassword = overlay.password.getText();
        // Порожні поля не повинні йти в HTTP-запит до Azuriom: сайт на такий запит
        // повертає не той JSON-об'єкт, який очікує azauth-клієнт, і замість
        // зрозумілої помилки клієнт падає з сирим Gson-стеком (Expected BEGIN_OBJECT).
        if (login.isBlank() || rawPassword.isBlank()) {
            // НЕ через overlay.future.completeExceptionally(): це проганяє AuthFlow.start()
            // в exceptionally-гілку, яка робить повний reset() (перестворення форми входу
            // й повторну реєстрацію її слухачів) + нову спливаючу нотифікацію - НА КОЖЕН
            // виклик. Автоклікер по кнопці з порожніми полями (сотні кліків/сек) за секунди
            // засипає FX-потік тисячами копій notification.fxml і перебудов форми, аж поки
            // вікно не замерзає вглухо. Показуємо попередження напряму, форму не чіпаємо,
            // і не даємо повторному кліку створити ще одну нотифікацію, поки перша не згасла.
            if (emptyFieldsWarningActive.compareAndSet(false, true)) {
                accessor.errorHandle(new RequestException(application.getTranslation("runtime.scenes.login.emptyFields")));
                javafx.animation.PauseTransition cooldown = new javafx.animation.PauseTransition(javafx.util.Duration.millis(800));
                cooldown.setOnFinished(e -> emptyFieldsWarningActive.set(false));
                cooldown.play();
            }
            return;
        }
        if (!authInFlight.compareAndSet(false, true)) {
            return; // попередній вхід ще триває
        }
        final String url = pendingUrl;
        CompletableFuture.supplyAsync(() -> {
            try {
                AuthClient azClient = new AuthClient(url);
                AuthResult<com.azuriom.azauth.model.User> result = azClient.login(login, rawPassword);
                if (result.isPending() && result.asPending().require2fa()) {
                    pendingCodeFuture = totpOverlay.awaitCode(6);
                    ContextHelper.runInFxThreadStatic(() -> accessor.showContent(totpOverlay));
                    String totpCode = pendingCodeFuture.join();
                    pendingCodeFuture = null;
                    result = azClient.login(login, rawPassword, totpCode);
                }
                if (!result.isSuccess()) {
                    throw new AuthException("Authentication failed");
                }
                String accessToken = result.getSuccessResult().getAccessToken();
                return new AuthFlow.LoginAndPasswordResult(login, new AuthOAuthPassword(accessToken));
            } catch (AuthException e) {
                // Повідомлення тут - сирий текст від Azuriom (за замовчуванням англійською,
                // напр. "Invalid credentials"), а не наш код помилки - тому звичайний шлях
                // перекладу через "runtime.request.<код>" тут не спрацює. Перекладаємо
                // найпоширеніший випадок напряму; RequestException-обгортку робимо нижче,
                // у whenComplete (тут не можна - RequestException checked, Supplier ні).
                String rawMessage = e.getMessage();
                String message = "Invalid credentials".equalsIgnoreCase(rawMessage)
                        ? application.getTranslation("runtime.scenes.login.invalidCredentials", rawMessage)
                        : rawMessage;
                throw new RuntimeException(message, e);
            }
        }).orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).whenComplete((res, ex) -> {
            // Завершуємо future в FX-потоці: подальший ланцюжок AuthFlow працює зі сценою,
            // і продовження з worker-потоку мовчки падає з IllegalStateException.
            ContextHelper.runInFxThreadStatic(() -> {
                pendingCodeFuture = null;
                authInFlight.set(false);
                if (ex != null) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    // RequestException (замість "сирого" cause) - щоб errorHandle() показав
                    // вже перекладене повідомлення без потворного префіксу класу винятку.
                    overlay.future.completeExceptionally(new RequestException(cause.getMessage(), cause));
                } else {
                    overlay.future.complete(res);
                }
            });
        });
    }

    @Override
    public void onUserCancel() {
        CompletableFuture<String> cf = pendingCodeFuture;
        if (cf != null) {
            pendingCodeFuture = null;
            cf.completeExceptionally(new UserAuthCanceledException());
        } else {
            overlay.future.completeExceptionally(LoginAndPasswordOverlay.USER_AUTH_CANCELED_EXCEPTION);
        }
    }

    @Override
    public CompletableFuture<Void> hide() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isOverlay() {
        return false;
    }

    public class LoginAndPasswordOverlay extends AbstractVisualComponent {
        private static final UserAuthCanceledException USER_AUTH_CANCELED_EXCEPTION = new UserAuthCanceledException();
        private TextField login;
        private TextField password;
        private volatile CompletableFuture<AuthFlow.LoginAndPasswordResult> future;

        public LoginAndPasswordOverlay(JavaFXApplication application) {
            super("scenes/login/methods/loginpassword.fxml", application);
        }

        @Override
        public String getName() {
            return "loginandpassword";
        }

        public AuthFlow.LoginAndPasswordResult getResult() {
            String rawLogin = login.getText();
            String rawPassword = password.getText();
            return new AuthFlow.LoginAndPasswordResult(rawLogin, application.authService.makePassword(rawPassword));
        }

        @Override
        protected void doInit() {
            login = LookupHelper.lookup(layout, "#login");
            password = LookupHelper.lookup(layout, "#password");

            login.textProperty().addListener(l -> accessor.getAuthButton().setState(login.getText().isEmpty()
                                                                                            ? LoginAuthButtonComponent.AuthButtonState.UNACTIVE
                                                                                            : LoginAuthButtonComponent.AuthButtonState.ACTIVE));

            if (application.runtimeSettings.login != null) {
                login.setText(application.runtimeSettings.login);
                accessor.getAuthButton().setState(LoginAuthButtonComponent.AuthButtonState.ACTIVE);
            } else {
                accessor.getAuthButton().setState(LoginAuthButtonComponent.AuthButtonState.UNACTIVE);
            }
            // AuthOAuthPassword тут — застарілий одноразовий токен сайта зі старих збірок
            // (нові його не зберігають): показувати його як «збережений пароль» не можна,
            // бо повторний сабміт гарантовано впаде з auth.expiretoken.
            if (application.runtimeSettings.password != null
                    && !(application.runtimeSettings.password instanceof AuthOAuthPassword)) {
                password.getStyleClass().add("hasSaved");
                password.setPromptText(application.getTranslation("runtime.scenes.login.password.saved"));
            }
        }

        @Override
        protected void doPostInit() {

        }


        @Override
        public void reset() {
            if (password == null) return;
            password.getStyleClass().removeAll("hasSaved");
            password.setPromptText(application.getTranslation("runtime.scenes.login.password"));
            password.setText("");
            login.setText("");
        }

        @Override
        public void disable() {

        }

        @Override
        public void enable() {

        }
    }
}
