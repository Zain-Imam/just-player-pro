package com.brouken.player;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.LocaleManagerCompat;
import androidx.preference.PreferenceManager;

final class PlayerCorner {

    static final String CLOCK = "clockMode";
    static final String BATTERY = "batteryMode";
    static final String OFF = "off";
    static final String WITH_CONTROLS = "controls";
    static final String ALWAYS = "always";
    // the legacy single clock switch, where on means always
    private static final String OLD_CLOCK = "alwaysOnClock";
    static final String TIME_FORMAT = "clockFormat";
    static final String FORMAT_PHONE = "phone";
    static final String FORMAT_12 = "12";
    static final String FORMAT_24 = "24";
    private static final int FADE_MS = 160;
    private static final int TICK_MS = 20_000;

    private final Activity activity;
    private final LinearLayout row;
    private final TextView clock;
    private final ImageView batteryIcon;
    private final TextView batteryText;
    private final BatteryIcon icon = new BatteryIcon();
    private String clockMode = OFF;
    private java.text.DateFormat timeFormat;
    private String batteryMode = OFF;
    private boolean controlsShown;
    private boolean inPip;
    private boolean started;
    private boolean listening;
    @Nullable
    private Runnable onShownWithControls;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(timeFormat.format(new java.util.Date()));
            describe();
            clock.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver batteryChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            showBattery(intent);
        }
    };

    PlayerCorner(@NonNull final Activity activity) {
        this.activity = activity;
        timeFormat = timeFormat(activity, FORMAT_PHONE);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.GONE);
        // read aloud as one: "81%, 9:41 PM"
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        batteryIcon = new ImageView(activity);
        batteryIcon.setImageDrawable(icon);
        batteryIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(batteryIcon);

        batteryText = text(activity);
        final LinearLayout.LayoutParams percent = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        percent.setMarginStart(Utils.dpToPx(4));
        row.addView(batteryText, percent);

        clock = text(activity);
        final LinearLayout.LayoutParams time = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        time.setMarginStart(Utils.dpToPx(12));
        row.addView(clock, time);
    }

    private static TextView text(final Context context) {
        final TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        shadow(view);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    // heavy: shown always, it sits on bright skies as well as dark scenes
    private static void shadow(final TextView view) {
        view.setShadowLayer(Utils.dpToPx(3), 0, Utils.dpToPx(1), 0xD9000000);
    }

    View view() {
        return row;
    }

    void dress(@Nullable final PlayerStyle style) {
        final TextView probe = text(activity);
        if (style != null) {
            style.dressClock(probe);
        }
        final int colour = probe.getCurrentTextColor();
        for (final TextView view : new TextView[]{clock, batteryText}) {
            view.setTextColor(colour);
            shadow(view);
        }
        icon.setColour(colour);
    }

    // called once laid out with the controls up, to keep the title clear of it
    void setOnShownWithControls(@Nullable final Runnable listener) {
        onShownWithControls = listener;
    }

    static void migrate(@NonNull final SharedPreferences prefs) {
        if (!prefs.contains(CLOCK) && prefs.contains(OLD_CLOCK)) {
            prefs.edit()
                    .putString(CLOCK, prefs.getBoolean(OLD_CLOCK, false) ? ALWAYS : OFF)
                    .remove(OLD_CLOCK)
                    .apply();
        }
    }

    private static String mode(final SharedPreferences prefs, final String key) {
        final String mode = prefs.getString(key, OFF);
        return WITH_CONTROLS.equals(mode) || ALWAYS.equals(mode) ? mode : OFF;
    }

    static boolean hasBattery(@NonNull final Context context) {
        if (Utils.isTvBox(context)) {
            return false;
        }
        final Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return sticky != null && sticky.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
    }

    void refresh() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        migrate(prefs);
        clockMode = mode(prefs, CLOCK);
        timeFormat = timeFormat(activity, prefs.getString(TIME_FORMAT, FORMAT_PHONE));
        batteryMode = hasBattery(activity) ? mode(prefs, BATTERY) : OFF;
        listen();
        show(false);
    }

    static java.text.DateFormat timeFormat(final Context context, @Nullable final String format) {
        final boolean hours24 = FORMAT_24.equals(format) || (!FORMAT_12.equals(format) && phoneUses24(context));
        final java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(
                locale, hours24 ? "Hm" : "hm"), locale);
    }

    // not is24HourFormat or Resources.getSystem(): both follow the per-app language
    private static boolean phoneUses24(final Context context) {
        final String set = Settings.System.getString(context.getContentResolver(), Settings.System.TIME_12_24);
        if (set != null) {
            return "24".equals(set);
        }
        final java.util.Locale phone = LocaleManagerCompat.getSystemLocales(context).get(0);
        final String pattern = android.text.format.DateFormat.getBestDateTimePattern(
                phone != null ? phone : java.util.Locale.getDefault(), "jm");
        return pattern.indexOf('H') >= 0 || pattern.indexOf('k') >= 0;
    }

    void onStart() {
        started = true;
        listen();
    }

    void onStop() {
        started = false;
        listen();
    }

    void setControlsShown(final boolean shown) {
        controlsShown = shown;
        show(true);
    }

    void setInPip(final boolean pip) {
        inPip = pip;
        show(false);
    }

    private void listen() {
        clock.removeCallbacks(tick);
        if (started && !OFF.equals(clockMode)) {
            tick.run();
        }
        final boolean wanted = started && !OFF.equals(batteryMode);
        if (wanted && !listening) {
            // sticky: the answer comes at once, then each change
            showBattery(activity.registerReceiver(batteryChanged, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            listening = true;
        } else if (!wanted && listening) {
            try {
                activity.unregisterReceiver(batteryChanged);
            } catch (IllegalArgumentException ignored) {
                // already gone with the activity
            }
            listening = false;
        }
    }

    private void showBattery(@Nullable final Intent intent) {
        if (intent == null) {
            return;
        }
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            return;
        }
        final int percent = Math.round(level * 100f / scale);
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        icon.setLevel(percent, charging);
        batteryText.setText(percent + "%");
        describe();
    }

    private void describe() {
        final StringBuilder said = new StringBuilder();
        if (batteryText.getVisibility() == View.VISIBLE) {
            said.append(batteryText.getText());
        }
        if (clock.getVisibility() == View.VISIBLE) {
            if (said.length() > 0) {
                said.append(", ");
            }
            said.append(clock.getText());
        }
        row.setContentDescription(said);
    }

    private boolean shows(final String mode) {
        return !inPip && (ALWAYS.equals(mode) || (WITH_CONTROLS.equals(mode) && controlsShown));
    }

    private void show(final boolean fade) {
        final boolean showClock = shows(clockMode);
        final boolean showBattery = shows(batteryMode);
        clock.setVisibility(showClock ? View.VISIBLE : View.GONE);
        batteryIcon.setVisibility(showBattery ? View.VISIBLE : View.GONE);
        batteryText.setVisibility(showBattery ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) clock.getLayoutParams()).setMarginStart(showBattery ? Utils.dpToPx(12) : 0);
        describe();
        final boolean any = showClock || showBattery;
        row.animate().cancel();
        if (any) {
            if (row.getVisibility() != View.VISIBLE) {
                row.setAlpha(fade ? 0f : 1f);
                row.setVisibility(View.VISIBLE);
            }
            row.animate().alpha(1f).setDuration(fade ? FADE_MS : 0).start();
        } else if (row.getVisibility() == View.VISIBLE) {
            row.animate().alpha(0f).setDuration(fade ? FADE_MS : 0)
                    .withEndAction(() -> row.setVisibility(View.GONE)).start();
        }
        if (controlsShown && onShownWithControls != null) {
            row.post(onShownWithControls);
        }
    }

    int widthWithControls() {
        return row.getVisibility() == View.VISIBLE ? row.getWidth() : 0;
    }

    private static final class BatteryIcon extends Drawable {
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF body = new RectF();
        private final RectF level = new RectF();
        private final Path boltPath = new Path();
        private int percent = 100;
        private boolean charging;
        private int colour = Color.WHITE;
        private final int width = Utils.dpToPx(20);
        private final int height = Utils.dpToPx(11);

        BatteryIcon() {
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(Utils.dpToPx(1) * 1.2f);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(outline.getStrokeWidth() + Utils.dpToPx(2));
            halo.setColor(0x73000000);
            halo.setStrokeJoin(Paint.Join.ROUND);
            bolt.setColor(0xFFFFD54A);
            setColour(Color.WHITE);
        }

        void setColour(final int colour) {
            this.colour = colour;
            outline.setColor(colour);
            invalidateSelf();
        }

        void setLevel(final int percent, final boolean charging) {
            this.percent = Math.max(0, Math.min(100, percent));
            this.charging = charging;
            invalidateSelf();
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void draw(@NonNull final Canvas canvas) {
            final float w = getBounds().width();
            final float h = getBounds().height();
            final float stroke = outline.getStrokeWidth();
            final float cap = h * 0.16f;
            body.set(getBounds().left + stroke / 2, getBounds().top + stroke / 2,
                    getBounds().left + w - cap - stroke / 2, getBounds().top + h - stroke / 2);
            final float corner = h * 0.22f;
            canvas.drawRoundRect(body, corner, corner, halo);
            canvas.drawRect(body.right + stroke / 2, getBounds().top + h * 0.32f,
                    body.right + stroke / 2 + cap, getBounds().top + h * 0.68f, halo);
            canvas.drawRoundRect(body, corner, corner, outline);
            fill.setColor(colour);
            canvas.drawRect(body.right + stroke / 2, getBounds().top + h * 0.32f,
                    body.right + stroke / 2 + cap, getBounds().top + h * 0.68f, fill);
            final float inset = stroke * 1.6f;
            level.set(body.left + inset, body.top + inset,
                    body.left + inset + (body.width() - 2 * inset) * percent / 100f, body.bottom - inset);
            fill.setColor(percent <= 15 && !charging ? 0xFFFF5252 : colour);
            canvas.drawRoundRect(level, corner / 2, corner / 2, fill);
            if (charging) {
                final float cx = body.centerX();
                final float top = getBounds().top - h * 0.05f;
                boltPath.reset();
                boltPath.moveTo(cx + h * 0.12f, top);
                boltPath.lineTo(cx - h * 0.3f, top + h * 0.6f);
                boltPath.lineTo(cx - h * 0.02f, top + h * 0.6f);
                boltPath.lineTo(cx - h * 0.12f, top + h * 1.1f);
                boltPath.lineTo(cx + h * 0.3f, top + h * 0.48f);
                boltPath.lineTo(cx + h * 0.02f, top + h * 0.48f);
                boltPath.close();
                canvas.drawPath(boltPath, bolt);
            }
        }

        @Override
        public void setAlpha(final int alpha) {
            halo.setAlpha(alpha * 0x73 / 0xFF);
            outline.setAlpha(alpha);
            fill.setAlpha(alpha);
            bolt.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable final ColorFilter filter) {
            outline.setColorFilter(filter);
            fill.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
