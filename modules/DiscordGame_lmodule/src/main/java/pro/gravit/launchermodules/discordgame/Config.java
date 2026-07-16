package pro.gravit.launchermodules.discordgame;

import pro.gravit.launcher.core.LauncherInject;

import java.util.LinkedHashMap;
import java.util.Map;

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
        config.appId = 0L;

        config.scopes = new LinkedHashMap<>();

        config.scopes.put("login",
                new ScopeConfig("NestWorld Launcher", "Авторизується",
                        "large", "small", "NestWorld", "NestWorld", false, "Site", "https://nestworld.site", false, "Discord", "https://nestworld.site").toMap());
        config.scopes.put("authorized",
                new ScopeConfig("NestWorld Launcher", "Вибирає сервер",
                        "large", "small", "NestWorld", "NestWorld", false, "Site", "https://nestworld.site", false, "Discord", "https://nestworld.site").toMap());
        config.scopes.put("server",
                new ScopeConfig("NestWorld Launcher", "Переглядає %serverName%",
                        "large", "small", "NestWorld", "NestWorld", false, "Site", "https://nestworld.site", false, "Discord", "https://nestworld.site").toMap());
        config.scopes.put("client",
                new ScopeConfig("NestWorld Launcher", "Грає на %profileName%",
                        "large", "small", "NestWorld", "NestWorld", false, "Site", "https://nestworld.site", false, "Discord", "https://nestworld.site").toMap());

        return config;
    }
}
