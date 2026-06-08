package pro.gravit.launchermodules.discordgame;

import pro.gravit.launcher.core.LauncherInject;

import java.util.*;

public class Config {
    @LauncherInject(value = "modules.discordgame.enable")
    public boolean enable;
    @LauncherInject(value = "modules.discordgame.appid")
    public long appId;
    @LauncherInject(value = "modules.discordgame.scopes")
    public Map<String, Map<String, String>> scopes;

    public static Object getDefault() {
        Config config = new Config();
        config.enable = true;
        config.appId = 810913859371532298L;

        config.scopes = new LinkedHashMap<>();

        config.scopes.put("login",
                new ScopeConfig("Кращий проект Minecraft", "Авторизується",
                        "large", "small", "Everything", "Everything", false, "Site", "https://example.com", false, "Forum", "https://example.com").toMap());
        config.scopes.put("authorized",
                new ScopeConfig("Кращий проект Minecraft", "Вибирає сервер",
                        "large", "small", "Everything", "Everything", false, "Site", "https://example.com", false, "Forum", "https://example.com").toMap());
        config.scopes.put("client",
                new ScopeConfig("Кращий проект Minecraft", "Грає на %profileName%",
                        "large", "small", "Everything", "Everything", false, "Site", "https://example.com", false, "Forum", "https://example.com").toMap());

        return config;
    }
}
