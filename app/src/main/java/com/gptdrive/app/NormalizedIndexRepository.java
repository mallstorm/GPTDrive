package com.gptdrive.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the normalized SQLite snapshot. File row ids are kept stable across syncs so
 * project_files relations and DocumentsProvider ids do not break after every refresh.
 */
final class NormalizedIndexRepository {
    private final DriveDb db;
    private final long accountId;

    NormalizedIndexRepository(DriveDb db, long accountId) {
        this.db = db;
        this.accountId = accountId;
    }

    void replaceFiles(SQLiteDatabase sql, List<ChatGptModels.RemoteFile> snapshot) {
        replaceFiles(sql, snapshot, 0);
    }

    /**
     * @param preserveSourceMask source flags whose last good rows must survive when that
     *                           remote surface failed during the current sync.
     */
    void replaceFiles(SQLiteDatabase sql, List<ChatGptModels.RemoteFile> snapshot, int preserveSourceMask) {
        Map<String, ExistingFile> existing = fileRows(sql);
        Set<String> seen = new HashSet<>();

        if (snapshot != null) {
            for (ChatGptModels.RemoteFile f : snapshot) {
                if (f == null) continue;
                String fileId = f.identity();
                if (fileId == null || fileId.trim().isEmpty()) continue;
                fileId = fileId.trim();
                seen.add(fileId);

                ExistingFile old = existing.get(fileId);
                int effectiveFlags = f.sourceFlags;
                if (old != null && preserveSourceMask != 0)
                    effectiveFlags |= old.sourceFlags & preserveSourceMask;

                ContentValues v = fileValues(f, fileId, effectiveFlags);
                if (old != null && old.rowId > 0) {
                    sql.update("files", v, "id=?", new String[]{Long.toString(old.rowId)});
                } else {
                    v.put("account_id", accountId);
                    v.put("file_id", fileId);
                    sql.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
        }

        // Only stale identities from successful surfaces are removed. If image_gen or Projects
        // failed, their previous rows survive until that surface has another successful snapshot.
        for (Map.Entry<String, ExistingFile> e : existing.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            ExistingFile old = e.getValue();
            if ((old.sourceFlags & preserveSourceMask) != 0) continue;
            sql.delete("project_files", "file_row_id=?", new String[]{Long.toString(old.rowId)});
            sql.delete("files", "id=?", new String[]{Long.toString(old.rowId)});
        }

        // v0.8.0 stored normalized files in legacy category rows as well.
        sql.delete("items",
                "account_id=? AND category IN (?,?) AND lower(url) LIKE 'gptdrive://file/%'",
                new String[]{Long.toString(accountId), "Library", "Images"});
    }

    void replaceProjects(SQLiteDatabase sql, List<ChatGptModels.RemoteProject> projects) {
        ArrayList<Long> oldRows = ids(sql, "projects", "account_id=?", new String[]{Long.toString(accountId)});
        for (Long id : oldRows)
            sql.delete("project_files", "project_row_id=?", new String[]{Long.toString(id)});
        sql.delete("projects", "account_id=?", new String[]{Long.toString(accountId)});
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Projects"});

        if (projects == null) return;
        for (ChatGptModels.RemoteProject p : projects) {
            if (p == null || blank(p.id)) continue;
            ContentValues pv = new ContentValues();
            pv.put("account_id", accountId);
            pv.put("project_id", p.id.trim());
            pv.put("name", clean(p.name, "Project"));
            pv.put("updated_at", System.currentTimeMillis());
            long projectRow = sql.insert("projects", null, pv);
            if (projectRow <= 0) continue;

            for (ChatGptModels.RemoteFile f : p.files) {
                if (f == null || blank(f.identity())) continue;
                long fileRow = findFileRow(sql, f.identity());
                if (fileRow <= 0) continue;
                ContentValues link = new ContentValues();
                link.put("project_row_id", projectRow);
                link.put("file_row_id", fileRow);
                sql.insertWithOnConflict("project_files", null, link, SQLiteDatabase.CONFLICT_IGNORE);
            }

            // Keep project roots visible in the current UI while the UI migrates to projects table.
            insertLegacyLink(sql, "Projects", clean(p.name, "Project"),
                    "https://chatgpt.com/g/" + p.id.trim());
        }
    }

    void replaceArchived(SQLiteDatabase sql, List<ChatGptModels.RemoteConversation> archived) {
        sql.delete("conversations", "account_id=?", new String[]{Long.toString(accountId)});
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Archived"});
        if (archived == null) return;

        for (ChatGptModels.RemoteConversation c : archived) {
            if (c == null || blank(c.id)) continue;
            ContentValues v = new ContentValues();
            v.put("account_id", accountId);
            v.put("conversation_id", c.id.trim());
            v.put("title", clean(c.title, "ChatGPT chat"));
            v.put("archived", c.archived ? 1 : 0);
            v.put("updated_at", Math.max(0, c.updatedAt));
            sql.insertWithOnConflict("conversations", null, v, SQLiteDatabase.CONFLICT_REPLACE);

            insertLegacyLink(sql, "Archived", clean(c.title, "ChatGPT chat"),
                    "https://chatgpt.com/c/" + c.id.trim());
        }
    }

    private ContentValues fileValues(ChatGptModels.RemoteFile f, String fileId, int sourceFlags) {
        ContentValues v = new ContentValues();
        v.put("library_file_id", nz(f.libraryFileId));
        v.put("name", clean(f.name, "ChatGPT file"));
        String mime = nz(f.mimeType);
        if (mime.isEmpty()) mime = DriveDb.guessMime(clean(f.name, "") + " " + nz(f.rawUrl));
        v.put("mime", mime);
        v.put("size_bytes", Math.max(0, f.sizeBytes));
        v.put("created_at", Math.max(0, f.createdAt));
        v.put("updated_at", f.updatedAt > 0 ? f.updatedAt : System.currentTimeMillis());
        v.put("conversation_id", nz(f.conversationId));
        v.put("message_id", nz(f.messageId));
        v.put("width", Math.max(0, f.width));
        v.put("height", Math.max(0, f.height));
        v.put("source_flags", sourceFlags);
        String ref = f.stableUrl();
        if (blank(ref)) ref = ChatGptApiClient.buildFileRef(fileId, f.conversationId);
        v.put("remote_ref", ref);
        return v;
    }

    private Map<String, ExistingFile> fileRows(SQLiteDatabase sql) {
        HashMap<String, ExistingFile> out = new HashMap<>();
        Cursor c = sql.query("files", new String[]{"id", "file_id", "source_flags"}, "account_id=?",
                new String[]{Long.toString(accountId)}, null, null, null);
        try {
            while (c.moveToNext()) {
                String id = c.getString(1);
                if (!blank(id)) out.put(id, new ExistingFile(c.getLong(0), c.getInt(2)));
            }
        } finally { c.close(); }
        return out;
    }

    private long findFileRow(SQLiteDatabase sql, String fileId) {
        Cursor c = sql.query("files", new String[]{"id"}, "account_id=? AND file_id=?",
                new String[]{Long.toString(accountId), fileId}, null, null, null, "1");
        try { return c.moveToFirst() ? c.getLong(0) : -1; }
        finally { c.close(); }
    }

    private void insertLegacyLink(SQLiteDatabase sql, String category, String name, String url) {
        ContentValues v = new ContentValues();
        v.put("account_id", accountId);
        v.put("category", category);
        v.put("name", name);
        v.put("url", url);
        v.put("mime", DriveActions.LINK_MIME);
        v.put("size_bytes", 0);
        v.put("updated", System.currentTimeMillis());
        sql.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static ArrayList<Long> ids(SQLiteDatabase db, String table, String where, String[] args) {
        ArrayList<Long> out = new ArrayList<>();
        Cursor c = db.query(table, new String[]{"id"}, where, args, null, null, null);
        try { while (c.moveToNext()) out.add(c.getLong(0)); }
        finally { c.close(); }
        return out;
    }

    private static final class ExistingFile {
        final long rowId;
        final int sourceFlags;
        ExistingFile(long rowId, int sourceFlags) {
            this.rowId = rowId;
            this.sourceFlags = sourceFlags;
        }
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String clean(String s, String fallback) {
        String v = nz(s).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) v = fallback;
        return v.length() > 160 ? v.substring(0, 160) : v;
    }
}
