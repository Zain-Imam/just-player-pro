package com.brouken.player;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper;

// Keeps the network on while playing in the background: from Android 15 a background app loses it
// within seconds. The notification uses the player's own media session, so both engines get controls.
public class PlaybackService extends Service {

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_PLAY = "com.brouken.player.action.PLAY";
    private static final String ACTION_PAUSE = "com.brouken.player.action.PAUSE";

    @Nullable
    private static MediaSession session;
    // the player's task, for the notification's tap
    static int playerTask = -1;
    // asked for and not yet called off; read when the service gets going
    private static boolean wanted;
    @Nullable
    private static PlaybackService instance;

    private boolean inForeground;
    @Nullable
    private Player watched;

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onEvents(final Player player, final Player.Events events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED)) {
                show();
            }
        }
    };

    // as the player goes out of sight; within seconds of that Android still allows it
    static void start(final Context context, @Nullable final MediaSession current, final int task) {
        session = current;
        playerTask = task;
        wanted = true;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException: plays on as before, without the notification
            wanted = false;
            Utils.log("Playback service not allowed: " + e);
        }
    }

    // stopped before it reached the foreground, Android would end the app; it stops itself then
    static void stop(final Context context) {
        wanted = false;
        if (instance != null && instance.inForeground) {
            context.stopService(new Intent(context, PlaybackService.class));
        }
    }

    // a stream opened again in the background comes with a new session
    static void sessionChanged(@Nullable final MediaSession current) {
        session = current;
        if (instance != null && instance.inForeground) {
            instance.show();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(androidx.media3.session.R.string.default_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        final Player player = player();
        if (intent != null && player != null) {
            if (ACTION_PLAY.equals(intent.getAction())) {
                player.play();
            } else if (ACTION_PAUSE.equals(intent.getAction())) {
                player.pause();
            }
        }
        // every start must reach startForeground, or Android ends the app
        show();
        if (!wanted) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Nullable
    private static Player player() {
        return session == null ? null : session.getPlayer();
    }

    // media notifications need no notification permission
    @SuppressLint("MissingPermission")
    private void show() {
        final Player player = player();
        if (player != watched) {
            if (watched != null) {
                watched.removeListener(listener);
            }
            watched = player;
            if (player != null) {
                player.addListener(listener);
            }
        }
        final Notification notification = build(player);
        if (!inForeground) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
            inForeground = true;
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification build(@Nullable final Player player) {
        final boolean playing = player != null && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED;
        final MediaMetadata shown = player == null ? MediaMetadata.EMPTY : player.getMediaMetadata();
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.notification_accent))
                .setContentTitle(shown.title != null ? shown.title : getString(R.string.app_name))
                .setContentText(shown.artist)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, ShowPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(playing)
                .setShowWhen(false)
                .setSilent(true);
        // below Android 13 the notification's own button; from 13 the system draws the session's
        builder.addAction(playing
                ? new NotificationCompat.Action(androidx.media3.session.R.drawable.media3_icon_pause,
                        getString(androidx.media3.session.R.string.media3_controls_pause_description),
                        command(ACTION_PAUSE))
                : new NotificationCompat.Action(androidx.media3.session.R.drawable.media3_icon_play,
                        getString(androidx.media3.session.R.string.media3_controls_play_description),
                        command(ACTION_PLAY)));
        if (shown.artworkData != null) {
            builder.setLargeIcon(BitmapFactory.decodeByteArray(shown.artworkData, 0, shown.artworkData.length));
        }
        if (session != null) {
            builder.setStyle(new MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0));
        }
        return builder.build();
    }

    private PendingIntent command(final String action) {
        final Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        if (watched != null) {
            watched.removeListener(listener);
            watched = null;
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        inForeground = false;
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
