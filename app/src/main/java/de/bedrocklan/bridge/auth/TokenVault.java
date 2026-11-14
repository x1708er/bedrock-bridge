package de.bedrocklan.bridge.auth;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM-Verschlüsselung für Refresh-Tokens über den AndroidKeyStore.
 * Keine dauerhafte Klartextspeicherung; Bestandswerte werden beim ersten
 * Zugriff migriert. Fehler werden nie verschluckt — Aufrufer entscheiden.
 */
final class TokenVault {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "blb_refresh_tokens";
    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecretKey key;

    /** true, wenn der gespeicherte Wert bereits Vault-verschlüsselt ist. */
    static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Verschlüsselt einen Klartext; Ergebnis beginnt mit dem Präfix. */
    synchronized String encrypt(String plaintext) throws GeneralSecurityException {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new GeneralSecurityException("Kein Klartext vorhanden");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] out = new byte[IV_BYTES + ct.length];
        System.arraycopy(iv, 0, out, 0, Math.min(iv.length, IV_BYTES));
        System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
        return PREFIX + Base64.getEncoder().encodeToString(out);
    }

    /** Entschlüsselt einen Vault-Wert; wirft bei Defekt statt Daten zu verlieren. */
    synchronized String decrypt(String stored) throws GeneralSecurityException {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new GeneralSecurityException("Wert ist nicht Vault-verschlüsselt");
        }
        byte[] in;
        try {
            in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Vault-Wert ist kein Base64", e);
        }
        if (in.length < IV_BYTES + (TAG_BITS / 8)) {
            throw new GeneralSecurityException("Vault-Wert zu kurz");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
        return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private SecretKey key() throws GeneralSecurityException {
        if (key != null) return key;
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                key = ((KeyStore.SecretKeyEntry) e).getSecretKey();
                return key;
            }
        } catch (GeneralSecurityException | java.io.IOException ex) {
            throw new GeneralSecurityException("AndroidKeyStore nicht lesbar", ex);
        }
        try {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            g.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            key = g.generateKey();
            return key;
        } catch (GeneralSecurityException ex) {
            throw new GeneralSecurityException("AndroidKeyStore-Schlüssel nicht erzeugbar", ex);
        }
    }
}
