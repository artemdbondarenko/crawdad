package io.github.artemdbondarenko.crawdad.service.storage;

import io.github.artemdbondarenko.crawdad.service.log.LogText;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where the service keeps its files: {@code data} under {@code $XDG_DATA_HOME/crawdad} (by default
 * {@code ~/.local/share/crawdad}) and {@code runtime}, for the control socket, under
 * {@code $XDG_RUNTIME_DIR/crawdad}. Directories are created 0700 and files written atomically, 0600
 * from the moment they exist, so other users of the OS never see their content.
 */
public record DataDirectory(Path data, Path runtime) {

    private static final Logger LOG = System.getLogger(DataDirectory.class.getName());

    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** Writes the content of a file into the stream; the stream is closed by {@code writeAtomically}. */
    @FunctionalInterface
    public interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    public DataDirectory {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(runtime, "runtime");
    }

    public static DataDirectory fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Resolves the directories by the XDG Base Directory specification: empty or relative values are
     * ignored. Without {@code XDG_RUNTIME_DIR} the socket goes to {@code run} inside the data directory.
     */
    static DataDirectory fromEnvironment(Map<String, String> environment) {
        Path dataHome = absolute(environment.get("XDG_DATA_HOME"));
        if (dataHome == null) {
            Path home = absolute(environment.get("HOME"));
            if (home == null) {
                throw new IllegalStateException(
                        "Cannot find where to keep the service data: neither XDG_DATA_HOME nor HOME"
                                + " is set to an absolute path");
            }
            dataHome = home.resolve(".local/share");
        }
        Path data = dataHome.resolve("crawdad");
        Path runtimeHome = absolute(environment.get("XDG_RUNTIME_DIR"));
        if (runtimeHome == null) {
            Path fallback = data.resolve("run");
            LOG.log(Level.WARNING, "XDG_RUNTIME_DIR is not set; the control socket goes to " + LogText.safe(fallback.toString()));
            return new DataDirectory(data, fallback);
        }
        return new DataDirectory(data, runtimeHome.resolve("crawdad"));
    }

    private static Path absolute(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path : null;
    }

    /**
     * Creates the directory and its missing parents 0700; an existing directory is closed to other users.
     */
    public static Path createPrivateDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY);
        } else {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY));
        }
        return directory;
    }

    public static void writeAtomically(Path file, byte[] content) throws IOException {
        writeAtomically(file, out -> out.write(content));
    }

    /**
     * Replaces the file so that a reader sees either the whole old content or the whole new one: a
     * temporary file next to it, created 0600, is synced, moved over the file and the directory synced.
     */
    public static void writeAtomically(Path file, ContentWriter content) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Path temporary = Files.createTempFile(directory, "." + file.getFileName() + ".", ".tmp", OWNER_FILE);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                content.writeTo(Channels.newOutputStream(channel));
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        // The rename itself survives a crash only once the directory entry is on disk.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
