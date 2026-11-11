package io.github.artemdbondarenko.crawdad.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirectoryTest {

    @TempDir
    Path temp;

    @Test
    @Tag("FILE-1")
    @Tag("FILE-2")
    void usesTheXdgDirectoriesWhenTheyAreSet() {
        DataDirectory directory = DataDirectory.fromEnvironment(Map.of(
                "HOME", temp.resolve("home").toString(),
                "XDG_DATA_HOME", temp.resolve("data").toString(),
                "XDG_RUNTIME_DIR", temp.resolve("run").toString()));

        assertThat(directory.data()).isEqualTo(temp.resolve("data/crawdad"));
        assertThat(directory.runtime()).isEqualTo(temp.resolve("run/crawdad"));
    }

    @Test
    @Tag("FILE-1")
    void fallsBackToLocalShareWithoutXdgDataHome() {
        DataDirectory directory = DataDirectory.fromEnvironment(Map.of(
                "HOME", temp.resolve("home").toString(),
                "XDG_RUNTIME_DIR", temp.resolve("run").toString()));

        assertThat(directory.data()).isEqualTo(temp.resolve("home/.local/share/crawdad"));
    }

    @Test
    @Tag("FILE-1")
    @Tag("FILE-2")
    void ignoresEmptyAndRelativeXdgValuesAsTheSpecificationRequires() {
        DataDirectory empty = DataDirectory.fromEnvironment(Map.of(
                "HOME", temp.resolve("home").toString(),
                "XDG_DATA_HOME", "",
                "XDG_RUNTIME_DIR", ""));
        DataDirectory relative = DataDirectory.fromEnvironment(Map.of(
                "HOME", temp.resolve("home").toString(),
                "XDG_DATA_HOME", "relative/data",
                "XDG_RUNTIME_DIR", "relative/run"));

        assertThat(empty.data()).isEqualTo(temp.resolve("home/.local/share/crawdad"));
        assertThat(empty.runtime()).isEqualTo(temp.resolve("home/.local/share/crawdad/run"));
        assertThat(relative.data()).isEqualTo(temp.resolve("home/.local/share/crawdad"));
        assertThat(relative.runtime()).isEqualTo(temp.resolve("home/.local/share/crawdad/run"));
    }

    @Test
    @Tag("FILE-2")
    void fallsBackToADirectoryInsideTheDataDirectoryWithoutXdgRuntimeDir() {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler collect = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger log = Logger.getLogger(DataDirectory.class.getName());
        log.addHandler(collect);
        DataDirectory directory;
        try {
            directory = DataDirectory.fromEnvironment(Map.of("XDG_DATA_HOME", temp.resolve("data").toString()));
        } finally {
            log.removeHandler(collect);
        }

        assertThat(directory.runtime()).isEqualTo(temp.resolve("data/crawdad/run"));
        // The XDG specification asks for a warning when the runtime directory is replaced.
        assertThat(logged).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("XDG_RUNTIME_DIR").contains(directory.runtime().toString());
        });
    }

    @Test
    @Tag("FILE-1")
    void refusesWithoutAHomeToFallBackTo() {
        assertThatThrownBy(() -> DataDirectory.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOME")
                .hasMessageContaining("XDG_DATA_HOME");
        assertThatThrownBy(() -> DataDirectory.fromEnvironment(Map.of("HOME", "relative/home")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsTheProcessEnvironmentByDefault() {
        DataDirectory directory = DataDirectory.fromEnvironment();

        assertThat(directory.data()).isAbsolute();
        assertThat(directory.data().getFileName()).hasToString("crawdad");
    }

    @Test
    @Tag("FILE-4")
    void createsMissingDirectoriesForTheOwnerOnly() throws IOException {
        Path directory = temp.resolve("data/crawdad");

        Path created = DataDirectory.createPrivateDirectory(directory);

        assertThat(created).isEqualTo(directory);
        assertThat(permissions(directory)).isEqualTo("rwx------");
    }

    @Test
    void closesAnExistingDirectoryToOtherUsers() throws IOException {
        Path directory = Files.createDirectory(temp.resolve("crawdad"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));

        DataDirectory.createPrivateDirectory(directory);

        assertThat(permissions(directory)).isEqualTo("rwx------");
    }

    @Test
    @Tag("FILE-4")
    void writesAFileThatIsOwnerOnlyFromTheMomentItIsCreated() throws IOException {
        Path file = temp.resolve("devices.json");
        // While the content is being written, the only file in the directory is the temporary one.
        List<String> seenWhileWriting = new ArrayList<>();

        DataDirectory.writeAtomically(file, out -> {
            seenWhileWriting.addAll(listWithPermissions(temp));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        });

        assertThat(seenWhileWriting).singleElement().asString()
                .doesNotStartWith("devices.json ")
                .endsWith(" rw-------");
        assertThat(Files.readString(file)).isEqualTo("{}");
        assertThat(permissions(file)).isEqualTo("rw-------");
    }

    @Test
    @Tag("FILE-5")
    void replacesAFileAtomicallyAndLeavesNoTemporaryFile() throws IOException {
        Path file = temp.resolve("devices.json");
        DataDirectory.writeAtomically(file, "old".getBytes(StandardCharsets.UTF_8));
        // Until the move, a reader still sees the whole old content.
        List<String> oldContentWhileWriting = new ArrayList<>();

        DataDirectory.writeAtomically(file, out -> {
            out.write("new".getBytes(StandardCharsets.UTF_8));
            oldContentWhileWriting.add(Files.readString(file));
        });

        assertThat(oldContentWhileWriting).containsExactly("old");
        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(listWithPermissions(temp)).containsExactly("devices.json rw-------");
    }

    @Test
    @Tag("FILE-5")
    void keepsTheOldFileAndRemovesTheTemporaryOneWhenWritingFails() throws IOException {
        Path file = temp.resolve("devices.json");
        DataDirectory.writeAtomically(file, "old".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> DataDirectory.writeAtomically(file, out -> {
            out.write("half".getBytes(StandardCharsets.UTF_8));
            throw new IOException("disk full");
        })).isInstanceOf(IOException.class).hasMessage("disk full");

        assertThat(Files.readString(file)).isEqualTo("old");
        assertThat(listWithPermissions(temp)).containsExactly("devices.json rw-------");
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    /** Each entry of the directory as {@code "<name> <permissions>"}. */
    private static List<String> listWithPermissions(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> {
                try {
                    return path.getFileName() + " " + permissions(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sorted().toList();
        }
    }
}
