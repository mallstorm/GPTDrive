package com.gptdrive.app;

final class DriveItem {
    final long id;
    final String category;
    final String name;
    final String url;
    final String mime;
    final long updated;

    DriveItem(long id, String category, String name, String url, String mime, long updated) {
        this.id = id;
        this.category = category;
        this.name = name;
        this.url = url;
        this.mime = mime;
        this.updated = updated;
    }
}
