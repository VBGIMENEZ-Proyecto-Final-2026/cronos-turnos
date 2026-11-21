package ar.edu.um.cronos.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La configuración de los clientes de atlas y de la cátedra y de la disponibilidad: qué se exige y qué no se imprime. */
class ClientPropertiesTest {

    private static final Duration D = Duration.ofSeconds(1);

    @Test
    void laCatedraExigeUrlYTokenYDiceCualFalta() {
        assertThatThrownBy(() -> new CatedraProperties(null, "t", D, D, 1))
                .hasMessageContaining("CATEDRA_REST_BASE_URL");
        assertThatThrownBy(() -> new CatedraProperties("  ", "t", D, D, 1))
                .hasMessageContaining("CATEDRA_REST_BASE_URL");
        assertThatThrownBy(() -> new CatedraProperties("http://x.test", null, D, D, 1))
                .hasMessageContaining("CATEDRA_ID_TOKEN");
        assertThatThrownBy(() -> new CatedraProperties("http://x.test", " ", D, D, 1))
                .hasMessageContaining("CATEDRA_ID_TOKEN");
    }

    @Test
    void elTokenDeLaCatedraNoSeImprimeNiEnToStringNiEnLosErrores() {
        var propiedades = new CatedraProperties("http://x.test", "token-secreto-123", D, D, 1);

        assertThat(propiedades.toString()).doesNotContain("token-secreto-123").contains("idToken=***");
        assertThatThrownBy(() -> new CatedraProperties("ftp://x.test", "token-secreto-123", D, D, 1))
                .hasMessageNotContaining("token-secreto-123");
    }

    @Test
    void lasUrlsDebenSerHttpOHttpsAbsolutas() {
        for (String url : new String[]{"ftp://x.test", "x.test", "/api", "http://", "no es una url"}) {
            assertThatThrownBy(() -> new AtlasProperties(url, D, D, 1)).as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new AtlasProperties(" https://atlas.test:8443 ", D, D, 1).baseUrl())
                .isEqualTo("https://atlas.test:8443");
    }

    @Test
    void losTimeoutsDebenSerPositivosYLosReintentosEstarAcotados() {
        assertThatThrownBy(() -> new AtlasProperties("http://a.test", Duration.ZERO, D, 1))
                .hasMessageContaining("timeouts");
        assertThatThrownBy(() -> new AtlasProperties("http://a.test", D, Duration.ofSeconds(-1), 1))
                .hasMessageContaining("timeouts");
        assertThatThrownBy(() -> new AtlasProperties("http://a.test", D, D, -1)).hasMessageContaining("reintentos");
        assertThatThrownBy(() -> new CatedraProperties("http://a.test", "t", D, D, 6))
                .hasMessageContaining("sin ciclos ilimitados");
        assertThat(new CatedraProperties("http://a.test", "t", D, D, 0).maxRetries()).isZero();
    }

    @Test
    void elRangoMaximoDeLaDisponibilidadEstaAcotado() {
        var zona = ZoneId.of("America/Argentina/Buenos_Aires");
        assertThatThrownBy(() -> new AvailabilityProperties(zona, 0)).hasMessageContaining("entre 1 y 366");
        assertThatThrownBy(() -> new AvailabilityProperties(zona, 367)).hasMessageContaining("entre 1 y 366");
        assertThat(new AvailabilityProperties(zona, 31).maxRangeDays()).isEqualTo(31);
    }
}
