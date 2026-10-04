package com.gptdrive.app;

final class AccountProfile {
    final long id;
    final String label;
    final String email;
    final String cookieChatgpt;
    final String cookieAuth;
    final String cookieFiles;
    final long updated;
    final boolean active;

    AccountProfile(long id, String label, String email, String cookieChatgpt, String cookieAuth, String cookieFiles, long updated, boolean active) {
        this.id = id;
        this.label = label == null ? "" : label;
        this.email = email == null ? "" : email;
        this.cookieChatgpt = cookieChatgpt == null ? "" : cookieChatgpt;
        this.cookieAuth = cookieAuth == null ? "" : cookieAuth;
        this.cookieFiles = cookieFiles == null ? "" : cookieFiles;
        this.updated = updated;
        this.active = active;
    }

    String displayName() {
        if (!email.trim().isEmpty()) return email.trim();
        if (!label.trim().isEmpty()) return label.trim();
        return "ChatGPT account";
    }

    boolean hasSession() {
        return !cookieChatgpt.trim().isEmpty() || !cookieAuth.trim().isEmpty();
    }
}
