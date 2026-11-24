package io.github.artemdbondarenko.crawdad.adapter;

import java.util.regex.Pattern;

/**
 * The version of this interface and the attributes an adapter jar names itself with in its manifest
 * ({@code specs/agent-adapters.md}, "Adapter jar").
 */
public final class AdapterApi {

    /**
     * The version of the adapter interface this module describes. Any change an adapter built for the previous
     * version cannot run with raises it by one; the service takes only an adapter built for the same version
     * ({@code ADAPTER-7}).
     */
    public static final int VERSION = 1;

    /** The manifest attribute with the name of the adapter, the value of {@code --agent}. */
    public static final String NAME_ATTRIBUTE = "Crawdad-Adapter-Name";

    /** The manifest attribute with the version of this interface the adapter is built for. */
    public static final String VERSION_ATTRIBUTE = "Crawdad-Adapter-API-Version";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private static final Pattern WHOLE_NUMBER = Pattern.compile("0|[1-9][0-9]{0,8}");

    private AdapterApi() {}

    /** Whether {@code name} is a valid adapter name: 1 to 32 lower-case ASCII letters, digits and {@code -}, a letter first. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Whether {@code version} is a whole number in decimal without leading zeros. */
    public static boolean isValidVersion(String version) {
        return version != null && WHOLE_NUMBER.matcher(version).matches();
    }
}
