package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The auto-off timer's two alarms (a minute early, the end), plus the adb-only test action. */
public final class SleepTimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (SleepTimer.ACTION_SLEEP.equals(action)) {
            SleepTimer.fired(context);
        } else if (SleepTimer.ACTION_WARN.equals(action)) {
            SleepTimer.warn(context);
        } else if (SleepTimer.ACTION_TEST.equals(action)) {
            SleepTimer.startSeconds(context, intent.getIntExtra("seconds", 70));
        }
    }
}
