package ar.edu.um.cronos.config;

import ar.edu.um.cronos.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPrivateKeysTest {

    private static SecurityProperties.Jwt jwt(String inline, String location) {
        return new SecurityProperties.Jwt(inline, location, Duration.ofHours(1), Duration.ofDays(30), Duration.ofSeconds(60));
    }

    @Test
    void cargaLaClaveDesdeUnArchivo(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("clave.pem"), TestKeys.privatePem());

        RSAPrivateKey key = JwtPrivateKeys.load(jwt(null, file.toString()));

        assertThat(key.getModulus()).isEqualTo(TestKeys.privateKey().getModulus());
    }

    @Test
    void cargaLaClaveDesdeUnaVariableConBarraNLiterales() {
        String enUnaLinea = TestKeys.privatePem().trim().replace("\n", "\\n");

        assertThat(JwtPrivateKeys.load(jwt(enUnaLinea, null)).getModulus())
                .isEqualTo(TestKeys.privateKey().getModulus());
    }

    @Test
    void laPublicaQueSeDerivaEsLaDelParDeClaves() {
        RSAPrivateKey key = JwtPrivateKeys.load(jwt(TestKeys.privatePem(), null));

        assertThat(JwtPrivateKeys.publicKeyOf(key)).isEqualTo(TestKeys.publicKey());
    }

    @Test
    void sinNingunaClaveNoArranca() {
        assertThatThrownBy(() -> jwt(null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRONOS_JWT_PRIVATE_KEY");
        assertThatThrownBy(() -> jwt("  ", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conLasDosFormasTampocoArranca() {
        assertThatThrownBy(() -> jwt("x", "y")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("solo una");
    }

    @Test
    void unArchivoInexistenteDaUnMensajeClaro() {
        assertThatThrownBy(() -> JwtPrivateKeys.load(jwt(null, "/no/existe/clave.pem")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No existe el archivo");
    }

    @Test
    void unTextoQueNoEsUnPemSeRechazaSinRepetirSuContenido() {
        assertThatThrownBy(() -> JwtPrivateKeys.load(jwt("esto-es-un-secreto-que-no-es-pem", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PKCS#8")
                .hasMessageNotContaining("esto-es-un-secreto");
    }

    @Test
    void unaClaveChicaSeRechaza() {
        String chica = TestKeys.pem("PRIVATE KEY", TestKeys.generate(1024).getPrivate().getEncoded());

        assertThatThrownBy(() -> JwtPrivateKeys.load(jwt(chica, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2048");
    }

    @Test
    void unaClavePublicaNoSirveComoPrivada() {
        String publica = TestKeys.pem("PUBLIC KEY", TestKeys.publicKey().getEncoded());

        assertThatThrownBy(() -> JwtPrivateKeys.load(jwt(publica, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lasVigenciasDebenSerCoherentes() {
        assertThatThrownBy(() -> new SecurityProperties.Jwt("x", null, Duration.ZERO, Duration.ofDays(1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityProperties.Jwt("x", null, Duration.ofDays(2), Duration.ofDays(1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elToStringDeLasPropiedadesNoImprimeLaClave() {
        assertThat(jwt(TestKeys.privatePem(), null).toString()).doesNotContain("BEGIN").contains("definida");
    }
}
