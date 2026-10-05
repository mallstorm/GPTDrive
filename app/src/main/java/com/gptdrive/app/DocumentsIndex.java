package com.gptdrive.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Read-only adapter from normalized storage to Android DocumentsProvider hierarchy. */
final class DocumentsIndex {
    static final class ProjectDoc {
        final long rowId, accountId, updated;
        final String projectId, name;
        ProjectDoc(long rowId, long accountId, String projectId, String name, long updated) {
            this.rowId = rowId; this.accountId = accountId; this.projectId = nz(projectId);
            this.name = clean(name, "Project"); this.updated = updated;
        }
    }

    static final class ConversationDoc {
        final long rowId, accountId, updated;
        final String conversationId, title;
        ConversationDoc(long rowId, long accountId, String conversationId, String title, long updated) {
            this.rowId = rowId; this.accountId = accountId; this.conversationId = nz(conversationId);
            this.title = clean(title, "ChatGPT chat"); this.updated = updated;
        }
        String chatUrl() { return "https://chatgpt.com/c/" + conversationId; }
    }

    private final DriveDb db;
    DocumentsIndex(DriveDb db) { this.db = db; }

    List<ProjectDoc> projects(long accountId, int limit) {
        ArrayList<ProjectDoc> out = new ArrayList<>();
        Cursor c = db.getReadableDatabase().query("projects", null, "account_id=?",
                new String[]{Long.toString(accountId)}, null, null, "updated_at DESC", Integer.toString(limit));
        try {
            while (c.moveToNext()) out.add(project(c));
        } finally { c.close(); }
        return out;
    }

    ProjectDoc project(long accountId, long rowId) {
        Cursor c = db.getReadableDatabase().query("projects", null, "account_id=? AND id=?",
                new String[]{Long.toString(accountId), Long.toString(rowId)}, null, null, null, "1");
        try { return c.moveToFirst() ? project(c) : null; }
        finally { c.close(); }
    }

    List<DriveItem> projectFiles(long accountId, long projectRowId, int limit) {
        ArrayList<DriveItem> out = new ArrayList<>();
        SQLiteDatabase sql = db.getReadableDatabase();
        String query = "SELECT f.id,f.account_id,f.name,f.remote_ref,f.mime,f.size_bytes,f.updated_at " +
                "FROM project_files pf JOIN files f ON f.id=pf.file_row_id " +
                "WHERE pf.project_row_id=? AND f.account_id=? ORDER BY f.updated_at DESC LIMIT ?";
        Cursor c = sql.rawQuery(query, new String[]{Long.toString(projectRowId), Long.toString(accountId), Integer.toString(limit)});
        try {
            while (c.moveToNext()) {
                out.add(new DriveItem(-c.getLong(0), c.getLong(1), "Projects",
                        c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6)));
            }
        } finally { c.close(); }
        return out;
    }

    List<ConversationDoc> archived(long accountId, int limit) {
        ArrayList<ConversationDoc> out = new ArrayList<>();
        Cursor c = db.getReadableDatabase().query("conversations", null, "account_id=? AND archived=1",
                new String[]{Long.toString(accountId)}, null, null, "updated_at DESC", Integer.toString(limit));
        try {
            while (c.moveToNext()) out.add(conversation(c));
        } finally { c.close(); }
        return out;
    }

    ConversationDoc conversation(long accountId, long rowId) {
        Cursor c = db.getReadableDatabase().query("conversations", null, "account_id=? AND id=?",
                new String[]{Long.toString(accountId), Long.toString(rowId)}, null, null, null, "1");
        try { return c.moveToFirst() ? conversation(c) : null; }
        finally { c.close(); }
    }

    private static ProjectDoc project(Cursor c) {
        return new ProjectDoc(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getLong(c.getColumnIndexOrThrow("account_id")),
                str(c, "project_id"), str(c, "name"), lng(c, "updated_at"));
    }

    private static ConversationDoc conversation(Cursor c) {
        return new ConversationDoc(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getLong(c.getColumnIndexOrThrow("account_id")),
                str(c, "conversation_id"), str(c, "title"), lng(c, "updated_at"));
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col); return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }
    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col); return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String clean(String s, String fallback) {
        String v = nz(s).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) v = fallback;
        return v.length() > 160 ? v.substring(0, 160) : v;
    }
}
