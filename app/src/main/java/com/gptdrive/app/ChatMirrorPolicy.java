package com.gptdrive.app;

/** Pure policy helpers for deciding when a local Chat Mirror body needs refresh. */
final class ChatMirrorPolicy {
    private ChatMirrorPolicy() {}

    static boolean needsRefresh(boolean hasStoredPath, boolean snapshotFileExists,
                                long sourceUpdatedAt, long remoteUpdatedAt) {
        if (!hasStoredPath || !snapshotFileExists) return true;
        if (sourceUpdatedAt <= 0) return true;
        return remoteUpdatedAt > 0 && sourceUpdatedAt < remoteUpdatedAt;
    }
}
