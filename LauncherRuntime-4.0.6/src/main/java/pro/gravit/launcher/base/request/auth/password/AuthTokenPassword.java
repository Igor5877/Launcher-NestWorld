package pro.gravit.launcher.base.request.auth.password;

import pro.gravit.launcher.base.request.auth.AuthRequest;

public class AuthTokenPassword implements AuthRequest.AuthPasswordInterface {
    public String token;

    public AuthTokenPassword(String token) {
        this.token = token;
    }

    @Override
    public boolean check() throws SecurityException {
        return true;
    }
}
