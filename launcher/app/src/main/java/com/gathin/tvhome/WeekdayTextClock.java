package com.gathin.tvhome;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.widget.TextClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The date under the clock. On a Sunday the date part ("MINGGU 15") is red while the month name
 * ("NOVEMBER") keeps the header's own white. TextClock re-formats its text every minute, so the
 * colour is put on each new line as it is set.
 */
public final class WeekdayTextClock extends TextClock {

    /** The Sunday red. */
    private static final int SUNDAY_RED = 0xFFFF5252;

    public WeekdayTextClock(Context context) {
        this(context, null);
    }

    public WeekdayTextClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WeekdayTextClock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(colour(text), type);
    }

    /** Red before the month name on a Sunday, the line as it came otherwise. */
    private static CharSequence colour(CharSequence text) {
        if (text == null || text.length() == 0) return text;
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) return text;
        String line = text.toString();
        int at = monthStart(line);
        if (at <= 0) return text;
        SpannableString red = new SpannableString(text);
        red.setSpan(new ForegroundColorSpan(SUNDAY_RED), 0, at,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return red;
    }

    /** Where the month name starts in the formatted line, or 0 when the line has no month. */
    private static int monthStart(String text) {
        Locale locale = Locale.getDefault();
        String month = new SimpleDateFormat("MMMM", locale).format(new Date());
        int at = month.isEmpty() ? -1 : text.toLowerCase(locale).lastIndexOf(month.toLowerCase(locale));
        if (at > 0) return at;
        int space = text.lastIndexOf(' ');
        return space > 0 ? space + 1 : 0;
    }
}
