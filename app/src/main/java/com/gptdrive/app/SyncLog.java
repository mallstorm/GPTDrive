package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small persistent ring buffer of sync/auth/provider events (last 80 lines), also sent to logcat. */
final class SyncLog {
    private static final String PREFS = "gptdrive_sync_log";
    private static final int MAX = 80;
    private static final Object LOCK = new Object();

    private SyncLog() {}

    static void e(Context c, String tag, String message, Throwable t) {
        String m = message + (t == null ? "" : ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        Log.w("GPTDrive/" + tag, m);
        append(c, "E " + tag + " " + m);
    }

    static void i(Context c, String tag, String message) {
        Log.i("GPTDrive/" + tag, message);
        append(c, "I " + tag + " " + message);
    }

    static String dump(Context c) {
        if (c == null) return "";
        return prefs(c).getString("log", "");
    }

    private static void append(Context c, String line) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences p = prefs(c);
                String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
                String cur = p.getString("log", "");
                String[] lines = cur.isEmpty() ? new String[0] : cur.split("\n");
                StringBuilder out = new StringBuilder();
                int from = Math.max(0, lines.length - (MAX - 1));
                for (int i = from; i < lines.length; i++) out.append(lines[i]).append('\n');
                out.append(stamp).append(' ').append(line.replace('\n', ' '));
                p.edit().putString("log", out.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
