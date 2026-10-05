package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Account switcher with official OAuth identity and separate Library Session JSON. */
public final class AccountManagerActivity extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int MAX_ACCOUNTS = 20;
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private DriveDb db;
    private LinearLayout list;
    private TextView addButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        buildUi();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(12), 0);
        TextView back = text("‹", 28, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(52)));
        TextView title = text("Аккаунты", 20, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        addButton = text("＋  Добавить аккаунт", 13, true);
        addButton.setGravity(Gravity.CENTER);
        addButton.setTextColor(GREEN);
        addButton.setBackground(round(Color.WHITE, 14, 0xFF8EDFC0, 1));
        addButton.setOnClickListener(v -> addAccount());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(46));
        ap.setMargins(dp(14), dp(4), dp(14), dp(10));
        root.addView(addButton, ap);

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), 0, dp(14), dp(18));
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        List<AccountProfile> registered = registeredAccounts();
        for (AccountProfile a : registered) list.addView(row(a));
        if (registered.isEmpty()) {
            TextView empty = text("Подключи ChatGPT через системный браузер или импортируй Session JSON для Library.", 12, false);
            empty.setTextColor(MUTED); empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(110)));
        }
        if (registered.size() >= MAX_ACCOUNTS) {
            addButton.setText("Достигнут лимит: 20 аккаунтов"); addButton.setTextColor(MUTED);
        } else {
            addButton.setText("＋  Добавить аккаунт"); addButton.setTextColor(GREEN);
        }
    }

    private List<AccountProfile> registeredAccounts() {
        ArrayList<AccountProfile> out = new ArrayList<>();
        for (AccountProfile a : db.listAccounts()) {
            if ((a.email != null && !a.email.trim().isEmpty()) || ChatGptOAuthStore.has(this, a.id) || ChatGptSessionStore.has(this, a.id)) out.add(a);
        }
        return out;
    }

    private View row(AccountProfile a) {
        boolean oauth = ChatGptOAuthStore.has(this, a.id);
        boolean library = ChatGptSessionStore.has(this, a.id);
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(8), dp(8), dp(8));
        card.setBackground(round(Color.WHITE, 15, 0xFFE4E8EB, 1));

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_account_24);
        avatar.setColorFilter(Color.WHITE);
        avatar.setPadding(dp(10), dp(10), dp(10), dp(10));
        avatar.setBackground(round(a.active ? GREEN : 0xFF98A2B3, 21, 0, 0));
        card.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL); info.setGravity(Gravity.CENTER_VERTICAL); info.setPadding(dp(10), 0, dp(6), 0);
        TextView name = text(a.displayName(), 14, true); name.setSingleLine(true);
        String state = (oauth ? "OAuth ✓" : "OAuth —") + "  ·  " + (library ? "Library ✓" : "Library —");
        TextView status = text(state, 10, true); status.setTextColor(library ? GREEN : MUTED); status.setSingleLine(true);
        TextView plan = text((a.plan == null || a.plan.trim().isEmpty() ? "ChatGPT" : a.planLabel()) + (a.active ? "  •  Активен" : ""), 10, false);
        plan.setTextColor(MUTED); plan.setSingleLine(true);
        info.addView(name); info.addView(status); info.addView(plan);
        card.addView(info, new LinearLayout.LayoutParams(0, dp(56), 1));

        TextView action = text(a.active ? "Активен" : "Выбрать", 11, true);
        action.setGravity(Gravity.CENTER);
        action.setTextColor(a.active ? MUTED : Color.WHITE);
        action.setBackground(round(a.active ? 0xFFF0F2F4 : GREEN, 11, 0, 0));
        action.setEnabled(!a.active);
        action.setOnClickListener(v -> select(a));
        card.addView(action, new LinearLayout.LayoutParams(dp(76), dp(36)));

        TextView more = text("⋮", 20, false); more.setGravity(Gravity.CENTER); more.setOnClickListener(v -> menu(v, a));
        card.addView(more, new LinearLayout.LayoutParams(dp(28), dp(40)));
        if (!a.active) card.setOnClickListener(v -> select(a));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(76)); p.setMargins(0, 0, 0, dp(7)); card.setLayoutParams(p);
        return card;
    }

    private void addAccount() {
        if (registeredAccounts().size() >= MAX_ACCOUNTS) { Toast.makeText(this, "Можно подключить не более 20 аккаунтов", Toast.LENGTH_SHORT).show(); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Подключение ChatGPT")
                .setItems(new String[]{"Продолжить с ChatGPT — системный браузер", "Session JSON для Library — экспериментально"}, (d, which) -> {
                    if (which == 0) startNewOAuth(); else startNewSessionImport();
                }).show();
    }

    private long newPlaceholder() { return db.addAccount("Новый аккаунт"); }

    private void startNewOAuth() {
        long id = newPlaceholder();
        Intent i = new Intent(this, ChatGptOAuthActivity.class);
        i.putExtra(ChatGptOAuthActivity.EXTRA_ACCOUNT_ID, id);
        i.putExtra(ChatGptOAuthActivity.EXTRA_NEW_ACCOUNT, true);
        startActivity(i);
    }

    private void startNewSessionImport() {
        long id = newPlaceholder();
        Intent i = new Intent(this, SessionImportActivity.class);
        i.putExtra(SessionImportActivity.EXTRA_ACCOUNT_ID, id);
        i.putExtra(SessionImportActivity.EXTRA_NEW_ACCOUNT, true);
        startActivity(i);
    }

    private void oauthExisting(AccountProfile a) {
        Intent i = new Intent(this, ChatGptOAuthActivity.class);
        i.putExtra(ChatGptOAuthActivity.EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(ChatGptOAuthActivity.EXTRA_NEW_ACCOUNT, false);
        startActivity(i);
    }

    private void importSession(AccountProfile a) {
        Intent i = new Intent(this, SessionImportActivity.class);
        i.putExtra(SessionImportActivity.EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(SessionImportActivity.EXTRA_NEW_ACCOUNT, false);
        startActivity(i);
    }

    private void diagnostics(AccountProfile a) {
        Intent i = new Intent(this, SyncDiagnosticsActivity.class);
        i.putExtra(SyncDiagnosticsActivity.EXTRA_ACCOUNT_ID, a.id);
        startActivity(i);
    }

    private void select(AccountProfile a) {
        db.setActiveAccount(a.id); notifyRoots();
        Toast.makeText(this, "Переключено: " + a.displayName(), Toast.LENGTH_SHORT).show(); render();
    }

    private void menu(View anchor, AccountProfile a) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(round(Color.WHITE, 12, 0xFFE4E8EB, 1));
        PopupWindow w = new PopupWindow(panel, dp(230), -2, true);
        panel.setTag(w); w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setOutsideTouchable(true); w.setElevation(dp(10));

        if (!a.active) {
            TextView choose = popupAction("Сделать активным"); panel.addView(choose, new LinearLayout.LayoutParams(-1, dp(42)));
            choose.setOnClickListener(v -> { w.dismiss(); select(a); });
        }
        TextView oauth = popupAction(ChatGptOAuthStore.has(this, a.id) ? "Обновить вход ChatGPT" : "Продолжить с ChatGPT");
        panel.addView(oauth, new LinearLayout.LayoutParams(-1, dp(42)));
        oauth.setOnClickListener(v -> { w.dismiss(); oauthExisting(a); });

        TextView session = popupAction(ChatGptSessionStore.has(this, a.id) ? "Обновить Session JSON" : "Подключить Library Session");
        panel.addView(session, new LinearLayout.LayoutParams(-1, dp(42)));
        session.setOnClickListener(v -> { w.dismiss(); importSession(a); });

        TextView diag = popupAction("Диагностика");
        panel.addView(diag, new LinearLayout.LayoutParams(-1, dp(42)));
        diag.setOnClickListener(v -> { w.dismiss(); diagnostics(a); });

        TextView del = popupAction("Удалить аккаунт"); del.setTextColor(0xFFD92D20);
        panel.addView(del, new LinearLayout.LayoutParams(-1, dp(42)));
        del.setOnClickListener(v -> {
            w.dismiss();
            if (a.active) { Toast.makeText(this, "Сначала переключись на другой аккаунт", Toast.LENGTH_SHORT).show(); return; }
            new ChatMirrorRepository(this, db, a.id).deleteAccountMirror();
            ChatGptOAuthStore.clear(this, a.id); ChatGptSessionStore.clear(this, a.id);
            if (db.deleteAccount(a.id)) { notifyRoots(); render(); }
        });
        w.showAsDropDown(anchor, anchor.getWidth() - dp(230), -dp(2));
    }

    private TextView popupAction(String s) {
        TextView t = text(s, 12, false); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(12), 0, dp(12), 0); return t;
    }

    private void notifyRoots() { try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {} }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(TEXT); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke); return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
