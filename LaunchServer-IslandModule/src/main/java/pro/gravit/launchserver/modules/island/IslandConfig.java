package pro.gravit.launchserver.modules.island;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public class IslandConfig {
    @SerializedName("enabled")
    public boolean enabled = true;

    @SerializedName("api_url")
    public String apiUrl = "http://localhost:8000/api/v1/islands/launcher/start";

    @SerializedName("api_key")
    public String apiKey = "your_secret_token_here";

    @SerializedName("api_timeout_ms")
    public int apiTimeoutMs = 3000;

    @SerializedName("profiles")
    public List<String> profiles = new ArrayList<>();
}
