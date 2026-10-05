package com.gptdrive.app;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ChatGptConversationsApiTest {
    @Test public void parsesConversationSummaryMetadata() throws Exception {
        JSONObject o = new JSONObject("{\"id\":\"conv-1\",\"title\":\"Test chat\",\"update_time\":1710000000.5,\"create_time\":1700000000,\"is_archived\":true,\"project_id\":\"proj-1\"}");
        ChatGptModels.RemoteConversation c = ChatGptConversationsApi.parseSummary(o);
        assertNotNull(c);
        assertEquals("conv-1", c.id);
        assertEquals("Test chat", c.title);
        assertEquals(1710000000500L, c.updatedAt);
        assertEquals(1700000000000L, c.createdAt);
        assertTrue(c.archived);
        assertEquals("proj-1", c.projectId);
    }

    @Test public void loadsFullRawMappingWithoutDiscardingBranches() throws Exception {
        String raw = "{\"id\":\"conv-2\",\"title\":\"Branched\",\"current_node\":\"node-b\",\"mapping\":{" +
                "\"node-a\":{\"id\":\"node-a\",\"children\":[\"node-b\"]}," +
                "\"node-b\":{\"id\":\"node-b\",\"parent\":\"node-a\"}}}";
        ChatGptModels.RemoteConversation c = ChatGptConversationsApi.load(
                endpoint -> ok(raw), ChatGptConversationsApiTest::ensure200, "conv-2");
        assertEquals("conv-2", c.id);
        assertEquals("node-b", c.currentNode);
        assertTrue(c.hasMapping);
        assertTrue(c.hasFullSnapshot());
        assertTrue(c.rawJson.contains("node-a"));
        assertTrue(c.rawJson.contains("node-b"));
    }

    @Test public void paginatesConversationSummariesByOffset() throws Exception {
        ChatGptConversationsApi.Fetcher fake = endpoint -> {
            if (endpoint.contains("offset=0")) return ok(listPage(0, 100, 101));
            if (endpoint.contains("offset=100")) return ok(listPage(100, 1, 101));
            return ok("{\"items\":[]}");
        };
        List<ChatGptModels.RemoteConversation> list = ChatGptConversationsApi.list(
                fake, ChatGptConversationsApiTest::ensure200, false, 10);
        assertEquals(101, list.size());
        assertEquals("conv-0", list.get(0).id);
        assertEquals("conv-100", list.get(100).id);
    }

    @Test public void archivedListMarksItemsArchivedEvenWhenBackendOmitsFlag() throws Exception {
        List<ChatGptModels.RemoteConversation> list = ChatGptConversationsApi.list(
                endpoint -> ok("{\"items\":[{\"id\":\"conv-a\",\"title\":\"Archived\"}],\"total\":1}"),
                ChatGptConversationsApiTest::ensure200, true, 2);
        assertEquals(1, list.size());
        assertTrue(list.get(0).archived);
    }

    @Test public void propagatesAuthStatus() throws Exception {
        try {
            ChatGptConversationsApi.list(endpoint -> status(401, "{}"), response -> {
                if (response.code == 401) throw new SecurityException("expired");
                ensure200(response);
            }, false, 2);
            fail("Expected auth error");
        } catch (SecurityException expected) {
            assertEquals("expired", expected.getMessage());
        }
    }

    private static String listPage(int start, int count, int total) {
        StringBuilder s = new StringBuilder("{\"items\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) s.append(',');
            int n = start + i;
            s.append("{\"id\":\"conv-").append(n).append("\",\"title\":\"Chat ").append(n).append("\",\"update_time\":").append(1700000000L + n).append('}');
        }
        return s.append("],\"total\":").append(total).append('}').toString();
    }

    private static HttpResponse ok(String body) { return status(200, body); }
    private static HttpResponse status(int code, String body) {
        return new HttpResponse(code, body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap(), "https://chatgpt.com/backend-api/conversations");
    }
    private static void ensure200(HttpResponse response) throws Exception {
        if (response.code != 200) throw new Exception("HTTP " + response.code);
    }
}
