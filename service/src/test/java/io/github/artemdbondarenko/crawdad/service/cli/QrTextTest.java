package io.github.artemdbondarenko.crawdad.service.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QrTextTest {

    @Test
    void theTextDecodesBackToTheEncodedAddress() throws Exception {
        String address = "https://100.64.0.1:8460/#pair=123456";

        assertThat(QrDecoder.decode(QrText.render(address))).isEqualTo(address);
    }

    @Test
    void everyLineHasTheSameWidthAndOnlyHalfBlocks() {
        String[] lines = QrText.render("https://laptop.tail1234.ts.net:8460/#pair=000000").split("\n");

        assertThat(lines).hasSizeGreaterThan(10);
        for (String line : lines) {
            assertThat(line).hasSameSizeAs(lines[0]).matches("[ \\u2580\\u2584\\u2588]+");
        }
        // The quiet zone is light, so the first and the last lines are full blocks.
        assertThat(lines[0]).matches("\\u2588+");
        assertThat(lines[lines.length - 1]).matches("[\\u2580\\u2588]+");
    }

    @Test
    void aTextTooLongForAQrCodeIsRefused() {
        assertThatThrownBy(() -> QrText.render("x".repeat(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Cannot encode a QR code");
    }
}
