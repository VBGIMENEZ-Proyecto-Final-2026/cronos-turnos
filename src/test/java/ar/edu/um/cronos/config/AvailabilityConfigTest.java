package ar.edu.um.cronos.config;

import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.application.port.out.CatalogUnavailableException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los clientes tal como los arma la configuración, contra HTTP real: a quién le mandan qué token, cuánto esperan,
 * cuántas veces reintentan y que no siguen redirecciones (un token no se reenvía a otro destino).
 */
class AvailabilityConfigTest {

    private static final String TOKEN_DEL_USUARIO = "jwt-del-usuario";
    private static final String TOKEN_TECNICO = "jwt-tecnico-de-la-catedra";
    private static final LocalDate DIA = LocalDate.of(2026, 11, 23);

    private final AvailabilityConfig config = new AvailabilityConfig();

    private static AtlasProperties atlas(String url, String readTimeout, long reintentos) {
        return new AtlasProperties(url, Duration.ofSeconds(2), Duration.parse(readTimeout), reintentos);
    }

    private static CatedraProperties catedra(String url, String readTimeout, long reintentos) {
        return new CatedraProperties(url, TOKEN_TECNICO, Duration.ofSeconds(2), Duration.parse(readTimeout), reintentos);
    }

    @Test
    void atlasRecibeElTokenDelUsuarioYNuncaElTecnico() {
        try (var atlas = new StubServer().reply(Reply.json(200, "{\"id\":7,\"enabled\":true,\"schedules\":[]}"))) {
            config.catalogClient(atlas(atlas.url(), "PT2S", 0)).findAgenda(7, TOKEN_DEL_USUARIO);

            var autorizacion = atlas.requests().get(0).header("Authorization");
            assertThat(autorizacion).isEqualTo("Bearer " + TOKEN_DEL_USUARIO).doesNotContain(TOKEN_TECNICO);
        }
    }

    @Test
    void laCatedraRecibeElTokenTecnicoYNuncaElDelUsuario() {
        try (var catedra = new StubServer().reply(Reply.json(200, "{\"occupancies\":[]}"))) {
            config.occupancyClient(catedra(catedra.url(), "PT2S", 0)).findOccupancies(7, DIA, DIA);

            var autorizacion = catedra.requests().get(0).header("Authorization");
            assertThat(autorizacion).isEqualTo("Bearer " + TOKEN_TECNICO).doesNotContain(TOKEN_DEL_USUARIO);
        }
    }

    @Test
    void unaRespuestaLentaCortaPorTimeoutEnLugarDeColgarse() {
        try (var atlas = new StubServer().reply(Reply.json(200, "{}").delayed(1500))) {
            long inicio = System.nanoTime();

            assertThatThrownBy(() -> config.catalogClient(atlas(atlas.url(), "PT0.3S", 0)).findAgenda(7, "t"))
                    .isInstanceOf(CatalogUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofMillis(1200));
        }
    }

    @Test
    void elTimeoutSeReintentaSoloLasVecesConfiguradas() {
        try (var catedra = new StubServer().reply(Reply.json(200, "{}").delayed(1500))) {
            assertThatThrownBy(() -> config.occupancyClient(catedra(catedra.url(), "PT0.2S", 1))
                    .findOccupancies(7, DIA, DIA)).isInstanceOf(CatedraUnavailableException.class);

            assertThat(catedra.requests()).as("el primer intento y un reintento").hasSize(2);
        }
    }

    @Test
    void unaRedireccionNoSeSigueYElTokenNoSeReenvia() {
        try (var destino = new StubServer().reply(Reply.json(200, "{\"occupancies\":[]}"));
             var catedra = new StubServer().reply(Reply.redirect(destino.url() + "/robado"))) {
            assertThatThrownBy(() -> config.occupancyClient(catedra(catedra.url(), "PT2S", 0))
                    .findOccupancies(7, DIA, DIA)).isInstanceOf(CatedraUnavailableException.class);

            assertThat(destino.requests()).as("el destino de la redirección nunca recibe nada").isEmpty();
        }
    }

    @Test
    void lasRedireccionesDeAtlasTampocoSeSiguen() {
        try (var destino = new StubServer().reply(Reply.json(200, "{}"));
             var atlas = new StubServer().reply(Reply.redirect(destino.url() + "/robado"))) {
            assertThatThrownBy(() -> config.catalogClient(atlas(atlas.url(), "PT2S", 0))
                    .findAgenda(7, TOKEN_DEL_USUARIO)).isInstanceOf(CatalogUnavailableException.class);

            assertThat(destino.requests()).isEmpty();
        }
    }
}
