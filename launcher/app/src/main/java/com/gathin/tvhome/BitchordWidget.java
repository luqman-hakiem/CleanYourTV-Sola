package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Compact glass control for BitChord TV, placed under the prayer card. It reads BitChord's own media
 * session so the remote can play/pause, skip to the next track, or open the app from Home. "Lyrics"
 * opens BitChord, which is where lyrics are shown (the session exposes no lyric intent).
 *
 * <p>Platform MediaBrowser/MediaController are used on purpose: no new library and no extra permission.
 */
final class BitchordWidget {
    private static final String PKG = "com.music.bitchord.tv";
    private static final ComponentName SERVICE =
            new ComponentName(PKG, "com.music.bitchord.playback.PlaybackService");

    private final Activity activity;
    private final View bar;
    private final BitchordEqualizerView equalizer;
    private final ImageView art;
    private final TextView label, sub, play, next, lyrics;
    private final Intent launch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private MediaBrowser browser;
    private MediaController controller;
    private boolean connecting;
    private boolean playing;
    private boolean destroyed;
    private int retries;

    /** Null when BitChord is not installed (or when the layout has no bar). */
    static BitchordWidget create(Activity activity) {
        View bar = activity.findViewById(R.id.bitchordBar);
        if (bar == null) return null;
        PackageManager pm = activity.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(PKG);
        if (launch == null) launch = pm.getLaunchIntentForPackage(PKG);
        if (launch == null) {
            bar.setVisibility(View.GONE);
            return null;
        }
        return new BitchordWidget(activity, bar, launch);
    }

    private BitchordWidget(Activity activity, View bar, Intent launch) {
        this.activity = activity;
        this.bar = bar;
        this.equalizer = bar.findViewById(R.id.bitchordEqualizer);
        this.launch = launch;
        this.density = activity.getResources().getDisplayMetrics().density;
        art = bar.findViewById(R.id.bitchordArt);
        label = bar.findViewById(R.id.bitchordLabel);
        sub = bar.findViewById(R.id.bitchordSub);
        play = bar.findViewById(R.id.bitchordPlay);
        next = bar.findViewById(R.id.bitchordNext);
        lyrics = bar.findViewById(R.id.bitchordLyrics);
        for (TextView t : new TextView[]{label, sub, play, next, lyrics}) {
            if (t != null) t.setShadowLayer(3 * density, 0, 1f * density, 0x99000000);
        }
        // The whole card is the primary remote target: focus lands here and OK plays/pauses in Home.
        bar.setOnClickListener(v -> toggle());
        play.setOnClickListener(v -> toggle());
        next.setOnClickListener(v -> skipNext());
        lyrics.setOnClickListener(v -> open());
        bar.setVisibility(View.VISIBLE);
        // The main content layer is declared after this bar in the XML and would otherwise cover it:
        // bring it to the front so the remote's focus reaches the controls.
        bar.bringToFront();
        bar.setElevation(1f);
        connect();
    }

    View view() {
        return bar;
    }

    private final MediaBrowser.ConnectionCallback connection = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            if (destroyed || browser == null) return;
            connecting = false;
            MediaSession.Token token = browser.getSessionToken();
            if (token == null) {
                scheduleReconnect();
                return;
            }
            try {
                controller = new MediaController(activity, token);
                controller.registerCallback(controllerCallback);
                handler.post(BitchordWidget.this::refresh);
            } catch (RuntimeException e) {
                controller = null;
                scheduleReconnect();
            }
        }

        @Override
        public void onConnectionFailed() {
            connecting = false;
            scheduleReconnect();
        }
    };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            handler.post(() -> {
                controller = null;
                connecting = false;
                if (browser != null) {
                    try {
                        browser.disconnect();
                    } catch (RuntimeException ignored) {
                    }
                    browser = null;
                }
                scheduleReconnect();
            });
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState st) {
            handler.post(BitchordWidget.this::refresh);
        }

        @Override
        public void onMetadataChanged(MediaMetadata md) {
            handler.post(BitchordWidget.this::refresh);
        }
    };

    private void refresh() {
        if (destroyed) return;
        MediaMetadata md = controller == null ? null : controller.getMetadata();
        String title = md == null ? "" : text(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = md == null ? "" : text(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (title.isEmpty() && md != null && md.getDescription() != null
                && md.getDescription().getTitle() != null) {
            title = text(String.valueOf(md.getDescription().getTitle()));
        }
        if (artist.isEmpty() && md != null) {
            artist = text(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        }
        label.setText(title.isEmpty() ? activity.getString(R.string.bitchord_name) : title);
        sub.setText(artist);
        sub.setVisibility(artist.isEmpty() ? View.GONE : View.VISIBLE);
        Bitmap cover = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null && md != null) cover = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover != null) art.setImageBitmap(cover);
        else art.setImageDrawable(null);
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        play.setText(playing ? "\u23F8" : "\u25B6");
        if (equalizer != null) equalizer.setPlaying(playing);
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }

    private void connect() {
        if (destroyed || browser != null || connecting) return;
        connecting = true;
        try {
            browser = new MediaBrowser(activity, SERVICE, connection, null);
            browser.connect();
        } catch (RuntimeException e) {
            browser = null;
            connecting = false;
            scheduleReconnect();
        }
    }

    /** A stopped BitChord service has no session yet; try a few times, then wait for the next Home visit. */
    private void scheduleReconnect() {
        if (destroyed || retries >= 4) return;
        retries++;
        final int attempt = retries;
        handler.postDelayed(() -> {
            if (destroyed) return;
            if (browser != null) {
                try {
                    browser.disconnect();
                } catch (RuntimeException ignored) {
                }
                browser = null;
            }
            connect();
        }, 1200L * attempt);
    }

    /** Play/pause through the session; fall back to a media key if the session is not up yet. */
    private void toggle() {
        if (controller != null) {
            try {
                if (playing) controller.getTransportControls().pause();
                else controller.getTransportControls().play();
                return;
            } catch (RuntimeException ignored) {
            }
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        retries = 0;
        connect();
    }

    /** Skip to the next track. */
    private void skipNext() {
        if (controller != null) {
            try {
                controller.getTransportControls().skipToNext();
                return;
            } catch (RuntimeException ignored) {
            }
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        retries = 0;
        connect();
    }

    private void dispatchMediaKey(int code) {
        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException ignored) {
        }
    }

    private void open() {
        try {
            activity.startActivity(new Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** Sits directly below the left side of the prayer card. */
    void placeUnder(View card, View root) {
        if (bar.getVisibility() != View.VISIBLE || card.getWidth() == 0) return;
        int[] r = new int[2], c = new int[2];
        root.getLocationInWindow(r);
        card.getLocationInWindow(c);
        int x = c[0] - r[0];
        int y = c[1] - r[1] + card.getHeight() + (int) (4 * density);
        if (Math.abs(bar.getX() - x) > 0.5f || Math.abs(bar.getY() - y) > 0.5f) {
            bar.setX(x);
            bar.setY(y);
        }
    }

    void shutdown() {
        destroyed = true;
        if (equalizer != null) equalizer.setPlaying(false);
        handler.removeCallbacksAndMessages(null);
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (RuntimeException ignored) {
            }
            controller = null;
        }
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (RuntimeException ignored) {
            }
            browser = null;
        }
    }
}
