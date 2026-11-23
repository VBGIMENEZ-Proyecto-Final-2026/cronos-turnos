package ar.edu.um.cronos.adapters.out.rest;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.application.port.out.CatalogUnavailableException;
import ar.edu.um.cronos.application.port.out.ProfessionalNotFoundException;
import ar.edu.um.cronos.domain.WeeklySchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El adaptador de atlas contra HTTP real: qué manda, cómo lee la respuesta y cómo traduce cada falla. */
class AtlasCatalogClientTest {

    private static final String TOKEN_DEL_USUARIO = "jwt-del-usuario-que-no-debe-filtrarse";

    private static final String DETALLE = """
            {"id": 207, "firstName": "Diego", "lastName": "Alvarez", "enabled": true,
             "category": {"id": 103, "name": "Clinica medica", "enabled": true},
             "campoNuevoQueCronosNoConoce": {"x": 1},
             "schedules": [
               {"id": 311, "dayOfWeek": "MONDAY", "startTime": "18:00:00", "endTime": "21:00:00",
                "slotDurationMinutes": 30, "enabled": true},
               {"id": 312, "dayOfWeek": "WEDNESDAY", "startTime": "12:00", "endTime": "15:00",
                "slotDurationMinutes": 60, "enabled": false}
             ]}""";

    private StubServer atlas;

    @BeforeEach
    void levantar() {
        atlas = new StubServer();
    }

    @AfterEach
    void apagar() {
        atlas.close();
    }

    private AtlasCatalogClient cliente(String baseUrl, long reintentos) {
        RestClient rest = RestClient.builder().baseUrl(baseUrl).build();
        RetryTemplate retry = new RetryTemplate(RetryPolicy.builder().maxRetries(reintentos)
                .delay(Duration.ofMillis(1)).predicate(RestFailures::isTransient).build());
        return new AtlasCatalogClient(rest, retry);
    }

    @Test
    void leElDetalleConSusHorariosYIgnoraLoQueNoConoce() {
        atlas.reply(Reply.json(200, DETALLE));

        var agenda = cliente(atlas.url(), 0).findAgenda(207, TOKEN_DEL_USUARIO);

        assertThat(agenda.professionalId()).isEqualTo(207);
        assertThat(agenda.enabled()).isTrue();
        assertThat(agenda.schedules()).containsExactly(
                new WeeklySchedule(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true),
                new WeeklySchedule(DayOfWeek.WEDNESDAY, LocalTime.of(12, 0), LocalTime.of(15, 0), 60, false));
    }

    @Test
    void reenviaElTokenDelUsuarioTalCualYPideLaRutaCorrecta() {
        atlas.reply(Reply.json(200, DETALLE));

        cliente(atlas.url(), 0).findAgenda(207, TOKEN_DEL_USUARIO);

        assertThat(atlas.requests()).hasSize(1);
        var pedido = atlas.requests().get(0);
        assertThat(pedido.method()).isEqualTo("GET");
        assertThat(pedido.uri()).isEqualTo("/api/professionals/207");
        assertThat(pedido.header("Authorization")).isEqualTo("Bearer " + TOKEN_DEL_USUARIO);
    }

    @Test
    void unProfesionalSinHorariosEsUnaAgendaVacia() {
        atlas.reply(Reply.json(200, "{\"id\":207,\"enabled\":true,\"schedules\":[]}"));

        assertThat(cliente(atlas.url(), 0).findAgenda(207, "t").schedules()).isEmpty();
    }

    @Test
    void unProfesionalDeshabilitadoSeLeeComoDeshabilitado() {
        atlas.reply(Reply.json(200, "{\"id\":207,\"enabled\":false,\"schedules\":[]}"));

        assertThat(cliente(atlas.url(), 0).findAgenda(207, "t").enabled()).isFalse();
    }

    @Test
    void un404EsUnProfesionalInexistente() {
        atlas.reply(Reply.problem(404, "PROFESSIONAL_NOT_FOUND"));

        assertThatThrownBy(() -> cliente(atlas.url(), 1).findAgenda(999, "t"))
                .isInstanceOf(ProfessionalNotFoundException.class);
        assertThat(atlas.requests()).as("un 404 no se reintenta").hasSize(1);
    }

