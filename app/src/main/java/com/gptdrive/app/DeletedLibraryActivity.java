package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Official ChatGPT Library "Recently deleted" surface embedded in GPT Drive.
 * We intentionally rely on ChatGPT's own UI for listing, restore and permanent delete
 * instead of guessing private backend endpoints that may change without notice.
 */
public final class DeletedLibraryActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_URL = "url";       // legacy compatibility
    public static final String EXTRA_TITLE = "title";   // legacy compatibility
    public static final String EXTRA_NOTE = "note";     // legacy compatibility

    private static final String CHATGPT = "https://chatgpt.com";
    private static final String DELETED_URL = CHATGPT + "/library?deleted=true";
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private WebView web;
    private ProgressBar progress;
    private TextView state;
    private long accountId;

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        DriveDb db = new DriveDb(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        buildUi(db.accountById(accountId));
        seedSessionCookies();
        web.loadUrl(DELETED_URL);
    }

    private void buildUi(AccountProfile account) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(10), 0);
        bar.setBackgroundColor(Color.WHITE);

        TextView back = text("‹", 30, false, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> goBack());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Удалённые", 20, true, TEXT);
        title.setSingleLine(true);
        String who = account == null ? "ChatGPT Library" : "ChatGPT Library  •  " + account.displayName();
        TextView subtitle = text(who, 10, false, MUTED);
        subtitle.setSingleLine(true);
        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, dp(56), 1));

        TextView refresh = text("↻", 23, false, GREEN);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(round(0xFFEAF8F2, 18, 0, 0));
        refresh.setOnClickListener(v -> {
            seedSessionCookies();
            web.reload();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(38), dp(38));
        rp.setMargins(dp(4), 0, 0, 0);
        bar.addView(refresh, rp);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(12), dp(7), dp(12), dp(7));
        info.setBackground(round(Color.WHITE, 14, 0xFFE4E8EB, 1));
        TextView badge = text("ChatGPT", 10, true, GREEN);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(0xFFEAF8F2, 11, 0, 0));
        info.addView(badge, new LinearLayout.LayoutParams(dp(70), dp(30)));
        TextView note = text("Официальная корзина Library — восстановление и окончательное удаление выполняет сам ChatGPT.", 10, false, MUTED);
        note.setPadding(dp(10), 0, 0, 0);
        info.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(dp(12), dp(4), dp(12), dp(7));
        root.addView(info, ip);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(round(Color.WHITE, 16, 0xFFE4E8EB, 1));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(AppIdentity.userAgent());
        web.setBackgroundColor(Color.WHITE);
        web.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(web, true); } catch (Exception ignored) {}

        state = text("Открываю ChatGPT Library…", 11, false, MUTED);
        state.setGravity(Gravity.CENTER);
        state.setBackgroundColor(Color.WHITE);
        state.setVisibility(View.GONE);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                state.setVisibility(View.GONE);
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String text = error == null ? "Не удалось открыть Library" : String.valueOf(error.getDescription());
                    state.setText("Не удалось открыть ChatGPT Library: " + text + "\nНажмите ↻, чтобы повторить.");
                    state.setVisibility(View.VISIBLE);
                }
            }
        });

        holder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        holder.addView(state, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, 0, 1);
        hp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(holder, hp);
        setContentView(root);
    }

    private void seedSessionCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String cookie = AccountSession.cookieHeader(this, accountId, CHATGPT + "/");
            if (cookie != null && !cookie.trim().isEmpty()) {
                for (String part : cookie.split(";\\s*")) {
                    if (!part.contains("=")) continue;
                    cm.setCookie(CHATGPT, part + "; Path=/; Secure; SameSite=None");
                }
                cm.flush();
            }
        } catch (Exception ignored) {}
    }

    private void goBack() {
        if (web != null && web.canGoBack()) web.goBack(); else finish();
    }

    @Override public void onBackPressed() { goBack(); }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
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

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
