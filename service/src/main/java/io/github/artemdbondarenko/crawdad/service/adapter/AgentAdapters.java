package io.github.artemdbondarenko.crawdad.service.adapter;

import io.github.artemdbondarenko.crawdad.adapter.AdapterApi;
import io.github.artemdbondarenko.crawdad.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Finds the adapter the owner named with {@code --agent}: one of the built-in adapters, or a jar in the folder
 * {@code adapters/} of the data directory ({@code specs/agent-adapters.md}). Names and versions of the jars come
 * from their manifests; code is loaded only from the jar of the chosen adapter, after every check has passed
 * ({@code ADAPTER-8}).
 */
public final class AgentAdapters {

    /** The folder of adapters inside the data directory ({@code ADAPTER-3}). */
    public static final String FOLDER = "adapters";

    /** Where a built-in adapter comes from, in the lines for the owner and the log. */
    static final String BUILT_IN = "built in";

    /** The built-in adapters by name ({@code ADAPTER-2}). */
    private static final Map<String, Supplier<AgentAdapter>> BUILT_IN_ADAPTERS =
            Map.of(FakeAgentAdapter.NAME, FakeAgentAdapter::new);

    private static final String API_PACKAGE = AgentAdapter.class.getPackageName();

    private AgentAdapters() {}

