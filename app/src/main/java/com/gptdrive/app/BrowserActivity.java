package com.gptdrive.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

    private WebView web;
    private LibraryBridge bridge;
    private DriveDb db;
    private String section;
    private TextView title;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            if (title != null && db != null) title.setText(section + "  ·  " + db.count(section));
            handler.postDelayed(this, 1200);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "Library";
        db = new DriveDb(this);
        bridge = new LibraryBridge(db);
        bridge.setCategory(section);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(v -> { if (web != null && web.canGoBack()) web.goBack(); else finish(); });
        title = new TextView(this);
        title.setText(section);
        title.setTextSize(18);
        title.setTextColor(0xFF111111);
        title.setPadding(dp(8), 0, 0, 0);
        Button done = new Button(this);
        done.setText("Готово");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(done, new LinearLayout.LayoutParams(dp(88), dp(48)));
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("Это окно только для входа и синхронизации. После загрузки нажми «Готово». ");
        hint.setTextSize(12);
        hint.setTextColor(0xFF777777);
        hint.setPadding(dp(14), dp(3), dp(14), dp(6));
        root.addView(hint);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " GPTDrive/0.2");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(bridge, "GPTDriveBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
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
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        String requested = getIntent().getStringExtra(EXTRA_URL);
        if (requested != null && requested.startsWith("https://")) web.loadUrl(requested);
        else web.loadUrl(startUrl(section));
        handler.post(statusLoop);
    }

    private String startUrl(String s) {
        if ("Library".equals(s)) return "https://chatgpt.com/library";
        if ("Images".equals(s)) return "https://chatgpt.com/images";
        return "https://chatgpt.com/";
    }

    private void injectScanner() {
        String safeSection = section.replace("'", "");
        String js = "(function(){" +
                "const sec='" + safeSection + "';let timer=null;" +
                "function scan(){try{const out=[];" +
                "document.querySelectorAll('a[href]').forEach(a=>{const u=a.href||'';const n=(a.innerText||a.getAttribute('aria-label')||a.title||'').trim();let kind='';if(sec==='Projects'&&(u.includes('/project')||u.includes('/projects')||u.includes('/g/g-p-')))kind='link';if(sec==='Archived'&&u.includes('/c/'))kind='link';if(u)out.push({url:u,name:n,kind:kind});});" +
                "document.querySelectorAll('img[src],img[srcset]').forEach(i=>{const u=i.currentSrc||i.src||'';const n=(i.alt||i.getAttribute('aria-label')||'').trim();if(u)out.push({url:u,name:n,mime:'image/*'});});" +
                "document.querySelectorAll('video[src],audio[src],source[src]').forEach(i=>{const u=i.src||'';if(u)out.push({url:u,name:'Media'});});" +
                "GPTDriveBridge.capture(JSON.stringify(out.slice(0,1200)));}catch(e){}}" +
                "scan();new MutationObserver(()=>{clearTimeout(timer);timer=setTimeout(scan,550);}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['href','src','srcset']});" +
                "if(sec==='Projects'||sec==='Archived'){setTimeout(()=>{try{const terms=sec==='Projects'?['project','projects','проект','проекты']:['archived','archive','архив','архивированные'];const els=[...document.querySelectorAll('a,button,[role=button]')];const e=els.find(x=>{const t=((x.innerText||x.getAttribute('aria-label')||'')+'').toLowerCase();return terms.some(k=>t.includes(k));});if(e)e.click();}catch(e){}},1000);}" +
                "})();";
        web.evaluateJavascript(js, null);
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Скачивание: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
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
