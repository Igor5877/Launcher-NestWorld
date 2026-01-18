package pro.gravit.launcher.base.events.request;

import pro.gravit.launcher.base.events.RequestEvent;

import java.util.List;

public class RestoreRequestEvent extends RequestEvent {
    public CurrentUserRequestEvent.UserInfo userInfo;
    public List<String> invalidTokens;
    public AuthRequestEvent.OAuthRequestEvent oauth;

    public RestoreRequestEvent() {
    }

    public RestoreRequestEvent(CurrentUserRequestEvent.UserInfo userInfo) {
        this.userInfo = userInfo;
    }

    public RestoreRequestEvent(CurrentUserRequestEvent.UserInfo userInfo, List<String> invalidTokens) {
        this.userInfo = userInfo;
        this.invalidTokens = invalidTokens;
    }

    public RestoreRequestEvent(List<String> invalidTokens) {
        this.invalidTokens = invalidTokens;
    }

    public RestoreRequestEvent(CurrentUserRequestEvent.UserInfo userInfo, List<String> invalidTokens, AuthRequestEvent.OAuthRequestEvent oauth) {
        this.userInfo = userInfo;
        this.invalidTokens = invalidTokens;
        this.oauth = oauth;
    }

    @Override
    public String getType() {
        return "restore";
    }
}
