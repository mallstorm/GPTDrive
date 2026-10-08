package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class DnsSettingsActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int RED = 0xFFD92D20;
    private static final int ORANGE = 0xFFE47A18;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private LinearLayout routes;
    private TextView status, updated, webViewState;
    private Switch toggle, autoToggle;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SmartDnsManager.initialize(this);
        SmartDnsProxy.applyWebViewRouting(this, null);
        buildUi();
        render();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), 0, dp(14), 0);
        TextView back = text("‹", 30, false, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(54)));
        TextView title = text("Routing & DNS", 20, true, TEXT);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView sc = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(8), dp(14), dp(24));

        LinearLayout card = panel();
        card.addView(switchRow("Smart Routing", "Только внутри GPT Drive · без второго VPN", true));
        toggle = lastSwitch;
        divider(card);
        card.addView(switchRow("Автовыбор ChatGPT Smart DNS", "Лучший проверенный маршрут для ChatGPT", false));
        autoToggle = lastSwitch;

        status = text("System DNS", 13, true, GREEN);
        status.setPadding(0, dp(12), 0, dp(3));
        card.addView(status);
        webViewState = text("", 10, false, MUTED);
        card.addView(webViewState);
        updated = text("", 10, false, MUTED);
        updated.setPadding(0, dp(2), 0, 0);
        card.addView(updated);

        TextView note = text("SmartDNS обслуживает только ChatGPT/OpenAI внутри GPT Drive. Проверяются сессия ChatGPT и TLS авторизации OpenAI. Остальные сайты используют системное подключение. Вход через Google может потребовать внешнего VPN.", 10, false, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(0, 1.12f);
        card.addView(note);
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView registryTitle = text("ChatGPT Smart DNS · удалённый реестр", 13, true, TEXT);
        registryTitle.setPadding(dp(2), dp(18), 0, dp(5));
        body.addView(registryTitle);
        TextView registry = text(SmartDnsManager.REGISTRY_URL, 10, false, MUTED);
        registry.setPadding(dp(12), dp(10), dp(12), dp(10));
        registry.setBackground(round(Color.WHITE, 12, 0xFFE2E8E5, 1));
        registry.setTextIsSelectable(true);
        body.addView(registry, new LinearLayout.LayoutParams(-1, -2));

        TextView refresh = action("Обновить реестр и проверить ChatGPT", GREEN);
        refresh.setOnClickListener(v -> runCheck(refresh, true));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(50));
        rp.setMargins(0, dp(12), 0, 0);
        body.addView(refresh, rp);

        TextView benchmark = action("Проверить и отсортировать по качеству", 0xFF344054);
        benchmark.setOnClickListener(v -> runCheck(benchmark, false));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(48));
        bp.setMargins(0, dp(8), 0, 0);
        body.addView(benchmark, bp);

        TextView disableBad = action("Снять галочки с нерабочих DNS", Color.WHITE);
        disableBad.setTextColor(TEXT);
        disableBad.setBackground(round(Color.WHITE, 14, 0xFFD0D5DD, 1));
        disableBad.setOnClickListener(v -> {
            SmartDnsManager.disableFailedRoutes(this);
            render();
            Toast.makeText(this, "Нерабочие маршруты ChatGPT исключены.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams dpBad = new LinearLayout.LayoutParams(-1, dp(46));
        dpBad.setMargins(0, dp(8), 0, 0);
        body.addView(disableBad, dpBad);

        TextView rt = text("ChatGPT Smart DNS · лучший сверху", 13, true, TEXT);
        rt.setPadding(dp(2), dp(20), 0, dp(7));
        body.addView(rt);
        routes = new LinearLayout(this);
        routes.setOrientation(LinearLayout.VERTICAL);
        body.addView(routes);

        sc.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private Switch lastSwitch;
    private View switchRow(String title, String subtitle, boolean master) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, true, TEXT));
        TextView sub = text(subtitle, 11, false, MUTED);
        sub.setPadding(0, dp(3), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(58), 1));
        Switch sw = new Switch(this);
        lastSwitch = sw;
        if (master) {
            sw.setOnCheckedChangeListener((button, checked) -> {
                SmartDnsManager.setEnabled(this, checked);
                SmartDnsProxy.applyWebViewRouting(this, this::renderOnUi);
                render();
            });
        } else {
            sw.setOnCheckedChangeListener((button, checked) -> {
                SmartDnsManager.setAuto(this, checked);
                render();
            });
        }
        row.addView(sw, new LinearLayout.LayoutParams(dp(58), dp(48)));
        return row;
    }

    private void runCheck(TextView button, boolean refreshRegistry) {
        String original = button.getText().toString();
        button.setText(refreshRegistry ? "Обновляю и проверяю…" : "Проверяю маршруты…");
        button.setEnabled(false);
        Runnable done = () -> runOnUiThread(() -> {
            button.setText(original);
            button.setEnabled(true);
            render();
            String err = SmartDnsManager.lastRegistryError();
            if (refreshRegistry && err != null && !err.isEmpty()) Toast.makeText(this, "ChatGPT DNS: " + err, Toast.LENGTH_LONG).show();
        });

        if (refreshRegistry) {
            SmartDnsManager.refreshAndSweep(this, done);
        } else {
            SmartDnsManager.sweepNow(this, done);
        }
    }

    private void renderOnUi() { runOnUiThread(this::render); }

    private void render() {
        if (toggle == null) return;
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(SmartDnsManager.isEnabled());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            SmartDnsManager.setEnabled(this, checked);
            SmartDnsProxy.applyWebViewRouting(this, this::renderOnUi);
            render();
        });

        autoToggle.setOnCheckedChangeListener(null);
        autoToggle.setChecked(SmartDnsManager.isAuto());
        autoToggle.setEnabled(SmartDnsManager.isEnabled());
        autoToggle.setOnCheckedChangeListener((button, checked) -> { SmartDnsManager.setAuto(this, checked); render(); });

        status.setText(SmartDnsManager.isEnabled() ? "ChatGPT: " + SmartDnsManager.selectedSummary() : "Выключено · System DNS");
        status.setTextColor(!SmartDnsManager.isEnabled() ? MUTED : SmartDnsManager.hasHealthyRoute() ? GREEN : ORANGE);
        String wv = SmartDnsProxy.webViewStatus();
        webViewState.setText(wv == null || wv.isEmpty() ? "WebView: применение маршрута…" : wv);

        long at = SmartDnsManager.lastUpdated();
        String when = at > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(new Date(at)) : "ещё не загружен";
        String err = SmartDnsManager.lastRegistryError();
        updated.setText("ChatGPT Registry: " + when + (err == null || err.isEmpty() ? "" : " · " + err));

        routes.removeAllViews();
        List<SmartDnsManager.RouteView> list = SmartDnsManager.routes();
        if (list.isEmpty()) {
            TextView empty = text("Нажми «Обновить реестр и проверить ChatGPT», чтобы загрузить маршруты.", 11, false, MUTED);
            empty.setPadding(dp(12), dp(15), dp(12), dp(15));
            routes.addView(empty);
            return;
        }
        for (SmartDnsManager.RouteView r : list) routes.addView(routeRow(r));
    }

    private LinearLayout routeRow(SmartDnsManager.RouteView r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(10));
        card.setBackground(round(r.selected ? 0xFFE9F9F3 : Color.WHITE, 13, r.selected ? 0xFF80DDBA : 0xFFE2E8E5, 1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox enabled = new CheckBox(this);
        enabled.setChecked(r.enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            SmartDnsManager.setRouteEnabled(this, r.id, checked);
            render();
        });
        head.addView(enabled, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = text((r.selected ? "● " : "") + r.name + " · " + r.mode, 13, true, r.selected ? GREEN : TEXT);
        names.addView(name);
        TextView state = text(routeStatus(r), 10, true, routeStatusColor(r));
        state.setPadding(0, dp(2), 0, 0);
        names.addView(state);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(head);

        String perf = "score " + Math.round(r.score);
        if (r.dnsMs >= 0) perf += " · DNS " + r.dnsMs + " ms";
        if (r.tlsMs >= 0) perf += " · TLS " + r.tlsMs + " ms";
        if (r.httpMs >= 0) perf += " · HTTP " + r.httpMs + " ms";
        if (r.statusCode > 0) perf += " · HTTP " + r.statusCode;
        TextView line = text(perf, 10, false, MUTED);
        line.setPadding(dp(42), dp(2), 0, 0);
        card.addView(line);

        String coverage = "ChatGPT " + (r.ok ? "✓" : "×");
        TextView cv = text(coverage, 9, true, MUTED);
        cv.setPadding(dp(42), dp(2), 0, 0);
        card.addView(cv);

        TextView ep = text(r.endpoint, 9, false, MUTED);
        ep.setPadding(dp(42), dp(2), 0, 0);
        card.addView(ep);

        if (r.error != null && !r.error.isEmpty()) {
            TextView er = text(r.error, 9, false, r.blocked ? RED : ORANGE);
            er.setPadding(dp(42), dp(3), 0, 0);
            card.addView(er);
        }

        card.setOnClickListener(v -> {
            if (!r.ok) {
                Toast.makeText(this, "Сначала проверь маршрут: ChatGPT должен быть доступен", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!r.enabled) SmartDnsManager.setRouteEnabled(this, r.id, true);
            SmartDnsManager.setManualRoute(this, r.id);
            Toast.makeText(this, "Закреплён ChatGPT DNS: " + r.name + " · " + r.mode, Toast.LENGTH_SHORT).show();
            render();
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(7));
        card.setLayoutParams(p);
        return card;
    }

    private String routeStatus(SmartDnsManager.RouteView r) {
        if (!r.enabled) return "выключен пользователем";
        if (r.testedAt == 0) return "не проверен";
        if (r.blocked) return "ChatGPT недоступен через этот маршрут";
        if (r.ok) return r.selected ? "работает · выбран" : "работает";
        return "не работает";
    }

    private int routeStatusColor(SmartDnsManager.RouteView r) {
        if (!r.enabled || r.testedAt == 0) return MUTED;
        if (r.blocked || !r.ok) return RED;
        return GREEN;
    }

    private LinearLayout panel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        card.setBackground(round(Color.WHITE, 16, 0xFFE2E8E5, 1));
        return card;
    }

    private TextView action(String label, int color) {
        TextView v = text(label, 13, true, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(color, 14, 0, 0));
        return v;
    }

    private void divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(0xFFEAECEF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.setMargins(0, dp(2), 0, dp(2));
        parent.addView(v, p);
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
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

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}

