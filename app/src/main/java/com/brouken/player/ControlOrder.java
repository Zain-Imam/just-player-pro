package com.brouken.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which buttons sit in the strip at the bottom right, and in what order.
 *
 * <p>Stored as two plain lists of keys rather than indices, so a build that
 * adds a button does not shuffle everybody's row: an unknown key is dropped on
 * the way in, and a button the stored order has never heard of is appended
 * where it would have been by default.
 *
 * <p>Keys never change once shipped. The labels can.
 */
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

    /** Left to right, as the player has always had them. */
    static final String[] DEFAULT = {
            OPEN, SUBTITLE, AUDIO, ASPECT, SETTINGS, LOCK, ROTATE, PIP, REPEAT,
    };

    private static final String PREF_ORDER = "controlOrder";
    private static final String PREF_HIDDEN = "controlHidden";

    private ControlOrder() {
    }

    /**
     * The gear cannot be hidden.
     *
     * <p>It is the way back to this screen. Without it, hiding it would leave a
     * player that cannot be configured again short of clearing the app's data,
     * which is a poor thing to learn afterwards.
     */
    static boolean canHide(final String key) {
        return !SETTINGS.equals(key);
    }

    /** Every key, in the order this device should draw them. */
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
        // Anything the stored order does not mention -- a button added since it
        // was written -- takes its default place rather than being lost.
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

    /**
     * Whether this device shows the button at all, whatever the order says.
     *
     * <p>Three of them are conditional: a television has no reason to rotate,
     * picture-in-picture is not on every device, and the loop button is a
     * setting of its own. They are still listed and still orderable, so a
     * position can be chosen before the thing appears.
     */
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

    /** Why it is not on this device, or null when it is. */
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
