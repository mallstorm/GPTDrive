package com.gptdrive.app;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ChatGptRoutePolicyTest {
    @Test public void routesOnlyOpenAiDomains() {
        assertTrue(ChatGptRoutePolicy.isOpenAiHost("auth.openai.com"));
        assertTrue(ChatGptRoutePolicy.isOpenAiHost("FILES.OAIUSERCONTENT.COM."));
        assertFalse(ChatGptRoutePolicy.isOpenAiHost("accounts.google.com"));
        assertFalse(ChatGptRoutePolicy.isOpenAiHost("gemini.google.com"));
        assertFalse(ChatGptRoutePolicy.isOpenAiHost("chatgpt.com.attacker.example"));
        assertFalse(ChatGptRoutePolicy.isOpenAiHost("fakechatgpt.com"));
    }
    @Test public void rejectsChallengesAndErrorsRatherThanTreatingEveryHttpResponseAsWorking() {
        assertTrue(ChatGptRoutePolicy.isSessionResponse(200, "{}"));
        assertTrue(ChatGptRoutePolicy.isSessionResponse(200, "{\"user\":{\"id\":\"x\"},\"expires\":\"later\"}"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(403, "{}"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(401, "{}"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(302, "{}"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(200, "<html>Just a moment...</html>"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(200, "{\"error\":\"blocked\"}"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(200, "{\"WARNING_BANNER\":\"Country, region, or territory not supported\"}"));
        assertFalse(ChatGptRoutePolicy.isSessionResponse(200, "{broken}"));
    }
    @Test public void decodesChunkedSessionWithExtensionsWithoutWaitingForClose() throws Exception {
        ChatGptRoutePolicy.HttpResponse r = response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1;x=y\r\n{\r\n1\r\n}\r\n0\r\n\r\n");
        assertEquals("{}", r.body);
        assertTrue(ChatGptRoutePolicy.isSessionResponse(r.code, r.body));
    }
    @Test public void respectsContentLength() throws Exception {
        assertEquals("{}", response("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}extra").body);
    }
    @Test(expected=IOException.class) public void rejectsTruncatedBody() throws Exception {
        response("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n{}");
    }
    @Test(expected=IOException.class) public void rejectsOversizedChunk() throws Exception {
        response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffff\r\n");
    }
    private static ChatGptRoutePolicy.HttpResponse response(String text) throws Exception {
        return ChatGptRoutePolicy.readResponse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
