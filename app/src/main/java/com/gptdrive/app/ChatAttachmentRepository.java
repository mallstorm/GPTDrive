package com.gptdrive.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Durable relation table between Chat Mirror conversations and normalized file identities. */
final class ChatAttachmentRepository {
    private static final String TABLE = "chat_attachments";
    private final DriveDb db;
    private final long accountId;

    ChatAttachmentRepository(DriveDb db, long accountId) {
        this.db = db;
        this.accountId = accountId;
        ensureSchema();
    }

    void ensureSchema() {
        SQLiteDatabase sql = db.getWritableDatabase();
        sql.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + "(" +
                "account_id INTEGER NOT NULL," +
                "conversation_id TEXT NOT NULL," +
                "file_id TEXT NOT NULL," +
                "library_file_id TEXT," +
                "message_id TEXT," +
                "project_id TEXT," +
                "name TEXT NOT NULL," +
                "mime TEXT," +
                "size_bytes INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "asset_pointer TEXT," +
                "width INTEGER NOT NULL DEFAULT 0," +
                "height INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(account_id,conversation_id,file_id))");
        sql.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_attachments_account_file ON " + TABLE +
                "(account_id,file_id)");
        sql.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_attachments_account_project ON " + TABLE +
                "(account_id,project_id)");
        // Keeps the relation table clean even though the core DB predates this feature.
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS trg_chat_attachments_delete_account " +
                "AFTER DELETE ON accounts BEGIN DELETE FROM " + TABLE + " WHERE account_id=OLD.id; END");
    }

    void replaceForConversation(ChatGptModels.RemoteConversation conversation) {
        if (conversation == null || blank(conversation.id)) return;
        String conversationId = conversation.id.trim();
        SQLiteDatabase sql = db.getWritableDatabase();
        sql.beginTransaction();
        try {
            sql.delete(TABLE, "account_id=? AND conversation_id=?",
                    new String[]{Long.toString(accountId), conversationId});
            for (ChatGptModels.RemoteFile f : conversation.attachments) {
                if (f == null || blank(f.identity())) continue;
                ContentValues v = new ContentValues();
                v.put("account_id", accountId);
                v.put("conversation_id", conversationId);
                v.put("file_id", f.identity());
                v.put("library_file_id", nz(f.libraryFileId));
                v.put("message_id", nz(f.messageId));
                v.put("project_id", ChatGptModels.first(f.projectId, conversation.projectId));
                v.put("name", clean(f.name, "ChatGPT file"));
                v.put("mime", nz(f.mimeType));
                v.put("size_bytes", Math.max(0, f.sizeBytes));
                v.put("created_at", Math.max(0, f.createdAt));
                v.put("updated_at", Math.max(f.updatedAt, f.createdAt));
                v.put("asset_pointer", nz(f.assetPointer));
                v.put("width", Math.max(0, f.width));
                v.put("height", Math.max(0, f.height));
                sql.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }
    }

    List<ChatGptModels.RemoteFile> listAll() { return listForConversation(null); }

    List<ChatGptModels.RemoteFile> listForConversation(String conversationId) {
        ensureSchema();
        ArrayList<ChatGptModels.RemoteFile> out = new ArrayList<>();
        String where = "account_id=?";
        ArrayList<String> args = new ArrayList<>();
        args.add(Long.toString(accountId));
        if (!blank(conversationId)) {
            where += " AND conversation_id=?";
            args.add(conversationId.trim());
        }
        Cursor c = db.getReadableDatabase().query(TABLE, null, where,
                args.toArray(new String[0]), null, null, "updated_at DESC, name ASC");
        try {
            while (c.moveToNext()) out.add(from(c));
        } finally { c.close(); }
        return out;
    }

    int count() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id=?",
                new String[]{Long.toString(accountId)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    int projectLinkCount() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id=? AND project_id IS NOT NULL AND project_id<>''",
                new String[]{Long.toString(accountId)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    private ChatGptModels.RemoteFile from(Cursor c) {
        ChatGptModels.RemoteFile f = new ChatGptModels.RemoteFile();
        f.fileId = str(c, "file_id");
        f.libraryFileId = str(c, "library_file_id");
        f.name = clean(str(c, "name"), "ChatGPT file");
        f.mimeType = str(c, "mime");
        f.sizeBytes = lng(c, "size_bytes");
        f.createdAt = lng(c, "created_at");
        f.updatedAt = lng(c, "updated_at");
        f.conversationId = str(c, "conversation_id");
        f.messageId = str(c, "message_id");
        f.projectId = str(c, "project_id");
        f.assetPointer = str(c, "asset_pointer");
        f.width = integer(c, "width");
        f.height = integer(c, "height");
        f.sourceFlags = ChatGptModels.SRC_CONVERSATION;
        return f;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? "" : nz(c.getString(i));
    }
    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
    private static int integer(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getInt(i);
    }
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String clean(String s, String fallback) {
        String v = nz(s).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) v = fallback;
        return v.length() > 240 ? v.substring(0, 240) : v;
    }
}
