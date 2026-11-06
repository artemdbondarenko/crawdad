package io.github.artemdbondarenko.crawdad.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.NewDevice;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.RevokeResult;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceRegistryTest {

    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock(START);
    private DataDirectory directory;
    private DeviceRegistry registry;

    @BeforeEach
    void openRegistry() throws IOException {
        directory = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        registry = DeviceRegistry.open(directory, clock);
    }

    @Test
    void repeatedNamesGetSuffixesAndARevokedNameIsFreeAgain() throws IOException {
        assertThat(registry.add("tablet", Listener.LOOPBACK).device().name()).isEqualTo("tablet");
        assertThat(registry.add("tablet", Listener.NETWORK).device().name()).isEqualTo("tablet-2");
        assertThat(registry.add("tablet", Listener.LOOPBACK).device().name()).isEqualTo("tablet-3");

        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone");
        registry.revoke("tablet-2");

        assertThat(registry.add("phone", Listener.NETWORK).device().name()).isEqualTo("phone");
        assertThat(registry.add("tablet", Listener.NETWORK).device().name()).isEqualTo("tablet-2");
        assertThat(registry.add("tablet", Listener.NETWORK).device().name()).isEqualTo("tablet-4");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> registry.add(" ", Listener.LOOPBACK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.add(null, Listener.LOOPBACK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewDeviceHasAFreshRandomKeyAndNoLoginYet() throws IOException {
        NewDevice first = registry.add("laptop", Listener.LOOPBACK);
        NewDevice second = registry.add("laptop", Listener.LOOPBACK);

        assertThat(Base64.getUrlDecoder().decode(first.key())).hasSize(32);
        assertThat(first.key()).doesNotContain("=").isNotEqualTo(second.key());
        assertThat(first.device().id()).isNotEqualTo(second.device().id());
        assertThat(first.device().pairedAt()).isEqualTo(START);
        assertThat(first.device().listener()).isEqualTo(Listener.LOOPBACK);
        assertThat(first.device().keySha256()).isEqualTo(sha256(first.key()));
        assertThat(first.device().lastSeen()).isNull();
        assertThat(first.device().lastAddress()).isNull();
        assertThat(first.device().lastUserAgent()).isNull();
        assertThat(first.device().revokedAt()).isNull();
        assertThat(first.device().active()).isTrue();
    }

    @Test
    void aKeyWorksOnlyOnTheListenerWhereItWasPaired() throws IOException {
        NewDevice local = registry.add("laptop", Listener.LOOPBACK);
        NewDevice remote = registry.add("phone", Listener.NETWORK);

        assertThat(registry.authenticate(local.key(), Listener.LOOPBACK))
                .hasValueSatisfying(device -> assertThat(device.name()).isEqualTo("laptop"));
        assertThat(registry.authenticate(local.key(), Listener.NETWORK)).isEmpty();
        assertThat(registry.authenticate(remote.key(), Listener.NETWORK))
                .hasValueSatisfying(device -> assertThat(device.name()).isEqualTo("phone"));
        assertThat(registry.authenticate(remote.key(), Listener.LOOPBACK)).isEmpty();
    }

    @Test
    void anUnknownOrMissingKeyDoesNotPass() throws IOException {
        registry.add("laptop", Listener.LOOPBACK);

        assertThat(registry.authenticate("not-a-key", Listener.LOOPBACK)).isEmpty();
        assertThat(registry.authenticate("", Listener.LOOPBACK)).isEmpty();
        assertThat(registry.authenticate(null, Listener.LOOPBACK)).isEmpty();
    }

    @Test
    void aRevokedKeyDoesNotPassAndItsHashIsErased() throws IOException {
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        clock.advance(Duration.ofHours(1));

        RevokeResult result = registry.revoke("phone");

        assertThat(result).isInstanceOfSatisfying(RevokeResult.Revoked.class, revoked -> {
            assertThat(revoked.device().name()).isEqualTo("phone");
            assertThat(revoked.device().revokedAt()).isEqualTo(START.plus(Duration.ofHours(1)));
            assertThat(revoked.device().keySha256()).isNull();
            assertThat(revoked.device().active()).isFalse();
        });
        assertThat(registry.authenticate(phone.key(), Listener.NETWORK)).isEmpty();
    }

    @Test
    void recordingALoginUpdatesOnlyTheLoginFields() throws IOException {
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        clock.advance(Duration.ofMinutes(5));

        Device seen = registry.recordLogin(phone.device().id(), "100.64.0.7", "Firefox/140");

        assertThat(seen.lastSeen()).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(seen.lastAddress()).isEqualTo("100.64.0.7");
        assertThat(seen.lastUserAgent()).isEqualTo("Firefox/140");
        assertThat(seen.pairedAt()).isEqualTo(START);
        assertThat(seen.keySha256()).isEqualTo(phone.device().keySha256());
        assertThat(registry.devices(false)).containsExactly(seen);
    }

    @Test
    void recordingALoginDoesNotBringARevokedDeviceBack() throws IOException {
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        registry.revoke("phone");
        clock.advance(Duration.ofMinutes(1));

        Device seen = registry.recordLogin(phone.device().id(), "100.64.0.7", "Firefox/140");

        assertThat(seen.revokedAt()).isEqualTo(START);
        assertThat(seen.keySha256()).isNull();
        assertThat(registry.authenticate(phone.key(), Listener.NETWORK)).isEmpty();
        assertThat(registry.devices(false)).isEmpty();
    }

    @Test
    void recordingALoginOfAnUnknownDeviceFails() {
        assertThatThrownBy(() -> registry.recordLogin("no-such-id", "127.0.0.1", "curl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theListHidesRevokedDevicesUnlessAllAreAsked() throws IOException {
        registry.add("laptop", Listener.LOOPBACK);
        clock.advance(Duration.ofMinutes(1));
        registry.add("phone", Listener.NETWORK);
        clock.advance(Duration.ofMinutes(1));
        registry.revoke("phone");

        assertThat(registry.devices(false)).extracting(Device::name).containsExactly("laptop");
        assertThat(registry.devices(true)).extracting(Device::name, Device::revokedAt).containsExactly(
                tuple("laptop", null),
                tuple("phone", START.plus(Duration.ofMinutes(2))));
    }

    @Test
    void revokingAnUnknownNameListsTheActiveDevices() throws IOException {
        registry.add("laptop", Listener.LOOPBACK);
        registry.add("phone", Listener.NETWORK);
        registry.add("tablet", Listener.NETWORK);
        registry.revoke("tablet");

        RevokeResult result = registry.revoke("watch");

        assertThat(result).isEqualTo(new RevokeResult.NotFound(List.of("laptop", "phone")));
    }

    @Test
    void revokingARevokedNameGivesTheDateOfRevocation() throws IOException {
        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone");
        clock.advance(Duration.ofDays(1));
        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone");
        clock.advance(Duration.ofDays(1));

        RevokeResult result = registry.revoke("phone");

        assertThat(result).isEqualTo(new RevokeResult.AlreadyRevoked(START.plus(Duration.ofDays(1))));
    }

    @Test
    void revokingANameThatIsActiveAgainRevokesTheActiveDevice() throws IOException {
        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone");
        clock.advance(Duration.ofDays(1));
        NewDevice again = registry.add("phone", Listener.NETWORK);

        RevokeResult result = registry.revoke("phone");

        assertThat(result).isInstanceOfSatisfying(RevokeResult.Revoked.class,
                revoked -> assertThat(revoked.device().id()).isEqualTo(again.device().id()));
        assertThat(registry.devices(false)).isEmpty();
    }

    @Test
    void everythingIsInPlaceAfterReloadingFromTheFile() throws IOException {
        NewDevice laptop = registry.add("laptop", Listener.LOOPBACK);
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        NewDevice tablet = registry.add("tablet", Listener.NETWORK);
        clock.advance(Duration.ofMinutes(3));
        registry.recordLogin(laptop.device().id(), "127.0.0.1", "Chrome/150");
        registry.revoke("tablet");

        DeviceRegistry reloaded = DeviceRegistry.open(directory, clock);

        assertThat(reloaded.devices(true)).isEqualTo(registry.devices(true));
        assertThat(reloaded.authenticate(laptop.key(), Listener.LOOPBACK)).isPresent();
        assertThat(reloaded.authenticate(phone.key(), Listener.NETWORK)).isPresent();
        assertThat(reloaded.authenticate(tablet.key(), Listener.NETWORK)).isEmpty();
    }

    @Test
    void aMissingFileIsAnEmptyRegistryAndNothingIsWrittenUntilAChange() throws IOException {
        Path file = directory.data().resolve("devices.json");

        assertThat(registry.devices(true)).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void theFileKeepsOnlyTheHashOfTheKeyAndIsPrivate() throws IOException {
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        Path file = directory.data().resolve("devices.json");

        String content = Files.readString(file, StandardCharsets.UTF_8);

        assertThat(content).doesNotContain(phone.key())
                .contains("\"key_sha256\":\"" + sha256(phone.key()) + "\"")
                .contains("\"version\":1")
                .contains("\"listener\":\"network\"")
                .contains("\"last_seen\":null");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.data())))
                .isEqualTo("rwx------");
    }

    @Test
    void theKeyDoesNotLeakThroughToString() throws IOException {
        NewDevice phone = registry.add("phone", Listener.NETWORK);

        assertThat(phone.toString()).doesNotContain(phone.key()).contains("phone");
    }

    @Test
    void refusesAFileOfAnUnknownVersion() throws IOException {
        Path file = directory.data().resolve("devices.json");
        Files.writeString(file, "{\"version\":2,\"devices\":[]}");

        assertThatThrownBy(() -> DeviceRegistry.open(directory, clock))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void refusesAFileThatIsNotJson() throws IOException {
        Path file = directory.data().resolve("devices.json");
        Files.writeString(file, "not json");

        assertThatThrownBy(() -> DeviceRegistry.open(directory, clock))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(file.toString());
    }

    private static String sha256(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
