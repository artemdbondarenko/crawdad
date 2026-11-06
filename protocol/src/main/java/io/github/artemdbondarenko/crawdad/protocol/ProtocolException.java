package io.github.artemdbondarenko.crawdad.protocol;

/**
 * A text received over the protocol is not a valid message. The message is meant for the peer that
 * sent the text, so it names the problem without library details.
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
