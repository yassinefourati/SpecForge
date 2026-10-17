package io.specforge.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SEC-2, SEC-4, SEC-5, SEC-6. */
class SecretsTest {

    @TempDir
    Path home;

    @Test
    void encryptsAndDecryptsWithAGeneratedKeyFile() throws Exception {
        SecretStore s = SecretStore.open(home, null);
        String token = s.encrypt("s3cr3t-Passw0rd");
        assertThat(token).startsWith("v1:").doesNotContain("s3cr3t");
        assertThat(s.decrypt(token)).isEqualTo("s3cr3t-Passw0rd");
        assertThat(s.encrypt("same")).isNotEqualTo(s.encrypt("same")); // random IV
        assertThat(Files.readString(home.resolve("secret.key")).trim()).hasSizeGreaterThan(40);
        assertThat(SecretStore.open(home, null).decrypt(token)).as("same key file, same key").isEqualTo("s3cr3t-Passw0rd");
    }

    @Test
    void passphraseKeyIsStableAndDifferentKeysCannotDecrypt() throws Exception {
        SecretStore a = SecretStore.open(home, "correct horse battery staple");
        String token = a.encrypt("pw");
        assertThat(SecretStore.open(home, "correct horse battery staple").decrypt(token)).isEqualTo("pw");
        assertThatThrownBy(() -> SecretStore.open(home, "another passphrase").decrypt(token))
            .isInstanceOf(SecretStore.SecretUnavailable.class).hasMessageContaining("Re-enter");
    }

    @Test
    void fingerprintsAreKeyedAndDeterministic() throws Exception {
        SecretStore a = SecretStore.open(home.resolve("a"), null), b = SecretStore.open(home.resolve("b"), null);
        assertThat(a.fingerprint("x")).isEqualTo(a.fingerprint("x")).isNotEqualTo(a.fingerprint("y"));
        assertThat(a.fingerprint("x")).as("keyed: another install gives another fingerprint").isNotEqualTo(b.fingerprint("x"));
    }

    @Test
    void masksOnlyTheEndOfLongSecrets() {
        assertThat(SecretStore.mask("")).isEmpty();
        assertThat(SecretStore.mask("short")).isEqualTo("••••");
        assertThat(SecretStore.mask("averylongsecret1234")).isEqualTo("••••1234");
    }

    @Test
    void redactsSecretsUrlsAndKeyValuesAndKeepsOneLine() {
        String msg = "FATAL: password authentication failed for user \"bob\" using Hunter2pw at postgresql://bob:Hunter2pw@db:5432/x?password=Hunter2pw\nsecond line";
        String r = Redactor.redact(msg, List.of("Hunter2pw"));
        assertThat(r).doesNotContain("Hunter2pw").doesNotContain("second line").contains("•••");
        assertThat(Redactor.redact("jdbc:postgresql://u:p4ss@h/db token=abc123", List.of())).doesNotContain("p4ss").doesNotContain("abc123");
        assertThat(Redactor.redact("x".repeat(500), List.of())).hasSizeLessThanOrEqualTo(300);
    }
}
