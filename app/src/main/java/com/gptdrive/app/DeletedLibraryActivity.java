package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Real ChatGPT Recently Deleted surface inside GPT Drive. Destructive operations stay on ChatGPT's own confirmed UI. */
public final class DeletedLibraryActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        long accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, new DriveDb(this).activeAccountId());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), 0);
        TextView back = text("‹", 29, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));
        TextView title = text("Удалённые", 20, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView note = text("Это реальная корзина ChatGPT. Здесь можно восстановить материал или удалить его окончательно. ChatGPT хранит удалённые материалы до 30 дней.", 10, false);
        note.setTextColor(MUTED);
        note.setPadding(dp(14), dp(8), dp(14), dp(9));
        note.setBackground(round(Color.WHITE, 0, 0xFFE4E8EB, 1));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(AppIdentity.userAgent());
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.WHITE);

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String cookie = AccountSession.cookieHeader(this, accountId, "https://chatgpt.com/");
            if (cookie != null && !cookie.trim().isEmpty()) {
                for (String part : cookie.split(";\\s*")) if (part.contains("=")) cm.setCookie("https://chatgpt.com", part + "; Path=/; Secure");
                cm.flush();
            }
        } catch (Exception ignored) {}

        web.loadUrl("https://chatgpt.com/library?deleted=true");
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
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
