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
    /** Row ids for the whole sync; loaded once instead of once per page. */
    private Map<String, ExistingFile> rowCache;

    NormalizedIndexRepository(DriveDb db, long accountId) {
        this.db = db;
        this.accountId = accountId;
    }

    /** Adds or refreshes one received page without deleting rows from an unfinished sync. */
    void mergeFiles(SQLiteDatabase sql, List<ChatGptModels.RemoteFile> page) {
        if (page == null || page.isEmpty()) return;
        if (rowCache == null) rowCache = fileRows(sql);
        Map<String, ExistingFile> existing = rowCache;
        for (ChatGptModels.RemoteFile f : page) {
            if (f == null || blank(f.identity())) continue;
            String fileId = f.identity().trim();
            ExistingFile old = existing.get(fileId);
            int flags = f.sourceFlags | (old == null ? 0 : old.sourceFlags);
            ContentValues v = fileValues(f, fileId, flags);
            if (old != null && old.rowId > 0) {
                sql.update("files", v, "id=?", new String[]{Long.toString(old.rowId)});
            } else {
                v.put("account_id", accountId);
                v.put("file_id", fileId);
                long row = sql.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                if (row > 0) existing.put(fileId, new ExistingFile(row, flags));
            }
        }
        sql.delete("items",
                "account_id=? AND category IN (?,?) AND lower(url) LIKE 'gptdrive://file/%'",
                new String[]{Long.toString(accountId), "Library", "Images"});
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

    /**
     * Upserts projects by (account_id, project_id) so project row ids, and therefore
     * DocumentsProvider ids handed to other apps, survive every sync. Only projects that
     * disappeared from a complete remote listing are removed.
     */
    void replaceProjects(SQLiteDatabase sql, List<ChatGptModels.RemoteProject> projects) {
        Map<String, Long> existing = new HashMap<>();
        Cursor c = sql.query("projects", new String[]{"id", "project_id"}, "account_id=?",
                new String[]{Long.toString(accountId)}, null, null, null);
        try { while (c.moveToNext()) existing.put(c.getString(1), c.getLong(0)); }
        finally { c.close(); }

        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Projects"});
        Set<String> seen = new HashSet<>();
        if (projects != null) {
            for (ChatGptModels.RemoteProject p : projects) {
                if (p == null || blank(p.id)) continue;
                String pid = p.id.trim();
                seen.add(pid);
                ContentValues pv = new ContentValues();
                pv.put("name", clean(p.name, "Project"));
                pv.put("updated_at", System.currentTimeMillis());
                Long row = existing.get(pid);
                long projectRow;
                if (row != null) {
                    sql.update("projects", pv, "id=?", new String[]{Long.toString(row)});
                    sql.delete("project_files", "project_row_id=?", new String[]{Long.toString(row)});
                    projectRow = row;
                } else {
                    pv.put("account_id", accountId);
                    pv.put("project_id", pid);
                    projectRow = sql.insert("projects", null, pv);
                    if (projectRow <= 0) continue;
                }
                for (ChatGptModels.RemoteFile f : p.files) {
                    if (f == null || blank(f.identity())) continue;
                    long fileRow = findFileRow(sql, f.identity().trim());
                    if (fileRow <= 0) continue;
                    ContentValues link = new ContentValues();
                    link.put("project_row_id", projectRow);
                    link.put("file_row_id", fileRow);
                    sql.insertWithOnConflict("project_files", null, link, SQLiteDatabase.CONFLICT_IGNORE);
                }
                insertLegacyLink(sql, "Projects", clean(p.name, "Project"),
                        "https://chatgpt.com/g/" + pid);
            }
        }
        for (Map.Entry<String, Long> e : existing.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            sql.delete("project_files", "project_row_id=?", new String[]{Long.toString(e.getValue())});
            sql.delete("projects", "id=?", new String[]{Long.toString(e.getValue())});
        }
    }

    /** Upserts archived conversations; stale ones are removed only after a complete listing. */
    void replaceArchived(SQLiteDatabase sql, List<ChatGptModels.RemoteConversation> archived) {
        Map<String, Long> existing = new HashMap<>();
        Cursor c = sql.query("conversations", new String[]{"id", "conversation_id"}, "account_id=?",
                new String[]{Long.toString(accountId)}, null, null, null);
        try { while (c.moveToNext()) existing.put(c.getString(1), c.getLong(0)); }
        finally { c.close(); }

        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Archived"});
        Set<String> seen = new HashSet<>();
        if (archived != null) {
            for (ChatGptModels.RemoteConversation x : archived) {
                if (x == null || blank(x.id)) continue;
                String cid = x.id.trim();
                seen.add(cid);
                ContentValues v = new ContentValues();
                v.put("title", clean(x.title, "ChatGPT chat"));
                v.put("archived", x.archived ? 1 : 0);
                v.put("updated_at", Math.max(0, x.updatedAt));
                Long row = existing.get(cid);
                if (row != null) {
                    sql.update("conversations", v, "id=?", new String[]{Long.toString(row)});
                } else {
                    v.put("account_id", accountId);
                    v.put("conversation_id", cid);
                    sql.insertWithOnConflict("conversations", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                }
                insertLegacyLink(sql, "Archived", clean(x.title, "ChatGPT chat"),
                        "https://chatgpt.com/c/" + cid);
            }
        }
        for (Map.Entry<String, Long> e : existing.entrySet()) {
            if (!seen.contains(e.getKey()))
                sql.delete("conversations", "id=?", new String[]{Long.toString(e.getValue())});
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
