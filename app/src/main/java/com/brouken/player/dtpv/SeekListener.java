/*
 * Copyright (c) 2019 Viktor Krez
 *
 * Taken from DoubleTapPlayerView, https://github.com/vkay94/DoubleTapPlayerView,
 * and used under the MIT licence. The full licence text is in
 * licenses/MIT-DoubleTapPlayerView.txt, which is distributed with this software.
 *
 * This file has been modified: translated from the original Kotlin into Java and
 * adapted to this player by the Just Player project.
 */
package com.brouken.player.dtpv;

public interface SeekListener {
    void onVideoStartReached();

    void onVideoEndReached();
}
