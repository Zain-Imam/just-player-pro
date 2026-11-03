package com.brouken.player.engine;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

// YouTube slows a request for the rest of a file to about 0.8 MB/s but serves 10 MB ranges at
// full speed, so one long read is made of ranges asked for one after another
public final class RangedDataSource implements DataSource {

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory inner;
        private final long pieceBytes;

        public Factory(final DataSource.Factory inner, final long pieceBytes) {
            this.inner = inner;
            this.pieceBytes = pieceBytes;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new RangedDataSource(inner.createDataSource(), pieceBytes);
        }
    }

    private final DataSource inner;
    private final long pieceBytes;

    @Nullable
    private DataSpec spec;
    private long position;
    private long remaining;
    private long pieceAsked;
    private long pieceLeft;
    private boolean pieceOpen;

    public RangedDataSource(final DataSource inner, final long pieceBytes) {
        this.inner = inner;
        this.pieceBytes = pieceBytes;
    }

    @Override
    public void addTransferListener(@NonNull final TransferListener transferListener) {
        inner.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull final DataSpec dataSpec) throws IOException {
        spec = dataSpec;
        position = dataSpec.position;
        remaining = dataSpec.length;
        openPiece();
        if (remaining != C.LENGTH_UNSET) {
            return remaining;
        }
        final long total = totalLength();
        return total == C.LENGTH_UNSET ? C.LENGTH_UNSET : total - position;
    }

    private void openPiece() throws IOException {
        pieceAsked = remaining == C.LENGTH_UNSET ? pieceBytes : Math.min(pieceBytes, remaining);
        final DataSpec piece = spec.subrange(position - spec.position, pieceAsked);
        final long got = inner.open(piece);
        pieceOpen = true;
        pieceLeft = got == C.LENGTH_UNSET ? pieceAsked : got;
    }

    // from "Content-Range: bytes 0-10485759/257619653"
    private long totalLength() {
        final Map<String, List<String>> headers = inner.getResponseHeaders();
        for (final Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey() == null || !"Content-Range".equalsIgnoreCase(header.getKey())
                    || header.getValue().isEmpty()) {
                continue;
            }
            final String value = header.getValue().get(0);
            final int slash = value.lastIndexOf('/');
            if (slash > 0) {
                try {
                    return Long.parseLong(value.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    return C.LENGTH_UNSET;
                }
            }
        }
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(@NonNull final byte[] buffer, final int offset, final int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        int read = inner.read(buffer, offset, length);
        if (read == C.RESULT_END_OF_INPUT) {
            // a short piece was the last one
            if (pieceLeft > 0 || pieceAsked < pieceBytes) {
                return C.RESULT_END_OF_INPUT;
            }
            inner.close();
            pieceOpen = false;
            try {
                openPiece();
            } catch (HttpDataSource.InvalidResponseCodeException e) {
                if (e.responseCode == 416) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw e;
            }
            read = inner.read(buffer, offset, length);
            if (read == C.RESULT_END_OF_INPUT) {
                return read;
            }
        }
        position += read;
        pieceLeft -= read;
        if (remaining != C.LENGTH_UNSET) {
            remaining -= read;
        }
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return inner.getUri();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return inner.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        spec = null;
        if (pieceOpen) {
            pieceOpen = false;
            inner.close();
        }
    }
}
