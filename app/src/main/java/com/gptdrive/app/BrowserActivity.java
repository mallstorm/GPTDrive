package com.gptdrive.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class BrowserActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_LOGIN = "new_login";
    private static final String AUTHORITY = "com.gptdrive.app.documents";

    private WebView web;
    private LibraryBridge bridge;
    private DriveDb db;
    private String section;
    private long accountId;
    private TextView title;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            if (title != null && db != null) {
                AccountProfile a = db.accountById(accountId);
                String who = a == null ? "" : a.displayName();
                String plan = a == null ? "" : a.planLabel();
                title.setText(section + " · " + db.count(accountId, section) + (who.isEmpty() ? "" : " · " + who) + (plan.isEmpty() ? "" : " · " + plan));
            }
            handler.postDelayed(this, 1200);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new DriveDb(this);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "Library";
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        bridge = new LibraryBridge(db, accountId);
        bridge.setCategory(section);
        buildUi();

        boolean fresh = getIntent().getBooleanExtra(EXTRA_NEW_LOGIN, false);
        Runnable load = () -> {
            String requested = getIntent().getStringExtra(EXTRA_URL);
            if (fresh) web.loadUrl("https://chatgpt.com/auth/login");
            else if (requested != null && requested.startsWith("https://")) web.loadUrl(requested);
            else web.loadUrl(startUrl(section));
        };
        if (fresh) AccountSession.startFresh(this, accountId, load);
        else AccountSession.switchTo(this, accountId, load);
        handler.post(statusLoop);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(v -> { if (web != null && web.canGoBack()) web.goBack(); else finishDone(); });
        title = new TextView(this);
        title.setText(section);
        title.setTextSize(17);
        title.setTextColor(0xFF111111);
        title.setPadding(dp(8),0,0,0);
        title.setMaxLines(1);
        Button done = new Button(this);
        done.setText("Готово");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finishDone());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(done, new LinearLayout.LayoutParams(dp(88), dp(48)));
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("Вход и синхронизация активного аккаунта. После загрузки нажми «Готово».");
        hint.setTextSize(12);
        hint.setTextColor(0xFF777777);
        hint.setPadding(dp(14),dp(3),dp(14),dp(6));
        root.addView(hint);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " GPTDrive/0.4");
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
                if (url != null && url.contains("chatgpt.com")) injectScanner();
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request != null && request.getUrl() != null) bridge.captureResource(request.getUrl().toString());
                } catch (Exception ignored) {}
                return super.shouldInterceptRequest(view, request);
            }
        });
        web.setDownloadListener((url, ua, contentDisposition, mimetype, len) -> download(url, ua, contentDisposition, mimetype));
        root.addView(web, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
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
        db.markSynced(accountId);
        notifyRoots();
        finish();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    @Override protected void onPause() {
        AccountSession.snapshot(this, accountId);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else finishDone();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusLoop);
        if (web != null) {
            web.removeJavascriptInterface("GPTDriveBridge");
            web.destroy();
        }
        super.onDestroy();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
