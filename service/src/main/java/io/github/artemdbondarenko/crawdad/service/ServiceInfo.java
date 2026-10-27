package io.github.artemdbondarenko.crawdad.service;

/**
 * Identity of the Crawdad service: a stable machine name and a name shown to people.
 */
public record ServiceInfo(String name, String displayName) {

    public static ServiceInfo current() {
        return new ServiceInfo("crawdad-service", "Crawdad service");
    }
}
