package io.specforge.secrets;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encrypts secrets at rest (SEC-2) and computes keyed fingerprints (SEC-6).
 * <p>
 * Key material: {@code SPECFORGE_SECRET_KEY} stretched with PBKDF2-HMAC-SHA256 (600,000 iterations) and a
 * per-install salt ({@code secret.salt}); without that variable, a random key file {@code secret.key} restricted to
 * its owner. 64 bytes are derived: the first 32 for AES-GCM, the last 32 for HMAC-SHA256.
 */
public final class SecretStore {

    public static final String ENV = "SPECFORGE_SECRET_KEY";
    private static final String PREFIX = "v1:";
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aes;
    private final SecretKeySpec mac;

    /** A stored secret can no longer be decrypted (the key changed); the user must re-enter it. */
    public static class SecretUnavailable extends Exception {
        public SecretUnavailable(String message) {
            super(message);
        }
    }

    private SecretStore(byte[] material) {
        this.aes = new SecretKeySpec(Arrays.copyOfRange(material, 0, 32), "AES");
        this.mac = new SecretKeySpec(Arrays.copyOfRange(material, 32, 64), "HmacSHA256");
        Arrays.fill(material, (byte) 0);
    }

    public static SecretStore open(Path home) throws IOException {
        return open(home, System.getenv(ENV));
    }

    static SecretStore open(Path home, String passphrase) throws IOException {
        Files.createDirectories(home);
        if (passphrase != null && !passphrase.isBlank()) {
            Path saltFile = home.resolve("secret.salt");
            if (!Files.isRegularFile(saltFile)) AtomicFiles.write(saltFile, Base64.getEncoder().encode(random(16)), false);
            byte[] salt = Base64.getDecoder().decode(Files.readString(saltFile).trim());
            try {
                PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 64 * 8);
                byte[] material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                spec.clearPassword();
                return new SecretStore(material);
            } catch (GeneralSecurityException e) {
                throw new IOException("Cannot derive the secret key: " + e.getMessage(), e);
            }
        }
        Path keyFile = home.resolve("secret.key");
        if (!Files.isRegularFile(keyFile)) AtomicFiles.write(keyFile, Base64.getEncoder().encode(random(64)), true);
        byte[] material = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        if (material.length != 64) throw new IOException("The key file " + keyFile + " is damaged; delete it and re-enter saved passwords.");
        return new SecretStore(material);
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = random(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String token) throws SecretUnavailable {
        if (token == null || !token.startsWith(PREFIX)) throw new SecretUnavailable("A saved secret is in an unknown format; re-enter it.");
        try {
            byte[] in = Base64.getDecoder().decode(token.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretUnavailable("A saved secret cannot be decrypted with the current key (" + ENV
                + " or secret.key changed). Re-enter it.");
        }
    }

    /** Keyed fingerprint: identifies settings without storing a guessable hash of a password (SEC-6). */
    public String fingerprint(String material) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(mac);
            return HexFormat.of().formatHex(m.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Fingerprint failed", e);
        }
    }

    /** SEC-4: the last four characters of long secrets, nothing of short ones. */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.length() >= 12 ? "••••" + value.substring(value.length() - 4) : "••••";
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
