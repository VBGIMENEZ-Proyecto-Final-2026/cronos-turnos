package ar.edu.um.cronos.adapters.out.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    void elHashNoEsLaContrasenaYSeVerificaConMatches() {
        String hash = hasher.hash("clave-secreta");

        assertThat(hash).startsWith("$2").hasSize(60).doesNotContain("clave-secreta");
        assertThat(hasher.matches("clave-secreta", hash)).isTrue();
        assertThat(hasher.matches("otra-clave", hash)).isFalse();
    }

    @Test
    void laMismaContrasenaDaHashesDistintosPorLaSal() {
        assertThat(hasher.hash("clave-secreta")).isNotEqualTo(hasher.hash("clave-secreta"));
    }

    @Test
    void aceptaLaContrasenaMasLargaDelContratoDeCienCaracteres() {
        String larga = "a".repeat(100);

        assertThat(hasher.matches(larga, hasher.hash(larga))).isTrue();
    }

    @Test
    void laContrasenaDeCienCaracteresMultibyteSuperaLos72BytesYFunciona() {
        String multibyte = "é".repeat(100); // 200 bytes en UTF-8

        assertThat(hasher.matches(multibyte, hasher.hash(multibyte))).isTrue();
    }

    @Test
    void dosContrasenasQueComparten72BytesNoSeConfunden() {
        String base = "a".repeat(72);
        String hash = hasher.hash(base + "uno");

        assertThat(hasher.matches(base + "uno", hash)).isTrue();
        assertThat(hasher.matches(base + "dos", hash)).isFalse();
        assertThat(hasher.matches(base, hash)).isFalse();
    }
}