    /** A reason of {@code ADAPTER-4}–{@code ADAPTER-8} to refuse the start, told in one line; exit code {@code 1}. */
    public static final class RefusedAdapterException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        RefusedAdapterException(String message) {
            super(message);
        }
    }

    /** The adapter the service talks to, its name, and its jar, empty for a built-in one. */
    public record Chosen(String name, AgentAdapter adapter, Optional<Path> jar) {

        /** "built in" or the path of the jar ({@code ADAPTER-10}). */
        public String source() {
            return jar.map(Path::toString).orElse(BUILT_IN);
        }
    }

    /** An adapter found before any of its code is loaded: built in, or a jar with a valid manifest. */
    private record Found(String name, Optional<Path> jar, int apiVersion) {

        String source() {
            return jar.map(Path::toString).orElse(BUILT_IN);
        }
    }

    /** The names of the built-in adapters, in order, for the line of a start without {@code --agent}. */
    public static List<String> builtInNames() {
        return BUILT_IN_ADAPTERS.keySet().stream().sorted().toList();
    }

    /**
     * Finds the adapter named {@code name} and creates it. {@code owner} is the OS user who runs the service: the
     * folder and the chosen jar must be theirs ({@code ADAPTER-4}). A jar that is not an adapter goes to
     * {@code warn} with the reason, and the search goes on ({@code ADAPTER-8}).
     *
     * @throws RefusedAdapterException for a reason the owner fixes ({@code ADAPTER-4}–{@code ADAPTER-8})
     * @throws IOException             when the folder cannot be read
     * @throws IllegalStateException   when the provider of the chosen jar fails ({@code START-8}), with the cause
     */
    public static Chosen choose(String name, Path dataDirectory, UserPrincipal owner, Consumer<String> warn)
            throws IOException {
        Path folder = dataDirectory.resolve(FOLDER);
        List<Found> found = new ArrayList<>();
        BUILT_IN_ADAPTERS.keySet().forEach(builtIn -> found.add(new Found(builtIn, Optional.empty(),
                AdapterApi.VERSION)));
        if (Files.exists(folder)) {
            if (!Files.isDirectory(folder)) {
                throw new NotDirectoryException(folder.toString());
            }
            requireOwnersOnly(folder, "folder of agent adapters", owner);
            found.addAll(jarsIn(folder, warn));
        }
        // By name, a built-in adapter before the jars, the jars by path.
        found.sort(Comparator.comparing(Found::name).thenComparing(adapter -> adapter.jar().isPresent())
                .thenComparing(Found::source));
        List<Found> named = found.stream().filter(adapter -> adapter.name().equals(name)).toList();
        if (named.isEmpty()) {
            throw new RefusedAdapterException("No agent adapter is named " + LogText.safe(name)
                    + "; the adapters are: " + found.stream()
                            .map(adapter -> adapter.name() + " (" + LogText.safe(adapter.source()) + ")")
                            .collect(Collectors.joining(", ")));
        }
        if (named.size() > 1) {
            throw new RefusedAdapterException(named.size() + " agent adapters are named " + name + ": "
                    + named.stream().map(adapter -> LogText.safe(adapter.source())).collect(Collectors.joining(", "))
                    + "; keep one of them");
        }
        Found chosen = named.getFirst();
        if (chosen.jar().isEmpty()) {
            return new Chosen(name, BUILT_IN_ADAPTERS.get(name).get(), Optional.empty());
        }
        Path jar = chosen.jar().get();
        requireOwnersOnly(jar, "agent adapter", owner);
        if (chosen.apiVersion() != AdapterApi.VERSION) {
            boolean adapterOlder = chosen.apiVersion() < AdapterApi.VERSION;
            throw new RefusedAdapterException("The agent adapter " + name + " in " + LogText.safe(jar.toString())
                    + " is built for version " + chosen.apiVersion() + " of the adapter interface, and the service"
                    + " needs version " + AdapterApi.VERSION + "; " + (adapterOlder
                            ? "the adapter is older: use a build of it for version " + AdapterApi.VERSION
                            : "the service is older: use a newer Crawdad or a build of the adapter for version "
                                    + AdapterApi.VERSION));
        }
        return new Chosen(name, load(jar), Optional.of(jar));
    }

    /** The adapters in the jars of {@code folder}, read from their manifests without loading a class. */
    private static List<Found> jarsIn(Path folder, Consumer<String> warn) throws IOException {
        List<Found> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, "*.jar")) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String why = null;
                try (JarFile jar = new JarFile(entry.toFile(), false)) {
                    Manifest manifest = jar.getManifest();
                    Attributes main = manifest == null ? new Attributes() : manifest.getMainAttributes();
                    String name = main.getValue(AdapterApi.NAME_ATTRIBUTE);
                    String version = main.getValue(AdapterApi.VERSION_ATTRIBUTE);
                    if (name == null) {
                        why = "its manifest has no " + AdapterApi.NAME_ATTRIBUTE;
                    } else if (!AdapterApi.isValidName(name)) {
                        why = "the name in its manifest is not valid: " + name;
                    } else if (version == null) {
                        why = "its manifest has no " + AdapterApi.VERSION_ATTRIBUTE;
                    } else if (!AdapterApi.isValidVersion(version)) {
                        why = "the version in its manifest is not a whole number: " + version;
                    } else {
                        found.add(new Found(name, Optional.of(entry), Integer.parseInt(version)));
                    }
                } catch (IOException | SecurityException e) {
                    why = "it cannot be read as a jar: " + e.getMessage();
                }
                if (why != null) {
                    warn.accept("Not an agent adapter: " + LogText.safe(entry.toString()) + ", "
                            + LogText.safe(why));
                }
            }
        }
        return found;
    }

    /** Refuses a file or folder another OS user owns or can write to: its code would run as the owner. */
    private static void requireOwnersOnly(Path path, String what, UserPrincipal owner) throws IOException {
        UserPrincipal actual = Files.getOwner(path);
        if (!actual.equals(owner)) {
            throw new RefusedAdapterException("The " + what + " " + LogText.safe(path.toString())
                    + " belongs to " + LogText.safe(actual.getName()) + ", not to " + LogText.safe(owner.getName())
                    + ", who runs the service; Crawdad runs only adapters of its own OS user");
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new RefusedAdapterException("Other OS users can write to the " + what + " "
                    + LogText.safe(path.toString()) + ", so Crawdad does not run it; remove their write permission,"
                    + " for example with chmod go-w");
        }
    }

    /**
     * Loads the one provider of {@code jar} in a class loader of its own that sees only the platform, the adapter
     * interface and the jar ({@code ADAPTER-11}), and creates the adapter.
     */
    private static AgentAdapter load(Path jar) {
        URLClassLoader loader;
        try {
            loader = new URLClassLoader("adapter " + jar.getFileName(), new URL[] {jar.toUri().toURL()},
                    new ApiOnly());
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Cannot load the agent adapter " + jar, e);
        }
        try {
            List<ServiceLoader.Provider<AgentAdapterProvider>> providers;
            try {
                providers = ServiceLoader.load(AgentAdapterProvider.class, loader).stream().toList();
            } catch (ServiceConfigurationError e) {
                throw new RefusedAdapterException("The agent adapter " + LogText.safe(jar.toString())
                        + " does not name a usable provider of " + AgentAdapterProvider.class.getName() + ": "
                        + LogText.safe(String.valueOf(e.getMessage())));
            }
            if (providers.size() != 1) {
                throw new RefusedAdapterException("The agent adapter " + LogText.safe(jar.toString()) + " names "
                        + providers.size() + " providers of " + AgentAdapterProvider.class.getName()
                        + "; it must name exactly one");
            }
            AgentAdapter adapter;
            try {
                adapter = providers.getFirst().get().create();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
                throw new IllegalStateException("The agent adapter " + jar + " failed to start: " + e, e);
            }
            if (adapter == null) {
                throw new IllegalStateException("The agent adapter " + jar + " created no adapter");
            }
            return adapter;
        } catch (RuntimeException e) {
            try {
                loader.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /**
     * The parent of every adapter's class loader: the Java platform, and from the service only the classes of the
     * adapter interface. The libraries of the service stay out of an adapter's sight ({@code ADAPTER-11}).
     */
    private static final class ApiOnly extends ClassLoader {

        ApiOnly() {
            super("crawdad-adapter-api", ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            int lastDot = name.lastIndexOf('.');
            if (lastDot > 0 && name.substring(0, lastDot).equals(API_PACKAGE)) {
                return AgentAdapter.class.getClassLoader().loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
