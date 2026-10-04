package com.gptdrive.app;

final class AccountProfile {
    final long id;
    final String label;
    final String email;
    final String plan;
    final String cookieChatgpt;
    final String cookieAuth;
    final String cookieFiles;
    final long remoteUsedBytes;
    final long remoteQuotaBytes;
    final long lastSync;
    final long updated;
    final boolean active;

    AccountProfile(long id, String label, String email, String plan,
                   String cookieChatgpt, String cookieAuth, String cookieFiles,
                   long remoteUsedBytes, long remoteQuotaBytes, long lastSync,
                   long updated, boolean active) {
        this.id = id;
        this.label = label == null ? "" : label;
        this.email = email == null ? "" : email;
        this.plan = plan == null ? "" : plan;
        this.cookieChatgpt = cookieChatgpt == null ? "" : cookieChatgpt;
        this.cookieAuth = cookieAuth == null ? "" : cookieAuth;
        this.cookieFiles = cookieFiles == null ? "" : cookieFiles;
        this.remoteUsedBytes = Math.max(0, remoteUsedBytes);
        this.remoteQuotaBytes = Math.max(0, remoteQuotaBytes);
        this.lastSync = Math.max(0, lastSync);
        this.updated = updated;
        this.active = active;
    }

    String displayName() {
        if (!email.trim().isEmpty()) return email.trim();
        if (!label.trim().isEmpty()) return label.trim();
        return "ChatGPT account";
    }

    String planLabel() {
        return plan.trim().isEmpty() ? "Не определён" : plan.trim();
    }

    boolean hasSession() {
        return !cookieChatgpt.trim().isEmpty() || !cookieAuth.trim().isEmpty();
    }

    boolean hasRemoteQuota() {
        return remoteQuotaBytes > 0;
    }
}
