package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
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
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private LinearLayout routes;
    private TextView status, updated;
    private Switch toggle;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SmartDnsManager.initialize(this);
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

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(Color.WHITE, 16, 0xFFE2E8E5, 1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Smart DNS маршрутизация", 15, true, TEXT));
        TextView sub = text("Автовыбор и ротация DNS для ChatGPT", 11, false, MUTED);
        sub.setPadding(0, dp(3), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(54), 1));
        toggle = new Switch(this);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            SmartDnsManager.setEnabled(this, checked);
            render();
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(48)));
        card.addView(row);

        status = text("System DNS", 13, true, GREEN);
        status.setPadding(0, dp(10), 0, dp(3));
        card.addView(status);
        updated = text("", 10, false, MUTED);
        card.addView(updated);

        TextView note = text("DNS меняется только внутри GPT Drive. Список провайдеров автоматически загружается из удалённого OmniChat DNS Registry; системный Private DNS Android не изменяется.", 10, false, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(0, 1.12f);
        card.addView(note);
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView registryTitle = text("Удалённый реестр", 13, true, TEXT);
        registryTitle.setPadding(dp(2), dp(18), 0, dp(5));
        body.addView(registryTitle);
        TextView registry = text(SmartDnsManager.REGISTRY_URL, 10, false, MUTED);
        registry.setPadding(dp(12), dp(10), dp(12), dp(10));
        registry.setBackground(round(Color.WHITE, 12, 0xFFE2E8E5, 1));
        registry.setTextIsSelectable(true);
        body.addView(registry, new LinearLayout.LayoutParams(-1, -2));

        TextView refresh = text("Обновить список и проверить DNS", 13, true, Color.WHITE);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(round(GREEN, 14, 0, 0));
        refresh.setOnClickListener(v -> {
            refresh.setText("Проверяю…");
            refresh.setEnabled(false);
            SmartDnsManager.refreshAndSweep(this, () -> runOnUiThread(() -> {
                refresh.setText("Обновить список и проверить DNS");
                refresh.setEnabled(true);
                render();
                String err = SmartDnsManager.lastRegistryError();
                if (err != null && !err.isEmpty()) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            }));
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(50));
        rp.setMargins(0, dp(12), 0, 0);
        body.addView(refresh, rp);

        TextView rt = text("Доступные маршруты", 13, true, TEXT);
        rt.setPadding(dp(2), dp(20), 0, dp(7));
        body.addView(rt);
        routes = new LinearLayout(this);
        routes.setOrientation(LinearLayout.VERTICAL);
        body.addView(routes);

        sc.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        if (toggle == null) return;
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(SmartDnsManager.isEnabled());
        toggle.setOnCheckedChangeListener((button, checked) -> { SmartDnsManager.setEnabled(this, checked); render(); });
        status.setText(SmartDnsManager.isEnabled() ? "Активен: " + SmartDnsManager.selectedSummary() : "Выключено · System DNS");
        long at = SmartDnsManager.lastUpdated();
        String when = at > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(new Date(at)) : "ещё не загружен";
        String err = SmartDnsManager.lastRegistryError();
        updated.setText("Registry: " + when + (err == null || err.isEmpty() ? "" : " · " + err));
        routes.removeAllViews();
        List<SmartDnsManager.RouteView> list = SmartDnsManager.routes();
        if (list.isEmpty()) {
            TextView empty = text("Включи Smart DNS или нажми «Обновить список», чтобы загрузить маршруты.", 11, false, MUTED);
            empty.setPadding(dp(12), dp(15), dp(12), dp(15));
            routes.addView(empty);
            return;
        }
        for (SmartDnsManager.RouteView r : list) routes.addView(routeRow(r));
    }

    private LinearLayout routeRow(SmartDnsManager.RouteView r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(round(r.selected ? 0xFFE9F9F3 : Color.WHITE, 13, r.selected ? 0xFF80DDBA : 0xFFE2E8E5, 1));
        TextView name = text((r.selected ? "● " : "") + r.name + " · " + r.mode, 13, true, r.selected ? GREEN : TEXT);
        card.addView(name);
        String perf = "score " + Math.round(r.score);
        if (r.dnsMs >= 0) perf += " · DNS " + r.dnsMs + " ms";
        if (r.tlsMs >= 0) perf += " · TLS " + r.tlsMs + " ms";
        if (r.httpMs >= 0) perf += " · HTTP " + r.httpMs + " ms";
        TextView line = text(perf, 10, false, MUTED);
        line.setPadding(0, dp(3), 0, 0);
        card.addView(line);
        TextView ep = text(r.endpoint, 9, false, MUTED);
        ep.setPadding(0, dp(3), 0, 0);
        card.addView(ep);
        if (r.error != null && !r.error.isEmpty()) {
            TextView er = text(r.error, 9, false, 0xFFD92D20);
            er.setPadding(0, dp(3), 0, 0);
            card.addView(er);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(7));
        card.setLayoutParams(p);
        return card;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private GradientDrawable round(int color,int radius,int stroke,int strokeDp){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return g;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
