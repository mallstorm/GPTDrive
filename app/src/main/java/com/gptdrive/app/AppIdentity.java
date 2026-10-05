package com.gptdrive.app;

final class AppIdentity {
    private AppIdentity() {}

    static String userAgent() {
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Mobile Safari/537.36 GPTDrive/" + BuildConfig.VERSION_NAME;
    }

    static String providerLabel() { return "provider v" + BuildConfig.VERSION_NAME; }
}
