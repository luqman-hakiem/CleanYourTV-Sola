package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * Puts the TV to sleep when the auto-off timer ends. The "lock screen" global action (Android 9 and later)
 * sends the device to sleep, which an ordinary app cannot do otherwise. The service asks for no events and
 * cannot read the screen; {@link SleepTimer#ensureService} puts it on the enabled accessibility list.
 */
public class SleepService extends AccessibilityService {

    private static SleepService running;

    static boolean running() {
        return running != null;
    }

    /** Puts the TV to sleep; false when the service is not bound (or the TV is older than Android 9). */
    static boolean sleep() {
        SleepService service = running;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
