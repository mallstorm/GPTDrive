package com.gptdrive.app;

/** Network/display identity kept in one place for this release. */
final class AppIdentity {
    static final String VERSION = "0.12.4";

    private AppIdentity() {}

    private static volatile String webUa = "";

    /** Loads the UA used by the WebView login; Cloudflare cookies are bound to it. */
    static void init(android.content.Context c) {
        try {
            String v = c.getApplicationContext().getSharedPreferences("gptdrive_identity", 0).getString("web_ua", "");
            if (v != null) webUa = v;
        } catch (Exception ignored) {}
    }

    static void rememberWebUserAgent(android.content.Context c, String ua) {
        if (ua == null || ua.trim().isEmpty()) return;
        webUa = ua.trim();
        try { c.getApplicationContext().getSharedPreferences("gptdrive_identity", 0).edit().putString("web_ua", webUa).apply(); }
        catch (Exception ignored) {}
    }

    static String userAgent() {
        String w = webUa;
        if (w != null && !w.isEmpty()) return w;
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Mobile Safari/537.36 GPTDrive/" + VERSION;
    }

    static String providerLabel() { return "provider v" + VERSION; }
}
