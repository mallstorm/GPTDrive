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

import java.util.Locale;

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
    private static final String[] FULL_SECTIONS = {"Library", "Images", "Projects", "Archived"};

    private WebView web;
    private LibraryBridge bridge;
    private DriveDb db;
    private String section;
    private long accountId;
    private boolean freshLogin;
    private boolean syncMode;
    private boolean pageReady;
    private boolean markedComplete;
    private boolean fullSync;
    private boolean phaseAdvancing;
    private int sectionIndex;
    private long lastCount = -1;
    private long lastChangeAt = System.currentTimeMillis();
    private long sectionStartedAt = System.currentTimeMillis();

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
            handler.postDelayed(this, 600);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new DriveDb(this);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "Library";
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        freshLogin = getIntent().getBooleanExtra(EXTRA_NEW_LOGIN, false);
        String requested = getIntent().getStringExtra(EXTRA_URL);
        fullSync = requested == null && "Library".equals(section);
        sectionIndex = indexOfSection(section);
        syncMode = !freshLogin;
        db.pruneFalsePositives(accountId);

        bridge = new LibraryBridge(db, accountId);
        bridge.setCategory(section);
        buildUi();

        Runnable load = () -> {
            if (freshLogin) web.loadUrl("https://chatgpt.com/auth/login");
            else if (requested != null && requested.startsWith("https://")) web.loadUrl(requested);
            else startSection(section);
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
        s.setUserAgentString(s.getUserAgentString() + " GPTDrive/0.5.0");
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
                if (url != null && url.contains("chatgpt.com")) {
                    injectScannerAndApiCapture();
                    boolean targetPage = url.contains(sectionUrlMarker(section)) || (!"Library".equals(section) && !"Images".equals(section));
                    if (targetPage) {
                        pageReady = true;
                        lastChangeAt = System.currentTimeMillis();
                    }
                }
                handler.postDelayed(BrowserActivity.this::updateState, 350);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request != null && request.getUrl() != null) bridge.captureResource(request.getUrl().toString());
                } catch (Exception ignored) {}
                return super.shouldInterceptRequest(view, request);
            }
        });
        web.setDownloadListener((url, ua, contentDisposition, mimetype, len) -> download(url, ua, contentDisposition, mimetype));
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
        TextView sub = text("Полная синхронизация ChatGPT", 11, false, MUTED);
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

        TextView note = text("Читаю структурированные ответы ChatGPT, а не служебные URL. Файлы, изображения, проекты и архив проходят отдельные фазы.", 10, false, MUTED);
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

        TextView hint = text("После завершения индекс доступен в GPT Drive и Android Files. Служебные usage/path/content/favicon больше не считаются файлами.", 10, false, MUTED);
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
            fullSync = true;
            sectionIndex = 0;
            section = FULL_SECTIONS[0];
            bridge.setCategory(section);
            startSection(section);
        }

        if (!syncMode) return;

        db.pruneFalsePositives(accountId);
        a = db.accountById(accountId);
        String account = a.displayName();
        String plan = a.planLabel();
        syncAccount.setText(account + (plan == null || plan.isEmpty() ? "" : "  ·  " + plan));

        long shown = db.countAll(accountId);
        long phaseCount = db.count(accountId, section);
        long bytes = db.knownStorageBytes(accountId);
        syncCount.setText("Найдено: " + shown + " объектов");
        if (a.hasRemoteQuota()) {
            syncBytes.setText(MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes));
        } else {
            syncBytes.setText(bytes > 0 ? "Индекс: " + MainActivity.formatBytes(bytes) : "Объём: определяю…");
        }

        if (phaseCount != lastCount) {
            long delta = lastCount < 0 ? phaseCount : Math.max(1, phaseCount - lastCount);
            bars.pulse(Math.min(1.8f, 0.7f + delta / 12f));
            lastCount = phaseCount;
            lastChangeAt = System.currentTimeMillis();
            markedComplete = false;
            syncButton.setText("Идёт синхронизация…");
            syncButton.setEnabled(false);
            syncButton.setAlpha(.55f);
            bars.setActive(true);
        }

        long now = System.currentTimeMillis();
        long quiet = now - lastChangeAt;
        long elapsed = now - sectionStartedAt;
        String phase = phaseLabel(section);
        int part = fullSync ? Math.max(1, sectionIndex + 1) : 1;
        int totalParts = fullSync ? FULL_SECTIONS.length : 1;

        if (!authenticated) {
            syncStatus.setText("Ожидаю вход в ChatGPT…");
            return;
        }
        if (!pageReady) {
            syncStatus.setText(phase + " " + part + "/" + totalParts + " · открываю…");
            return;
        }

        if (elapsed < 10000 || quiet < 5000) {
            syncStatus.setText(phase + " " + part + "/" + totalParts + " · читаю данные…");
            return;
        }

        if (fullSync && sectionIndex < FULL_SECTIONS.length - 1) {
            advanceSection();
            return;
        }

        if (!markedComplete) {
            db.pruneFalsePositives(accountId);
            db.markSynced(accountId);
            notifyRoots();
            markedComplete = true;
        }
        syncStatus.setText("Синхронизировано · " + friendlyNow());
        syncButton.setText("Готово");
        syncButton.setEnabled(true);
        syncButton.setAlpha(1f);
        bars.setActive(false);
    }

    private void enterSyncMode() {
        syncMode = true;
        web.setVisibility(View.INVISIBLE);
        syncPanel.setVisibility(View.VISIBLE);
        markedComplete = false;
        lastCount = -1;
        lastChangeAt = System.currentTimeMillis();
        sectionStartedAt = lastChangeAt;
        bars.setActive(true);
    }

    private void startSection(String target) {
        if (web == null) return;
        section = target == null ? "Library" : target;
        bridge.setCategory(section);
        pageReady = false;
        phaseAdvancing = false;
        lastCount = db.count(accountId, section);
        lastChangeAt = System.currentTimeMillis();
        sectionStartedAt = lastChangeAt;
        web.loadUrl(startUrl(section));
    }

    private void advanceSection() {
        if (phaseAdvancing) return;
        phaseAdvancing = true;
        sectionIndex++;
        section = FULL_SECTIONS[sectionIndex];
        bridge.setCategory(section);
        pageReady = false;
        lastCount = db.count(accountId, section);
        lastChangeAt = System.currentTimeMillis();
        sectionStartedAt = lastChangeAt;
        syncStatus.setText(phaseLabel(section) + " " + (sectionIndex + 1) + "/" + FULL_SECTIONS.length + " · открываю…");
        handler.postDelayed(() -> startSection(section), 250);
    }

    private int indexOfSection(String value) {
        for (int i = 0; i < FULL_SECTIONS.length; i++) if (FULL_SECTIONS[i].equals(value)) return i;
        return 0;
    }

    private String phaseLabel(String s) {
        if ("Images".equals(s)) return "Изображения";
        if ("Projects".equals(s)) return "Проекты";
        if ("Archived".equals(s)) return "Архив";
        return "Файлы";
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

    private void injectScannerAndApiCapture() {
        String safeSection = section.replace("'", "");
        String js = "(function(){try{" +
                "const sec='" + safeSection + "';" +
                "const relevant=u=>{u=(u||'').toString().toLowerCase();return (u.includes('/backend-api/')||u.includes('/api/'))&&(u.includes('file')||u.includes('library')||u.includes('image')||u.includes('asset')||u.includes('upload')||u.includes('project')||u.includes('archive')||u.includes('conversation')||u.includes('usage')||u.includes('storage')||u.includes('quota')||u.includes('capabilit'));};" +
                "const send=(u,t)=>{try{if(relevant(u)&&t&&t.length<8000000)GPTDriveBridge.captureApi(String(u),String(t));}catch(e){}};" +
                "if(!window.__gptDriveApiHook){window.__gptDriveApiHook=true;" +
                "const of=window.fetch;window.fetch=function(){const a=arguments;return of.apply(this,a).then(r=>{try{const u=(typeof a[0]==='string'?a[0]:(a[0]&&a[0].url)||r.url||'');if(relevant(u))r.clone().text().then(t=>send(u,t)).catch(()=>{});}catch(e){}return r;});};" +
                "const oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this.__gdu=u;return oo.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){try{this.addEventListener('load',function(){try{if(relevant(this.__gdu)&&typeof this.responseText==='string')send(this.__gdu,this.responseText);}catch(e){}});}catch(e){}return os.apply(this,arguments);};}" +
                "try{[...performance.getEntriesByType('resource')].map(x=>x.name).filter(relevant).slice(-80).forEach(u=>{try{of(u,{credentials:'include'}).then(r=>r.text()).then(t=>send(u,t)).catch(()=>{});}catch(e){}});}catch(e){}" +
                "try{of('/api/auth/session',{credentials:'include'}).then(r=>r.text()).then(t=>{try{GPTDriveBridge.captureAccount(t);}catch(e){}}).catch(()=>{});}catch(e){}" +
                "function bytes(t){try{const m=((t||'')+'').replace(',','.').match(/([0-9]+(?:\\.[0-9]+)?)\\s*(KB|MB|GB|TB|КБ|МБ|ГБ|ТБ)/i);if(!m)return 0;const n=parseFloat(m[1]);const u=m[2].toUpperCase();let k=1;if(u==='KB'||u==='КБ')k=1024;else if(u==='MB'||u==='МБ')k=1048576;else if(u==='GB'||u==='ГБ')k=1073741824;else if(u==='TB'||u==='ТБ')k=1099511627776;return Math.round(n*k);}catch(e){return 0;}}" +
                "function ctx(e){try{const p=e.closest('li,article,[role=row],[data-testid]')||e.parentElement;return p?((p.innerText||'')+''):'';}catch(x){return '';}}" +
                "function scan(){try{const out=[];document.querySelectorAll('a[href]').forEach(a=>{const u=a.href||'',l=u.toLowerCase(),n=(a.innerText||a.getAttribute('aria-label')||a.title||'').trim();let kind='';if(sec==='Projects'&&(l.includes('/project')||l.includes('/g/g-p-')))kind='link';if(sec==='Archived'&&l.includes('/c/'))kind='link';if(kind||l.includes('oaiusercontent')||/\\/files\\/file[-_][a-z0-9_-]+\\/content/i.test(l))out.push({url:u,name:n,kind:kind,sizeBytes:bytes(ctx(a))});});document.querySelectorAll('img[src],img[srcset]').forEach(i=>{const u=i.currentSrc||i.src||'',l=u.toLowerCase();if(!u||l.includes('favicon')||l.includes('/assets/')||l.includes('static')||l.includes('logo'))return;out.push({url:u,name:(i.alt||i.getAttribute('aria-label')||'').trim(),mime:'image/*',sizeBytes:bytes(ctx(i))});});GPTDriveBridge.capture(JSON.stringify(out.slice(0,2000)));}catch(e){}}" +
                "scan();if(!window.__gptDriveObserver){window.__gptDriveObserver=true;let tm=null;new MutationObserver(()=>{clearTimeout(tm);tm=setTimeout(scan,500);}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['href','src','srcset']});}" +
                "if(!window.__gptDriveAutoScroll){window.__gptDriveAutoScroll=true;let rounds=0;const iv=setInterval(()=>{try{scan();const els=[document.scrollingElement,...document.querySelectorAll('main,section,div')].filter(Boolean).filter(e=>e.scrollHeight>e.clientHeight+180);els.sort((a,b)=>(b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight));const e=els[0];if(e)e.scrollTop=Math.min(e.scrollHeight,e.scrollTop+Math.max(300,e.clientHeight*.8));rounds++;if(rounds>=24)clearInterval(iv);}catch(e){clearInterval(iv);}},700);}" +
                "if(sec==='Projects'||sec==='Archived'){setTimeout(()=>{try{const terms=sec==='Projects'?['project','projects','проект','проекты']:['archived','archive','архив','архивированные'];const els=[...document.querySelectorAll('a,button,[role=button]')];const e=els.find(x=>{const t=((x.innerText||x.getAttribute('aria-label')||'')+'').toLowerCase();return terms.some(k=>t.includes(k));});if(e)e.click();}catch(e){}},900);}" +
                "}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = AccountSession.cookieHeader(this, accountId, url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
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
        db.pruneFalsePositives(accountId);
        if (markedComplete) db.markSynced(accountId);
        notifyRoots();
        finish();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    private String friendlyNow() {
        return new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
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
