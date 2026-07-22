package com.nfcvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts vault values at rest with a key that never leaves Android Keystore. */
final class SecureVaultStore {
    private static final String TAG = "SecureVaultStore";
    private static final String PREFS_NAME = "nfc_vault_secure_prefs";
    private static final String LEGACY_PREFS_NAME = "nfc_vault_prefs";
    private static final String KEY_ALIAS = "nfc_vault_aes_key_v1";
    private static final String VALUE_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Context context;

    SecureVaultStore(Context context) {
        this.context = context.getApplicationContext();
    }

    void putString(String key, String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String encoded = VALUE_PREFIX
                    + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            securePreferences().edit().putString(key, encoded).apply();
            legacyPreferences().edit().remove(key).apply();
        } catch (Exception e) {
            Log.e(TAG, "Unable to encrypt vault data", e);
            throw new IllegalStateException("Secure storage is unavailable", e);
        }
    }

    String getString(String key) {
        String stored = securePreferences().getString(key, null);
        if (stored != null) {
            return decrypt(stored);
        }

        // One-time migration from the previous unencrypted SharedPreferences file.
        String legacy = legacyPreferences().getString(key, null);
        if (legacy != null) {
            putString(key, legacy);
            return legacy;
        }
        return "";
    }

    void remove(String key) {
        securePreferences().edit().remove(key).apply();
        legacyPreferences().edit().remove(key).apply();
    }

    private String decrypt(String stored) {
        if (!stored.startsWith(VALUE_PREFIX)) {
            // Migrate values written during an interrupted upgrade.
            return stored;
        }
        try {
            String[] parts = stored.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid encrypted value");
            }
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Unable to decrypt vault data", e);
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private SharedPreferences securePreferences() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private SharedPreferences legacyPreferences() {
        return context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
    }
}
