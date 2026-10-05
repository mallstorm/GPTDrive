package com.gptdrive.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Android Keystore-backed envelope for sensitive JSON records. */
final class SecureJsonStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";

    private SecureJsonStore() {}

    static String seal(String alias, String plain) throws Exception {
        SecretKey key = key(alias);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        JSONObject wrapper = new JSONObject();
        wrapper.put("v", 1);
        wrapper.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        wrapper.put("ct", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return wrapper.toString();
    }

    static String open(String alias, String stored) throws Exception {
        if (stored == null || stored.trim().isEmpty()) return "";
        JSONObject wrapper;
        try { wrapper = new JSONObject(stored); }
        catch (Exception e) { return stored; }
        if (wrapper.optInt("v", 0) != 1 || !wrapper.has("ct") || !wrapper.has("iv")) return stored;
        byte[] iv = Base64.decode(wrapper.getString("iv"), Base64.NO_WRAP);
        byte[] ct = Base64.decode(wrapper.getString("ct"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key(alias), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    static boolean isSealed(String stored) {
        try {
            JSONObject o = new JSONObject(stored == null ? "" : stored);
            return o.optInt("v", 0) == 1 && o.has("iv") && o.has("ct");
        } catch (Exception e) {
            return false;
        }
    }

    private static SecretKey key(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEY_STORE);
        ks.load(null);
        java.security.Key existing = ks.getKey(alias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        kg.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
