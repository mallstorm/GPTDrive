package com.gptdrive.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class DriveDb extends SQLiteOpenHelper {
    private static final String DB = "gpt_drive.db";
    private static final int VERSION = 5;

    private static volatile DriveDb shared;

    /** One helper (and one connection pool) for the whole process. */
    static DriveDb get(Context c) {
        DriveDb d = shared;
        if (d == null) {
            synchronized (DriveDb.class) {
                d = shared;
                if (d == null) shared = d = new DriveDb(c.getApplicationContext());
            }
        }
        return d;
    }

    DriveDb(Context c) { super(c, DB, null, VERSION); setWriteAheadLoggingEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        createAccounts(db);
        createItems(db); // legacy/link surface, kept for backwards-compatible UI links
        createNormalizedTables(db);
        seedDefault(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            db.beginTransaction();
            try {
                createAccounts(db);
                seedDefault(db);
                db.execSQL("DROP INDEX IF EXISTS idx_items_category");
                db.execSQL("ALTER TABLE items RENAME TO items_legacy");
                createItems(db);
                db.execSQL("INSERT OR IGNORE INTO items(account_id,category,name,url,mime,updated) SELECT 1,category,name,url,mime,updated FROM items_legacy");
                db.execSQL("DROP TABLE items_legacy");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        if (oldVersion < 4) {
            addColumn(db, "accounts", "plan TEXT");
            addColumn(db, "accounts", "storage_used INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "accounts", "storage_quota INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "accounts", "last_sync INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "items", "size_bytes INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 5) {
            db.beginTransaction();
            try {
                createNormalizedTables(db);
                migrateLegacyFileRows(db);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }

    private static void addColumn(SQLiteDatabase db, String table, String definition) {
        try { db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + definition); }
        catch (Exception ignored) {}
    }

    private void createAccounts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS accounts(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "label TEXT NOT NULL," +
                "email TEXT," +
                "plan TEXT," +
                "cookie_chatgpt TEXT," +
                "cookie_auth TEXT," +
                "cookie_files TEXT," +
                "storage_used INTEGER NOT NULL DEFAULT 0," +
                "storage_quota INTEGER NOT NULL DEFAULT 0," +
                "last_sync INTEGER NOT NULL DEFAULT 0," +
                "updated INTEGER NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_accounts_active ON accounts(active)");
    }

    private void createItems(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS items(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_id INTEGER NOT NULL," +
                "category TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "url TEXT NOT NULL," +
                "mime TEXT," +
                "size_bytes INTEGER NOT NULL DEFAULT 0," +
                "updated INTEGER NOT NULL," +
                "UNIQUE(account_id,category,url))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_account_category ON items(account_id,category,updated DESC)");
    }

    private void createNormalizedTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS files(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_id INTEGER NOT NULL," +
                "file_id TEXT NOT NULL," +
                "library_file_id TEXT," +
                "name TEXT NOT NULL," +
                "mime TEXT," +
                "size_bytes INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "conversation_id TEXT," +
                "message_id TEXT," +
                "width INTEGER NOT NULL DEFAULT 0," +
                "height INTEGER NOT NULL DEFAULT 0," +
                "source_flags INTEGER NOT NULL DEFAULT 0," +
                "remote_ref TEXT NOT NULL," +
                "UNIQUE(account_id,file_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_account_updated ON files(account_id,updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_account_mime ON files(account_id,mime)");

        db.execSQL("CREATE TABLE IF NOT EXISTS projects(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_id INTEGER NOT NULL," +
                "project_id TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(account_id,project_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS project_files(" +
                "project_row_id INTEGER NOT NULL," +
                "file_row_id INTEGER NOT NULL," +
                "UNIQUE(project_row_id,file_row_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_project_files_project ON project_files(project_row_id)");

        db.execSQL("CREATE TABLE IF NOT EXISTS conversations(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_id INTEGER NOT NULL," +
                "conversation_id TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "archived INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(account_id,conversation_id))");

        db.execSQL("CREATE TABLE IF NOT EXISTS sync_state(" +
                "account_id INTEGER NOT NULL," +
                "surface TEXT NOT NULL," +
                "last_success INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT," +
                "last_count INTEGER NOT NULL DEFAULT 0," +
                "meta TEXT," +
                "PRIMARY KEY(account_id,surface))");
    }

    private void migrateLegacyFileRows(SQLiteDatabase db) {
        Cursor c = db.query("items", null,
                "category IN (?,?) AND (mime IS NULL OR mime<>?)",
                new String[]{"Library", "Images", DriveActions.LINK_MIME},
                null, null, "updated ASC");
        ArrayList<Long> migrated = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                String url = getString(c, "url");
                String fid = ChatGptApiClient.extractFileIdCompat(url);
                if (fid.isEmpty()) continue; // keep unidentifiable legacy row intact
                String cat = getString(c, "category");
                int flag = "Images".equals(cat) ? ChatGptModels.SRC_IMAGE_GEN : ChatGptModels.SRC_LIBRARY;
                long rowId = upsertNormalizedFile(db,
                        c.getLong(c.getColumnIndexOrThrow("account_id")),
                        fid, "", getString(c, "name"), getString(c, "mime"),
                        getLong(c, "size_bytes"), 0, getLong(c, "updated"),
                        ChatGptApiClient.extractConversationId(url), "", 0, 0, flag,
                        ChatGptApiClient.buildFileRef(fid, ChatGptApiClient.extractConversationId(url)));
                if (rowId > 0) migrated.add(c.getLong(c.getColumnIndexOrThrow("id")));
            }
        } finally { c.close(); }
        for (Long id : migrated) db.delete("items", "id=?", new String[]{Long.toString(id)});
    }

    private void seedDefault(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM accounts", null);
        long count;
        try { count = c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
        if (count == 0) {
            ContentValues v = new ContentValues();
            v.put("id", 1);
            v.put("label", "Аккаунт 1");
            v.put("updated", System.currentTimeMillis());
            v.put("active", 1);
            db.insert("accounts", null, v);
        }
    }

    long addAccount(String label) {
        ContentValues v = new ContentValues();
        v.put("label", label == null || label.trim().isEmpty() ? "Новый аккаунт" : label.trim());
        v.put("updated", System.currentTimeMillis());
        v.put("active", 0);
        return getWritableDatabase().insert("accounts", null, v);
    }

    List<AccountProfile> listAccounts() {
        ArrayList<AccountProfile> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("accounts", null, null, null, null, null, "active DESC, updated DESC, id ASC");
        try { while (c.moveToNext()) out.add(accountFrom(c)); } finally { c.close(); }
        if (out.isEmpty()) { seedDefault(getWritableDatabase()); return listAccounts(); }
        return out;
    }

    AccountProfile accountById(long id) {
        Cursor c = getReadableDatabase().query("accounts", null, "id=?", new String[]{Long.toString(id)}, null, null, null, "1");
        try { return c.moveToFirst() ? accountFrom(c) : null; } finally { c.close(); }
    }

    AccountProfile activeAccount() {
        Cursor c = getReadableDatabase().query("accounts", null, "active=1", null, null, null, "id ASC", "1");
        try { if (c.moveToFirst()) return accountFrom(c); } finally { c.close(); }
        List<AccountProfile> all = listAccounts();
        if (all.isEmpty()) return null;
        setActiveAccount(all.get(0).id);
        return accountById(all.get(0).id);
    }

    long activeAccountId() {
        AccountProfile a = activeAccount();
        return a == null ? 1 : a.id;
    }

    void setActiveAccount(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues off = new ContentValues(); off.put("active", 0);
            db.update("accounts", off, null, null);
            ContentValues on = new ContentValues();
            on.put("active", 1); on.put("updated", System.currentTimeMillis());
            db.update("accounts", on, "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void updateAccountIdentity(long id, String name, String email) {
        ContentValues v = new ContentValues();
        if (name != null && !name.trim().isEmpty()) v.put("label", name.trim());
        if (email != null && !email.trim().isEmpty()) v.put("email", email.trim());
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("accounts", v, "id=?", new String[]{Long.toString(id)});
    }

    void updateAccountSubscription(long id, String plan, long usedBytes, long quotaBytes) {
        ContentValues v = new ContentValues();
        if (plan != null && !plan.trim().isEmpty()) v.put("plan", plan.trim());
        if (usedBytes > 0) v.put("storage_used", usedBytes);
        if (quotaBytes > 0) v.put("storage_quota", quotaBytes);
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("accounts", v, "id=?", new String[]{Long.toString(id)});
    }

    void markSynced(long id) {
        ContentValues v = new ContentValues();
        v.put("last_sync", System.currentTimeMillis());
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("accounts", v, "id=?", new String[]{Long.toString(id)});
    }

    void saveCookies(long id, String chatgpt, String auth, String files) {
        ContentValues v = new ContentValues();
        v.put("cookie_chatgpt", sealCookie(id, "chatgpt", chatgpt));
        v.put("cookie_auth", sealCookie(id, "auth", auth));
        v.put("cookie_files", sealCookie(id, "files", files));
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("accounts", v, "id=?", new String[]{Long.toString(id)});
    }

    boolean deleteAccount(long id) {
        List<AccountProfile> all = listAccounts();
        if (all.size() <= 1) return false;
        AccountProfile target = accountById(id);
        if (target == null || target.active) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ArrayList<Long> fileRows = queryIds(db, "files", "account_id=?", new String[]{Long.toString(id)});
            ArrayList<Long> projectRows = queryIds(db, "projects", "account_id=?", new String[]{Long.toString(id)});
            for (Long p : projectRows) db.delete("project_files", "project_row_id=?", new String[]{Long.toString(p)});
            for (Long f : fileRows) db.delete("project_files", "file_row_id=?", new String[]{Long.toString(f)});
            db.delete("projects", "account_id=?", new String[]{Long.toString(id)});
            db.delete("conversations", "account_id=?", new String[]{Long.toString(id)});
            db.delete("sync_state", "account_id=?", new String[]{Long.toString(id)});
            db.delete("files", "account_id=?", new String[]{Long.toString(id)});
            db.delete("items", "account_id=?", new String[]{Long.toString(id)});
            db.delete("accounts", "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    void pruneFalsePositives(long accountId) {
        SQLiteDatabase db = getWritableDatabase();
        String legacyWhere = "account_id=? AND (" +
                "lower(name) IN ('usage','path','content','capabilities','library','storage','metadata','manifest') OR " +
                "lower(name) LIKE 'favicon%' OR lower(name) LIKE '%48%px%' OR " +
                "lower(url) LIKE '%/files/usage%' OR lower(url) LIKE '%/files/path%' OR " +
                "lower(url) LIKE '%/files/capabilities%' OR lower(url) LIKE '%/backend-api/files?%' OR " +
                "lower(url) LIKE '%favicon%' OR lower(url) LIKE '%/assets/%' OR lower(url) LIKE '%/static/%')";
        db.delete("items", legacyWhere, new String[]{Long.toString(accountId)});
        db.delete("files", "account_id=? AND (lower(name) IN ('usage','path','content','capabilities','library','storage','metadata','manifest') OR lower(name) LIKE 'favicon%')",
                new String[]{Long.toString(accountId)});
    }

    // Legacy/link API. File snapshots use replaceRemoteFiles() below.
    void upsert(String category, String name, String url, String mime) { upsert(activeAccountId(), category, name, url, mime, 0); }
    void upsert(long accountId, String category, String name, String url, String mime) { upsert(accountId, category, name, url, mime, 0); }
    void upsert(long accountId, String category, String name, String url, String mime, long sizeBytes) {
        if (url == null || url.trim().isEmpty()) return;
        category = normalizeCategory(category);
        ContentValues v = new ContentValues();
        v.put("account_id", accountId); v.put("category", category);
        v.put("name", cleanName(name, url)); v.put("url", url);
        v.put("mime", mime == null || mime.isEmpty() ? guessMime(url) : mime);
        if (sizeBytes > 0) v.put("size_bytes", sizeBytes);
        v.put("updated", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        int changed = db.update("items", v, "account_id=? AND category=? AND url=?", new String[]{Long.toString(accountId), category, url});
        if (changed == 0) db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void replaceRemoteFiles(SQLiteDatabase sql, long accountId, List<ChatGptModels.RemoteFile> files) {
        sql.delete("files", "account_id=?", new String[]{Long.toString(accountId)});
        // Remove previously normalized legacy categories. Unidentifiable legacy rows survive migration.
        sql.delete("items", "account_id=? AND category IN (?,?) AND lower(url) LIKE 'gptdrive://file/%'",
                new String[]{Long.toString(accountId), "Library", "Images"});
        if (files == null) return;
        for (ChatGptModels.RemoteFile f : files) {
            if (f == null) continue;
            String fid = f.identity();
            if (fid.isEmpty()) continue;
            upsertNormalizedFile(sql, accountId, fid, f.libraryFileId, f.name, f.mimeType,
                    f.sizeBytes, f.createdAt, f.updatedAt, f.conversationId, f.messageId,
                    f.width, f.height, f.sourceFlags, f.stableUrl());
        }
    }

    void replaceProjectsNormalized(SQLiteDatabase sql, long accountId, List<ChatGptModels.RemoteProject> projects) {
        ArrayList<Long> oldProjects = queryIds(sql, "projects", "account_id=?", new String[]{Long.toString(accountId)});
        for (Long id : oldProjects) sql.delete("project_files", "project_row_id=?", new String[]{Long.toString(id)});
        sql.delete("projects", "account_id=?", new String[]{Long.toString(accountId)});
        if (projects == null) return;
        for (ChatGptModels.RemoteProject p : projects) {
            if (p == null || p.id == null || p.id.trim().isEmpty()) continue;
            ContentValues pv = new ContentValues();
            pv.put("account_id", accountId); pv.put("project_id", p.id.trim());
            pv.put("name", cleanName(p.name, p.id)); pv.put("updated_at", System.currentTimeMillis());
            long pr = sql.insert("projects", null, pv);
            if (pr <= 0) continue;
            for (ChatGptModels.RemoteFile f : p.files) {
                if (f == null || f.identity().isEmpty()) continue;
                long fr = fileRowId(sql, accountId, f.identity());
                if (fr <= 0) continue;
                ContentValues link = new ContentValues(); link.put("project_row_id", pr); link.put("file_row_id", fr);
                sql.insertWithOnConflict("project_files", null, link, SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
    }

    void replaceConversationsNormalized(SQLiteDatabase sql, long accountId, List<ChatGptModels.RemoteConversation> conversations) {
        sql.delete("conversations", "account_id=?", new String[]{Long.toString(accountId)});
        if (conversations == null) return;
        for (ChatGptModels.RemoteConversation c : conversations) {
            if (c == null || c.id == null || c.id.trim().isEmpty()) continue;
            ContentValues v = new ContentValues();
            v.put("account_id", accountId); v.put("conversation_id", c.id.trim());
            v.put("title", cleanName(c.title, c.id)); v.put("archived", c.archived ? 1 : 0);
            v.put("updated_at", c.updatedAt);
            sql.insertWithOnConflict("conversations", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    void recordSyncState(long accountId, String surface, boolean success, int count, String message, String meta) {
        ContentValues v = new ContentValues();
        v.put("account_id", accountId); v.put("surface", surface == null ? "unknown" : surface);
        if (success) v.put("last_success", System.currentTimeMillis());
        v.put("last_error", success ? "" : (message == null ? "" : message));
        v.put("last_count", Math.max(0, count)); v.put("meta", meta == null ? "" : meta);
        getWritableDatabase().insertWithOnConflict("sync_state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    long count(String category) { return count(activeAccountId(), category); }
    long count(long accountId, String category) {
        String cat = normalizeCategory(category);
        long normalized = 0;
        if ("Library".equals(cat) || "Images".equals(cat)) {
            String where = "account_id=?" + ("Images".equals(cat) ? " AND (lower(mime) LIKE 'image/%' OR (source_flags & 2)<>0)" : "");
            Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM files WHERE " + where, new String[]{Long.toString(accountId)});
            try { normalized = c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
        }
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM items WHERE account_id=? AND category=?",
                new String[]{Long.toString(accountId), cat});
        try { return normalized + (c.moveToFirst() ? c.getLong(0) : 0); } finally { c.close(); }
    }

    long countAll(long accountId) {
        Cursor a = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM files WHERE account_id=?", new String[]{Long.toString(accountId)});
        long files; try { files = a.moveToFirst() ? a.getLong(0) : 0; } finally { a.close(); }
        Cursor b = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM items WHERE account_id=?", new String[]{Long.toString(accountId)});
        try { return files + (b.moveToFirst() ? b.getLong(0) : 0); } finally { b.close(); }
    }

    long knownStorageBytes(long accountId) {
        Cursor a = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM files WHERE account_id=? AND size_bytes>0", new String[]{Long.toString(accountId)});
        long files; try { files = a.moveToFirst() ? a.getLong(0) : 0; } finally { a.close(); }
        Cursor b = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM (SELECT MAX(size_bytes) AS size_bytes FROM items WHERE account_id=? AND size_bytes>0 GROUP BY url)", new String[]{Long.toString(accountId)});
        try { return files + (b.moveToFirst() ? b.getLong(0) : 0); } finally { b.close(); }
    }

    long knownSizedFiles(long accountId) {
        Cursor a = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM files WHERE account_id=? AND size_bytes>0", new String[]{Long.toString(accountId)});
        long files; try { files = a.moveToFirst() ? a.getLong(0) : 0; } finally { a.close(); }
        Cursor b = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM (SELECT url FROM items WHERE account_id=? AND size_bytes>0 GROUP BY url)", new String[]{Long.toString(accountId)});
        try { return files + (b.moveToFirst() ? b.getLong(0) : 0); } finally { b.close(); }
    }

    List<DriveItem> list(String category, int limit) { return list(activeAccountId(), category, limit); }
    List<DriveItem> list(long accountId, String category, int limit) {
        String cat = category == null ? null : normalizeCategory(category);
        ArrayList<DriveItem> out = new ArrayList<>();
        if (cat == null || "Library".equals(cat) || "Images".equals(cat)) {
            String selection = "account_id=?";
            if ("Images".equals(cat)) selection += " AND (lower(mime) LIKE 'image/%' OR (source_flags & 2)<>0)";
            Cursor f = getReadableDatabase().query("files", null, selection, new String[]{Long.toString(accountId)}, null, null, "updated_at DESC", Integer.toString(limit));
            try { while (f.moveToNext() && out.size() < limit) out.add(fromFile(f, "Images".equals(cat) ? "Images" : "Library")); }
            finally { f.close(); }
        }
        if (out.size() < limit) {
            String selection = cat == null ? "account_id=?" : "account_id=? AND category=?";
            String[] args = cat == null ? new String[]{Long.toString(accountId)} : new String[]{Long.toString(accountId), cat};
            Cursor c = getReadableDatabase().query("items", null, selection, args, null, null, "updated DESC", Integer.toString(limit - out.size()));
            try { while (c.moveToNext() && out.size() < limit) out.add(from(c)); } finally { c.close(); }
        }
        return out;
    }

    List<DriveItem> listDistinct(long accountId, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        Cursor f = getReadableDatabase().query("files", null, "account_id=?", new String[]{Long.toString(accountId)}, null, null, "updated_at DESC", Integer.toString(limit));
        try {
            while (f.moveToNext() && out.size() < limit) {
                DriveItem x = fromFile(f, "Library"); out.add(x); seen.add(x.url);
            }
        } finally { f.close(); }
        if (out.size() < limit) {
            Cursor c = getReadableDatabase().query("items", null, "account_id=?", new String[]{Long.toString(accountId)}, null, null, "updated DESC", Integer.toString(Math.max(limit * 4, limit)));
            try {
                while (c.moveToNext() && out.size() < limit) {
                    DriveItem x = from(c);
                    if (x.url == null || seen.add(x.url)) out.add(x);
                }
            } finally { c.close(); }
        }
        return out;
    }

    List<DriveItem> listFiles(String category, int limit) { return listFiles(activeAccountId(), category, limit); }
    List<DriveItem> listFiles(long accountId, String category, int limit) {
        String cat = normalizeCategory(category);
        ArrayList<DriveItem> out = new ArrayList<>();
        if ("Library".equals(cat) || "Images".equals(cat)) {
            String selection = "account_id=?" + ("Images".equals(cat) ? " AND (lower(mime) LIKE 'image/%' OR (source_flags & 2)<>0)" : "");
            Cursor f = getReadableDatabase().query("files", null, selection, new String[]{Long.toString(accountId)}, null, null, "updated_at DESC", Integer.toString(limit));
            try { while (f.moveToNext() && out.size() < limit) out.add(fromFile(f, cat)); } finally { f.close(); }
        }
        if (out.size() < limit) {
            Cursor c = getReadableDatabase().query("items", null,
                    "account_id=? AND category=? AND (mime IS NULL OR mime<>?)",
                    new String[]{Long.toString(accountId), cat, DriveActions.LINK_MIME},
                    null, null, "updated DESC", Integer.toString(limit - out.size()));
            try { while (c.moveToNext() && out.size() < limit) out.add(from(c)); } finally { c.close(); }
        }
        return out;
    }

    DriveItem byFileId(long accountId, String fileId) {
        if (fileId == null || fileId.trim().isEmpty()) return null;
        Cursor c = getReadableDatabase().query("files", null, "account_id=? AND file_id=?",
                new String[]{Long.toString(accountId), fileId.trim()}, null, null, null, "1");
        try { return c.moveToFirst() ? fromFile(c, "Library") : null; }
        finally { c.close(); }
    }

    DriveItem byId(long id) {
        if (id < 0) {
            long row = -id;
            Cursor f = getReadableDatabase().query("files", null, "id=?", new String[]{Long.toString(row)}, null, null, null, "1");
            try { return f.moveToFirst() ? fromFile(f, "Library") : null; } finally { f.close(); }
        }
        Cursor c = getReadableDatabase().query("items", null, "id=?", new String[]{Long.toString(id)}, null, null, null, "1");
        try { return c.moveToFirst() ? from(c) : null; } finally { c.close(); }
    }

    private long upsertNormalizedFile(SQLiteDatabase db, long accountId, String fileId, String libraryFileId,
                                      String name, String mime, long size, long created, long updated,
                                      String conversationId, String messageId, int width, int height,
                                      int sourceFlags, String remoteRef) {
        if (fileId == null || fileId.trim().isEmpty()) return -1;
        long existing = fileRowId(db, accountId, fileId);
        if (existing > 0) {
            Cursor c = db.query("files", new String[]{"source_flags","name","mime","size_bytes","created_at","updated_at","conversation_id","message_id","width","height","library_file_id","remote_ref"},
                    "id=?", new String[]{Long.toString(existing)}, null, null, null, "1");
            int oldFlags = 0; String oldName="", oldMime="", oldConv="", oldMsg="", oldLib="", oldRef=""; long oldSize=0, oldCreated=0, oldUpdated=0; int oldW=0, oldH=0;
            try { if (c.moveToFirst()) { oldFlags=getInt(c,"source_flags"); oldName=getString(c,"name"); oldMime=getString(c,"mime"); oldSize=getLong(c,"size_bytes"); oldCreated=getLong(c,"created_at"); oldUpdated=getLong(c,"updated_at"); oldConv=getString(c,"conversation_id"); oldMsg=getString(c,"message_id"); oldW=getInt(c,"width"); oldH=getInt(c,"height"); oldLib=getString(c,"library_file_id"); oldRef=getString(c,"remote_ref"); } } finally { c.close(); }
            ContentValues v = new ContentValues();
            v.put("library_file_id", first(oldLib, libraryFileId));
            v.put("name", preferName(oldName, name)); v.put("mime", first(oldMime, mime));
            v.put("size_bytes", oldSize > 0 ? oldSize : Math.max(0, size));
            v.put("created_at", oldCreated > 0 ? oldCreated : Math.max(0, created));
            v.put("updated_at", Math.max(oldUpdated, updated));
            v.put("conversation_id", first(oldConv, conversationId)); v.put("message_id", first(oldMsg, messageId));
            v.put("width", oldW > 0 ? oldW : Math.max(0, width)); v.put("height", oldH > 0 ? oldH : Math.max(0, height));
            v.put("source_flags", oldFlags | sourceFlags); v.put("remote_ref", first(remoteRef, oldRef));
            db.update("files", v, "id=?", new String[]{Long.toString(existing)});
            return existing;
        }
        ContentValues v = new ContentValues();
        v.put("account_id", accountId); v.put("file_id", fileId.trim()); v.put("library_file_id", nz(libraryFileId));
        v.put("name", cleanName(name, remoteRef)); v.put("mime", mime == null || mime.isEmpty() ? guessMime(name + " " + remoteRef) : mime);
        v.put("size_bytes", Math.max(0, size)); v.put("created_at", Math.max(0, created)); v.put("updated_at", Math.max(updated, System.currentTimeMillis()));
        v.put("conversation_id", nz(conversationId)); v.put("message_id", nz(messageId)); v.put("width", Math.max(0,width)); v.put("height", Math.max(0,height));
        v.put("source_flags", sourceFlags); v.put("remote_ref", remoteRef == null || remoteRef.isEmpty() ? ChatGptApiClient.buildFileRef(fileId, conversationId) : remoteRef);
        return db.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private long fileRowId(SQLiteDatabase db, long accountId, String fileId) {
        Cursor c = db.query("files", new String[]{"id"}, "account_id=? AND file_id=?", new String[]{Long.toString(accountId), fileId}, null, null, null, "1");
        try { return c.moveToFirst() ? c.getLong(0) : -1; } finally { c.close(); }
    }

    private AccountProfile accountFrom(Cursor c) {
        return new AccountProfile(
                c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("label")),
                c.getString(c.getColumnIndexOrThrow("email")), getString(c, "plan"),
                openCookie(c.getLong(c.getColumnIndexOrThrow("id")), "chatgpt", getString(c, "cookie_chatgpt")),
                openCookie(c.getLong(c.getColumnIndexOrThrow("id")), "auth", getString(c, "cookie_auth")),
                openCookie(c.getLong(c.getColumnIndexOrThrow("id")), "files", getString(c, "cookie_files")),
                getLong(c, "storage_used"), getLong(c, "storage_quota"), getLong(c, "last_sync"),
                c.getLong(c.getColumnIndexOrThrow("updated")), c.getInt(c.getColumnIndexOrThrow("active")) == 1);
    }

    private DriveItem from(Cursor c) {
        return new DriveItem(c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("account_id")),
                c.getString(c.getColumnIndexOrThrow("category")), c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("url")), c.getString(c.getColumnIndexOrThrow("mime")),
                getLong(c, "size_bytes"), c.getLong(c.getColumnIndexOrThrow("updated")));
    }

    private DriveItem fromFile(Cursor c, String category) {
        long row = c.getLong(c.getColumnIndexOrThrow("id"));
        return new DriveItem(-row, c.getLong(c.getColumnIndexOrThrow("account_id")), category,
                getString(c,"name"), getString(c,"remote_ref"), getString(c,"mime"), getLong(c,"size_bytes"),
                getLong(c,"updated_at"), getString(c,"file_id"), getString(c,"library_file_id"),
                getLong(c,"created_at"));
    }

    private static String sealCookie(long accountId, String scope, String value) {
        String plain = value == null ? "" : value;
        if (plain.isEmpty()) return "";
        try { return SecureJsonStore.seal("GPTDrive.Cookie." + accountId + "." + scope + ".v1", plain); }
        catch (Exception e) { throw new IllegalStateException("Не удалось защитить cookies", e); }
    }

    private static String openCookie(long accountId, String scope, String value) {
        try { return SecureJsonStore.open("GPTDrive.Cookie." + accountId + "." + scope + ".v1", value); }
        catch (Exception e) { return ""; }
    }

    private static ArrayList<Long> queryIds(SQLiteDatabase db, String table, String where, String[] args) {
        ArrayList<Long> out = new ArrayList<>(); Cursor c = db.query(table, new String[]{"id"}, where, args, null, null, null);
        try { while (c.moveToNext()) out.add(c.getLong(0)); } finally { c.close(); } return out;
    }
    private static String getString(Cursor c, String column) { int i=c.getColumnIndex(column); return i<0||c.isNull(i)?"":c.getString(i); }
    private static long getLong(Cursor c, String column) { int i=c.getColumnIndex(column); return i<0||c.isNull(i)?0:c.getLong(i); }
    private static int getInt(Cursor c, String column) { int i=c.getColumnIndex(column); return i<0||c.isNull(i)?0:c.getInt(i); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String first(String a, String b) { return a!=null&&!a.trim().isEmpty()?a:(b==null?"":b); }
    private static String preferName(String oldName, String next) {
        String o=nz(oldName).trim(), n=nz(next).trim();
        if (o.isEmpty() || o.equalsIgnoreCase("ChatGPT file") || o.equalsIgnoreCase("ChatGPT image")) return n.isEmpty()?o:n;
        return o;
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isEmpty()) return "Library";
        if (category.equals("Images") || category.equals("Projects") || category.equals("Archived")) return category;
        return "Library";
    }

    private static String cleanName(String name, String url) {
        if (name != null) {
            name = name.replace('\n', ' ').trim().replaceAll("\\s+", " ");
            if (!name.isEmpty()) return name.length() > 160 ? name.substring(0, 160) : name;
        }
        String u = url == null ? "" : url; int q=u.indexOf('?'); String base=q>=0?u.substring(0,q):u; int slash=base.lastIndexOf('/');
        String v=slash>=0?base.substring(slash+1):base; return v.isEmpty()?"ChatGPT item":(v.length()>160?v.substring(0,160):v);
    }

    static String guessMime(String url) {
        String s = url == null ? "" : url.toLowerCase();
        if (s.contains(".png")) return "image/png"; if (s.contains(".jpg") || s.contains(".jpeg")) return "image/jpeg";
        if (s.contains(".webp")) return "image/webp"; if (s.contains(".gif")) return "image/gif"; if (s.contains(".heic")) return "image/heic"; if (s.contains(".avif")) return "image/avif";
        if (s.contains(".pdf")) return "application/pdf"; if (s.contains(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (s.contains(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; if (s.contains(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (s.contains(".zip")) return "application/zip"; if (s.contains(".json")) return "application/json"; if (s.contains(".txt") || s.contains(".md")) return "text/plain";
        return "application/octet-stream";
    }
}
