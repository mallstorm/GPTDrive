package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class FilePreviewActivity extends Activity {
    public static final String EXTRA_ID = "file_id";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private LinearLayout body;
    private TextView title;
    private DriveItem item;
    private File temporary;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        item = new DriveDb(this).byId(getIntent().getLongExtra(EXTRA_ID, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff7f9fa);
        title = new TextView(this);
        title.setText(item == null ? "Файл не найден" : item.name);
        title.setTextSize(18);
        title.setTextColor(Color.BLACK);
        title.setPadding(20,24,20,24);
        root.addView(title);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(18,12,18,20);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        if (item != null) {
            label("Загрузка файла…");
            new Thread(this::load, "gptdrive-preview").start();
        }
    }
    private void load() {
        try {
            File cache = File.createTempFile("preview-", ".tmp", getCacheDir());
            temporary = cache;
            try (RemoteFileSource.Connection c = RemoteFileSource.open(this,item.accountId,item.url);
                 InputStream in = c.inputStream(); OutputStream out = new FileOutputStream(cache)) {
                byte[] buf = new byte[32768]; long total=0; int n;
                while ((n=in.read(buf))!=-1) {
                    total+=n;
                    if (total>MAX_BYTES) throw new IOException("Файл больше 64 МБ. Используйте скачивание.");
                    out.write(buf,0,n);
                }
            }
            String name = item.name == null ? "" : item.name.toLowerCase(Locale.ROOT);
            runOnUiThread(() -> {
                body.removeAllViews();
                if (name.endsWith(".zip")) showZip(cache);
                else if (name.endsWith(".pdf")) showPdf(cache);
                else showText(cache,name);
            });
        } catch(Exception e) {
            runOnUiThread(() -> {body.removeAllViews();label("Не удалось открыть: "+e.getMessage());});
        }
    }
    private void showText(File file,String name) {
        try {
            if(file.length()>4*1024*1024) {label("Текст больше 4 МБ. Скачайте файл для просмотра.");return;}
            byte[] data = new byte[(int)file.length()];
            try(InputStream in=new FileInputStream(file)){int at=0,n;while(at<data.length&&(n=in.read(data,at,data.length-at))>0)at+=n;}
            String value=new String(data,StandardCharsets.UTF_8);
            if(name.endsWith(".md")) value=value.replaceAll("(?m)^#{1,6}\\s+","").replace("**","");
            TextView t=label(value);
            t.setTextIsSelectable(true);
            t.setTextSize(14);
        } catch(Exception e){label("Ошибка текста: "+e.getMessage());}
    }
    private void showZip(File file) {
        try(ZipFile zip=new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            int count=0;
            while(entries.hasMoreElements()&&count<1000) {
                ZipEntry entry=entries.nextElement();
                label((entry.isDirectory()?"📁 ":"📄 ")+entry.getName()+"  "+(entry.isDirectory()?"":entry.getSize()+" B"));
                count++;
            }
            if(entries.hasMoreElements()) label("Показаны первые 1000 записей");
        }catch(Exception e){label("Ошибка ZIP: "+e.getMessage());}
    }
    private void showPdf(File file) {
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer=new PdfRenderer(fd)) {
            int pages=Math.min(renderer.getPageCount(),12);
            label("PDF: "+renderer.getPageCount()+" страниц. Предпросмотр первых "+pages);
            for(int i=0;i<pages;i++) {
                try(PdfRenderer.Page page=renderer.openPage(i)) {
                    int width=Math.min(900,page.getWidth()*2);
                    int height=Math.max(1,(int)((long)width*page.getHeight()/page.getWidth()));
                    if((long)width*height>5000000) continue;
                    Bitmap bmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ImageView image=new ImageView(this);
                    image.setImageBitmap(bmp);
                    image.setAdjustViewBounds(true);
                    body.addView(image,new LinearLayout.LayoutParams(-1,-2));
                }
            }
        }catch(Exception e){label("Ошибка PDF: "+e.getMessage());}
    }
    private TextView label(String value) {
        TextView v=new TextView(this);
        v.setText(value);v.setTextColor(0xff182230);v.setTextSize(13);
        v.setPadding(8,8,8,8);
        body.addView(v,new LinearLayout.LayoutParams(-1,-2));
        return v;
    }
    @Override protected void onDestroy(){super.onDestroy();if(temporary!=null)temporary.delete();}
}
