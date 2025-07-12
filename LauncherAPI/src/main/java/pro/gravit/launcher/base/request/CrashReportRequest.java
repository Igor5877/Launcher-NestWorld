package pro.gravit.launcher.base.request;

import pro.gravit.launcher.base.events.request.CrashReportRequestEvent;

import java.nio.charset.StandardCharsets;

public class CrashReportRequest extends Request<CrashReportRequestEvent> {
    public final String filename;
    public final byte[] content;
    public final String gameVersion;
    public final String forgeVersion;
    public final long timestamp;

    public CrashReportRequest(String filename, String content, String gameVersion, String forgeVersion) {
        this.filename = filename;
        this.content = content.getBytes(StandardCharsets.UTF_8);
        this.gameVersion = gameVersion;
        this.forgeVersion = forgeVersion;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String getType() {
        return "crashReport";
    }
}