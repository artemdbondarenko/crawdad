package io.github.artemdbondarenko.crawdad.service.pairing;

import io.github.artemdbondarenko.crawdad.service.OriginGuard;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import java.util.Objects;

/**
 * One connector of the service as pairing sees it: the guard of its page, the listener its devices
 * are paired through, whether it is served over TLS and the port it actually listens on. The port is
 * part of the key cookie's name, because browsers send a cookie to every port of a host.
 */
public record Site(OriginGuard guard, Listener listener, boolean secure, int port) {

    /** About 400 days, the longest lifetime browsers keep a cookie for. */
    static final long COOKIE_MAX_AGE_SECONDS = 34_560_000;

    public Site {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(listener, "listener");
    }

    /** The name of the key cookie; over TLS the {@code __Host-} prefix ties it to this host and path {@code /}. */
    public String cookieName() {
        return (secure ? "__Host-" : "") + "crawdad_device_" + port;
    }

    /** The {@code Set-Cookie} value that hands {@code key} to the browser. */
    String setCookie(String key) {
        return cookieName() + "=" + key + "; Path=/; Max-Age=" + COOKIE_MAX_AGE_SECONDS
                + (secure ? "; Secure" : "") + "; HttpOnly; SameSite=Strict";
    }
}
