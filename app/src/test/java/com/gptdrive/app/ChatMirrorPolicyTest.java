package com.gptdrive.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatMirrorPolicyTest {
    @Test public void newConversationNeedsSnapshot() {
        assertTrue(ChatMirrorPolicy.needsRefresh(false, false, 0, 100));
    }

    @Test public void missingSnapshotFileNeedsRefresh() {
        assertTrue(ChatMirrorPolicy.needsRefresh(true, false, 100, 100));
    }

    @Test public void changedRemoteConversationNeedsRefresh() {
        assertTrue(ChatMirrorPolicy.needsRefresh(true, true, 100, 101));
    }

    @Test public void unchangedConversationDoesNotNeedRefresh() {
        assertFalse(ChatMirrorPolicy.needsRefresh(true, true, 100, 100));
        assertFalse(ChatMirrorPolicy.needsRefresh(true, true, 101, 100));
    }
}
