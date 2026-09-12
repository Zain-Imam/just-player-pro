package com.brouken.player.subtitle;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.FontStyle;
import android.os.Build;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.DisplayMetrics;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.media3.common.text.Cue;

import com.brouken.player.osd.subtitle.SubtitleEdgeType;
import com.brouken.player.osd.subtitle.SubtitleTypeface;

import java.util.ArrayList;
import java.util.List;

public class CueModifier {

    private final ShadowSpan shadowSpan;

    private SubtitleTypeface subtitleTypeface;
    private Typeface italicTypeface;
    private SubtitleEdgeType subtitleEdgeType;

    /*
     * Where the line of text goes, and how far it is allowed to go.
     *
     * Two problems, one answer. The position slider did nothing on this engine
     * for any subtitle carrying its own placement — which is every ASS file and
     * a good many converted SRTs — because the bottom-padding fraction the view
     * offers is consulted only for cues that name no position of their own. And
     * the view covers the whole player, so what did move could be moved down
     * into the letterbox, where it is drawn on black and reads as lost.
     *
     * Every cue that belongs at the bottom is given an explicit line instead,
     * measured inside the picture rather than inside the screen. A cue placed
     * near the top is left alone: that is a sign or a caption over the scene,
     * not dialogue, and dragging it to the floor would be wrong.
     */
    private float bottomFraction = 0.08f;
    private float pictureTop;
    private float pictureBottom = 1f;

    /** The slider value, in the same units and the same sense mpv is given. */
    public void setVerticalPosition(final int position) {
        bottomFraction = Math.max(0f, Math.min(0.9f, 0.08f + position * 0.01f));
    }

    /** Top and bottom of the picture, as fractions of the subtitle view. */
    public void setPictureArea(final float top, final float bottom) {
        pictureTop = Math.max(0f, Math.min(1f, top));
        pictureBottom = Math.max(pictureTop, Math.min(1f, bottom));
    }

    public CueModifier(Context context) {
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        int oneDpInPx = Math.round((1f * displayMetrics.densityDpi) / DisplayMetrics.DENSITY_DEFAULT);
        shadowSpan = new ShadowSpan(oneDpInPx, oneDpInPx);
    }

    @SuppressLint("InlinedApi")
    public void setSubtitleTypeface(SubtitleTypeface subtitleTypeface, Typeface typeface) {
        this.subtitleTypeface = subtitleTypeface;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            this.italicTypeface = Typeface.create(typeface, FontStyle.FONT_WEIGHT_MEDIUM, true);
        }
    }

    public SubtitleTypeface getSubtitleTypeface() {
        return subtitleTypeface;
    }

    public void setSubtitleEdgeType(SubtitleEdgeType edgeType) {
        this.subtitleEdgeType = edgeType;
    }

    public SubtitleEdgeType getSubtitleEdgeType() {
        return subtitleEdgeType;
    }

    public void setShadowColor(@ColorInt int shadowColor) {
        shadowSpan.setShadowColor(shadowColor);
    }

    @ColorInt
    public int getShadowColor() {
        return shadowSpan.getShadowColor();
    }

    public List<Cue> modifyCues(@NonNull List<Cue> cues) {
        List<Cue> modifiedCues = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            modifiedCues.add(modifyCue(cues.get(i)));
        }
        return modifiedCues;
    }

    // TODO(Wasky) Create documentation for this method
    private Cue modifyCue(Cue cue) {
        SpannableString spannableString;
        if (cue.text == null) {
            return cue;
        } else if (cue.text instanceof SpannableString) {
            spannableString = (SpannableString) cue.text;
        } else {
            spannableString = SpannableString.valueOf(cue.text);
        }

        addTextPaintModifier(spannableString);
        modifyItalicTypeface(spannableString);
        addShadow(spannableString);

        final Cue.Builder builder = cue.buildUpon().setText(spannableString);
        placeAtBottom(cue, builder);
        return builder.build();
    }

    private void placeAtBottom(final Cue cue, final Cue.Builder builder) {
        final float height = pictureBottom - pictureTop;
        if (height <= 0f) {
            return;
        }

        // Already placed in the top half: a sign over the scene, and it belongs
        // where the file put it.
        if (cue.line != Cue.DIMEN_UNSET
                && (cue.lineType != Cue.LINE_TYPE_FRACTION || cue.line < 0.5f)) {
            return;
        }

        final float line = pictureBottom - bottomFraction * height;
        builder.setLine(Math.max(pictureTop, Math.min(pictureBottom, line)),
                        Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_END);
        if (cue.position == Cue.DIMEN_UNSET) {
            builder.setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE);
        }
    }

    private boolean addTextPaintModifier(SpannableString spannableString) {
        spannableString.setSpan(new TextPaintModifierSpan(), 0, spannableString.length(), 0);
        return true;
    }

    private boolean modifyItalicTypeface(SpannableString spannableString) {
        boolean modified = false;
        if (subtitleTypeface == SubtitleTypeface.Medium && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StyleSpan[] styleSpans = spannableString.getSpans(0, spannableString.length(), StyleSpan.class);
            for (StyleSpan span : styleSpans) {
                if (span.getStyle() == Typeface.ITALIC) {
                    int start = spannableString.getSpanStart(span);
                    int end = spannableString.getSpanEnd(span);
                    int flags = spannableString.getSpanFlags(span);
                    spannableString.removeSpan(span);
                    TypefaceSpan newSpan = new TypefaceSpan(italicTypeface);
                    spannableString.setSpan(newSpan, start, end, flags);
                    modified = true;
                }
            }
        }
        return modified;
    }

    private boolean addShadow(SpannableString spannableString) {
        if (subtitleEdgeType == SubtitleEdgeType.OutlineShadow) {
            spannableString.setSpan(shadowSpan, 0, spannableString.length(), 0);
            return true;
        }
        return false;
    }

}
