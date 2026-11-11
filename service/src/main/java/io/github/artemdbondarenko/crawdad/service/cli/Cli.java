package io.github.artemdbondarenko.crawdad.service.cli;

import io.github.artemdbondarenko.crawdad.service.control.ControlSocket;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The owner's commands {@code pair [--name <name>]}, {@code devices [--all]}, {@code revoke <name>} and
 * {@code tls export-ca}, run from the service jar. All but {@code tls export-ca} are clients of the control
 * socket of the running service. Exit codes: 0 done, 1 failed or not done, 2 wrong arguments. Values from
 * the service, such as device names, browsers and addresses, are printed through {@link LogText}.
 */
public final class Cli {

    static final int DONE = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private static final Set<String> COMMANDS = Set.of("pair", "devices", "revoke", "tls");
    private static final String USAGE_LINE =
            "Usage: pair [--name <name>] | devices [--all] | revoke <name> | tls export-ca";
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] COLUMNS = {"NAME", "PAIRED", "LAST SEEN", "ADDRESS", "BROWSER", "LISTENER"};

    /** The answer of the service is not one the contract allows. */
    private static final class UnexpectedAnswerException extends Exception {

        private static final long serialVersionUID = 1L;

        UnexpectedAnswerException(Object answer) {
            super("Unexpected answer from Crawdad: " + LogText.safe(String.valueOf(answer)));
        }
    }

    private final Path socket;
    private final Path data;
    private final String startCommand;
    private final PrintStream out;
    private final PrintStream err;

    private Cli(DataDirectory data, String startCommand, PrintStream out, PrintStream err) {
        this.socket = data.runtime().resolve(ControlSocket.FILE_NAME);
        this.data = data.data();
        this.startCommand = startCommand;
        this.out = out;
        this.err = err;
    }

    /** Whether {@code args} name a command; anything else is the options of the service itself. */
    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0]);
    }

    /**
     * Finds the data directory the way the service does, but without its warning about a missing
     * {@code XDG_RUNTIME_DIR}: the service has already told the owner where its socket is.
     */
    public static DataDirectory dataDirectory(Supplier<DataDirectory> lookup) {
        // DataDirectory logs through System.Logger, which goes to java.util.logging under the same name.
        Logger logger = Logger.getLogger(DataDirectory.class.getName());
        Level before = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            return lookup.get();
        } finally {
            logger.setLevel(before);
        }
    }

    /**
     * Runs the command in {@code args} against the service whose files are in {@code data}.
     * {@code startCommand} is how the owner starts the service, for the hint when it is not running.
     */
    public static int run(String[] args, DataDirectory data, String startCommand, PrintStream out, PrintStream err) {
        return new Cli(data, startCommand, out, err).run(List.of(args));
    }

    private int run(List<String> args) {
        List<String> options = args.subList(1, args.size());
        try {
            return switch (args.getFirst()) {
                case "pair" -> pairCommand(options);
                case "devices" -> devicesCommand(options);
                case "revoke" -> options.size() == 1 ? revoke(options.getFirst()) : usage();
                case "tls" -> options.equals(List.of("export-ca")) ? exportCa() : usage();
                default -> usage();
            };
        } catch (UnexpectedAnswerException e) {
            err.println(e.getMessage());
            return FAILED;
        }
    }

    private int usage() {
        err.println(USAGE_LINE);
        return USAGE;
    }

    private int pairCommand(List<String> options) throws UnexpectedAnswerException {
        if (options.isEmpty()) {
            return pair(null);
        }
        if (options.size() == 2 && options.getFirst().equals("--name")) {
            return pair(options.get(1));
        }
        return usage();
    }

    private int devicesCommand(List<String> options) throws UnexpectedAnswerException {
        if (options.isEmpty()) {
            return devices(false);
        }
        if (options.equals(List.of("--all"))) {
            return devices(true);
        }
        return usage();
    }

    /**
     * Asks for a code, prints it with the links and, with a network address, a QR code, then waits for how
     * it ends. The request side stays open while waiting: the socket takes its end as Ctrl+C.
     */
    private int pair(String name) throws UnexpectedAnswerException {
        ObjectNode request = request("pair");
        if (name != null) {
            request.put("name", name);
        }
        try (Connection connection = connect()) {
            if (connection == null) {
                return notRunning();
            }
            JsonNode first = connection.ask(request);
            if (first == null) {
                return stopped();
            }
            if ("invalid_name".equals(text(first, "error"))) {
                err.println("The device name must be 1 to " + ControlSocket.MAX_NAME_LENGTH
                        + " characters, without control characters.");
                return FAILED;
            }
            String code = text(first, "code");
            JsonNode urls = first.get("urls");
            String loopback = urls == null ? null : text(urls, "loopback");
            String network = urls == null ? null : text(urls, "network");
            JsonNode expiresIn = first.get("expires_in");
            if (code == null || !CODE.matcher(code).matches() || loopback == null || expiresIn == null
                    || !expiresIn.isIntegralNumber()) {
                throw new UnexpectedAnswerException(first);
            }
            JsonNode previous = first.get("burned");
            if (previous != null) {
                out.println("The previous code burned after wrong attempts from: " + addresses(previous));
            }
            out.println("Pairing code: " + code + " (valid for " + term(expiresIn.longValue()) + ")");
            out.println("On this machine, open: " + LogText.safe(loopback + "#pair=" + code));
            if (network == null) {
                out.println("To pair a phone, start Crawdad with --host <private address> and run pair again.");
            } else {
                String link = network + "#pair=" + code;
                out.println(QrText.render(link));
                out.println("On another device, scan the code or open: " + LogText.safe(link));
            }
            out.println("Waiting for the code to be used; Ctrl+C stops waiting and the code stays valid.");
            return outcome(connection.next());
        } catch (IOException e) {
            return stopped();
        }
    }

    private int outcome(JsonNode line) throws UnexpectedAnswerException {
        if (line == null) {
            return stopped();
        }
        JsonNode paired = line.get("paired");
        if (paired != null && text(paired, "name") != null && text(paired, "address") != null) {
            String browser = text(paired, "user_agent");
            out.println("Paired " + LogText.safe(text(paired, "name")) + " from "
                    + LogText.safe(text(paired, "address")) + ", browser: "
                    + (browser == null ? "unknown" : LogText.safe(browser)));
            return DONE;
        }
        if (line.has("expired")) {
            err.println("The code expired; run pair again for a new one.");
        } else if (line.has("burned")) {
            err.println("The code burned after wrong attempts from: " + addresses(line.get("burned")));
        } else if (line.has("replaced")) {
            err.println("The code was replaced by a newer pair command.");
        } else if (line.has("failed")) {
            err.println("Pairing failed: the service could not save the device; see the service log");
        } else {
            throw new UnexpectedAnswerException(line);
        }
        return FAILED;
    }

    private int devices(boolean all) throws UnexpectedAnswerException {
        ObjectNode request = request("devices");
        request.put("all", all);
        JsonNode answer;
        try (Connection connection = connect()) {
            if (connection == null) {
                return notRunning();
            }
            answer = connection.ask(request);
        } catch (IOException e) {
            return lostConnection();
        }
        if (answer == null) {
            return lostConnection();
        }
        JsonNode list = answer.get("devices");
        if (list == null || !list.isArray()) {
            throw new UnexpectedAnswerException(answer);
        }
        if (list.isEmpty()) {
            out.println("No paired devices.");
            return DONE;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(all ? append(COLUMNS, "REVOKED") : COLUMNS);
        for (JsonNode device : list) {
            String[] row = {
                cell(device, "name", "-"),
                cell(device, "paired_at", "-"),
                cell(device, "last_seen", "never"),
                cell(device, "last_address", "-"),
                cell(device, "last_user_agent", "-"),
                cell(device, "listener", "-"),
            };
            rows.add(all ? append(row, cell(device, "revoked_at", "-")) : row);
        }
        printTable(rows);
        return DONE;
    }

    private int revoke(String name) throws UnexpectedAnswerException {
        ObjectNode request = request("revoke");
        request.put("name", name);
        JsonNode answer;
        try (Connection connection = connect()) {
            if (connection == null) {
                return notRunning();
            }
            answer = connection.ask(request);
        } catch (IOException e) {
            return lostConnection();
        }
        if (answer == null) {
            return lostConnection();
        }
        String revoked = text(answer, "revoked");
        if (revoked != null) {
            out.println("Revoked " + LogText.safe(revoked) + "; its open connections are closed.");
            return DONE;
        }
        String error = text(answer, "error");
        if ("not_found".equals(error) && answer.has("active")) {
            String active = names(answer.get("active"));
            err.println("No active device named " + LogText.safe(name) + ". "
                    + (active.isEmpty() ? "There are no active devices." : "Active devices: " + active));
        } else if ("already_revoked".equals(error)) {
            err.println(LogText.safe(name) + " was already revoked at " + cell(answer, "revoked_at", "?") + ".");
        } else if ("server_error".equals(error)) {
            err.println("Crawdad could not save the revocation; see the service log.");
        } else {
            throw new UnexpectedAnswerException(answer);
        }
        return FAILED;
    }

    /** Prints the service's own root as PEM and its fingerprint on stderr; reads the file, not the service. */
    private int exportCa() {
        Path file = data.resolve(LocalCa.DIRECTORY).resolve(LocalCa.CERTIFICATE_FILE);
        X509Certificate root;
        try {
            byte[] content = Files.readAllBytes(file);
            root = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(content));
            byte[] der = root.getEncoded();
            String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
            out.print("-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n");
            out.flush();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            err.println("SHA-256 fingerprint: " + HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest));
            return DONE;
        } catch (NoSuchFileException e) {
            err.println("No root certificate yet: start Crawdad with --host <private address> first.");
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
            err.println(file + " is not a readable certificate: " + LogText.safe(e.getMessage()));
        }
        return FAILED;
    }

    private int notRunning() {
        err.println("Crawdad is not running; start it with: " + startCommand);
        return FAILED;
    }

    private int lostConnection() {
        err.println("Crawdad closed the connection without an answer; see the service log.");
        return FAILED;
    }

    private int stopped() {
        err.println("Crawdad stopped before the code was used.");
        return FAILED;
    }

    /** A connection to the socket, or {@code null} when nothing accepts there: no file, refused, not allowed. */
    private Connection connect() {
        try {
            return new Connection(SocketChannel.open(UnixDomainSocketAddress.of(socket)));
        } catch (IOException e) {
            return null;
        }
    }

    private static ObjectNode request(String op) {
        ObjectNode request = JSON.createObjectNode();
        request.put("op", op);
        return request;
    }

    /** {@code 5 minutes} for 300 seconds; seconds when they are not whole minutes. */
    private static String term(long seconds) {
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private static String addresses(JsonNode burned) {
        return names(burned.get("addresses"));
    }

    /** The strings of a JSON array, each made safe, comma-separated; empty for no array. */
    private static String names(JsonNode list) {
        List<String> names = new ArrayList<>();
        if (list != null) {
            list.forEach(entry -> names.add(LogText.safe(entry.isString() ? entry.stringValue() : entry.toString())));
        }
        return String.join(", ", names);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static String cell(JsonNode node, String field, String absent) {
        String value = text(node, field);
        return value == null ? absent : LogText.safe(value);
    }

    private static String[] append(String[] row, String cell) {
        String[] longer = Arrays.copyOf(row, row.length + 1);
        longer[row.length] = cell;
        return longer;
    }

    /** Columns padded to their widest cell and two spaces apart. */
    private void printTable(List<String[]> rows) {
        int[] widths = new int[rows.getFirst().length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].codePointCount(0, row[i].length()));
            }
        }
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(row[i]);
                if (i < row.length - 1) {
                    line.append(" ".repeat(widths[i] - row[i].codePointCount(0, row[i].length()) + 2));
                }
            }
            out.println(line.toString().stripTrailing());
        }
    }

    /** One request and its answer lines on the control socket. */
    private static final class Connection implements Closeable {

        private final SocketChannel channel;
        private final BufferedReader in;

        Connection(SocketChannel channel) {
            this.channel = channel;
            this.in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8));
        }

        /** Sends the request line and returns the first answer line, {@code null} when the service hung up. */
        JsonNode ask(ObjectNode request) throws IOException, UnexpectedAnswerException {
            ByteBuffer line = ByteBuffer.wrap((JSON.writeValueAsString(request) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            while (line.hasRemaining()) {
                channel.write(line);
            }
            return next();
        }

        /** The next answer line, {@code null} when the service hung up. */
        JsonNode next() throws IOException, UnexpectedAnswerException {
            String line = in.readLine();
            if (line == null) {
                return null;
            }
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (JacksonException e) {
                throw new UnexpectedAnswerException(line);
            }
            if (node == null || !node.isObject()) {
                throw new UnexpectedAnswerException(node);
            }
            return node;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
