package com.gptdrive.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Incremental metadata merge used by the lightweight watcher. It never deletes rows or downloads bodies. */
final class LibraryWatcherRepository {
    static final class MergeResult {
        final ArrayList<ChatGptModels.RemoteFile> newItems;
        final int existingBefore;
        MergeResult(ArrayList<ChatGptModels.RemoteFile> newItems, int existingBefore) {
            this.newItems = newItems;
            this.existingBefore = existingBefore;
        }
    }

    private final DriveDb db;
    private final long accountId;

    LibraryWatcherRepository(DriveDb db, long accountId) {
        this.db = db;
        this.accountId = accountId;
    }

    MergeResult merge(List<ChatGptModels.RemoteFile> remote) {
        SQLiteDatabase sql = db.getWritableDatabase();
        int existingBefore = countExisting(sql);
        ArrayList<ChatGptModels.RemoteFile> fresh = new ArrayList<>();
        sql.beginTransaction();
        try {
            if (remote != null) {
                for (ChatGptModels.RemoteFile f : remote) {
                    if (f == null) continue;
                    String identity = safe(f.identity());
                    if (identity.isEmpty()) continue;
                    Existing old = find(sql, identity);
                    ContentValues values = values(f, identity, old == null ? 0 : old.sourceFlags);
                    if (old == null) {
                        values.put("account_id", accountId);
                        values.put("file_id", identity);
                        long row = sql.insertWithOnConflict("files", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                        if (row > 0) fresh.add(f);
                    } else {
                        sql.update("files", values, "id=?", new String[]{Long.toString(old.rowId)});
                    }
                }
            }
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }
        return new MergeResult(fresh, existingBefore);
    }

    private ContentValues values(ChatGptModels.RemoteFile f, String identity, int oldFlags) {
        ContentValues v = new ContentValues();
        v.put("library_file_id", safe(f.libraryFileId));
        String name = cleanName(f.name);
        v.put("name", name);
        String mime = safe(f.mimeType);
        if (mime.isEmpty()) mime = DriveDb.guessMime(name + " " + safe(f.rawUrl));
        v.put("mime", mime);
        v.put("size_bytes", Math.max(0, f.sizeBytes));
        v.put("created_at", Math.max(0, f.createdAt));
        v.put("updated_at", f.updatedAt > 0 ? f.updatedAt : System.currentTimeMillis());
        v.put("conversation_id", safe(f.conversationId));
        v.put("message_id", safe(f.messageId));
        v.put("width", Math.max(0, f.width));
        v.put("height", Math.max(0, f.height));
        v.put("source_flags", oldFlags | f.sourceFlags);
        String ref = safe(f.stableUrl());
        if (ref.isEmpty()) ref = ChatGptApiClient.buildFileRef(identity, f.conversationId);
        v.put("remote_ref", ref);
        return v;
    }

    private Existing find(SQLiteDatabase sql, String identity) {
        Cursor c = sql.query("files", new String[]{"id", "source_flags"},
                "account_id=? AND file_id=?",
                new String[]{Long.toString(accountId), identity}, null, null, null, "1");
        try {
            if (!c.moveToFirst()) return null;
            return new Existing(c.getLong(0), c.getInt(1));
        } finally { c.close(); }
    }

    private int countExisting(SQLiteDatabase sql) {
        Cursor c = sql.rawQuery("SELECT COUNT(*) FROM files WHERE account_id=?",
                new String[]{Long.toString(accountId)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private static String cleanName(String value) {
        String v = safe(value).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) v = "ChatGPT file";
        return v.length() > 160 ? v.substring(0, 160) : v;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String v = value.trim();
        return "null".equals(v.toLowerCase(Locale.ROOT)) ? "" : v;
    }

    private static final class Existing {
        final long rowId;
        final int sourceFlags;
        Existing(long rowId, int sourceFlags) { this.rowId = rowId; this.sourceFlags = sourceFlags; }
    }
}
