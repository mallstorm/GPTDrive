package com.gptdrive.app;

final class DriveItem {
    final long id;
    final long accountId;
    final String category;
    final String name;
    final String url;
    final String mime;
    final long sizeBytes;
    final long updated;
    final String fileId;
    final String libraryFileId;
    final long created;

    DriveItem(long id, long accountId, String category, String name, String url, String mime, long sizeBytes, long updated) {
        this(id, accountId, category, name, url, mime, sizeBytes, updated, "", "", 0);
    }

    DriveItem(long id, long accountId, String category, String name, String url, String mime,
              long sizeBytes, long updated, String fileId, String libraryFileId, long created) {
        this.id = id;
        this.accountId = accountId;
        this.category = category == null ? "" : category;
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.mime = mime == null ? "" : mime;
        this.sizeBytes = Math.max(0, sizeBytes);
        this.updated = updated;
        this.fileId = fileId == null ? "" : fileId;
        this.libraryFileId = libraryFileId == null ? "" : libraryFileId;
        this.created = created;
    }

    boolean canMutateLibrary() {
        return !libraryFileId.trim().isEmpty();
    }
}
