package com.gptdrive.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Extracts stable file references from a full ChatGPT conversation mapping. */
final class ChatGptAttachmentExtractor {
    private ChatGptAttachmentExtractor() {}

    static List<ChatGptModels.RemoteFile> extract(JSONObject root, String conversationId, String projectId) {
        LinkedHashMap<String, ChatGptModels.RemoteFile> out = new LinkedHashMap<>();
        if (root == null) return new ArrayList<>();

        JSONObject mapping = root.optJSONObject("mapping");
        if (mapping != null) {
            JSONArray keys = mapping.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String nodeId = keys.optString(i, "");
                    JSONObject node = mapping.optJSONObject(nodeId);
                    if (node == null) continue;
                    JSONObject message = node.optJSONObject("message");
                    if (message == null) continue;
                    String messageId = ChatGptModels.first(message.optString("id", ""), nodeId);
                    walk(message, out, conversationId, projectId, messageId, 0);
                }
            }
        }

        // Some backend revisions place conversation-level attachment metadata outside mapping.
        for (String key : new String[]{"attachments", "files", "assets", "metadata"}) {
            Object node = root.opt(key);
            if (node instanceof JSONObject || node instanceof JSONArray)
                walk(node, out, conversationId, projectId, "", 0);
        }
        return new ArrayList<>(out.values());
    }

    private static void walk(Object node, LinkedHashMap<String, ChatGptModels.RemoteFile> out,
                             String conversationId, String projectId, String messageId, int depth) {
        if (node == null || depth > 14) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++)
                walk(a.opt(i), out, conversationId, projectId, messageId, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;

        JSONObject o = (JSONObject) node;
        ChatGptModels.RemoteFile candidate = ChatGptResponseParser.mapToRemote(o);
        if (candidate != null && !candidate.identity().isEmpty()) {
            candidate.conversationId = ChatGptModels.first(candidate.conversationId, conversationId);
            candidate.messageId = ChatGptModels.first(candidate.messageId, messageId);
            candidate.projectId = ChatGptModels.first(candidate.projectId, projectId);
            candidate.sourceFlags |= ChatGptModels.SRC_CONVERSATION;
            ChatGptModels.merge(out, candidate);
        }

        JSONArray names = o.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            Object child = o.opt(key);
            if (!(child instanceof JSONObject) && !(child instanceof JSONArray)) continue;
            String nextMessage = messageId;
            if ("message".equals(key) && child instanceof JSONObject) {
                JSONObject m = (JSONObject) child;
                nextMessage = ChatGptModels.first(m.optString("id", ""), messageId);
            }
            walk(child, out, conversationId, projectId, nextMessage, depth + 1);
        }
    }
}
