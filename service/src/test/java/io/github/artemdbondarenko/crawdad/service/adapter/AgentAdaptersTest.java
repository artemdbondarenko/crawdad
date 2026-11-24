package io.github.artemdbondarenko.crawdad.service.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.artemdbondarenko.crawdad.adapter.AdapterApi;
import io.github.artemdbondarenko.crawdad.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.adapter.AgentAdapterProvider;
import io.github.artemdbondarenko.crawdad.adapter.AgentEvent;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapters.RefusedAdapterException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentAdaptersTest {

    private static final String SERVICES = "META-INF/services/" + AgentAdapterProvider.class.getName();

    @TempDir
    Path temp;

    private Path data;
    private Path folder;
    private UserPrincipal owner;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        data = temp.resolve("data");
        folder = data.resolve(AgentAdapters.FOLDER);
        Files.createDirectories(data);
        owner = Files.getOwner(temp);
    }

    @Test
    @Tag("ADAPTER-2")
    @Tag("ADAPTER-3")
    @Tag("FAKE-4")
    void theFakeAgentIsBuiltInAndNeedsNoFolder() throws Exception {
        AgentAdapters.Chosen chosen = choose("fake");

        assertThat(chosen.adapter()).isInstanceOf(FakeAgentAdapter.class);
        assertThat(chosen.jar()).isEmpty();
        assertThat(chosen.source()).isEqualTo("built in");
        assertThat(AgentAdapters.builtInNames()).containsExactly("fake");
        assertThat(Files.exists(folder)).as("the folder is not created").isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    @Tag("ADAPTER-3")
    @Tag("ADAPTER-8")
    @Tag("ADAPTER-9")
    void anAdapterFromTheFolderIsLoadedFromItsJar() throws Exception {
        Path jar = adapterJar("echo-adapter-1.2.jar", "echo", "1", TestAdapterProvider.class);

        AgentAdapters.Chosen chosen = choose("echo");

        assertThat(chosen.name()).isEqualTo("echo");
        assertThat(chosen.jar()).contains(jar);
        assertThat(chosen.source()).isEqualTo(jar.toString());
        assertThat(chosen.adapter().executesRepositoryConfiguration()).isTrue();
        List<AgentEvent> events = new ArrayList<>();
        chosen.adapter().sendMessage(chosen.adapter().startSession(), "hi", events::add);
        assertThat(events).containsExactly(new AgentEvent.Start("m"), new AgentEvent.Delta("m", "hi"),
                new AgentEvent.End("m"));
    }

    @Test
    @Tag("ADAPTER-3")
    @Tag("ADAPTER-4")
    void theFolderAndTheJarAreLeftAsTheyAre() throws Exception {
        Path jar = adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("r--r--r--"));

        choose("echo");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(jar))).isEqualTo("r--r--r--");
    }

    @Test
    @Tag("ADAPTER-11")
    void anAdapterSeesOnlyThePlatformTheInterfaceAndItsOwnJar() throws Exception {
        adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);

        AgentAdapter adapter = choose("echo").adapter();
        ClassLoader loader = adapter.getClass().getClassLoader();

        assertThat(adapter.getClass()).isNotSameAs(TestAdapterProvider.class);
        assertThat(loader.loadClass(AgentAdapter.class.getName())).isSameAs(AgentAdapter.class);
        assertThat(loader.loadClass(AgentEvent.Delta.class.getName())).isSameAs(AgentEvent.Delta.class);
        assertThat(loader.loadClass("java.util.List")).isSameAs(List.class);
        for (String hidden : List.of("tools.jackson.databind.ObjectMapper", "org.eclipse.jetty.server.Server",
                AgentAdapters.class.getName(), FakeAgentAdapter.class.getName())) {
            assertThatThrownBy(() -> loader.loadClass(hidden)).as(hidden)
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @Tag("ADAPTER-11")
    void eachJarHasAClassLoaderOfItsOwn() throws Exception {
        adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);
        adapterJar("other.jar", "other", "1", TestAdapterProvider.class);

        ClassLoader echo = choose("echo").adapter().getClass().getClassLoader();
        ClassLoader other = choose("other").adapter().getClass().getClassLoader();

        assertThat(echo).isNotSameAs(other);
    }

    @Test
    @Tag("ADAPTER-5")
    void aNameThatIsNotThereListsEveryAdapterFound() throws Exception {
        Path jar = adapterJar("echo-adapter-1.2.jar", "echo", "1");

        assertThatThrownBy(() -> choose("ecko")).isInstanceOf(RefusedAdapterException.class)
                .hasMessage("No agent adapter is named ecko; the adapters are: echo (" + jar + "), fake (built in)");
    }

    @Test
    @Tag("ADAPTER-5")
    void withoutTheFolderOnlyTheBuiltInAdaptersAreListed() {
        assertThatThrownBy(() -> choose("echo")).isInstanceOf(RefusedAdapterException.class)
                .hasMessage("No agent adapter is named echo; the adapters are: fake (built in)");
    }

    @Test
    @Tag("ADAPTER-5")
    @Tag("LOG-3")
    void theNameIsShownOnOneLine() {
        assertThatThrownBy(() -> choose("e\ncho")).hasMessageStartingWith("No agent adapter is named e\\x0Acho;");
    }

    @Test
    @Tag("ADAPTER-6")
    void twoJarsWithTheChosenNameAreRefusedWithBothFiles() throws Exception {
        Path older = adapterJar("echo-adapter-1.2.jar", "echo", "1", TestAdapterProvider.class);
        Path newer = adapterJar("echo-adapter-1.3.jar", "echo", "1", TestAdapterProvider.class);

        assertThatThrownBy(() -> choose("echo")).isInstanceOf(RefusedAdapterException.class)
                .hasMessage("2 agent adapters are named echo: " + older + ", " + newer + "; keep one of them");
    }

    @Test
    @Tag("ADAPTER-6")
    void aJarWithTheNameOfABuiltInAdapterIsRefused() throws Exception {
        Path jar = adapterJar("fake.jar", "fake", "1", TestAdapterProvider.class);

        assertThatThrownBy(() -> choose("fake")).isInstanceOf(RefusedAdapterException.class)
                .hasMessage("2 agent adapters are named fake: built in, " + jar + "; keep one of them");
    }

    @Test
    @Tag("ADAPTER-6")
    void adaptersThatShareAnotherNameAreNoRefusal() throws Exception {
        adapterJar("a.jar", "echo", "1", TestAdapterProvider.class);
        adapterJar("b.jar", "echo", "1", TestAdapterProvider.class);

        assertThat(choose("fake").adapter()).isInstanceOf(FakeAgentAdapter.class);
    }

    @Test
    @Tag("ADAPTER-7")
    @Tag("ADAPTER-8")
    void anAdapterForAnOlderInterfaceIsRefusedBeforeItsCodeIsLoaded() throws Exception {
        // The services file names a class the jar does not hold: loading it would be another refusal.
        Path jar = jar("echo-adapter-0.9.jar", Map.of(AdapterApi.NAME_ATTRIBUTE, "echo",
                AdapterApi.VERSION_ATTRIBUTE, "0"), List.of(), "com.example.Missing\n");

        assertThatThrownBy(() -> choose("echo")).isInstanceOf(RefusedAdapterException.class)
                .hasMessage("The agent adapter echo in " + jar + " is built for version 0 of the adapter interface,"
                        + " and the service needs version 1; the adapter is older: use a build of it for version 1");
    }

    @Test
    @Tag("ADAPTER-7")
    void anAdapterForANewerInterfaceSaysTheServiceIsOlder() throws Exception {
        adapterJar("echo.jar", "echo", "2", TestAdapterProvider.class);

        assertThatThrownBy(() -> choose("echo")).isInstanceOf(RefusedAdapterException.class)
                .hasMessageContaining("built for version 2").hasMessageContaining("the service is older");
    }

    @Test
    @Tag("ADAPTER-8")
    void jarsThatAreNotAdaptersAreWarnedAboutAndSkipped() throws Exception {
        Files.createDirectories(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        Path noManifest = jar("plain.jar", null, List.of(), null);
        Path noName = jar("no-name.jar", Map.of(AdapterApi.VERSION_ATTRIBUTE, "1"), List.of(), null);
        Path badName = jar("bad-name.jar", Map.of(AdapterApi.NAME_ATTRIBUTE, "Echo",
                AdapterApi.VERSION_ATTRIBUTE, "1"), List.of(), null);
        Path noVersion = jar("no-version.jar", Map.of(AdapterApi.NAME_ATTRIBUTE, "echo"), List.of(), null);
        Path badVersion = jar("bad-version.jar", Map.of(AdapterApi.NAME_ATTRIBUTE, "echo",
                AdapterApi.VERSION_ATTRIBUTE, "1.0"), List.of(), null);
        Path broken = folder.resolve("broken.jar");
        Files.writeString(broken, "not a zip");
        Files.writeString(folder.resolve("notes.txt"), "ignored");
        Files.createDirectory(folder.resolve("dir.jar"));

        assertThat(choose("fake").adapter()).isInstanceOf(FakeAgentAdapter.class);

        assertThat(warnings).hasSize(6).allSatisfy(warning -> assertThat(warning).startsWith("Not an agent adapter: "));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains(noManifest.toString(), "no Crawdad-Adapter-Name"))
                .anySatisfy(w -> assertThat(w).contains(noName.toString(), "no Crawdad-Adapter-Name"))
                .anySatisfy(w -> assertThat(w).contains(badName.toString(), "name in its manifest is not valid: Echo"))
                .anySatisfy(w -> assertThat(w).contains(noVersion.toString(), "no Crawdad-Adapter-API-Version"))
                .anySatisfy(w -> assertThat(w).contains(badVersion.toString(), "not a whole number: 1.0"))
                .anySatisfy(w -> assertThat(w).contains(broken.toString(), "cannot be read as a jar"))
                .noneSatisfy(w -> assertThat(w).contains("notes.txt"))
                .noneSatisfy(w -> assertThat(w).contains("dir.jar"));
    }

    @Test
    @Tag("ADAPTER-8")
    void theChosenJarMustNameExactlyOneProvider() throws Exception {
        Path none = jar("none.jar", attributes("none", "1"), List.of(TestAdapterProvider.class), null);
        Path two = jar("two.jar", attributes("two", "1"), List.of(TestAdapterProvider.class,
                FailingAdapterProvider.class), TestAdapterProvider.class.getName() + "\n"
                        + FailingAdapterProvider.class.getName() + "\n");
        Path missing = jar("missing.jar", attributes("missing", "1"), List.of(), "com.example.Missing\n");

        assertThatThrownBy(() -> choose("none")).isInstanceOf(RefusedAdapterException.class)
                .hasMessage("The agent adapter " + none + " names 0 providers of " + AgentAdapterProvider.class.getName()
                        + "; it must name exactly one");
        assertThatThrownBy(() -> choose("two")).isInstanceOf(RefusedAdapterException.class)
                .hasMessageStartingWith("The agent adapter " + two + " names 2 providers");
        assertThatThrownBy(() -> choose("missing")).isInstanceOf(RefusedAdapterException.class)
                .hasMessageStartingWith("The agent adapter " + missing + " does not name a usable provider");
    }

    @Test
    @Tag("ADAPTER-8")
    @Tag("START-8")
    void aProviderThatFailsIsAFailureWithItsCause() throws Exception {
        Path jar = adapterJar("failing.jar", "failing", "1", FailingAdapterProvider.class);

        assertThatThrownBy(() -> choose("failing")).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(RefusedAdapterException.class)
                .hasMessageStartingWith("The agent adapter " + jar + " failed to start")
                .hasRootCauseMessage("no agent installed");
    }

    @Test
    @Tag("ADAPTER-4")
    void aJarOthersCanWriteToIsRefusedAndLeftAsItIs() throws Exception {
        Path jar = adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-rw-rw-"));

        assertThatThrownBy(() -> choose("echo")).isInstanceOf(RefusedAdapterException.class)
                .hasMessageStartingWith("Other OS users can write to the agent adapter " + jar);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(jar))).isEqualTo("rw-rw-rw-");
    }

    @Test
    @Tag("ADAPTER-4")
    void aFolderItsGroupCanWriteToIsRefusedEvenForABuiltInAdapter() throws Exception {
        adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxrwx---"));

        assertThatThrownBy(() -> choose("fake")).isInstanceOf(RefusedAdapterException.class)
                .hasMessageStartingWith("Other OS users can write to the folder of agent adapters " + folder);
    }

    @Test
    @Tag("ADAPTER-4")
    void onlyTheChosenJarIsChecked() throws Exception {
        adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);
        Path other = adapterJar("other.jar", "other", "1", TestAdapterProvider.class);
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-rw-rw-"));

        assertThat(choose("echo").name()).isEqualTo("echo");
    }

    @Test
    @Tag("ADAPTER-4")
    void aFolderOfAnotherOsUserIsRefused() throws Exception {
        adapterJar("echo.jar", "echo", "1", TestAdapterProvider.class);
        UserPrincipal root = folder.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("root");
        assumeFalse(root.equals(owner), "the tests run as root");

        assertThatThrownBy(() -> AgentAdapters.choose("echo", data, root, warnings::add))
                .isInstanceOf(RefusedAdapterException.class)
                .hasMessageStartingWith("The folder of agent adapters " + folder + " belongs to " + owner.getName()
                        + ", not to root");
    }

    @Test
    void aFolderThatIsAFileIsAFailure() throws Exception {
        Files.writeString(folder, "not a folder");

        assertThatThrownBy(() -> choose("fake")).isInstanceOf(NotDirectoryException.class);
    }

    private AgentAdapters.Chosen choose(String name) throws IOException {
        return AgentAdapters.choose(name, data, owner, warnings::add);
    }

    private static Map<String, String> attributes(String name, String version) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(AdapterApi.NAME_ATTRIBUTE, name);
        attributes.put(AdapterApi.VERSION_ATTRIBUTE, version);
        return attributes;
    }

    /** A jar of an adapter named {@code name}, with {@code provider} as its one provider, or none at all. */
    private Path adapterJar(String file, String name, String version, Class<?>... provider) throws IOException {
        return jar(file, attributes(name, version), List.of(provider),
                provider.length == 0 ? null : provider[0].getName() + "\n");
    }

    /**
     * Writes {@code file} into the folder of adapters, {@code 0644} in a {@code 0700} folder, with {@code attributes}
     * in its manifest (none when {@code null}), the bytes of {@code classes}, and {@code services} as its services
     * file (none when {@code null}).
     */
    private Path jar(String file, Map<String, String> attributes, List<Class<?>> classes, String services)
            throws IOException {
        if (!Files.exists(folder)) {
            Files.createDirectories(folder);
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        }
        Path jar = folder.resolve(file);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream stream = attributes == null ? new JarOutputStream(out)
                        : new JarOutputStream(out, manifest(attributes))) {
            for (Class<?> type : classes) {
                String path = type.getName().replace('.', '/') + ".class";
                stream.putNextEntry(new JarEntry(path));
                try (InputStream in = type.getClassLoader().getResourceAsStream(path)) {
                    in.transferTo(stream);
                }
                stream.closeEntry();
            }
            if (services != null) {
                stream.putNextEntry(new JarEntry(SERVICES));
                stream.write(services.getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-r--r--"));
        return jar;
    }

    private static Manifest manifest(Map<String, String> attributes) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
        return manifest;
    }
}
