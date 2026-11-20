package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small Reception camera preview under the football card. The PC bridge publishes one JPEG every two
 * seconds; the launcher only decodes the latest frame, so the Home stays light. OK opens HiLook for the
 * full live view. No new permission: INTERNET is already used by the other Home widgets.
 */
final class CctvWidget {
    private static final String SNAPSHOT = "http://10.10.10.176:8890/reception.jpg";
    private static final String HILOOK = "com.mcu.hilook";
    private static final long REFRESH_MS = 2000L;

    private final Activity activity;
    private final View card;
    private final ImageView preview;
    private final TextView status;
    private final Intent launch;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Bitmap current;
    private boolean destroyed;
    private boolean live;

    /** Null when the widget is absent from this layout. */
    static CctvWidget create(Activity activity) {
        View card = activity.findViewById(R.id.cctvCard);
        if (card == null) return null;
        return new CctvWidget(activity, card);
    }

    private CctvWidget(Activity activity, View card) {
        this.activity = activity;
        this.card = card;
        this.density = activity.getResources().getDisplayMetrics().density;
        preview = card.findViewById(R.id.cctvPreview);
        status = card.findViewById(R.id.cctvStatus);
        if (status != null) status.setShadowLayer(3 * density, 0, 1f * density, 0x99000000);
        launch = activity.getPackageManager().getLaunchIntentForPackage(HILOOK);
        card.setOnClickListener(v -> openHiLook());
        // Same bright rim as the BitChord card, so the remote's focus is unmistakable on the TV.
        Drawable normal = card.getBackground();
        Drawable focused = card.getResources().getDrawable(R.drawable.bitchord_bar_focused, null);
        card.setOnFocusChangeListener((v, hasFocus) -> card.setBackground(hasFocus ? focused : normal));
        setVisible(true);
        // The main content layer is declared after this card and would otherwise cover it.
        card.bringToFront();
        card.setElevation(1f);
        handler.postDelayed(this::tick, 400L);
    }

    View view() {
        return card;
    }

    /** Hidden with the football card; DOWN from the music card then falls through to the app grid. */
    void setVisible(boolean show) {
        card.setVisibility(show ? View.VISIBLE : View.GONE);
        View music = activity.findViewById(R.id.bitchordBar);
        if (music != null) music.setNextFocusDownId(show ? R.id.cctvCard : R.id.grid);
    }

    private void openHiLook() {
        if (launch == null) return;
        try {
            activity.startActivity(launch);
        } catch (RuntimeException ignored) {
            // HiLook removed while Home was open: the preview keeps running without an OK action.
        }
    }

    private void tick() {
        if (destroyed) return;
        io.execute(this::load);
        handler.postDelayed(this::tick, REFRESH_MS);
    }

    private void load() {
        Bitmap fresh = null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SNAPSHOT).openConnection();
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setUseCaches(false);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream()) {
                    fresh = BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception ignored) {
            fresh = null;   // bridge not running (yet): the card keeps the last frame and retries
        } finally {
            if (connection != null) connection.disconnect();
        }
        final Bitmap decoded = fresh;
        handler.post(() -> apply(decoded));
    }

    private void apply(Bitmap fresh) {
        if (destroyed) {
            if (fresh != null) fresh.recycle();
            return;
        }
        if (fresh != null) {
            Bitmap old = current;
            current = fresh;
            preview.setImageBitmap(fresh);
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

    /** Aligns the card under the football card, centred on its width. */
    void placeUnder(View football, View root) {
        int[] r = new int[2];
        int[] f = new int[2];
        root.getLocationInWindow(r);
        football.getLocationInWindow(f);
        float x = f[0] - r[0] + (football.getWidth() - card.getWidth()) / 2f;
        float y = f[1] - r[1] + football.getHeight() + 8 * density;
        if (Math.abs(card.getX() - x) > 0.5f || Math.abs(card.getY() - y) > 0.5f) {
            card.setX(x);
            card.setY(y);
        }
    }

    void shutdown() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (current != null) {
            current.recycle();
            current = null;
        }
    }
}
