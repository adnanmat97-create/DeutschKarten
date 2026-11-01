package com.example.deutschkarten;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.net.URL;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AiSecureConfig {
    private static final String PREFS = "deutschkarten_ai_secure";
    private static final String KEY_ALIAS = "deutschkarten_ai_token_key_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private AiSecureConfig() {}

    public static synchronized boolean save(Context context, String endpoint, String token) {
        try {
            String safeEndpoint = endpoint == null ? "" : endpoint.trim();
            String safeToken = token == null ? "" : token.trim();
            URL url = new URL(safeEndpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || safeToken.length() < 24 || safeToken.length() > 512) {
                return false;
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(safeToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.edit()
                    .putString("endpoint", safeEndpoint)
                    .putString("token_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("token_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized String getEndpoint(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", "");
    }

    public static synchronized boolean isConfigured(Context context) {
        return !getEndpoint(context).isEmpty() && !getToken(context).isEmpty();
    }

    static synchronized String getToken(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ivText = prefs.getString("token_iv", "");
            String cipherText = prefs.getString("token_ciphertext", "");
            if (ivText.isEmpty() || cipherText.isEmpty()) return "";
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(cipherText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] decoded = cipher.doFinal(encrypted);
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Throwable ignored) {}
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
