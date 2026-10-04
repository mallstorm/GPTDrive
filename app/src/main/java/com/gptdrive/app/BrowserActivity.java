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
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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

    private WebView web;
    private LibraryBridge bridge;
    private DriveDb db;
    private String section;
    private long accountId;
    private boolean freshLogin;
    private boolean syncMode;
    private boolean pageReady;
    private boolean markedComplete;
    private long lastCount = -1;
    private long lastChangeAt = System.currentTimeMillis();

    private LinearLayout syncPanel;
    private TextView syncAccount;
    private TextView syncStatus;
    private TextView syncCount;
    private TextView syncBytes;
    private TextView syncButton;
    private SyncBarsView bars;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            updateState();
            handler.postDelayed(this, 450);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new DriveDb(this);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "Library";
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        freshLogin = getIntent().getBooleanExtra(EXTRA_NEW_LOGIN, false);
        syncMode = !freshLogin;
        bridge = new LibraryBridge(db, accountId);
        bridge.setCategory(section);
        buildUi();

        Runnable load = () -> {
            String requested = getIntent().getStringExtra(EXTRA_URL);
            if (freshLogin) web.loadUrl("https://chatgpt.com/auth/login");
            else if (requested != null && requested.startsWith("https://")) web.loadUrl(requested);
            else web.loadUrl(startUrl(section));
        };
        if (freshLogin) AccountSession.startFresh(this, accountId, load);
        else AccountSession.switchTo(this, accountId, load);
        handler.post(statusLoop);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        configureWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        syncPanel = buildSyncPanel();
        root.addView(syncPanel, new FrameLayout.LayoutParams(-1, -1));

        if (freshLogin) {
            web.setVisibility(View.VISIBLE);
            syncPanel.setVisibility(View.GONE);
        } else {
            web.setVisibility(View.INVISIBLE);
            syncPanel.setVisibility(View.VISIBLE);
        }
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " GPTDrive/0.4.8");
        if (BrowserSessionStore.has(this, accountId)) s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(bridge, "GPTDriveBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                AccountSession.snapshot(BrowserActivity.this, accountId);
                String imported = BrowserSessionStore.load(BrowserActivity.this, accountId);
                if (BrowserSessionStore.isUsable(imported)) bridge.captureAccount(imported);
                if (url != null && url.contains("chatgpt.com")) {
                    injectScanner();
                    if (url.contains("/library") || url.contains("/images") || (!"Library".equals(section) && !"Images".equals(section))) {
                        pageReady = true;
                    }
                }
                handler.postDelayed(BrowserActivity.this::updateState, 350);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    WebResourceResponse imported = importedSessionResponse(request);
                    if (imported != null) return imported;
                    if (request != null && request.getUrl() != null) bridge.captureResource(request.getUrl().toString());
                } catch (Exception ignored) {}
                return super.shouldInterceptRequest(view, request);
            }
        });
        web.setDownloadListener((url, ua, contentDisposition, mimetype, len) -> download(url, ua, contentDisposition, mimetype));
    }

    private WebResourceResponse importedSessionResponse(WebResourceRequest request) {
        if (request == null || request.getUrl() == null || !"GET".equalsIgnoreCase(request.getMethod())) return null;
        Uri uri = request.getUrl();
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null || !host.equalsIgnoreCase("chatgpt.com") || !"/api/auth/session".equals(path)) return null;
        String json = BrowserSessionStore.load(this, accountId);
        if (!BrowserSessionStore.isUsable(json)) return null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store, no-cache, must-revalidate");
        headers.put("Pragma", "no-cache");
        headers.put("Content-Type", "application/json; charset=utf-8");
        return new WebResourceResponse(
                "application/json",
                "UTF-8",
                200,
                "OK",
                headers,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private LinearLayout buildSyncPanel() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(22));
        page.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_gpt_drive_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView app = text("GPT Drive", 20, true, TEXT);
        TextView sub = text("Синхронизация библиотеки", 11, false, MUTED);
        titles.addView(app);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1));

        TextView close = text("×", 27, false, MUTED);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> finishDone());
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(Color.WHITE, 20, 0xFFE5E9EC, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(22), 0, 0);
        page.addView(card, cp);

        syncAccount = text("Подключение к ChatGPT…", 15, true, TEXT);
        syncAccount.setSingleLine(true);
        card.addView(syncAccount);

        syncStatus = text("Подготовка библиотеки", 12, false, MUTED);
        syncStatus.setPadding(0, dp(5), 0, 0);
        card.addView(syncStatus);

        bars = new SyncBarsView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(112));
        bp.setMargins(0, dp(18), 0, dp(8));
        card.addView(bars, bp);

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        syncCount = text("Найдено: 0 объектов", 13, true, TEXT);
        syncBytes = text("Объём: —", 11, false, MUTED);
        syncBytes.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        stats.addView(syncCount, new LinearLayout.LayoutParams(0, dp(34), 1));
        stats.addView(syncBytes, new LinearLayout.LayoutParams(0, dp(34), 1));
        card.addView(stats);

        TextView note = text("GPT Drive индексирует доступные файлы и изображения в фоне. Процент не показывается, пока ChatGPT не сообщает точный общий объём.", 10, false, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(0, 1.12f);
        card.addView(note);

        syncButton = text("Идёт синхронизация…", 13, true, Color.WHITE);
        syncButton.setGravity(Gravity.CENTER);
        syncButton.setBackground(round(GREEN, 14, 0, 0));
        syncButton.setAlpha(.55f);
        syncButton.setEnabled(false);
        syncButton.setOnClickListener(v -> finishDone());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.setMargins(0, dp(20), 0, 0);
        page.addView(syncButton, sp);

        TextView hint = text("После завершения все найденные материалы появятся в GPT Drive и в Android Files.", 10, false, MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(18), dp(13), dp(18), 0);
        page.addView(hint);
        return page;
    }

    private void updateState() {
        if (db == null) return;
        AccountProfile a = db.accountById(accountId);
        if (a == null) return;

        boolean authenticated = a.hasSession() && a.email != null && !a.email.trim().isEmpty();
        if (freshLogin && !syncMode && authenticated) {
            enterSyncMode();
            String current = web == null ? "" : web.getUrl();
            String target = startUrl(section);
            if (current == null || !current.contains(sectionUrlMarker(section))) {
                pageReady = false;
                web.loadUrl(target);
            }
        }

        if (!syncMode) return;

        String account = a.displayName();
        String plan = a.planLabel();
        syncAccount.setText(account + (plan == null || plan.isEmpty() ? "" : "  ·  " + plan));

        long count = db.count(accountId, section);
        long total = db.countAll(accountId);
        long shown = "Library".equals(section) ? total : count;
        long bytes = db.knownStorageBytes(accountId);
        syncCount.setText("Найдено: " + shown + " объектов");
        syncBytes.setText(bytes > 0 ? "Объём: " + MainActivity.formatBytes(bytes) : "Объём: —");

        if (shown != lastCount) {
            long delta = lastCount < 0 ? shown : Math.max(1, shown - lastCount);
            bars.pulse(Math.min(1.8f, 0.7f + delta / 12f));
            lastCount = shown;
            lastChangeAt = System.currentTimeMillis();
            markedComplete = false;
            syncStatus.setText("Сканирование библиотеки…  +" + Math.max(0, delta));
            syncButton.setText("Идёт синхронизация…");
            syncButton.setEnabled(false);
            syncButton.setAlpha(.55f);
            bars.setActive(true);
        } else {
            long quiet = System.currentTimeMillis() - lastChangeAt;
            if (pageReady && authenticated && quiet > 3500) {
                syncStatus.setText("Синхронизировано  ·  " + friendlyNow());
                syncButton.setText("Готово");
                syncButton.setEnabled(true);
                syncButton.setAlpha(1f);
                bars.setActive(false);
                if (!markedComplete) {
                    db.markSynced(accountId);
                    notifyRoots();
                    markedComplete = true;
                }
            } else {
                syncStatus.setText(pageReady ? "Проверяю новые материалы…" : "Открываю библиотеку ChatGPT…");
            }
        }
    }

    private void enterSyncMode() {
        syncMode = true;
        web.setVisibility(View.INVISIBLE);
        syncPanel.setVisibility(View.VISIBLE);
        lastChangeAt = System.currentTimeMillis();
        bars.setActive(true);
    }

    private String sectionUrlMarker(String s) {
        if ("Library".equals(s)) return "/library";
        if ("Images".equals(s)) return "/images";
        return "chatgpt.com";
    }

    private String startUrl(String s) {
        if ("Library".equals(s)) return "https://chatgpt.com/library";
        if ("Images".equals(s)) return "https://chatgpt.com/images";
        return "https://chatgpt.com/";
    }

    private void injectScanner() {
        String safeSection = section.replace("'", "");
        String js = "(function(){const sec='" + safeSection + "';let timer=null;" +
                "function bytes(t){try{const m=((t||'')+'').replace(',','.').match(/([0-9]+(?:\\.[0-9]+)?)\\s*(KB|MB|GB|TB|КБ|МБ|ГБ|ТБ)/i);if(!m)return 0;const n=parseFloat(m[1]);const u=m[2].toUpperCase();let k=1;if(u==='KB'||u==='КБ')k=1024;else if(u==='MB'||u==='МБ')k=1048576;else if(u==='GB'||u==='ГБ')k=1073741824;else if(u==='TB'||u==='ТБ')k=1099511627776;return Math.round(n*k);}catch(e){return 0;}}" +
                "function contextText(e){try{const p=e.closest('li,article,[role=row],[data-testid]')||e.parentElement;return p?((p.innerText||'')+''):'';}catch(x){return '';}}" +
                "try{fetch('/api/auth/session',{credentials:'include'}).then(r=>r.json()).then(s=>GPTDriveBridge.captureAccount(JSON.stringify(s))).catch(()=>{});}catch(e){}" +
                "function scan(){try{const out=[];document.querySelectorAll('a[href]').forEach(a=>{const u=a.href||'';const n=(a.innerText||a.getAttribute('aria-label')||a.title||'').trim();let kind='';if(sec==='Projects'&&(u.includes('/project')||u.includes('/projects')||u.includes('/g/g-p-')))kind='link';if(sec==='Archived'&&u.includes('/c/'))kind='link';const sizeBytes=bytes(contextText(a));if(u)out.push({url:u,name:n,kind:kind,sizeBytes:sizeBytes});});document.querySelectorAll('img[src],img[srcset]').forEach(i=>{const u=i.currentSrc||i.src||'';const n=(i.alt||i.getAttribute('aria-label')||'').trim();const sizeBytes=bytes(contextText(i));if(u)out.push({url:u,name:n,mime:'image/*',sizeBytes:sizeBytes});});document.querySelectorAll('video[src],audio[src],source[src]').forEach(i=>{const u=i.src||'';const sizeBytes=bytes(contextText(i));if(u)out.push({url:u,name:'Media',sizeBytes:sizeBytes});});GPTDriveBridge.capture(JSON.stringify(out.slice(0,1500)));}catch(e){}}" +
                "scan();new MutationObserver(()=>{clearTimeout(timer);timer=setTimeout(scan,500);}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['href','src','srcset']});" +
                "if(sec==='Projects'||sec==='Archived'){setTimeout(()=>{try{const terms=sec==='Projects'?['project','projects','проект','проекты']:['archived','archive','архив','архивированные'];const els=[...document.querySelectorAll('a,button,[role=button]')];const e=els.find(x=>{const t=((x.innerText||x.getAttribute('aria-label')||'')+'').toLowerCase();return terms.some(k=>t.includes(k));});if(e)e.click();}catch(e){}},1000);}})();";
        web.evaluateJavascript(js, null);
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = AccountSession.cookieHeader(this, accountId, url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            String bearer = AccountSession.bearerToken(this, accountId);
            if (bearer != null && !bearer.isEmpty()) r.addRequestHeader("Authorization", "Bearer " + bearer);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Скачивание: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show();
        }
    }

    private void finishDone() {
        AccountSession.snapshot(this, accountId);
        AccountProfile a = db.accountById(accountId);
        if (a != null && a.hasSession()) db.markSynced(accountId);
        notifyRoots();
        finish();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    private String friendlyNow() {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
    }

    @Override protected void onPause() {
        AccountSession.snapshot(this, accountId);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (!syncMode && web != null && web.canGoBack()) web.goBack();
        else finishDone();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusLoop);
        if (web != null) {
            web.removeJavascriptInterface("GPTDriveBridge");
            web.destroy();
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
