package io.github.artemdbondarenko.crawdad.service.storage;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The lock that keeps one service per OS user: an OS file lock on {@code crawdad.lock} in the runtime
 * directory. The OS releases it when the process exits, also when it crashes, so a stale lock file
 * never blocks a start. While it is held, files of a previous instance, such as its socket, are leftovers.
 */
public final class InstanceLock implements AutoCloseable {

    public static final String FILE_NAME = "crawdad.lock";

    private static final Logger LOG = System.getLogger(InstanceLock.class.getName());

    /** Another service of this user, in this or another process, holds the lock. */
    public static final class AlreadyRunningException extends IOException {

        private static final long serialVersionUID = 1L;

        AlreadyRunningException() {
            super("Crawdad is already running");
        }
    }

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock in {@code runtimeDirectory}, creating the directory 0700 and the file 0600 if needed.
     * Does not wait: a lock held elsewhere throws {@link AlreadyRunningException}.
     */
    public static InstanceLock acquire(Path runtimeDirectory) throws IOException {
        Path file = DataDirectory.createPrivateDirectory(runtimeDirectory).resolve(FILE_NAME);
        FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // This JVM holds it already: OS locks belong to the process, so tryLock cannot tell otherwise.
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new AlreadyRunningException();
        }
        return new InstanceLock(channel, lock);
    }

    /** Releases the lock; the file stays, since deleting it would race with the next instance taking it. */
    @Override
    public void close() {
        // Closing the channel releases the lock; closing twice does nothing.
        try {
            channel.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not release the instance lock; it goes when the process exits", e);
        }
    }

    /** Whether the lock is still held; for tests. */
    boolean held() {
        return lock.isValid();
    }
}
