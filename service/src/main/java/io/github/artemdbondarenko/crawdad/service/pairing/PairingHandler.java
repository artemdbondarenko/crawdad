package io.github.artemdbondarenko.crawdad.service.pairing;

import io.github.artemdbondarenko.crawdad.service.device.Device;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.NewDevice;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import io.github.artemdbondarenko.crawdad.service.log.WarningAggregator;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /pair} exchanges the pairing code for a device key in a cookie; {@code GET /device} tells
 * whether that key works on this address and renews the cookie. Each connector is a {@link Site}: its
 * guard is checked before anything else, and a key works only on the site it was issued on. Every
 * other path goes to the wrapped handler.
 */
public final class PairingHandler extends Handler.Wrapper {

    public static final String PAIR_PATH = "/pair";
    public static final String DEVICE_PATH = "/device";

    /** The largest body of {@code POST /pair} that is read; a larger one is a wrong attempt. */
    static final int MAX_BODY = 1024;

    private static final Logger LOG = System.getLogger(PairingHandler.class.getName());
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** A device and the key it presented, which renews its cookie. */
    public record KeyedDevice(Device device, String key) {

        /** Keeps the key out of logs and error messages. */
        @Override
        public String toString() {
            return "KeyedDevice[device=" + device + ", key=<hidden>]";
        }
    }

    private final PairingCodes codes;
    private final DeviceRegistry devices;
    private final Function<Connector, Site> sites;
    private final WarningAggregator warnings = new WarningAggregator(LOG);

    /** {@code sites} gives the site of the connector a request arrived on. */
    public PairingHandler(Handler next, PairingCodes codes, DeviceRegistry devices, Function<Connector, Site> sites) {
        super(next);
        this.codes = Objects.requireNonNull(codes, "codes");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.sites = Objects.requireNonNull(sites, "sites");
    }

    /**
     * Writing the registry blocks, so Jetty must not call this on a selector thread. The body of
     * {@code POST /pair} is read without blocking: a slow sender holds a connection, never a thread.
     */
    @Override
    public InvocationType getInvocationType() {
        return InvocationType.BLOCKING;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = Request.getPathInContext(request);
        if (path.equals(PAIR_PATH)) {
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            pair(request, response, callback);
            return true;
        }
        if (path.equals(DEVICE_PATH)) {
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            device(request, response, callback);
            return true;
        }
        return super.handle(request, response, callback);
    }

