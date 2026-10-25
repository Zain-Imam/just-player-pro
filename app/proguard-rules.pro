# private fields read by reflection; R8 would rename them in release builds
-keepclassmembers class androidx.media3.ui.DefaultTimeBar {
    private android.graphics.Rect seekBounds;
    private android.graphics.Rect progressBar;
    private android.graphics.Rect bufferedBar;
    private android.graphics.Rect scrubberBar;
}
-keepclassmembers class androidx.media3.ui.PlayerControlView {
    private androidx.media3.ui.TrackNameProvider trackNameProvider;
    private android.graphics.drawable.Drawable playButtonDrawable;
    private android.graphics.drawable.Drawable pauseButtonDrawable;
}

# mpv calls back into the binding from its own threads by name.
-keep class dev.jdtech.mpv.MPVLib { *; }
-keep interface dev.jdtech.mpv.MPVLib$* { *; }

# Settings pages are named in res/xml/settings_headers.xml (app:fragment) and made by name
-keep class com.brouken.player.SettingsActivity$SettingsFragment { public <init>(); }
