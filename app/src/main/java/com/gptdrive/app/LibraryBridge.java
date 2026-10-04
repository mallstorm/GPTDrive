package com.gptdrive.app;

import android.util.Base64;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class LibraryBridge {
    private final DriveDb db;
    private final long accountId;
    private volatile String category = "Library";

    LibraryBridge(DriveDb db, long accountId) {
        this.db = db;
        this.accountId = accountId;
    }

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
                long sizeBytes = o.optLong("sizeBytes", 0);
                if (sizeBytes <= 0) sizeBytes = parseSize(o.optString("size", ""));

                if ("link".equals(kind) || acceptsLink(url)) {
                    if (acceptsLink(url)) db.upsert(accountId, category, name, url, DriveActions.LINK_MIME, 0);
                    continue;
                }
                if (acceptsAsset(url, mime)) {
                    if ("Images".equals(category) && (mime == null || mime.isEmpty() || "image/*".equals(mime))) mime = "image/*";
                    db.upsert(accountId, category, name, url, mime == null || mime.isEmpty() ? null : mime, sizeBytes);
                }
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface public void captureAccount(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject user = root.optJSONObject("user");
            String name = user != null ? user.optString("name", "") : root.optString("name", "");
            String email = user != null ? user.optString("email", "") : root.optString("email", "");
            if (!name.isEmpty() || !email.isEmpty()) db.updateAccountIdentity(accountId, name, email);

            String plan = detectPlan(root, user);
            long used = detectStorage(root, true);
            long quota = detectStorage(root, false);
            db.updateAccountSubscription(accountId, plan, used, quota);
        } catch (Exception ignored) {}
    }

    void captureResource(String url) {
        if (url == null || !acceptsAsset(url, "")) return;
        db.upsert(accountId, category, "", url, "Images".equals(category) ? "image/*" : null, 0);
    }

    private String detectPlan(JSONObject root, JSONObject user) {
        JSONObject account = root.optJSONObject("account");
        JSONObject subscription = root.optJSONObject("subscription");
        JSONObject jwt = decodeJwtPayload(root.optString("accessToken", ""));
        JSONObject jwtAuth = jwt == null ? null : jwt.optJSONObject("https://api.openai.com/auth");

        String value = firstNonEmpty(
                root.optString("plan_type", ""),
                root.optString("plan", ""),
                root.optString("subscription_plan", ""),
                user == null ? "" : user.optString("plan_type", ""),
                user == null ? "" : user.optString("plan", ""),
                account == null ? "" : account.optString("plan_type", ""),
                account == null ? "" : account.optString("plan", ""),
                subscription == null ? "" : subscription.optString("plan_type", ""),
                subscription == null ? "" : subscription.optString("plan", ""),
                subscription == null ? "" : subscription.optString("name", ""),
                jwtAuth == null ? "" : jwtAuth.optString("chatgpt_plan_type", ""),
                jwtAuth == null ? "" : jwtAuth.optString("plan_type", ""),
                jwt == null ? "" : jwt.optString("chatgpt_plan_type", ""),
                jwt == null ? "" : jwt.optString("plan_type", ""));
        return normalizePlan(value);
    }

    private static JSONObject decodeJwtPayload(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String p = parts[1];
            int mod = p.length() % 4;
            if (mod != 0) p += "====".substring(mod);
            byte[] bytes = Base64.decode(p, Base64.URL_SAFE | Base64.NO_WRAP);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    private long detectStorage(JSONObject root, boolean used) {
        String[] keys = used
                ? new String[]{"storage_used_bytes", "used_storage_bytes", "file_storage_used_bytes"}
                : new String[]{"storage_quota_bytes", "storage_limit_bytes", "file_storage_quota_bytes", "file_storage_limit_bytes"};
        long value = firstLong(root, keys);
        if (value > 0) return value;
        JSONObject storage = root.optJSONObject("storage");
        value = firstLong(storage, used
                ? new String[]{"used_bytes", "storage_used_bytes"}
                : new String[]{"quota_bytes", "limit_bytes", "storage_quota_bytes"});
        if (value > 0) return value;
        JSONObject account = root.optJSONObject("account");
        return firstLong(account, keys);
    }

    private static long firstLong(JSONObject o, String[] keys) {
        if (o == null) return 0;
        for (String key : keys) {
            Object v = o.opt(key);
            if (v instanceof Number) {
                long n = ((Number) v).longValue();
                if (n > 0) return n;
            } else if (v != null) {
                try {
                    long n = Long.parseLong(String.valueOf(v).replaceAll("[^0-9]", ""));
                    if (n > 0) return n;
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static String normalizePlan(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = raw.trim();
        String l = s.toLowerCase();
        if (l.contains("enterprise")) return "Enterprise";
        if (l.contains("business")) return "Business";
        if (l.contains("team")) return "Team";
        if (l.contains("edu")) return "Edu";
        if (l.contains("plus")) return "Plus";
        if (l.contains("pro")) return "Pro";
        if (l.equals("go") || l.contains("chatgpt_go")) return "Go";
        if (l.contains("free")) return "Free";
        if (s.length() > 32) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static long parseSize(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        try {
            String s = text.trim().toUpperCase().replace(',', '.');
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|TB|КБ|МБ|ГБ|ТБ)")
                    .matcher(s);
            if (!m.find()) return 0;
            double n = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            long mul = 1;
            if (unit.equals("KB") || unit.equals("КБ")) mul = 1024L;
            else if (unit.equals("MB") || unit.equals("МБ")) mul = 1024L * 1024L;
            else if (unit.equals("GB") || unit.equals("ГБ")) mul = 1024L * 1024L * 1024L;
            else if (unit.equals("TB") || unit.equals("ТБ")) mul = 1024L * 1024L * 1024L * 1024L;
            return (long) (n * mul);
        } catch (Exception e) { return 0; }
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
