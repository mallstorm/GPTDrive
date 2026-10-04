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

/** Clean account switcher. Only successfully authorized ChatGPT accounts are shown. */
public final class AccountManagerActivity extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
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

    @Override protected void onResume() {
        super.onResume();
        render();
    }

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
        title.setSingleLine(true);
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
            TextView empty = text("Добавь ChatGPT аккаунт, чтобы открыть библиотеку.", 12, false);
            empty.setTextColor(MUTED);
            empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(100)));
        }

        if (registered.size() >= ChatGptAuthActivity.MAX_ACCOUNTS) {
            addButton.setText("Достигнут лимит: 20 аккаунтов");
            addButton.setTextColor(MUTED);
        } else {
            addButton.setText("＋  Добавить аккаунт");
            addButton.setTextColor(GREEN);
        }
    }

    private List<AccountProfile> registeredAccounts() {
        ArrayList<AccountProfile> out = new ArrayList<>();
        for (AccountProfile a : db.listAccounts()) {
            // Only a confirmed ChatGPT identity counts as a registered account.
            // Anonymous cookies from the login page must never create a card.
            if (a.email != null && !a.email.trim().isEmpty()) out.add(a);
        }
        return out;
    }

    private View row(AccountProfile a) {
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
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(10), 0, dp(6), 0);
        TextView name = text(a.displayName(), 14, true);
        name.setSingleLine(true);
        TextView email = text(a.email == null ? "" : a.email, 10, false);
        email.setTextColor(MUTED);
        email.setSingleLine(true);
        String status = a.active ? "Активен" : "Готов к переключению";
        TextView plan = text(a.planLabel() + "  •  " + status, 10, true);
        plan.setTextColor(a.active ? GREEN : MUTED);
        info.addView(name);
        if (!email.getText().toString().isEmpty()) info.addView(email);
        info.addView(plan);
        card.addView(info, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView action = text(a.hasSession() ? (a.active ? "Активен" : "Выбрать") : "Войти", 11, true);
        action.setGravity(Gravity.CENTER);
        boolean disabled = a.active && a.hasSession();
        action.setTextColor(disabled ? MUTED : Color.WHITE);
        action.setBackground(round(disabled ? 0xFFF0F2F4 : GREEN, 11, 0, 0));
        action.setEnabled(!disabled);
        action.setOnClickListener(v -> {
            if (!a.hasSession()) authorizeExisting(a);
            else if (!a.active) select(a);
        });
        card.addView(action, new LinearLayout.LayoutParams(dp(76), dp(36)));

        TextView more = text("⋮", 20, false);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> menu(v, a));
        card.addView(more, new LinearLayout.LayoutParams(dp(28), dp(40)));
        if (!a.active && a.hasSession()) card.setOnClickListener(v -> select(a));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(74));
        p.setMargins(0, 0, 0, dp(7));
        card.setLayoutParams(p);
        return card;
    }

    private void addAccount() {
        if (registeredAccounts().size() >= ChatGptAuthActivity.MAX_ACCOUNTS) {
            Toast.makeText(this, "Можно подключить не более 20 аккаунтов", Toast.LENGTH_SHORT).show();
            return;
        }
        // Pending row is hidden from this screen and is removed automatically if
        // the user cancels authorization.
        long id = db.addAccount("Новый аккаунт");
        Intent i = new Intent(this, ChatGptAuthActivity.class);
        i.putExtra(ChatGptAuthActivity.EXTRA_ACCOUNT_ID, id);
        i.putExtra(ChatGptAuthActivity.EXTRA_NEW_ACCOUNT, true);
        startActivity(i);
    }

    private void authorizeExisting(AccountProfile a) {
        Intent i = new Intent(this, ChatGptAuthActivity.class);
        i.putExtra(ChatGptAuthActivity.EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(ChatGptAuthActivity.EXTRA_NEW_ACCOUNT, false);
        startActivity(i);
    }

    private void select(AccountProfile a) {
        AccountSession.switchTo(this, a.id, () -> {
            notifyRoots();
            Toast.makeText(this, "Переключено: " + a.displayName(), Toast.LENGTH_SHORT).show();
            render();
        });
    }

    private void menu(View anchor, AccountProfile a) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(round(Color.WHITE, 12, 0xFFE4E8EB, 1));

        if (!a.active) {
            TextView choose = popupAction(a.hasSession() ? "Сделать активным" : "Войти");
            panel.addView(choose, new LinearLayout.LayoutParams(-1, dp(42)));
            choose.setOnClickListener(v -> {
                PopupWindow p = findPopup(panel);
                if (p != null) p.dismiss();
                if (a.hasSession()) select(a); else authorizeExisting(a);
            });
        }
        TextView del = popupAction("Удалить аккаунт");
        del.setTextColor(0xFFD92D20);
        panel.addView(del, new LinearLayout.LayoutParams(-1, dp(42)));

        PopupWindow w = new PopupWindow(panel, dp(190), -2, true);
        panel.setTag(w);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setOutsideTouchable(true);
        w.setElevation(dp(10));
        del.setOnClickListener(v -> {
            w.dismiss();
            if (a.active) Toast.makeText(this, "Сначала переключись на другой аккаунт", Toast.LENGTH_SHORT).show();
            else if (db.deleteAccount(a.id)) { notifyRoots(); render(); }
        });
        w.showAsDropDown(anchor, anchor.getWidth() - dp(190), -dp(2));
    }

    private PopupWindow findPopup(View panel) {
        Object tag = panel.getTag();
        return tag instanceof PopupWindow ? (PopupWindow) tag : null;
    }

    private TextView popupAction(String s) {
        TextView t = text(s, 12, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(12), 0);
        return t;
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
