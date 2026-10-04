package com.gptdrive.app;

import android.database.sqlite.SQLiteDatabase;

/** Small migrations/cleanup that do not require changing the public DB API. */
final class IndexMaintenance {
    private IndexMaintenance() {}

    static void pruneTransientSignedUrls(DriveDb db, long accountId) {
        if (db == null || accountId <= 0) return;
        try {
            SQLiteDatabase sql = db.getWritableDatabase();
            sql.delete(
                    "items",
                    "account_id=? AND (lower(url) LIKE ? OR lower(url) LIKE ? OR lower(url) LIKE ?)",
                    new String[]{
                            Long.toString(accountId),
                            "%oaiusercontent.com%",
                            "%oaidalle%",
                            "%x-goog-signature=%"
                    });
        } catch (Exception ignored) {}
    }
}
