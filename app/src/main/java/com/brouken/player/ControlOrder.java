package com.brouken.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// stored as key lists, not indices: unknown keys are dropped, new ones appended
// keys are persisted, so never rename one
final class ControlOrder {

    static final String OPEN = "open";
    static final String SUBTITLE = "subtitle";
    static final String AUDIO = "audio";
    static final String ASPECT = "aspect";
    static final String SETTINGS = "settings";
    static final String LOCK = "lock";
    static final String ROTATE = "rotate";
    static final String PIP = "pip";
    static final String REPEAT = "repeat";

    static final String[] DEFAULT = {
            OPEN, SUBTITLE, AUDIO, ASPECT, SETTINGS, LOCK, ROTATE, PIP, REPEAT,
    };

    private static final String PREF_ORDER = "controlOrder";
    private static final String PREF_HIDDEN = "controlHidden";

    private ControlOrder() {
    }

    // the gear is the way back to this screen, so it cannot be hidden
    static boolean canHide(final String key) {
        return !SETTINGS.equals(key);
    }

    static List<String> order(final Context context) {
        final String stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_ORDER, null);
        final List<String> known = Arrays.asList(DEFAULT);
        final List<String> out = new ArrayList<>();
        if (stored != null) {
            for (final String key : stored.split(",")) {
                final String k = key.trim();
                if (known.contains(k) && !out.contains(k)) {
                    out.add(k);
                }
            }
        }
        // buttons missing from the stored order take their default place
        for (final String key : DEFAULT) {
            if (!out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    static Set<String> hidden(final Context context) {
        final String stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_HIDDEN, "");
        final Set<String> out = new LinkedHashSet<>();
        if (stored != null && !stored.isEmpty()) {
            final List<String> known = Arrays.asList(DEFAULT);
            for (final String key : stored.split(",")) {
                final String k = key.trim();
                if (known.contains(k) && canHide(k)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    static void save(final Context context, final List<String> order, final Set<String> hidden) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_ORDER, String.join(",", order))
                .putString(PREF_HIDDEN, String.join(",", hidden))
                .apply();
    }

    static void reset(final Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove(PREF_ORDER)
                .remove(PREF_HIDDEN)
                .apply();
    }

    // conditional buttons stay orderable even where they are not shown
    static boolean availableHere(final Context context, final String key) {
        switch (key) {
            case ROTATE:
                return !Utils.isTvBox(context);
            case PIP:
                return Utils.isPiPSupported(context);
            case REPEAT:
                return PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("repeatToggle", false);
            default:
                return true;
        }
    }

    @Nullable
    static Integer unavailableReason(final Context context, final String key) {
        if (availableHere(context, key)) {
            return null;
        }
        switch (key) {
            case ROTATE:
                return R.string.controls_unavailable_tv;
            case PIP:
                return R.string.controls_unavailable_device;
            default:
                return R.string.controls_unavailable_off;
        }
    }

    static int label(final String key) {
        switch (key) {
            case OPEN:     return R.string.controls_name_open;
            case SUBTITLE: return R.string.controls_name_subtitle;
            case AUDIO:    return R.string.controls_name_audio;
            case ASPECT:   return R.string.controls_name_aspect;
            case SETTINGS: return R.string.controls_name_settings;
            case LOCK:     return R.string.controls_name_lock;
            case ROTATE:   return R.string.controls_name_rotate;
            case PIP:      return R.string.controls_name_pip;
            default:       return R.string.controls_name_repeat;
        }
    }

}
