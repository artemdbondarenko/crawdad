package io.github.artemdbondarenko.crawdad.service;

import java.io.PrintStream;

/** Entry point of the service: {@code java -jar crawdad-service.jar [--host <address>] [--port <number>]}. */
public final class CrawdadService {

    /** Classpath directory with the files of the web client. */
    static final String CLIENT_FILES = "static/";

    private CrawdadService() {}

    public static void main(String[] args) throws Exception {
        run(args, System.out).join();
    }

    /** Starts the server and tells the owner where to open it. */
    static CrawdadServer run(String[] args, PrintStream out) throws Exception {
        CrawdadServer server = CrawdadServer.start(ServiceConfig.fromArgs(args), CLIENT_FILES);
        out.println(ServiceInfo.current().displayName() + " is running. Open " + server.address() + " in a browser.");
        return server;
    }
}
