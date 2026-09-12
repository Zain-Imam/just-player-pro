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

/*
 * A square somebody can point a phone at.
 *
 * A television has no share sheet worth the name and often no mail or messaging
 * app at all, so an error report shown there is an error report that stays
 * there. The details are small — a version, a device, an error code — and they
 * fit in a code that can be read off the screen from the sofa.
 */
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
            // A screen photographed across a room is a poor scan; the highest
            // correction level survives it and the payload is small enough to
            // afford the room.
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
                    // Dark on light whatever the app's theme is: a scanner
                    // expects that way round and half of them will not invert.
                    pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            // A payload too big for a code, or a device that would not give the
            // memory. The text is still on screen either way.
            return null;
        }
    }
}
