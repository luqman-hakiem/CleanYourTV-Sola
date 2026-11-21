package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen view of the Reception camera, opened with OK on the Home card. It prefers a decoded
 * frame of the cloud main stream (960x1080 here), falling back to the lightweight 352x288 snapshot
 * when the stream is unavailable. OK or BACK returns to Home.
 */
public class CctvFullscreenActivity extends Activity {
    private static final long REFRESH_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ImageView image;
    private TextView status;
    private Bitmap current;
    private boolean destroyed;
    private boolean live;
    private volatile long lastLive;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cctv_fullscreen);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        image = findViewById(R.id.cctvFullImage);
        status = findViewById(R.id.cctvFullStatus);
        tick();
    }

    private void tick() {
        if (destroyed) return;
        boolean wantLive = System.currentTimeMillis() - lastLive > 15000L;
        io.execute(() -> {
            Bitmap fresh = null;
            if (wantLive) {
                fresh = HikCctv.get(CctvFullscreenActivity.this).captureLive();
                if (fresh != null) lastLive = System.currentTimeMillis();
            }
            if (fresh == null) fresh = CctvWidget.fetchSnapshot(CctvFullscreenActivity.this);
            final Bitmap next = fresh;
            handler.post(() -> apply(next));
        });
        handler.postDelayed(this::tick, REFRESH_MS);
    }

    private void apply(Bitmap fresh) {
        if (destroyed) {
            if (fresh != null) fresh.recycle();
            return;
        }
        if (fresh != null) {
            Bitmap old = current;
            current = fresh;
            image.setImageBitmap(fresh);
            if (old != null) old.recycle();
            if (!live && status != null) {
                live = true;
                status.setText(R.string.cctv_live);
                status.setTextColor(0xFF8BFF8B);
            }
        } else if (live && status != null) {
            live = false;
            status.setText(R.string.cctv_waiting);
            status.setTextColor(0xFFFF8A8A);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (current != null) {
            current.recycle();
            current = null;
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
