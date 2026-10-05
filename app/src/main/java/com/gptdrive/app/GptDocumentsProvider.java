package com.gptdrive.app;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Base64;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

public final class GptDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "gptdrive";
    private static final String[] CATS = {"Library", "Images", "Projects", "Archived", "Chat Mirror"};
    private static final int MAX_RESULTS = 10000;
    private DriveDb db;
    private DocumentsIndex index;

    @Override public boolean onCreate() {
        db = new DriveDb(getContext());
        index = new DocumentsIndex(db);
        return true;
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(resolveRootProjection(projection));
        AccountProfile a = db.activeAccount();
        if (a == null) return c;

        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT);
        r.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, accountDocId(a.id));
        r.add(DocumentsContract.Root.COLUMN_TITLE, "GPT Drive");
        long known = db.knownStorageBytes(a.id);
        String size = a.hasRemoteQuota()
                ? MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes)
                : (known > 0 ? MainActivity.formatBytes(known) + " индексировано" : "объём —");
        String summary = a.displayName() + "  •  " + a.planLabel() + "  •  " + size;
        if (!a.hasSession()) summary += "  •  Нужен вход";
        r.add(DocumentsContract.Root.COLUMN_SUMMARY, summary);
        r.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_SEARCH);
        r.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        r.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_drive);
        return c;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(resolveDocProjection(projection));
        include(c, id);
        return c;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(resolveDocProjection(projection));
        if (parent.startsWith("acct:")) {
            long accountId = parseLongAfter(parent, "acct:");
            for (String cat : CATS) addDir(c, dirId(accountId, cat), cat, System.currentTimeMillis());
            return c;
        }
        if (parent.startsWith("dir:")) {
            DirRef d = parseDir(parent);
            if ("Projects".equals(d.category)) {
                for (DocumentsIndex.ProjectDoc p : index.projects(d.accountId, MAX_RESULTS)) addProject(c, p);
                return c;
            }
            if ("Archived".equals(d.category)) {
                for (DocumentsIndex.ConversationDoc x : index.archived(d.accountId, MAX_RESULTS)) addConversation(c, x);
                return c;
            }
            if ("Chat Mirror".equals(d.category)) {
                ChatMirrorRepository mirror = new ChatMirrorRepository(getContext(), db, d.accountId);
                for (ChatMirrorRepository.Entry x : mirror.listEntries(MAX_RESULTS)) {
                    if (x.hasSnapshot) addMirror(c, d.accountId, x);
                }
                return c;
            }
            for (DriveItem x : db.listFiles(d.accountId, d.category, MAX_RESULTS)) addFile(c, x);
            return c;
        }
        if (parent.startsWith("project:")) {
            PairRef ref = parsePair(parent, "project:");
            DocumentsIndex.ProjectDoc project = index.project(ref.accountId, ref.rowId);
            if (project == null) throw new FileNotFoundException(parent);
            for (DriveItem x : index.projectFiles(ref.accountId, ref.rowId, MAX_RESULTS)) addFile(c, x);
            return c;
        }
        throw new FileNotFoundException(parent);
    }

    @Override public Cursor querySearchDocuments(String rootId, String query, String[] projection) {
        MatrixCursor c = new MatrixCursor(resolveDocProjection(projection));
        long accountId = db.activeAccountId();
        String q = query == null ? "" : query.toLowerCase();
        if (accountId <= 0) return c;

        HashSet<Long> seen = new HashSet<>();
        for (DriveItem x : db.listDistinct(accountId, MAX_RESULTS)) {
            if (x.name != null && x.name.toLowerCase().contains(q) && seen.add(x.id)) addFile(c, x);
        }
        for (DocumentsIndex.ConversationDoc x : index.archived(accountId, MAX_RESULTS)) {
            if (x.title.toLowerCase().contains(q)) addConversation(c, x);
        }
        ChatMirrorRepository mirror = new ChatMirrorRepository(getContext(), db, accountId);
        for (ChatMirrorRepository.Entry x : mirror.listEntries(MAX_RESULTS)) {
            if (x.hasSnapshot && x.title.toLowerCase().contains(q)) addMirror(c, accountId, x);
        }
        return c;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (documentId.startsWith("chat:")) {
            ChatRef ref = parseChat(documentId);
            ChatMirrorRepository mirror = new ChatMirrorRepository(getContext(), db, ref.accountId);
            ChatMirrorRepository.Entry entry = mirror.entry(ref.conversationId);
            if (entry == null || !entry.hasSnapshot) throw new FileNotFoundException(documentId);
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> streamMirror(mirror, ref.conversationId, pipe[1]), "gptdrive-chat-mirror").start();
                return pipe[0];
            } catch (Exception e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        if (documentId.startsWith("conv:")) {
            PairRef ref = parsePair(documentId, "conv:");
            DocumentsIndex.ConversationDoc conversation = index.conversation(ref.accountId, ref.rowId);
            if (conversation == null) throw new FileNotFoundException(documentId);
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> streamConversation(conversation, pipe[1]), "gptdrive-conversation-link").start();
                return pipe[0];
            } catch (Exception e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }

        DriveItem item = resolveFile(documentId);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> stream(item, pipe[1]), "gptdrive-stream").start();
            return pipe[0];
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        DriveItem item = resolveFile(documentId);
        if (!DriveActions.isImage(item)) throw new FileNotFoundException(documentId);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> stream(item, pipe[1]), "gptdrive-thumb").start();
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private DriveItem resolveFile(String documentId) throws FileNotFoundException {
        if (!documentId.startsWith("file:")) throw new FileNotFoundException(documentId);
        long id = parseLongAfter(documentId, "file:");
        DriveItem item = db.byId(id);
        if (item == null || DriveActions.isLink(item)) throw new FileNotFoundException(documentId);
        return item;
    }

    private void stream(DriveItem item, ParcelFileDescriptor writeSide) {
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide);
             RemoteFileSource.Connection c = RemoteFileSource.open(getContext(), item.accountId, item.url);
             java.io.InputStream in = c.inputStream()) {
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        } catch (Exception ignored) {
            try { writeSide.close(); } catch (Exception ignored2) {}
        }
    }

    private void streamMirror(ChatMirrorRepository mirror, String conversationId, ParcelFileDescriptor writeSide) {
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            String raw = mirror.readRaw(conversationId);
            if (raw == null || raw.isEmpty()) throw new FileNotFoundException(conversationId);
            out.write(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            try { writeSide.close(); } catch (Exception ignored2) {}
        }
    }

    private void streamConversation(DocumentsIndex.ConversationDoc conversation, ParcelFileDescriptor writeSide) {
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            out.write((conversation.chatUrl() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            try { writeSide.close(); } catch (Exception ignored2) {}
        }
    }

    private void include(MatrixCursor c, String id) throws FileNotFoundException {
        if (id.startsWith("acct:")) {
            long accountId = parseLongAfter(id, "acct:");
            AccountProfile a = db.accountById(accountId);
            if (a == null) throw new FileNotFoundException(id);
            addDir(c, id, "GPT Drive", System.currentTimeMillis());
            return;
        }
        if (id.startsWith("dir:")) {
            DirRef d = parseDir(id);
            addDir(c, id, d.category, System.currentTimeMillis());
            return;
        }
        if (id.startsWith("project:")) {
            PairRef ref = parsePair(id, "project:");
            DocumentsIndex.ProjectDoc p = index.project(ref.accountId, ref.rowId);
            if (p == null) throw new FileNotFoundException(id);
            addProject(c, p);
            return;
        }
        if (id.startsWith("conv:")) {
            PairRef ref = parsePair(id, "conv:");
            DocumentsIndex.ConversationDoc x = index.conversation(ref.accountId, ref.rowId);
            if (x == null) throw new FileNotFoundException(id);
            addConversation(c, x);
            return;
        }
        if (id.startsWith("chat:")) {
            ChatRef ref = parseChat(id);
            ChatMirrorRepository.Entry x = new ChatMirrorRepository(getContext(), db, ref.accountId).entry(ref.conversationId);
            if (x == null || !x.hasSnapshot) throw new FileNotFoundException(id);
            addMirror(c, ref.accountId, x);
            return;
        }
        addFile(c, resolveFile(id));
    }

    private void addProject(MatrixCursor c, DocumentsIndex.ProjectDoc p) {
        addDir(c, projectDocId(p.accountId, p.rowId), p.name, p.updated);
    }

    private void addConversation(MatrixCursor c, DocumentsIndex.ConversationDoc x) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, conversationDocId(x.accountId, x.rowId));
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, x.title + ".url");
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "text/uri-list");
        r.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, x.updated);
        r.add(DocumentsContract.Document.COLUMN_SIZE, x.chatUrl().getBytes(StandardCharsets.UTF_8).length + 1);
    }

    private void addMirror(MatrixCursor c, long accountId, ChatMirrorRepository.Entry x) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, chatDocId(accountId, x.conversationId));
        String name = x.displayName();
        if (!x.remotePresent) name = "[local] " + name;
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "application/json");
        r.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        long updated = x.mirroredAt > 0 ? x.mirroredAt : x.updatedAt;
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, updated);
    }

    private void addDir(MatrixCursor c, String id, String name, long updated) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        r.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, updated > 0 ? updated : System.currentTimeMillis());
    }

    private void addFile(MatrixCursor c, DriveItem x) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "file:" + x.id);
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, x.name);
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, x.mime);
        int flags = DriveActions.isImage(x) ? DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL : 0;
        r.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, x.updated);
        if (x.sizeBytes > 0) r.add(DocumentsContract.Document.COLUMN_SIZE, x.sizeBytes);
    }

    private static String accountDocId(long accountId) { return "acct:" + accountId; }
    private static String dirId(long accountId, String category) { return "dir:" + accountId + ":" + category; }
    private static String projectDocId(long accountId, long rowId) { return "project:" + accountId + ":" + rowId; }
    private static String conversationDocId(long accountId, long rowId) { return "conv:" + accountId + ":" + rowId; }
    private static String chatDocId(long accountId, String conversationId) {
        String token = Base64.encodeToString(conversationId.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "chat:" + accountId + ":" + token;
    }

    private static long parseLongAfter(String value, String prefix) throws FileNotFoundException {
        if (value == null || !value.startsWith(prefix)) throw new FileNotFoundException(value);
        try { return Long.parseLong(value.substring(prefix.length())); }
        catch (Exception e) { throw new FileNotFoundException(value); }
    }

    private static PairRef parsePair(String value, String prefix) throws FileNotFoundException {
        if (value == null || !value.startsWith(prefix)) throw new FileNotFoundException(value);
        String rest = value.substring(prefix.length());
        int cut = rest.indexOf(':');
        if (cut <= 0 || cut >= rest.length() - 1) throw new FileNotFoundException(value);
        try {
            return new PairRef(Long.parseLong(rest.substring(0, cut)), Long.parseLong(rest.substring(cut + 1)));
        } catch (Exception e) { throw new FileNotFoundException(value); }
    }

    private static ChatRef parseChat(String value) throws FileNotFoundException {
        if (value == null || !value.startsWith("chat:")) throw new FileNotFoundException(value);
        String rest = value.substring(5);
        int cut = rest.indexOf(':');
        if (cut <= 0 || cut >= rest.length() - 1) throw new FileNotFoundException(value);
        try {
            long accountId = Long.parseLong(rest.substring(0, cut));
            byte[] decoded = Base64.decode(rest.substring(cut + 1), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String conversationId = new String(decoded, StandardCharsets.UTF_8);
            if (conversationId.trim().isEmpty()) throw new IllegalArgumentException();
            return new ChatRef(accountId, conversationId);
        } catch (Exception e) { throw new FileNotFoundException(value); }
    }

    private static DirRef parseDir(String id) throws FileNotFoundException {
        if (id == null || !id.startsWith("dir:")) throw new FileNotFoundException(id);
        String rest = id.substring(4);
        int cut = rest.indexOf(':');
        if (cut <= 0 || cut >= rest.length() - 1) throw new FileNotFoundException(id);
        try {
            long accountId = Long.parseLong(rest.substring(0, cut));
            String category = rest.substring(cut + 1);
            return new DirRef(accountId, category);
        } catch (Exception e) { throw new FileNotFoundException(id); }
    }

    private static final class PairRef {
        final long accountId, rowId;
        PairRef(long accountId, long rowId) { this.accountId = accountId; this.rowId = rowId; }
    }

    private static final class ChatRef {
        final long accountId;
        final String conversationId;
        ChatRef(long accountId, String conversationId) { this.accountId = accountId; this.conversationId = conversationId; }
    }

    private static final class DirRef {
        final long accountId;
        final String category;
        DirRef(long accountId, String category) { this.accountId = accountId; this.category = category; }
    }

    private static String[] resolveRootProjection(String[] p) {
        return p != null ? p : new String[]{
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_ICON};
    }

    private static String[] resolveDocProjection(String[] p) {
        return p != null ? p : new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE};
    }
}
