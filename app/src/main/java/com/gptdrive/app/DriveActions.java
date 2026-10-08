package com.gptdrive.app;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

final class DriveActions {
    static final String LINK_MIME = "application/x-gptdrive-link";
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private DriveActions() {}

    static boolean isLink(DriveItem item){return item!=null&&LINK_MIME.equals(item.mime);}
    static boolean isImage(DriveItem item){return item!=null&&item.mime!=null&&item.mime.startsWith("image/");}

    static void open(Context context, DriveItem item) {
        if (item == null) return;
        if (isLink(item)) {
            Intent i = new Intent(context, BrowserActivity.class);
            i.putExtra(BrowserActivity.EXTRA_SECTION, item.category);
            i.putExtra(BrowserActivity.EXTRA_URL, item.url);
            i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, item.accountId);
            context.startActivity(i);
            return;
        }
        String name = item.name == null ? "" : item.name.toLowerCase(java.util.Locale.ROOT);
        String mime = item.mime == null ? "" : item.mime.toLowerCase(java.util.Locale.ROOT);
        String preference = context.getSharedPreferences("file-handlers", Context.MODE_PRIVATE)
                .getString("mode", "auto");
        if (!"external".equals(preference) && (isImage(item) ||
                mime.startsWith("image/") || name.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic)$"))) {
            Intent i = new Intent(context, ImageViewerActivity.class);
            i.putExtra(ImageViewerActivity.EXTRA_ID, item.id);
            context.startActivity(i);
            return;
        }
        boolean preview = name.matches(".*\\\\.(md|txt|log|json|xml|yaml|yml|csv|pdf|zip|docx|xlsx|pptx|mp4|webm|3gp|mp3|m4a|wav|ogg)$") ||
                mime.equals("application/pdf") || mime.equals("application/zip") ||
                mime.startsWith("text/") || mime.equals("application/json");
        if (!"external".equals(preference) && preview) {
            Intent i = new Intent(context, FilePreviewActivity.class);
            i.putExtra(FilePreviewActivity.EXTRA_ID, item.id);
            context.startActivity(i);
            return;
        }
        try {
            Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, "file:" + item.id);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime.isEmpty() ? inferredMime(name) : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(i, "Открыть файл"));
        } catch (Exception e) {
            Toast.makeText(context, "Нет приложения для открытия этого формата", Toast.LENGTH_LONG).show();
        }
    }

    private static String inferredMime(String name) {
        if (name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".log")) return "text/plain";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "text/xml";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "text/plain";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        return "*/*";
    }

    static void download(Context context,DriveItem item){
        if(item==null||isLink(item))return;
        Context app=context.getApplicationContext();
        Toast.makeText(context,"Получаю свежую ссылку…",Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try{
                if(Build.VERSION.SDK_INT>=29){
                    streamToDownloads(app,item);
                }else{
                    enqueueLegacyDownload(app,item);
                }
            }catch(Exception e){
                post(app,"Не удалось получить файл: "+shortError(e),Toast.LENGTH_LONG);
            }
        },"gptdrive-download").start();
    }

    private static void streamToDownloads(Context app,DriveItem item)throws Exception{
        String name=item.name==null||item.name.trim().isEmpty()?"ChatGPT-file":safeFileName(item.name);
        String mime=item.mime==null||item.mime.trim().isEmpty()?"application/octet-stream":item.mime;
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri target=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(target==null)throw new java.io.IOException("Не удалось создать файл в Downloads");
        boolean ok=false;
        try(RemoteFileSource.Connection c=RemoteFileSource.open(app,item.accountId,item.url);
            InputStream in=c.inputStream();
            OutputStream out=app.getContentResolver().openOutputStream(target,"w")){
            if(out==null)throw new java.io.IOException("Downloads недоступен");
            byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);out.flush();ok=true;
        }finally{
            if(ok){ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);app.getContentResolver().update(target,done,null,null);}
            else app.getContentResolver().delete(target,null,null);
        }
        post(app,"Скачано: "+name,Toast.LENGTH_SHORT);
    }

    private static void enqueueLegacyDownload(Context app,DriveItem item)throws Exception{
        String resolved=RemoteFileSource.resolve(app,item.accountId,item.url);
        DownloadManager.Request r=new DownloadManager.Request(Uri.parse(resolved));
        r.addRequestHeader("Referer","https://chatgpt.com/");
        r.addRequestHeader("User-Agent","Mozilla/5.0 Android GPTDrive/0.6.4");
        if(resolved.startsWith("https://chatgpt.com/")){
            ChatGptSessionStore.Session s=ChatGptSessionStore.load(app,item.accountId);
            if(s!=null&&s.accessToken!=null&&!s.accessToken.isEmpty()){
                r.addRequestHeader("Authorization","Bearer "+s.accessToken);
                if(s.accountId!=null&&!s.accountId.isEmpty())r.addRequestHeader("chatgpt-account-id",s.accountId);
            }
            String cookie=AccountSession.cookieHeader(app,item.accountId,resolved);
            if(cookie!=null&&!cookie.trim().isEmpty())r.addRequestHeader("Cookie",cookie);
        }
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        String name=item.name==null||item.name.trim().isEmpty()?URLUtil.guessFileName(resolved,null,item.mime):safeFileName(item.name);
        r.setTitle(name);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
        ((DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
        post(app,"Скачивание: "+name,Toast.LENGTH_SHORT);
    }

    private static void post(Context app,String text,int duration){android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());h.post(()->Toast.makeText(app,text,duration).show());}
    static String shortType(DriveItem item){if(item==null)return"";if(isLink(item))return"ChatGPT";String m=item.mime==null?"":item.mime;if(m.startsWith("image/"))return"Изображение";if(m.equals("application/pdf"))return"PDF";if(m.contains("wordprocessingml"))return"DOCX";if(m.contains("spreadsheetml"))return"XLSX";if(m.contains("presentationml"))return"PPTX";if(m.contains("zip"))return"ZIP";if(m.contains("android.package"))return"APK";if(m.equals("application/json"))return"JSON";if(m.startsWith("text/"))return"Текст";return"Файл";}
    private static String safeFileName(String s){String v=s.replaceAll("[\\\\/:*?\"<>|]","_").trim();if(v.length()>140)v=v.substring(0,140);return v.isEmpty()?"ChatGPT-file":v;}
    private static String shortError(Exception e){String m=e==null?"":e.getMessage();return m==null||m.trim().isEmpty()?"ошибка соединения":m;}
}
