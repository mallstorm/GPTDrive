package com.gptdrive.app;

final class DriveItem {
    final long id;
    final long accountId;
    final String category;
    final String name;
    final String url;
    final String mime;
    final long updated;

    DriveItem(long id, long accountId, String category, String name, String url, String mime, long updated) {
        this.id = id;
        this.accountId = accountId;
        this.category = category;
        this.name = name;
        this.url = url;
        this.mime = mime;
        this.updated = updated;
    }
}
