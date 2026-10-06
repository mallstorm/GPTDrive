package com.gptdrive.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Semantic views over the normalized cloud index. Keeps the home out of the old catch-all Library bucket. */
final class DriveCollections {
    static final String FILES = "Files";
    static final String IMAGES = "Images";
    static final String GENERATED = "Generated";
    static final String ARCHIVED = "Archived";
    private static final int SRC_GENERATED_FUTURE = 1 << 4;

    static final class ProjectEntry {
        final long rowId;
        final String projectId;
        final String name;
        final long updated;
        final int files;
        ProjectEntry(long rowId, String projectId, String name, long updated, int files) {
            this.rowId = rowId;
            this.projectId = projectId;
            this.name = name;
            this.updated = updated;
            this.files = files;
        }
    }

    private final DriveDb db;
    DriveCollections(DriveDb db) { this.db = db; }

    long count(long accountId, String collection) {
        SQLiteDatabase sql = db.getReadableDatabase();
        if (FILES.equals(collection)) return scalar(sql,
                "SELECT COUNT(*) FROM files WHERE account_id=? AND lower(COALESCE(mime,'')) NOT LIKE 'image/%' AND (source_flags & ?) = 0 AND (source_flags & ?) = 0",
                new String[]{Long.toString(accountId), Integer.toString(ChatGptModels.SRC_IMAGE_GEN), Integer.toString(ChatGptModels.SRC_PROJECT)});
        if (IMAGES.equals(collection)) return scalar(sql,
                "SELECT COUNT(*) FROM files WHERE account_id=? AND (lower(COALESCE(mime,'')) LIKE 'image/%' OR (source_flags & ?)<>0)",
                new String[]{Long.toString(accountId), Integer.toString(ChatGptModels.SRC_IMAGE_GEN)});
        if (GENERATED.equals(collection)) return scalar(sql,
                "SELECT COUNT(*) FROM files WHERE account_id=? AND (source_flags & ?)<>0",
                new String[]{Long.toString(accountId), Integer.toString(ChatGptModels.SRC_IMAGE_GEN | SRC_GENERATED_FUTURE)});
        if (ARCHIVED.equals(collection)) return scalar(sql,
                "SELECT COUNT(DISTINCT f.id) FROM files f JOIN conversations c ON c.account_id=f.account_id AND c.conversation_id=f.conversation_id WHERE f.account_id=? AND c.archived=1",
                new String[]{Long.toString(accountId)});
        return 0;
    }

    long countProjects(long accountId) {
        return scalar(db.getReadableDatabase(), "SELECT COUNT(*) FROM projects WHERE account_id=?", new String[]{Long.toString(accountId)});
    }

    long countArchivedChats(long accountId) {
        return scalar(db.getReadableDatabase(), "SELECT COUNT(*) FROM conversations WHERE account_id=? AND archived=1", new String[]{Long.toString(accountId)});
    }

    List<DriveItem> list(long accountId, String collection, int limit, int offset) {
        SQLiteDatabase sql = db.getReadableDatabase();
        String where;
        String[] args;
        String tables = "files f";
        if (FILES.equals(collection)) {
            where = "f.account_id=? AND lower(COALESCE(f.mime,'')) NOT LIKE 'image/%' AND (f.source_flags & ?) = 0 AND (f.source_flags & ?) = 0";
            args = new String[]{Long.toString(accountId), Integer.toString(ChatGptModels.SRC_IMAGE_GEN), Integer.toString(ChatGptModels.SRC_PROJECT)};
        } else if (IMAGES.equals(collection)) {
            where = "f.account_id=? AND (lower(COALESCE(f.mime,'')) LIKE 'image/%' OR (f.source_flags & ?)<>0)";
            args = new String[]{Long.toString(accountId), Integer.toString(ChatGptModels.SRC_IMAGE_GEN)};
        } else if (GENERATED.equals(collection)) {
            where = "f.account_id=? AND (f.source_flags & ?)<>0";
            args = new String[]{Long.toString(accountId), Integer.toString(ChatGptModels.SRC_IMAGE_GEN | SRC_GENERATED_FUTURE)};
        } else if (ARCHIVED.equals(collection)) {
            tables = "files f JOIN conversations c ON c.account_id=f.account_id AND c.conversation_id=f.conversation_id";
            where = "f.account_id=? AND c.archived=1";
            args = new String[]{Long.toString(accountId)};
        } else return new ArrayList<>();
        return queryFiles(sql, tables, where, args, collection, limit, offset);
    }

    List<ProjectEntry> projects(long accountId) {
        ArrayList<ProjectEntry> out = new ArrayList<>();
        SQLiteDatabase sql = db.getReadableDatabase();
        Cursor c = sql.rawQuery(
                "SELECT p.id,p.project_id,p.name,p.updated_at,COUNT(pf.file_row_id) " +
                        "FROM projects p LEFT JOIN project_files pf ON pf.project_row_id=p.id " +
                        "WHERE p.account_id=? GROUP BY p.id ORDER BY p.updated_at DESC,p.name COLLATE NOCASE",
                new String[]{Long.toString(accountId)});
        try {
            while (c.moveToNext()) out.add(new ProjectEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)));
        } finally { c.close(); }
        return out;
    }

    List<DriveItem> projectFiles(long accountId, long projectRowId, int limit, int offset) {
        SQLiteDatabase sql = db.getReadableDatabase();
        return queryFiles(sql,
                "files f JOIN project_files pf ON pf.file_row_id=f.id JOIN projects p ON p.id=pf.project_row_id",
                "f.account_id=? AND p.account_id=? AND p.id=?",
                new String[]{Long.toString(accountId), Long.toString(accountId), Long.toString(projectRowId)},
                "Projects", limit, offset);
    }

    String projectName(long accountId, long projectRowId) {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT name FROM projects WHERE account_id=? AND id=? LIMIT 1",
                new String[]{Long.toString(accountId), Long.toString(projectRowId)});
        try { return c.moveToFirst() ? c.getString(0) : "Проект"; }
        finally { c.close(); }
    }

    int projectFileCount(long accountId, long projectRowId) {
        return (int) scalar(db.getReadableDatabase(),
                "SELECT COUNT(*) FROM project_files pf JOIN projects p ON p.id=pf.project_row_id WHERE p.account_id=? AND p.id=?",
                new String[]{Long.toString(accountId), Long.toString(projectRowId)});
    }

    private List<DriveItem> queryFiles(SQLiteDatabase sql, String tables, String where, String[] args,
                                       String category, int limit, int offset) {
        ArrayList<DriveItem> out = new ArrayList<>();
        String q = "SELECT DISTINCT f.id,f.account_id,f.name,f.remote_ref,f.mime,f.size_bytes,f.updated_at " +
                "FROM " + tables + " WHERE " + where + " ORDER BY f.updated_at DESC LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset);
        Cursor c = sql.rawQuery(q, args);
        try {
            while (c.moveToNext()) out.add(new DriveItem(-c.getLong(0), c.getLong(1), category,
                    nz(c.getString(2)), nz(c.getString(3)), nz(c.getString(4)), c.getLong(5), c.getLong(6)));
        } finally { c.close(); }
        return out;
    }

    private static long scalar(SQLiteDatabase sql, String query, String[] args) {
        Cursor c = sql.rawQuery(query, args);
        try { return c.moveToFirst() ? c.getLong(0) : 0; }
        finally { c.close(); }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
