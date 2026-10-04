package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.List;

public final class MainActivity extends Activity {
    private DriveDb db;
    private LinearLayout recent;
    private LinearLayout categories;
    private TextView session;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new DriveDb(this);
        draw();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void draw() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(0xFFF7F7F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));

        TextView h = text("GPT Drive", 29, true);
        root.addView(h);
        TextView sub = text("Твоя ChatGPT Library как облачный диск", 14, false);
        sub.setTextColor(0xFF666666);
        sub.setPadding(0, dp(3), 0, dp(8));
        root.addView(sub);

        session = text("", 12, false);
        session.setPadding(0, 0, 0, dp(12));
        root.addView(session);

        Button sync = new Button(this);
        sync.setText("Синхронизировать Library");
        sync.setAllCaps(false);
        sync.setOnClickListener(v -> openSync("Library"));
        root.addView(sync, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView sections = text("Облачный диск", 17, true);
        sections.setPadding(dp(4), dp(18), 0, dp(8));
        root.addView(sections);
        categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.VERTICAL);
        root.addView(categories);

        LinearLayout filesRow = new LinearLayout(this);
        filesRow.setGravity(Gravity.CENTER_VERTICAL);
        filesRow.setPadding(dp(4), dp(14), dp(4), dp(6));
        TextView rt = text("Недавние", 17, true);
        filesRow.addView(rt, new LinearLayout.LayoutParams(0, -2, 1));
        Button system = new Button(this);
        system.setText("Android Files");
        system.setAllCaps(false);
        system.setOnClickListener(v -> openSystemFiles());
        filesRow.addView(system);
        root.addView(filesRow);

        recent = new LinearLayout(this);
        recent.setOrientation(LinearLayout.VERTICAL);
        root.addView(recent);
        sc.addView(root);
        setContentView(sc);
        refresh();
    }

    private void refresh() {
        if (db == null || categories == null) return;
        String cookie = CookieManager.getInstance().getCookie("https://chatgpt.com");
        boolean connected = cookie != null && !cookie.trim().isEmpty();
        session.setText(connected ? "● Сессия ChatGPT сохранена" : "○ ChatGPT ещё не подключён");
        session.setTextColor(connected ? 0xFF287A42 : 0xFF777777);

        categories.removeAllViews();
        categories.addView(card("▣", "Library", "Документы и загруженные файлы", db.count("Library")));
        categories.addView(card("◫", "Images", "Сгенерированные и отправленные изображения", db.count("Images")));
        categories.addView(card("⌂", "Projects", "Ссылки и содержимое проектов", db.count("Projects")));
        categories.addView(card("□", "Archived", "Архивированные чаты и их вложения", db.count("Archived")));
        fillRecent();
    }

    private View card(String icon, String title, String desc, long count) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(12), dp(10), dp(12));
        c.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(82));
        cp.setMargins(0, 0, 0, dp(9));
        c.setLayoutParams(cp);

        TextView ic = text(icon, 27, false);
        ic.setGravity(Gravity.CENTER);
        c.addView(ic, new LinearLayout.LayoutParams(dp(48), -1));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 18, true);
        TextView d = text(desc, 13, false);
        d.setTextColor(0xFF777777);
        tx.addView(t);
        tx.addView(d);
        c.addView(tx, new LinearLayout.LayoutParams(0, -1, 1));

        TextView n = text(Long.toString(count), 14, true);
        n.setTextColor(0xFF555555);
        n.setGravity(Gravity.CENTER);
        c.addView(n, new LinearLayout.LayoutParams(dp(42), -1));
        TextView a = text("›", 30, false);
        a.setGravity(Gravity.CENTER);
        c.addView(a, new LinearLayout.LayoutParams(dp(28), -1));
        c.setOnClickListener(v -> openCategory(title));
        return c;
    }

    private void fillRecent() {
        recent.removeAllViews();
        List<DriveItem> xs = db.list(null, 12);
        if (xs.isEmpty()) {
            TextView e = text("После первой синхронизации здесь появятся файлы и изображения.", 14, false);
            e.setTextColor(0xFF777777);
            e.setPadding(dp(4), dp(8), dp(4), dp(8));
            recent.addView(e);
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (DriveItem x : xs) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            r.setBackgroundColor(0xFFFFFFFF);
            TextView n = text(x.name, 15, true);
            n.setMaxLines(2);
            TextView m = text(x.category + "  •  " + DriveActions.shortType(x) + "  •  " + df.format(x.updated), 12, false);
            m.setTextColor(0xFF777777);
            r.addView(n);
            r.addView(m);
            r.setOnClickListener(v -> DriveActions.open(this, x));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.setMargins(0, 0, 0, dp(6));
            recent.addView(r, p);
        }
    }

    private void openCategory(String category) {
        Intent i = new Intent(this, NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, category);
        startActivity(i);
    }

    private void openSync(String section) {
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, section);
        startActivity(i);
    }

    private void openSystemFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 100);
        } catch (Exception ignored) {}
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF111111);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
