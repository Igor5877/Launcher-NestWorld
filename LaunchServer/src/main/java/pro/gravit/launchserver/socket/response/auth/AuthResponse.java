package pro.gravit.launchserver.socket.response.auth;

import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.events.request.AuthRequestEvent;
import pro.gravit.launcher.base.request.auth.AuthRequest;
import pro.gravit.launchserver.auth.AuthException;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.manangers.AuthManager;
import pro.gravit.launchserver.socket.Client;
import pro.gravit.launchserver.socket.response.SimpleResponse;
import pro.gravit.utils.HookException;

public class AuthResponse extends SimpleResponse {
    private transient final Logger logger = LogManager.getLogger();
    public String login;
    public String client;

    public AuthRequest.AuthPasswordInterface password;

    public String auth_id;
    public ConnectTypes authType;

    @Override
    public String getType() {
        return "auth";
    }

    @Override
    public void execute(ChannelHandlerContext ctx, Client clientData) {
        try {
            AuthRequestEvent result = new AuthRequestEvent();
            AuthProviderPair pair;
            if (auth_id == null || auth_id.isEmpty()) pair = server.config.getAuthProviderPair();
            else pair = server.config.getAuthProviderPair(auth_id);
            if (pair == null) {
                sendError("auth_id incorrect");
                return;
            }
            AuthContext context = server.authManager.makeAuthContext(clientData, authType, pair, login, client, ip);
            password = server.authManager.decryptPassword(password);
            server.authManager.check(context, password);
            // Every auth type goes through the hooks (whitelist, rate limiter, ...). For OAuth the login is not known yet,
            // so login based hooks skip it and the user based checks run in postHook
            server.authHookManager.preHook.hook(context, clientData);
            context.report = server.authManager.auth(context, password);
            try {
                server.authHookManager.postHook.hook(context, clientData);
            } catch (HookException e) {
                // auth() already marked the connection as authorized: do not leave it that way when a hook rejects the user
                server.authManager.resetAuth(clientData);
                throw e;
            }
            result.permissions = context.report.session() != null ? (context.report.session().getUser() != null ? context.report.session().getUser().getPermissions() : null) : null;
            if (context.report.isUsingOAuth()) {
                result.oauth = new AuthRequestEvent.OAuthRequestEvent(context.report.oauthAccessToken(), context.report.oauthRefreshToken(), context.report.oauthExpire());
            }
            if (context.report.minecraftAccessToken() != null) {
                result.accessToken = context.report.minecraftAccessToken();
            }
            result.playerProfile = server.authManager.getPlayerProfile(clientData);
            sendResult(result);
        } catch (AuthException | HookException e) {
            sendError(e.getMessage());
        }
    }

    public enum ConnectTypes {
        CLIENT,
        API
    }

    public static class AuthContext {
        public final String login;
        public final String profileName;
        public final String ip;
        public final ConnectTypes authType;
        public transient final Client client;
        public transient final AuthProviderPair pair;
        public transient AuthManager.AuthReport report;

        public AuthContext(Client client, String login, String profileName, String ip, ConnectTypes authType, AuthProviderPair pair) {
            this.client = client;
            this.login = login;
            this.profileName = profileName;
            this.ip = ip;
            this.authType = authType;
            this.pair = pair;
        }
    }

    @Override
    public ThreadSafeStatus getThreadSafeStatus() {
        return ThreadSafeStatus.READ_WRITE;
    }
}
