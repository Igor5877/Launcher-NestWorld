package pro.gravit.launcher.base.events.request;

import pro.gravit.launcher.base.events.RequestEvent;

public class CrashReportRequestEvent extends RequestEvent {
    public final boolean success;
    public final String message;
    public final String savedPath;
    
    public CrashReportRequestEvent(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.savedPath = null;
    }
    
    public CrashReportRequestEvent(boolean success, String message, String savedPath) {
        this.success = success;
        this.message = message;
        this.savedPath = savedPath;
    }
    
    @Override
    public String getType() {
        return "crashReport";
    }
}