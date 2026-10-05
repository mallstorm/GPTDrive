package com.gptdrive.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Durable local Chat Mirror metadata + gzip snapshots.
 *
 * Remote disappearance never deletes a local snapshot. A missing remote conversation is only
 * marked remote_present=0 with remote_missing_since, matching the Windows reference behavior.
 */
final class ChatMirrorRepository {
    static final class Stats {
        final int total;
        final int remotePresent;
        final int mirrored;
        final int pending;
        final int missingRemote;
        final long bytes;

        Stats(int total, int remotePresent, int mirrored, int pending, int missingRemote, long bytes) {
            this.total = total;
            this.remotePresent = remotePresent;
            this.mirrored = mirrored;
            this.pending = pending;
            this.missingRemote = missingRemote;
            this.bytes = bytes;
        }
    }

    private static final String TABLE = "chat_mirror";
    private final Context context;
    private final DriveDb db;
    private final long accountId;

    ChatMirrorRepository(Context context, DriveDb db, long accountId) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.accountId = accountId;
        ensureSchema();
    }

    void ensureSchema() {
        SQLiteDatabase sql = db.getWritableDatabase();
        sql.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + "(" +
                "account_id INTEGER NOT NULL," +
                "conversation_id TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "archived INTEGER NOT NULL DEFAULT 0," +
                "project_id TEXT," +
                "current_node TEXT," +
                "has_mapping INTEGER NOT NULL DEFAULT 0," +
                "raw_path TEXT," +
                "raw_size INTEGER NOT NULL DEFAULT 0," +
                "mirrored_at INTEGER NOT NULL DEFAULT 0," +
                "source_updated_at INTEGER NOT NULL DEFAULT 0," +
                "remote_present INTEGER NOT NULL DEFAULT 1," +
                "remote_missing_since INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT," +
                "PRIMARY KEY(account_id,conversation_id))");
        sql.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_mirror_account_updated ON " + TABLE +
                "(account_id,remote_present,updated_at DESC)");
    }

    /** Apply a complete remote summary snapshot without deleting historical local rows. */
    void applyRemoteSummaries(List<ChatGptModels.RemoteConversation> summaries) {
        ensureSchema();
        SQLiteDatabase sql = db.getWritableDatabase();
        long now = System.currentTimeMillis();
        sql.beginTransaction();
        try {
            ContentValues absent = new ContentValues();
            absent.put("remote_present", 0);
            sql.update(TABLE, absent, "account_id=?", new String[]{Long.toString(accountId)});

            if (summaries != null) {
                Set<String> seen = new HashSet<>();
                for (ChatGptModels.RemoteConversation c : summaries) {
                    if (c == null || blank(c.id)) continue;
                    String id = c.id.trim();
                    if (!seen.add(id)) continue;

                    ContentValues v = new ContentValues();
                    v.put("title", clean(c.title, "ChatGPT chat"));
                    v.put("created_at", Math.max(0, c.createdAt));
                    v.put("updated_at", Math.max(0, c.updatedAt));
                    v.put("archived", c.archived ? 1 : 0);
                    v.put("project_id", nz(c.projectId));
                    v.put("remote_present", 1);
                    v.put("remote_missing_since", 0);
                    int changed = sql.update(TABLE, v,
                            "account_id=? AND conversation_id=?",
                            new String[]{Long.toString(accountId), id});
                    if (changed == 0) {
                        v.put("account_id", accountId);
                        v.put("conversation_id", id);
                        v.put("current_node", "");
                        v.put("has_mapping", 0);
                        v.put("raw_path", "");
                        v.put("raw_size", 0);
                        v.put("mirrored_at", 0);
                        v.put("source_updated_at", 0);
                        v.put("last_error", "");
                        sql.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                }
            }

            ContentValues missing = new ContentValues();
            missing.put("remote_missing_since", now);
            sql.update(TABLE, missing,
                    "account_id=? AND remote_present=0 AND remote_missing_since=0",
                    new String[]{Long.toString(accountId)});
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }
    }

    /** New/changed conversations whose full mapping should be refreshed. */
    List<String> pendingIds(int limit) {
        ensureSchema();
        int max = limit <= 0 ? 25 : Math.min(limit, 500);
        ArrayList<String> out = new ArrayList<>();
        Cursor c = db.getReadableDatabase().query(TABLE,
                new String[]{"conversation_id", "raw_path", "source_updated_at", "updated_at"},
                "account_id=? AND remote_present=1",
                new String[]{Long.toString(accountId)}, null, null,
                "CASE WHEN raw_path IS NULL OR raw_path='' THEN 0 ELSE 1 END ASC, updated_at DESC");
        try {
            while (c.moveToNext() && out.size() < max) {
                String id = c.getString(0);
                String path = c.getString(1);
                long sourceUpdated = c.getLong(2);
                long remoteUpdated = c.getLong(3);
                boolean stale = sourceUpdated <= 0 || (remoteUpdated > 0 && sourceUpdated < remoteUpdated);
                boolean missingFile = blank(path) || !resolve(path).isFile();
                if ((stale || missingFile) && !blank(id)) out.add(id);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void storeFull(ChatGptModels.RemoteConversation conversation) throws Exception {
        if (conversation == null || blank(conversation.id) || blank(conversation.rawJson))
            throw new IllegalArgumentException("Full conversation snapshot required");

        String id = conversation.id.trim();
        String relative = relativePath(id);
        File target = resolve(relative);
        File dir = target.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()))
            throw new java.io.IOException("Не удалось создать каталог Chat Mirror");

        File part = new File(target.getAbsolutePath() + ".part");
        try (FileOutputStream fos = new FileOutputStream(part);
             GZIPOutputStream gz = new GZIPOutputStream(fos, 32768)) {
            gz.write(conversation.rawJson.getBytes(StandardCharsets.UTF_8));
            gz.finish();
            fos.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            part.delete();
            throw new java.io.IOException("Не удалось заменить snapshot Chat Mirror");
        }
        if (!part.renameTo(target)) {
            copy(part, target);
            if (!part.delete()) part.deleteOnExit();
        }

        long sourceUpdated = conversation.updatedAt > 0 ? conversation.updatedAt : remoteUpdatedAt(id);
        ContentValues v = new ContentValues();
        v.put("title", clean(conversation.title, "ChatGPT chat"));
        if (conversation.createdAt > 0) v.put("created_at", conversation.createdAt);
        if (conversation.updatedAt > 0) v.put("updated_at", conversation.updatedAt);
        v.put("archived", conversation.archived ? 1 : 0);
        v.put("project_id", nz(conversation.projectId));
        v.put("current_node", nz(conversation.currentNode));
        v.put("has_mapping", conversation.hasMapping ? 1 : 0);
        v.put("raw_path", relative);
        v.put("raw_size", target.length());
        v.put("mirrored_at", System.currentTimeMillis());
        v.put("source_updated_at", Math.max(0, sourceUpdated));
        v.put("remote_present", 1);
        v.put("remote_missing_since", 0);
        v.put("last_error", "");
        db.getWritableDatabase().update(TABLE, v,
                "account_id=? AND conversation_id=?",
                new String[]{Long.toString(accountId), id});
    }

    void markFetchError(String conversationId, Throwable error) {
        if (blank(conversationId)) return;
        String m = error == null || blank(error.getMessage())
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : error.getMessage().trim();
        if (m.length() > 500) m = m.substring(0, 500);
        ContentValues v = new ContentValues();
        v.put("last_error", m);
        db.getWritableDatabase().update(TABLE, v,
                "account_id=? AND conversation_id=?",
                new String[]{Long.toString(accountId), conversationId.trim()});
    }

    String readRaw(String conversationId) throws Exception {
        String relative = rawPath(conversationId);
        if (blank(relative)) return "";
        File file = resolve(relative);
        if (!file.isFile()) return "";
        try (GZIPInputStream gz = new GZIPInputStream(new FileInputStream(file), 32768);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[32768];
            int n;
            while ((n = gz.read(b)) != -1) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    Stats stats() {
        ensureSchema();
        SQLiteDatabase sql = db.getReadableDatabase();
        int total = scalarInt(sql, "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id=?",
                new String[]{Long.toString(accountId)});
        int remote = scalarInt(sql, "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id=? AND remote_present=1",
                new String[]{Long.toString(accountId)});
        int mirrored = scalarInt(sql, "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id=? AND raw_path IS NOT NULL AND raw_path<>''",
                new String[]{Long.toString(accountId)});
        int missing = scalarInt(sql, "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id=? AND remote_present=0",
                new String[]{Long.toString(accountId)});
        int pending = scalarInt(sql, "SELECT COUNT(*) FROM " + TABLE +
                        " WHERE account_id=? AND remote_present=1 AND (raw_path IS NULL OR raw_path='' OR source_updated_at<updated_at)",
                new String[]{Long.toString(accountId)});
        long bytes = scalarLong(sql, "SELECT COALESCE(SUM(raw_size),0) FROM " + TABLE + " WHERE account_id=?",
                new String[]{Long.toString(accountId)});
        return new Stats(total, remote, mirrored, pending, missing, bytes);
    }

    void deleteAccountMirror() {
        ensureSchema();
        db.getWritableDatabase().delete(TABLE, "account_id=?", new String[]{Long.toString(accountId)});
        deleteTree(accountDir());
    }

    private String rawPath(String conversationId) {
        Cursor c = db.getReadableDatabase().query(TABLE, new String[]{"raw_path"},
                "account_id=? AND conversation_id=?",
                new String[]{Long.toString(accountId), conversationId == null ? "" : conversationId.trim()},
                null, null, null, "1");
        try { return c.moveToFirst() ? nz(c.getString(0)) : ""; }
        finally { c.close(); }
    }

    private long remoteUpdatedAt(String conversationId) {
        Cursor c = db.getReadableDatabase().query(TABLE, new String[]{"updated_at"},
                "account_id=? AND conversation_id=?",
                new String[]{Long.toString(accountId), conversationId}, null, null, null, "1");
        try { return c.moveToFirst() ? c.getLong(0) : 0; }
        finally { c.close(); }
    }

    private File accountDir() {
        return new File(context.getFilesDir(), "chat_mirror/accounts/a" + accountId);
    }

    private String relativePath(String conversationId) throws Exception {
        return "chat_mirror/accounts/a" + accountId + "/conversations/" + sha256(conversationId) + ".json.gz";
    }

    private File resolve(String relative) {
        if (blank(relative)) return new File(context.getFilesDir(), "missing");
        return new File(context.getFilesDir(), relative);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void copy(File source, File target) throws Exception {
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            out.getFD().sync();
        }
    }

    private static int scalarInt(SQLiteDatabase db, String sql, String[] args) {
        return (int) Math.min(Integer.MAX_VALUE, scalarLong(db, sql, args));
    }

    private static long scalarLong(SQLiteDatabase db, String sql, String[] args) {
        Cursor c = db.rawQuery(sql, args);
        try { return c.moveToFirst() ? c.getLong(0) : 0; }
        finally { c.close(); }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String clean(String s, String fallback) {
        String v = nz(s).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) v = fallback;
        return v.length() > 240 ? v.substring(0, 240) : v;
    }
}
