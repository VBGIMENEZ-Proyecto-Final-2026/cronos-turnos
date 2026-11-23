package ar.edu.um.cronos.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validaciones de la configuración que usa la etapa 4: tolerancia de reloj y orígenes CORS. */
class SecurityPropertiesTest {

    private static SecurityProperties.Jwt jwt(Duration clockSkew) {
        return new SecurityProperties.Jwt("clave", null, Duration.ofHours(1), Duration.ofDays(30), clockSkew);
    }

    @Test
    void laToleranciaDeRelojDebeEstarEntreCeroYCincoMinutos() {
        assertThat(jwt(Duration.ZERO).clockSkew()).isZero();
        assertThat(jwt(Duration.ofMinutes(5)).clockSkew()).isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> jwt(Duration.ofSeconds(-1))).hasMessageContaining("CRONOS_JWT_CLOCK_SKEW");
        assertThatThrownBy(() -> jwt(Duration.ofMinutes(6))).hasMessageContaining("CRONOS_JWT_CLOCK_SKEW");
    }

    @Test
    void sinOrigenesNoSeAceptaNinguno() {
        assertThat(new SecurityProperties.Cors(List.of(), Duration.ofHours(1)).allowedOrigins()).isEmpty();
        assertThat(new SecurityProperties.Cors(List.of("", "  "), Duration.ofHours(1)).allowedOrigins()).isEmpty();
    }

    @Test
    void losOrigenesValidosSeAceptanSinEspacios() {
        SecurityProperties.Cors cors = new SecurityProperties.Cors(
                List.of(" https://app.ejemplo.test ", "http://localhost:3000"), Duration.ofHours(1));

        assertThat(cors.allowedOrigins()).containsExactly("https://app.ejemplo.test", "http://localhost:3000");
    }

    @Test
    void unOrigenConComodinRutaOSinEsquemaSeRechaza() {
        for (String malo : List.of("*", "https://*.ejemplo.test", "https://app.ejemplo.test/ruta", "app.ejemplo.test",
                "https://usuario@app.ejemplo.test", "ftp://app.ejemplo.test")) {
            assertThatThrownBy(() -> new SecurityProperties.Cors(List.of(malo), Duration.ofHours(1)))
                    .as(malo).hasMessageContaining("CRONOS_CORS_ALLOWED_ORIGINS");
        }
    }

    @Test
    void laVigenciaDeLaConsultaPreviaNoPuedeSerNegativa() {
        assertThatThrownBy(() -> new SecurityProperties.Cors(List.of(), Duration.ofSeconds(-1)))
                .hasMessageContaining("max-age");
    }
}
