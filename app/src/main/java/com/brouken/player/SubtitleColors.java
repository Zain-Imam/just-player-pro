package com.brouken.player;

// keys are the stored subtitleColor values, in the order of its list
public final class SubtitleColors {

    // on Media3 this still follows the system caption colour when one is set
    public static final String DEFAULT = "white";

    private static final String[] KEYS = {
            "white", "cream", "yellow", "amber", "green", "cyan", "sky", "pink"
    };
    private static final int[] RGB = {
            0xFFFFFF, 0xFFF2CC, 0xFFE74C, 0xFFC233, 0x7DF08F, 0x6FE7F2, 0x8EC5FF, 0xFF9FCF
    };

    private SubtitleColors() {
    }

    public static int count() {
        return KEYS.length;
    }

    public static int indexOf(final String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return i;
            }
        }
        return 0;
    }

    public static String keyAt(final int index) {
        return KEYS[index >= 0 && index < KEYS.length ? index : 0];
    }

    public static boolean isDefault(final String key) {
        return indexOf(key) == 0;
    }

    public static int argb(final String key) {
        return 0xFF000000 | RGB[indexOf(key)];
    }

    // mpv's sub-color form
    public static String hex(final String key) {
        return String.format(java.util.Locale.ROOT, "#%06X", RGB[indexOf(key)]);
    }
}
