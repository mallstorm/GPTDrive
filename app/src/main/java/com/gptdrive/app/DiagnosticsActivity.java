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
import java.util.Locale;

/** Credential-free diagnostics for backend, sync surfaces and Smart DNS. */
public final class DiagnosticsActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private DriveDb db;
    private LinearLayout body;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        SmartDnsManager.initialize(this);
        buildUi();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(8),0,dp(14),0);
        TextView back = text("‹",30,false,TEXT); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(44),dp(54)));
        top.addView(text("Диагностика GPT Drive",20,true,TEXT),new LinearLayout.LayoutParams(0,dp(54),1));
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(58)));

        ScrollView sc = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(14),dp(6),dp(14),dp(24));
        sc.addView(body,new ScrollView.LayoutParams(-1,-2)); root.addView(sc,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        AccountProfile a = db.activeAccount();
        if (a == null) { body.addView(card("Аккаунт", "Нет активного аккаунта", false)); return; }

        boolean oauth = ChatGptOAuthStore.has(this,a.id);
        ChatGptSessionStore.Session session = ChatGptSessionStore.load(this,a.id);
        String identity = a.displayName() + "\nOAuth: " + (oauth?"✓":"—") + " · Library Session: " + (session!=null?"✓":"—");
        if (session != null && session.accountId != null && !session.accountId.isEmpty()) identity += "\nAccount ID: …" + tail(session.accountId,8);
        identity += "\nПоследняя синхронизация: " + when(a.lastSync);
        body.addView(card("Аккаунт",identity,session!=null));

        String counts = "Library: " + db.count(a.id,"Library") +
                "\nImages: " + db.count(a.id,"Images") +
                "\nProjects: " + db.count(a.id,"Projects") +
                "\nArchive: " + db.count(a.id,"Archived") +
                "\nИндекс: " + MainActivity.formatBytes(db.knownStorageBytes(a.id));
        body.addView(card("Локальный индекс",counts,true));

        List<SyncStateRepository.Entry> states = SyncStateRepository.list(db,a.id);
        if (states.isEmpty()) body.addView(card("Sync state","Синхронизация ещё не записывала диагностическое состояние.",false));
        else {
            StringBuilder s = new StringBuilder();
            for (SyncStateRepository.Entry e : states) {
                if (s.length()>0) s.append('\n');
                s.append(e.surface).append(": ").append(e.lastError.isEmpty()?"✓":"⚠").append(" · ").append(e.count).append(" объектов");
                if (e.lastSuccess>0) s.append(" · ").append(when(e.lastSuccess));
                if (!e.meta.isEmpty()) s.append(" · ").append(e.meta);
                if (!e.lastError.isEmpty()) s.append("\n  ").append(e.lastError);
            }
            body.addView(card("Состояние поверхностей",s.toString(),true));
        }

        DiagnosticsState.Snapshot d = DiagnosticsState.snapshot();
        String runtime = "Последний HTTP: " + (d.endpoint.isEmpty()?"—":d.method+" "+d.endpoint+" → "+d.status+" · "+d.durationMs+" ms") +
                "\nLibrary strategy: " + (d.strategy<0?"initial":Integer.toString(d.strategy)) +
                " · pages " + d.pages + " · objects " + d.objects;
        if (!d.error.isEmpty()) runtime += "\nПоследняя ошибка: " + d.errorSurface + " · " + d.error;
        body.addView(card("Backend runtime",runtime,d.status>0 && d.status<400));

        String dns = "Smart DNS: " + (SmartDnsManager.isEnabled()?"включён":"выключен") +
                "\nМаршрут: " + SmartDnsManager.selectedSummary() +
                "\nRegistry: " + when(SmartDnsManager.lastUpdated());
        String registryError = SmartDnsManager.lastRegistryError();
        if (registryError!=null && !registryError.isEmpty()) dns += "\nRegistry error: " + registryError;
        body.addView(card("Routing & DNS",dns,!SmartDnsManager.isEnabled() || registryError==null || registryError.isEmpty()));

        List<SmartDnsManager.RouteView> routes = SmartDnsManager.routes();
        if (!routes.isEmpty()) {
            StringBuilder r = new StringBuilder();
            for (SmartDnsManager.RouteView x : routes) {
                if (r.length()>0) r.append('\n');
                r.append(x.selected?"● ":"○ ").append(x.name).append(" · ").append(x.mode).append(" · score ").append(Math.round(x.score));
                if (x.dnsMs>=0) r.append(" · DNS ").append(x.dnsMs).append(" ms");
                if (x.tlsMs>=0) r.append(" · TLS ").append(x.tlsMs).append(" ms");
                if (x.httpMs>=0) r.append(" · HTTP ").append(x.httpMs).append(" ms");
            }
            body.addView(card("DNS routes",r.toString(),true));
        }

        TextView privacy = text("AccessToken, Session JSON, Authorization, cookies и signed URL здесь намеренно не отображаются и не сохраняются в диагностике.",10,false,MUTED);
        privacy.setPadding(dp(4),dp(10),dp(4),0); body.addView(privacy);
    }

    private LinearLayout card(String title,String value,boolean ok) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14),dp(12),dp(14),dp(12));
        c.setBackground(round(Color.WHITE,14,ok?0xFFE2E8E5:0xFFFFD9A8,1));
        TextView h=text(title,13,true,ok?GREEN:0xFF8A5A00); c.addView(h);
        TextView v=text(value,11,false,TEXT); v.setPadding(0,dp(5),0,0); v.setTextIsSelectable(true); c.addView(v);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(8)); c.setLayoutParams(p); return c;
    }
    private String when(long t){return t<=0?"—":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.getDefault()).format(new Date(t));}
    private static String tail(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(s.length()-n);}
    private TextView text(String s,int sp,boolean bold,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius,int stroke,int strokeDp){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return g;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
