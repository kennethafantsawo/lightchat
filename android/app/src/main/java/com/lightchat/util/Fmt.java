package com.lightchat.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class Fmt {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat DMY = new SimpleDateFormat("dd/MM", Locale.getDefault());

    private Fmt() {}

    public static String listTime(long ms) {
        if (ms <= 0) return "";
        Calendar cal = Calendar.getInstance();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        cal.setTimeInMillis(ms);
        if (!cal.before(start)) return TIME.format(new Date(ms));
        return DMY.format(new Date(ms));
    }
}