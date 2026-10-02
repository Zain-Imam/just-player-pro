package com.brouken.player.home;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Videos on the device as folders and files. Grouped in code because
// MediaStore's grouped query needs Android 11.
public final class Library {

    private Library() {
    }

    /** A directory holding at least one playable file. */
    public static final class Folder {
        /** Also the key a pin is stored against. */
        public final String id;
        /** Not final: disambiguate() extends names that clash. */
        public String name;
        public int count;
        public long size;
        public long modified;

        Folder(final String id, final String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Video {
        public final Uri uri;
        public final String name;
        public final long size;
        public final long duration;
        public final long modified;
        public final String folderId;
        public final String folderName;

        Video(final Uri uri, final String name, final long size, final long duration,
              final long modified, final String folderId, final String folderName) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.duration = duration;
            this.modified = modified;
            this.folderId = folderId;
            this.folderName = folderName;
        }
    }

    // empty on any error: no permission, unmounted volume, refusing provider
    @NonNull
    public static List<Video> videos(@NonNull final Context context) {
        final List<Video> videos = new ArrayList<>();

        final boolean buckets = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        final List<String> projection = new ArrayList<>();
        projection.add(MediaStore.Video.Media._ID);
        projection.add(MediaStore.Video.Media.DISPLAY_NAME);
        projection.add(MediaStore.Video.Media.SIZE);
        projection.add(MediaStore.Video.Media.DURATION);
        projection.add(MediaStore.Video.Media.DATE_MODIFIED);
        // keyed by path: no buckets below 10, and names repeat across cards
        projection.add(MediaStore.Video.Media.DATA);
        if (buckets) {
            projection.add(MediaStore.MediaColumns.BUCKET_ID);
            projection.add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
        }

        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection.toArray(new String[0]), null, null, null)) {
            if (cursor == null) {
                return videos;
            }
            final int columnId = cursor.getColumnIndex(MediaStore.Video.Media._ID);
            final int columnName = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            final int columnSize = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
            final int columnDuration = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
            final int columnModified = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);
            final int columnData = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
            final int columnBucketId = buckets
                    ? cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID) : -1;
            final int columnBucketName = buckets
                    ? cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME) : -1;

            while (cursor.moveToNext()) {
                final long id = columnId < 0 ? -1 : cursor.getLong(columnId);
                if (id < 0) {
                    continue;
                }
                final String path = columnData < 0 ? null : cursor.getString(columnData);
                final String bucketId = columnBucketId < 0 ? null : cursor.getString(columnBucketId);
                final String bucketName = columnBucketName < 0
                        ? null : cursor.getString(columnBucketName);
                final String parent = parentOf(path);

                final String folderId;
                final String folderName;
                if (parent != null && !parent.isEmpty()) {
                    folderId = parent;
                    final String segment = lastSegment(parent);
                    folderName = segment == null || segment.isEmpty()
                            ? (bucketName == null ? parent : bucketName) : segment;
                } else if (bucketId != null && !bucketId.isEmpty()) {
                    // DATA may be empty from Android 10 on
                    folderId = "bucket:" + bucketId;
                    folderName = bucketName == null || bucketName.isEmpty()
                            ? bucketId : bucketName;
                } else {
                    continue;
                }

                String name = columnName < 0 ? null : cursor.getString(columnName);
                if (name == null || name.isEmpty()) {
                    name = lastSegment(path);
                }
                if (name == null || name.isEmpty()) {
                    continue;
                }
                videos.add(new Video(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        name,
                        columnSize < 0 ? 0L : cursor.getLong(columnSize),
                        columnDuration < 0 ? 0L : cursor.getLong(columnDuration),
                        columnModified < 0 ? 0L : cursor.getLong(columnModified),
                        folderId, folderName));
            }
        } catch (Exception error) {
            Utils.log("Could not read the media store: " + error);
        }

        return videos;
    }

    // in scan order; the caller sorts
    @NonNull
    public static List<Folder> folders(@NonNull final List<Video> videos) {
        final Map<String, Folder> byId = new LinkedHashMap<>();
        for (final Video video : videos) {
            Folder folder = byId.get(video.folderId);
            if (folder == null) {
                folder = new Folder(video.folderId, video.folderName);
                byId.put(video.folderId, folder);
            }
            folder.count++;
            folder.size += video.size;
            folder.modified = Math.max(folder.modified, video.modified);
        }
        final List<Folder> folders = new ArrayList<>(byId.values());
        disambiguate(folders);
        return folders;
    }

    // appends enough of the parent path to tell same-named folders apart;
    // package-private for tests
    static void disambiguate(final List<Folder> folders) {
        final Map<String, List<Folder>> byName = new LinkedHashMap<>();
        for (final Folder folder : folders) {
            List<Folder> group = byName.get(folder.name);
            if (group == null) {
                group = new ArrayList<>();
                byName.put(folder.name, group);
            }
            group.add(folder);
        }

        for (final List<Folder> group : byName.values()) {
            if (group.size() < 2) {
                continue;
            }
            // skip levels all share (often "Media"), then add the fewest that differ
            final List<List<String>> chains = new ArrayList<>();
            for (final Folder folder : group) {
                chains.add(ancestors(folder.id));
            }
            final int common = commonPrefix(chains);
            for (int i = 0; i < group.size(); i++) {
                final String label = shortestUnique(chains, i, common);
                if (label != null) {
                    group.get(i).name = group.get(i).name + "  ·  " + label;
                }
            }
        }
    }

    // folder names above this one, nearest first, stopping at the storage root
    private static List<String> ancestors(final String path) {
        final List<String> chain = new ArrayList<>();
        String at = parentOf(path);
        while (at != null && chain.size() < 8) {
            final String segment = lastSegment(at);
            if (segment == null || segment.isEmpty()
                    || "storage".equals(segment) || "emulated".equals(segment)) {
                break;
            }
            // the user id under "emulated" is the root of internal storage
            if ("emulated".equals(lastSegment(parentOf(at)))) {
                break;
            }
            chain.add(segment);
            at = parentOf(at);
        }
        return chain;
    }

    // shared levels, capped one below the shortest so it keeps a label
    private static int commonPrefix(final List<List<String>> chains) {
        int shortest = Integer.MAX_VALUE;
        for (final List<String> chain : chains) {
            shortest = Math.min(shortest, chain.size());
        }
        final int limit = Math.max(0, shortest - 1);

        int depth = 0;
        while (depth < limit) {
            String value = null;
            for (final List<String> chain : chains) {
                if (value == null) {
                    value = chain.get(depth);
                } else if (!value.equals(chain.get(depth))) {
                    return depth;
                }
            }
            depth++;
        }
        return depth;
    }

    /** The fewest levels above {@code common} that make this one unique. */
    @Nullable
    private static String shortestUnique(final List<List<String>> chains, final int index,
                                         final int common) {
        final List<String> mine = chains.get(index);
        for (int depth = common + 1; depth <= mine.size(); depth++) {
            final List<String> slice = mine.subList(common, depth);
            boolean unique = true;
            for (int other = 0; other < chains.size() && unique; other++) {
                if (other == index) {
                    continue;
                }
                final List<String> theirs = chains.get(other);
                if (theirs.size() >= depth && theirs.subList(common, depth).equals(slice)) {
                    unique = false;
                }
            }
            if (unique) {
                return joined(slice);
            }
        }
        // the shallowest of the set: its whole chain still differs from the rest
        return mine.size() > common ? joined(mine.subList(common, mine.size())) : null;
    }

    /** A slice of the chain the way a path reads: outermost first. */
    private static String joined(final List<String> slice) {
        final StringBuilder label = new StringBuilder();
        for (int i = slice.size() - 1; i >= 0; i--) {
            if (label.length() > 0) {
                label.append('/');
            }
            label.append(slice.get(i));
        }
        return label.toString();
    }

    /** Only the files in one folder, in the order they were scanned. */
    @NonNull
    public static List<Video> inFolder(@NonNull final List<Video> videos, final String folderId) {
        final List<Video> out = new ArrayList<>();
        for (final Video video : videos) {
            if (video.folderId.equals(folderId)) {
                out.add(video);
            }
        }
        return out;
    }

    /** Files whose name contains every word typed, in any order, ignoring case. */
    @NonNull
    public static List<Video> matching(@NonNull final List<Video> videos, final String query) {
        final String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new ArrayList<>();
        }
        final String[] words = trimmed.toLowerCase(Locale.getDefault()).split("\\s+");
        final List<Video> out = new ArrayList<>();
        for (final Video video : videos) {
            final String name = video.name.toLowerCase(Locale.getDefault());
            boolean all = true;
            for (final String word : words) {
                if (!name.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(video);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- naming

    @Nullable
    private static String parentOf(@Nullable final String path) {
        if (path == null) {
            return null;
        }
        final int cut = path.lastIndexOf('/');
        return cut <= 0 ? null : path.substring(0, cut);
    }

    @Nullable
    private static String lastSegment(@Nullable final String path) {
        if (path == null) {
            return null;
        }
        final int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }
}
