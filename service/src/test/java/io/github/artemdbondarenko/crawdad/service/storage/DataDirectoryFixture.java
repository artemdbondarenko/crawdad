package io.github.artemdbondarenko.crawdad.service.storage;

import java.util.Map;

/** Data directories that other packages' tests cannot get from the real environment. */
public final class DataDirectoryFixture {

    private DataDirectoryFixture() {}

    /** Looks up the directories in an environment without {@code XDG_DATA_HOME} and {@code HOME}; it refuses. */
    public static DataDirectory withoutHome() {
        return DataDirectory.fromEnvironment(Map.of());
    }
}
