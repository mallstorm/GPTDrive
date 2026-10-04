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
    DriveDb(Context c) { super(c, DB, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE, mime TEXT, updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_items_category ON items(category, updated DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    void upsert(String category, String name, String url, String mime) {
        if (url == null || url.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("category", category == null ? "Library" : category);
        v.put("name", cleanName(name, url));
        v.put("url", url);
        v.put("mime", mime == null ? guessMime(url) : mime);
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    List<DriveItem> list(String category, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("items", null, category == null ? null : "category=?", category == null ? null : new String[]{category}, null, null, "updated DESC", Integer.toString(limit));
        try {
            while (c.moveToNext()) out.add(from(c));
        } finally { c.close(); }
        return out;
    }

    DriveItem byId(long id) {
        Cursor c = getReadableDatabase().query("items", null, "id=?", new String[]{Long.toString(id)}, null, null, null, "1");
        try { return c.moveToFirst() ? from(c) : null; } finally { c.close(); }
    }

    private DriveItem from(Cursor c) {
        return new DriveItem(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("category")), c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("url")), c.getString(c.getColumnIndexOrThrow("mime")), c.getLong(c.getColumnIndexOrThrow("updated")));
    }

    private static String cleanName(String name, String url) {
        if (name != null) { name = name.trim(); if (!name.isEmpty() && name.length() <= 160) return name; }
        int q = url.indexOf('?'); String base = q >= 0 ? url.substring(0, q) : url;
        int slash = base.lastIndexOf('/'); String v = slash >= 0 ? base.substring(slash + 1) : base;
        return v.isEmpty() ? "ChatGPT file" : v;
    }

    static String guessMime(String url) {
        String s = url.toLowerCase();
        if (s.contains(".png")) return "image/png";
        if (s.contains(".jpg") || s.contains(".jpeg")) return "image/jpeg";
        if (s.contains(".webp")) return "image/webp";
        if (s.contains(".gif")) return "image/gif";
        if (s.contains(".pdf")) return "application/pdf";
        if (s.contains(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (s.contains(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (s.contains(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (s.contains(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
