package ar.edu.um.cronos.adapters.out.rest;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.domain.Occupancy;
import ar.edu.um.cronos.domain.OccupancyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El adaptador de la cátedra contra HTTP real: la consulta que arma, cómo lee las ocupaciones y sus fallas. */
class CatedraOccupancyClientTest {

    private static final String TOKEN_TECNICO = "jwt-tecnico-de-la-catedra-que-no-debe-filtrarse";
    private static final LocalDate DESDE = LocalDate.of(2026, 11, 23);
    private static final LocalDate HASTA = LocalDate.of(2026, 11, 29);

    private static final String OCUPACIONES = """
            {"professionalId": 15, "from": "2026-11-23", "to": "2026-11-29",
             "campoNuevo": true,
             "occupancies": [
               {"date": "2026-11-23", "startTime": "09:00:00", "status": "CONFIRMED", "expiresAt": null},
               {"date": "2026-11-23", "startTime": "09:30:00", "status": "HELD", "expiresAt": "2026-11-23T14:44:50Z"}
             ]}""";

    private StubServer catedra;

    @BeforeEach
    void levantar() {
        catedra = new StubServer();
    }

    @AfterEach
    void apagar() {
        catedra.close();
    }

    private CatedraOccupancyClient cliente(long reintentos) {
        RestClient rest = RestClient.builder().baseUrl(catedra.url())
                .defaultHeader("Authorization", "Bearer " + TOKEN_TECNICO).build();
        RetryTemplate retry = new RetryTemplate(RetryPolicy.builder().maxRetries(reintentos)
                .delay(Duration.ofMillis(1)).predicate(RestFailures::isTransient).build());
        return new CatedraOccupancyClient(rest, retry);
    }

    @Test
    void leLasOcupacionesConfirmadasYBloqueadas() {
        catedra.reply(Reply.json(200, OCUPACIONES));

        var ocupaciones = cliente(0).findOccupancies(15, DESDE, HASTA);

        assertThat(ocupaciones).containsExactly(
                new Occupancy(DESDE, LocalTime.of(9, 0), OccupancyStatus.CONFIRMED, null),
                new Occupancy(DESDE, LocalTime.of(9, 30), OccupancyStatus.HELD,
                        Instant.parse("2026-11-23T14:44:50Z")));
    }

    @Test
    void armaLaConsultaConElProfesionalYLasFechasInclusivas() {
        catedra.reply(Reply.json(200, OCUPACIONES));

        cliente(0).findOccupancies(15, DESDE, HASTA);

        var pedido = catedra.requests().get(0);
        assertThat(pedido.method()).isEqualTo("GET");
        assertThat(pedido.uri()).startsWith("/api/appointment-occupancies?")
                .contains("professionalId=15", "from=2026-11-23", "to=2026-11-29");
    }

