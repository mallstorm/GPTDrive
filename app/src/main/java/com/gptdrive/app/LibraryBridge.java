package com.gptdrive.app;

import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

public final class LibraryBridge {
    private final DriveDb db;
    private final long accountId;
    private volatile String category = "Library";

    LibraryBridge(DriveDb db, long accountId) {
        this.db = db;
        this.accountId = accountId;
    }

    void setCategory(String c) { category = normalizeCategory(c); }

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

                if ("link".equals(kind) || acceptsLink(url, category)) {
                    if (acceptsLink(url, category)) db.upsert(accountId, category, name, url, DriveActions.LINK_MIME, 0);
                    continue;
                }

                String originalUrl = url;
                String stableUrl = RemoteFileSource.stableUrl(url);
                if (RemoteFileSource.isTransientSignedUrl(url) && stableUrl.equals(url)) continue;
                url = stableUrl;
                if (!acceptsAsset(url, mime)) continue;
                String target = classifyCategory(category, originalUrl, mime);
                if (isNoiseName(name)) continue;
                db.upsert(accountId, target, name, url, normalizeMime(mime, originalUrl), sizeBytes);
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface public void captureApi(String url, String json) {
        if (url == null || json == null || json.trim().isEmpty()) return;
        String u = url.toLowerCase(Locale.ROOT);
        if (!isRelevantApi(u)) return;
        try {
            Object root = parseJson(json);
            if (root == null) return;
            if (u.contains("/api/auth/session")) {
                if (root instanceof JSONObject) captureAccount(((JSONObject) root).toString());
                return;
            }
            if (u.contains("usage") || u.contains("storage") || u.contains("quota") || u.contains("capabilit")) {
                captureUsage(root);
            }
            walkStructured(root, u, 0);
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
        if (url == null) return;
        String originalUrl = url;
        String stableUrl = RemoteFileSource.stableUrl(url);
        if (RemoteFileSource.isTransientSignedUrl(url) && stableUrl.equals(url)) return;
        if (!acceptsAsset(stableUrl, "")) return;
        String target = classifyCategory(category, originalUrl, "");
        db.upsert(accountId, target, "", stableUrl, normalizeMime("", originalUrl), 0);
    }

    private void walkStructured(Object node, String endpoint, int depth) {
        if (node == null || depth > 8) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            tryCaptureObject(o, endpoint);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = o.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) walkStructured(child, endpoint, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walkStructured(a.opt(i), endpoint, depth + 1);
        }
    }

    private void tryCaptureObject(JSONObject o, String endpoint) {
        String id = firstStringDeep(o, "file_id", "fileId", "asset_id", "assetId", "id");
        String name = firstStringDeep(o, "filename", "file_name", "display_name", "name", "title");
        String mime = firstStringDeep(o, "mime_type", "mimeType", "content_type", "contentType", "mime");
        String url = firstStringDeep(o, "download_url", "downloadUrl", "content_url", "contentUrl", "file_url", "fileUrl", "asset_url", "assetUrl", "url");
        long size = firstSizeDeep(o);

        String e = endpoint == null ? "" : endpoint.toLowerCase(Locale.ROOT);
        if (isProjectEndpoint(e)) {
            if (!name.trim().isEmpty() && !isNoiseName(name) && looksProjectId(id)) {
                String link = id.startsWith("http") ? id : "https://chatgpt.com/g/" + id;
                db.upsert(accountId, "Projects", name, link, DriveActions.LINK_MIME, 0);
            }
            return;
        }

        if (isArchiveEndpoint(e)) {
            if (!name.trim().isEmpty() && !isNoiseName(name) && looksConversationId(id)) {
                db.upsert(accountId, "Archived", name, "https://chatgpt.com/c/" + id, DriveActions.LINK_MIME, 0);
            }
            return;
        }

        boolean fileId = looksFileId(id);
        boolean strictUrl = acceptsAsset(url, mime);
        boolean fileShape = (!mime.isEmpty() || size > 0) && (!name.isEmpty() || fileId) && isFileEndpoint(e);
        if (!fileId && !strictUrl && !fileShape) return;
        if (isNoiseName(name)) return;
        if (fileId && name.trim().isEmpty()) return;

        String originalUrl = url;
        String normalizedMime = normalizeMime(mime, originalUrl);
        if (fileId) {
            url = "https://chatgpt.com/backend-api/files/" + id + "/content";
        } else {
            String stable = RemoteFileSource.stableUrl(url);
            if (RemoteFileSource.isTransientSignedUrl(url) && stable.equals(url)) return;
            url = stable;
        }
        if (!acceptsAsset(url, normalizedMime)) return;

        String target = classifyCategory(categoryForEndpoint(e), originalUrl, normalizedMime);
        db.upsert(accountId, target, name, url, normalizedMime, size);
    }

