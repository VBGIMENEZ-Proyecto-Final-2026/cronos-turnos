package ar.edu.um.cronos.adapters.out.rest;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.application.port.out.CatedraFailure;
import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El cliente de holds contra HTTP real: qué manda, cómo lee las respuestas y cuándo "rechazó" y cuándo "no se sabe". */
class CatedraHoldClientTest {

    private static final String TOKEN_TECNICO = "jwt-tecnico-que-no-debe-filtrarse";
    private static final LocalDate DIA = LocalDate.of(2026, 11, 24);
    private static final LocalTime HORA = LocalTime.of(14, 30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StubServer catedra;

    @BeforeEach
    void levantar() {
        catedra = new StubServer();
    }

    @AfterEach
    void apagar() {
        catedra.close();
    }

    private CatedraHoldClient cliente() {
        return new CatedraHoldClient(RestClient.builder().baseUrl(catedra.url())
                .defaultHeader("Authorization", "Bearer " + TOKEN_TECNICO).build());
    }

    private static final String HOLD = "{\"holdId\":\"hold-6c0c2f41\",\"reservationProcessId\":\"process-a92b47d0\","
            + "\"status\":\"HELD\",\"expiresAt\":\"2026-11-24T14:44:50Z\",\"campoNuevo\":1}";
    private static final String CONFIRMADO = "{\"reservationProcessId\":\"process-a92b47d0\","
            + "\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"2026-11-24T14:45:50Z\"}";

    // ---- crear el hold ---------------------------------------------------------------------------------------

    @Test
    void creaElHoldConElCuerpoYLaCabeceraQueEsperaLaCatedra() throws Exception {
        catedra.reply(Reply.json(201, HOLD));

        var hold = cliente().createHold(218, DIA, HORA, "user-7");

        assertThat(hold.holdId()).isEqualTo("hold-6c0c2f41");
        assertThat(hold.reservationProcessId()).isEqualTo("process-a92b47d0");
        assertThat(hold.expiresAt()).isEqualTo(Instant.parse("2026-11-24T14:44:50Z"));
        var pedido = catedra.requests().get(0);
        assertThat(pedido.method()).isEqualTo("POST");
        assertThat(pedido.uri()).isEqualTo("/api/appointment-holds");
        assertThat(pedido.header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        assertThat(pedido.header("Content-Type")).startsWith("application/json");
        Map<String, Object> cuerpo = JSON.readValue(pedido.body(), Map.class);
        assertThat(cuerpo).containsOnlyKeys("professionalId", "date", "startTime", "externalPatientId");
        assertThat(cuerpo.get("professionalId")).isEqualTo(218);
        assertThat(cuerpo.get("date")).isEqualTo("2026-11-24");
        assertThat(cuerpo.get("startTime")).as("HH:mm, como en la referencia").isEqualTo("14:30");
        assertThat(cuerpo.get("externalPatientId")).isEqualTo("user-7");
    }

    @Test
    void unaRespuestaDeHoldIncompletaEsUnResultadoDesconocidoNoUnExito() {
        String[] respuestas = {"", "no es json", "{}", "{\"holdId\":\"h\"}", "{\"reservationProcessId\":\"p\"}",
                "{\"holdId\":\" \",\"reservationProcessId\":\"p\"}"};
        for (String respuesta : respuestas) {
            try (var servidor = new StubServer().reply(Reply.json(201, respuesta))) {
                var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url()).build());

                assertThatThrownBy(() -> cliente.createHold(218, DIA, HORA, "user-7")).as(respuesta)
                        .isInstanceOf(CatedraUnavailableException.class);
            }
        }
    }

    // ---- confirmar el hold -----------------------------------------------------------------------------------

