package com.gptdrive.app;

/** Network/display identity kept in one place for this release. */
final class AppIdentity {
    static final String VERSION = "0.8.6";

    private AppIdentity() {}

    static String userAgent() {
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/150.0.0.0 Mobile Safari/537.36 GPTDrive/" + VERSION;
    }

    static String providerLabel() { return "provider v" + VERSION; }
}
