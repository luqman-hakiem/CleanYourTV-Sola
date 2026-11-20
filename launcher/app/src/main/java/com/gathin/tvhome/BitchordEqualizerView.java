package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Small audio equalizer for the BitChord card: a row of rounded bars that dance while the session is
 * playing and settle flat when paused. Pure Canvas drawing, no new dependency and no permission.
 */
final class BitchordEqualizerView extends View {
    private static final int BARS = 26;

    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private ValueAnimator animator;
    private float phase;
    private boolean playing;

    public BitchordEqualizerView(Context context) {
        this(context, null);
    }

    public BitchordEqualizerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BitchordEqualizerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        density = context.getResources().getDisplayMetrics().density;
        screen.setColor(0x24000000);
        screen.setStyle(Paint.Style.FILL);
        glow.setColor(0x552B6FE0);
        bars.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            bars.setShader(new LinearGradient(0f, 0f, w, 0f,
                    new int[]{0xFF2B6FE0, 0xFF7FC0FF, 0xFF9FD2FF, 0xFF5AA0F0},
                    null, Shader.TileMode.CLAMP));
        }
    }

    /** Called from the media-session callback on the main thread. */
    void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        if (on) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(760L);
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
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, screen);

        float gap = 2f * density;
        float barWidth = (w - gap * (BARS - 1)) / BARS;
        float barRadius = Math.min(barWidth / 2f, 2f * density);
        float minH = h * 0.12f;
        float maxH = h * 0.9f;
        for (int i = 0; i < BARS; i++) {
            float level = playing ? level(i) : 0.06f;
            float bh = minH + (maxH - minH) * level;
            float x = i * (barWidth + gap);
            float y = h - bh;
            rect.set(x - 1f, y - 1f, x + barWidth + 1f, h + 1f);
            canvas.drawRoundRect(rect, barRadius + 1f, barRadius + 1f, glow);
            rect.set(x, y, x + barWidth, h);
            canvas.drawRoundRect(rect, barRadius, barRadius, bars);
        }
    }

    /** Layered sines at different speeds make the bars look musical instead of mechanical. */
    private float level(int i) {
        double p = phase;
        double a = Math.sin(2 * Math.PI * (p * 2.0 + i * 0.11));
        double b = Math.sin(2 * Math.PI * (p * 3.3 + i * 0.29));
        double c = Math.sin(2 * Math.PI * (p * 1.3 + i * 0.07));
        double v = 0.5 + 0.26 * a + 0.16 * b + 0.1 * c;
        if (v < 0.04) v = 0.04;
        if (v > 1f) v = 1f;
        return (float) v;
    }

    @Override
    protected void onDetachedFromWindow() {
        setPlaying(false);
        super.onDetachedFromWindow();
    }
}
