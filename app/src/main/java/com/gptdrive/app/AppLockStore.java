package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Persistent app-lock settings plus process-local lock state. */
final class AppLockStore {
    static final long TIMEOUT_IMMEDIATE = 0L;
    static final long TIMEOUT_30_SECONDS = 30_000L;
    static final long TIMEOUT_1_MINUTE = 60_000L;
    static final long TIMEOUT_5_MINUTES = 300_000L;

    private static final String PREFS = "gptdrive_app_lock";
    private static final String K_ENABLED = "enabled";
    private static final String K_BIOMETRIC = "biometric";
    private static final String K_PATTERN_SALT = "pattern_salt";
    private static final String K_PATTERN_HASH = "pattern_hash";
    private static final String K_TIMEOUT = "timeout_ms";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private static volatile boolean sessionUnlocked;
    private static volatile long backgroundAtElapsed;

    private final SharedPreferences prefs;

    AppLockStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isEnabled() {
        return prefs.getBoolean(K_ENABLED, false) && hasPattern();
    }

    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(K_ENABLED, enabled && hasPattern()).apply();
        if (!enabled) markSessionUnlocked();
    }

    boolean isBiometricEnabled() {
        return isEnabled() && prefs.getBoolean(K_BIOMETRIC, false);
    }

    void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(K_BIOMETRIC, enabled && hasPattern()).apply();
    }

    boolean hasPattern() {
        return !prefs.getString(K_PATTERN_SALT, "").isEmpty() &&
                !prefs.getString(K_PATTERN_HASH, "").isEmpty();
    }

    boolean savePattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return false;
        try {
            byte[] salt = new byte[24];
            new SecureRandom().nextBytes(salt);
            byte[] hash = derive(pattern, salt);
            prefs.edit()
                    .putString(K_PATTERN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(K_PATTERN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .putBoolean(K_ENABLED, true)
                    .apply();
            markSessionUnlocked();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean verifyPattern(String pattern) {
        if (pattern == null || !hasPattern()) return false;
        try {
            byte[] salt = Base64.decode(prefs.getString(K_PATTERN_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(prefs.getString(K_PATTERN_HASH, ""), Base64.NO_WRAP);
            return MessageDigest.isEqual(expected, derive(pattern, salt));
        } catch (Exception e) {
            return false;
        }
    }

    void clearProtection() {
        prefs.edit().clear().apply();
        markSessionUnlocked();
    }

    long timeoutMs() {
        long value = prefs.getLong(K_TIMEOUT, TIMEOUT_IMMEDIATE);
        if (value == TIMEOUT_30_SECONDS || value == TIMEOUT_1_MINUTE || value == TIMEOUT_5_MINUTES)
            return value;
        return TIMEOUT_IMMEDIATE;
    }

    void setTimeoutMs(long timeout) {
        long value = timeout;
        if (value != TIMEOUT_IMMEDIATE && value != TIMEOUT_30_SECONDS &&
                value != TIMEOUT_1_MINUTE && value != TIMEOUT_5_MINUTES)
            value = TIMEOUT_IMMEDIATE;
        prefs.edit().putLong(K_TIMEOUT, value).apply();
    }

    String timeoutLabel() {
        long value = timeoutMs();
        if (value == TIMEOUT_30_SECONDS) return "Через 30 секунд";
        if (value == TIMEOUT_1_MINUTE) return "Через 1 минуту";
        if (value == TIMEOUT_5_MINUTES) return "Через 5 минут";
        return "Сразу";
    }

    boolean shouldRequireUnlock() {
        if (!isEnabled()) return false;
        if (!sessionUnlocked) return true;
        long at = backgroundAtElapsed;
        if (at <= 0) return false;
        return SystemClock.elapsedRealtime() - at >= timeoutMs();
    }

    static void markSessionUnlocked() {
        sessionUnlocked = true;
        backgroundAtElapsed = 0L;
    }

    static void lockSession() {
        sessionUnlocked = false;
    }

    static void markBackgrounded() {
        backgroundAtElapsed = SystemClock.elapsedRealtime();
    }

    static void clearBackgroundMarker() {
        backgroundAtElapsed = 0L;
    }

    private static byte[] derive(String pattern, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pattern.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