    private void pair(Request request, Response response, Callback callback) {
        if (!HttpMethod.POST.is(request.getMethod())) {
            methodNotAllowed(HttpMethod.POST, response, callback);
            return;
        }
        Site site = sites.apply(request.getConnectionMetaData().getConnector());
        String address = Request.getRemoteAddr(request);
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);
        String host = request.getHeaders().get(HttpHeader.HOST);
        // A foreign page is refused before the code is looked at, so it cannot spend the owner's attempts.
        if (!site.guard().allows(origin, host)) {
            // The aggregator escapes the whole message; escaping here too would double every backslash.
            warnings.warn(address, "Pairing refused: Origin=" + origin + ", Host=" + host);
            json(response, callback, 403, Map.of("error", "forbidden"));
            return;
        }
        // The rest runs once the whole body is in, on a thread of the pool; nothing waits for it meanwhile.
        Content.Source.asByteArrayAsync(request, MAX_BODY, Promise.Invocable.from(InvocationType.BLOCKING,
                (body, failure) -> {
                    try {
                        exchange(request, response, callback, site, address, body == null ? null : codeIn(body));
                    } catch (RuntimeException e) {
                        callback.failed(e);
                    }
                }));
    }

    /**
     * Exchanges the {@code entered} code, null when the body was not {@code {"code":"123456"}}, too large
     * or not read to the end; anything but exactly six ASCII digits still counts as a wrong attempt.
     */
    private void exchange(Request request, Response response, Callback callback, Site site, String address,
            String entered) {
        // "" never matches a code.
        if (!(codes.attempt(entered == null ? "" : entered, address)
                instanceof PairingCodes.Attempt.Accepted accepted)) {
            warnings.warn(address, "Wrong pairing code");
            json(response, callback, 401, Map.of("error", "invalid_code"));
            return;
        }
        String userAgent = request.getHeaders().get(HttpHeader.USER_AGENT);
        boolean reported = false;
        try {
            NewDevice added;
            try {
                added = devices.add(accepted.requestedName().orElseGet(() -> nameFor(userAgent)), site.listener());
            } catch (IOException | RuntimeException e) {
                // The cause names the file, never the key or the code.
                LOG.log(Level.ERROR, "Could not save the device paired from " + LogText.safe(address), e);
                // Before the answer, as on success: the client must not learn the result ahead of crawdad pair.
                accepted.failed();
                reported = true;
                json(response, callback, 500, Map.of("error", "server_error"));
                return;
            }
            String name = added.device().name();
            accepted.paired(name, address, userAgent);
            reported = true;
            LOG.log(Level.INFO, "Device paired: {0} from {1}", LogText.safe(name), LogText.safe(address));
            response.getHeaders().add(HttpHeader.SET_COOKIE, site.setCookie(added.key()));
            response.setStatus(204);
            response.write(true, null, callback);
        } finally {
            // Whatever went wrong, the waiting crawdad pair learns that this code ended.
            if (!reported) {
                accepted.failed();
            }
        }
    }

    /** The code of a body {@code {"code":"123456"}}; null for anything else. */
    private static String codeIn(byte[] body) {
        JsonNode code;
        try {
            code = JSON.readTree(body).get("code");
        } catch (JacksonException e) {
            return null;
        }
        if (code == null || !code.isString() || !CODE.matcher(code.stringValue()).matches()) {
            return null;
        }
        return code.stringValue();
    }

    private void device(Request request, Response response, Callback callback) {
        if (!HttpMethod.GET.is(request.getMethod())) {
            methodNotAllowed(HttpMethod.GET, response, callback);
            return;
        }
        Site site = sites.apply(request.getConnectionMetaData().getConnector());
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);
        String host = request.getHeaders().get(HttpHeader.HOST);
        if (!site.guard().allowsWithoutOrigin(origin, host)) {
            warnings.warn(Request.getRemoteAddr(request), "Device check refused: Origin=" + origin + ", Host=" + host);
            json(response, callback, 403, Map.of("error", "forbidden"));
            return;
        }
        Optional<KeyedDevice> found = deviceOf(request, site, devices);
        if (found.isPresent()) {
            response.getHeaders().add(HttpHeader.SET_COOKIE, site.setCookie(found.get().key()));
            json(response, callback, 200, Map.of("name", found.get().device().name()));
            return;
        }
        // Not logged: the client asks at every reconnect, and a refused /ws is logged already.
        json(response, callback, 401, Map.of("error", "not_paired"));
    }

    /**
     * The active device whose key the request carries in the key cookie of {@code site}, if that key was
     * paired on this site. A browser may send several cookies of this name, such as one left by another
     * service on the same host; the first that works here counts.
     */
    public static Optional<KeyedDevice> deviceOf(Request request, Site site, DeviceRegistry devices) {
        for (HttpCookie cookie : Request.getCookies(request)) {
            if (!cookie.getName().equals(site.cookieName())) {
                continue;
            }
            Optional<Device> device = devices.authenticate(cookie.getValue(), site.listener());
            if (device.isPresent()) {
                return Optional.of(new KeyedDevice(device.get(), cookie.getValue()));
            }
        }
        return Optional.empty();
    }

    private static void methodNotAllowed(HttpMethod allowed, Response response, Callback callback) {
        response.getHeaders().put(HttpHeader.ALLOW, allowed.asString());
        json(response, callback, 405, Map.of("error", "method_not_allowed"));
    }

    private static void json(Response response, Callback callback, int status, Map<String, String> body) {
        byte[] content = JSON.writeValueAsBytes(body);
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.length);
        response.write(true, ByteBuffer.wrap(content), callback);
    }

    /**
     * The name of a device paired without {@code --name}: {@code <browser> on <OS>} from its
     * {@code User-Agent}, such as {@code Chrome on Android}; {@code Browser} for an unknown browser, and
     * no {@code on ...} for an unknown system. The raw header never becomes the name.
     */
    static String nameFor(String userAgent) {
        if (userAgent == null) {
            return "Browser";
        }
        String os = operatingSystem(userAgent);
        return browser(userAgent) + (os == null ? "" : " on " + os);
    }

    /** Edge and Chrome both say {@code Safari}, and Edge also says {@code Chrome}: the order matters. */
    private static String browser(String userAgent) {
        if (containsAny(userAgent, "Edg/", "Edge/", "EdgA/", "EdgiOS/")) {
            return "Edge";
        }
        if (containsAny(userAgent, "Chrome/", "CriOS/")) {
            return "Chrome";
        }
        if (containsAny(userAgent, "Firefox/", "FxiOS/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "Browser";
    }

    /** Android says {@code Linux}, and iOS says {@code like Mac OS X}: the order matters. */
    private static String operatingSystem(String userAgent) {
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (containsAny(userAgent, "iPhone", "iPad", "iPod")) {
            return "iOS";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (containsAny(userAgent, "Macintosh", "Mac OS X")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }

    private static boolean containsAny(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
