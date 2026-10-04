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
    private static final int VERSION = 4;

    DriveDb(Context c) { super(c, DB, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        createAccounts(db);
        createItems(db);
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
            on.put("active", 1);
            on.put("updated", System.currentTimeMillis());
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
        v.put("last_sync", System.currentTimeMillis());
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
        v.put("cookie_chatgpt", chatgpt == null ? "" : chatgpt);
        v.put("cookie_auth", auth == null ? "" : auth);
        v.put("cookie_files", files == null ? "" : files);
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
            db.delete("items", "account_id=?", new String[]{Long.toString(id)});
            db.delete("accounts", "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    void upsert(String category, String name, String url, String mime) {
        upsert(activeAccountId(), category, name, url, mime, 0);
    }

    void upsert(long accountId, String category, String name, String url, String mime) {
        upsert(accountId, category, name, url, mime, 0);
    }

    void upsert(long accountId, String category, String name, String url, String mime, long sizeBytes) {
        if (url == null || url.trim().isEmpty()) return;
        category = normalizeCategory(category);
        ContentValues v = new ContentValues();
        v.put("account_id", accountId);
        v.put("category", category);
        v.put("name", cleanName(name, url));
        v.put("url", url);
        v.put("mime", mime == null || mime.isEmpty() ? guessMime(url) : mime);
        if (sizeBytes > 0) v.put("size_bytes", sizeBytes);
        v.put("updated", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        int changed = db.update("items", v, "account_id=? AND category=? AND url=?", new String[]{Long.toString(accountId), category, url});
        if (changed == 0) db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    long count(String category) { return count(activeAccountId(), category); }

    long count(long accountId, String category) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM items WHERE account_id=? AND category=?",
                new String[]{Long.toString(accountId), normalizeCategory(category)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    long countAll(long accountId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM items WHERE account_id=?", new String[]{Long.toString(accountId)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    long knownStorageBytes(long accountId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(size_bytes),0) FROM (SELECT MAX(size_bytes) AS size_bytes FROM items WHERE account_id=? AND size_bytes>0 GROUP BY url)",
                new String[]{Long.toString(accountId)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    long knownSizedFiles(long accountId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM (SELECT url FROM items WHERE account_id=? AND size_bytes>0 GROUP BY url)",
                new String[]{Long.toString(accountId)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    List<DriveItem> list(String category, int limit) { return list(activeAccountId(), category, limit); }

    List<DriveItem> list(long accountId, String category, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        String selection = category == null ? "account_id=?" : "account_id=? AND category=?";
        String[] args = category == null ? new String[]{Long.toString(accountId)} : new String[]{Long.toString(accountId), normalizeCategory(category)};
        Cursor c = getReadableDatabase().query("items", null, selection, args, null, null, "updated DESC", Integer.toString(limit));
        try { while (c.moveToNext()) out.add(from(c)); } finally { c.close(); }
        return out;
    }

    List<DriveItem> listDistinct(long accountId, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        Cursor c = getReadableDatabase().query("items", null, "account_id=?", new String[]{Long.toString(accountId)}, null, null, "updated DESC", Integer.toString(Math.max(limit * 4, limit)));
        try {
            while (c.moveToNext() && out.size() < limit) {
                DriveItem x = from(c);
                if (x.url == null || seen.add(x.url)) out.add(x);
            }
        } finally { c.close(); }
        return out;
    }

    List<DriveItem> listFiles(String category, int limit) { return listFiles(activeAccountId(), category, limit); }

    List<DriveItem> listFiles(long accountId, String category, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(
                "items", null,
                "account_id=? AND category=? AND (mime IS NULL OR mime<>?)",
                new String[]{Long.toString(accountId), normalizeCategory(category), DriveActions.LINK_MIME},
                null, null, "updated DESC", Integer.toString(limit));
        try { while (c.moveToNext()) out.add(from(c)); } finally { c.close(); }
        return out;
    }

    DriveItem byId(long id) {
        Cursor c = getReadableDatabase().query("items", null, "id=?", new String[]{Long.toString(id)}, null, null, null, "1");
        try { return c.moveToFirst() ? from(c) : null; } finally { c.close(); }
    }

    private AccountProfile accountFrom(Cursor c) {
        return new AccountProfile(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("label")),
                c.getString(c.getColumnIndexOrThrow("email")),
                getString(c, "plan"),
                c.getString(c.getColumnIndexOrThrow("cookie_chatgpt")),
                c.getString(c.getColumnIndexOrThrow("cookie_auth")),
                c.getString(c.getColumnIndexOrThrow("cookie_files")),
                getLong(c, "storage_used"),
                getLong(c, "storage_quota"),
                getLong(c, "last_sync"),
                c.getLong(c.getColumnIndexOrThrow("updated")),
                c.getInt(c.getColumnIndexOrThrow("active")) == 1);
    }

    private DriveItem from(Cursor c) {
        return new DriveItem(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getLong(c.getColumnIndexOrThrow("account_id")),
                c.getString(c.getColumnIndexOrThrow("category")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("url")),
                c.getString(c.getColumnIndexOrThrow("mime")),
                getLong(c, "size_bytes"),
                c.getLong(c.getColumnIndexOrThrow("updated")));
    }

    private static String getString(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long getLong(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
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
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        int slash = base.lastIndexOf('/');
        String v = slash >= 0 ? base.substring(slash + 1) : base;
        return v.isEmpty() ? "ChatGPT item" : (v.length() > 160 ? v.substring(0, 160) : v);
    }

    static String guessMime(String url) {
        String s = url == null ? "" : url.toLowerCase();
        if (s.contains(".png")) return "image/png";
        if (s.contains(".jpg") || s.contains(".jpeg")) return "image/jpeg";
        if (s.contains(".webp")) return "image/webp";
        if (s.contains(".gif")) return "image/gif";
        if (s.contains(".heic")) return "image/heic";
        if (s.contains(".pdf")) return "application/pdf";
        if (s.contains(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (s.contains(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (s.contains(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (s.contains(".zip")) return "application/zip";
        if (s.contains(".json")) return "application/json";
        if (s.contains(".txt") || s.contains(".md")) return "text/plain";
        return "application/octet-stream";
    }
}
