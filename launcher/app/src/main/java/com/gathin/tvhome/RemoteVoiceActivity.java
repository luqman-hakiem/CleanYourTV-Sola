package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;

/**
 * The voice key on the AX810 remote (SDMC firmware). The remote sends key code 203, which the
 * firmware's GlobalKey receiver turns into {@code com.sdmc.bluetoothconnect.START_BTSETUP}; no app
 * on this set handles that action, so the mic button did nothing. This activity answers it and opens
 * the Assistant (Katniss), the Google voice search already installed on the TV. On other sets nothing
 * sends the action, so the activity is never started.
 */
public class RemoteVoiceActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        } finally {
            finish();
        }
    }
}
