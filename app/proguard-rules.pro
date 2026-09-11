# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# The seek bar reads three private rects out of DefaultTimeBar by name, to tell a
# touch on the timeline from one merely near it, and PlayerControlView's track
# name provider is replaced the same way. R8 renames private fields, so without
# these the reflection quietly returns nothing and the release build behaves
# differently from the debug one.
-keepclassmembers class androidx.media3.ui.DefaultTimeBar {
    private android.graphics.Rect seekBounds;
    private android.graphics.Rect progressBar;
    private android.graphics.Rect scrubberBar;
}
-keepclassmembers class androidx.media3.ui.PlayerControlView {
    private androidx.media3.ui.TrackNameProvider trackNameProvider;
}

# mpv calls back into the binding from its own threads by name.
-keep class dev.jdtech.mpv.MPVLib { *; }
-keep interface dev.jdtech.mpv.MPVLib$* { *; }
