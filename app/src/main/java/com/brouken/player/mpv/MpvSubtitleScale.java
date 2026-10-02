package com.brouken.player.mpv;

// Kept apart from MpvPlayer so settings can use it without starting mpv.
public final class MpvSubtitleScale {

    // Media3's default text size, as a fraction of the view height
    static final double SUB_SIZE_DEFAULT = 0.0533;
    // one step of the size setting, as a fraction of the height
    static final double SUB_SIZE_STEP = 0.001;

    // libass sizes by full font height, Media3 by em; measured line widths 248px vs 205px
    static final double SUB_GLYPH_MATCH = 248.0 / 205.0;

    // mpv's default sub-font-size, relative to a 720px tall window
    static final double DEFAULT_FONT_SIZE = 38.0;

    // old scale, based on mpv's former default sub-font-size of 55
    private static final double PARITY_4_1 = SUB_SIZE_DEFAULT / (55.0 / 720.0);

    private MpvSubtitleScale() {
    }

    // sub-scale that makes mpv text at fontSize match Media3's size
    static double parity(final double fontSize) {
        return SUB_SIZE_DEFAULT / (fontSize / 720.0) * SUB_GLYPH_MATCH;
    }

    static double scale(final int step, final double parity) {
        return Math.max(0.2, (1.0 + step * (SUB_SIZE_STEP / SUB_SIZE_DEFAULT)) * parity);
    }

    /** Converts a size step saved under the old scale to one that draws the same size now. */
    public static int stepDrawnLike41(final int step41) {
        final double perStep = SUB_SIZE_STEP / SUB_SIZE_DEFAULT;
        final double drawn = (1.0 + step41 * perStep) * PARITY_4_1;
        return (int) Math.round((drawn / parity(DEFAULT_FONT_SIZE) - 1.0) / perStep);
    }
}
