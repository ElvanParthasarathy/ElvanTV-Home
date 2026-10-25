package com.elvan.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The Home date. In Tamil the system writes Saturday and Wednesday as the short planet names; the short weekday names people
 * say are காரி and அறிவன் (the other days are short too), so those two are swapped in after the system has formatted the date.
 */
public class DateClock extends TextClock {
    public DateClock(Context context) {
        super(context);
    }

    public DateClock(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DateClock(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(tamilDays(text), type);
    }

    private static CharSequence tamilDays(CharSequence text) {
        if (text == null || !"ta".equals(Locale.getDefault().getLanguage())) return text;
        String s = text.toString();
        s = swap(s, Calendar.SATURDAY, "காரி");
        s = swap(s, Calendar.WEDNESDAY, "அறிவன்");
        return s;
    }

    /** Replaces the system's name for one weekday, found by formatting that weekday the same way. */
    private static String swap(String s, int day, String name) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK, day);
        String system = new SimpleDateFormat("EEEE", Locale.getDefault()).format(c.getTime());
        return system.isEmpty() || s.startsWith(name) ? s : s.replace(system, name);
    }
}
