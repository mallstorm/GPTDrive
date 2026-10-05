package com.gptdrive.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.*;

public class LibraryPaginationEngineTest {
    @Test public void negotiatesPastBackendThatIgnoresFirstCursorShape() throws Exception {
        LibraryPaginationEngine.Fetcher fake = request -> {
            if (request.strategy == -1) return ok(page("file_a123456", "c1", true));
            if (request.strategy == 0) return ok(page("file_a123456", "c1", true)); // backend ignored cursor
            if (request.strategy == 1) return ok(page("file_b123456", "", false));
            return status(400, "{}");
        };

        LibraryPaginationEngine.Result r = LibraryPaginationEngine.load(fake, LibraryPaginationEngineTest::ensure200, 20, null);
        assertEquals(2, r.files.size());
        assertEquals(2, r.pages);
        assertEquals(1, r.strategy);
        assertEquals("file_a123456", r.files.get(0).fileId);
        assertEquals("file_b123456", r.files.get(1).fileId);
    }

    @Test public void fallsBackToOffsetWhenHasMoreHasNoCursor() throws Exception {
        LibraryPaginationEngine.Fetcher fake = request -> {
            if (request.strategy == -1) return ok("{\"items\":[{\"file_id\":\"file_a123456\",\"name\":\"a.pdf\"}],\"has_more\":true}");
            if (request.strategy == 5 && request.payload.contains("\"offset\":1"))
                return ok(page("file_b123456", "", false));
            return status(400, "{}");
        };

        LibraryPaginationEngine.Result r = LibraryPaginationEngine.load(fake, LibraryPaginationEngineTest::ensure200, 20, null);
        assertEquals(2, r.files.size());
        assertEquals(5, r.strategy);
        assertEquals(2, r.pages);
    }

    @Test public void rejectsRepeatedChosenPageInsteadOfLoopingForever() throws Exception {
        LibraryPaginationEngine.Fetcher fake = request -> {
            if (request.strategy == -1) return ok(page("file_a123456", "c1", true));
            if (request.strategy == 0 && request.payload.contains("c1")) return ok(page("file_b123456", "c2", true));
            if (request.strategy == 0 && request.payload.contains("c2")) return ok(page("file_b123456", "c2", true));
            return status(400, "{}");
        };

        try {
            LibraryPaginationEngine.load(fake, LibraryPaginationEngineTest::ensure200, 20, null);
            fail("Expected PaginationFailure");
        } catch (LibraryPaginationEngine.PaginationFailure expected) {
            assertTrue(expected.getMessage().contains("повторяет") || expected.getMessage().contains("зациклился"));
        }
    }

    @Test public void strategyRequestsMatchWindowsProviderShapes() {
        assertEquals("{\"cursor\":\"abc\"}", LibraryPaginationEngine.requestForStrategy(0, "abc", 10).payload);
        assertTrue(LibraryPaginationEngine.requestForStrategy(1, "abc", 10).payload.contains("\"limit\":100"));
        assertEquals("{\"after\":\"abc\"}", LibraryPaginationEngine.requestForStrategy(2, "abc", 10).payload);
        assertEquals("{\"next_cursor\":\"abc\"}", LibraryPaginationEngine.requestForStrategy(3, "abc", 10).payload);
        assertTrue(LibraryPaginationEngine.requestForStrategy(4, "a b", 10).endpoint.contains("cursor=a%20b"));
        assertTrue(LibraryPaginationEngine.requestForStrategy(5, "", 123).payload.contains("\"offset\":123"));
    }

    @Test public void authFailureDuringProbeIsNotMisclassifiedAsStrategyRejection() throws Exception {
        LibraryPaginationEngine.Fetcher fake = request -> {
            if (request.strategy == -1) return ok(page("file_a123456", "c1", true));
            return status(401, "{}");
        };

        try {
            LibraryPaginationEngine.load(fake, response -> {
                if (response.code == 401) throw new SecurityException("expired");
                ensure200(response);
            }, 20, null);
            fail("Expected auth error");
        } catch (SecurityException expected) {
            assertEquals("expired", expected.getMessage());
        }
    }

    @Test public void rejectsHtml200InsteadOfFakeEmptySync() throws Exception {
        try {
            ChatGptProvider.validateLibraryResponse(ok("<!doctype html><html><body>login</body></html>"));
            fail("Expected SchemaException");
        } catch (ChatGptProvider.SchemaException expected) {
            assertTrue(expected.getMessage().contains("не JSON"));
        }
    }

    @Test public void warningBanner200RequiresReauthentication() throws Exception {
        try {
            ChatGptProvider.validateLibraryResponse(ok("{\"WARNING_BANNER\":\"DO NOT SHARE\"}"));
            fail("Expected AuthException");
        } catch (ChatGptProvider.AuthException expected) {
            assertTrue(expected.getMessage().contains("WARNING_BANNER"));
        }
    }

    @Test public void acceptsExplicitEmptyLibraryEnvelope() throws Exception {
        HttpResponse r = ok("{\"items\":[],\"has_more\":false,\"total\":0}");
        assertSame(r, ChatGptProvider.validateLibraryResponse(r));
    }

    @Test public void rejectsUnknownJsonObjectInsteadOfMarkingSynced() throws Exception {
        try {
            ChatGptProvider.validateLibraryResponse(ok("{\"status\":\"ok\"}"));
            fail("Expected SchemaException");
        } catch (ChatGptProvider.SchemaException expected) {
            assertTrue(expected.getMessage().contains("ложный успех"));
        }
    }

    private static String page(String fileId, String next, boolean more) {
        String meta = next == null || next.isEmpty()
                ? "\"has_more\":" + more
                : "\"next_cursor\":\"" + next + "\",\"has_more\":" + more;
        return "{\"items\":[{\"file_id\":\"" + fileId + "\",\"name\":\"" + fileId + ".pdf\"}]," + meta + "}";
    }

    private static HttpResponse ok(String body) { return status(200, body); }

    private static HttpResponse status(int code, String body) {
        return new HttpResponse(code, body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap(), "https://chatgpt.com/backend-api/files/library");
    }

    private static void ensure200(HttpResponse response) throws Exception {
        if (response.code != 200) throw new Exception("HTTP " + response.code);
    }
}
