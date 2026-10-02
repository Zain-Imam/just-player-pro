package com.brouken.player.engine;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import com.brouken.player.mpv.MpvPlayer;

// Every seek sets its precision right before seeking; the setting is sticky.
public final class SeekPrecision {

    private SeekPrecision() {
    }

    public static void exact(@Nullable final Player player) {
        apply(player, SeekParameters.EXACT);
    }

    public static void apply(@Nullable final Player player, final SeekParameters parameters) {
        if (player instanceof ExoPlayer) {
            ((ExoPlayer) player).setSeekParameters(parameters);
        } else if (player instanceof MpvPlayer) {
            final boolean exact = SeekParameters.EXACT.equals(parameters)
                    || SeekParameters.DEFAULT.equals(parameters);
            ((MpvPlayer) player).setKeyframeSeeking(!exact);
        }
    }
}
