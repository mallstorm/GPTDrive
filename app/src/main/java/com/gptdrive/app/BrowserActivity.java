package com.gptdrive.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class BrowserActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    private WebView web;
    private LibraryBridge bridge;
    private String section;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "Library";
        bridge = new LibraryBridge(new DriveDb(this));
        bridge.setCategory(section);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFFFFFFFF);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12), dp(8), dp(8), dp(8));
        Button back = new Button(this); back.setText("‹"); back.setOnClickListener(v -> { if (web != null && web.canGoBack()) web.goBack(); else finish(); });
        TextView title = new TextView(this); title.setText(section); title.setTextSize(19); title.setTextColor(0xFF111111); title.setPadding(dp(10),0,0,0);
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setLoadsImagesAutomatically(true); s.setUserAgentString(s.getUserAgentString() + " GPTDrive/0.1");
        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(bridge, "GPTDriveBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectScanner();
                if (url.startsWith("https://chatgpt.com")) getPreferences(MODE_PRIVATE).edit().putBoolean("opened_chatgpt", true).apply();
            }
        });
        web.setDownloadListener((url, ua, contentDisposition, mimetype, len) -> download(url, ua, contentDisposition, mimetype));
        root.addView(web, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        web.loadUrl(startUrl(section));
    }

    private String startUrl(String s) {
        if ("Library".equals(s)) return "https://chatgpt.com/library";
        if ("Images".equals(s)) return "https://chatgpt.com/images";
        return "https://chatgpt.com/";
    }

    private void injectScanner() {
        String target = section.replace("'", "").toLowerCase();
        String js = "(function(){" +
                "function scan(){try{const out=[];document.querySelectorAll('a[href]').forEach(a=>{const u=a.href||'';const n=(a.innerText||a.getAttribute('aria-label')||a.title||'').trim();if(u)out.push({url:u,name:n});});document.querySelectorAll('img[src]').forEach(i=>{const u=i.src||'';const n=(i.alt||i.getAttribute('aria-label')||'').trim();if(u)out.push({url:u,name:n,mime:'image/*'});});GPTDriveBridge.capture(JSON.stringify(out.slice(0,600)));}catch(e){}}scan();new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});" +
                (("projects".equals(target) || "archived".equals(target)) ? "setTimeout(()=>{const t='"+target+"';const es=[...document.querySelectorAll('a,button,[role=button]')];const e=es.find(x=>((x.innerText||x.getAttribute('aria-label')||'').toLowerCase().includes(t)));if(e)e.click();},1200);" : "") +
                "})();";
        web.evaluateJavascript(js, null);
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url); if (cookie != null) r.addRequestHeader("Cookie", cookie); if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Скачивание: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (web != null) { web.removeJavascriptInterface("GPTDriveBridge"); web.destroy(); } super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
