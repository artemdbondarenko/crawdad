package io.github.artemdbondarenko.crawdad.service.device;

import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The paired devices, kept in {@code devices.json} in the data directory. The file holds only the
 * SHA-256 of each key; the key itself leaves the registry once, in {@link NewDevice}. Names are unique
 * among active devices. Every change is written atomically before it becomes visible; all methods
 * hold the registry's monitor, so a caller can extend that lock to its own state.
 */
public final class DeviceRegistry {

    public static final String FILE_NAME = "devices.json";

    private static final int VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    /** A device just added and its key, to be handed to the device and then forgotten. */
    public record NewDevice(Device device, String key) {

        /** Keeps the key out of logs and error messages. */
        @Override
        public String toString() {
            return "NewDevice[device=" + device + ", key=<hidden>]";
        }
    }

    /** What {@link #revoke} did. */
    public sealed interface RevokeResult {

        record Revoked(Device device) implements RevokeResult {}

        /** No active device has the name; {@code active} lists the names that do exist. */
        record NotFound(List<String> active) implements RevokeResult {}

        record AlreadyRevoked(Instant revokedAt) implements RevokeResult {}
    }

    private record FileContent(int version, List<Device> devices) {}

    private final Path file;
    private final Clock clock;
    private List<Device> devices;

    private DeviceRegistry(Path file, Clock clock, List<Device> devices) {
        this.file = file;
        this.clock = clock;
        this.devices = devices;
    }

    /** Reads the registry from the data directory, creating the directory 0700 if needed. */
    public static DeviceRegistry open(DataDirectory directory, Clock clock) throws IOException {
        Objects.requireNonNull(clock, "clock");
        Path file = DataDirectory.createPrivateDirectory(directory.data()).resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new DeviceRegistry(file, clock, List.of());
        }
        FileContent content;
        try {
            content = JSON.readValue(Files.readAllBytes(file), FileContent.class);
        } catch (JacksonException e) {
            throw new IOException("Cannot read " + file + ": not a device registry", e);
        }
        if (content.version() != VERSION || content.devices() == null) {
            throw new IOException("Cannot read " + file + ": unsupported version " + content.version());
        }
        return new DeviceRegistry(file, clock, List.copyOf(content.devices()));
    }

    /**
     * Adds a device paired through {@code listener} under {@code name}, or under {@code name-2},
     * {@code name-3}… when an active device already has it.
     */
    public synchronized NewDevice add(String name, Listener listener) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A device needs a name");
        }
        Objects.requireNonNull(listener, "listener");
        byte[] secret = new byte[KEY_BYTES];
        RANDOM.nextBytes(secret);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Device device = new Device(UUID.randomUUID().toString(), freeName(name), HEX.formatHex(sha256(key)),
                listener, clock.instant(), null, null, null, null);
        List<Device> changed = new ArrayList<>(devices);
        changed.add(device);
        save(changed);
        return new NewDevice(device, key);
    }

    private String freeName(String name) {
        String candidate = name;
        for (int suffix = 2; activeByName(candidate).isPresent(); suffix++) {
            candidate = name + "-" + suffix;
        }
        return candidate;
    }

    /** The active device whose key this is, if it was paired through the same listener. */
    public synchronized Optional<Device> authenticate(String key, Listener listener) {
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        byte[] presented = sha256(key);
        Device found = null;
        // Every stored hash is compared in constant time, so timing does not tell which one is close.
        for (Device device : devices) {
            if (device.keySha256() != null && MessageDigest.isEqual(presented, HEX.parseHex(device.keySha256()))) {
                found = device;
            }
        }
        return Optional.ofNullable(found).filter(device -> device.active() && device.listener() == listener);
    }

    /** Stores the time, address and {@code User-Agent} of a successful handshake; nothing else changes. */
    public synchronized Device recordLogin(String id, String address, String userAgent) throws IOException {
        List<Device> changed = new ArrayList<>(devices);
        for (int i = 0; i < changed.size(); i++) {
            if (changed.get(i).id().equals(id)) {
                Device seen = changed.get(i).withLogin(clock.instant(), address, userAgent);
                changed.set(i, seen);
                save(changed);
                return seen;
            }
        }
        throw new IllegalArgumentException("No device with id " + id);
    }

    /** Revokes the active device with the name: its key hash is erased and the date recorded. */
    public synchronized RevokeResult revoke(String name) throws IOException {
        Optional<Device> active = activeByName(name);
        if (active.isPresent()) {
            Device revoked = active.get().withRevocation(clock.instant());
            List<Device> changed = new ArrayList<>(devices);
            changed.set(changed.indexOf(active.get()), revoked);
            save(changed);
            return new RevokeResult.Revoked(revoked);
        }
        Optional<Instant> lastRevoked = devices.stream()
                .filter(device -> device.name().equals(name))
                .map(Device::revokedAt)
                .max(Instant::compareTo);
        if (lastRevoked.isPresent()) {
            return new RevokeResult.AlreadyRevoked(lastRevoked.get());
        }
        return new RevokeResult.NotFound(devices(false).stream().map(Device::name).toList());
    }

    /** Devices in the order they were paired; revoked ones only when {@code all} is set. */
    public synchronized List<Device> devices(boolean all) {
        return devices.stream().filter(device -> all || device.active()).toList();
    }

    private Optional<Device> activeByName(String name) {
        return devices.stream().filter(device -> device.active() && device.name().equals(name)).findFirst();
    }

    private void save(List<Device> changed) throws IOException {
        byte[] content = JSON.writeValueAsBytes(new FileContent(VERSION, changed));
        DataDirectory.writeAtomically(file, content);
        devices = List.copyOf(changed);
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
