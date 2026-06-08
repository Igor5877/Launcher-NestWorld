package pro.gravit.launchermodules.discordgame;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфігурація одного стану Discord Rich Presence (login / authorized / client).
 * Назви полів точно відповідають ключам у JSON-конфігурації (@LauncherInject scopes).
 */
public class ScopeConfig {
    private final String details;
    private final String state;
    private final String largeImageKey;
    private final String smallImageKey;
    private final String largeImageText;
    private final String smallImageText;
    private final boolean firstButtonEnable;
    private final String firstButtonName;
    private final String firstButtonUrl;
    private final boolean secondButtonEnable;
    private final String secondButtonName;
    private final String secondButtonUrl;

    public ScopeConfig(String details, String state,
                       String largeImageKey, String smallImageKey,
                       String largeImageText, String smallImageText,
                       boolean firstButtonEnable, String firstButtonName, String firstButtonUrl,
                       boolean secondButtonEnable, String secondButtonName, String secondButtonUrl) {
        this.details = details;
        this.state = state;
        this.largeImageKey = largeImageKey;
        this.smallImageKey = smallImageKey;
        this.largeImageText = largeImageText;
        this.smallImageText = smallImageText;
        this.firstButtonEnable = firstButtonEnable;
        this.firstButtonName = firstButtonName;
        this.firstButtonUrl = firstButtonUrl;
        this.secondButtonEnable = secondButtonEnable;
        this.secondButtonName = secondButtonName;
        this.secondButtonUrl = secondButtonUrl;
    }

    public ScopeConfig(Map<String, String> map) {
        this.details          = map.get("details");
        this.state            = map.get("state");
        this.largeImageKey    = map.get("largeImageKey");
        this.smallImageKey    = map.get("smallImageKey");
        this.largeImageText   = map.get("largeImageText");
        this.smallImageText   = map.get("smallImageText");
        this.firstButtonEnable  = Boolean.parseBoolean(map.get("firstButtonEnable"));
        this.firstButtonName    = map.get("firstButtonName");
        this.firstButtonUrl     = map.get("firstButtonUrl");
        this.secondButtonEnable = Boolean.parseBoolean(map.get("secondButtonEnable"));
        this.secondButtonName   = map.get("secondButtonName");
        this.secondButtonUrl    = map.get("secondButtonUrl");
    }

    public String getDetails()        { return details; }
    public String getState()          { return state; }
    public String getLargeImageKey()  { return largeImageKey; }
    public String getSmallImageKey()  { return smallImageKey; }
    public String getLargeImageText() { return largeImageText; }
    public String getSmallImageText() { return smallImageText; }
    public boolean isFirstButtonEnable()  { return firstButtonEnable; }
    public String getFirstButtonName()    { return firstButtonName; }
    public String getFirstButtonUrl()     { return firstButtonUrl; }
    public boolean isSecondButtonEnable() { return secondButtonEnable; }
    public String getSecondButtonName()   { return secondButtonName; }
    public String getSecondButtonUrl()    { return secondButtonUrl; }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("details",          details);
        map.put("state",            state);
        map.put("largeImageKey",    largeImageKey);
        map.put("smallImageKey",    smallImageKey);
        map.put("largeImageText",   largeImageText);
        map.put("smallImageText",   smallImageText);
        map.put("firstButtonEnable",  String.valueOf(firstButtonEnable));
        map.put("firstButtonName",    firstButtonName);
        map.put("firstButtonUrl",     firstButtonUrl);
        map.put("secondButtonEnable", String.valueOf(secondButtonEnable));
        map.put("secondButtonName",   secondButtonName);
        map.put("secondButtonUrl",    secondButtonUrl);
        return map;
    }

    @Override
    public String toString() {
        return "ScopeConfig{details='" + details + "', state='" + state + "', largeImageKey='" + largeImageKey +
               "', smallImageKey='" + smallImageKey + "', firstButtonEnable=" + firstButtonEnable + '}';
    }
}
