package io.github.artemdbondarenko.crawdad.service.cli;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;

/**
 * A QR code as terminal text: each character holds two modules, one above the other, as half blocks.
 * Light modules and the quiet zone are drawn and dark ones left blank, for the usual light text on a dark
 * terminal; a phone camera reads it either way.
 */
final class QrText {

    /** Modules of light margin around the code; scanners need some to find its edge. */
    static final int QUIET_ZONE = 2;

    private static final char BOTH = '█';
    private static final char UPPER = '▀';
    private static final char LOWER = '▄';
    private static final char NONE = ' ';

    private QrText() {}

    /** The QR code of {@code text} as lines separated by {@code \n}, without a trailing newline. */
    static String render(String text) {
        ByteMatrix matrix;
        try {
            matrix = Encoder.encode(text, ErrorCorrectionLevel.M).getMatrix();
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode a QR code of " + text.length() + " characters", e);
        }
        int size = matrix.getWidth() + 2 * QUIET_ZONE;
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < size; row += 2) {
            if (row > 0) {
                out.append('\n');
            }
            for (int column = 0; column < size; column++) {
                boolean upper = light(matrix, column, row);
                // An odd last row is finished with light, as if the quiet zone went on.
                boolean lower = row + 1 >= size || light(matrix, column, row + 1);
                out.append(upper ? (lower ? BOTH : UPPER) : (lower ? LOWER : NONE));
            }
        }
        return out.toString();
    }

    /** Whether the module at a position of the code with its quiet zone is light. */
    private static boolean light(ByteMatrix matrix, int column, int row) {
        int x = column - QUIET_ZONE;
        int y = row - QUIET_ZONE;
        if (x < 0 || y < 0 || x >= matrix.getWidth() || y >= matrix.getHeight()) {
            return true;
        }
        return matrix.get(x, y) != 1;
    }
}
