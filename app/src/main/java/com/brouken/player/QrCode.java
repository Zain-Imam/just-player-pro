package com.brouken.player;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

// QR code for error details, since TVs often have no way to share text
public final class QrCode {

    private QrCode() {
    }

    @Nullable
    public static Bitmap of(final String text, final int sizePx) {
        if (text == null || text.isEmpty() || sizePx <= 0) {
            return null;
        }
        try {
            final Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // highest correction level: scanned off a screen across a room
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 2);

            final BitMatrix matrix = new QRCodeWriter()
                    .encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            final int width = matrix.getWidth();
            final int height = matrix.getHeight();
            final int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                final int row = y * width;
                for (int x = 0; x < width; x++) {
                    // always dark on light: many scanners do not handle inverted codes
                    pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            // payload too large or out of memory; the text is still shown
            return null;
        }
    }
}
