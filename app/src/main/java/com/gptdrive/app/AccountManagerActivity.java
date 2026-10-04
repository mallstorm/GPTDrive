package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class AccountManagerActivity extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private DriveDb db;
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        buildUi();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F9FA);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(v -> finish());
        TextView title = text("Аккаунты GPT Drive", 21, true);
        title.setPadding(dp(8),0,0,0);
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bar);

        Button add = new Button(this);
        add.setText("＋ Добавить ChatGPT аккаунт");
        add.setAllCaps(false);
        add.setOnClickListener(v -> addAccount());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(52));
        ap.setMargins(dp(16), 0, dp(16), dp(12));
        root.addView(add, ap);

        TextView hint = text("Аккаунтов может быть несколько, но активен всегда один. Здесь видно план каждого профиля и известный GPT Drive объём.", 13, false);
        hint.setTextColor(0xFF667085);
        hint.setPadding(dp(18),0,dp(18),dp(10));
        root.addView(hint);

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14),0,dp(14),dp(20));
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        List<AccountProfile> accounts = db.listAccounts();
        for (AccountProfile a : accounts) list.addView(row(a));
    }

    private View row(AccountProfile a) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(8), dp(10));
        row.setBackground(round(0xFFFFFFFF, 18, 0xFFE7EAEC));

        TextView dot = text(a.active ? "●" : "○", 20, false);
        dot.setTextColor(a.active ? 0xFF08A66D : 0xFF98A2B3);
        dot.setGravity(Gravity.CENTER);
        row.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(68)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(a.displayName(), 16, true);
        String state;
        if (a.active) state = a.hasSession() ? "Активен" : "Активен • нужен вход";
        else state = a.hasSession() ? "Сессия сохранена" : "Нужен вход";
        TextView plan = text("План: " + a.planLabel() + "  •  " + state, 12, true);
        plan.setTextColor(0xFF06764E);
        long known = db.knownStorageBytes(a.id);
        String storage;
        if (a.hasRemoteQuota()) storage = MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes);
        else if (known > 0) storage = MainActivity.formatBytes(known) + " индексировано";
        else storage = "Объём не определён";
        TextView meta = text(storage + "  •  " + db.countAll(a.id) + " элементов", 12, false);
        meta.setTextColor(0xFF667085);
        info.addView(name);
        info.addView(plan);
        info.addView(meta);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(70), 1));

        Button use = new Button(this);
        use.setText(a.active ? "Активен" : "Выбрать");
        use.setAllCaps(false);
        use.setEnabled(!a.active);
        use.setOnClickListener(v -> select(a));
        row.addView(use, new LinearLayout.LayoutParams(dp(96), dp(48)));
        row.setOnClickListener(v -> { if (!a.active) select(a); });
        row.setOnLongClickListener(v -> { menu(v, a); return true; });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(94));
        p.setMargins(0,0,0,dp(8));
        row.setLayoutParams(p);
        return row;
    }

    private void select(AccountProfile a) {
        AccountSession.switchTo(this, a.id, () -> {
            notifyRoots();
            Toast.makeText(this, "Переключено: " + a.displayName(), Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void addAccount() {
        long id = db.addAccount("Новый аккаунт");
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, "Library");
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, id);
        i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN, true);
        startActivity(i);
    }

    private void menu(View anchor, AccountProfile a) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0,1,0,"Открыть и синхронизировать");
        m.getMenu().add(0,2,1,"Удалить аккаунт");
        m.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                Intent i = new Intent(this, BrowserActivity.class);
                i.putExtra(BrowserActivity.EXTRA_SECTION, "Library");
                i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, a.id);
                startActivity(i);
            } else if (mi.getItemId() == 2) {
                if (a.active) Toast.makeText(this, "Сначала переключись на другой аккаунт", Toast.LENGTH_SHORT).show();
                else if (db.deleteAccount(a.id)) {
                    notifyRoots();
                    render();
                }
            }
            return true;
        });
        m.show();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF101828);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
