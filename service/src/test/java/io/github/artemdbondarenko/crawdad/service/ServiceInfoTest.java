package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceInfoTest {

    @Test
    void describesTheService() {
        ServiceInfo info = ServiceInfo.current();

        assertThat(info.name()).isEqualTo("crawdad-service");
        assertThat(info.displayName()).isEqualTo("Crawdad service");
    }
}
