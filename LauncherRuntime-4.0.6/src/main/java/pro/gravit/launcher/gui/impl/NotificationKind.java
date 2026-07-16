package pro.gravit.launcher.gui.impl;

public enum NotificationKind {
    INFO("ℹ", "notification-info"),
    SUCCESS("✓", "notification-success"),
    WARNING("⚠", "notification-warning"),
    ERROR("⚠", "notification-error");

    public final String icon;
    public final String styleClass;

    NotificationKind(String icon, String styleClass) {
        this.icon = icon;
        this.styleClass = styleClass;
    }
}
