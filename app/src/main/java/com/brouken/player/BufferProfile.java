package com.brouken.player;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import java.util.Locale;

public final class BufferProfile {

    private static final long LOW_MEMORY_LIMIT_MB = 4500;
    private static final long HIGH_MEMORY_LIMIT_MB = 6500;

    private static final int LOW_BATTERY_PERCENT = 20;

    private BufferProfile() {
    }

    public enum Tier {
        LOW("device-low", 30_000, 120_000, 30_000, 64),
        BALANCED("device-balanced", 50_000, 300_000, 75_000, 192),
        HIGH("device-high", 60_000, 600_000, 150_000, 384),
        BATTERY_SAVER("battery-saver", 20_000, 60_000, 15_000, 24),
        LIVE("live-stream", 5_000, 15_000, 4_000, 16);

        final String label;
        final int minBufferMs;
        final int maxBufferMs;
        final int backBufferMs;
        final int targetBufferMb;

        Tier(String label, int minBufferMs, int maxBufferMs, int backBufferMs, int targetBufferMb) {
            this.label = label;
            this.minBufferMs = minBufferMs;
            this.maxBufferMs = maxBufferMs;
            this.backBufferMs = backBufferMs;
            this.targetBufferMb = targetBufferMb;
        }
    }

    static long totalMemoryMb(final Context context) {
        try {
            final ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return -1;
            }
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.totalMem / (1024L * 1024L);
        } catch (Exception e) {
            Utils.log("Could not read total memory: " + e);
            return -1;
        }
    }

    static Tier deviceTier(final Context context) {
        final long memoryMb = totalMemoryMb(context);
        // Unreadable memory takes the safest tier, as the Lua does.
        if (memoryMb < 0 || memoryMb < LOW_MEMORY_LIMIT_MB) {
            return Tier.LOW;
        }
        return memoryMb < HIGH_MEMORY_LIMIT_MB ? Tier.BALANCED : Tier.HIGH;
    }

    static boolean batterySaver(final Context context) {
        try {
            final Intent status =
                    context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) {
                return false;
            }
            final int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return false;
            }
            final int percent = (int) ((level * 100L) / scale);

            final int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            // Unknown charging state is treated as charging, so a device that will
            // not say is never held to the smaller buffer.
            final boolean charging = plugged != 0;

            return percent <= LOW_BATTERY_PERCENT && !charging;
        } catch (Exception e) {
            Utils.log("Could not read battery state: " + e);
            return false;
        }
    }

    static boolean looksLive(@Nullable final Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("rtmp") || scheme.equals("rtmps") || scheme.equals("rtsp")
                || scheme.equals("udp") || scheme.equals("srt")) {
            return true;
        }
        final String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    public static Tier tierFor(final Context context, @Nullable final Uri uri) {
        if (looksLive(uri)) {
            return Tier.LIVE;
        }
        if (batterySaver(context)) {
            return Tier.BATTERY_SAVER;
        }
        return deviceTier(context);
    }

    @NonNull
    static LoadControl create(final Context context, @Nullable final Uri uri) {
        final Tier tier = tierFor(context, uri);

        final int budgetMb = heapBudgetMb(context, tier);

        Utils.log("Buffering: " + tier.label + " up to " + budgetMb + " MiB, "
                + (tier.maxBufferMs / 1000) + "s");

        return new DefaultLoadControl.Builder()
                .setAllocator(new DefaultAllocator(true, C_DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(
                        tier.minBufferMs,
                        tier.maxBufferMs,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setBackBuffer(tier.backBufferMs, true)
                .setTargetBufferBytes(budgetMb * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
    }

    static int heapBudgetMb(final Context context, final Tier tier) {
        int heapMb = 0;
        try {
            final ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                heapMb = manager.getLargeMemoryClass();
            }
        } catch (Exception e) {
            Utils.log("Could not read the heap limit: " + e);
        }
        if (heapMb <= 0) {
            // No answer: take the tier at its word but stay modest.
            return Math.min(tier.targetBufferMb, 32);
        }
        return Math.max(8, Math.min(tier.targetBufferMb, heapMb / 2));
    }

    private static final int C_DEFAULT_BUFFER_SEGMENT_SIZE = 64 * 1024;
}
