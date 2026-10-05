package com.gptdrive.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Preserves last successful sync while recording independent subsystem failures. */
final class SyncStateRepository {
    private SyncStateRepository() {}

    static void record(DriveDb db, long accountId, String surface, boolean success,
                       int count, String message, String meta) {
        if (db == null || accountId <= 0) return;
        String s = surface == null || surface.trim().isEmpty() ? "unknown" : surface.trim();
        SQLiteDatabase sql = db.getWritableDatabase();
        long lastSuccess = 0;
        Cursor c = sql.query("sync_state", new String[]{"last_success"},
                "account_id=? AND surface=?", new String[]{Long.toString(accountId), s},
                null, null, null, "1");
        try { if (c.moveToFirst()) lastSuccess = c.getLong(0); } finally { c.close(); }

        ContentValues v = new ContentValues();
        v.put("account_id", accountId);
        v.put("surface", s);
        v.put("last_success", success ? System.currentTimeMillis() : lastSuccess);
        v.put("last_error", success ? "" : safeMessage(message));
        v.put("last_count", Math.max(0, count));
        v.put("meta", meta == null ? "" : meta);
        int changed = sql.update("sync_state", v, "account_id=? AND surface=?",
                new String[]{Long.toString(accountId), s});
        if (changed == 0) sql.insertWithOnConflict("sync_state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    static List<Entry> list(DriveDb db, long accountId) {
        ArrayList<Entry> out = new ArrayList<>();
        if (db == null || accountId <= 0) return out;
        Cursor c = db.getReadableDatabase().query("sync_state", null,
                "account_id=?", new String[]{Long.toString(accountId)}, null, null, "surface ASC");
        try {
            while (c.moveToNext()) {
                out.add(new Entry(
                        getString(c, "surface"),
                        getLong(c, "last_success"),
                        getString(c, "last_error"),
                        (int) getLong(c, "last_count"),
                        getString(c, "meta")));
            }
        } finally { c.close(); }
        return out;
    }

    static final class Entry {
        final String surface;
        final long lastSuccess;
        final String lastError;
        final int count;
        final String meta;
        Entry(String surface, long lastSuccess, String lastError, int count, String meta) {
            this.surface = surface; this.lastSuccess = lastSuccess; this.lastError = lastError;
            this.count = count; this.meta = meta;
        }
    }

    private static String safeMessage(String message) {
        if (message == null) return "";
        String s = message.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+", "Bearer <redacted>");
        if (s.length() > 300) s = s.substring(0, 300);
        return s;
    }
    private static String getString(Cursor c, String n) { int i=c.getColumnIndex(n); return i<0||c.isNull(i)?"":c.getString(i); }
    private static long getLong(Cursor c, String n) { int i=c.getColumnIndex(n); return i<0||c.isNull(i)?0:c.getLong(i); }
}
