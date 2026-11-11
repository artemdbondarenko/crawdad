package io.github.artemdbondarenko.crawdad.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UmaskTest {

    @TempDir
    Path temp;

    @Test
    @Tag("FILE-4")
    void aSocketBoundUnderTheMaskIsOwnerReadWriteWithoutAChmod() throws Exception {
        Path path = temp.resolve("s.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            Umask.during(Umask.OWNER_READ_WRITE, () -> server.bind(UnixDomainSocketAddress.of(path)));

            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path))).isEqualTo("rw-------");
        }
    }

    @Test
    void thePreviousMaskIsPutBackAfterwardsAndAfterAFailure() throws Exception {
        int before = Umask.set(022);
        try {
            Umask.during(Umask.OWNER_READ_WRITE, () -> null);
            assertThat(current()).isEqualTo(022);

            assertThatThrownBy(() -> Umask.during(Umask.OWNER_READ_WRITE, () -> {
                throw new IOException("bind failed");
            })).hasMessage("bind failed");
            assertThat(current()).isEqualTo(022);

            // Outside the call, a file gets the permissions of the old mask.
            Path file = Files.createFile(temp.resolve("plain"));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r--r--");
        } finally {
            Umask.set(before);
        }
    }

    /** Reading the mask means setting it; it is set straight back. */
    private static int current() {
        int mask = Umask.set(0);
        Umask.set(mask);
        return mask;
    }
}
