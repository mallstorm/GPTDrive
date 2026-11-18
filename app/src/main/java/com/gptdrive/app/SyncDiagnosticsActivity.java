package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Compact diagnostics for auth, normalized index, Smart DNS and per-surface sync state. */
public final class SyncDiagnosticsActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private DriveDb db;
    private long accountId;
    private LinearLayout body;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = DriveDb.get(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
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
        bar.setPadding(dp(8), 0, dp(12), 0);
        TextView back = text("‹", 28, false, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));
        TextView title = text("Диагностика", 20, true, TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(20));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        body.removeAllViews();
        AccountProfile a = db.accountById(accountId);
        ChatGptSessionStore.Session session = ChatGptSessionStore.load(this, accountId);
        ChatGptOAuthStore.Record oauth = ChatGptOAuthStore.load(this, accountId);

        addSection("Аккаунт");
        addRow("Профиль", a == null ? "—" : a.displayName(), true);
        addRow("Локальный ID", Long.toString(accountId), false);
        addRow("OAuth", oauth == null ? "—" : "✓", oauth != null);
        addRow("Library Session", session == null ? "—" : (session.expired() ? "токен истёк" : "✓"), session != null && !session.expired());
        addRow("Состояние входа", AccountAuth.needsLogin(this, accountId)
                ? "Нужен вход — вставьте новый Session JSON"
                : AccountAuth.connected(a) ? "Подключён" : "Не подключён", AccountAuth.connected(a));
        addRow("Backend Account ID", session == null || empty(session.accountId) ? "—" : session.accountId, session != null && !empty(session.accountId));
        addRow("Session email", session == null || empty(session.email) ? "—" : session.email, session != null);

        addSection("Индекс");
        addRow("Library", db.count(accountId, "Library") + " объектов", db.count(accountId, "Library") > 0);
        addRow("Images", db.count(accountId, "Images") + " объектов", db.count(accountId, "Images") > 0);
        addRow("Projects", db.count(accountId, "Projects") + " объектов", db.count(accountId, "Projects") > 0);
        addRow("Archive", db.count(accountId, "Archived") + " объектов", db.count(accountId, "Archived") > 0);
        addRow("Известный объём", MainActivity.formatBytes(db.knownStorageBytes(accountId)), db.knownStorageBytes(accountId) > 0);

        ChatMirrorRepository.Stats mirror = new ChatMirrorRepository(this, db, accountId).stats();
        addSection("Chat Mirror");
        addRow("Удалённо", mirror.remotePresent + " чатов", mirror.remotePresent > 0);
        addRow("Локальные snapshot", mirror.mirrored + " / " + mirror.total, mirror.mirrored > 0);
        addRow("Ожидают snapshot", Integer.toString(mirror.pending), mirror.pending == 0);
        addRow("Исчезли из ChatGPT", mirror.missingRemote + " · локальные копии не удаляются", true);
        addRow("Локальный объём", MainActivity.formatBytes(mirror.bytes), mirror.bytes > 0);

        addSection("Синхронизация");
        List<SyncStateStore.State> states = SyncStateStore.list(db, accountId);
        if (states.isEmpty()) {
            addRow("Состояние", "Ещё нет данных", false);
        } else {
            for (SyncStateStore.State s : states) {
                String value = s.count + " · " + when(s.lastSuccess);
                if (!empty(s.lastError)) value += " · " + s.lastError;
                if (!empty(s.meta)) value += "\n" + s.meta;
                addRow(s.surface, value, s.ok());
            }
        }

        addSection("Журнал событий");
        String log = SyncLog.dump(this);
        addRow("Последние события", log.isEmpty() ? "Пока пусто" : log, log.isEmpty());

        addSection("Routing & DNS");
        SmartDnsManager.initialize(this);
        addRow("Smart DNS", SmartDnsManager.isEnabled() ? "Включён" : "Выключен", SmartDnsManager.isEnabled());
        addRow("Маршрут", SmartDnsManager.selectedSummary(), SmartDnsManager.isEnabled());
        addRow("Реестр", SmartDnsManager.lastRegistryError().isEmpty() ? "OK" : SmartDnsManager.lastRegistryError(), SmartDnsManager.lastRegistryError().isEmpty());
        long updated = SmartDnsManager.lastUpdated();
        addRow("Обновление DNS", updated > 0 ? when(updated) : "—", updated > 0);

        List<SmartDnsManager.RouteView> routes = SmartDnsManager.routes();
        for (SmartDnsManager.RouteView r : routes) {
            if (!r.selected && !r.ok) continue;
            String value = r.name + " · " + r.mode;
            if (r.dnsMs >= 0) value += " · DNS " + r.dnsMs + " ms";
            if (r.tlsMs >= 0) value += " · TLS " + r.tlsMs + " ms";
            if (r.httpMs >= 0) value += " · HTTP " + r.httpMs + " ms";
            if (!empty(r.error)) value += " · " + r.error;
            addRow(r.selected ? "Текущий DNS" : "Резерв", value, r.ok || r.selected);
        }
    }

    private void addSection(String title) {
        TextView t = text(title, 13, true, MUTED);
        t.setPadding(dp(4), dp(12), dp(4), dp(5));
        body.addView(t, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private void addRow(String name, String value, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(round(Color.WHITE, 12, 0xFFE5E8EB, 1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(name, 12, true, TEXT);
        top.addView(n, new LinearLayout.LayoutParams(0, dp(24), 1));
        TextView badge = text(ok ? "✓" : "•", 13, true, ok ? GREEN : MUTED);
        badge.setGravity(Gravity.CENTER);
        top.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.addView(top);

        TextView v = text(value == null ? "—" : value, 10, false, MUTED);
        v.setLineSpacing(0, 1.12f);
        row.addView(v);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(5));
        body.addView(row, p);
    }

    private String when(long time) {
        if (time <= 0) return "нет успешной синхронизации";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
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

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
