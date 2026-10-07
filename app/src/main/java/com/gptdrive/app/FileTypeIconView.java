package com.gptdrive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.util.Locale;

/** Compact generated-style icon pack for common Library file extensions. */
final class FileTypeIconView extends View {
    private static final int APK=1, ARCHIVE=2, PDF=3, WORD=4, SHEET=5, SLIDES=6,
            TEXT=7, MARKDOWN=8, JSON=9, VIDEO=10, AUDIO=11, LINK=12, UNKNOWN=13;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int type;
    private final String badge;

    FileTypeIconView(Context context, DriveItem item) {
        super(context);
        String name = item == null || item.name == null ? "" : item.name.toLowerCase(Locale.ROOT);
        String mime = item == null || item.mime == null ? "" : item.mime.toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm") || mime.contains("android.package")) { type=APK; badge=name.endsWith(".xapk")?"XAPK":name.endsWith(".apkm")?"APKM":"APK"; }
        else if (ends(name,".zip",".rar",".7z",".tar",".gz",".tgz") || mime.contains("zip") || mime.contains("rar") || mime.contains("compressed")) { type=ARCHIVE; badge=name.endsWith(".rar")?"RAR":"ZIP"; }
        else if (name.endsWith(".pdf") || mime.contains("pdf")) { type=PDF; badge="PDF"; }
        else if (ends(name,".doc",".docx",".odt") || mime.contains("wordprocessing")) { type=WORD; badge="W"; }
        else if (ends(name,".xls",".xlsx",".ods",".csv") || mime.contains("spreadsheet") || mime.contains("csv")) { type=SHEET; badge="X"; }
        else if (ends(name,".ppt",".pptx",".odp") || mime.contains("presentation")) { type=SLIDES; badge="P"; }
        else if (ends(name,".md",".markdown")) { type=MARKDOWN; badge="M↓"; }
        else if (ends(name,".json",".xml",".yaml",".yml") || mime.contains("json") || mime.contains("xml")) { type=JSON; badge="{ }"; }
        else if (mime.startsWith("video/") || ends(name,".mp4",".mkv",".webm",".avi",".mov")) { type=VIDEO; badge=""; }
        else if (mime.startsWith("audio/") || ends(name,".mp3",".wav",".ogg",".m4a",".flac")) { type=AUDIO; badge=""; }
        else if (DriveActions.isLink(item)) { type=LINK; badge=""; }
        else if (mime.startsWith("text/") || ends(name,".txt",".log",".ini",".cfg")) { type=TEXT; badge="TXT"; }
        else { type=UNKNOWN; badge="?"; }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight(), pad=Math.min(w,h)*.08f;
        paint.setColor(outer());
        c.drawRoundRect(0,0,w,h,h*.22f,h*.22f,paint);
        int[] cc=colors();
        paint.setShader(new LinearGradient(pad,pad,w-pad,h-pad,cc[0],cc[1], Shader.TileMode.CLAMP));
        c.drawRoundRect(pad,pad,w-pad,h-pad,h*.18f,h*.18f,paint);
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        if(type==APK) android(c,w/2f,h*.45f,Math.min(w,h)*.19f);
        else if(type==ARCHIVE) archive(c,w,h);
        else if(type==VIDEO) play(c,w/2f,h/2f,Math.min(w,h)*.23f);
        else if(type==AUDIO) note(c,w/2f,h/2f,Math.min(w,h)*.28f);
        else if(type==LINK) cloud(c,w/2f,h/2f,Math.min(w,h)*.25f);
        else document(c,w,h);
        if(!badge.isEmpty()) {
            paint.setShader(null); paint.setColor(0xFFFFFFFF); paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true); paint.setTextSize(Math.min(w,h)*(badge.length()>3?.17f:.21f));
            c.drawText(badge,w/2f,h*.78f-(type==APK?0:h*.02f),paint);
            paint.setFakeBoldText(false);
        }
    }

    private int outer(){ switch(type){case APK:return 0xFFEAF0FF;case ARCHIVE:return 0xFFFFF2DB;case PDF:return 0xFFFFEBE8;case WORD:return 0xFFEAF3FF;case SHEET:return 0xFFE6F9F0;case SLIDES:return 0xFFFFF0E4;case MARKDOWN:return 0xFFE8F0FA;case JSON:return 0xFFF0EBFF;case VIDEO:case AUDIO:return 0xFFFFEAF4;default:return 0xFFEEF2F6;} }
    private int[] colors(){ switch(type){case APK:return new int[]{0xFF27A8F8,0xFF5B3FF0};case ARCHIVE:return new int[]{0xFFFFC52E,0xFFFF8617};case PDF:return new int[]{0xFFFF6557,0xFFE43832};case WORD:return new int[]{0xFF278DF2,0xFF1459C7};case SHEET:return new int[]{0xFF20CD7A,0xFF068B50};case SLIDES:return new int[]{0xFFFFA22E,0xFFFF6418};case MARKDOWN:return new int[]{0xFF2788D7,0xFF174B91};case JSON:return new int[]{0xFF9368F5,0xFF6337D9};case VIDEO:return new int[]{0xFFF849A0,0xFFE51E68};case AUDIO:return new int[]{0xFFF85AAE,0xFFD91A83};case LINK:return new int[]{0xFF28C6BC,0xFF079A91};case TEXT:return new int[]{0xFF91A3B9,0xFF52657E};default:return new int[]{0xFF91A3B9,0xFF52657E};} }

    private void android(Canvas c,float cx,float cy,float s){ paint.setStrokeWidth(s*.12f);paint.setStrokeCap(Paint.Cap.ROUND);c.drawRoundRect(cx-s,cy-s*.55f,cx+s,cy+s*.72f,s*.25f,s*.25f,paint);paint.setStyle(Paint.Style.STROKE);c.drawLine(cx-s*.55f,cy-s*.75f,cx-s*.83f,cy-s*1.12f,paint);c.drawLine(cx+s*.55f,cy-s*.75f,cx+s*.83f,cy-s*1.12f,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(colors()[1]);c.drawCircle(cx-s*.45f,cy-s*.13f,s*.10f,paint);c.drawCircle(cx+s*.45f,cy-s*.13f,s*.10f,paint);paint.setColor(0xFFFFFFFF);c.drawRoundRect(cx-s*1.30f,cy-s*.30f,cx-s*1.05f,cy+s*.62f,s*.12f,s*.12f,paint);c.drawRoundRect(cx+s*1.05f,cy-s*.30f,cx+s*1.30f,cy+s*.62f,s*.12f,s*.12f,paint); }
    private void archive(Canvas c,float w,float h){paint.setColor(0x55FFFFFF);c.drawRoundRect(w*.22f,h*.22f,w*.78f,h*.72f,h*.08f,h*.08f,paint);paint.setColor(0xFFFFFFFF);float x=w*.50f;for(int i=0;i<5;i++)c.drawRoundRect(x-w*.035f,h*(.19f+i*.105f),x+w*.035f,h*(.255f+i*.105f),w*.018f,w*.018f,paint);c.drawRoundRect(w*.39f,h*.62f,w*.61f,h*.73f,h*.025f,h*.025f,paint);}
    private void document(Canvas c,float w,float h){paint.setColor(0x33FFFFFF);path.reset();path.moveTo(w*.25f,h*.16f);path.lineTo(w*.64f,h*.16f);path.lineTo(w*.77f,h*.30f);path.lineTo(w*.77f,h*.72f);path.lineTo(w*.25f,h*.72f);path.close();c.drawPath(path,paint);if(type==TEXT||type==UNKNOWN){paint.setColor(0xFFFFFFFF);paint.setStrokeWidth(w*.055f);paint.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<3;i++)c.drawLine(w*.34f,h*(.34f+i*.11f),w*(i==2?.60f:.68f),h*(.34f+i*.11f),paint);}}
    private void play(Canvas c,float cx,float cy,float s){path.reset();path.moveTo(cx-s*.55f,cy-s);path.lineTo(cx+s,cy);path.lineTo(cx-s*.55f,cy+s);path.close();c.drawPath(path,paint);}
    private void note(Canvas c,float cx,float cy,float s){paint.setStrokeWidth(s*.22f);paint.setStrokeCap(Paint.Cap.ROUND);c.drawLine(cx+s*.38f,cy-s*.92f,cx+s*.38f,cy+s*.42f,paint);c.drawLine(cx+s*.38f,cy-s*.92f,cx-s*.55f,cy-s*.68f,paint);c.drawCircle(cx-s*.64f,cy+s*.55f,s*.36f,paint);c.drawCircle(cx+s*.10f,cy+s*.31f,s*.36f,paint);}
    private void cloud(Canvas c,float cx,float cy,float s){c.drawCircle(cx-s*.43f,cy,s*.52f,paint);c.drawCircle(cx+s*.12f,cy-s*.30f,s*.66f,paint);c.drawCircle(cx+s*.64f,cy+s*.02f,s*.43f,paint);c.drawRoundRect(cx-s*.86f,cy,cx+s*.96f,cy+s*.55f,s*.22f,s*.22f,paint);}
    private boolean ends(String n,String... suffixes){for(String s:suffixes)if(n.endsWith(s))return true;return false;}
}
