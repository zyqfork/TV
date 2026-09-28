package com.fongmi.android.tv.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores network credentials encrypted with an app-local Android Keystore key. */
final class NetworkCredentialStore {

    private static final String PREFS = "network_storage_credentials_v1";
    private static final String KEY_ALIAS = "network_storage_credentials_key_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int IV_BYTES = 12;
    private static final Gson GSON = new Gson();

    private NetworkCredentialStore() {
    }

    static synchronized Credentials get(String id) {
        if (TextUtils.isEmpty(id)) return Credentials.EMPTY;
        String encoded = prefs().getString(id, "");
        if (TextUtils.isEmpty(encoded)) return Credentials.EMPTY;
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException("Invalid credential payload");
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            System.arraycopy(packed, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            Credentials value = GSON.fromJson(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8), Credentials.class);
            return value == null ? Credentials.EMPTY : value;
        } catch (Exception e) {
            // A backup restored onto another device cannot be decrypted with this device's key, so
            // the secret really is unrecoverable and dropping just that entry is right. Anything
            // else — the keystore being briefly unavailable, say — must not destroy the password.
            if (isUnrecoverable(e)) prefs().edit().remove(id).apply();
            return Credentials.EMPTY;
        }
    }

    private static boolean isUnrecoverable(Exception e) {
        // Bad tag: the payload was encrypted with a different key. Illegal argument: the stored
        // payload itself is malformed. Both mean the ciphertext can never be read again.
        return e instanceof AEADBadTagException || e instanceof IllegalArgumentException;
    }

    static synchronized void put(String id, String username, String password) {
        if (TextUtils.isEmpty(id)) return;
        if (TextUtils.isEmpty(username) && TextUtils.isEmpty(password)) {
            remove(id);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(GSON.toJson(new Credentials(username, password)).getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            prefs().edit().putString(id, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect network credentials", e);
        }
    }

    static void remove(String id) {
        if (!TextUtils.isEmpty(id)) prefs().edit().remove(id).apply();
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static final class Credentials {
        static final Credentials EMPTY = new Credentials("", "");
        private String username;
        private String password;

        Credentials(String username, String password) {
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        String username() {
            return username == null ? "" : username;
        }

        String password() {
            return password == null ? "" : password;
        }
    }
}
