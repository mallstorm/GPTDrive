package com.gptdrive.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.CookieManager;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public final class GptDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "gptdrive";
    private static final String[] CATS = {"Library","Images"};
    private DriveDb db;

    @Override public boolean onCreate() { db = new DriveDb(getContext()); return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(resolveRootProjection(projection));
        MatrixCursor.RowBuilder r=c.newRow();
        r.add(DocumentsContract.Root.COLUMN_ROOT_ID,ROOT);
        r.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID,"dir:root");
        r.add(DocumentsContract.Root.COLUMN_TITLE,"GPT Drive");
        r.add(DocumentsContract.Root.COLUMN_SUMMARY,"ChatGPT Library");
        r.add(DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.FLAG_SUPPORTS_SEARCH);
        r.add(DocumentsContract.Root.COLUMN_MIME_TYPES,"*/*");
        r.add(DocumentsContract.Root.COLUMN_ICON,com.gptdrive.app.R.drawable.ic_drive);
        return c;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(resolveDocProjection(projection)); include(c,id); return c;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(resolveDocProjection(projection));
        if ("dir:root".equals(parent)) { for(String cat:CATS) addDir(c,"dir:"+cat,cat); return c; }
        if (parent.startsWith("dir:")) { String cat=parent.substring(4); for(DriveItem x:db.list(cat,500)) addFile(c,x); return c; }
        throw new FileNotFoundException(parent);
    }

    @Override public Cursor querySearchDocuments(String rootId, String query, String[] projection) {
        MatrixCursor c=new MatrixCursor(resolveDocProjection(projection)); String q=query==null?"":query.toLowerCase(); for(String cat:CATS) for(DriveItem x:db.list(cat,500)) if(x.name.toLowerCase().contains(q)) addFile(c,x); return c;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if(!documentId.startsWith("file:")) throw new FileNotFoundException(documentId);
        long id=Long.parseLong(documentId.substring(5)); DriveItem item=db.byId(id); if(item==null) throw new FileNotFoundException(documentId);
        try {
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(() -> stream(item, pipe[1]), "gptdrive-stream").start();
            return pipe[0];
        } catch(Exception e){ throw new FileNotFoundException(e.getMessage()); }
    }

    private void stream(DriveItem item, ParcelFileDescriptor writeSide) {
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            HttpURLConnection h=(HttpURLConnection)new URL(item.url).openConnection(); h.setInstanceFollowRedirects(true); h.setConnectTimeout(15000); h.setReadTimeout(30000);
            String cookie=CookieManager.getInstance().getCookie(item.url); if(cookie!=null)h.setRequestProperty("Cookie",cookie); h.setRequestProperty("User-Agent","Mozilla/5.0 Android GPTDrive/0.1");
            try(java.io.InputStream in=h.getInputStream()){ byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1)out.write(b,0,n); }
            h.disconnect();
        } catch(Exception ignored) { try { writeSide.close(); } catch(Exception ignored2){} }
    }

    private void include(MatrixCursor c,String id) throws FileNotFoundException { if("dir:root".equals(id)){addDir(c,id,"GPT Drive");return;} if(id.startsWith("dir:")){addDir(c,id,id.substring(4));return;} if(id.startsWith("file:")){DriveItem x=db.byId(Long.parseLong(id.substring(5)));if(x!=null){addFile(c,x);return;}} throw new FileNotFoundException(id); }
    private void addDir(MatrixCursor c,String id,String name){MatrixCursor.RowBuilder r=c.newRow();r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,id);r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,name);r.add(DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.MIME_TYPE_DIR);r.add(DocumentsContract.Document.COLUMN_FLAGS,0);r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED,System.currentTimeMillis());}
    private void addFile(MatrixCursor c,DriveItem x){MatrixCursor.RowBuilder r=c.newRow();r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,"file:"+x.id);r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,x.name);r.add(DocumentsContract.Document.COLUMN_MIME_TYPE,x.mime);r.add(DocumentsContract.Document.COLUMN_FLAGS,0);r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED,x.updated);}
    private static String[] resolveRootProjection(String[] p){return p!=null?p:new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_SUMMARY,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES,DocumentsContract.Root.COLUMN_ICON};}
    private static String[] resolveDocProjection(String[] p){return p!=null?p:new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE};}
}
