package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent.Outcome;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationEvent;
import ar.edu.um.cronos.domain.ReservationEvent.AppointmentCancelled;
import ar.edu.um.cronos.domain.ReservationEvent.AppointmentConfirmed;
import ar.edu.um.cronos.domain.ReservationEvent.InformationRejected;
import ar.edu.um.cronos.domain.ReservationEvent.InformationRequested;
import ar.edu.um.cronos.domain.ReservationEvent.ProcessExpired;
import ar.edu.um.cronos.domain.ReservationEvent.ProcessInvalid;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ar.edu.um.cronos.domain.ReservationStatus.CANCELLED;
import static ar.edu.um.cronos.domain.ReservationStatus.CONFIRMED;
import static ar.edu.um.cronos.domain.ReservationStatus.EXPIRED;
import static ar.edu.um.cronos.domain.ReservationStatus.HELD;
import static ar.edu.um.cronos.domain.ReservationStatus.INVALID;
import static ar.edu.um.cronos.domain.ReservationStatus.WAITING_FOR_PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aplicar lo que la cátedra informa por Kafka, contra un PostgreSQL real: cada fila de la tabla de transiciones del plan
 * (sección 3), el duplicado, el evento tardío, el proceso desconocido, la atomicidad y dos eventos iguales a la vez. No
 * toca Kafka: los eventos se arman a mano.
 */
@SpringBootTest
@IntegrationTestSupport
class ProcessReservationEventTest {

    private static final Instant T0 = Instant.parse("2026-11-21T15:00:00Z");
    private static final Instant VENCE = Instant.parse("2026-11-24T15:00:00Z");
    private static final LocalDate DIA = LocalDate.of(2026, 11, 24);

    @Autowired
    ProcessReservationEvent caso;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private long ana;
    private int seq;

    @BeforeEach
    void preparar() {
        limpiar();
        ana = users.save(User.register("ana", "$2a$10$hash", "Ana", "Gómez", null, null, "es", T0)).id();
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM unmatched_event");
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------

    /** Un proceso de ana en el estado pedido, con su hold y su proceso de la cátedra ({@code process-N}, {@code hold-N}). */
    private Reservation proceso(ReservationStatus estado) {
        seq++;
        var turno = new Slot(DIA.plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0));
        var r = reservations.save(Reservation.start(ana, 218, "Romina", "Acosta", turno, "Juan", "Pérez", T0));
        r = reservations.save(r.held("hold-" + seq, "process-" + seq, VENCE, T0.plusSeconds(1)));
        if (estado == HELD) {
            return r;
        }
        if (estado == EXPIRED) {
            return reservations.save(r.expired(T0.plusSeconds(2)));
        }
        r = reservations.save(r.waitingForPhone(VENCE, T0.plusSeconds(2)));
        if (estado == WAITING_FOR_PHONE) {
            return r;
        }
        r = reservations.save(r.confirmed(9000 + seq, T0.plusSeconds(3)));
        return estado == CONFIRMED ? r : reservations.save(r.cancelled(T0.plusSeconds(4)));
    }

    private Reservation leer(Reservation r) {
        return reservations.findById(r.id()).orElseThrow();
    }

    private static InformationRequested pedido(Reservation r, String eventId) {
        return new InformationRequested(eventId, r.reservationProcessId(), r.holdId());
    }

    private static AppointmentConfirmed confirmada(Reservation r, String eventId, long reservationId) {
        return new AppointmentConfirmed(eventId, r.reservationProcessId(), r.holdId(), reservationId);
    }

