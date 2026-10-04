package com.gptdrive.app;

import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LibraryBridge {
    private final DriveDb db;
    private volatile String category = "Library";

    LibraryBridge(DriveDb db) { this.db = db; }
    void setCategory(String c) { category = c == null ? "Library" : c; }

    @JavascriptInterface public void capture(String json) {
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                String name = o.optString("name", "");
                String mime = o.optString("mime", "");
                String kind = o.optString("kind", "");

                if ("link".equals(kind) || acceptsLink(url)) {
                    if (acceptsLink(url)) db.upsert(category, name, url, DriveActions.LINK_MIME);
                    continue;
                }

                if (acceptsAsset(url, mime)) {
                    if ("Images".equals(category) && (mime == null || mime.isEmpty() || "image/*".equals(mime))) mime = "image/*";
                    db.upsert(category, name, url, mime == null || mime.isEmpty() ? null : mime);
                }
            }
        } catch (Exception ignored) {}
    }

    void captureResource(String url) {
        if (url == null || !acceptsAsset(url, "")) return;
        db.upsert(category, "", url, "Images".equals(category) ? "image/*" : null);
    }

    private boolean acceptsLink(String url) {
        if (url == null || !url.startsWith("https://chatgpt.com/")) return false;
        String s = url.toLowerCase();
        if ("Projects".equals(category)) return s.contains("/project") || s.contains("/projects") || s.contains("/g/g-p-");
        if ("Archived".equals(category)) return s.contains("/c/");
        return false;
    }

    private boolean acceptsAsset(String url, String mime) {
        if (url == null || url.isEmpty() || !url.startsWith("https://")) return false;
        String s = url.toLowerCase();
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.startsWith("image/")) return !isUiAsset(s);
        if (s.contains("oaiusercontent") || s.contains("oaidalle") || s.contains("/files/") || s.contains("/file/") || s.contains("download")) return true;
        return hasExtension(s, ".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".pdf", ".docx", ".xlsx", ".pptx", ".zip", ".txt", ".md", ".json");
    }

    private boolean isUiAsset(String s) {
        return s.contains("favicon") || s.contains("avatar") || s.contains("/assets/") || s.contains("static") || s.contains("logo");
    }

    private boolean hasExtension(String s, String... exts) {
        for (String e : exts) if (s.contains(e)) return true;
        return false;
    }
}
