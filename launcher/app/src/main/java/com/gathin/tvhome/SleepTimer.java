package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * Auto-off timer (the Timer pill next to Settings). The owner picks a length; two exact alarms are set: one a
 * minute early, which shows a banner over whatever is playing, and the sleep alarm itself. An ordinary app
 * cannot power the TV off, so at the end {@link SleepService} performs the "lock screen" global action, which
 * on Android 9 and later puts the device to sleep.
 *
 * The timer survives Home being restarted or the TV rebooting: the end time is in shared preferences and
 * {@link AdzanReceiver} schedules the alarms again after a boot or an update.
 */
final class SleepTimer {

    /** The lengths the menu offers, in minutes. */
    static final int[] MINUTES = {15, 30, 45, 60, 90, 120};

    static final String ACTION_WARN = "com.gathin.tvhome.action.SLEEP_TIMER_WARN";
    static final String ACTION_SLEEP = "com.gathin.tvhome.action.SLEEP_TIMER_SLEEP";
    /** From adb only (the receiver is not exported), to try the whole path with a few seconds. */
    static final String ACTION_TEST = "com.gathin.tvhome.action.SLEEP_TIMER_TEST";

    /** The banner comes up this long before the TV goes to sleep. */
    static final long WARN_LEAD_MS = 60_000L;

    /** When the sleep action is refused (the helper has not bound yet), the alarm tries again this often. */
    private static final long RETRY_MS = 15_000L;
    /** Give up after this many tries (~6 minutes): a helper that never binds will not start now. */
    private static final int MAX_TRIES = 24;
    private static final String TAG = "SleepTimer";

    private static final String PREFS = "sleep_timer";
    private static final String KEY_END = "end";
    private static final String KEY_TRIES = "tries";
    private static final int REQUEST_WARN = 10;
    private static final int REQUEST_SLEEP = 11;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WindowManager bannerWindow;
    private static View banner;

    private SleepTimer() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** When the TV goes to sleep, in wall-clock millis, or 0 when no timer is set. */
    static long end(Context context) {
        return prefs(context).getLong(KEY_END, 0);
    }

    /** True while the timer is still running. */
    static boolean active(Context context) {
        return end(context) > System.currentTimeMillis();
    }

    /** Whole minutes left, rounded up, for the menu and the pill. */
    static int minutesLeft(Context context) {
        long left = end(context) - System.currentTimeMillis();
        return (int) Math.max(1, (left + 59_999) / 60_000);
    }

    /** "45 min", "1 h", "1 h 30 min": the same wording in the menu and in the toast. */
    static String durationText(Context context, int minutes) {
        if (minutes < 60) return context.getString(R.string.sleep_timer_minutes, minutes);
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0
                ? context.getString(R.string.sleep_timer_hours, hours)
                : context.getString(R.string.sleep_timer_hours_minutes, hours, rest);
    }

    static void start(Context context, int minutes) {
        startSeconds(context, minutes * 60);
    }

