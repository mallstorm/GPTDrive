package com.gptdrive.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;

import static org.junit.Assert.*;

public class ChatGptResponseParserTest {
    @Test public void parsesNestedPaginationAndFiles() {
        String json = "{\"data\":{\"items\":[{\"file_id\":\"file_abc12345\",\"file_name\":\"report.pdf\",\"mime_type\":\"application/pdf\"}]}," +
                "\"meta\":{\"next_cursor\":\"cursor-2\",\"has_more\":true,\"total_count\":42}}";
        ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(json);
        assertEquals(1, page.files.size());
        assertEquals("file_abc12345", page.files.get(0).fileId);
        assertEquals("cursor-2", page.meta.nextCursor);
        assertTrue(page.meta.hasMore);
        assertEquals(42, page.meta.total);
    }

    @Test public void acceptsRootCursorAsOpaqueContinuation() {
        ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(
                "{\"items\":[{\"id\":\"file_xyz98765\",\"name\":\"x.png\"}],\"cursor\":\"opaque-next\"}");
        assertEquals("opaque-next", page.meta.nextCursor);
        assertTrue(page.meta.hasMore);
    }

    @Test public void mergesImageMetadataIntoLibraryIdentity() throws Exception {
        ChatGptModels.RemoteFile lib = ChatGptResponseParser.mapToRemote(new JSONObject(
                "{\"file_id\":\"file_same123\",\"file_name\":\"image.png\",\"size\":100}"));
        ChatGptModels.RemoteFile image = ChatGptResponseParser.imageToRemote(new JSONObject(
                "{\"file_id\":\"file_same123\",\"title\":\"Generated image\",\"width\":1024,\"height\":1024,\"conversation_id\":\"conv1\"}"));
        LinkedHashMap<String, ChatGptModels.RemoteFile> map = new LinkedHashMap<>();
        ChatGptModels.merge(map, lib);
        ChatGptModels.merge(map, image);
        assertEquals(1, map.size());
        ChatGptModels.RemoteFile merged = map.values().iterator().next();
        assertEquals(100, merged.sizeBytes);
        assertEquals(1024, merged.width);
        assertEquals("conv1", merged.conversationId);
        assertTrue(merged.isImage());
    }

    @Test public void filtersInternalUserStoragePaths() throws Exception {
        ChatGptModels.RemoteFile f = ChatGptResponseParser.mapToRemote(new JSONObject(
                "{\"file_id\":\"file_internal123\",\"name\":\"user-LcNbBJ8UFHlortegjedzlgMK/4a4975ff7a4048e4b18911589edf213b/item\",\"mime_type\":\"image/png\"}"));
        assertNull(f);
    }

    @Test public void keepsNormalUserFilename() throws Exception {
        ChatGptModels.RemoteFile f = ChatGptResponseParser.mapToRemote(new JSONObject(
                "{\"file_id\":\"file_normal123\",\"name\":\"Screenshot.png\",\"mime_type\":\"image/png\"}"));
        assertNotNull(f);
        assertEquals("Screenshot.png", f.name);
    }
}
