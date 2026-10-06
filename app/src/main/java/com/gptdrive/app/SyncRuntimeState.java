package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted runtime state for one account sync. Keeps progress across Activity recreation. */
final class SyncRuntimeState {
    private static final String PREFS = "gptdrive_sync_runtime";

    static final class Snapshot {
        final String status;
        final String phase;
        final String message;
        final int part;
        final int total;
        final int objects;
        final int library;
        final int images;
        final int projects;
        final int archived;
        final int pages;
        final int strategy;
        final boolean imagesFresh;
        final boolean projectsFresh;
        final boolean archivedFresh;
        final long updatedAt;

        Snapshot(SharedPreferences p, String k) {
            status = p.getString(k + "status", "idle");
            phase = p.getString(k + "phase", "");
            message = p.getString(k + "message", "");
            part = p.getInt(k + "part", 0);
            total = p.getInt(k + "total", 0);
            objects = p.getInt(k + "objects", 0);
            library = p.getInt(k + "library", 0);
            images = p.getInt(k + "images", 0);
            projects = p.getInt(k + "projects", 0);
            archived = p.getInt(k + "archived", 0);
            pages = p.getInt(k + "pages", 0);
            strategy = p.getInt(k + "strategy", -1);
            imagesFresh = p.getBoolean(k + "imagesFresh", false);
            projectsFresh = p.getBoolean(k + "projectsFresh", false);
            archivedFresh = p.getBoolean(k + "archivedFresh", false);
            updatedAt = p.getLong(k + "updatedAt", 0);
        }

        boolean running() { return "running".equals(status); }
        boolean success() { return "success".equals(status); }
        boolean auth() { return "auth".equals(status); }
        boolean error() { return "error".equals(status); }
    }

    private SyncRuntimeState() {}

    static Snapshot read(Context c, long accountId) {
        SharedPreferences p = prefs(c);
        return new Snapshot(p, key(accountId));
    }

    static void begin(Context c, long accountId) {
        SharedPreferences p = prefs(c);
        String k = key(accountId);
        boolean resume = "running".equals(p.getString(k + "status", "idle"));
        SharedPreferences.Editor e = p.edit()
                .putString(k + "status", "running")
                .putString(k + "message", "")
                .putLong(k + "updatedAt", System.currentTimeMillis());
        if (!resume) {
            e.putString(k + "phase", "Подготовка синхронизации")
                    .putInt(k + "part", 0)
                    .putInt(k + "total", 0)
                    .putInt(k + "objects", 0)
                    .putInt(k + "library", 0)
                    .putInt(k + "images", 0)
                    .putInt(k + "projects", 0)
                    .putInt(k + "archived", 0)
                    .putInt(k + "pages", 0)
                    .putInt(k + "strategy", -1);
        }
        e.apply();
    }

    static void progress(Context c, long accountId, String phase, int part, int total, int objects) {
        SharedPreferences p = prefs(c);
        String k = key(accountId);
        int oldObjects = p.getInt(k + "objects", 0);
        p.edit()
                .putString(k + "status", "running")
                .putString(k + "phase", phase == null ? "" : phase)
                .putInt(k + "part", part)
                .putInt(k + "total", total)
                .putInt(k + "objects", Math.max(oldObjects, objects))
                .putLong(k + "updatedAt", System.currentTimeMillis())
                .apply();
    }

    static void success(Context c, long accountId, ChatGptApiClient.SyncResult r) {
        String k = key(accountId);
        prefs(c).edit()
                .putString(k + "status", "success")
                .putString(k + "phase", "Готово")
                .putString(k + "message", "")
                .putInt(k + "objects", r.library)
                .putInt(k + "library", r.library)
                .putInt(k + "images", r.images)
                .putInt(k + "projects", r.projects)
                .putInt(k + "archived", r.archived)
                .putInt(k + "pages", r.pages)
                .putInt(k + "strategy", r.libraryStrategy)
                .putBoolean(k + "imagesFresh", r.imagesFresh)
                .putBoolean(k + "projectsFresh", r.projectsFresh)
                .putBoolean(k + "archivedFresh", r.archivedFresh)
                .putLong(k + "updatedAt", System.currentTimeMillis())
                .apply();
    }

    static void auth(Context c, long accountId, String message) { terminal(c, accountId, "auth", message); }
    static void error(Context c, long accountId, String message) { terminal(c, accountId, "error", message); }

    private static void terminal(Context c, long accountId, String status, String message) {
        String k = key(accountId);
        prefs(c).edit()
                .putString(k + "status", status)
                .putString(k + "message", message == null ? "" : message)
                .putLong(k + "updatedAt", System.currentTimeMillis())
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(long id) { return "a" + id + "_"; }
}
