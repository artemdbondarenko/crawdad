package io.github.artemdbondarenko.crawdad.service;

import javax.net.ssl.SSLSession;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;

/**
 * The request customizer of the network address. Besides Jetty's own check that the certificate covers
 * {@code Host}, a request whose {@code Host} differs from the TLS server name the browser asked for is
 * answered 400 ({@code NET-10}). Without a server name, as a browser connects to an IP address, there is
 * nothing to compare with. Names are compared ignoring case, as DNS does.
 */
final class ServerNameCheck extends SecureRequestCustomizer {

    @Override
    protected void checkSni(Request request, SSLSession session) {
        super.checkSni(request, session);
        String serverName = retrieveSni(request, session);
        if (serverName != null && !serverName.equalsIgnoreCase(Request.getServerName(request))) {
            throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "Host differs from the TLS server name");
        }
    }
}
