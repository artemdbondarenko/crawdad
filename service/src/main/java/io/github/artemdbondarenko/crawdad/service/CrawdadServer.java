package io.github.artemdbondarenko.crawdad.service;

import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

/**
 * The HTTP server of the service: one port that serves the files of the web client and accepts the
 * client's WebSocket on {@link #WEBSOCKET_PATH}. Started with {@link #start}; the owner opens
 * {@link #address()} in a browser.
 */
public final class CrawdadServer {

    public static final String WEBSOCKET_PATH = "/ws";

    /** The largest text frame a client may send, in bytes; a larger one closes the connection (1009). */
    public static final int MAX_TEXT_MESSAGE_SIZE = 64 * 1024;

    private static final Logger LOG = System.getLogger(CrawdadServer.class.getName());

    private final Server server;
    private final ServerConnector connector;
    private final AgentAdapter adapter;

    private CrawdadServer(Server server, ServerConnector connector, AgentAdapter adapter) {
        this.server = server;
        this.connector = connector;
        this.adapter = adapter;
    }

    /** Starts the server with the {@link FakeAgentAdapter}, the only adapter so far. */
    public static CrawdadServer start(ServiceConfig config, String staticRoot) throws Exception {
        return start(config, staticRoot, new FakeAgentAdapter());
    }

    /**
     * Starts the server. {@code staticRoot} is a classpath directory with the client files, such as
     * {@code "static/"}; the service refuses to start without it. Each WebSocket connection becomes
     * one session of {@code adapter}.
     */
    public static CrawdadServer start(ServiceConfig config, String staticRoot, AgentAdapter adapter)
            throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(config.host());
        connector.setPort(config.port());
        server.addConnector(connector);
        CrawdadServer crawdad = new CrawdadServer(server, connector, adapter);

        ResourceHandler files = new ResourceHandler();
        Resource root = ResourceFactory.of(files).newClassLoaderResource(staticRoot);
        if (root == null || !root.exists()) {
            throw new IllegalStateException("Client files not found on the classpath: " + staticRoot);
        }
        files.setBaseResource(root);
        files.setWelcomeFiles(List.of("index.html"));
        files.setDirAllowed(false);

        WebSocketUpgradeHandler webSocket = WebSocketUpgradeHandler.from(server, container -> {
            container.setMaxTextMessageSize(MAX_TEXT_MESSAGE_SIZE);
            container.addMapping(WEBSOCKET_PATH, crawdad::createWebSocket);
        });
        webSocket.setHandler(files);
        server.setHandler(webSocket);

        // Ctrl+C or a stop signal closes the port and open connections cleanly.
        server.setStopAtShutdown(true);
        server.start();
        return crawdad;
    }

    /** The address to open in a browser, such as {@code http://127.0.0.1:8460/}. */
    public URI address() {
        return URI.create("http://" + connector.getHost() + ":" + connector.getLocalPort() + "/");
    }

    public void stop() throws Exception {
        server.stop();
    }

    /** Waits until the server stops. */
    public void join() throws InterruptedException {
        server.join();
    }

    /** Accepts the handshake only from the page this server serves; otherwise 403 and no session. */
    private Object createWebSocket(ServerUpgradeRequest request, ServerUpgradeResponse response,
            Callback callback) {
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);
        String host = request.getHeaders().get(HttpHeader.HOST);
        if (!new OriginGuard(address().getAuthority()).allows(origin, host)) {
            LOG.log(Level.WARNING, "WebSocket refused: Origin={0}, Host={1}", origin, host);
            Response.writeError(request, response, callback, 403);
            return null;
        }
        LOG.log(Level.INFO, "WebSocket connected: Origin={0}", origin);
        return new Endpoint(adapter);
    }

    /**
     * Jetty's listener for one accepted WebSocket; hands frames to a {@link ClientConnection}. Public
     * because Jetty calls the listener methods only on a public class.
     */
    public static final class Endpoint implements Session.Listener.AutoDemanding {

        private final AgentAdapter adapter;
        private ClientConnection connection;

        private Endpoint(AgentAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            connection = new ClientConnection(adapter, text -> session.sendText(text,
                    org.eclipse.jetty.websocket.api.Callback.from(() -> {}, Endpoint::logSendFailure)));
            connection.open();
        }

        @Override
        public void onWebSocketText(String text) {
            connection.receive(text);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            if (isClientGone(cause)) {
                LOG.log(Level.INFO, "WebSocket client disconnected: {0}", cause.toString());
            } else {
                LOG.log(Level.WARNING, "WebSocket error", cause);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            LOG.log(Level.INFO, "WebSocket closed: {0} {1}", String.valueOf(statusCode), reason);
        }

        /** A client that left mid-send is routine: one line, no stack trace. */
        private static void logSendFailure(Throwable failure) {
            if (isClientGone(failure)) {
                LOG.log(Level.INFO, "WebSocket send skipped, the client has disconnected: {0}", failure.toString());
            } else {
                LOG.log(Level.WARNING, "WebSocket send failed", failure);
            }
        }

        /** Jetty marks errors of a closed peer as {@link QuietException}. */
        static boolean isClientGone(Throwable failure) {
            return failure instanceof QuietException || failure instanceof ClosedChannelException;
        }
    }
}
