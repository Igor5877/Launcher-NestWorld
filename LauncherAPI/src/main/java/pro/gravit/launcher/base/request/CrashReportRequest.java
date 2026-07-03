package pro.gravit.launcher.base.request;

import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;

public class CrashReportRequest extends Request<CrashReportRequestEvent> {
    public final String filename;
    public final String content;
    public final String gameVersion;
    public final String forgeVersion;
    public final String profileName;
    public final long timestamp;

    public CrashReportRequest(String filename, String content, String gameVersion, String forgeVersion, String profileName) {
        this.filename = filename;
        this.content = content;
        this.gameVersion = gameVersion;
        this.forgeVersion = forgeVersion;
        this.profileName = profileName;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String getType() {
        return "crashReport";
    }
}
