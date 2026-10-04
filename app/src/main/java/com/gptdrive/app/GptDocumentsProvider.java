package com.gptdrive.app;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.CookieManager;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class GptDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "gptdrive";
    private static final String[] CATS = {"Library", "Images", "Projects", "Archived"};
    private DriveDb db;

    @Override public boolean onCreate() {
        db = new DriveDb(getContext());
        return true;
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(resolveRootProjection(projection));
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT);
        r.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "dir:root");
        r.add(DocumentsContract.Root.COLUMN_TITLE, "GPT Drive");
        r.add(DocumentsContract.Root.COLUMN_SUMMARY, "ChatGPT Library");
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
        if ("dir:root".equals(parent)) {
            for (String cat : CATS) addDir(c, "dir:" + cat, cat);
            return c;
        }
        if (parent.startsWith("dir:")) {
            String cat = parent.substring(4);
            for (DriveItem x : db.listFiles(cat, 1000)) addFile(c, x);
            return c;
        }
        throw new FileNotFoundException(parent);
    }

    @Override public Cursor querySearchDocuments(String rootId, String query, String[] projection) {
        MatrixCursor c = new MatrixCursor(resolveDocProjection(projection));
        String q = query == null ? "" : query.toLowerCase();
        for (String cat : CATS) {
            for (DriveItem x : db.listFiles(cat, 1000)) {
                if (x.name.toLowerCase().contains(q)) addFile(c, x);
            }
        }
        return c;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
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
        long id;
        try { id = Long.parseLong(documentId.substring(5)); }
        catch (Exception e) { throw new FileNotFoundException(documentId); }
        DriveItem item = db.byId(id);
        if (item == null || DriveActions.isLink(item)) throw new FileNotFoundException(documentId);
        return item;
    }

    private void stream(DriveItem item, ParcelFileDescriptor writeSide) {
        HttpURLConnection h = null;
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            h = (HttpURLConnection) new URL(item.url).openConnection();
            h.setInstanceFollowRedirects(true);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            String cookie = CookieManager.getInstance().getCookie(item.url);
            if (cookie != null) h.setRequestProperty("Cookie", cookie);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 Android GPTDrive/0.2");
            try (java.io.InputStream in = h.getInputStream()) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
        } catch (Exception ignored) {
            try { writeSide.close(); } catch (Exception ignored2) {}
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private void include(MatrixCursor c, String id) throws FileNotFoundException {
        if ("dir:root".equals(id)) { addDir(c, id, "GPT Drive"); return; }
        if (id.startsWith("dir:")) { addDir(c, id, id.substring(4)); return; }
        addFile(c, resolveFile(id));
    }

    private void addDir(MatrixCursor c, String id, String name) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        r.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis());
    }

    private void addFile(MatrixCursor c, DriveItem x) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "file:" + x.id);
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, x.name);
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, x.mime);
        int flags = DriveActions.isImage(x) ? DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL : 0;
        r.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, x.updated);
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
