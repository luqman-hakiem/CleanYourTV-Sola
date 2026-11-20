package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A thin glass line under the BitChord card while it is playing. A bright segment slides from left to
 * right and repeats; pausing stops the animation and leaves only the quiet track. Pure Canvas drawing,
 * no new dependency and no media permission of its own.
 */
final class BitchordPulseView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
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
        track.setColor(0x3DFFFFFF);
        glow.setColor(0x662B6FE0);
        light.setColor(0xFF9FD2FF);
    }

    /** Called from the media-session callback on the main thread. */
    void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        if (on) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(1500L);
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
        canvas.drawRoundRect(rect, radius, radius, track);
        if (!playing) return;
        float segment = w * 0.26f;
        float x = phase * (w - segment);
        rect.set(x - 7f, -2f, x + segment + 7f, h + 2f);
        canvas.drawRoundRect(rect, radius + 2f, radius + 2f, glow);
        rect.set(x, 0f, x + segment, h);
        canvas.drawRoundRect(rect, radius, radius, light);
    }

    @Override
    protected void onDetachedFromWindow() {
        setPlaying(false);
        super.onDetachedFromWindow();
    }
}
