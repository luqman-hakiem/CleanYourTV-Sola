package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reception camera preview beside the football card. The PC bridge publishes one JPEG every two
 * seconds; the launcher only decodes the latest frame, so the Home stays light. OK opens a
 * full-screen view of the same frame. No new permission: INTERNET is already used by the other widgets.
 */
final class CctvWidget {
    static final String SNAPSHOT = "http://10.10.10.176:8890/reception.jpg";
    private static final long REFRESH_MS = 2000L;

    private final Activity activity;
    private final View card;
    private final ImageView preview;
    private final TextView status;
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
        // The glass rim has 20dp corners, so clip the square camera frame just inside it. Without this,
        // the dark JPEG and its scrim poke out at all four corners of the rounded card.
        FrameLayout frame = card.findViewById(R.id.cctvFrame);
        if (frame != null) {
            final float radius = 17f * density;   // 20dp card radius minus its 3dp padding
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            frame.setClipToOutline(true);
        }
        if (status != null) status.setShadowLayer(3 * density, 0, 1f * density, 0x99000000);
        card.setOnClickListener(v -> openFullscreen());
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

    /**
     * Hidden with the football card. The top pills then fall through to the music card again, exactly
     * as before this widget existed.
     */
    void setVisible(boolean show) {
        card.setVisibility(show ? View.VISIBLE : View.GONE);
        int topDown = show ? R.id.cctvCard : R.id.bitchordBar;
        int[] pills = {R.id.btnInputs, R.id.btnWifi, R.id.btnVpn, R.id.btnReorder,
                R.id.btnLibraryTop, R.id.btnTimer, R.id.btnSettings};
        for (int id : pills) {
            View pill = activity.findViewById(id);
            if (pill != null) pill.setNextFocusDownId(topDown);
        }
        View music = activity.findViewById(R.id.bitchordBar);
        if (music != null) music.setNextFocusUpId(show ? R.id.cctvCard : R.id.btnWifi);
    }

    /** OK on the focused card: fill the screen with the same live frame, without leaving the launcher. */
    private void openFullscreen() {
        try {
            activity.startActivity(new Intent(activity, CctvFullscreenActivity.class));
        } catch (RuntimeException ignored) {
            // Activity not available: the preview on Home keeps running.
        }
    }

    private void tick() {
        if (destroyed) return;
        io.execute(this::load);
        handler.postDelayed(this::tick, REFRESH_MS);
    }

    private void load() {
        final Bitmap decoded = fetchSnapshot();
        handler.post(() -> apply(decoded));
    }

    /** One snapshot, shared by the Home card and the full-screen viewer. Never throws. */
    static Bitmap fetchSnapshot() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SNAPSHOT).openConnection();
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setUseCaches(false);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception ignored) {
            // Bridge not running (yet): the caller keeps its last frame and retries.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
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

    /** Same size as the football card: align its top edge and use the same 14dp gap as the other cards. */
    void placeBeside(View football, View root) {
        int[] r = new int[2];
        int[] f = new int[2];
        root.getLocationInWindow(r);
        football.getLocationInWindow(f);
        float x = f[0] - r[0] + football.getWidth() + 14 * density;
        float y = f[1] - r[1];
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