    @Test
    void usaElTokenTecnicoDelProyecto() {
        catedra.reply(Reply.json(200, OCUPACIONES));

        cliente(0).findOccupancies(15, DESDE, HASTA);

        assertThat(catedra.requests().get(0).header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
    }

    @Test
    void unaListaVaciaEsUnaRespuestaValida() {
        catedra.reply(Reply.json(200,
                "{\"professionalId\":15,\"from\":\"2026-11-23\",\"to\":\"2026-11-29\",\"occupancies\":[]}"));

        assertThat(cliente(0).findOccupancies(15, DESDE, HASTA)).isEmpty();
    }

    @Test
    void siLaCatedraRechazaLaCredencialNoHayDisponibilidadYSeAvisaSinFiltrarElToken() {
        for (int status : new int[]{401, 403}) {
            try (var servidor = new StubServer().reply(Reply.problem(status, "X"));
                 var log = new LogCapture()) {
                var cliente = new CatedraOccupancyClient(
                        RestClient.builder().baseUrl(servidor.url())
                                .defaultHeader("Authorization", "Bearer " + TOKEN_TECNICO).build(),
                        new RetryTemplate(RetryPolicy.builder().maxRetries(1).delay(Duration.ofMillis(1))
                                .predicate(RestFailures::isTransient).build()));

                var error = org.junit.jupiter.api.Assertions.assertThrows(CatedraUnavailableException.class,
                        () -> cliente.findOccupancies(15, DESDE, HASTA));

                assertThat(servidor.requests()).as("HTTP " + status + " no se reintenta").hasSize(1);
                assertThat(log.events()).isPositive();
                assertThat(log.text()).contains("evento=catedra.credencial.rechazada operacion=consultar_ocupaciones profesional=15 estado=")
                        .contains("accion=rotar_token_en_env").doesNotContain(TOKEN_TECNICO);
                assertThat(error.getMessage()).doesNotContain(TOKEN_TECNICO);
            }
        }
    }

    @Test
    void unaFechaInvalidaQueLaCatedraRechazaSeTraduce() {
        catedra.reply(Reply.problem(400, "INVALID_DATE_RANGE"));

        assertThatThrownBy(() -> cliente(1).findOccupancies(15, DESDE, HASTA))
                .isInstanceOf(CatedraUnavailableException.class);
        assertThat(catedra.requests()).hasSize(1);
    }

    @Test
    void un503SeReintentaUnaVezYSiSeRecuperaSigue() {
        catedra.reply(Reply.problem(503, "DOWN"), Reply.json(200, OCUPACIONES));

        assertThat(cliente(1).findOccupancies(15, DESDE, HASTA)).hasSize(2);
        assertThat(catedra.requests()).hasSize(2);
    }

    @Test
    void un503PersistenteNoSeReintentaParaSiempre() {
        catedra.reply(Reply.problem(503, "DOWN"));

        assertThatThrownBy(() -> cliente(2).findOccupancies(15, DESDE, HASTA))
                .isInstanceOf(CatedraUnavailableException.class);
        assertThat(catedra.requests()).hasSize(3);
    }

    @Test
    void un500SeTraduceSinReintentar() {
        catedra.reply(Reply.problem(500, "BOOM"));

        assertThatThrownBy(() -> cliente(2).findOccupancies(15, DESDE, HASTA))
                .isInstanceOf(CatedraUnavailableException.class);
        assertThat(catedra.requests()).hasSize(1);
    }

    @Test
    void unEstadoDeOcupacionDesconocidoNoSeIgnora() {
        // Ofrecer un turno que podría estar tomado es peor que decir que no se puede calcular.
        catedra.reply(Reply.json(200, "{\"occupancies\":[{\"date\":\"2026-11-23\",\"startTime\":\"09:00:00\","
                + "\"status\":\"BLOCKED\",\"expiresAt\":null}]}"));

        assertThatThrownBy(() -> cliente(0).findOccupancies(15, DESDE, HASTA))
                .isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void unaRespuestaIncompletaOIlegibleEsUnaCatedraNoDisponible() {
        String[] respuestas = {
                "",
                "esto no es json",
                "{\"occupancies\":[{\"startTime\":\"09:00:00\",\"status\":\"HELD\"}]}",   // sin fecha
                "{\"occupancies\":[{\"date\":\"2026-11-23\",\"status\":\"HELD\"}]}",       // sin hora
                "{\"occupancies\":[{\"date\":\"2026-11-23\",\"startTime\":\"09:00:00\"}]}" // sin estado
        };
        for (String respuesta : respuestas) {
            try (var servidor = new StubServer().reply(Reply.json(200, respuesta))) {
                var cliente = new CatedraOccupancyClient(RestClient.builder().baseUrl(servidor.url()).build(),
                        new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()));

                assertThatThrownBy(() -> cliente.findOccupancies(15, DESDE, HASTA))
                        .as("respuesta: " + respuesta)
                        .isInstanceOf(CatedraUnavailableException.class);
            }
        }
    }
}
