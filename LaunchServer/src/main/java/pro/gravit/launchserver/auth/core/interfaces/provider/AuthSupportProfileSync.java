package pro.gravit.launchserver.auth.core.interfaces.provider;

import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launchserver.auth.Feature;

import java.util.Set;

@Feature("profileSync")
public interface AuthSupportProfileSync extends AuthSupport {
    void syncProfiles(Set<ClientProfile> profiles);
}