    @Test
    void confirmaElHoldConLosDatosDelPaciente() throws Exception {
        catedra.reply(Reply.json(202, CONFIRMADO));

        var confirmado = cliente().confirmHold("hold-6c0c2f41", "user-7", "Juan", "Pérez");

        assertThat(confirmado.reservationProcessId()).isEqualTo("process-a92b47d0");
        assertThat(confirmado.expiresAt()).isEqualTo(Instant.parse("2026-11-24T14:45:50Z"));
        var pedido = catedra.requests().get(0);
        assertThat(pedido.method()).isEqualTo("POST");
        assertThat(pedido.uri()).isEqualTo("/api/appointment-holds/hold-6c0c2f41/confirm");
        assertThat(pedido.header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        Map<String, Object> cuerpo = JSON.readValue(pedido.body(), Map.class);
        assertThat(cuerpo).containsOnlyKeys("externalPatientId", "patientFirstName", "patientLastName");
        assertThat(cuerpo.get("externalPatientId")).isEqualTo("user-7");
        assertThat(cuerpo.get("patientFirstName")).isEqualTo("Juan");
        assertThat(cuerpo.get("patientLastName")).isEqualTo("Pérez");
    }

    @Test
    void unaConfirmacionIncompletaEsUnResultadoDesconocido() {
        for (String respuesta : new String[]{"", "no es json", "{}"}) {
            try (var servidor = new StubServer().reply(Reply.json(202, respuesta))) {
                var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url()).build());

                assertThatThrownBy(() -> cliente.confirmHold("h", "user-7", "Juan", "Pérez")).as(respuesta)
                        .isInstanceOf(CatedraUnavailableException.class);
            }
        }
    }

    // ---- rechazos: la cátedra respondió y se sabe qué pasó -----------------------------------------------------

    @Test
    void cadaCodigoDeLaMatrizDeErroresSeTraduceASuMotivo() {
        Object[][] casos = {
                {409, "SLOT_ALREADY_HELD", CatedraFailure.SLOT_ALREADY_HELD},
                {409, "SLOT_ALREADY_RESERVED", CatedraFailure.SLOT_ALREADY_RESERVED},
                {409, "RESERVATION_PROCESS_ALREADY_CONFIRMED", CatedraFailure.RESERVATION_PROCESS_ALREADY_CONFIRMED},
                {400, "INVALID_SLOT", CatedraFailure.INVALID_SLOT},
                {400, "PROFESSIONAL_DISABLED", CatedraFailure.PROFESSIONAL_DISABLED},
                {400, "EXTERNAL_PATIENT_MISMATCH", CatedraFailure.EXTERNAL_PATIENT_MISMATCH},
                {400, "VALIDATION_ERROR", CatedraFailure.VALIDATION_ERROR},
                {404, "PROFESSIONAL_NOT_FOUND", CatedraFailure.PROFESSIONAL_NOT_FOUND},
                {404, "HOLD_NOT_FOUND", CatedraFailure.HOLD_NOT_FOUND},
                {404, "HOLD_EXPIRED", CatedraFailure.HOLD_EXPIRED},
                {403, "HOLD_OWNER_MISMATCH", CatedraFailure.HOLD_OWNER_MISMATCH},
        };
        for (Object[] caso : casos) {
            try (var servidor = new StubServer().reply(Reply.problem((int) caso[0], (String) caso[1]))) {
                var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url()).build());

                assertThatThrownBy(() -> cliente.createHold(218, DIA, HORA, "user-7"))
                        .as(caso[0] + " " + caso[1])
                        .isInstanceOfSatisfying(CatedraRejectedException.class,
                                e -> assertThat(e.failure()).isEqualTo(caso[2]));
                assertThatThrownBy(() -> cliente.confirmHold("h", "user-7", "Juan", "Pérez"))
                        .isInstanceOfSatisfying(CatedraRejectedException.class,
                                e -> assertThat(e.failure()).isEqualTo(caso[2]));
            }
        }
    }

    @Test
    void unCodigoDesconocidoOSinCodigoEnUn4xxEsUnRechazoInesperado() {
        for (var respuesta : new Reply[]{Reply.problem(400, "CODIGO_NUEVO"), Reply.json(404, "{}"),
                Reply.json(409, "no es json"), Reply.problem(429, "RATE")}) {
            try (var servidor = new StubServer().reply(respuesta)) {
                var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url()).build());

                assertThatThrownBy(() -> cliente.createHold(218, DIA, HORA, "user-7"))
                        .isInstanceOfSatisfying(CatedraRejectedException.class,
                                e -> assertThat(e.failure()).isEqualTo(CatedraFailure.UNEXPECTED));
            }
        }
    }

    @Test
    void laDecisionNoDependeDelTextoDelDetalle() {
        // Un detail engañoso: manda el code y el status, no el texto.
        try (var servidor = new StubServer().reply(new Reply(409, "application/problem+json",
                "{\"status\":409,\"code\":\"SLOT_ALREADY_HELD\",\"detail\":\"HOLD_EXPIRED INVALID_SLOT\"}", 0, null))) {
            var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url()).build());

            assertThatThrownBy(() -> cliente.createHold(218, DIA, HORA, "user-7"))
                    .isInstanceOfSatisfying(CatedraRejectedException.class,
                            e -> assertThat(e.failure()).isEqualTo(CatedraFailure.SLOT_ALREADY_HELD));
        }
    }

    // ---- no se sabe qué pasó ---------------------------------------------------------------------------------

    @Test
    void unErrorDeServidorUnaCredencialRechazadaOUn403SinCodigoNoSonUnRechazoSinoUnResultadoDesconocido() {
        for (var respuesta : new Reply[]{Reply.problem(500, "BOOM"), Reply.problem(503, "DOWN"),
                Reply.problem(401, "X"), Reply.json(403, "{}"), Reply.problem(403, "STUDENT_INTEGRATION_NOT_FOUND")}) {
            try (var servidor = new StubServer().reply(respuesta)) {
                var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url()).build());

                assertThatThrownBy(() -> cliente.createHold(218, DIA, HORA, "user-7"))
                        .as("HTTP " + respuesta.status())
                        .isInstanceOf(CatedraUnavailableException.class);
                assertThatThrownBy(() -> cliente.confirmHold("h", "user-7", "Juan", "Pérez"))
                        .isInstanceOf(CatedraUnavailableException.class);
            }
        }
    }

    @Test
    void sinConexionNoSeSabeQuePaso() throws Exception {
        int puertoCerrado;
        try (var socket = new java.net.ServerSocket(0)) {
            puertoCerrado = socket.getLocalPort();
        }
        var cliente = new CatedraHoldClient(RestClient.builder().baseUrl("http://127.0.0.1:" + puertoCerrado).build());

        assertThatThrownBy(() -> cliente.createHold(218, DIA, HORA, "user-7"))
                .isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void nuncaReintentaPorSuCuenta() {
        catedra.reply(Reply.problem(503, "DOWN"));

        assertThatThrownBy(() -> cliente().createHold(218, DIA, HORA, "user-7"))
                .isInstanceOf(CatedraUnavailableException.class);
        assertThatThrownBy(() -> cliente().confirmHold("h", "user-7", "Juan", "Pérez"))
                .isInstanceOf(CatedraUnavailableException.class);

        assertThat(catedra.requests()).as("un pedido por llamada: ningún reintento").hasSize(2);
    }

    // ---- secretos --------------------------------------------------------------------------------------------

    @Test
    void ningunTokenNiNombreDelPacienteApareceEnLosLogsNiEnLosMensajes() {
        for (var respuesta : new Reply[]{Reply.problem(401, "X"), Reply.problem(409, "SLOT_ALREADY_HELD"),
                Reply.problem(500, "BOOM")}) {
            try (var servidor = new StubServer().reply(respuesta); var log = new LogCapture()) {
                var cliente = new CatedraHoldClient(RestClient.builder().baseUrl(servidor.url())
                        .defaultHeader("Authorization", "Bearer " + TOKEN_TECNICO).build());

                var error1 = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> cliente.confirmHold("hold-1", "user-7", "NombreSecreto", "ApellidoSecreto"));

                assertThat(log.events()).as("tiene que haber algo que mirar").isPositive();
                assertThat(log.text()).doesNotContain(TOKEN_TECNICO, "NombreSecreto", "ApellidoSecreto");
                assertThat(error1.getMessage()).doesNotContain(TOKEN_TECNICO, "NombreSecreto", "ApellidoSecreto");
            }
        }
    }

    // ---- cancelar una reserva confirmada ----------------------------------------------------------------------

    private static final String CANCELADA = "{\"reservationId\":8251,\"reservationProcessId\":\"process-a92b47d0\","
            + "\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-22T15:10:00Z\",\"campoNuevo\":true}";

    @Test
    void cancelaConElMotivoEnElCuerpoYLeeLaRespuestaIgnorandoLoQueNoConoce() throws Exception {
        catedra.reply(Reply.json(200, CANCELADA));

        var cancelada = cliente().cancel("process-a92b47d0", "Cambio solicitado por el usuario");

        assertThat(cancelada.reservationId()).isEqualTo(8251);
        assertThat(cancelada.cancelledAt()).isEqualTo(Instant.parse("2026-11-22T15:10:00Z"));
        var pedido = catedra.requests().get(0);
        assertThat(pedido.method()).isEqualTo("POST");
        assertThat(pedido.uri()).isEqualTo("/api/appointments/process-a92b47d0/cancel");
        assertThat(pedido.header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        assertThat(pedido.header("Content-Type")).startsWith("application/json");
        Map<String, Object> cuerpo = JSON.readValue(pedido.body(), Map.class);
        assertThat(cuerpo).containsOnlyKeys("reason").containsEntry("reason", "Cambio solicitado por el usuario");
    }

    @Test
    void sinMotivoNoMandaCuerpoPorqueLaReferenciaLoDejaOpcional() {
        catedra.reply(Reply.json(200, CANCELADA));

        cliente().cancel("process-a92b47d0", null);

        var pedido = catedra.requests().get(0);
        assertThat(pedido.uri()).isEqualTo("/api/appointments/process-a92b47d0/cancel");
        assertThat(pedido.body() == null ? "" : pedido.body()).isEmpty();
    }

    @Test
    void elIdentificadorDelProcesoSeCodificaEnLaRutaYNoPuedeCambiarElDestino() {
        catedra.reply(Reply.json(200, "{\"reservationId\":1,\"reservationProcessId\":\"a/../../otra-cosa b\","
                + "\"status\":\"CANCELLED\"}"));

        cliente().cancel("a/../../otra-cosa b", null);

        assertThat(catedra.requests().get(0).uri()).isEqualTo("/api/appointments/a%2F..%2F..%2Fotra-cosa%20b/cancel");
    }

    @Test
    void cadaRechazoDeLaCancelacionSeTraduceASuMotivoSinRepetirNada() {
        var casos = Map.of(
                404, "RESERVATION_NOT_FOUND",
                403, "RESERVATION_OWNER_MISMATCH",
                409, "RESERVATION_NOT_CANCELLABLE",
                400, "VALIDATION_ERROR");
        for (var caso : casos.entrySet()) {
            catedra.reset();
            catedra.reply(Reply.problem(caso.getKey(), caso.getValue()));

            assertThatThrownBy(() -> cliente().cancel("process-a92b47d0", null))
                    .as("HTTP %d %s", caso.getKey(), caso.getValue())
                    .isInstanceOfSatisfying(CatedraRejectedException.class, e ->
                            assertThat(e.failure()).isEqualTo(CatedraFailure.valueOf(caso.getValue())));
        }
    }

    @Test
    void unErrorDeServidorUnaCredencialRechazadaOUnTimeoutNoSonUnRechazoSinoUnResultadoDesconocido() {
        for (Reply respuesta : new Reply[]{Reply.problem(503, "X"), Reply.problem(500, "X"), Reply.problem(401, "X"),
                Reply.problem(403, "STUDENT_INTEGRATION_NOT_FOUND"), Reply.problem(404, "RUTA_QUE_NO_ES")}) {
            catedra.reset();
            catedra.reply(respuesta);

            if (respuesta.status() == 404) {
                // Un 404 con un código que no es el de la matriz es un rechazo inesperado, no "no se sabe".
                assertThatThrownBy(() -> cliente().cancel("p", null)).isInstanceOf(CatedraRejectedException.class);
            } else {
                assertThatThrownBy(() -> cliente().cancel("p", null)).as("HTTP %d", respuesta.status())
                        .isInstanceOf(CatedraUnavailableException.class);
            }
        }
    }

    @Test
    void unaRespuestaDeCancelacionInvalidaEsUnResultadoDesconocidoNoUnExito() {
        for (String cuerpo : new String[]{"{}", "{\"reservationId\":8251,\"reservationProcessId\":\"process-a92b47d0\","
                + "\"status\":\"CONFIRMED\"}", "{\"reservationProcessId\":\"process-a92b47d0\",\"status\":\"CANCELLED\"}",
                "{\"reservationId\":8251,\"reservationProcessId\":\"otro-proceso\",\"status\":\"CANCELLED\"}", "no es json"}) {
            catedra.reset();
            catedra.reply(Reply.json(200, cuerpo));

            assertThatThrownBy(() -> cliente().cancel("process-a92b47d0", null)).as(cuerpo)
                    .isInstanceOf(CatedraUnavailableException.class);
        }
    }

    @Test
    void sinConexionNoSeSabeSiSeCancelo() throws Exception {
        var cliente = cliente();
        catedra.close();

        assertThatThrownBy(() -> cliente.cancel("process-a92b47d0", null)).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void laCancelacionNuncaSeReintentaPorSuCuenta() {
        catedra.reply(Reply.problem(503, "X"), Reply.json(200, CANCELADA));

        assertThatThrownBy(() -> cliente().cancel("process-a92b47d0", null)).isInstanceOf(CatedraUnavailableException.class);

        assertThat(catedra.requests()).as("quien llama decide si repetir").hasSize(1);
    }

    @Test
    void niElTokenNiElMotivoDelUsuarioAparecenEnLosLogsNiEnLosMensajes() {
        var motivo = "Motivo con datos personales de Juan Pérez";
        try (var log = new LogCapture()) {
            catedra.reply(Reply.problem(409, "RESERVATION_NOT_CANCELLABLE"), Reply.problem(503, "X"));
            Throwable rechazo = org.junit.jupiter.api.Assertions.assertThrows(CatedraRejectedException.class,
                    () -> cliente().cancel("process-a92b47d0", motivo));
            Throwable incierto = org.junit.jupiter.api.Assertions.assertThrows(CatedraUnavailableException.class,
                    () -> cliente().cancel("process-a92b47d0", motivo));

            assertThat(log.events()).isPositive();
            // Con todo en TRACE Spring escribe los objetos que serializa ("Writing [CancelJson[...]]"): el toString no debe
            // imprimir el motivo. Solo se excluye lo que escribe el cliente HTTP a nivel de cable, que es de la librería.
            assertThat(log.textExcluding("org.apache.hc", "jdk.internal.net.http", "sun.net"))
                    .doesNotContain(TOKEN_TECNICO).doesNotContain("Juan").doesNotContain("Pérez").doesNotContain("datos personales");
            assertThat(rechazo.getMessage() + incierto.getMessage()).doesNotContain(TOKEN_TECNICO).doesNotContain("Juan");
        }
    }
}
