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

/**
 * Everything on the device that can be played, as folders and files.
 *
 * <p>One query, grouped here rather than by the database. The chooser this
 * project already had asks MediaStore to group for it, which is why it carries
 * {@code @RequiresApi(R)} — that form of the call arrived in Android 11, and
 * this application still runs on 7.1, which is exactly the old television boxes
 * it is best on. Counting rows in a loop costs nothing at these sizes and works
 * everywhere.
 *
 * <p>Nothing here touches the network and nothing here decodes a frame: a
 * folder list should appear instantly on a box that struggles to draw one.
 */
public final class Library {

    private Library() {
    }

    /** A directory holding at least one playable file. */
    public static final class Folder {
        /** What identifies it, and what a favourite is stored against. */
        public final String id;
        /**
         * What to call it on screen.
         *
         * <p>Not final: where two folders share a leaf name they are given the
         * one above them as well, and that can only be known once every folder
         * has been found. See disambiguate().
         */
        public String name;
        public int count;
        public long size;
        public long modified;

        // Package-private for the same reason.
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
        /** What the folder is called, carried so the grouping need not guess. */
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

    /**
     * Everything MediaStore knows about, newest query wins.
     *
     * <p>Returns an empty list rather than throwing: a missing permission, a
     * volume being unmounted mid-query and a provider that simply declines all
     * arrive here as exceptions, and an empty home screen with a line of text
     * on it is a better answer than a crash on launch.
     */
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
        // The path is what a folder is below Android 10, and remains the better
        // answer above it: two cards can hold folders of the same name, and the
        // display name alone cannot tell them apart.
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
                    // The directory's own name, falling back to what the store
                    // calls the bucket when the path ends in nothing useful.
                    final String segment = lastSegment(parent);
                    folderName = segment == null || segment.isEmpty()
                            ? (bucketName == null ? parent : bucketName) : segment;
                } else if (bucketId != null && !bucketId.isEmpty()) {
                    // No path: allowed from Android 10 on, and a file with no
                    // home at all would simply vanish from the list.
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

    /**
     * The folders those files live in, each with what is in it.
     *
     * <p>Insertion-ordered, so the caller decides the order rather than
     * inheriting whatever the map felt like.
     */
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

    /**
     * Tell apart the folders that share a name.
     *
     * <p>A phone has several directories called "WhatsApp Animated Gifs" — one
     * under WhatsApp, one under WhatsApp Business, one under Sent — and they
     * arrived here as four identical-looking rows, two of them even carrying
     * the same count and the same size. There is no way to choose between
     * those, and no way to tell which one a favourite was put against.
     *
     * <p>Only the ones that clash are touched. Giving every folder its parent
     * would make a tidy library read like a file path for the sake of a
     * handful of duplicates that most people do not have.
     */
    // Package-private so it can be checked without a device: the logic is path
    // arithmetic and the failures are silent, which is the worst combination.
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
            /*
             * The shortest part of the path above that actually tells them
             * apart, and no more of it than that.
             *
             * One step up is the obvious answer and on a real phone it is the
             * wrong one: WhatsApp keeps a copy of this folder per account, all
             * of them under a directory called "Media", so naming the parent
             * produced four rows reading "· Media" and helped nobody. A step
             * further is no better — two of the five then read "· WhatsApp".
             *
             * So: drop what every one of them has in common, then give each the
             * least it needs to be unique. The accounts differ one level up and
             * get "1001", "1003", "1004"; the two that need two levels get both.
             */
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

    /**
     * Every folder above this one, nearest first, stopping at the card.
     *
     * <p>Nothing above the storage root is a place anybody recognises.
     * Internal storage is {@code /storage/emulated/0}, and walking past the
     * folders into that produced a row labelled "0/WhatsApp" — which is the
     * Android user id and means nothing at all to the person reading it.
     */
    private static List<String> ancestors(final String path) {
        final List<String> chain = new ArrayList<>();
        String at = parentOf(path);
        while (at != null && chain.size() < 8) {
            final String segment = lastSegment(at);
            if (segment == null || segment.isEmpty()
                    || "storage".equals(segment) || "emulated".equals(segment)) {
                break;
            }
            // The user id under "emulated", which is the root of internal
            // storage however it is numbered.
            if ("emulated".equals(lastSegment(parentOf(at)))) {
                break;
            }
            chain.add(segment);
            at = parentOf(at);
        }
        return chain;
    }

    /**
     * How many levels, from nearest up, every one of them shares.
     *
     * <p>Never all of the shortest one. Where one path is the tail of another —
     * {@code /WhatsApp/Media/X} inside
     * {@code /Android/media/com.whatsapp/WhatsApp/Media/X} — the shorter chain
     * runs out exactly when the common part ends, and dropping all of it left
     * that folder with nothing to be called and no label at all, sitting bare
     * beside others that had one. One level is always kept back.
     */
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

    /**
     * The fewest levels above {@code common} that make this one unique,
     * written the way a path reads: outermost first.
     */
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
        /*
         * Nothing unique to say, so say everything there is.
         *
         * This is the shallowest of a clashing set — the one whose path runs
         * out before the others' do, where /WhatsApp/Media/X sits beside
         * /Android/media/com.whatsapp/WhatsApp/Media/X. Left unlabelled it was
         * the only row in the group without a location, which reads as an
         * oversight. Its whole chain is still shorter than theirs, so the two
         * labels differ anyway.
         */
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
