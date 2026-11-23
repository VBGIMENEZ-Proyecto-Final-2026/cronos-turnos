package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent;
import ar.edu.um.cronos.config.CatedraKafkaProperties;
import ar.edu.um.cronos.domain.ReservationEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.BackOffExecution;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Qué hace el consumidor con cada tipo de falla: cuántos intentos, qué se estaciona y qué se escribe en el log. */
class ConsumerErrorPolicyTest {

    private static final String JSON = "{\"eventId\":\"ev-1\",\"eventType\":\"AppointmentProcessExpired\","
            + "\"reservationProcessId\":\"process-1\",\"holdId\":\"hold-1\",\"schemaVersion\":1}";

    private final ProcessReservationEvent process = mock(ProcessReservationEvent.class);
    private final ReservationEventParser parser = new ReservationEventParser(JsonMapper.builder().build());
    private final ConsumerErrorPolicy policy = new ConsumerErrorPolicy(new CatedraKafkaProperties.Retry(
            3, 4, Duration.ofMillis(10), 2.0, Duration.ofMillis(40)), parser, process);

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("topic-con-el-group-id", 0, 7L, "process-1", value);
    }

    private static int retries(BackOffExecution execution, int limit) {
        int n = 0;
        while (n < limit && execution.nextBackOff() != BackOffExecution.STOP) {
            n++;
        }
        return n;
    }

    // ---- cuántos intentos -------------------------------------------------------------------------------------

    @Test
    void unProcesoDesconocidoSeReintentaDosVecesTresIntentosEnTotal() {
        var backOff = policy.backOffFor(new UnknownProcessException(null), record(JSON));

        assertThat(retries(backOff.start(), 100)).isEqualTo(2);
    }

    @Test
    void unMensajeQueFallaPorSiMismoSeReintentaTresVecesCuatroIntentosEnTotal() {
        var backOff = policy.backOffFor(new DataIntegrityViolationException("restricción"), record(JSON));

        assertThat(retries(backOff.start(), 100)).isEqualTo(3);
    }

    @Test
    void unaFallaPasajeraDeLaBaseSeReintentaSinLimiteConLaEsperaTopeada() {
        var backOff = policy.backOffFor(new DataAccessResourceFailureException("se cayó"), record(JSON));
        var ejecucion = backOff.start();

        long mayor = 0;
        for (int i = 0; i < 5000; i++) {
            long espera = ejecucion.nextBackOff();
            assertThat(espera).as("intento %d", i).isNotEqualTo(BackOffExecution.STOP);
            mayor = Math.max(mayor, espera);
        }
        assertThat(mayor).as("la espera crece hasta el tope y no más").isEqualTo(40);
    }

    @Test
    void laEsperaCreceEnCadaReintentoHastaElTope() {
        var ejecucion = policy.backOffFor(new DataAccessResourceFailureException("x"), record(JSON)).start();

        assertThat(new long[]{ejecucion.nextBackOff(), ejecucion.nextBackOff(), ejecucion.nextBackOff(),
                ejecucion.nextBackOff()}).containsExactly(10, 20, 40, 40);
    }

    @Test
    void elEnvoltorioDelContenedorNoCambiaLaClasificacion() {
        // El manejador recibe la excepción envuelta; la política mira la causa.
        var envuelta = new ListenerExecutionFailedException("x", new UnknownProcessException(null));
        var backOff = policy.backOffFor(KafkaFailures.cause(envuelta), record(JSON));

        assertThat(retries(backOff.start(), 100)).isEqualTo(2);
    }

    // ---- qué se escribe en cada reintento -------------------------------------------------------------------

    @Test
    void cadaReintentoDejaUnaLineaConElNumeroDeIntentoYLaEspera() {
        try (var log = new LogCapture()) {
            var ejecucion = policy.backOffFor(new DataAccessResourceFailureException("x"), record(JSON)).start();
            ejecucion.nextBackOff();
            ejecucion.nextBackOff();
            ejecucion.nextBackOff();

            assertThat(log.eventos("kafka.mensaje.reintento")).extracting(e -> e.getFormattedMessage())
                    .hasSize(3).allSatisfy(m -> assertThat(m).contains("tipo_de_falla=infraestructura_sin_respuesta")
                            .contains("motivo=DataAccessResourceFailureException").contains("particion=0 offset=7"))
                    .satisfies(m -> {
                        assertThat(m.get(0)).contains("intento=2 espera_ms=10");
                        assertThat(m.get(2)).contains("intento=4 espera_ms=40");
                    });
        }
    }

    @Test
    void alAgotarseLosIntentosNoSeEscribeUnaLineaDeReintentoDeMas() {
        try (var log = new LogCapture()) {
            var ejecucion = policy.backOffFor(new UnknownProcessException(null), record(JSON)).start();
            retries(ejecucion, 100);

            assertThat(log.eventos("kafka.mensaje.reintento")).hasSize(2);
        }
    }

    // ---- qué se estaciona al agotarse ------------------------------------------------------------------------

    @Test
    void unProcesoDesconocidoQueNoApareceSeEstacionaConSuMotivo() {
        try (var log = new LogCapture()) {
            policy.recover(record(JSON), new ListenerExecutionFailedException("x", new UnknownProcessException(null)));

            verify(process).park(any(ReservationEvent.ProcessExpired.class), org.mockito.ArgumentMatchers.eq("UNKNOWN_PROCESS"));
            assertThat(log.eventos("kafka.mensaje.agotado")).singleElement().satisfies(e -> {
                assertThat(e.getLevel().toString()).isEqualTo("WARN");
                assertThat(e.getFormattedMessage()).contains("motivo=UNKNOWN_PROCESS")
                        .contains("accion=estacionado_y_offset_confirmado");
            });
        }
    }

    @Test
    void unMensajeQueFallaPorSiMismoSeEstacionaComoFallidoYSeEscribeComoError() {
        var mensajeConDatos = "Failing row contains (Juan, Pérez) Key (event_id)=(ev-1)";
        var falla = new DataIntegrityViolationException(mensajeConDatos, new SQLException(mensajeConDatos, "23514"));
        try (var log = new LogCapture()) {
            policy.recover(record(JSON), new ListenerExecutionFailedException("x", falla));

            verify(process).park(any(ReservationEvent.ProcessExpired.class), org.mockito.ArgumentMatchers.eq("PROCESSING_FAILED"));
            var linea = log.eventos("kafka.mensaje.agotado").get(0);
            assertThat(linea.getLevel().toString()).isEqualTo("ERROR");
            assertThat(linea.getFormattedMessage()).contains("motivo=PROCESSING_FAILED")
                    .contains("excepcion=DataIntegrityViolationException").contains("causa_raiz=SQLException");
            assertThat(linea.getThrowableProxy()).as("sin traza: puede traer la fila con los datos").isNull();
            assertThat(log.text()).doesNotContain("Juan").doesNotContain("Pérez").doesNotContain("topic-con-el-group-id");
        }
    }

    @Test
    void unMensajeIlegibleONuloNoSeEstacionaPeroTampocoRompeLaRecuperacion() {
        policy.recover(record("esto no es json"), new IllegalStateException("x"));
        policy.recover(record(null), new IllegalStateException("x"));

        verify(process, never()).park(any(ReservationEvent.class), anyString());
    }

    @Test
    void lasLineasDeLaRecuperacionLlevanElIdentificadorDelMensaje() {
        try (var log = new LogCapture()) {
            policy.recover(record(JSON), new IllegalStateException("x"));

            assertThat(log.eventos("kafka.mensaje.agotado")).singleElement().satisfies(e ->
                    assertThat(e.getMDCPropertyMap()).containsEntry("requestId", "kafka-0-7"));
        }
    }
}
