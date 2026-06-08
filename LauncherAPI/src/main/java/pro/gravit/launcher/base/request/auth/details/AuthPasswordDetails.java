package pro.gravit.launcher.base.request.auth.details;

import pro.gravit.launcher.base.events.request.GetAvailabilityAuthRequestEvent;

public class AuthPasswordDetails implements GetAvailabilityAuthRequestEvent.AuthAvailabilityDetails {
    public String url;

    @Override
    public String getType() {
        return "password";
    }
}
