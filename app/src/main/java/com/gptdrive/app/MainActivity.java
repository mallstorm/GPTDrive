package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.List;

public final class MainActivity extends Activity {
    private DriveDb db;
    private LinearLayout recent;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); db = new DriveDb(this); draw();
    }
    @Override protected void onResume() { super.onResume(); if (recent != null) fillRecent(); }

    private void draw() {
        ScrollView sc = new ScrollView(this); sc.setFillViewport(true); sc.setBackgroundColor(0xFFF7F7F7);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(18), dp(18), dp(24));
        TextView h = text("GPT Drive", 28, true); root.addView(h);
        TextView sub = text("ChatGPT Library как облачный диск", 14, false); sub.setTextColor(0xFF666666); sub.setPadding(0,dp(3),0,dp(18)); root.addView(sub);

        Button connect = new Button(this); connect.setText("Подключить / открыть ChatGPT"); connect.setAllCaps(false); connect.setOnClickListener(v -> open("Library")); root.addView(connect, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout spacer = new LinearLayout(this); root.addView(spacer, new LinearLayout.LayoutParams(1, dp(14)));

        root.addView(card("▣", "Library", "Все загруженные и созданные файлы"));
        root.addView(card("◫", "Images", "Сгенерированные и отправленные изображения"));
        root.addView(card("⌂", "Projects", "Проекты ChatGPT"));
        root.addView(card("□", "Archived", "Архивированные чаты и вложения"));

        LinearLayout filesRow = new LinearLayout(this); filesRow.setGravity(Gravity.CENTER_VERTICAL); filesRow.setPadding(dp(4),dp(16),dp(4),dp(6));
        TextView rt = text("Недавно найденные файлы", 17, true); filesRow.addView(rt, new LinearLayout.LayoutParams(0,-2,1));
        Button system = new Button(this); system.setText("Системный диск"); system.setAllCaps(false); system.setOnClickListener(v -> { try { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, 100); } catch(Exception ignored){} }); filesRow.addView(system);
        root.addView(filesRow);
        recent = new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); root.addView(recent);
        fillRecent();
        sc.addView(root); setContentView(sc);
    }

    private View card(String icon, String title, String desc) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL); c.setPadding(dp(16),dp(14),dp(12),dp(14)); c.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(82)); cp.setMargins(0,0,0,dp(10)); c.setLayoutParams(cp);
        TextView ic = text(icon, 27, false); ic.setGravity(Gravity.CENTER); c.addView(ic,new LinearLayout.LayoutParams(dp(48),-1));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setGravity(Gravity.CENTER_VERTICAL); TextView t=text(title,18,true); TextView d=text(desc,13,false); d.setTextColor(0xFF777777); tx.addView(t); tx.addView(d); c.addView(tx,new LinearLayout.LayoutParams(0,-1,1)); TextView a=text("›",30,false); a.setGravity(Gravity.CENTER); c.addView(a,new LinearLayout.LayoutParams(dp(36),-1)); c.setOnClickListener(v->open(title)); return c;
    }

    private void fillRecent() {
        recent.removeAllViews(); List<DriveItem> xs = db.list(null, 12); if (xs.isEmpty()) { TextView e=text("Открой Library или Images — GPT Drive начнёт индексировать доступные элементы.",14,false); e.setTextColor(0xFF777777); e.setPadding(dp(4),dp(8),dp(4),dp(8)); recent.addView(e); return; }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (DriveItem x: xs) { LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(dp(12),dp(10),dp(12),dp(10)); r.setBackgroundColor(0xFFFFFFFF); TextView n=text(x.name,15,true); TextView m=text(x.category+"  •  "+df.format(x.updated),12,false); m.setTextColor(0xFF777777); r.addView(n);r.addView(m); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(6)); recent.addView(r,p); }
    }

    private void open(String section) { Intent i=new Intent(this,BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION,section); startActivity(i); }
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(0xFF111111);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
