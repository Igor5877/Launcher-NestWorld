package pro.gravit.launcher.base.request;

import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;

public class CrashReportRequest extends Request<CrashReportRequestEvent> {
    public final String filename;
    public final String content;
    public final String gameVersion;
    public final String forgeVersion;
    public final long timestamp;
    
    public CrashReportRequest(String filename, String content, String gameVersion, String forgeVersion) {
        this.filename = filename;
        this.content = content;
        this.gameVersion = gameVersion;
        this.forgeVersion = forgeVersion;
        this.timestamp = System.currentTimeMillis();
    }
    
    @Override
    public String getType() {
        return "crashReport";
    }
}