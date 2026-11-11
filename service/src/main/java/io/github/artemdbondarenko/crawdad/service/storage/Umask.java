package io.github.artemdbondarenko.crawdad.service.storage;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The file mode creation mask of the process, set through {@code umask(2)}. Java can give a file or a
 * directory its permissions as it creates it, but not a Unix socket: {@code bind} creates the socket with the
 * mode the mask leaves. {@link #during} narrows the mask for that one call, so the socket is {@code 0600} from
 * the moment it exists ({@code FILE-4}).
 *
 * <p>The mask belongs to the whole process. The service binds its socket at start, before Jetty or any other
 * thread of its own creates files; a file created by another thread meanwhile would only get fewer permissions,
 * never more.
 */
public final class Umask {

    /** Leaves only read and write for the owner: {@code 0666 & ~0177} is {@code 0600}, {@code 0777 & ~0177} too. */
    public static final int OWNER_READ_WRITE = 0177;

    private static final MethodHandle UMASK = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("umask")
                    .orElseThrow(() -> new IllegalStateException("No umask in the C library")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** What runs under the narrowed mask. */
    @FunctionalInterface
    public interface Action<T> {
        T run() throws IOException;
    }

    private Umask() {}

    /** Runs {@code action} with the mask set to {@code mask} and puts the previous mask back, also on failure. */
    public static synchronized <T> T during(int mask, Action<T> action) throws IOException {
        int previous = set(mask);
        try {
            return action.run();
        } finally {
            set(previous);
        }
    }

    /** Sets the mask and returns the previous one. */
    static int set(int mask) {
        try {
            return (int) UMASK.invokeExact(mask);
        } catch (Throwable e) {
            throw new IllegalStateException("umask failed", e);
        }
    }
}
