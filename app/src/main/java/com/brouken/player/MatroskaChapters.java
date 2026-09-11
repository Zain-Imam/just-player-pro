package com.brouken.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.online.SkipSegments;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MatroskaChapters {

    private static final long ID_SEGMENT = 0x18538067L;
    private static final long ID_CHAPTERS = 0x1043A770L;
    private static final long ID_EDITION_ENTRY = 0x45B9L;
    private static final long ID_CHAPTER_ATOM = 0xB6L;
    private static final long ID_CHAPTER_TIME_START = 0x91L;
    private static final long ID_CHAPTER_DISPLAY = 0x80L;
    private static final long ID_CHAP_STRING = 0x85L;
    private static final long ID_CHAPTER_FLAG_HIDDEN = 0x98L;

    private static final long SEARCH_LIMIT_BYTES = 64L * 1024 * 1024;

    private static final int MAX_CHAPTERS = 500;

    private MatroskaChapters() {
    }

    @NonNull
    public static List<SkipSegments.ChapterMark> read(final Context context,
                                                      @Nullable final Uri uri) {
        final List<SkipSegments.ChapterMark> chapters = new ArrayList<>();
        if (uri == null || !isLocal(uri) || !looksLikeMatroska(context, uri)) {
            return chapters;
        }

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                return chapters;
            }
            final Reader reader = new Reader(new BufferedInputStream(raw, 64 * 1024));
            readTop(reader, chapters);
        } catch (IOException | SecurityException | IllegalStateException
                 | IllegalArgumentException e) {
            Utils.log("Chapters could not be read: " + e);
        }
        return chapters;
    }

    private static boolean isLocal(final Uri uri) {
        final String scheme = uri.getScheme();
        return "file".equals(scheme) || "content".equals(scheme);
    }

    private static boolean looksLikeMatroska(final Context context, final Uri uri) {
        final String name = Utils.getFileName(context, uri, true);
        if (name == null) {
            // A content URI may not say; the EBML header check below decides.
            return true;
        }
        return name.matches("(?i).*\\.(mkv|mka|mks|webm)$");
    }

    private static void readTop(final Reader reader,
                                final List<SkipSegments.ChapterMark> out) throws IOException {
        while (reader.position() < SEARCH_LIMIT_BYTES) {
            final long id = reader.readId();
            if (id < 0) {
                return;
            }
            final long size = reader.readSize();
            if (size < 0) {
                return;
            }
            if (id == ID_SEGMENT) {
                // Descend rather than skip: everything interesting is inside.
                readSegment(reader, out);
                return;
            }
            reader.skip(size);
        }
    }

    private static void readSegment(final Reader reader,
                                    final List<SkipSegments.ChapterMark> out) throws IOException {
        while (reader.position() < SEARCH_LIMIT_BYTES) {
            final long id = reader.readId();
            if (id < 0) {
                return;
            }
            final long size = reader.readSize();
            if (size < 0) {
                return;
            }
            if (id == ID_CHAPTERS) {
                readChapters(reader, reader.position() + size, out);
                return; // one Chapters element is all there is
            }
            reader.skip(size);
        }
    }

    private static void readChapters(final Reader reader, final long end,
                                     final List<SkipSegments.ChapterMark> out) throws IOException {
        while (reader.position() < end) {
            final long id = reader.readId();
            if (id < 0) {
                return;
            }
            final long size = reader.readSize();
            if (size < 0) {
                return;
            }
            if (id == ID_EDITION_ENTRY) {
                readEdition(reader, reader.position() + size, out);
                // The first edition is the one players use.
                return;
            }
            reader.skip(size);
        }
    }

    private static void readEdition(final Reader reader, final long end,
                                    final List<SkipSegments.ChapterMark> out) throws IOException {
        while (reader.position() < end && out.size() < MAX_CHAPTERS) {
            final long id = reader.readId();
            if (id < 0) {
                return;
            }
            final long size = reader.readSize();
            if (size < 0) {
                return;
            }
            if (id == ID_CHAPTER_ATOM) {
                readAtom(reader, reader.position() + size, out);
            } else {
                reader.skip(size);
            }
        }
    }

    private static void readAtom(final Reader reader, final long end,
                                 final List<SkipSegments.ChapterMark> out) throws IOException {
        long startNanos = -1;
        String title = "";
        boolean hidden = false;

        while (reader.position() < end) {
            final long id = reader.readId();
            if (id < 0) {
                return;
            }
            final long size = reader.readSize();
            if (size < 0) {
                return;
            }

            if (id == ID_CHAPTER_TIME_START) {
                startNanos = reader.readUnsigned(size);
            } else if (id == ID_CHAPTER_FLAG_HIDDEN) {
                hidden = reader.readUnsigned(size) != 0;
            } else if (id == ID_CHAPTER_DISPLAY) {
                final long displayEnd = reader.position() + size;
                while (reader.position() < displayEnd) {
                    final long innerId = reader.readId();
                    if (innerId < 0) {
                        return;
                    }
                    final long innerSize = reader.readSize();
                    if (innerSize < 0) {
                        return;
                    }
                    if (innerId == ID_CHAP_STRING && title.isEmpty()) {
                        title = reader.readString(innerSize);
                    } else {
                        reader.skip(innerSize);
                    }
                }
            } else {
                reader.skip(size);
            }
        }

        if (startNanos >= 0 && !hidden) {
            out.add(new SkipSegments.ChapterMark(title, startNanos / 1_000_000_000.0));
        }
    }

    private static final class Reader {

        private final InputStream in;
        private long position;

        Reader(final InputStream in) {
            this.in = in;
        }

        long position() {
            return position;
        }

        long readId() throws IOException {
            final int first = in.read();
            if (first < 0) {
                return -1;
            }
            position++;
            final int length = lengthOf(first);
            if (length < 1) {
                return -1;
            }
            long id = first;
            for (int i = 1; i < length; i++) {
                final int next = in.read();
                if (next < 0) {
                    return -1;
                }
                position++;
                id = (id << 8) | next;
            }
            return id;
        }

        long readSize() throws IOException {
            final int first = in.read();
            if (first < 0) {
                return -1;
            }
            position++;
            final int length = lengthOf(first);
            if (length < 1) {
                return -1;
            }
            long size = first & (0xFF >> length);
            for (int i = 1; i < length; i++) {
                final int next = in.read();
                if (next < 0) {
                    return -1;
                }
                position++;
                size = (size << 8) | next;
            }
            return size;
        }

        long readUnsigned(final long length) throws IOException {
            long value = 0;
            for (long i = 0; i < length; i++) {
                final int next = in.read();
                if (next < 0) {
                    return value;
                }
                position++;
                value = (value << 8) | next;
            }
            return value;
        }

        String readString(final long length) throws IOException {
            if (length <= 0 || length > 4096) {
                skip(length);
                return "";
            }
            final byte[] bytes = new byte[(int) length];
            int read = 0;
            while (read < bytes.length) {
                final int count = in.read(bytes, read, bytes.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
                position += count;
            }
            return new String(bytes, 0, Math.max(0, read), StandardCharsets.UTF_8).trim();
        }

        void skip(final long length) throws IOException {
            long remaining = length;
            while (remaining > 0) {
                final long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    // skip() can refuse; a read keeps us moving.
                    if (in.read() < 0) {
                        return;
                    }
                    position++;
                    remaining--;
                    continue;
                }
                position += skipped;
                remaining -= skipped;
            }
        }

        private static int lengthOf(final int firstByte) {
            for (int i = 0; i < 8; i++) {
                if ((firstByte & (0x80 >> i)) != 0) {
                    return i + 1;
                }
            }
            return -1;
        }
    }
}
