package io.github.artemdbondarenko.crawdad.service.cli;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.Arrays;
import java.util.Map;

/** Reads a QR code printed by {@link QrText} back with ZXing, as a phone camera would read the terminal. */
final class QrDecoder {

    private static final int SCALE = 4;
    private static final int BORDER = 8;
    private static final int LIGHT = 0xFFFFFF;
    private static final int DARK = 0x000000;

    private QrDecoder() {}

    /** The text of the QR code made of the half-block {@code lines}. */
    static String decode(String text) throws Exception {
        String[] lines = text.split("\n");
        int columns = lines[0].length();
        int rows = lines.length * 2;
        int width = columns * SCALE + 2 * BORDER;
        int height = rows * SCALE + 2 * BORDER;
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, LIGHT);
        for (int line = 0; line < lines.length; line++) {
            for (int column = 0; column < columns; column++) {
                char c = lines[line].charAt(column);
                boolean topLight = c == '▀' || c == '█';
                boolean bottomLight = c == '▄' || c == '█';
                paint(pixels, width, column, line * 2, topLight);
                paint(pixels, width, column, line * 2 + 1, bottomLight);
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
    }

    private static void paint(int[] pixels, int width, int column, int row, boolean light) {
        for (int y = 0; y < SCALE; y++) {
            for (int x = 0; x < SCALE; x++) {
                pixels[(BORDER + row * SCALE + y) * width + BORDER + column * SCALE + x] = light ? LIGHT : DARK;
            }
        }
    }
}
