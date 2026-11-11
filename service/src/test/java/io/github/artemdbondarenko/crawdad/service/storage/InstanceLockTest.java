package io.github.artemdbondarenko.crawdad.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.service.storage.InstanceLock.AlreadyRunningException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {

    @TempDir
    Path temp;

    @Test
    @Tag("FILE-4")
    void createsTheRuntimeDirectory0700AndTheLockFile0600() throws Exception {
        Path runtime = temp.resolve("run/crawdad");

        try (InstanceLock lock = InstanceLock.acquire(runtime)) {
            assertThat(lock.held()).isTrue();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(runtime))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                    runtime.resolve(InstanceLock.FILE_NAME)))).isEqualTo("rw-------");
        }
    }

    @Test
    @Tag("CMD-7")
    void aSecondLockInTheSameProcessIsRefusedUntilTheFirstIsReleased() throws Exception {
        InstanceLock first = InstanceLock.acquire(temp);

        assertThatThrownBy(() -> InstanceLock.acquire(temp))
                .isInstanceOf(AlreadyRunningException.class)
                .hasMessage("Crawdad is already running");

        first.close();
        assertThat(first.held()).isFalse();
        // Closing twice is harmless; the lock file stays for the next instance.
        first.close();
        assertThat(temp.resolve(InstanceLock.FILE_NAME)).exists();
        try (InstanceLock second = InstanceLock.acquire(temp)) {
            assertThat(second.held()).isTrue();
        }
    }

    @Test
    @Tag("CMD-7")
    @Tag("CMD-8")
    void aLockHeldByAnotherProcessIsRefusedAndFreedWhenItExits() throws Exception {
        // Another JVM takes the lock and holds it until its standard input closes.
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                Holder.class.getName(), temp.toString())
                .redirectErrorStream(true)
                .start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                    StandardCharsets.UTF_8));
            assertThat(CompletableFuture.supplyAsync(() -> readLine(out)).get(30, TimeUnit.SECONDS))
                    .isEqualTo("locked");

            assertThatThrownBy(() -> InstanceLock.acquire(temp))
                    .isInstanceOf(AlreadyRunningException.class)
                    .hasMessage("Crawdad is already running");
        } finally {
            holder.getOutputStream().close();
            assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }

        // The OS released the lock with the process.
        try (InstanceLock lock = InstanceLock.acquire(temp)) {
            assertThat(lock.held()).isTrue();
        }
    }

    private static String readLine(BufferedReader in) {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Run in another JVM: takes the lock in the directory given, says so, and holds it until stdin closes. */
    static final class Holder {

        public static void main(String[] args) throws Exception {
            try (InstanceLock lock = InstanceLock.acquire(Path.of(args[0]))) {
                System.out.println("locked");
                System.out.flush();
                System.in.read();
            }
        }
    }
}
