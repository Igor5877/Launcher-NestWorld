package pro.gravit.launcher.base.request;

import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;

public class CrashReportRequest extends Request<CrashReportRequestEvent> {
    public final String filename;
    public final String content;
    public final String gameVersion;
    public final String forgeVersion;
    public final long timestamp;
    public final boolean isPart;
    public final boolean isLastPart;


    public CrashReportRequest(String filename, String content, String gameVersion, String forgeVersion, boolean isPart, boolean isLastPart) {
        this.filename = filename;
        this.content = content;
        this.gameVersion = gameVersion;
        this.forgeVersion = forgeVersion;
        this.timestamp = System.currentTimeMillis();
        this.isPart = isPart;
        this.isLastPart = isLastPart;
    }

    public CrashReportRequest(String filename, String content, String gameVersion, String forgeVersion) {
        this(filename, content, gameVersion, forgeVersion, false, true);
    }


    @Override
    public String getType() {
        return "crashReport";
    }
}