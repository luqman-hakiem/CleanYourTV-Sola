package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * ECG-style heartbeat trace drawn inside the BitChord card. The waveform scrolls right to left while
 * the session is playing and freezes on a quiet baseline when paused. Pure Canvas drawing: no new
 * dependency and no media permission of its own.
 */
final class BitchordPulseView extends View {
    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF bounds = new RectF();
    private final float density;
    private ValueAnimator animator;
    private float phase;
    private boolean playing;

    public BitchordPulseView(Context context) {
        this(context, null);
    }

    public BitchordPulseView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BitchordPulseView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        density = context.getResources().getDisplayMetrics().density;
        screen.setColor(0x24000000);
        screen.setStyle(Paint.Style.FILL);
        stroke(glow, 0x992B6FE0, 5f);
        stroke(line, 0xFFBFE6FF, 2f);
        stroke(dim, 0x66BFE6FF, 1.4f);
    }

    private void stroke(Paint p, int color, float dp) {
        p.setColor(color);
        p.setStrokeWidth(dp * density);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Called from the media-session callback on the main thread. */
    void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        if (on) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(900L);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
            }
            animator.start();
        } else if (animator != null) {
            animator.cancel();
            phase = 0f;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        float radius = h / 2f;
        bounds.set(0f, 0f, w, h);
        canvas.drawRoundRect(bounds, radius, radius, screen);

        float base = h * 0.66f;
        float amp = h * 0.5f;
        float period = w / 2f; // two beats visible across the card
        path.reset();
        for (float x = 0f; x <= w; x += 2f * density) {
            float t = ((x / period) + phase) % 1f;
            float y = base - amp * wave(t);
            if (x == 0f) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        if (playing) canvas.drawPath(path, glow);
        canvas.drawPath(path, playing ? line : dim);
    }

    /** One ECG beat: P wave, Q dip, sharp R spike, S dip, then the rounded T wave. */
    private static float wave(float t) {
        if (t < 0.10f) return 0f;
        if (t < 0.18f) return 0.16f * bump((t - 0.10f) / 0.08f);
        if (t < 0.28f) return 0f;
        if (t < 0.32f) return -0.14f * bump((t - 0.28f) / 0.04f);
        if (t < 0.36f) return lerp(-0.14f, 1f, (t - 0.32f) / 0.04f);
        if (t < 0.40f) return lerp(1f, -0.34f, (t - 0.36f) / 0.04f);
        if (t < 0.46f) return lerp(-0.34f, 0f, (t - 0.40f) / 0.06f);
        if (t < 0.66f) return 0.24f * bump((t - 0.46f) / 0.20f);
        return 0f;
    }

    private static float bump(float u) {
        return (float) Math.sin(Math.PI * u);
    }

    private static float lerp(float a, float b, float u) {
        return a + (b - a) * u;
    }

    @Override
    protected void onDetachedFromWindow() {
        setPlaying(false);
        super.onDetachedFromWindow();
    }
}
