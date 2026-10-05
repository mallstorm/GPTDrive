package com.gptdrive.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversation surface used by Archive today and by Chat Mirror later.
 * It intentionally preserves the full conversation JSON without interpreting the mapping tree.
 */
final class ChatGptConversationsApi {
    interface Fetcher { HttpResponse get(String endpoint) throws Exception; }
    interface StatusHandler { void ensure(HttpResponse response) throws Exception; }

    private ChatGptConversationsApi() {}

    static List<ChatGptModels.RemoteConversation> list(Fetcher fetcher, StatusHandler status,
                                                        boolean archivedOnly, int maxPages) throws Exception {
        if (fetcher == null || status == null) throw new IllegalArgumentException("fetcher/status required");
        if (maxPages <= 0) maxPages = 1;
        ArrayList<ChatGptModels.RemoteConversation> out = new ArrayList<>();
        int offset = 0;
        final int limit = 100;

        for (int page = 0; page < maxPages; page++) {
            String endpoint = "/backend-api/conversations?offset=" + offset + "&limit=" + limit + "&order=updated" +
                    (archivedOnly ? "&is_archived=true" : "");
            HttpResponse response = fetcher.get(endpoint);
            status.ensure(response);
            JSONObject root = new JSONObject(response.bodyUtf8());
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) break;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                ChatGptModels.RemoteConversation c = parseSummary(item);
                if (c == null) continue;
                if (archivedOnly) c.archived = true;
                out.add(c);
            }

            int total = root.optInt("total", root.optInt("total_count", 0));
            offset += items.length();
            if (items.length() < limit || (total > 0 && offset >= total)) break;
        }
        return out;
    }

    static ChatGptModels.RemoteConversation load(Fetcher fetcher, StatusHandler status, String conversationId) throws Exception {
        String id = clean(conversationId);
        if (id.isEmpty()) throw new IllegalArgumentException("conversationId is empty");
        HttpResponse response = fetcher.get("/backend-api/conversation/" + encode(id));
        status.ensure(response);
        String raw = response.bodyUtf8();
        JSONObject root = new JSONObject(raw);

        ChatGptModels.RemoteConversation c = parseSummary(root);
        if (c == null) {
            c = new ChatGptModels.RemoteConversation();
            c.id = id;
        }
        if (clean(c.id).isEmpty()) c.id = id;
        c.rawJson = raw;
        c.currentNode = first(root.optString("current_node", ""), root.optString("currentNode", ""));
        JSONObject mapping = root.optJSONObject("mapping");
        c.hasMapping = mapping != null && mapping.length() > 0;
        return c;
    }

    static ChatGptModels.RemoteConversation parseSummary(JSONObject o) {
        if (o == null) return null;
        String id = first(o.optString("id", ""), o.optString("conversation_id", ""), o.optString("conversationId", ""));
        if (id.isEmpty()) return null;
        ChatGptModels.RemoteConversation c = new ChatGptModels.RemoteConversation();
        c.id = id;
        c.title = ChatGptResponseParser.cleanName(first(o.optString("title", ""), "ChatGPT chat"));
        c.updatedAt = epochMillis(firstObject(o, "update_time", "updated_at", "updateTime", "updatedAt"));
        c.createdAt = epochMillis(firstObject(o, "create_time", "created_at", "createTime", "createdAt"));
        c.archived = firstBoolean(o, "is_archived", "archived", "isArchived");
        c.projectId = first(o.optString("project_id", ""), o.optString("projectId", ""),
                o.optString("gizmo_id", ""), o.optString("gizmoId", ""));
        return c;
    }

    private static Object firstObject(JSONObject o, String... keys) {
        for (String key : keys) if (o.has(key) && !o.isNull(key)) return o.opt(key);
        return null;
    }

    private static boolean firstBoolean(JSONObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.isNull(key)) continue;
            Object v = o.opt(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            String s = String.valueOf(v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        }
        return false;
    }

    private static long epochMillis(Object value) {
        if (value == null || value == JSONObject.NULL) return 0;
        try {
            double n = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
            if (n <= 0) return 0;
            return n < 100000000000L ? (long) (n * 1000d) : (long) n;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(clean(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String first(String... values) {
        if (values != null) for (String v : values) {
            String s = clean(v);
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
