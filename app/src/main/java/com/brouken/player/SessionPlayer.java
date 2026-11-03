package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.FlagSet;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// The engine's own metadata plus the film's name, episode or year and a picture, so the notification
// and lock screen look like other apps'. Both engines' sessions read it from here.
final class SessionPlayer extends ForwardingPlayer {

    private MediaMetadata details = MediaMetadata.EMPTY;
    private final List<Player.Listener> listeners = new CopyOnWriteArrayList<>();

    SessionPlayer(final Player player) {
        super(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable final MediaItem mediaItem, final int reason) {
                // the next video in a list brings its own
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    details = MediaMetadata.EMPTY;
                }
            }
        });
    }

    void setDetails(@NonNull final MediaMetadata details) {
        this.details = details;
        final MediaMetadata shown = getMediaMetadata();
        final Player.Events events = new Player.Events(
                new FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build());
        for (final Player.Listener listener : listeners) {
            listener.onMediaMetadataChanged(shown);
            listener.onEvents(this, events);
        }
    }

    @NonNull
    @Override
    public MediaMetadata getMediaMetadata() {
        final MediaMetadata own = super.getMediaMetadata();
        return details == MediaMetadata.EMPTY ? own : own.buildUpon().populate(details).build();
    }

    @Override
    public void addListener(@NonNull final Player.Listener listener) {
        super.addListener(listener);
        listeners.add(listener);
    }

    @Override
    public void removeListener(@NonNull final Player.Listener listener) {
        super.removeListener(listener);
        listeners.remove(listener);
    }
}
