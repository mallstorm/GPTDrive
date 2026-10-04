package com.gptdrive.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.webkit.URLUtil;
import android.widget.Toast;

final class DriveActions {
    static final String LINK_MIME = "application/x-gptdrive-link";
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private DriveActions() {}

    static boolean isLink(DriveItem item){return item!=null&&LINK_MIME.equals(item.mime);}
    static boolean isImage(DriveItem item){return item!=null&&item.mime!=null&&item.mime.startsWith("image/");}

    static void open(Context context,DriveItem item){
        if(item==null)return;
        if(isImage(item)){Intent i=new Intent(context,ImageViewerActivity.class);i.putExtra(ImageViewerActivity.EXTRA_ID,item.id);context.startActivity(i);return;}
        if(isLink(item)){Intent i=new Intent(context,BrowserActivity.class);i.putExtra(BrowserActivity.EXTRA_SECTION,item.category);i.putExtra(BrowserActivity.EXTRA_URL,item.url);i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,item.accountId);context.startActivity(i);return;}
        try{Uri uri=DocumentsContract.buildDocumentUri(AUTHORITY,"file:"+item.id);Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,item.mime==null?"*/*":item.mime);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);context.startActivity(i);}catch(Exception e){Toast.makeText(context,"Не удалось открыть файл",Toast.LENGTH_SHORT).show();}
    }

    static void download(Context context,DriveItem item){
        if(item==null||isLink(item))return;
        Context app=context.getApplicationContext();
        Toast.makeText(context,"Получаю свежую ссылку…",Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try{
                String resolved=RemoteFileSource.resolve(app,item.accountId,item.url);
                DownloadManager.Request r=new DownloadManager.Request(Uri.parse(resolved));
                r.addRequestHeader("Referer","https://chatgpt.com/");
                r.addRequestHeader("User-Agent","Mozilla/5.0 Android GPTDrive/0.6.0");
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name=item.name==null||item.name.trim().isEmpty()?URLUtil.guessFileName(resolved,null,item.mime):safeFileName(item.name);
                r.setTitle(name);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
                ((DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> Toast.makeText(app,"Скачивание: "+name,Toast.LENGTH_SHORT).show());
            }catch(Exception e){
                android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> Toast.makeText(app,"Не удалось получить файл: "+shortError(e),Toast.LENGTH_LONG).show());
            }
        },"gptdrive-download-resolve").start();
    }

    static String shortType(DriveItem item){if(item==null)return"";if(isLink(item))return"ChatGPT";String m=item.mime==null?"":item.mime;if(m.startsWith("image/"))return"Изображение";if(m.equals("application/pdf"))return"PDF";if(m.contains("wordprocessingml"))return"DOCX";if(m.contains("spreadsheetml"))return"XLSX";if(m.contains("presentationml"))return"PPTX";if(m.contains("zip"))return"ZIP";if(m.contains("android.package"))return"APK";if(m.equals("application/json"))return"JSON";if(m.startsWith("text/"))return"Текст";return"Файл";}
    private static String safeFileName(String s){String v=s.replaceAll("[\\\\/:*?\"<>|]","_").trim();if(v.length()>140)v=v.substring(0,140);return v.isEmpty()?"ChatGPT-file":v;}
    private static String shortError(Exception e){String m=e==null?"":e.getMessage();return m==null||m.trim().isEmpty()?"ошибка соединения":m;}
}
