package pro.gravit.launcher.gui.service;

import com.azuriom.azauth.AuthClient;
import com.azuriom.azauth.exception.AuthException;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launcher.base.LauncherConfig;
import pro.gravit.launcher.gui.JavaFXApplication;
import pro.gravit.launcher.base.events.request.AuthRequestEvent;
import pro.gravit.launcher.base.events.request.GetAvailabilityAuthRequestEvent;
import pro.gravit.launcher.base.profiles.PlayerProfile;
import pro.gravit.launcher.base.request.Request;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launcher.base.request.auth.password.*;
import pro.gravit.utils.helper.SecurityHelper;

import java.util.ArrayList;
import java.util.List;

public class AuthService {
    private final LauncherConfig config = Launcher.getConfig();
    private final JavaFXApplication application;
    private AuthRequestEvent rawAuthResult;
    private AuthClient authClient;
    private GetAvailabilityAuthRequestEvent.AuthAvailability authAvailability;

    public AuthService(JavaFXApplication application) {
        this.application = application;
        // We will initialize authClient when the Azuriom URL is available,
        // likely from a config file or runtime settings.
    }

    private void ensureAuthClientInitialized() {
        if (authClient == null) {
            // This is a placeholder. The URL should be fetched from your application's configuration.
            String azuriomUrl = application.runtimeSettings.azuriomUrl;
            if (azuriomUrl == null || azuriomUrl.isEmpty()) {
                throw new IllegalStateException("Azuriom URL is not configured.");
            }
            this.authClient = new AuthClient(azuriomUrl);
        }
    }

    public String authWithAzuriom(String login, String password) throws AuthException {
        ensureAuthClientInitialized();
        com.azuriom.azauth.AuthResult<com.azuriom.azauth.model.User> result = authClient.login(login, password);

        if (result.isPending() && result.asPending().require2fa()) {
            // This is a simplified approach. A real implementation would show a dialog.
            String totpCode = application.loginScene.request2FACode();
            result = authClient.login(login, password, totpCode);
        }

        if (!result.isSuccess()) {
            throw new AuthException("Authentication failed: " + result.toString());
        }

        return result.getSuccessResult().getAccessToken();
    }

    public AuthRequest.AuthPasswordInterface makePassword(String plainPassword) {
        if (config.passwordEncryptKey != null) {
            try {
                return new AuthAESPassword(encryptAESPassword(plainPassword));
            } catch (Exception ignored) {
            }
        }
        return new AuthPlainPassword(plainPassword);
    }

    public AuthRequest.AuthPasswordInterface make2FAPassword(AuthRequest.AuthPasswordInterface firstPassword,
            String totp) {
        Auth2FAPassword auth2FAPassword = new Auth2FAPassword();
        auth2FAPassword.firstPassword = firstPassword;
        auth2FAPassword.secondPassword = new AuthTOTPPassword();
        ((AuthTOTPPassword) auth2FAPassword.secondPassword).totp = totp;
        return auth2FAPassword;
    }

    public List<AuthRequest.AuthPasswordInterface> getListFromPassword(AuthRequest.AuthPasswordInterface password) {
        if (password instanceof Auth2FAPassword auth2FAPassword) {
            List<AuthRequest.AuthPasswordInterface> list = new ArrayList<>();
            list.add(auth2FAPassword.firstPassword);
            list.add(auth2FAPassword.secondPassword);
            return list;
        } else if (password instanceof AuthMultiPassword authMultiPassword) {
            return authMultiPassword.list;
        } else {
            List<AuthRequest.AuthPasswordInterface> list = new ArrayList<>(1);
            list.add(password);
            return list;
        }
    }

    public AuthRequest.AuthPasswordInterface getPasswordFromList(List<AuthRequest.AuthPasswordInterface> password) {
        if (password.size() == 1) {
            return password.get(0);
        }
        if (password.size() == 2) {
            Auth2FAPassword pass = new Auth2FAPassword();
            pass.firstPassword = password.get(0);
            pass.secondPassword = password.get(1);
            return pass;
        }
        AuthMultiPassword multi = new AuthMultiPassword();
        multi.list = password;
        return multi;
    }

    public AuthRequest makeAuthRequest(String login, AuthRequest.AuthPasswordInterface password, String authId) {
        return new AuthRequest(login, password, authId, false, application.isDebugMode()
                ? AuthRequest.ConnectTypes.API
                : AuthRequest.ConnectTypes.CLIENT);
    }

    private byte[] encryptAESPassword(String password) throws Exception {
        return SecurityHelper.encrypt(Launcher.getConfig().passwordEncryptKey, password);
    }

    public void setAuthResult(String authId, AuthRequestEvent rawAuthResult) {
        this.rawAuthResult = rawAuthResult;
        if (rawAuthResult.oauth != null) {
            Request.setOAuth(authId, rawAuthResult.oauth);
        }
    }

    public void setAuthAvailability(GetAvailabilityAuthRequestEvent.AuthAvailability info) {
        this.authAvailability = info;
    }

    public GetAvailabilityAuthRequestEvent.AuthAvailability getAuthAvailability() {
        return authAvailability;
    }

    public boolean isFeatureAvailable(String name) {
        return authAvailability.features != null && authAvailability.features.contains(name);
    }

    public String getUsername() {
        if (rawAuthResult == null || rawAuthResult.playerProfile == null) return "Player";
        return rawAuthResult.playerProfile.username;
    }

    public String getMainRole() {
        if (rawAuthResult == null
                || rawAuthResult.permissions == null
                || rawAuthResult.permissions.getRoles() == null
                || rawAuthResult.permissions.getRoles().isEmpty()) return "";
        return rawAuthResult.permissions.getRoles().get(0);
    }

    public boolean checkPermission(String name) {
        if (rawAuthResult == null || rawAuthResult.permissions == null) {
            return false;
        }
        return rawAuthResult.permissions.hasPerm(name);
    }

    public boolean checkDebugPermission(String name) {
        return application.isDebugMode() || (!application.guiModuleConfig.disableDebugPermissions &&
                checkPermission("launcher.debug."+name));
    }

    public PlayerProfile getPlayerProfile() {
        if (rawAuthResult == null) return null;
        return rawAuthResult.playerProfile;
    }

    public String getAccessToken() {
        if (rawAuthResult == null) return null;
        return rawAuthResult.accessToken;
    }

    public void exit() {
        rawAuthResult = null;
        //.profile = null;
    }
}