    private int procesados() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class);
    }

    private int estacionados() {
        return jdbc.queryForObject("SELECT count(*) FROM unmatched_event", Integer.class);
    }

    // ---- AdditionalInformationRequested ---------------------------------------------------------------------

    @Test
    void elPedidoDeTelefonoGuardaSuEventIdYNoCambiaElEstado() {
        for (var estado : List.of(HELD, WAITING_FOR_PHONE)) {
            var r = proceso(estado);

            assertThat(caso.execute(pedido(r, "request-event-" + r.id()))).as(estado.name()).isEqualTo(Outcome.APPLIED);

            var despues = leer(r);
            assertThat(despues.status()).as(estado.name()).isEqualTo(estado);
            assertThat(despues.requestEventId()).isEqualTo("request-event-" + r.id());
            assertThat(despues.version()).isEqualTo(r.version() + 1);
        }
        assertThat(procesados()).isEqualTo(2);
    }

    // ---- AdditionalInformationRejected ----------------------------------------------------------------------

    @Test
    void unRechazoGuardaElMotivoYElProcesoSigueEsperandoYSePuedeConfirmarDespues() {
        var r = proceso(WAITING_FOR_PHONE);
        caso.execute(pedido(r, "request-event-1"));

        var rechazo = new InformationRejected("rejection-event-1", r.reservationProcessId(), r.holdId(),
                "INVALID_PHONE_NUMBER");
        assertThat(caso.execute(rechazo)).isEqualTo(Outcome.APPLIED);

        var tras = leer(r);
        assertThat(tras.status()).as("un rechazo no termina el proceso").isEqualTo(WAITING_FOR_PHONE);
        assertThat(tras.lastRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(tras.requestEventId()).isEqualTo("request-event-1");

        // Otro teléfono, otro eventId: el proceso todavía puede confirmarse.
        assertThat(caso.execute(confirmada(r, "confirmation-event-1", 8251))).isEqualTo(Outcome.APPLIED);
        assertThat(leer(r).status()).isEqualTo(CONFIRMED);
    }

    // ---- AppointmentConfirmed -------------------------------------------------------------------------------

    @Test
    void laConfirmacionLlevaElProcesoAConfirmedYGuardaLaReservaDeLaCatedra() {
        var r = proceso(WAITING_FOR_PHONE);

        assertThat(caso.execute(confirmada(r, "confirmation-event-1", 8251))).isEqualTo(Outcome.APPLIED);

        var tras = leer(r);
        assertThat(tras.status()).isEqualTo(CONFIRMED);
        assertThat(tras.catedraReservationId()).isEqualTo(8251L);
        assertThat(tras.expiresAt()).as("el resto del proceso queda como estaba").isEqualTo(r.expiresAt());
    }

    @Test
    void siLaConfirmacionLlegaConElProcesoEnHeldPasaPorWaitingForPhoneYLlegaAConfirmed() {
        var r = proceso(HELD);

        assertThat(caso.execute(confirmada(r, "confirmation-event-1", 8251))).isEqualTo(Outcome.APPLIED);

        var tras = leer(r);
        assertThat(tras.status()).isEqualTo(CONFIRMED);
        assertThat(tras.catedraReservationId()).isEqualTo(8251L);
        assertThat(tras.expiresAt()).as("se conserva el vencimiento del hold").isEqualTo(VENCE);
    }

    @Test
    void unaConfirmacionDeOtraReservaSobreUnProcesoYaConfirmadoNoLoCambia() {
        var r = proceso(CONFIRMED);

        assertThat(caso.execute(confirmada(r, "confirmation-event-2", 12345))).isEqualTo(Outcome.IGNORED);

        assertThat(leer(r).catedraReservationId()).isEqualTo(r.catedraReservationId());
        assertThat(procesados()).isEqualTo(1);
    }

    // ---- AppointmentProcessExpired / Invalid ----------------------------------------------------------------

    @Test
    void elVencimientoCierraElProcesoEnHeldYEnWaitingForPhone() {
        for (var estado : List.of(HELD, WAITING_FOR_PHONE)) {
            var r = proceso(estado);

            assertThat(caso.execute(new ProcessExpired("expired-" + r.id(), r.reservationProcessId(), r.holdId())))
                    .as(estado.name()).isEqualTo(Outcome.APPLIED);

            assertThat(leer(r).status()).isEqualTo(EXPIRED);
        }
    }

    @Test
    void unProcesoInvalidoGuardaSuMotivoComoCodigo() {
        var r = proceso(WAITING_FOR_PHONE);

        assertThat(caso.execute(new ProcessInvalid("invalid-1", r.reservationProcessId(), r.holdId(),
                "REQUEST_EVENT_MISMATCH"))).isEqualTo(Outcome.APPLIED);

        var tras = leer(r);
        assertThat(tras.status()).isEqualTo(INVALID);
        assertThat(tras.failureCode()).isEqualTo("REQUEST_EVENT_MISMATCH");
    }

    @Test
    void unaConfirmadaNoVenceNiSeInvalidaPorUnEventoTardio() {
        var r = proceso(CONFIRMED);

        assertThat(caso.execute(new ProcessExpired("expired-1", r.reservationProcessId(), r.holdId())))
                .isEqualTo(Outcome.IGNORED);
        assertThat(caso.execute(new ProcessInvalid("invalid-1", r.reservationProcessId(), r.holdId(), "X")))
                .isEqualTo(Outcome.IGNORED);

        assertThat(leer(r).status()).isEqualTo(CONFIRMED);
    }

    // ---- AppointmentCancelled -------------------------------------------------------------------------------

    @Test
    void laCancelacionSoloAplicaAUnaReservaConfirmada() {
        var confirmada = proceso(CONFIRMED);
        var esperando = proceso(WAITING_FOR_PHONE);

        assertThat(caso.execute(new AppointmentCancelled("cancel-1", confirmada.reservationProcessId(),
                confirmada.holdId()))).isEqualTo(Outcome.APPLIED);
        assertThat(caso.execute(new AppointmentCancelled("cancel-2", esperando.reservationProcessId(),
                esperando.holdId()))).as("no estaba confirmada: se ignora").isEqualTo(Outcome.IGNORED);

        assertThat(leer(confirmada).status()).isEqualTo(CANCELLED);
        assertThat(leer(confirmada).catedraReservationId()).isEqualTo(confirmada.catedraReservationId());
        assertThat(leer(esperando).status()).isEqualTo(WAITING_FOR_PHONE);
    }

    // ---- idempotencia ---------------------------------------------------------------------------------------

    @Test
    void elMismoEventoDosVecesNoRepiteNingunEfecto() {
        var r = proceso(WAITING_FOR_PHONE);
        var evento = confirmada(r, "confirmation-event-1", 8251);

        assertThat(caso.execute(evento)).isEqualTo(Outcome.APPLIED);
        var trasElPrimero = leer(r);
        assertThat(caso.execute(evento)).isEqualTo(Outcome.DUPLICATE);
        assertThat(caso.execute(evento)).isEqualTo(Outcome.DUPLICATE);

        assertThat(leer(r)).as("ni el estado ni la versión ni la hora de cambio se tocan").isEqualTo(trasElPrimero);
        assertThat(procesados()).isEqualTo(1);
    }

    @Test
    void unDuplicadoDeUnEventoIgnoradoTambienSeReconoce() {
        var r = proceso(CONFIRMED);
        var tardio = new ProcessExpired("expired-1", r.reservationProcessId(), r.holdId());

        assertThat(caso.execute(tardio)).isEqualTo(Outcome.IGNORED);
        assertThat(caso.execute(tardio)).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void elMismoEventIdEsElMismoEventoAunqueCambieElTipo() {
        // La clave de idempotencia es el eventId (referencia 15.1): no se aplica dos veces bajo otro tipo.
        var r = proceso(WAITING_FOR_PHONE);
        assertThat(caso.execute(pedido(r, "evento-repetido"))).isEqualTo(Outcome.APPLIED);

        assertThat(caso.execute(confirmada(r, "evento-repetido", 8251))).isEqualTo(Outcome.DUPLICATE);

        assertThat(leer(r).status()).isEqualTo(WAITING_FOR_PHONE);
    }

    // ---- un estado final nunca retrocede --------------------------------------------------------------------

    @Test
    void unEventoTardioDespuesDeUnEstadoFinalNoCambiaNadaPeroQuedaRegistrado() {
        for (var final_ : List.of(EXPIRED, CANCELLED)) {
            var r = proceso(final_);
            var antes = leer(r);

            assertThat(caso.execute(confirmada(r, "late-confirm-" + r.id(), 8251))).as(final_.name())
                    .isEqualTo(Outcome.IGNORED);
            assertThat(caso.execute(pedido(r, "late-request-" + r.id()))).isEqualTo(Outcome.IGNORED);
            assertThat(caso.execute(new InformationRejected("late-reject-" + r.id(), r.reservationProcessId(),
                    r.holdId(), "X"))).isEqualTo(Outcome.IGNORED);

            assertThat(leer(r)).isEqualTo(antes);
        }
        assertThat(procesados()).isEqualTo(6);
    }

    @Test
    void unEventoDeOtroHoldNoSeAplica() {
        var r = proceso(WAITING_FOR_PHONE);

        assertThat(caso.execute(new AppointmentConfirmed("confirmation-event-1", r.reservationProcessId(),
                "hold-de-otro", 8251))).isEqualTo(Outcome.IGNORED);

        assertThat(leer(r).status()).isEqualTo(WAITING_FOR_PHONE);
    }

    // ---- proceso desconocido --------------------------------------------------------------------------------

    @Test
    void unEventoDeUnProcesoQueNoExisteNoSeMarcaYSePuedeEstacionarUnaSolaVez() {
        var evento = new ProcessExpired("process-event-viejo", "process-que-no-existe", "hold-viejo");

        assertThat(caso.execute(evento)).isEqualTo(Outcome.UNKNOWN_PROCESS);
        assertThat(caso.execute(evento)).as("sigue siendo desconocido: no quedó marcado").isEqualTo(Outcome.UNKNOWN_PROCESS);
        assertThat(procesados()).isZero();

        caso.park(evento);
        caso.park(evento);

        assertThat(estacionados()).isEqualTo(1);
        var fila = jdbc.queryForMap("SELECT * FROM unmatched_event");
        assertThat(fila).containsEntry("event_id", "process-event-viejo")
                .containsEntry("event_type", "AppointmentProcessExpired")
                .containsEntry("reservation_process_id", "process-que-no-existe")
                .containsEntry("reason", "UNKNOWN_PROCESS");
    }

    @Test
    void unEventoQueGanoLaCarreraContraElGuardadoDelProcesoSeAplicaCuandoApareceElProceso() {
        var evento = new InformationRequested("request-event-1", "process-1", "hold-1");
        assertThat(caso.execute(evento)).isEqualTo(Outcome.UNKNOWN_PROCESS);

        // El proceso se guarda después; el reintento del mismo evento ya lo encuentra.
        var turno = new Slot(DIA, LocalTime.of(14, 30), LocalTime.of(15, 0));
        var r = reservations.save(reservations.save(Reservation.start(ana, 218, "Romina", "Acosta", turno, "Juan",
                "Pérez", T0)).held("hold-1", "process-1", VENCE, T0.plusSeconds(1)));

        assertThat(caso.execute(evento)).isEqualTo(Outcome.APPLIED);
        assertThat(leer(r).requestEventId()).isEqualTo("request-event-1");
    }

    // ---- atomicidad -----------------------------------------------------------------------------------------

    @Test
    void siElGuardadoFallaElEventoNoQuedaMarcadoYSePuedeReintentar() {
        var uno = proceso(WAITING_FOR_PHONE);
        var otro = proceso(WAITING_FOR_PHONE);
        assertThat(caso.execute(confirmada(uno, "confirmation-event-1", 8251))).isEqualTo(Outcome.APPLIED);
        int antes = procesados();

        // La base no deja que dos procesos tengan la misma reserva de la cátedra: el guardado falla DESPUÉS de marcar.
        var conflictivo = confirmada(otro, "confirmation-event-2", 8251);
        assertThatThrownBy(() -> caso.execute(conflictivo)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(procesados()).as("el marcado se deshizo junto con el cambio").isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = 'confirmation-event-2'",
                Integer.class)).isZero();
        assertThat(leer(otro).status()).isEqualTo(WAITING_FOR_PHONE);
        // Con la causa resuelta, el mismo evento se aplica (no quedó como "ya procesado").
        assertThat(caso.execute(confirmada(otro, "confirmation-event-2", 8252))).isEqualTo(Outcome.APPLIED);
    }

    // ---- concurrencia ---------------------------------------------------------------------------------------

    @Test
    void dosEventosIgualesALaVezSeAplicanUnaSolaVez() throws Exception {
        var r = proceso(WAITING_FOR_PHONE);
        var evento = confirmada(r, "confirmation-event-1", 8251);
        int hilos = 8;
        var pool = Executors.newFixedThreadPool(hilos);
        var listos = new CountDownLatch(hilos);
        var arranque = new CountDownLatch(1);
        try {
            List<Future<Outcome>> resultados = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                resultados.add(pool.submit(() -> {
                    listos.countDown();
                    arranque.await();
                    return caso.execute(evento);
                }));
            }
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            arranque.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (var f : resultados) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(o -> o == Outcome.APPLIED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == Outcome.DUPLICATE).hasSize(hilos - 1);
        } finally {
            pool.shutdownNow();
        }

        var tras = leer(r);
        assertThat(tras.status()).isEqualTo(CONFIRMED);
        assertThat(tras.version()).as("el proceso cambió una sola vez").isEqualTo(r.version() + 1);
        assertThat(procesados()).isEqualTo(1);
    }

    // ---- lo que queda en el log -----------------------------------------------------------------------------

    @Test
    void cadaResultadoDejaSuEventoEnElLogSinDatosPersonales() {
        var r = proceso(WAITING_FOR_PHONE);
        var evento = confirmada(r, "confirmation-event-1", 8251);
        try (var log = new LogCapture()) {
            caso.execute(evento);
            caso.execute(evento);
            caso.execute(new ProcessExpired("expired-1", r.reservationProcessId(), r.holdId()));
            var desconocido = new ProcessExpired("expired-2", "process-que-no-existe", "hold-x");
            caso.execute(desconocido);
            caso.park(desconocido);

            assertThat(log.eventos("kafka.evento.aplicado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("id_evento=confirmation-event-1")
                            .contains("tipo=AppointmentConfirmed").contains("proceso=" + r.reservationProcessId())
                            .contains("estado_anterior=WAITING_FOR_PHONE estado_nuevo=CONFIRMED"));
            assertThat(log.eventos("kafka.evento.duplicado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("id_evento=confirmation-event-1")
                            .contains("accion=sin_efectos"));
            assertThat(log.eventos("kafka.evento.ignorado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("id_evento=expired-1").contains("estado=CONFIRMED")
                            .contains("motivo=estado_no_corresponde"));
            assertThat(log.eventos("kafka.evento.proceso_desconocido")).hasSize(1);
            assertThat(log.eventos("kafka.evento.estacionado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("accion=estacionado"));

            var lineas = log.list().stream().filter(e -> e.getLoggerName().startsWith("ar.edu.um.cronos"))
                    .map(e -> e.getFormattedMessage()).toList();
            assertThat(lineas).noneMatch(m -> m.contains("Juan") || m.contains("Pérez") || m.contains("Romina"));
        }
    }
}
