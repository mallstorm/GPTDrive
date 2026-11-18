package com.gptdrive.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Records the SHAPE (key names, value types, URL hosts only — never values) of the first Library
 * file and the first generated image the API returns, so the diagnostics log can show whether the
 * backend offers ready-made thumbnails.
 */
final class SchemaProbe {
    static volatile String library = "";
    static volatile String images = "";

    private SchemaProbe() {}

    static void captureLibrary(JSONObject root) {
        if (!library.isEmpty() || root == null) return;
        JSONObject file = findFile(root, 0);
        library = file == null
                ? "файл не найден; ключи корня: " + keys(root)
                : shape(file);
    }

    static void captureImage(JSONObject item) {
        if (!images.isEmpty() || item == null) return;
        images = shape(item);
    }

    private static JSONObject findFile(Object node, int depth) {
        if (node == null || depth > 8) return null;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = findFile(a.opt(i), depth + 1);
                if (f != null) return f;
            }
            return null;
        }
        if (!(node instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) node;
        if (ChatGptResponseParser.mapToRemote(o) != null) return o;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object child = o.opt(it.next());
            if (child instanceof JSONObject || child instanceof JSONArray) {
                JSONObject f = findFile(child, depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static String keys(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) sb.append(it.next()).append(',');
        return sb.toString();
    }

    private static String shape(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        walk(o, "", 0, sb);
        return sb.length() > 1800 ? sb.substring(0, 1800) : sb.toString();
    }

    private static void walk(JSONObject o, String prefix, int depth, StringBuilder sb) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            String path = prefix + k;
            if (v instanceof JSONObject) {
                if (depth < 3) walk((JSONObject) v, path + ".", depth + 1, sb);
                else sb.append(path).append("={…}; ");
            } else if (v instanceof JSONArray) {
                sb.append(path).append("=[").append(((JSONArray) v).length()).append("]; ");
            } else if (v instanceof String) {
                String s = (String) v;
                sb.append(path).append("=str").append(s.length());
                if (s.startsWith("http")) {
                    try { sb.append('@').append(android.net.Uri.parse(s).getHost()); }
                    catch (Exception ignored) {}
                }
                sb.append("; ");
            } else {
                sb.append(path).append('=').append(v == null || v == JSONObject.NULL ? "null"
                        : v.getClass().getSimpleName()).append("; ");
            }
        }
    }
}
