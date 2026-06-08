package pro.gravit.launchermodules.discordgame;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScopeConfig {
    private final String details;
    private final String state;
    private final String largeImage;
    private final String smallImage;
    private final String largeText;
    private final String smallText;
    private final boolean button1Enable;
    private final String button1Name;
    private final String button1Url;
    private final boolean button2Enable;
    private final String button2Name;
    private final String button2Url;

    public ScopeConfig(String details, String state,
                       String largeImage, String smallImage,
                       String largeText, String smallText,
                       boolean button1Enable, String button1Name, String button1Url,
                       boolean button2Enable, String button2Name, String button2Url) {
        this.details = details;
        this.state = state;
        this.largeImage = largeImage;
        this.smallImage = smallImage;
        this.largeText = largeText;
        this.smallText = smallText;
        this.button1Enable = button1Enable;
        this.button1Name = button1Name;
        this.button1Url = button1Url;
        this.button2Enable = button2Enable;
        this.button2Name = button2Name;
        this.button2Url = button2Url;
    }

    public ScopeConfig(Map<String, String> map) {
        this.details = map.get("details");
        this.state = map.get("state");
        this.largeImage = map.get("largeImage");
        this.smallImage = map.get("smallImage");
        this.largeText = map.get("largeText");
        this.smallText = map.get("smallText");
        this.button1Enable = Boolean.parseBoolean(map.get("button1Enable"));
        this.button1Name = map.get("button1Name");
        this.button1Url = map.get("button1Url");
        this.button2Enable = Boolean.parseBoolean(map.get("button2Enable"));
        this.button2Name = map.get("button2Name");
        this.button2Url = map.get("button2Url");
    }

    public String getDetails() { return details; }
    public String getState() { return state; }
    public String getLargeImage() { return largeImage; }
    public String getSmallImage() { return smallImage; }
    public String getLargeText() { return largeText; }
    public String getSmallText() { return smallText; }
    public boolean isButton1Enable() { return button1Enable; }
    public String getButton1Name() { return button1Name; }
    public String getButton1Url() { return button1Url; }
    public boolean isButton2Enable() { return button2Enable; }
    public String getButton2Name() { return button2Name; }
    public String getButton2Url() { return button2Url; }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("details", details);
        map.put("state", state);
        map.put("largeImage", largeImage);
        map.put("smallImage", smallImage);
        map.put("largeText", largeText);
        map.put("smallText", smallText);
        map.put("button1Enable", String.valueOf(button1Enable));
        map.put("button1Name", button1Name);
        map.put("button1Url", button1Url);
        map.put("button2Enable", String.valueOf(button2Enable));
        map.put("button2Name", button2Name);
        map.put("button2Url", button2Url);
        return map;
    }

    @Override
    public String toString() {
        return "ScopeConfig{" +
               "details='" + details + '\'' +
               ", state='" + state + '\'' +
               ", largeImage='" + largeImage + '\'' +
               ", smallImage='" + smallImage + '\'' +
               ", largeText='" + largeText + '\'' +
               ", smallText='" + smallText + '\'' +
               ", button1Enable=" + button1Enable +
               ", button1Name='" + button1Name + '\'' +
               ", button1Url='" + button1Url + '\'' +
               ", button2Enable=" + button2Enable +
               ", button2Name='" + button2Name + '\'' +
               ", button2Url='" + button2Url + '\'' +
               '}';
    }
}
