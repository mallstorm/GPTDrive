package com.gptdrive.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native synchronization host. WebView is no longer used to enumerate Library.
 * It is kept only for normal ChatGPT pages and for refreshing /api/auth/session.
 */
public final class BrowserActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_LOGIN = "new_login";

    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DriveDb db;
    private long accountId;
    private String section;
    private String requestedUrl;
    private boolean freshLogin;
    private boolean retryAfterRefresh;
    private boolean syncing;
    private boolean finished;
    private WebView web;
    private FrameLayout root;
    private LinearLayout syncPanel;
    private TextView syncAccount;
    private TextView syncStatus;
    private TextView syncCount;
    private TextView syncBytes;
    private TextView syncButton;
    private SyncBarsView bars;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "Library";
        requestedUrl = getIntent().getStringExtra(EXTRA_URL);
        freshLogin = getIntent().getBooleanExtra(EXTRA_NEW_LOGIN, false);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        if (freshLogin) {
            showBrowser("https://chatgpt.com/auth/login", true);
            return;
        }
        if (requestedUrl != null && requestedUrl.startsWith("https://")) {
            showBrowser(requestedUrl, false);
            return;
        }

        buildSyncUi();
        AccountSession.switchTo(this, accountId, this::startNativeSync);
    }

    private void showBrowser(String url, boolean fresh) {
        web = new WebView(this);
        configureWebView(web);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        Runnable load = () -> web.loadUrl(url);
        if (fresh) AccountSession.startFresh(this, accountId, load);
        else AccountSession.switchTo(this, accountId, load);
    }

    private void configureWebView(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " GPTDrive/0.6.1");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(view, true);
        view.setWebChromeClient(new WebChromeClient());
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                super.onPageFinished(v, u);
                CookieManager.getInstance().flush();
                AccountSession.snapshot(BrowserActivity.this, accountId);
            }
        });
        view.setDownloadListener((url, ua, disposition, mime, len) -> downloadBrowserUrl(url, ua, disposition, mime));
    }

    private void buildSyncUi() {
        syncPanel = new LinearLayout(this);
        syncPanel.setOrientation(LinearLayout.VERTICAL);
        syncPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        syncPanel.setPadding(dp(20), dp(18), dp(20), dp(22));
        syncPanel.setBackgroundColor(BG);
        root.addView(syncPanel, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_gpt_drive_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("GPT Drive", 20, true, TEXT));
        titles.addView(text("Native API Sync · Windows v0.17.3 provider", 10, false, MUTED));
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView close = text("×", 27, false, MUTED);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> finishDone());
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        syncPanel.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(Color.WHITE, 20, 0xFFE5E9EC, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(22), 0, 0);
        syncPanel.addView(card, cp);

        AccountProfile a = db.accountById(accountId);
        syncAccount = text(a == null ? "ChatGPT" : a.displayName() + "  ·  " + a.planLabel(), 15, true, TEXT);
        card.addView(syncAccount);
        syncStatus = text("Подготовка native API…", 12, false, MUTED);
        syncStatus.setPadding(0, dp(5), 0, 0);
        card.addView(syncStatus);

        bars = new SyncBarsView(this);
        bars.setActive(true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(112));
        bp.setMargins(0, dp(18), 0, dp(8));
        card.addView(bars, bp);

        LinearLayout stats = new LinearLayout(this);
        syncCount = text("Найдено: 0", 13, true, TEXT);
        syncBytes = text(storageText(), 11, false, MUTED);
        syncBytes.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        stats.addView(syncCount, new LinearLayout.LayoutParams(0, dp(34), 1));
        stats.addView(syncBytes, new LinearLayout.LayoutParams(0, dp(34), 1));
        card.addView(stats);

        TextView note = text("Library читается напрямую через backend-api с cursor pagination. При открытии файла GPT Drive получает новую подписанную ссылку по file_id.", 10, false, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(0, 1.12f);
        card.addView(note);

        syncButton = text("Идёт синхронизация…", 13, true, Color.WHITE);
        syncButton.setGravity(Gravity.CENTER);
        syncButton.setBackground(round(GREEN, 14, 0, 0));
        syncButton.setEnabled(false);
        syncButton.setAlpha(.55f);
        syncButton.setOnClickListener(v -> {
            if (finished) finishDone(); else if (!syncing) startNativeSync();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.setMargins(0, dp(20), 0, 0);
        syncPanel.addView(syncButton, sp);
    }

    private void startNativeSync() {
        if (syncing || finished) return;
        syncing = true;
        setStatus("Подключаюсь к ChatGPT backend…", 0);
        worker.execute(() -> {
            try {
                ChatGptApiClient client = new ChatGptApiClient(this, db, accountId);
                ChatGptApiClient.SyncResult result = client.sync((phase, part, total, objects) ->
                        runOnUiThread(() -> setStatus(phase + " " + part + "/" + total + " · " + objects, objects)));
                runOnUiThread(() -> completeSync(result));
            } catch (ChatGptApiClient.AuthException e) {
                runOnUiThread(() -> onAuthExpired(e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> failSync(e.getMessage()));
            }
        });
    }

    private void onAuthExpired(String message) {
        syncing = false;
        if (retryAfterRefresh) {
            failSync(message == null ? "Сессия ChatGPT устарела" : message);
            return;
        }
        retryAfterRefresh = true;
        syncStatus.setText("Обновляю backend-сессию через текущий вход…");
        refreshSessionWithHiddenWebView();
    }

    private void refreshSessionWithHiddenWebView() {
        if (web != null) { try { web.destroy(); } catch (Exception ignored) {} web = null; }
        web = new WebView(this);
        configureWebView(web);
        web.setVisibility(View.INVISIBLE);
        web.addJavascriptInterface(new SessionBridge(), "GPTDriveSessionRefresh");
        root.addView(web, new FrameLayout.LayoutParams(dp(1), dp(1)));
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                AccountSession.snapshot(BrowserActivity.this, accountId);
                if (url != null && url.contains("chatgpt.com")) {
                    String js = "(function(){try{fetch('/api/auth/session',{credentials:'include'}).then(r=>r.text()).then(t=>GPTDriveSessionRefresh.onSession(t)).catch(()=>GPTDriveSessionRefresh.onError());}catch(e){GPTDriveSessionRefresh.onError();}})();";
                    view.evaluateJavascript(js, null);
                }
            }
        });
        AccountSession.switchTo(this, accountId, () -> web.loadUrl("https://chatgpt.com/"));
        root.postDelayed(() -> {
            if (!syncing && retryAfterRefresh && !finished && !ChatGptSessionStore.has(this, accountId)) failSync("Не удалось обновить сессию. Переподключите аккаунт.");
        }, 12000);
    }

    private final class SessionBridge {
        @JavascriptInterface public void onSession(String json) {
            runOnUiThread(() -> {
                if (json != null && ChatGptSessionStore.save(BrowserActivity.this, accountId, json)) {
                    destroyRefreshWebView();
                    startNativeSync();
                } else failSync("ChatGPT не вернул accessToken. Переподключите аккаунт.");
            });
        }
        @JavascriptInterface public void onError() { runOnUiThread(() -> failSync("Не удалось обновить backend-сессию")); }
    }

    private void destroyRefreshWebView() {
        if (web == null) return;
        try {
            web.removeJavascriptInterface("GPTDriveSessionRefresh");
            root.removeView(web);
            web.destroy();
        } catch (Exception ignored) {}
        web = null;
    }

    private void completeSync(ChatGptApiClient.SyncResult r) {
        syncing = false;
        finished = true;
        notifyRoots();
        AccountProfile a = db.accountById(accountId);
        if (a != null) syncAccount.setText(a.displayName() + "  ·  " + a.planLabel());
        syncStatus.setText("Готово · Library " + r.library + " · Images " + r.images + " · Projects " + r.projects + " · Archive " + r.archived);
        syncCount.setText("Library pages: " + r.pages);
        syncBytes.setText(storageText());
        bars.setActive(false);
        syncButton.setText("Готово");
        syncButton.setEnabled(true);
        syncButton.setAlpha(1f);
    }

    private void failSync(String message) {
        syncing = false;
        finished = false;
        bars.setActive(false);
        syncStatus.setText(message == null || message.trim().isEmpty() ? "Ошибка синхронизации" : message);
        syncButton.setText("Повторить");
        syncButton.setEnabled(true);
        syncButton.setAlpha(1f);
    }

    private void setStatus(String status, int objects) {
        if (syncStatus != null) syncStatus.setText(status);
        if (syncCount != null) syncCount.setText("Найдено: " + objects);
        if (bars != null) { bars.setActive(true); bars.pulse(1f); }
    }

    private String storageText() {
        AccountProfile a = db.accountById(accountId);
        if (a != null && a.hasRemoteQuota()) return MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes);
        long known = db.knownStorageBytes(accountId);
        return known > 0 ? "Индекс: " + MainActivity.formatBytes(known) : "Объём: —";
    }

    private void downloadBrowserUrl(String url, String ua, String disposition, String mime) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = AccountSession.cookieHeader(this, accountId, url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Скачивание: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show(); }
    }

    private void finishDone() {
        AccountSession.snapshot(this, accountId);
        notifyRoots();
        finish();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else finishDone();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (web != null) {
            try { web.removeJavascriptInterface("GPTDriveSessionRefresh"); web.destroy(); } catch (Exception ignored) {}
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

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
