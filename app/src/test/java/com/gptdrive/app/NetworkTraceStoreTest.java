package com.gptdrive.app;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class NetworkTraceStoreTest {
    @Test public void stripsChatGptQueryAndCursorValues() {
        String out = NetworkTraceStore.redact("https://chatgpt.com/backend-api/files/library?cursor=secret-cursor&token=x");
        assertEquals("chatgpt.com/backend-api/files/library", out);
        assertFalse(out.contains("secret-cursor"));
        assertFalse(out.contains("token"));
    }

    @Test public void signedStorageUrlKeepsOnlyHost() {
        String out = NetworkTraceStore.redact("https://files.oaiusercontent.com/private/file.png?sig=super-secret&x-amz-signature=abc");
        assertEquals("files.oaiusercontent.com", out);
        assertFalse(out.contains("file.png"));
        assertFalse(out.contains("super-secret"));
    }

    @Test public void ringBufferStoresNoHeadersOrBodies() {
        NetworkTraceStore.clear();
        NetworkTraceStore.add("GET", "https://chatgpt.com/backend-api/conversations?access_token=secret", 200, 14, 1, "System DNS", null);
        List<NetworkTraceStore.Event> events = NetworkTraceStore.recent(5);
        assertEquals(1, events.size());
        assertEquals("chatgpt.com/backend-api/conversations", events.get(0).target);
        assertEquals(200, events.get(0).status);
        assertFalse(events.get(0).target.contains("secret"));
    }
}
