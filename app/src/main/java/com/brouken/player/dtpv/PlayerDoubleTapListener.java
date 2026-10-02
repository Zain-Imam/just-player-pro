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

public interface PlayerDoubleTapListener {

    default void onDoubleTapStarted(float posX, float posY) { }

    // the progress calls fire for every tap while double-tap mode lasts
    default void onDoubleTapProgressDown(float posX, float posY) { }

    default void onDoubleTapProgressUp(float posX, float posY) { }

    default void onDoubleTapFinished() { }
}