    private void captureUsage(Object root) {
        UsageStats stats = new UsageStats();
        scanUsage(root, "", stats, 0);
        if (stats.used > 0 || stats.quota > 0) db.updateAccountSubscription(accountId, "", stats.used, stats.quota);
    }

    private void scanUsage(Object node, String path, UsageStats stats, int depth) {
        if (node == null || depth > 8) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = o.opt(key);
                String p = path.isEmpty() ? key : path + "." + key;
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    scanUsage(value, p, stats, depth + 1);
                } else {
                    long bytes = valueToBytes(key, value);
                    if (bytes <= 0) continue;
                    String k = p.toLowerCase(Locale.ROOT);
                    if (containsAny(k, "quota", "limit", "maximum", "max_bytes", "capacity")) {
                        stats.quota = Math.max(stats.quota, bytes);
                    } else if (!k.contains("remaining") && containsAny(k, "used", "usage", "consumed", "total_usage", "storage")) {
                        stats.used = Math.max(stats.used, bytes);
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) scanUsage(a.opt(i), path, stats, depth + 1);
        }
    }

    private static Object parseJson(String text) {
        String s = text == null ? "" : text.trim();
        try {
            if (s.startsWith("{")) return new JSONObject(s);
            if (s.startsWith("[")) return new JSONArray(s);
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isRelevantApi(String u) {
        return u.contains("/api/auth/session") ||
                ((u.contains("/backend-api/") || u.contains("/api/")) &&
                        containsAny(u, "file", "library", "image", "asset", "upload", "project", "archive", "conversation", "usage", "storage", "quota", "capabilit"));
    }

    private static boolean isFileEndpoint(String e) {
        return containsAny(e, "file", "library", "image", "asset", "upload");
    }

    private static boolean isProjectEndpoint(String e) { return e.contains("project"); }
    private static boolean isArchiveEndpoint(String e) { return e.contains("archive") || e.contains("archived"); }

    private static String categoryForEndpoint(String e) {
        if (e.contains("image")) return "Images";
        if (e.contains("project")) return "Projects";
        if (e.contains("archive")) return "Archived";
        return "Library";
    }

    private static String classifyCategory(String preferred, String url, String mime) {
        String p = normalizeCategory(preferred);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/") || hasExtension(u, ".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".avif")) return "Images";
        return p;
    }

    private String detectPlan(JSONObject root, JSONObject user) {
        JSONObject account = root.optJSONObject("account");
        JSONObject subscription = root.optJSONObject("subscription");
        JSONObject jwt = decodeJwtPayload(root.optString("accessToken", ""));
        JSONObject jwtAuth = jwt == null ? null : jwt.optJSONObject("https://api.openai.com/auth");
        String value = firstNonEmpty(
                root.optString("plan_type", ""), root.optString("plan", ""), root.optString("subscription_plan", ""),
                user == null ? "" : user.optString("plan_type", ""), user == null ? "" : user.optString("plan", ""),
                account == null ? "" : account.optString("plan_type", ""), account == null ? "" : account.optString("plan", ""),
                subscription == null ? "" : subscription.optString("plan_type", ""), subscription == null ? "" : subscription.optString("plan", ""),
                subscription == null ? "" : subscription.optString("name", ""),
                jwtAuth == null ? "" : jwtAuth.optString("chatgpt_plan_type", ""), jwtAuth == null ? "" : jwtAuth.optString("plan_type", ""),
                jwt == null ? "" : jwt.optString("chatgpt_plan_type", ""), jwt == null ? "" : jwt.optString("plan_type", ""));
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
                ? new String[]{"storage_used_bytes", "used_storage_bytes", "file_storage_used_bytes", "total_usage_bytes"}
                : new String[]{"storage_quota_bytes", "storage_limit_bytes", "file_storage_quota_bytes", "file_storage_limit_bytes", "limit_bytes"};
        long value = firstLong(root, keys);
        if (value > 0) return value;
        JSONObject storage = root.optJSONObject("storage");
        value = firstLong(storage, used ? new String[]{"used_bytes", "storage_used_bytes", "total_usage_bytes"} : new String[]{"quota_bytes", "limit_bytes", "storage_quota_bytes"});
        if (value > 0) return value;
        JSONObject account = root.optJSONObject("account");
        return firstLong(account, keys);
    }

    private static String firstStringDeep(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            String v = o.optString(key, "").trim();
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
        }
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) {
            JSONObject n = o.optJSONObject(nested);
            if (n == null) continue;
            for (String key : keys) {
                String v = n.optString(key, "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
            }
        }
        return "";
    }

    private static long firstSizeDeep(JSONObject o) {
        String[] keys = {"size_bytes", "sizeBytes", "bytes", "file_size", "fileSize", "size"};
        long v = firstLong(o, keys);
        if (v > 0) return v;
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) {
            v = firstLong(o.optJSONObject(nested), keys);
            if (v > 0) return v;
        }
        return 0;
    }

    private static long firstLong(JSONObject o, String[] keys) {
        if (o == null) return 0;
        for (String key : keys) {
            Object v = o.opt(key);
            long n = valueToBytes(key, v);
            if (n > 0) return n;
        }
        return 0;
    }

    private static long valueToBytes(String key, Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (n <= 0) return 0;
            if (k.contains("gb")) return (long) (n * 1024L * 1024L * 1024L);
            if (k.contains("mb")) return (long) (n * 1024L * 1024L);
            if (k.contains("kb")) return (long) (n * 1024L);
            if (k.contains("byte") || n > 1024L * 1024L) return (long) n;
            return 0;
        }
        return parseSize(String.valueOf(value));
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static String normalizePlan(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = raw.trim();
        String l = s.toLowerCase(Locale.ROOT);
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
            String s = text.trim().toUpperCase(Locale.ROOT).replace(',', '.');
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

    private static boolean acceptsLink(String url, String cat) {
        if (url == null || !url.startsWith("https://chatgpt.com/")) return false;
        String s = url.toLowerCase(Locale.ROOT);
        if ("Projects".equals(cat)) return s.contains("/project") || s.contains("/projects") || s.contains("/g/g-p-");
        if ("Archived".equals(cat)) return s.contains("/c/");
        return false;
    }

    private static boolean acceptsAsset(String url, String mime) {
        if (url == null || url.trim().isEmpty() || !url.startsWith("https://")) return false;
        String s = url.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (isUiAsset(s) || isServiceEndpoint(s)) return false;
        if (s.contains("files.oaiusercontent.com") || s.contains("oaidalle") || s.contains("oaiusercontent")) return true;
        if (s.matches(".*?/files/(file[-_][a-z0-9_-]+|file_[a-z0-9_-]+)/content(?:[?/#].*)?$")) return true;
        if (m.startsWith("image/") && !s.contains("chatgpt.com/backend-api/")) return true;
        return hasExtension(s, ".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".avif", ".pdf", ".docx", ".xlsx", ".pptx", ".zip", ".txt", ".md");
    }

    private static boolean isServiceEndpoint(String s) {
        return containsAny(s,
                "/files/usage", "/files/path", "/files/capabilities", "/files/search", "/files/list",
                "/library/usage", "/storage/usage", "/backend-api/files?", "/backend-api/files/usage",
                "favicon", "/assets/", "/static/", "/manifest", "/service-worker");
    }

    private static boolean isUiAsset(String s) {
        return containsAny(s, "favicon", "avatar", "/assets/", "static", "logo", "sprite", "icon-", "apple-touch-icon");
    }

    private static boolean isNoiseName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.equals("usage") || n.equals("path") || n.equals("content") || n.equals("capabilities") ||
                n.equals("library") || n.equals("storage") || n.equals("metadata") || n.equals("manifest") ||
                n.startsWith("favicon") || n.matches(".*(?:16|24|32|48|64|96|128|192|256|512)[-_]?px.*");
    }

    private static boolean looksFileId(String id) {
        if (id == null) return false;
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("file-") || s.startsWith("file_");
    }

    private static boolean looksProjectId(String id) {
        if (id == null) return false;
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("g-p-") || s.startsWith("project-") || s.startsWith("project_");
    }

    private static boolean looksConversationId(String id) {
        if (id == null) return false;
        String s = id.trim();
        return s.matches("[A-Za-z0-9_-]{16,80}") && !looksFileId(s) && !looksProjectId(s);
    }

    private static String normalizeMime(String mime, String url) {
        if (mime != null && !mime.trim().isEmpty() && !"image/*".equals(mime)) return mime.trim();
        String guessed = DriveDb.guessMime(url);
        if (!"application/octet-stream".equals(guessed)) return guessed;
        return mime == null || mime.trim().isEmpty() ? guessed : mime.trim();
    }

    private static String normalizeCategory(String c) {
        if ("Images".equals(c) || "Projects".equals(c) || "Archived".equals(c)) return c;
        return "Library";
    }

    private static boolean hasExtension(String s, String... exts) {
        for (String e : exts) if (s.contains(e)) return true;
        return false;
    }

    private static boolean containsAny(String s, String... parts) {
        if (s == null) return false;
        for (String p : parts) if (s.contains(p)) return true;
        return false;
    }

    private static final class UsageStats {
        long used;
        long quota;
    }
}