    static void startSeconds(Context context, int seconds) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        long end = now + seconds * 1000L;
        prefs(app).edit().putLong(KEY_END, end).remove(KEY_TRIES).apply();
        // A short test timer must still be able to show its warning before the end.
        long warn = Math.max(now + Math.min(1_000, seconds * 500L), end - WARN_LEAD_MS);
        schedule(app, ACTION_WARN, warn);
        schedule(app, ACTION_SLEEP, end + 1_500);
        ensureService(app);
        // One line per timer, so a report of "the timer did nothing" can be checked against the TV's log.
        android.util.Log.i(TAG, "timer set for " + seconds + "s, ends " + new Date(end)
                + ", helper bound=" + SleepService.running());
        String at = DateFormat.getTimeFormat(app).format(new Date(end));
        Toast.makeText(app, app.getString(R.string.sleep_timer_started, at), Toast.LENGTH_LONG).show();
    }

    static void cancel(Context context) {
        Context app = context.getApplicationContext();
        prefs(app).edit().remove(KEY_END).remove(KEY_TRIES).apply();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(pending(app, ACTION_WARN, REQUEST_WARN));
            am.cancel(pending(app, ACTION_SLEEP, REQUEST_SLEEP));
        }
        removeBanner();
        Toast.makeText(app, R.string.sleep_timer_cancelled, Toast.LENGTH_SHORT).show();
    }

    /** The warning alarm: a banner over whatever is on screen, a minute before the end. */
    static void warn(Context context) {
        long end = end(context);
        if (end == 0) return;                                   // cancelled
        if (System.currentTimeMillis() > end + 5_000) return;    // stale
        android.util.Log.i(TAG, "one-minute banner at " + new Date());
        showBanner(context.getApplicationContext());
    }

    /** The sleep alarm: the timer ends, so put the TV to sleep. */
    static void fired(Context context) {
        Context app = context.getApplicationContext();
        long end = end(app);
        if (end == 0) return;                                    // cancelled since the alarm was set
        if (System.currentTimeMillis() < end - 5_000) return;    // replaced by a later timer
        removeBanner();
        if (SleepService.sleep()) {
            android.util.Log.i(TAG, "TV asleep, timer cleared");
            prefs(app).edit().remove(KEY_END).remove(KEY_TRIES).apply();
            return;
        }
        // The helper is not bound (the process was just started, or Android rebound it late): ask for it again and
        // try from a fresh alarm, so a Home process that dies in the meantime cannot swallow the timer.
        ensureService(app);
        int tries = prefs(app).getInt(KEY_TRIES, 0) + 1;
        if (tries > MAX_TRIES) {
            android.util.Log.w(TAG, "giving up after " + MAX_TRIES + " tries: the accessibility helper never bound");
            prefs(app).edit().remove(KEY_END).remove(KEY_TRIES).apply();
            Toast.makeText(app, R.string.sleep_timer_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        prefs(app).edit().putInt(KEY_TRIES, tries).apply();
        android.util.Log.i(TAG, "sleep action refused, alarm retry " + tries + " in " + (RETRY_MS / 1000) + "s");
        schedule(app, ACTION_SLEEP, System.currentTimeMillis() + RETRY_MS);
    }

    /** Re-arms a running timer after a reboot or an update (AdzanReceiver). */
    static void afterBoot(Context context) {
        Context app = context.getApplicationContext();
        long end = end(app);
        if (end == 0) return;
        ensureService(app);
        long now = System.currentTimeMillis();
        if (end <= now) {
            MAIN.postDelayed(() -> fired(app), 2_000);
            return;
        }
        schedule(app, ACTION_WARN, Math.max(now + 1_000, end - WARN_LEAD_MS));
        schedule(app, ACTION_SLEEP, end + 1_500);
    }

    private static void schedule(Context context, String action, long at) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        int request = ACTION_WARN.equals(action) ? REQUEST_WARN : REQUEST_SLEEP;
        PendingIntent pi = pending(context, action, request);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context context, String action, int request) {
        Intent intent = new Intent(context, SleepTimerReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Puts this launcher's sleep helper on the enabled accessibility list, so the timer works the first time
     * without a trip to the TV's settings. The launcher holds WRITE_SECURE_SETTINGS (granted at install); when
     * a firmware keeps it from writing, the timer still runs and only the last action would be missing.
     */
    static boolean ensureService(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        if (SleepService.running()) return true;
        try {
            ContentResolver cr = context.getContentResolver();
            ComponentName helper = new ComponentName(context, SleepService.class);
            String flat = helper.flattenToString();              // package/full.class.Name
            String shortFlat = helper.flattenToShortString();    // package/.Class
            String current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean present = false;
            if (!TextUtils.isEmpty(current)) {
                for (String entry : current.split(":")) {
                    if (entry.equalsIgnoreCase(flat) || entry.equalsIgnoreCase(shortFlat)) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                String next = TextUtils.isEmpty(current) ? flat : current + ":" + flat;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (SecurityException | IllegalArgumentException denied) {
            return false;
        }
    }

    // ---- the warning banner ----------------------------------------------------------------------

    private static void showBanner(Context context) {
        MAIN.post(() -> {
            try {
                removeBanner();
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, R.string.sleep_timer_warning, Toast.LENGTH_LONG).show();
                    return;
                }
                LinearLayout panel = new LinearLayout(context);
                panel.setOrientation(LinearLayout.VERTICAL);
                int padX = dp(context, 20);
                int padY = dp(context, 14);
                panel.setPadding(padX, padY, padX, padY);
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xF21C1C1E);
                background.setCornerRadius(dp(context, 18));
                background.setStroke(Math.max(1, dp(context, 1)), 0x33FFFFFF);
                panel.setBackground(background);

                TextView title = new TextView(context);
                title.setText(R.string.sleep_timer_warning);
                title.setTextColor(Color.WHITE);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                panel.addView(title);

                TextView hint = new TextView(context);
                hint.setText(R.string.sleep_timer_warning_hint);
                hint.setTextColor(0xB3FFFFFF);
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                hint.setPadding(0, dp(context, 4), 0, 0);
                panel.addView(hint);

                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = dp(context, 40);
                lp.y = dp(context, 32);
                wm.addView(panel, lp);
                bannerWindow = wm;
                banner = panel;
                MAIN.postDelayed(SleepTimer::removeBanner, 15_000);
            } catch (RuntimeException ignored) {
                // An overlay the firmware will not take is not worth crashing Home for.
            }
        });
    }

    private static void removeBanner() {
        View view = banner;
        WindowManager wm = bannerWindow;
        banner = null;
        bannerWindow = null;
        if (view == null || wm == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException alreadyGone) {
            // the window was never attached, or the system took it away
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** The pill's own label: "Timer" when off, "Timer · 23 min" while a timer runs. */
    static String pillLabel(Context context) {
        if (!active(context)) return context.getString(R.string.sleep_timer);
        return context.getString(R.string.sleep_timer_pill, durationText(context, minutesLeft(context)));
    }
}
