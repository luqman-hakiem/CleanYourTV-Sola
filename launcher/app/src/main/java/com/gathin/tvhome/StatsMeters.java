package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Locale;

/**
 * CPU, memory and storage as three slim gauges in one row: a dim label on the left, its reading on the
 * right, and a rounded track under them that fills with that measure's colour. Replaces the old
 * one-line text (config.xml show_stats), which is unreadable at a glance from the couch.
 */
public final class StatsMeters extends View {

    private static final int CPU_COLOR = 0xFF8CF0B4;
    private static final int MEMORY_COLOR = 0xFF8FC7E8;
    private static final int STORAGE_COLOR = 0xFFFFCF6B;
    private static final int TRACK_COLOR = 0x26FFFFFF;
    private static final int LABEL_COLOR = 0xA6FFFFFF;
    private static final int VALUE_COLOR = 0xFFF2F6F7;
    /** Shown instead of a reading that cannot be read (e.g. /proc/stat hidden by Android 9). */
    private static final String UNKNOWN = "–";

    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private final float barHeight;
    private final float textGap;
    private final float columnGap;
    private final String[] labels = new String[3];

    /** CPU busy percent, or -1 when unknown. */
    private int cpu = -1;
    /** RAM used and total in MB, -1 when unknown. */
    private long memUsed = -1, memTotal = -1;
    /** Storage used and total in bytes, -1 when unknown. */
    private long storeUsed = -1, storeTotal = -1;

    public StatsMeters(Context context) {
        this(context, null);
    }

    public StatsMeters(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatsMeters(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Resources res = context.getResources();
        labels[0] = res.getString(R.string.stats_label_cpu);
        labels[1] = res.getString(R.string.stats_label_memory);
        labels[2] = res.getString(R.string.stats_label_storage);

        labelPaint.setColor(LABEL_COLOR);
        labelPaint.setTextSize(sp(10));
        labelPaint.setLetterSpacing(0.03f);

        valuePaint.setColor(VALUE_COLOR);
        valuePaint.setTextSize(sp(11));
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        trackPaint.setColor(TRACK_COLOR);
        fillPaint.setColor(CPU_COLOR);

        barHeight = dp(4);
        textGap = dp(3);
        columnGap = dp(12);
    }

    /** New readings. Called on the main thread; a -1 stands for an unreadable measure. */
    void setStats(int cpu, long[] memoryMb, long[] storageBytes) {
        this.cpu = cpu;
        memUsed = memoryMb != null ? memoryMb[0] : -1;
        memTotal = memoryMb != null ? memoryMb[1] : -1;
        storeUsed = storageBytes != null ? storageBytes[0] : -1;
        storeTotal = storageBytes != null ? storageBytes[1] : -1;
        setContentDescription(describe());
        invalidate();
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(dp(360)), widthMeasureSpec);
        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        int height = resolveSize(Math.round(fm.descent - fm.ascent + textGap + barHeight),
                heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        float column = (width - 2 * columnGap) / 3f;
        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        float baseline = -fm.ascent;
        float barTop = baseline + fm.descent + textGap;
        float barBottom = Math.min(barTop + barHeight, getHeight());

        for (int i = 0; i < 3; i++) {
            float left = i * (column + columnGap);
            float right = left + column;
            String value = value(i);
            float valueWidth = valuePaint.measureText(value);

            canvas.save();
            canvas.clipRect(left, 0, right, getHeight());
            // Keep a gap: if a long label would run into the reading, it ends in an ellipsis instead.
            CharSequence label = TextUtils.ellipsize(labels[i], labelPaint,
                    Math.max(0, column - valueWidth - dp(6)), TextUtils.TruncateAt.END);
            canvas.drawText(label, 0, label.length(), left, baseline, labelPaint);
            canvas.drawText(value, right - valueWidth, baseline, valuePaint);
            canvas.restore();

            float radius = barHeight / 2f;
            bar.set(left, barTop, right, barBottom);
            canvas.drawRoundRect(bar, radius, radius, trackPaint);

            float filled = fraction(i);
            if (filled > 0) {
                bar.set(left, barTop, left + Math.max(barHeight, column * filled), barBottom);
                fillPaint.setColor(color(i));
                canvas.drawRoundRect(bar, radius, radius, fillPaint);
            }
        }
    }

    private int color(int index) {
        return index == 0 ? CPU_COLOR : index == 1 ? MEMORY_COLOR : STORAGE_COLOR;
    }

    private float fraction(int index) {
        switch (index) {
            case 0:
                return cpu < 0 ? 0f : Math.min(1f, cpu / 100f);
            case 1:
                return memTotal <= 0 ? 0f : Math.min(1f, Math.max(0f, (float) memUsed / memTotal));
            default:
                return storeTotal <= 0 ? 0f
                        : Math.min(1f, Math.max(0f, (float) storeUsed / storeTotal));
        }
    }

    private String value(int index) {
        switch (index) {
            case 0:
                return cpu < 0 ? UNKNOWN : getResources().getString(R.string.stats_value_percent, cpu);
            case 1:
                return memTotal <= 0 ? UNKNOWN
                        : getResources().getString(R.string.stats_value_mb, memUsed, memTotal);
            default:
                if (storeTotal <= 0) return UNKNOWN;
                String used = formatGbNumber(storeUsed);
                String whole = formatGbNumber(storeTotal);
                return used.isEmpty() || whole.isEmpty() ? UNKNOWN
                        : getResources().getString(R.string.stats_value_gb, used, whole);
        }
    }

    /** A size as a bare number with one decimal under 10, e.g. "5,3" or "12"; "" when unreadable. */
    private String formatGbNumber(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? getResources().getString(R.string.storage_num_whole, gb)
                : getResources().getString(R.string.storage_num_decimal, gb);
    }

    /** The three readings in one sentence, for a remote that reads the screen out. */
    private String describe() {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (line.length() > 0) line.append(", ");
            line.append(labels[i]).append(' ').append(value(i));
        }
        // Keep digits together in right-to-left locales (Hebrew, Arabic).
        String text = line.toString();
        if (locale.getLanguage().equals("he") || locale.getLanguage().equals("iw")
                || locale.getLanguage().equals("ar")) {
            return "\u200F" + text;
        }
        return text;
    }
}
