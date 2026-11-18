package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
 * Locked official ChatGPT Library "Recently deleted" surface embedded in GPT Drive.
 * The WebView is intentionally restricted to /library?deleted=true so it behaves like
 * an application screen instead of a general browser. Restore/permanent-delete actions
 * remain handled by ChatGPT's own authenticated UI.
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
    private boolean correctingNavigation;

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        DriveDb db = DriveDb.get(this);
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
        back.setOnClickListener(v -> closePreviewOrFinish());
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

        TextView refresh = text("⟳", 22, true, Color.WHITE);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(round(GREEN, 20, 0, 0));
        refresh.setElevation(dp(3));
        refresh.setOnClickListener(v -> {
            seedSessionCookies();
            correctingNavigation = false;
            web.stopLoading();
            web.loadUrl(DELETED_URL);
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(40), dp(40));
        rp.setMargins(dp(4), 0, 0, 0);
        bar.addView(refresh, rp);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        FrameLayout holder = new FrameLayout(this);
        holder.setBackgroundColor(Color.WHITE);

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
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(AppIdentity.userAgent());
        web.setBackgroundColor(Color.WHITE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);

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
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) return false;
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (isDeletedLibraryUrl(url)) return false;
                blockNavigation(url);
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!isDeletedLibraryUrl(url)) {
                    blockNavigation(url);
                    return;
                }
                state.setVisibility(View.GONE);
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!isDeletedLibraryUrl(url)) {
                    blockNavigation(url);
                    return;
                }
                correctingNavigation = false;
                view.clearHistory();
                lockPageChrome(view);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String text = error == null ? "Не удалось открыть Library" : String.valueOf(error.getDescription());
                    state.setText("Не удалось открыть ChatGPT Library: " + text + "\nНажмите ⟳, чтобы повторить.");
                    state.setVisibility(View.VISIBLE);
                }
            }
        });

        holder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        holder.addView(state, new FrameLayout.LayoutParams(-1, -1));
        root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private boolean isDeletedLibraryUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            Uri u = Uri.parse(raw);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (!"https".equalsIgnoreCase(scheme)) return false;
            if (!("chatgpt.com".equalsIgnoreCase(host) || "www.chatgpt.com".equalsIgnoreCase(host))) return false;
            String path = u.getPath();
            if (path == null) return false;
            while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            return "/library".equals(path) && "true".equalsIgnoreCase(u.getQueryParameter("deleted"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void blockNavigation(String attemptedUrl) {
        if (web == null || correctingNavigation) return;
        correctingNavigation = true;
        web.stopLoading();
        web.clearHistory();
        if (attemptedUrl != null && (attemptedUrl.contains("/auth/") || attemptedUrl.contains("login"))) {
            state.setText("Сессия ChatGPT требует повторного входа. Обновите подключение аккаунта и откройте «Удалённые» снова.");
            state.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            correctingNavigation = false;
            return;
        }
        web.post(() -> web.loadUrl(DELETED_URL));
    }

    private void lockPageChrome(WebView view) {
        String js = "(function(){" +
                "try{" +
                "var id='gptdrive-locked-trash-style';" +
                "var old=document.getElementById(id);if(old)old.remove();" +
                "var s=document.createElement('style');s.id=id;" +
                "s.textContent='html,body{overscroll-behavior:none!important;overflow-x:hidden!important;}' +" +
                "'body{margin:0!important;max-width:100vw!important;}' +" +
                "'nav,aside,header,footer{display:none!important;}' +" +
                "'[data-testid*=sidebar],[data-testid*=navigation]{display:none!important;}' +" +
                "'main{margin-left:0!important;max-width:100%!important;width:100%!important;padding-top:0!important;}' +" +
                "'#gptdrive-image-lightbox{position:fixed!important;inset:0!important;z-index:2147483647!important;background:rgba(8,12,18,.96)!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:18px!important;box-sizing:border-box!important;}' +" +
                "'#gptdrive-image-lightbox img{display:block!important;max-width:100%!important;max-height:92vh!important;width:auto!important;height:auto!important;object-fit:contain!important;border-radius:12px!important;box-shadow:0 14px 50px rgba(0,0,0,.45)!important;}' +" +
                "'#gptdrive-image-lightbox .gptdrive-close{position:absolute!important;top:16px!important;right:16px!important;width:42px!important;height:42px!important;border:0!important;border-radius:21px!important;background:rgba(255,255,255,.94)!important;color:#101828!important;font-size:28px!important;line-height:42px!important;text-align:center!important;padding:0!important;box-shadow:0 4px 16px rgba(0,0,0,.28)!important;}';" +
                "document.head.appendChild(s);" +
                "document.documentElement.style.overscrollBehavior='none';" +
                "document.body.style.overscrollBehavior='none';" +
                "document.body.style.overflowX='hidden';" +
                "function applyGap(){" +
                "try{" +
                "var buttons=Array.prototype.slice.call(document.querySelectorAll('button'));" +
                "var clear=buttons.find(function(b){var t=(b.innerText||b.textContent||'').trim();return /Очистить корзину|Empty trash/i.test(t);});" +
                "if(!clear)return;" +
                "var p=clear.parentElement;" +
                "while(p&&p!==document.body){var r=p.getBoundingClientRect();if(r.width>window.innerWidth*.72&&r.height>70){p.style.setProperty('margin-bottom','28px','important');break;}p=p.parentElement;}" +
                "}catch(e){}" +
                "}" +
                "function closeLightbox(){var o=document.getElementById('gptdrive-image-lightbox');if(o)o.remove();document.body.style.overflow='';}" +
                "function openLightbox(img){" +
                "closeLightbox();" +
                "var o=document.createElement('div');o.id='gptdrive-image-lightbox';" +
                "var c=img.cloneNode(true);c.removeAttribute('class');c.removeAttribute('style');c.removeAttribute('width');c.removeAttribute('height');c.draggable=false;" +
                "var x=document.createElement('button');x.className='gptdrive-close';x.setAttribute('aria-label','Закрыть');x.textContent='×';" +
                "x.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();closeLightbox();},true);" +
                "o.addEventListener('click',function(e){if(e.target===o)closeLightbox();},true);" +
                "o.appendChild(c);o.appendChild(x);document.body.appendChild(o);document.body.style.overflow='hidden';" +
                "}" +
                "if(!window.__gptdriveTrashClickInstalled){" +
                "window.__gptdriveTrashClickInstalled=true;" +
                "document.addEventListener('click',function(e){" +
                "var t=e.target;var img=null;" +
                "if(t&&t.tagName==='IMG')img=t;else if(t&&t.closest)img=t.closest('img');" +
                "if(!img||img.closest('#gptdrive-image-lightbox'))return;" +
                "var r=img.getBoundingClientRect();if(r.width<120||r.height<100)return;" +
                "e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();openLightbox(img);" +
                "},true);" +
                "}" +
                "applyGap();" +
                "if(window.__gptdriveTrashObserver)window.__gptdriveTrashObserver.disconnect();" +
                "var timer=null;window.__gptdriveTrashObserver=new MutationObserver(function(){clearTimeout(timer);timer=setTimeout(applyGap,120);});" +
                "window.__gptdriveTrashObserver.observe(document.body,{childList:true,subtree:true});" +
                "}catch(e){}" +
                "})();";
        try { view.evaluateJavascript(js, null); } catch (Exception ignored) {}
    }

    private void closePreviewOrFinish() {
        if (web == null) {
            finish();
            return;
        }
        String js = "(function(){var o=document.getElementById('gptdrive-image-lightbox');if(o){o.remove();document.body.style.overflow='';return true;}return false;})()";
        try {
            web.evaluateJavascript(js, value -> {
                if (!"true".equals(value)) finish();
            });
        } catch (Exception e) {
            finish();
        }
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

    @Override public void onBackPressed() { closePreviewOrFinish(); }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.clearHistory();
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
