package io.github.artemdbondarenko.crawdad.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry.Connection;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.NewDevice;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.RevokeResult;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceRegistryTest {

    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock(START);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler collect = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    // Held in a field: java.util.logging keeps loggers only weakly.
    private final Logger log = Logger.getLogger(DeviceRegistry.class.getName());
    private DataDirectory directory;
    private DeviceRegistry registry;

    @BeforeEach
    void openRegistry() throws IOException {
        log.addHandler(collect);
        directory = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        registry = DeviceRegistry.open(directory, clock);
    }

    @AfterEach
    void stopCollecting() {
        log.removeHandler(collect);
    }

    @Test
    @Tag("KEY-8")
    void repeatedNamesGetSuffixesAndARevokedNameIsFreeAgain() throws IOException {
        assertThat(registry.add("tablet", Listener.LOOPBACK).device().name()).isEqualTo("tablet");
        assertThat(registry.add("tablet", Listener.NETWORK).device().name()).isEqualTo("tablet-2");
        assertThat(registry.add("tablet", Listener.LOOPBACK).device().name()).isEqualTo("tablet-3");

        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone", connections);
        registry.revoke("tablet-2", connections);

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
    @Tag("KEY-1")
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
    @Tag("KEY-4")
    @Tag("KEY-5")
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
    @Tag("KEY-6")
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

        RevokeResult result = registry.revoke("phone", connections);

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
        registry.revoke("phone", connections);
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
        registry.revoke("phone", connections);

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
        registry.revoke("tablet", connections);

        RevokeResult result = registry.revoke("watch", connections);

        assertThat(result).isEqualTo(new RevokeResult.NotFound(List.of("laptop", "phone")));
    }

    @Test
    void revokingARevokedNameGivesTheDateOfRevocation() throws IOException {
        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone", connections);
        clock.advance(Duration.ofDays(1));
        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone", connections);
        clock.advance(Duration.ofDays(1));

        RevokeResult result = registry.revoke("phone", connections);

        assertThat(result).isEqualTo(new RevokeResult.AlreadyRevoked(START.plus(Duration.ofDays(1))));
    }

    @Test
    void revokingANameThatIsActiveAgainRevokesTheActiveDevice() throws IOException {
        registry.add("phone", Listener.NETWORK);
        registry.revoke("phone", connections);
        clock.advance(Duration.ofDays(1));
        NewDevice again = registry.add("phone", Listener.NETWORK);

        RevokeResult result = registry.revoke("phone", connections);

        assertThat(result).isInstanceOfSatisfying(RevokeResult.Revoked.class,
                revoked -> assertThat(revoked.device().id()).isEqualTo(again.device().id()));
        assertThat(registry.devices(false)).isEmpty();
    }

    @Test
    void revokingClosesEveryConnectionOfTheDeviceOnceTheRevocationIsSaved() throws IOException {
        NewDevice laptop = registry.add("laptop", Listener.LOOPBACK);
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        List<String> closes = new ArrayList<>();
        connections.register(laptop.device().id(), recording("laptop", "laptop tab 1", closes));
        connections.register(phone.device().id(), recording("phone", "phone", closes));
        connections.register(laptop.device().id(), recording("laptop", "laptop tab 2", closes));

        RevokeResult result = registry.revoke("laptop", connections);

        assertThat(result).isInstanceOfSatisfying(RevokeResult.Revoked.class,
                revoked -> assertThat(revoked.device().id()).isEqualTo(laptop.device().id()));
        // Each close saw the revocation already on disk; the phone's connection was not touched.
        assertThat(closes).containsExactly("laptop tab 1 4001 unpaired, saved=true",
                "laptop tab 2 4001 unpaired, saved=true");
        assertThat(messages()).containsExactly("Device revoked: laptop, closed 2 connections");
    }

    @Test
    void theRevocationLogLineEscapesTheName() throws IOException {
        registry.add("ph\u001bone", Listener.NETWORK);

        registry.revoke("ph\u001bone", connections);

        assertThat(messages()).containsExactly("Device revoked: ph\\x1Bone, closed 0 connections");
    }

    @Test
    void aRevocationThatCannotBeSavedClosesNothingAndKeepsTheDevice() throws IOException {
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        List<String> closes = new ArrayList<>();
        connections.register(phone.device().id(), recording("phone", "phone", closes));
        // The registry writes next to devices.json; without its directory every write fails.
        Files.move(directory.data(), temp.resolve("moved"));

        assertThatThrownBy(() -> registry.revoke("phone", connections)).isInstanceOf(IOException.class);

        assertThat(closes).isEmpty();
        assertThat(registry.devices(false)).containsExactly(phone.device());
        assertThat(registry.authenticate(phone.key(), Listener.NETWORK)).isPresent();
        assertThat(messages()).isEmpty();
    }

    @Test
    void anUnknownOrAlreadyRevokedNameClosesNothing() throws IOException {
        NewDevice laptop = registry.add("laptop", Listener.LOOPBACK);
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        registry.revoke("phone", connections);
        records.clear();
        List<String> closes = new ArrayList<>();
        connections.register(laptop.device().id(), recording("laptop", "laptop", closes));
        // Revoked devices have no connections; one registered anyway shows that nothing looks for it.
        connections.register(phone.device().id(), recording("phone", "phone", closes));

        assertThat(registry.revoke("watch", connections)).isInstanceOf(RevokeResult.NotFound.class);
        assertThat(registry.revoke("phone", connections)).isInstanceOf(RevokeResult.AlreadyRevoked.class);

        assertThat(closes).isEmpty();
        assertThat(messages()).isEmpty();
    }

    @Test
    void everythingIsInPlaceAfterReloadingFromTheFile() throws IOException {
        NewDevice laptop = registry.add("laptop", Listener.LOOPBACK);
        NewDevice phone = registry.add("phone", Listener.NETWORK);
        NewDevice tablet = registry.add("tablet", Listener.NETWORK);
        clock.advance(Duration.ofMinutes(3));
        registry.recordLogin(laptop.device().id(), "127.0.0.1", "Chrome/150");
        registry.revoke("tablet", connections);

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
    @Tag("KEY-3")
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
    @Tag("PAIR-10")
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

    /**
     * A connection of the device {@code name} that records how it was closed and whether the file
     * already showed the device revoked by then.
     */
    private Connection recording(String name, String label, List<String> closes) {
        return (statusCode, reason) -> {
            boolean saved;
            try {
                saved = DeviceRegistry.open(directory, clock).devices(false).stream()
                        .noneMatch(device -> device.name().equals(name));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            closes.add(label + " " + statusCode + " " + reason + ", saved=" + saved);
        };
    }

    /** The INFO lines the registry wrote, formatted as a log shows them. */
    private List<String> messages() {
        SimpleFormatter formatter = new SimpleFormatter();
        return records.stream().filter(record -> record.getLevel() == java.util.logging.Level.INFO)
                .map(formatter::formatMessage).toList();
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
