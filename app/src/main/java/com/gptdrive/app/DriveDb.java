package com.gptdrive.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class DriveDb extends SQLiteOpenHelper {
    private static final String DB = "gpt_drive.db";
    DriveDb(Context c) { super(c, DB, null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, mime TEXT, updated INTEGER NOT NULL, UNIQUE(category,url))");
        db.execSQL("CREATE INDEX idx_items_category ON items(category, updated DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE items RENAME TO items_old");
            onCreate(db);
            db.execSQL("INSERT OR IGNORE INTO items(category,name,url,mime,updated) SELECT category,name,url,mime,updated FROM items_old");
            db.execSQL("DROP TABLE items_old");
        }
    }

    void upsert(String category, String name, String url, String mime) {
        if (url == null || url.trim().isEmpty()) return;
        category = normalizeCategory(category);
        ContentValues v = new ContentValues();
        v.put("category", category);
        v.put("name", cleanName(name, url));
        v.put("url", url);
        v.put("mime", mime == null || mime.isEmpty() ? guessMime(url) : mime);
        v.put("updated", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        int changed = db.update("items", v, "category=? AND url=?", new String[]{category, url});
        if (changed == 0) db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    long count(String category) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM items WHERE category=?", new String[]{normalizeCategory(category)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    List<DriveItem> list(String category, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        String selection = category == null ? null : "category=?";
        String[] args = category == null ? null : new String[]{normalizeCategory(category)};
        Cursor c = getReadableDatabase().query("items", null, selection, args, null, null, "updated DESC", Integer.toString(limit));
        try { while (c.moveToNext()) out.add(from(c)); } finally { c.close(); }
        return out;
    }

    List<DriveItem> listFiles(String category, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("items", null, "category=? AND (mime IS NULL OR mime<>?)", new String[]{normalizeCategory(category), DriveActions.LINK_MIME}, null, null, "updated DESC", Integer.toString(limit));
        try { while (c.moveToNext()) out.add(from(c)); } finally { c.close(); }
        return out;
    }

    DriveItem byId(long id) {
        Cursor c = getReadableDatabase().query("items", null, "id=?", new String[]{Long.toString(id)}, null, null, null, "1");
        try { return c.moveToFirst() ? from(c) : null; } finally { c.close(); }
    }

    private DriveItem from(Cursor c) {
        return new DriveItem(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("category")), c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("url")), c.getString(c.getColumnIndexOrThrow("mime")), c.getLong(c.getColumnIndexOrThrow("updated")));
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
        int q = url.indexOf('?'); String base = q >= 0 ? url.substring(0, q) : url;
        int slash = base.lastIndexOf('/'); String v = slash >= 0 ? base.substring(slash + 1) : base;
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
