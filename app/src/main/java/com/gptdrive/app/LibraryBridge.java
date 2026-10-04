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
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String url = o.optString("url", "");
                if (!isUseful(url)) continue;
                db.upsert(category, o.optString("name", ""), url, o.optString("mime", null));
            }
        } catch (Exception ignored) {}
    }

    private boolean isUseful(String u) {
        if (u == null || u.isEmpty()) return false;
        String s = u.toLowerCase();
        return s.startsWith("https://") && (s.contains("files") || s.contains("file") || s.contains("download") || s.contains("oaiusercontent") || s.contains("oaidalle") || s.contains("blob"));
    }
}
