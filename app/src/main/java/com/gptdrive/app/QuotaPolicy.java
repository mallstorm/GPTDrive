package com.gptdrive.app;

import java.util.Locale;

/** Remote quota wins. Plus gets a conservative 20 GiB UI fallback until ChatGPT exposes quota metadata. */
final class QuotaPolicy {
    static final class Value {
        final long used;
        final long quota;
        final boolean fallback;
        Value(long used, long quota, boolean fallback) {
            this.used = Math.max(0, used);
            this.quota = Math.max(0, quota);
            this.fallback = fallback;
        }
        int percent() {
            if (quota <= 0) return -1;
            return (int) Math.max(0, Math.min(100, Math.round(used * 100f / quota)));
        }
        long remaining() { return quota <= 0 ? 0 : Math.max(0, quota - used); }
    }

    private static final long GIB = 1024L * 1024L * 1024L;
    private QuotaPolicy() {}

    static Value resolve(AccountProfile account, long indexedBytes) {
        if (account == null) return new Value(indexedBytes, 0, false);
        long used = account.remoteUsedBytes > 0 ? account.remoteUsedBytes : Math.max(0, indexedBytes);
        if (account.remoteQuotaBytes > 0) return new Value(used, account.remoteQuotaBytes, false);

        String plan = account.planLabel() == null ? "" : account.planLabel().toLowerCase(Locale.ROOT);
        // User-visible fallback only. It never overrides an actual quota returned by ChatGPT.
        if (plan.contains("plus")) return new Value(used, 20L * GIB, true);
        return new Value(used, 0, false);
    }
}
