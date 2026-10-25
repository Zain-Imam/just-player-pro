package com.brouken.player.mpv;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

// the family name libass matches sub-font against; a .ttc gives its first font
public final class FontFamilyName {

    private static final int TAG_TTCF = 0x74746366;
    private static final int TAG_NAME = 0x6E616D65;
    private static final int NAME_ID_FAMILY = 1;
    private static final int LANGUAGE_EN_US = 0x0409;

    private FontFamilyName() {
    }

    @Nullable
    public static String read(final File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            // mapped, so a large CJK font isn't read just for its name
            return read(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    public static String read(final ByteBuffer font) {
        try {
            final ByteBuffer b = font.duplicate().order(ByteOrder.BIG_ENDIAN);
            int directory = 0;
            if (b.getInt(0) == TAG_TTCF) {
                directory = b.getInt(12);
            }
            final int numTables = u16(b, directory + 4);
            for (int i = 0; i < numTables; i++) {
                final int record = directory + 12 + i * 16;
                if (b.getInt(record) == TAG_NAME) {
                    return fromNameTable(b, b.getInt(record + 8));
                }
            }
        } catch (IndexOutOfBoundsException e) {
            // not a font, or a broken one
        }
        return null;
    }

    // Windows Unicode first (US English, then any language), then Mac Roman
    @Nullable
    private static String fromNameTable(final ByteBuffer b, final int table) {
        final int count = u16(b, table + 2);
        final int strings = table + u16(b, table + 4);
        String windows = null;
        boolean windowsUs = false;
        String mac = null;
        for (int i = 0; i < count; i++) {
            final int record = table + 6 + i * 12;
            final int platform = u16(b, record);
            final int encoding = u16(b, record + 2);
            final int language = u16(b, record + 4);
            final int nameId = u16(b, record + 6);
            final int length = u16(b, record + 8);
            final int offset = strings + u16(b, record + 10);
            if (nameId != NAME_ID_FAMILY || length == 0) {
                continue;
            }
            if (platform == 3 && encoding == 1) {
                if (windows == null || (!windowsUs && language == LANGUAGE_EN_US)) {
                    final String name = clean(decode(b, offset, length, StandardCharsets.UTF_16BE));
                    if (name != null) {
                        windows = name;
                        windowsUs = language == LANGUAGE_EN_US;
                    }
                }
            } else if (platform == 1 && encoding == 0 && mac == null) {
                mac = clean(decode(b, offset, length, macRoman()));
            }
        }
        return windows != null ? windows : mac;
    }

    private static String decode(final ByteBuffer b, final int offset, final int length,
                                 final Charset charset) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = b.get(offset + i);
        }
        return new String(bytes, charset);
    }

    @Nullable
    private static String clean(final String name) {
        final String trimmed = name.replace("\u0000", "").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Charset macRoman() {
        try {
            return Charset.forName("x-MacRoman");
        } catch (RuntimeException e) {
            // the same for the plain ASCII almost every family name is
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static int u16(final ByteBuffer b, final int index) {
        return b.getShort(index) & 0xFFFF;
    }
}
