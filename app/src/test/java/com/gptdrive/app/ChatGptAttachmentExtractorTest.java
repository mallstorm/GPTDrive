package com.gptdrive.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatGptAttachmentExtractorTest {
    @Test public void extractsMessageAttachmentsAndAssetPointers() throws Exception {
        JSONObject root = new JSONObject("{\n" +
                "  \"id\":\"conv-1\",\n" +
                "  \"mapping\":{\n" +
                "    \"node-a\":{\"message\":{\"id\":\"msg-a\",\"metadata\":{\"attachments\":[{\"id\":\"file-report123\",\"name\":\"report.pdf\",\"mime_type\":\"application/pdf\",\"size\":42}]}}},\n" +
                "    \"node-b\":{\"message\":{\"id\":\"msg-b\",\"content\":{\"content_type\":\"multimodal_text\",\"parts\":[{\"asset_pointer\":\"file-service://file-image123\",\"mime_type\":\"image/png\",\"width\":512,\"height\":512}]}}},\n" +
                "    \"node-c\":{\"message\":{\"id\":\"msg-c\",\"metadata\":{\"attachments\":[{\"file_id\":\"file-report123\",\"name\":\"report.pdf\"}]}}}\n" +
                "  }\n" +
                "}");

        List<ChatGptModels.RemoteFile> files = ChatGptAttachmentExtractor.extract(root, "conv-1", "project-1");
        assertEquals(2, files.size());

        ChatGptModels.RemoteFile report = find(files, "file-report123");
        assertEquals("conv-1", report.conversationId);
        assertEquals("msg-a", report.messageId);
        assertEquals("project-1", report.projectId);
        assertEquals("report.pdf", report.name);
        assertTrue((report.sourceFlags & ChatGptModels.SRC_CONVERSATION) != 0);

        ChatGptModels.RemoteFile image = find(files, "file-image123");
        assertEquals("conv-1", image.conversationId);
        assertEquals("msg-b", image.messageId);
        assertEquals("image/png", image.mimeType);
        assertEquals(512, image.width);
        assertEquals(512, image.height);
    }

    private static ChatGptModels.RemoteFile find(List<ChatGptModels.RemoteFile> files, String id) {
        for (ChatGptModels.RemoteFile f : files) if (id.equals(f.identity())) return f;
        throw new AssertionError("Missing file " + id);
    }
}
