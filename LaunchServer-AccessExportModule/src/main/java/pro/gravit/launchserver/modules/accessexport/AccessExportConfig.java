package pro.gravit.launchserver.modules.accessexport;

import com.google.gson.annotations.SerializedName;

public class AccessExportConfig {
    @SerializedName("enabled")
    public boolean enabled = true;

    /**
     * Name of the auth provider ("auth" section of LaunchServer.json) whose database holds the Azuriom tables.
     * Empty: the first AzuriomCoreProvider with an "sql" section.
     */
    @SerializedName("auth_id")
    public String authId = "";

    /**
     * Prefix of the Azuriom tables (DB_PREFIX of the site, empty by default).
     */
    @SerializedName("table_prefix")
    public String tablePrefix = "";

    /**
     * How often the profiles are compared with what was exported last time.
     */
    @SerializedName("interval_seconds")
    public int intervalSeconds = 60;
}
