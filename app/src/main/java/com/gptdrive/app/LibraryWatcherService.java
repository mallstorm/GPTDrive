package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** User-controlled lightweight scheduler for recent Library metadata checks. */
public final class LibraryWatcherService {
    static final String PREFS = "gptdrive_library_watcher";
    static final String K_BASELINE_PREFIX = "baseline_";
    private static final String K_ENABLED = "enabled";
    private static final String K_INTERVAL = "interval_ms";
    private static final String WORK = "gptdrive-library-watcher";
    private static final long DEFAULT_INTERVAL = 15L * 60L * 1000L;

    private LibraryWatcherService() {}

    static boolean isEnabled(Context c) { return prefs(c).getBoolean(K_ENABLED, false); }

    static void setEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(K_ENABLED, enabled).apply();
        if (enabled) ensureStarted(c); else stop(c);
    }

    static long intervalMs(Context c) {
        long v = prefs(c).getLong(K_INTERVAL, DEFAULT_INTERVAL);
        return v == 30L * 60L * 1000L || v == 60L * 60L * 1000L ? v : DEFAULT_INTERVAL;
    }

    static void setIntervalMs(Context c, long value) {
        long v = value == 30L * 60L * 1000L || value == 60L * 60L * 1000L
                ? value : DEFAULT_INTERVAL;
        prefs(c).edit().putLong(K_INTERVAL, v).apply();
        if (isEnabled(c)) ensureStarted(c);
    }

    static String intervalLabel(Context c) {
        long v = intervalMs(c);
        return v == 60L * 60L * 1000L ? "60 минут"
                : v == 30L * 60L * 1000L ? "30 минут" : "15 минут";
    }

    static void ensureStarted(Context c) {
        if (c == null || !isEnabled(c)) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                LibraryWatcherWorker.class, intervalMs(c), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(c.getApplicationContext()).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    static void stop(Context c) {
        if (c == null) return;
        WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork(WORK);
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