    @Test
    void siAtlasRechazaElTokenElCatalogoNoEstaUtilizable() {
        for (int status : new int[]{401, 403}) {
            try (var servidor = new StubServer().reply(Reply.problem(status, "X"))) {
                assertThatThrownBy(() -> cliente(servidor.url(), 1).findAgenda(207, "t"))
                        .isInstanceOf(CatalogUnavailableException.class);
                assertThat(servidor.requests()).as("HTTP " + status + " no se reintenta").hasSize(1);
            }
        }
    }

    @Test
    void un500SeTraduceSinReintentar() {
        atlas.reply(Reply.problem(500, "BOOM"));

        assertThatThrownBy(() -> cliente(atlas.url(), 2).findAgenda(207, "t"))
                .isInstanceOf(CatalogUnavailableException.class);
        assertThat(atlas.requests()).hasSize(1);
    }

    @Test
    void un503SeReintentaUnaVezYSiSeRecuperaSigue() {
        atlas.reply(Reply.problem(503, "DOWN"), Reply.json(200, DETALLE));

        var agenda = cliente(atlas.url(), 1).findAgenda(207, "t");

        assertThat(agenda.schedules()).hasSize(2);
        assertThat(atlas.requests()).hasSize(2);
    }

    @Test
    void un503PersistenteSeReintentaSoloLoPermitidoYFalla() {
        atlas.reply(Reply.problem(503, "DOWN"));

        assertThatThrownBy(() -> cliente(atlas.url(), 2).findAgenda(207, "t"))
                .isInstanceOf(CatalogUnavailableException.class);
        assertThat(atlas.requests()).as("el primer intento y dos reintentos, ni uno más").hasSize(3);
    }

    @Test
    void sinConexionElCatalogoNoEstaDisponible() throws Exception {
        int puertoCerrado;
        try (var socket = new ServerSocket(0)) {
            puertoCerrado = socket.getLocalPort();
        }

        assertThatThrownBy(() -> cliente("http://127.0.0.1:" + puertoCerrado, 1).findAgenda(207, "t"))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void unaRespuestaQueNoSePuedeUsarEsUnCatalogoNoDisponible() {
        String[] respuestas = {
                "",                                                              // sin cuerpo
                "esto no es json",
                "{\"id\": 1, \"enabled\": true, \"schedules\": []}",             // de otro profesional
                "{\"id\": 207, \"schedules\": []}",                              // sin enabled
                "{\"id\": 207, \"enabled\": true, \"schedules\": [{\"dayOfWeek\": \"MONDAY\"}]}", // horario incompleto
                "{\"id\": 207, \"enabled\": true, \"schedules\": [{\"dayOfWeek\": \"FUNDAY\", \"startTime\": \"09:00\","
                        + " \"endTime\": \"10:00\", \"slotDurationMinutes\": 30, \"enabled\": true}]}"  // día inexistente
        };
        for (String respuesta : respuestas) {
            try (var servidor = new StubServer().reply(Reply.json(200, respuesta))) {
                assertThatThrownBy(() -> cliente(servidor.url(), 0).findAgenda(207, "t"))
                        .as("respuesta: " + respuesta)
                        .isInstanceOf(CatalogUnavailableException.class);
            }
        }
    }

    @Test
    void elTokenDelUsuarioNoApareceEnLosLogsNiEnLosMensajesDeError() {
        atlas.reply(Reply.problem(401, "X"));
        try (var log = new LogCapture()) {
            var error = org.junit.jupiter.api.Assertions.assertThrows(CatalogUnavailableException.class,
                    () -> cliente(atlas.url(), 1).findAgenda(207, TOKEN_DEL_USUARIO));

            assertThat(log.events()).as("tiene que haber algo que mirar").isPositive();
            assertThat(log.text()).doesNotContain(TOKEN_DEL_USUARIO);
            assertThat(error.getMessage()).doesNotContain(TOKEN_DEL_USUARIO);
        }
    }
}
