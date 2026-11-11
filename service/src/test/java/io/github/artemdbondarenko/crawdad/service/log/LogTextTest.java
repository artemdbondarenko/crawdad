package io.github.artemdbondarenko.crawdad.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The non-ASCII literals here are the characters under test, written as escapes. */
class LogTextTest {

    @Test
    void lineBreaksAndTerminalEscapesBecomeHex() {
        assertThat(LogText.safe("evil\r\nWARNING: forged\u001B[31m"))
                .isEqualTo("evil\\x0D\\x0AWARNING: forged\\x1B[31m");
    }

    @Test
    void everyC0DelAndC1CharacterIsEscapedAndTheirNeighboursAreNot() {
        assertThat(LogText.safe("\u0000\u0009\u001F")).isEqualTo("\\x00\\x09\\x1F");
        assertThat(LogText.safe("\u007F\u0080\u0085\u009B\u009F")).isEqualTo("\\x7F\\x80\\x85\\x9B\\x9F");
        assertThat(LogText.safe(" ~\u00A0")).isEqualTo(" ~\u00A0");
    }

    @Test
    void aBackslashIsDoubledSoThatEscapesAreUnambiguous() {
        assertThat(LogText.safe("\\x0A")).isEqualTo("\\\\x0A");
        assertThat(LogText.safe("\\x0A")).isNotEqualTo(LogText.safe("\n"));
    }

    @Test
    void lineSeparatorsAndDirectionControlsBecomeUnicodeEscapes() {
        assertThat(LogText.safe("\u2028\u2029")).isEqualTo("\\u2028\\u2029");
        assertThat(LogText.safe("\u200E\u200F")).isEqualTo("\\u200E\\u200F");
        assertThat(LogText.safe("\u202A\u202B\u202C\u202D\u202E"))
                .isEqualTo("\\u202A\\u202B\\u202C\\u202D\\u202E");
        assertThat(LogText.safe("\u2066\u2067\u2068\u2069")).isEqualTo("\\u2066\\u2067\\u2068\\u2069");
        // Neighbouring characters print as themselves.
        assertThat(LogText.safe("\u200D\u2010\u2027\u202F\u2065\u206A"))
                .isEqualTo("\u200D\u2010\u2027\u202F\u2065\u206A");
    }

    @Test
    void ordinaryHeaderValuesStayAsTheyAre() {
        assertThat(LogText.safe("http://127.0.0.1:8460")).isEqualTo("http://127.0.0.1:8460");
        assertThat(LogText.safe("Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0"))
                .isEqualTo("Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0");
        assertThat(LogText.safe("")).isEmpty();
    }

    @Test
    void aLongValueIsCutAtTwoHundredCharactersWithTheDroppedCount() {
        assertThat(LogText.safe("a".repeat(200))).isEqualTo("a".repeat(200));
        assertThat(LogText.safe("a".repeat(201))).isEqualTo("a".repeat(200) + "\u2026(+1)");
        assertThat(LogText.safe("a".repeat(250))).isEqualTo("a".repeat(200) + "\u2026(+50)");
    }

    @Test
    void theLimitCountsTheSourceCharactersNotTheEscapes() {
        assertThat(LogText.safe("\n".repeat(250))).isEqualTo("\\x0A".repeat(200) + "\u2026(+50)");
    }

    @Test
    void theCutDoesNotSplitASurrogatePair() {
        String emoji = "\uD83D\uDE00";

        assertThat(LogText.safe("a".repeat(199) + emoji + "b")).isEqualTo("a".repeat(199) + "\u2026(+3)");
        assertThat(LogText.safe("a".repeat(198) + emoji + "b")).isEqualTo("a".repeat(198) + emoji + "\u2026(+1)");
    }

    @Test
    void nullIsWrittenAsNull() {
        assertThat(LogText.safe(null)).isEqualTo("null");
    }

    @Test
    @Tag("LOG-3")
    void escapeEscapesAWholeLineWithoutCuttingIt() {
        assertThat(LogText.escape("a".repeat(300) + "\n\\")).isEqualTo("a".repeat(300) + "\\x0A\\\\");
        assertThat(LogText.escape(null)).isEqualTo("null");
    }

    @Test
    @Tag("LOG-3")
    void aCauseIsTheChainOfFailuresOnOneSafeLine() {
        Exception failure = new IllegalStateException("outer", new java.io.IOException("inner\r\nforged"));

        assertThat(LogText.cause(failure)).isEqualTo(
                "java.lang.IllegalStateException: outer; caused by: java.io.IOException: inner\\x0D\\x0Aforged");
        assertThat(LogText.cause(new RuntimeException("x".repeat(300)))).endsWith("\u2026(+" + (300 + 28 - 200) + ")");
    }
}
