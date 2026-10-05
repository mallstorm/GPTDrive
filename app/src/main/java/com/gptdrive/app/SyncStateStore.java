package com.gptdrive.app;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/** Read-only view of sync_state used by diagnostics/UI. */
final class SyncStateStore {
    static final class State {
        final String surface;
        final long lastSuccess;
        final String lastError;
        final int count;
        final String meta;

        State(String surface, long lastSuccess, String lastError, int count, String meta) {
            this.surface = surface == null ? "" : surface;
            this.lastSuccess = lastSuccess;
            this.lastError = lastError == null ? "" : lastError;
            this.count = Math.max(0, count);
            this.meta = meta == null ? "" : meta;
        }

        boolean ok() { return lastSuccess > 0 && lastError.isEmpty(); }
    }

    private SyncStateStore() {}

    static List<State> list(DriveDb db, long accountId) {
        ArrayList<State> out = new ArrayList<>();
        if (db == null || accountId <= 0) return out;
        Cursor c = db.getReadableDatabase().query(
                "sync_state",
                new String[]{"surface", "last_success", "last_error", "last_count", "meta"},
                "account_id=?",
                new String[]{Long.toString(accountId)},
                null, null,
                "CASE surface WHEN 'Library' THEN 1 WHEN 'Images' THEN 2 WHEN 'Projects' THEN 3 WHEN 'Archived' THEN 4 ELSE 9 END, surface ASC");
        try {
            while (c.moveToNext()) {
                out.add(new State(
                        c.getString(0),
                        c.getLong(1),
                        c.isNull(2) ? "" : c.getString(2),
                        c.getInt(3),
                        c.isNull(4) ? "" : c.getString(4)));
            }
        } finally { c.close(); }
        return out;
    }
}
