package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
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
        root.setBackgroundColor(0xFFF7F7F7);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button back = new Button(this); back.setText("‹"); back.setOnClickListener(v -> finish());
        TextView title = text("Аккаунты GPT Drive", 21, true); title.setPadding(dp(8),0,0,0);
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

        TextView hint = text("Каждый аккаунт публикуется в Android как отдельный корень GPT Drive.", 13, false);
        hint.setTextColor(0xFF6E6E6E); hint.setPadding(dp(18),0,dp(18),dp(10)); root.addView(hint);

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(14),0,dp(14),dp(20));
        sc.addView(list); root.addView(sc, new LinearLayout.LayoutParams(-1,0,1));
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
        row.setPadding(dp(14), dp(12), dp(8), dp(12));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView dot = text(a.active ? "●" : "○", 20, false);
        dot.setTextColor(a.active ? 0xFF238636 : 0xFF888888);
        dot.setGravity(Gravity.CENTER);
        row.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(54)));

        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(a.displayName(), 16, true);
        String state = a.hasSession() ? "Подключён" : "Нужен вход";
        TextView meta = text(state + "  •  " + db.countAll(a.id) + " элементов", 12, false); meta.setTextColor(0xFF777777);
        info.addView(name); info.addView(meta);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(58), 1));

        Button use = new Button(this); use.setText(a.active ? "Активен" : "Выбрать"); use.setAllCaps(false); use.setEnabled(!a.active);
        use.setOnClickListener(v -> select(a)); row.addView(use, new LinearLayout.LayoutParams(dp(96), dp(48)));
        row.setOnClickListener(v -> { if (!a.active) select(a); });
        row.setOnLongClickListener(v -> { menu(v, a); return true; });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(82)); p.setMargins(0,0,0,dp(8)); row.setLayoutParams(p);
        return row;
    }

    private void select(AccountProfile a) {
        AccountSession.switchTo(this, a.id, () -> {
            DocumentsContract.notifyRootsChanged(getContentResolver(), AUTHORITY);
            Toast.makeText(this, "Аккаунт переключён", Toast.LENGTH_SHORT).show();
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
        m.getMenu().add(0,1,0,"Синхронизировать");
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
                    DocumentsContract.notifyRootsChanged(getContentResolver(), AUTHORITY);
                    render();
                }
            }
            return true;
        });
        m.show();
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(0xFF111111);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
